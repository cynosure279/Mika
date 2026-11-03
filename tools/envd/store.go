package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	envKindWorkspace = "workspace"
	maxCloneBytes    = 1 << 30
	maxCloneEntries  = 100_000
)

var (
	ErrReadOnly         = errors.New("server is read-only")
	ErrNotFound         = errors.New("environment not found")
	ErrAttachNotAllowed = errors.New("attaching existing directories is not enabled")
)

// Environment is one named workspace on the remote host. The lock is not
// serialized: reads/writes take RLock and clone/delete take Lock.
type Environment struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Kind      string    `json:"kind"`
	RootPath  string    `json:"root,omitempty"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`

	lock sync.RWMutex
}

func (e *Environment) Root(dataDir string) string {
	if e.RootPath != "" {
		return e.RootPath
	}
	return filepath.Join(dataDir, "envs", e.ID, "root")
}

func (e *Environment) Attached() bool { return e.RootPath != "" }

// ValidateRoot re-checks the root at use time, so a directory swapped for a
// symlink after creation cannot move the environment outside its allowed area.
func (s *Store) ValidateRoot(e *Environment) error {
	if e.Attached() {
		if !s.rootAllowed(e.RootPath) {
			return errOutside
		}
		return nil
	}
	root, err := canonicalRoot(e.Root(s.dataDir))
	if err != nil {
		return err
	}
	base, err := canonicalRoot(s.dataDir)
	if err != nil {
		return err
	}
	if !within(base, root) {
		return errOutside
	}
	return nil
}

type storeFile struct {
	Version      int            `json:"version"`
	Environments []*Environment `json:"environments"`
}

type Store struct {
	mu           sync.Mutex
	dataDir      string
	metaPath     string
	readOnly     bool
	allowedRoots []string
	execUID      int
	execGID      int
	envs         map[string]*Environment
}

func OpenStore(dataDir string, readOnly bool, allowedRoots ...string) (*Store, error) {
	abs, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Join(abs, "envs"), 0o750); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}
	roots, err := canonicalAllowedRoots(allowedRoots)
	if err != nil {
		return nil, err
	}
	s := &Store{
		dataDir:      abs,
		metaPath:     filepath.Join(abs, "envs.json"),
		readOnly:     readOnly,
		allowedRoots: roots,
		execUID:      -1,
		execGID:      -1,
		envs:         map[string]*Environment{},
	}
	b, err := os.ReadFile(s.metaPath)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, fmt.Errorf("read %s: %w", s.metaPath, err)
	}
	var f storeFile
	if err := json.Unmarshal(b, &f); err != nil {
		return nil, fmt.Errorf("parse %s: %w", s.metaPath, err)
	}
	for _, e := range f.Environments {
		if e == nil || !validID(e.ID) {
			return nil, fmt.Errorf("invalid environment id in %s", s.metaPath)
		}
		if e.Kind == "" {
			e.Kind = envKindWorkspace
		}
		if e.Kind != envKindWorkspace {
			return nil, fmt.Errorf("unsupported environment kind %q in %s", e.Kind, s.metaPath)
		}
		if !validName(e.Name) {
			return nil, fmt.Errorf("invalid environment name for %s", e.ID)
		}
		if _, dup := s.envs[e.ID]; dup {
			return nil, fmt.Errorf("duplicate environment id %s", e.ID)
		}
		if e.RootPath != "" {
			if !filepath.IsAbs(e.RootPath) || !s.rootAllowed(e.RootPath) {
				return nil, fmt.Errorf("attached environment %s is unavailable or outside -allow-root", e.ID)
			}
		} else if err := os.MkdirAll(e.Root(abs), 0o770); err != nil {
			return nil, fmt.Errorf("create environment %s: %w", e.ID, err)
		}
		s.envs[e.ID] = e
		if err := s.ValidateRoot(e); err != nil {
			return nil, fmt.Errorf("environment %s root is unsafe: %w", e.ID, err)
		}
	}
	return s, nil
}

// ConfigureExecUser makes the envs directory setgid for the exec group and
// gives existing environment directories group write access. Command users
// still need the user to grant access to attached directories themselves.
func (s *Store) ConfigureExecUser(uid, gid int) error {
	s.execUID = uid
	s.execGID = gid
	if gid < 0 {
		return nil
	}
	envs := filepath.Join(s.dataDir, "envs")
	if err := os.Chmod(s.dataDir, 0o755); err != nil {
		return fmt.Errorf("chmod %s: %w", s.dataDir, err)
	}
	if err := os.Chown(envs, -1, gid); err != nil {
		return fmt.Errorf("chown %s to group %d: %w", envs, gid, err)
	}
	if err := os.Chmod(envs, 0o2770); err != nil {
		return fmt.Errorf("chmod %s: %w", envs, err)
	}
	for _, e := range s.envs {
		if e.Attached() {
			continue
		}
		_ = filepath.WalkDir(e.Root(s.dataDir), func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.IsDir() {
				_ = os.Chown(path, -1, gid)
				_ = os.Chmod(path, 0o770)
			}
			return nil
		})
	}
	return nil
}

func (s *Store) Create(name string) (*Environment, error) {
	if s.readOnly {
		return nil, ErrReadOnly
	}
	name = strings.TrimSpace(name)
	if !validName(name) {
		return nil, fmt.Errorf("name must be 1-64 printable characters")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.nameTakenLocked(name) {
		return nil, fmt.Errorf("environment name already exists")
	}
	id, err := s.newIDLocked()
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	e := &Environment{ID: id, Name: name, Kind: envKindWorkspace, CreatedAt: now, UpdatedAt: now}
	if err := os.MkdirAll(e.Root(s.dataDir), 0o770); err != nil {
		return nil, err
	}
	s.envs[id] = e
	if err := s.saveLocked(); err != nil {
		delete(s.envs, id)
		_ = os.RemoveAll(filepath.Dir(e.Root(s.dataDir)))
		return nil, err
	}
	return e, nil
}

// CreateAttached mounts an existing directory that lives under one of the
// -allow-root directories. It does not create or copy anything.
func (s *Store) CreateAttached(name, path string) (*Environment, error) {
	if s.readOnly {
		return nil, ErrReadOnly
	}
	name = strings.TrimSpace(name)
	if !validName(name) {
		return nil, fmt.Errorf("name must be 1-64 printable characters")
	}
	if len(s.allowedRoots) == 0 {
		return nil, ErrAttachNotAllowed
	}
	if path == "" {
		return nil, fmt.Errorf("path is required")
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, err
	}
	fi, err := os.Stat(real)
	if err != nil {
		return nil, err
	}
	if !fi.IsDir() {
		return nil, fmt.Errorf("path is not a directory")
	}
	if !s.rootAllowed(real) {
		return nil, errOutside
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.nameTakenLocked(name) {
		return nil, fmt.Errorf("environment name already exists")
	}
	id, err := s.newIDLocked()
	if err != nil {
		return nil, err
	}
	now := time.Now().UTC()
	e := &Environment{ID: id, Name: name, Kind: envKindWorkspace, RootPath: real, CreatedAt: now, UpdatedAt: now}
	s.envs[id] = e
	if err := s.saveLocked(); err != nil {
		delete(s.envs, id)
		return nil, err
	}
	return e, nil
}

func (s *Store) Clone(sourceID, name string) (*Environment, error) {
	if s.readOnly {
		return nil, ErrReadOnly
	}
	name = strings.TrimSpace(name)
	if !validName(name) {
		return nil, fmt.Errorf("name must be 1-64 printable characters")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	src := s.envs[sourceID]
	if src == nil {
		return nil, ErrNotFound
	}
	if s.nameTakenLocked(name) {
		return nil, fmt.Errorf("environment name already exists")
	}
	id, err := s.newIDLocked()
	if err != nil {
		return nil, err
	}

	// Block commands in the source while copying, so the copy is consistent.
	src.lock.Lock()
	defer src.lock.Unlock()
	if err := s.ValidateRoot(src); err != nil {
		return nil, err
	}

	dstRoot := filepath.Join(s.dataDir, "envs", id, "root")
	if err := os.MkdirAll(dstRoot, 0o770); err != nil {
		return nil, err
	}
	if err := copyTree(src.Root(s.dataDir), dstRoot); err != nil {
		_ = os.RemoveAll(filepath.Dir(dstRoot))
		return nil, err
	}
	now := time.Now().UTC()
	e := &Environment{ID: id, Name: name, Kind: envKindWorkspace, CreatedAt: now, UpdatedAt: now}
	s.envs[id] = e
	if err := s.saveLocked(); err != nil {
		delete(s.envs, id)
		_ = os.RemoveAll(filepath.Dir(dstRoot))
		return nil, err
	}
	return e, nil
}

func (s *Store) Delete(id, confirm string) error {
	if s.readOnly {
		return ErrReadOnly
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	e := s.envs[id]
	if e == nil {
		return ErrNotFound
	}
	if confirm != id {
		return fmt.Errorf("confirm must equal the environment id")
	}
	e.lock.Lock()
	defer e.lock.Unlock()
	if !e.Attached() {
		if err := os.RemoveAll(filepath.Dir(e.Root(s.dataDir))); err != nil {
			return err
		}
	}
	delete(s.envs, id)
	return s.saveLocked()
}

func (s *Store) Get(id string) (*Environment, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.envs[id]
	return e, ok
}

func (s *Store) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.envs)
}

type envView struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Kind      string    `json:"kind"`
	Attached  bool      `json:"attached"`
	CreatedAt time.Time `json:"createdAt"`
}

func (s *Store) List() []envView {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]envView, 0, len(s.envs))
	for _, e := range s.envs {
		out = append(out, envView{ID: e.ID, Name: e.Name, Kind: e.Kind, Attached: e.Attached(), CreatedAt: e.CreatedAt})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID < out[j].ID
		}
		return out[i].CreatedAt.Before(out[j].CreatedAt)
	})
	return out
}

func (s *Store) nameTakenLocked(name string) bool {
	for _, e := range s.envs {
		if strings.EqualFold(e.Name, name) {
			return true
		}
	}
	return false
}

func (s *Store) newIDLocked() (string, error) {
	for i := 0; i < 100; i++ {
		b := make([]byte, 4)
		if _, err := rand.Read(b); err != nil {
			return "", err
		}
		id := "env-" + hex.EncodeToString(b)
		if _, exists := s.envs[id]; !exists {
			return id, nil
		}
	}
	return "", fmt.Errorf("could not allocate an environment id")
}

func (s *Store) saveLocked() error {
	f := storeFile{Version: 1, Environments: make([]*Environment, 0, len(s.envs))}
	for _, e := range s.envs {
		f.Environments = append(f.Environments, e)
	}
	sort.Slice(f.Environments, func(i, j int) bool { return f.Environments[i].ID < f.Environments[j].ID })
	b, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	tmp := s.metaPath + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.metaPath); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func validID(id string) bool {
	if len(id) != len("env-")+8 || !strings.HasPrefix(id, "env-") {
		return false
	}
	for _, c := range id[len("env-"):] {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

func validName(name string) bool {
	if name == "" || strings.TrimSpace(name) != name || utf8.RuneCountInString(name) > 64 {
		return false
	}
	for _, r := range name {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	return true
}

func canonicalAllowedRoots(raw []string) ([]string, error) {
	out := make([]string, 0, len(raw))
	seen := map[string]bool{}
	for _, value := range raw {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		abs, err := filepath.Abs(value)
		if err != nil {
			return nil, fmt.Errorf("allow-root %s: %w", value, err)
		}
		real, err := filepath.EvalSymlinks(abs)
		if err != nil {
			return nil, fmt.Errorf("allow-root %s: %w", value, err)
		}
		fi, err := os.Stat(real)
		if err != nil {
			return nil, fmt.Errorf("allow-root %s: %w", value, err)
		}
		if !fi.IsDir() {
			return nil, fmt.Errorf("allow-root %s is not a directory", value)
		}
		if !seen[real] {
			out = append(out, real)
			seen[real] = true
		}
	}
	return out, nil
}

func (s *Store) rootAllowed(path string) bool {
	real, err := filepath.EvalSymlinks(path)
	if err != nil {
		return false
	}
	for _, root := range s.allowedRoots {
		if within(root, real) {
			return true
		}
	}
	return false
}

func copyTree(src, dst string) error {
	srcRoot, err := os.OpenRoot(src)
	if err != nil {
		return err
	}
	defer srcRoot.Close()
	dstRoot, err := os.OpenRoot(dst)
	if err != nil {
		return err
	}
	defer dstRoot.Close()

	var total int64
	entries := 0
	return fs.WalkDir(srcRoot.FS(), ".", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if path == "." {
			return nil
		}
		entries++
		if entries > maxCloneEntries {
			return fmt.Errorf("environment has too many entries")
		}
		name := native(path)
		info, err := srcRoot.Lstat(name)
		if err != nil {
			return err
		}
		switch {
		case info.IsDir():
			return dstRoot.MkdirAll(name, 0o770)
		case info.Mode().IsRegular():
			if hasMultipleLinks(info) {
				return fmt.Errorf("refusing to clone a file with multiple hard links: %s", path)
			}
			total += info.Size()
			if total > maxCloneBytes {
				return fmt.Errorf("environment is too large to clone")
			}
			in, err := srcRoot.Open(name)
			if err != nil {
				return err
			}
			defer in.Close()
			out, err := dstRoot.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o660)
			if err != nil {
				return err
			}
			_, copyErr := io.Copy(out, in)
			closeErr := out.Close()
			if copyErr != nil {
				return copyErr
			}
			return closeErr
		case info.Mode()&os.ModeSymlink != 0:
			link, err := srcRoot.Readlink(name)
			if err != nil {
				return err
			}
			if filepath.IsAbs(link) {
				return fmt.Errorf("absolute symlink is not allowed: %s", path)
			}
			target := filepath.Clean(filepath.Join(filepath.Dir(name), link))
			if target == ".." || strings.HasPrefix(target, ".."+string(filepath.Separator)) {
				return fmt.Errorf("symlink escapes the environment: %s", path)
			}
			return dstRoot.Symlink(link, name)
		default:
			// Device nodes, sockets and FIFOs are not copied.
			return nil
		}
	})
}

package main

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

var errOutside = errors.New("path is outside the environment workspace")

const (
	maxReadBytes     = 1 << 20
	maxWriteBytes    = 1 << 20
	defaultReadBytes = 256 << 10
	maxTransferBytes = 1 << 20
	maxSearchFiles   = 5000
	maxSearchEntries = 100_000
	maxSearchBytes   = 32 << 20
	maxListEntries   = 5000
)

type dirEntry struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Size int64  `json:"size"`
}

type searchMatch struct {
	Path string `json:"path"`
	Line int    `json:"line"`
	Text string `json:"text"`
}

func canonicalRoot(root string) (string, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return "", err
	}
	fi, err := os.Stat(real)
	if err != nil {
		return "", err
	}
	if !fi.IsDir() {
		return "", fmt.Errorf("environment root is not a directory")
	}
	return real, nil
}

func cleanRel(p string) (string, error) {
	if strings.IndexByte(p, 0) >= 0 {
		return "", fmt.Errorf("path contains a NUL byte")
	}
	if p == "" {
		p = "."
	}
	if filepath.IsAbs(p) || filepath.VolumeName(p) != "" {
		return "", errOutside
	}
	clean := filepath.Clean(p)
	if clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errOutside
	}
	return clean, nil
}

func within(base, target string) bool {
	rel, err := filepath.Rel(base, target)
	if err != nil {
		return false
	}
	return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)))
}

func native(rel string) string {
	return filepath.FromSlash(rel)
}

// resolveRead is used for exec's cwd; file contents go through os.Root below.
func resolveRead(root, p string) (string, error) {
	rel, err := cleanRel(p)
	if err != nil {
		return "", err
	}
	r, err := os.OpenRoot(root)
	if err != nil {
		return "", err
	}
	defer r.Close()
	f, err := r.Open(native(rel))
	if err != nil {
		return "", err
	}
	defer f.Close()
	if _, err := f.Stat(); err != nil {
		return "", err
	}
	return filepath.Join(root, native(rel)), nil
}

func readTextFile(root, p string, maxBytes int) ([]byte, error) {
	data, err := readAnyFile(root, p, maxBytes)
	if err != nil {
		return nil, err
	}
	if bytes.IndexByte(data, 0) >= 0 {
		return nil, fmt.Errorf("file is binary; use download_file")
	}
	return data, nil
}

func readAnyFile(root, p string, maxBytes int) ([]byte, error) {
	rel, err := cleanRel(p)
	if err != nil {
		return nil, err
	}
	if rel == "." {
		return nil, fmt.Errorf("path is a directory")
	}
	r, err := os.OpenRoot(root)
	if err != nil {
		return nil, err
	}
	defer r.Close()
	f, err := r.Open(native(rel))
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, fmt.Errorf("path is not a regular file")
	}
	if hasMultipleLinks(info) {
		return nil, fmt.Errorf("refusing to read a file with multiple hard links")
	}
	if info.Size() > int64(maxBytes) {
		return nil, fmt.Errorf("file is larger than %d bytes", maxBytes)
	}
	data, err := io.ReadAll(io.LimitReader(f, int64(maxBytes)+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxBytes {
		return nil, fmt.Errorf("file is larger than %d bytes", maxBytes)
	}
	return data, nil
}

func writeTextFile(root, p string, content []byte, appendMode bool, maxBytes int) (int, error) {
	if len(content) > maxBytes {
		return 0, fmt.Errorf("content is larger than %d bytes", maxBytes)
	}
	rel, err := cleanRel(p)
	if err != nil {
		return 0, err
	}
	if rel == "." {
		return 0, fmt.Errorf("path must name a file")
	}
	r, err := os.OpenRoot(root)
	if err != nil {
		return 0, err
	}
	defer r.Close()
	if dir := filepath.Dir(rel); dir != "." {
		if err := r.MkdirAll(native(dir), 0o770); err != nil {
			return 0, err
		}
	}
	if appendMode {
		f, err := r.OpenFile(native(rel), os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o660)
		if err != nil {
			return 0, err
		}
		info, statErr := f.Stat()
		if statErr != nil || !info.Mode().IsRegular() || hasMultipleLinks(info) {
			_ = f.Close()
			if statErr != nil {
				return 0, statErr
			}
			return 0, fmt.Errorf("refusing to append to a non-regular or hard-linked file")
		}
		n, writeErr := f.Write(content)
		closeErr := f.Close()
		if writeErr != nil {
			return n, writeErr
		}
		return n, closeErr
	}

	tmp := filepath.Join(native(filepath.Dir(rel)), ".mika-tmp-"+randomHex(8))
	f, err := r.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o660)
	if err != nil {
		return 0, err
	}
	if _, err := f.Write(content); err != nil {
		_ = f.Close()
		_ = r.Remove(tmp)
		return 0, err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		_ = r.Remove(tmp)
		return 0, err
	}
	if err := f.Close(); err != nil {
		_ = r.Remove(tmp)
		return 0, err
	}
	if err := r.Rename(tmp, native(rel)); err != nil {
		_ = r.Remove(tmp)
		return 0, err
	}
	return len(content), nil
}

func listDirectory(root, p string) ([]dirEntry, bool, error) {
	rel, err := cleanRel(p)
	if err != nil {
		return nil, false, err
	}
	r, err := os.OpenRoot(root)
	if err != nil {
		return nil, false, err
	}
	defer r.Close()
	f, err := r.Open(native(rel))
	if err != nil {
		return nil, false, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, false, err
	}
	if !info.IsDir() {
		return nil, false, fmt.Errorf("path is not a directory")
	}
	items, readErr := f.Readdir(maxListEntries + 1)
	if readErr != nil && !errors.Is(readErr, io.EOF) {
		return nil, false, readErr
	}
	truncated := len(items) > maxListEntries
	if truncated {
		items = items[:maxListEntries]
	}
	out := make([]dirEntry, 0, len(items))
	for _, item := range items {
		kind := "file"
		switch {
		case item.IsDir():
			kind = "dir"
		case item.Mode()&os.ModeSymlink != 0:
			kind = "link"
		case !item.Mode().IsRegular():
			kind = "other"
		}
		out = append(out, dirEntry{Name: item.Name(), Type: kind, Size: item.Size()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, truncated, nil
}

func searchFiles(root, p, pattern string, maxResults int) ([]searchMatch, int, error) {
	if pattern == "" || len(pattern) > 200 {
		return nil, 0, fmt.Errorf("pattern must be 1-200 characters")
	}
	rel, err := cleanRel(p)
	if err != nil {
		return nil, 0, err
	}
	r, err := os.OpenRoot(root)
	if err != nil {
		return nil, 0, err
	}
	defer r.Close()
	info, err := r.Stat(native(rel))
	if err != nil {
		return nil, 0, err
	}

	var files []string
	if info.IsDir() {
		visited := 0
		rootFS := r.FS()
		err = fs.WalkDir(rootFS, filepath.ToSlash(rel), func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			visited++
			if visited > maxSearchEntries {
				return fs.SkipAll
			}
			if d.IsDir() || d.Type()&fs.ModeSymlink != 0 || !d.Type().IsRegular() {
				return nil
			}
			files = append(files, path)
			if len(files) >= maxSearchFiles {
				return fs.SkipAll
			}
			return nil
		})
		if err != nil && !errors.Is(err, fs.SkipAll) {
			return nil, 0, err
		}
	} else if info.Mode().IsRegular() {
		files = []string{filepath.ToSlash(rel)}
	} else {
		return nil, 0, fmt.Errorf("path is not a file or directory")
	}

	var matches []searchMatch
	var scanned int64
	for _, file := range files {
		info, err := r.Lstat(native(file))
		if err != nil || !info.Mode().IsRegular() || info.Size() > 1<<20 || hasMultipleLinks(info) {
			continue
		}
		scanned += info.Size()
		if scanned > maxSearchBytes {
			break
		}
		data, err := r.ReadFile(native(file))
		if err != nil || bytes.IndexByte(data, 0) >= 0 {
			continue
		}
		for i, line := range bytes.Split(data, []byte("\n")) {
			if !bytes.Contains(line, []byte(pattern)) {
				continue
			}
			matches = append(matches, searchMatch{Path: file, Line: i + 1, Text: string(line)})
			if len(matches) >= maxResults {
				return matches, len(files), nil
			}
		}
	}
	return matches, len(files), nil
}

func encodeBase64(data []byte) string {
	return base64.StdEncoding.EncodeToString(data)
}

func decodeBase64(s string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(s)
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "fallback"
	}
	return hex.EncodeToString(b)
}

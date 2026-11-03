package main

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
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
	maxSearchBytes   = 32 << 20
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

func resolveRead(root, p string) (string, error) {
	rel, err := cleanRel(p)
	if err != nil {
		return "", err
	}
	full := filepath.Join(root, rel)
	real, err := filepath.EvalSymlinks(full)
	if err != nil {
		return "", err
	}
	if !within(root, real) {
		return "", errOutside
	}
	return real, nil
}

func resolveWrite(root, p string) (string, error) {
	rel, err := cleanRel(p)
	if err != nil {
		return "", err
	}
	if rel == "." {
		return "", fmt.Errorf("path must name a file")
	}
	if err := mkdirAllInside(root, filepath.Dir(rel)); err != nil {
		return "", err
	}
	full := filepath.Join(root, rel)
	if fi, err := os.Lstat(full); err == nil {
		if fi.Mode()&os.ModeSymlink != 0 {
			return "", fmt.Errorf("refusing to write through a symbolic link")
		}
		if fi.IsDir() {
			return "", fmt.Errorf("path is a directory")
		}
		if !fi.Mode().IsRegular() {
			return "", fmt.Errorf("path is not a regular file")
		}
		if hasMultipleLinks(fi) {
			return "", fmt.Errorf("refusing to write a file with multiple hard links")
		}
	} else if !os.IsNotExist(err) {
		return "", err
	}
	return full, nil
}

func mkdirAllInside(root, rel string) error {
	if rel == "." || rel == "" {
		return nil
	}
	cur := root
	for _, seg := range strings.Split(rel, string(filepath.Separator)) {
		if seg == "" || seg == "." {
			continue
		}
		next := filepath.Join(cur, seg)
		fi, err := os.Lstat(next)
		if err != nil {
			if !os.IsNotExist(err) {
				return err
			}
			if err := os.Mkdir(next, 0o750); err != nil && !os.IsExist(err) {
				return err
			}
			cur = next
			continue
		}
		if fi.Mode()&os.ModeSymlink != 0 {
			real, err := filepath.EvalSymlinks(next)
			if err != nil {
				return err
			}
			if !within(root, real) {
				return errOutside
			}
			cur = real
			continue
		}
		if !fi.IsDir() {
			return fmt.Errorf("%s is not a directory", seg)
		}
		cur = next
	}
	return nil
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
	full, err := resolveRead(root, p)
	if err != nil {
		return nil, err
	}
	fi, err := os.Lstat(full)
	if err != nil {
		return nil, err
	}
	if !fi.Mode().IsRegular() {
		return nil, fmt.Errorf("path is not a regular file")
	}
	if fi.Size() > int64(maxBytes) {
		return nil, fmt.Errorf("file is larger than %d bytes", maxBytes)
	}
	return os.ReadFile(full)
}

func writeTextFile(root, p string, content []byte, appendMode bool, maxBytes int) (int, error) {
	if len(content) > maxBytes {
		return 0, fmt.Errorf("content is larger than %d bytes", maxBytes)
	}
	full, err := resolveWrite(root, p)
	if err != nil {
		return 0, err
	}
	if appendMode {
		f, err := os.OpenFile(full, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o640)
		if err != nil {
			return 0, err
		}
		if _, err := f.Write(content); err != nil {
			_ = f.Close()
			return 0, err
		}
		return len(content), f.Close()
	}
	dir := filepath.Dir(full)
	tmp := filepath.Join(dir, ".mika-tmp-"+randomHex(8))
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o640)
	if err != nil {
		return 0, err
	}
	if _, err := f.Write(content); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return 0, err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return 0, err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	if err := os.Rename(tmp, full); err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	return len(content), nil
}

func listDirectory(root, p string) ([]dirEntry, error) {
	full, err := resolveRead(root, p)
	if err != nil {
		return nil, err
	}
	fi, err := os.Stat(full)
	if err != nil {
		return nil, err
	}
	if !fi.IsDir() {
		return nil, fmt.Errorf("path is not a directory")
	}
	items, err := os.ReadDir(full)
	if err != nil {
		return nil, err
	}
	out := make([]dirEntry, 0, len(items))
	for _, item := range items {
		info, err := os.Lstat(filepath.Join(full, item.Name()))
		if err != nil {
			continue
		}
		kind := "file"
		switch {
		case info.IsDir():
			kind = "dir"
		case info.Mode()&os.ModeSymlink != 0:
			kind = "link"
		case !info.Mode().IsRegular():
			kind = "other"
		}
		out = append(out, dirEntry{Name: item.Name(), Type: kind, Size: info.Size()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

func searchFiles(root, p, pattern string, maxResults int) ([]searchMatch, int, error) {
	if pattern == "" || len(pattern) > 200 {
		return nil, 0, fmt.Errorf("pattern must be 1-200 characters")
	}
	full, err := resolveRead(root, p)
	if err != nil {
		return nil, 0, err
	}
	fi, err := os.Stat(full)
	if err != nil {
		return nil, 0, err
	}
	var files []string
	if fi.IsDir() {
		err = filepath.WalkDir(full, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.IsDir() {
				return nil
			}
			if d.Type()&os.ModeSymlink != 0 || !d.Type().IsRegular() {
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
	} else if fi.Mode().IsRegular() {
		files = []string{full}
	} else {
		return nil, 0, fmt.Errorf("path is not a file or directory")
	}

	var matches []searchMatch
	var scanned int64
	for _, file := range files {
		info, err := os.Lstat(file)
		if err != nil || !info.Mode().IsRegular() || info.Size() > 1<<20 {
			continue
		}
		scanned += info.Size()
		if scanned > maxSearchBytes {
			break
		}
		data, err := os.ReadFile(file)
		if err != nil || bytes.IndexByte(data, 0) >= 0 {
			continue
		}
		rel, err := filepath.Rel(root, file)
		if err != nil {
			continue
		}
		for i, line := range bytes.Split(data, []byte("\n")) {
			if !bytes.Contains(line, []byte(pattern)) {
				continue
			}
			matches = append(matches, searchMatch{Path: rel, Line: i + 1, Text: string(line)})
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

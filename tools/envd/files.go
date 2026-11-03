package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// fileLinkTool issues a short-lived signed URL for one environment file. The
// main bearer token is never put in the URL.
func (s *Server) fileLink(args json.RawMessage) (string, bool) {
	var a struct {
		Env       string `json:"env"`
		Path      string `json:"path"`
		Direction string `json:"direction"`
		TTLSec    int    `json:"ttlSec"`
	}
	if err := decodeArgs(args, &a); err != nil {
		return jsonFail("invalid_arguments", err.Error())
	}
	direction := strings.TrimSpace(a.Direction)
	if direction == "" {
		direction = "download"
	}
	if direction != "download" && direction != "upload" {
		return jsonFail("invalid_arguments", "direction must be download or upload")
	}
	if direction == "upload" && s.cfg.readOnly {
		return toolError(ErrReadOnly)
	}
	ttl := a.TTLSec
	if ttl == 0 {
		ttl = 300
	}
	if ttl < 30 || ttl > 3600 {
		return jsonFail("invalid_arguments", "ttlSec must be 30-3600")
	}
	e, err := s.environment(a.Env)
	if err != nil {
		return toolError(err)
	}
	rel, err := cleanRel(a.Path)
	if err != nil {
		return toolError(err)
	}
	if rel == "." {
		return jsonFail("invalid_arguments", "path must name a file")
	}
	root, unlock, err := s.envRoot(e)
	if err != nil {
		return toolError(err)
	}
	defer unlock()
	if direction == "download" {
		r, err := os.OpenRoot(root)
		if err != nil {
			return toolError(err)
		}
		defer r.Close()
		f, err := r.Open(native(rel))
		if err != nil {
			return toolError(err)
		}
		info, statErr := f.Stat()
		_ = f.Close()
		if statErr != nil {
			return toolError(statErr)
		}
		if !info.Mode().IsRegular() || hasMultipleLinks(info) {
			return jsonFail("not_a_file", "path must be a regular file without multiple hard links")
		}
	}
	if s.cfg.publicURL == "" {
		return jsonFail("public_url_missing", "start envd with -public-url to issue file links")
	}
	exp := time.Now().Add(time.Duration(ttl) * time.Second).Unix()
	link := s.fileLinkURL(direction, a.Env, filepath.ToSlash(rel), exp)
	return jsonOK(map[string]any{"ok": true, "url": link, "expiresAt": time.Unix(exp, 0).UTC().Format(time.RFC3339)})
}

func (s *Server) fileLinkURL(direction, env, rel string, exp int64) string {
	endpoint := "/files/download"
	if direction == "upload" {
		endpoint = "/files/upload"
	}
	q := url.Values{}
	q.Set("env", env)
	q.Set("path", rel)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("sig", s.fileSig(direction, env, rel, exp))
	return strings.TrimRight(s.cfg.publicURL, "/") + endpoint + "?" + q.Encode()
}

func (s *Server) fileSig(direction, env, rel string, exp int64) string {
	mac := hmac.New(sha256.New, s.linkKey)
	fmt.Fprintf(mac, "%s\n%s\n%s\n%d", direction, env, rel, exp)
	return hex.EncodeToString(mac.Sum(nil))
}

func (s *Server) validFileLink(r *http.Request) bool {
	direction := "download"
	if r.URL.Path == "/files/upload" {
		direction = "upload"
	}
	if direction == "download" && r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	if direction == "upload" && r.Method != http.MethodPut && r.Method != http.MethodPost {
		return false
	}
	q := r.URL.Query()
	env := q.Get("env")
	rawPath := q.Get("path")
	expText := q.Get("exp")
	sig := q.Get("sig")
	if env == "" || rawPath == "" || expText == "" || sig == "" {
		return false
	}
	rel, err := cleanRel(rawPath)
	if err != nil || rel == "." {
		return false
	}
	exp, err := strconv.ParseInt(expText, 10, 64)
	if err != nil || time.Now().Unix() > exp {
		return false
	}
	expected := s.fileSig(direction, env, filepath.ToSlash(rel), exp)
	return hmac.Equal([]byte(sig), []byte(expected))
}

func (s *Server) handleFiles(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/files/download" {
		s.serveFileDownload(w, r)
		return
	}
	s.serveFileUpload(w, r)
}

func (s *Server) serveFileDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	e, err := s.environment(r.URL.Query().Get("env"))
	if err != nil {
		http.Error(w, "environment not found", http.StatusNotFound)
		return
	}
	rel, err := cleanRel(r.URL.Query().Get("path"))
	if err != nil || rel == "." {
		http.Error(w, "path must name a file", http.StatusBadRequest)
		return
	}
	root, unlock, err := s.envRoot(e)
	if err != nil {
		http.Error(w, "environment is unavailable", http.StatusNotFound)
		return
	}
	defer unlock()
	rootFS, err := os.OpenRoot(root)
	if err != nil {
		http.Error(w, "environment is unavailable", http.StatusNotFound)
		return
	}
	defer rootFS.Close()
	f, err := rootFS.Open(native(rel))
	if err != nil {
		http.Error(w, "file not found", http.StatusNotFound)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || hasMultipleLinks(info) {
		http.Error(w, "not a regular file", http.StatusBadRequest)
		return
	}
	name := filepath.Base(native(rel))
	if disposition := mime.FormatMediaType("attachment", map[string]string{"filename": name}); disposition != "" {
		w.Header().Set("Content-Disposition", disposition)
	}
	http.ServeContent(w, r, name, info.ModTime(), f)
}

func (s *Server) serveFileUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut && r.Method != http.MethodPost {
		w.Header().Set("Allow", "PUT, POST")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.cfg.readOnly {
		http.Error(w, "server is read-only", http.StatusForbidden)
		return
	}
	if r.ContentLength > s.cfg.maxUpload {
		http.Error(w, "upload is too large", http.StatusRequestEntityTooLarge)
		return
	}
	e, err := s.environment(r.URL.Query().Get("env"))
	if err != nil {
		http.Error(w, "environment not found", http.StatusNotFound)
		return
	}
	rel, err := cleanRel(r.URL.Query().Get("path"))
	if err != nil || rel == "." {
		http.Error(w, "path must name a file", http.StatusBadRequest)
		return
	}
	root, unlock, err := s.envRoot(e)
	if err != nil {
		http.Error(w, "environment is unavailable", http.StatusNotFound)
		return
	}
	defer unlock()

	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.maxUpload)
	disk, err := os.OpenRoot(root)
	if err != nil {
		http.Error(w, "environment is unavailable", http.StatusInternalServerError)
		return
	}
	defer disk.Close()
	if dir := filepath.Dir(rel); dir != "." {
		if err := disk.MkdirAll(native(dir), 0o770); err != nil {
			http.Error(w, "cannot create directory", http.StatusInternalServerError)
			return
		}
	}
	tmp := filepath.Join(native(filepath.Dir(rel)), ".mika-upload-"+randomHex(8))
	out, err := disk.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o660)
	if err != nil {
		http.Error(w, "cannot create file", http.StatusInternalServerError)
		return
	}
	hash := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(out, hash), r.Body)
	closeErr := out.Close()
	if copyErr != nil {
		_ = disk.Remove(tmp)
		var maxErr *http.MaxBytesError
		if errors.As(copyErr, &maxErr) {
			http.Error(w, "upload is too large", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "upload failed", http.StatusBadRequest)
		return
	}
	if closeErr != nil || disk.Rename(tmp, native(rel)) != nil {
		_ = disk.Remove(tmp)
		http.Error(w, "cannot save file", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"ok":     true,
		"path":   filepath.ToSlash(rel),
		"bytes":  written,
		"sha256": hex.EncodeToString(hash.Sum(nil)),
	})
}

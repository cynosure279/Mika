package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestLargeFileUploadAndDownloadOverHTTPS(t *testing.T) {
	ts, _ := newTestServerWith(t, config{maxExec: 1, maxOutput: 1 << 20, maxUpload: 8 << 20}, false)
	id := createEnv(t, ts, "files")
	body := bytes.Repeat([]byte("large-file-"), 200_000) // ~2 MiB
	put, err := http.NewRequest(http.MethodPut, ts.URL+"/files/upload?env="+id+"&path=big/data.bin", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	put.Header.Set("Authorization", "Bearer "+testToken)
	resp, err := http.DefaultClient.Do(put)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		text, _ := io.ReadAll(resp.Body)
		t.Fatalf("upload: HTTP %d %s", resp.StatusCode, text)
	}
	var uploaded struct {
		Bytes  int    `json:"bytes"`
		SHA256 string `json:"sha256"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&uploaded); err != nil {
		t.Fatal(err)
	}
	if uploaded.Bytes != len(body) || uploaded.SHA256 == "" {
		t.Fatalf("upload result: %+v", uploaded)
	}

	get, err := http.NewRequest(http.MethodGet, ts.URL+"/files/download?env="+id+"&path=big/data.bin", nil)
	if err != nil {
		t.Fatal(err)
	}
	get.Header.Set("Authorization", "Bearer "+testToken)
	resp2, err := http.DefaultClient.Do(get)
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("download: HTTP %d", resp2.StatusCode)
	}
	got, err := io.ReadAll(resp2.Body)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, body) {
		t.Fatalf("download bytes differ: %d vs %d", len(got), len(body))
	}
}

func TestSignedFileLinksAreScopedAndExpire(t *testing.T) {
	ts, srv := newTestServerWith(t, config{maxExec: 1, maxOutput: 1 << 20, maxUpload: 8 << 20}, false)
	srv.cfg.publicURL = ts.URL
	id := createEnv(t, ts, "links")
	callJSON[struct{}](t, ts, "write_file", map[string]any{"env": id, "path": "a.txt", "content": "hello"})

	link := callJSON[struct {
		URL string `json:"url"`
	}](t, ts, "file_link", map[string]any{"env": id, "path": "a.txt"})
	if !strings.Contains(link.URL, "/files/download?") || !strings.Contains(link.URL, "sig=") {
		t.Fatalf("link: %s", link.URL)
	}
	resp, err := http.Get(link.URL)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || string(body) != "hello" {
		t.Fatalf("signed download: HTTP %d %q", resp.StatusCode, body)
	}

	tampered := strings.Replace(link.URL, "path=a.txt", "path=b.txt", 1)
	resp2, err := http.Get(tampered)
	if err != nil {
		t.Fatal(err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusUnauthorized {
		t.Fatalf("tampered link: HTTP %d", resp2.StatusCode)
	}

	expired := srv.fileLinkURL("download", id, "a.txt", time.Now().Add(-time.Minute).Unix())
	respExpired, err := http.Get(expired)
	if err != nil {
		t.Fatal(err)
	}
	respExpired.Body.Close()
	if respExpired.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expired link: HTTP %d", respExpired.StatusCode)
	}

	upload := callJSON[struct {
		URL string `json:"url"`
	}](t, ts, "file_link", map[string]any{"env": id, "path": "b.txt", "direction": "upload", "ttlSec": 60})
	req, err := http.NewRequest(http.MethodPut, upload.URL, strings.NewReader("world"))
	if err != nil {
		t.Fatal(err)
	}
	resp3, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp3.Body.Close()
	if resp3.StatusCode != http.StatusOK {
		t.Fatalf("signed upload: HTTP %d", resp3.StatusCode)
	}
	read := callJSON[struct {
		Content string `json:"content"`
	}](t, ts, "read_file", map[string]any{"env": id, "path": "b.txt"})
	if read.Content != "world" {
		t.Fatalf("uploaded content: %q", read.Content)
	}

	mismatch, err := http.NewRequest(http.MethodGet, upload.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	resp4, err := http.DefaultClient.Do(mismatch)
	if err != nil {
		t.Fatal(err)
	}
	resp4.Body.Close()
	if resp4.StatusCode != http.StatusUnauthorized {
		t.Fatalf("upload link used for download: HTTP %d", resp4.StatusCode)
	}
}

func TestUploadLimit(t *testing.T) {
	ts, _ := newTestServerWith(t, config{maxExec: 1, maxOutput: 1 << 20, maxUpload: 4}, false)
	id := createEnv(t, ts, "limit")
	req, err := http.NewRequest(http.MethodPut, ts.URL+"/files/upload?env="+id+"&path=big.bin", strings.NewReader("12345"))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+testToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("upload limit: HTTP %d", resp.StatusCode)
	}
}

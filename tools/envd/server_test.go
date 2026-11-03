package main

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

const testToken = "0123456789abcdef0123456789abcdef"

func newTestServer(t *testing.T) (*httptest.Server, *Server) {
	t.Helper()
	return newTestServerWith(t, config{maxExec: 2, maxOutput: 1 << 20}, false)
}

func newTestServerWith(t *testing.T, cfg config, readOnly bool) (*httptest.Server, *Server) {
	t.Helper()
	return newTestServerWithRoots(t, cfg, readOnly)
}

func newTestServerWithRoots(t *testing.T, cfg config, readOnly bool, roots ...string) (*httptest.Server, *Server) {
	t.Helper()
	if cfg.execUser == 0 && cfg.execGroup == 0 {
		cfg.execUser, cfg.execGroup = -1, -1
	}
	dir := t.TempDir()
	store, err := OpenStore(dir, readOnly, roots...)
	if err != nil {
		t.Fatal(err)
	}
	cfg.dataDir = dir
	cfg.readOnly = readOnly
	if cfg.execUser >= 0 {
		_ = os.Chmod(filepath.Dir(dir), 0o755)
		setGroupUmask()
		if err := store.ConfigureExecUser(cfg.execUser, cfg.execGroup); err != nil {
			t.Fatal(err)
		}
		if err := validateExecUser(cfg.execUser, cfg.execGroup); err != nil {
			t.Fatal(err)
		}
	}
	s := NewServer(cfg, testToken, store)
	ts := httptest.NewServer(s)
	t.Cleanup(ts.Close)
	return ts, s
}

func doRPC(t *testing.T, ts *httptest.Server, token, origin string, id any, method string, params any) (int, map[string]any) {
	t.Helper()
	body := map[string]any{"jsonrpc": "2.0", "method": method}
	if id != nil {
		body["id"] = id
	}
	if params != nil {
		body["params"] = params
	}
	b, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(http.MethodPost, ts.URL+"/mcp", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	if resp.StatusCode == http.StatusOK {
		if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
			t.Fatalf("decode response: %v", err)
		}
	}
	return resp.StatusCode, out
}

func postRPC(t *testing.T, ts *httptest.Server, method string, params any) map[string]any {
	t.Helper()
	status, out := doRPC(t, ts, testToken, "", 1, method, params)
	if status != http.StatusOK {
		t.Fatalf("%s: HTTP %d", method, status)
	}
	if e, ok := out["error"]; ok {
		t.Fatalf("%s: rpc error %v", method, e)
	}
	result, ok := out["result"].(map[string]any)
	if !ok {
		t.Fatalf("%s: missing result: %v", method, out)
	}
	return result
}

func callTool(t *testing.T, ts *httptest.Server, name string, args map[string]any) (string, bool) {
	t.Helper()
	if args == nil {
		args = map[string]any{}
	}
	result := postRPC(t, ts, "tools/call", map[string]any{"name": name, "arguments": args})
	content, ok := result["content"].([]any)
	if !ok || len(content) == 0 {
		t.Fatalf("%s: missing content: %v", name, result)
	}
	item, ok := content[0].(map[string]any)
	if !ok {
		t.Fatalf("%s: bad content item: %v", name, content[0])
	}
	text, _ := item["text"].(string)
	isErr, _ := result["isError"].(bool)
	return text, isErr
}

func callJSON[T any](t *testing.T, ts *httptest.Server, name string, args map[string]any) T {
	t.Helper()
	text, isErr := callTool(t, ts, name, args)
	if isErr {
		t.Fatalf("%s failed: %s", name, text)
	}
	var v T
	if err := json.Unmarshal([]byte(text), &v); err != nil {
		t.Fatalf("%s: decode %q: %v", name, text, err)
	}
	return v
}

func createEnv(t *testing.T, ts *httptest.Server, name string) string {
	t.Helper()
	out := callJSON[struct {
		Environment struct {
			ID string `json:"id"`
		} `json:"environment"`
	}](t, ts, "env_create", map[string]any{"name": name})
	if out.Environment.ID == "" {
		t.Fatal("env_create returned no id")
	}
	return out.Environment.ID
}

func TestAuthAndProtocol(t *testing.T) {
	ts, _ := newTestServer(t)

	if status, _ := doRPC(t, ts, "", "", 1, "initialize", nil); status != http.StatusUnauthorized {
		t.Fatalf("missing token: HTTP %d", status)
	}
	if status, _ := doRPC(t, ts, "wrong-token", "", 1, "initialize", nil); status != http.StatusUnauthorized {
		t.Fatalf("wrong token: HTTP %d", status)
	}
	if status, _ := doRPC(t, ts, testToken, "https://example.com", 1, "initialize", nil); status != http.StatusForbidden {
		t.Fatalf("origin: HTTP %d", status)
	}

	init := postRPC(t, ts, "initialize", map[string]any{"protocolVersion": "2025-03-26"})
	if init["protocolVersion"] != "2025-03-26" {
		t.Fatalf("protocol: %v", init["protocolVersion"])
	}
	info, _ := init["serverInfo"].(map[string]any)
	if info["name"] != serverName {
		t.Fatalf("serverInfo: %v", info)
	}

	if status, _ := doRPC(t, ts, testToken, "", nil, "notifications/initialized", map[string]any{}); status != http.StatusAccepted {
		t.Fatalf("notification: HTTP %d", status)
	}

	status, out := doRPC(t, ts, testToken, "", 2, "missing/method", nil)
	if status != http.StatusOK {
		t.Fatalf("unknown method: HTTP %d", status)
	}
	e, _ := out["error"].(map[string]any)
	if e["code"] != float64(-32601) {
		t.Fatalf("unknown method error: %v", out)
	}
}

func TestExecUserRunsAsAnotherUid(t *testing.T) {
	if runtime.GOOS == "windows" || os.Geteuid() != 0 {
		t.Skip("changing uid requires a Unix host and root")
	}
	ts, _ := newTestServerWith(t, config{maxExec: 1, maxOutput: 1 << 20, execUser: 65534, execGroup: 65534}, false)
	id := createEnv(t, ts, "runner")
	out := callJSON[struct {
		Stdout string `json:"stdout"`
	}](t, ts, "exec", map[string]any{"env": id, "command": "id -u"})
	if strings.TrimSpace(out.Stdout) != "65534" {
		t.Fatalf("exec uid: %q", out.Stdout)
	}
}

func TestToolsList(t *testing.T) {
	ts, _ := newTestServer(t)
	result := postRPC(t, ts, "tools/list", nil)
	tools, ok := result["tools"].([]any)
	if !ok || len(tools) == 0 {
		t.Fatalf("tools: %v", result)
	}
	names := map[string]bool{}
	for _, item := range tools {
		m := item.(map[string]any)
		names[m["name"].(string)] = true
		if _, ok := m["inputSchema"].(map[string]any); !ok {
			t.Fatalf("%s has no inputSchema", m["name"])
		}
	}
	for _, want := range []string{"env_list", "env_create", "env_clone", "env_delete", "exec", "read_file", "write_file", "upload_file", "download_file", "list_dir", "search"} {
		if !names[want] {
			t.Fatalf("missing tool %s", want)
		}
	}
}

func TestEnvironmentLifecycle(t *testing.T) {
	ts, _ := newTestServer(t)
	id := createEnv(t, ts, "ops")

	list := callJSON[struct {
		Environments []envView `json:"environments"`
	}](t, ts, "env_list", nil)
	if len(list.Environments) != 1 || list.Environments[0].ID != id {
		t.Fatalf("list: %+v", list.Environments)
	}

	write := callJSON[struct {
		Bytes int `json:"bytes"`
	}](t, ts, "write_file", map[string]any{"env": id, "path": "notes/hello.txt", "content": "hello world\n"})
	if write.Bytes != 12 {
		t.Fatalf("write bytes: %d", write.Bytes)
	}

	read := callJSON[struct {
		Content string `json:"content"`
	}](t, ts, "read_file", map[string]any{"env": id, "path": "notes/hello.txt"})
	if read.Content != "hello world\n" {
		t.Fatalf("read: %q", read.Content)
	}

	dir := callJSON[struct {
		Entries []dirEntry `json:"entries"`
	}](t, ts, "list_dir", map[string]any{"env": id, "path": "notes"})
	if len(dir.Entries) != 1 || dir.Entries[0].Name != "hello.txt" {
		t.Fatalf("list_dir: %+v", dir.Entries)
	}

	found := callJSON[struct {
		Matches []searchMatch `json:"matches"`
	}](t, ts, "search", map[string]any{"env": id, "pattern": "world"})
	if len(found.Matches) != 1 || found.Matches[0].Line != 1 {
		t.Fatalf("search: %+v", found.Matches)
	}

	clone := callJSON[struct {
		Environment struct {
			ID string `json:"id"`
		} `json:"environment"`
	}](t, ts, "env_clone", map[string]any{"source": id, "name": "ops-copy"})
	if clone.Environment.ID == "" || clone.Environment.ID == id {
		t.Fatalf("clone: %+v", clone)
	}
	copied := callJSON[struct {
		Content string `json:"content"`
	}](t, ts, "read_file", map[string]any{"env": clone.Environment.ID, "path": "notes/hello.txt"})
	if copied.Content != "hello world\n" {
		t.Fatalf("clone content: %q", copied.Content)
	}

	if text, isErr := callTool(t, ts, "env_delete", map[string]any{"id": id, "confirm": "wrong"}); !isErr || !strings.Contains(text, "confirm") {
		t.Fatalf("delete without confirm: %s (isError=%v)", text, isErr)
	}
	if _, isErr := callTool(t, ts, "env_delete", map[string]any{"id": id, "confirm": id}); isErr {
		t.Fatal("delete failed")
	}
	after := callJSON[struct {
		Environments []envView `json:"environments"`
	}](t, ts, "env_list", nil)
	if len(after.Environments) != 1 || after.Environments[0].ID != clone.Environment.ID {
		t.Fatalf("after delete: %+v", after.Environments)
	}
}

func TestExec(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the test commands use a Unix shell")
	}
	ts, _ := newTestServer(t)
	id := createEnv(t, ts, "exec")

	ok := callJSON[struct {
		ExitCode int    `json:"exitCode"`
		Stdout   string `json:"stdout"`
		Stderr   string `json:"stderr"`
		TimedOut bool   `json:"timedOut"`
	}](t, ts, "exec", map[string]any{"env": id, "command": "printf 'hello\\n'"})
	if ok.ExitCode != 0 || ok.Stdout != "hello\n" || ok.TimedOut {
		t.Fatalf("exec: %+v", ok)
	}

	fail := callJSON[struct {
		ExitCode int    `json:"exitCode"`
		Stderr   string `json:"stderr"`
	}](t, ts, "exec", map[string]any{"env": id, "command": "printf 'oops\\n' >&2; exit 3"})
	if fail.ExitCode != 3 || !strings.Contains(fail.Stderr, "oops") {
		t.Fatalf("exec fail: %+v", fail)
	}

	timeout := callJSON[struct {
		TimedOut   bool  `json:"timedOut"`
		DurationMs int64 `json:"durationMs"`
	}](t, ts, "exec", map[string]any{"env": id, "command": "sleep 5", "timeoutSec": 1})
	if !timeout.TimedOut || timeout.DurationMs > 4000 {
		t.Fatalf("timeout: %+v", timeout)
	}

	callJSON[struct{}](t, ts, "exec", map[string]any{"env": id, "command": "mkdir -p sub"})
	pwd := callJSON[struct {
		Stdout string `json:"stdout"`
	}](t, ts, "exec", map[string]any{"env": id, "command": "pwd", "cwd": "sub"})
	if !strings.HasSuffix(strings.TrimSpace(pwd.Stdout), "/sub") {
		t.Fatalf("cwd: %q", pwd.Stdout)
	}
}

func TestPathTraversalAndSymlinks(t *testing.T) {
	ts, _ := newTestServer(t)
	id := createEnv(t, ts, "paths")

	for _, path := range []string{"../escape", "/etc/passwd"} {
		if text, isErr := callTool(t, ts, "write_file", map[string]any{"env": id, "path": path, "content": "x"}); !isErr || !strings.Contains(text, "outside") {
			t.Fatalf("write %q: %s (isErr=%v)", path, text, isErr)
		}
		if text, isErr := callTool(t, ts, "read_file", map[string]any{"env": id, "path": path}); !isErr || !strings.Contains(text, "outside") {
			t.Fatalf("read %q: %s (isErr=%v)", path, text, isErr)
		}
	}

	if runtime.GOOS == "windows" {
		t.Skip("symlink test needs a Unix host")
	}
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	callJSON[struct{}](t, ts, "exec", map[string]any{"env": id, "command": "mkdir -p linkdir"})
	// Create the link through exec so it lives inside the environment root.
	callJSON[struct{}](t, ts, "exec", map[string]any{"env": id, "command": "ln -s " + outside + " linkdir/out"})
	if text, isErr := callTool(t, ts, "read_file", map[string]any{"env": id, "path": "linkdir/out/secret"}); !isErr {
		t.Fatalf("read through escaping symlink succeeded: %s", text)
	}
	if text, isErr := callTool(t, ts, "write_file", map[string]any{"env": id, "path": "linkdir/out/pwn", "content": "x"}); !isErr {
		t.Fatalf("write through escaping symlink succeeded: %s", text)
	}
}

func TestReadOnly(t *testing.T) {
	dir := t.TempDir()
	writable, err := OpenStore(dir, false)
	if err != nil {
		t.Fatal(err)
	}
	env, err := writable.Create("readonly")
	if err != nil {
		t.Fatal(err)
	}
	root, err := canonicalRoot(env.Root(dir))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := writeTextFile(root, "a.txt", []byte("keep"), false, maxWriteBytes); err != nil {
		t.Fatal(err)
	}

	readOnly, err := OpenStore(dir, true)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config{dataDir: dir, maxExec: 1, maxOutput: 1 << 20, readOnly: true, execUser: -1, execGroup: -1}
	ts := httptest.NewServer(NewServer(cfg, testToken, readOnly))
	t.Cleanup(ts.Close)

	read := callJSON[struct {
		Content string `json:"content"`
	}](t, ts, "read_file", map[string]any{"env": env.ID, "path": "a.txt"})
	if read.Content != "keep" {
		t.Fatalf("read-only read: %q", read.Content)
	}
	for _, c := range []struct {
		name string
		args map[string]any
	}{
		{"env_create", map[string]any{"name": "nope"}},
		{"write_file", map[string]any{"env": env.ID, "path": "a.txt", "content": "x"}},
		{"exec", map[string]any{"env": env.ID, "command": "echo x"}},
	} {
		if text, isErr := callTool(t, ts, c.name, c.args); !isErr || !strings.Contains(text, "read_only") {
			t.Fatalf("%s in read-only mode: %s (isErr=%v)", c.name, text, isErr)
		}
	}
}

func TestUploadDownloadAndBinary(t *testing.T) {
	ts, _ := newTestServer(t)
	id := createEnv(t, ts, "files")
	data := []byte{0x00, 0x01, 0x02, 0xff}
	encoded := base64.StdEncoding.EncodeToString(data)
	callJSON[struct{}](t, ts, "upload_file", map[string]any{"env": id, "path": "bin/data.bin", "contentBase64": encoded})

	down := callJSON[struct {
		ContentBase64 string `json:"contentBase64"`
	}](t, ts, "download_file", map[string]any{"env": id, "path": "bin/data.bin"})
	got, err := base64.StdEncoding.DecodeString(down.ContentBase64)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("download: %v %x", err, got)
	}
	if text, isErr := callTool(t, ts, "read_file", map[string]any{"env": id, "path": "bin/data.bin"}); !isErr || !strings.Contains(text, "binary") {
		t.Fatalf("read binary: %s (isErr=%v)", text, isErr)
	}
}

func TestOutputTruncation(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the test command uses a Unix shell")
	}
	ts, _ := newTestServerWith(t, config{maxExec: 1, maxOutput: 16}, false)
	id := createEnv(t, ts, "truncate")
	out := callJSON[struct {
		Stdout    string `json:"stdout"`
		Truncated bool   `json:"truncated"`
	}](t, ts, "exec", map[string]any{"env": id, "command": "printf 'abcdefghijklmnopqrstuvwxyz'"})
	if !out.Truncated || len(out.Stdout) != 16 {
		t.Fatalf("truncate: %+v", out)
	}
}

func TestAttachExistingDirectory(t *testing.T) {
	base := t.TempDir()
	allowed := filepath.Join(base, "allowed")
	shared := filepath.Join(allowed, "shared")
	if err := os.MkdirAll(shared, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(shared, "keep.txt"), []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	ts, srv := newTestServerWithRoots(t, config{maxExec: 1, maxOutput: 1 << 20}, false, allowed)

	attached := callJSON[struct {
		Environment struct {
			ID       string `json:"id"`
			Attached bool   `json:"attached"`
		} `json:"environment"`
	}](t, ts, "env_create", map[string]any{"name": "phone", "path": shared})
	if attached.Environment.ID == "" || !attached.Environment.Attached {
		t.Fatalf("attach: %+v", attached)
	}
	id := attached.Environment.ID

	read := callJSON[struct {
		Content string `json:"content"`
	}](t, ts, "read_file", map[string]any{"env": id, "path": "keep.txt"})
	if read.Content != "keep" {
		t.Fatalf("read attached: %q", read.Content)
	}
	callJSON[struct{}](t, ts, "write_file", map[string]any{"env": id, "path": "new.txt", "content": "new"})
	if err := os.WriteFile(filepath.Join(shared, "outside.txt"), []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	read2 := callJSON[struct {
		Content string `json:"content"`
	}](t, ts, "read_file", map[string]any{"env": id, "path": "outside.txt"})
	if read2.Content != "outside" {
		t.Fatalf("read external file: %q", read2.Content)
	}

	// Cloning an attached environment copies it into a managed workspace.
	clone := callJSON[struct {
		Environment struct {
			ID       string `json:"id"`
			Attached bool   `json:"attached"`
		} `json:"environment"`
	}](t, ts, "env_clone", map[string]any{"source": id, "name": "phone-copy"})
	if clone.Environment.Attached {
		t.Fatal("clone must be a managed environment")
	}

	// An attached environment is visible only with the same -allow-root.
	if _, err := OpenStore(srv.store.dataDir, false, allowed); err != nil {
		t.Fatalf("reload with allowed root: %v", err)
	}
	if _, err := OpenStore(srv.store.dataDir, false); err == nil {
		t.Fatal("reload without -allow-root must fail")
	}

	// Deleting an attached environment keeps the user's directory.
	if _, isErr := callTool(t, ts, "env_delete", map[string]any{"id": id, "confirm": id}); isErr {
		t.Fatal("delete attached failed")
	}
	if _, err := os.Stat(filepath.Join(shared, "keep.txt")); err != nil {
		t.Fatalf("attached files were removed: %v", err)
	}
	if _, err := OpenStore(srv.store.dataDir, false, allowed); err != nil {
		t.Fatalf("reload after delete: %v", err)
	}
}

func TestExecStreamsProgress(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the test command uses a Unix shell")
	}
	ts, _ := newTestServer(t)
	id := createEnv(t, ts, "stream")
	body := fmt.Sprintf(`{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"exec","arguments":{"env":%q,"command":"for i in 1 2 3; do echo chunk$i; sleep 0.15; done"},"_meta":{"progressToken":"p1"}}}`, id)
	req, err := http.NewRequest(http.MethodPost, ts.URL+"/mcp", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if got := resp.Header.Get("Content-Type"); !strings.HasPrefix(got, "text/event-stream") {
		t.Fatalf("content type: %q", got)
	}
	progress := 0
	var final map[string]any
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		var msg map[string]any
		if err := json.Unmarshal([]byte(strings.TrimSpace(strings.TrimPrefix(line, "data:"))), &msg); err != nil {
			t.Fatalf("bad SSE data %q: %v", line, err)
		}
		if msg["method"] == "notifications/progress" {
			progress++
		}
		if msg["id"] == float64(7) {
			final = msg
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if progress == 0 {
		t.Fatal("no progress notifications")
	}
	if final == nil {
		t.Fatal("no final response")
	}
	result, _ := final["result"].(map[string]any)
	content, _ := result["content"].([]any)
	if len(content) == 0 {
		t.Fatalf("final result: %v", final)
	}
	item, _ := content[0].(map[string]any)
	text, _ := item["text"].(string)
	var out struct {
		Stdout string `json:"stdout"`
	}
	if err := json.Unmarshal([]byte(text), &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.Stdout, "chunk3") {
		t.Fatalf("stdout: %q", out.Stdout)
	}
}

func TestManagedRootSymlinkEscapeIsRefused(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink test needs a Unix host")
	}
	ts, srv := newTestServer(t)
	id := createEnv(t, ts, "swapped")
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(srv.store.dataDir, "envs", id, "root")
	if err := os.RemoveAll(root); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, root); err != nil {
		t.Fatal(err)
	}
	if text, isErr := callTool(t, ts, "read_file", map[string]any{"env": id, "path": "secret"}); !isErr || !strings.Contains(text, "outside") {
		t.Fatalf("read through swapped root: %s (isErr=%v)", text, isErr)
	}
	if text, isErr := callTool(t, ts, "exec", map[string]any{"env": id, "command": "pwd"}); !isErr || !strings.Contains(text, "outside") {
		t.Fatalf("exec through swapped root: %s (isErr=%v)", text, isErr)
	}
}

func TestAttachedRootSymlinkEscapeIsRefused(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink test needs a Unix host")
	}
	allowed := t.TempDir()
	shared := filepath.Join(allowed, "shared")
	if err := os.MkdirAll(shared, 0o750); err != nil {
		t.Fatal(err)
	}
	ts, _ := newTestServerWithRoots(t, config{maxExec: 1, maxOutput: 1 << 20}, false, allowed)
	attached := callJSON[struct {
		Environment struct {
			ID string `json:"id"`
		} `json:"environment"`
	}](t, ts, "env_create", map[string]any{"name": "phone", "path": shared})
	id := attached.Environment.ID

	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(shared); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, shared); err != nil {
		t.Fatal(err)
	}
	if text, isErr := callTool(t, ts, "read_file", map[string]any{"env": id, "path": "secret"}); !isErr || !strings.Contains(text, "outside") {
		t.Fatalf("read through swapped attached root: %s (isErr=%v)", text, isErr)
	}
}

func TestReadRejectsHardLinkedFile(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("hardlink test needs a Unix host")
	}
	ts, _ := newTestServer(t)
	id := createEnv(t, ts, "hardlink")
	outside := t.TempDir()
	secret := filepath.Join(outside, "secret")
	if err := os.WriteFile(secret, []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	callJSON[struct{}](t, ts, "exec", map[string]any{"env": id, "command": fmt.Sprintf("ln %q hard", secret)})
	for _, tool := range []string{"read_file", "download_file"} {
		text, isErr := callTool(t, ts, tool, map[string]any{"env": id, "path": "hard"})
		if !isErr || !strings.Contains(text, "hard links") {
			t.Fatalf("%s hardlink: %s (isErr=%v)", tool, text, isErr)
		}
	}
	found := callJSON[struct {
		Matches []searchMatch `json:"matches"`
	}](t, ts, "search", map[string]any{"env": id, "pattern": "secret"})
	if len(found.Matches) != 0 {
		t.Fatalf("search read a hardlinked file: %+v", found.Matches)
	}
	if text, isErr := callTool(t, ts, "env_clone", map[string]any{"source": id, "name": "copy"}); !isErr || !strings.Contains(text, "hard links") {
		t.Fatalf("clone with hardlink: %s (isErr=%v)", text, isErr)
	}
}

func TestAttachRequiresAllowRoot(t *testing.T) {
	ts, _ := newTestServer(t)
	dir := t.TempDir()
	if text, isErr := callTool(t, ts, "env_create", map[string]any{"name": "nope", "path": dir}); !isErr || !strings.Contains(text, "attach_not_allowed") {
		t.Fatalf("attach without allow-root: %s (isErr=%v)", text, isErr)
	}
}

func TestAttachOutsideAllowedRoot(t *testing.T) {
	allowed := t.TempDir()
	outside := t.TempDir()
	ts, _ := newTestServerWithRoots(t, config{maxExec: 1, maxOutput: 1 << 20}, false, allowed)
	if text, isErr := callTool(t, ts, "env_create", map[string]any{"name": "nope", "path": outside}); !isErr || !strings.Contains(text, "outside") {
		t.Fatalf("attach outside: %s (isErr=%v)", text, isErr)
	}
}

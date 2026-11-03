package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"sync"
	"time"
)

const (
	defaultExecTimeout = 60
	maxExecTimeout     = 600
	maxCommandChars    = 20_000
)

type execOutput struct {
	ExitCode   int    `json:"exitCode"`
	Stdout     string `json:"stdout"`
	Stderr     string `json:"stderr"`
	Truncated  bool   `json:"truncated"`
	TimedOut   bool   `json:"timedOut"`
	DurationMs int64  `json:"durationMs"`
}

type cappedBuffer struct {
	mu      sync.Mutex
	buf     bytes.Buffer
	limit   int
	dropped int64
	onWrite func([]byte)
}

func newCappedBuffer(limit int) *cappedBuffer {
	return &cappedBuffer{limit: limit}
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	room := b.limit - b.buf.Len()
	written := 0
	if room > 0 {
		n := len(p)
		if n > room {
			n = room
		}
		_, _ = b.buf.Write(p[:n])
		written = n
		b.dropped += int64(len(p) - n)
	} else {
		b.dropped += int64(len(p))
	}
	callback := b.onWrite
	b.mu.Unlock()
	if callback != nil && written > 0 {
		chunk := make([]byte, written)
		copy(chunk, p[:written])
		callback(chunk)
	}
	return len(p), nil
}

func (b *cappedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func (b *cappedBuffer) Truncated() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.dropped > 0
}

// runProcessCapture runs one process with a timeout and bounded output. The
// child gets its own process group so a timeout also stops its descendants.
// onChunk receives output as it is produced; it may be called from the stdout
// and stderr reader goroutines at the same time.
func runProcessCapture(ctx context.Context, name string, args []string, dir string, env []string, timeoutSec, maxOutput int, onChunk func(string), uid, gid int) (execOutput, error) {
	runCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutSec)*time.Second)
	defer cancel()

	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = env
	stdout := newCappedBuffer(maxOutput)
	stderr := newCappedBuffer(maxOutput)
	if onChunk != nil {
		stdout.onWrite = func(p []byte) { onChunk(string(p)) }
		stderr.onWrite = func(p []byte) { onChunk(string(p)) }
	}
	cmd.Stdout = stdout
	cmd.Stderr = stderr
	setProcessGroup(cmd)
	if err := setExecCredential(cmd, uid, gid); err != nil {
		return execOutput{}, err
	}

	started := time.Now()
	if err := cmd.Start(); err != nil {
		return execOutput{}, err
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	timedOut := false
	var waitErr error
	select {
	case <-runCtx.Done():
		timedOut = errors.Is(runCtx.Err(), context.DeadlineExceeded)
		killProcessGroup(cmd)
		waitErr = <-done
	case waitErr = <-done:
	}

	exitCode := 0
	if waitErr != nil {
		var exitErr *exec.ExitError
		if errors.As(waitErr, &exitErr) {
			exitCode = exitErr.ExitCode()
		} else if !timedOut {
			return execOutput{}, waitErr
		}
	}
	if timedOut {
		exitCode = -1
	}
	return execOutput{
		ExitCode:   exitCode,
		Stdout:     stdout.String(),
		Stderr:     stderr.String(),
		Truncated:  stdout.Truncated() || stderr.Truncated(),
		TimedOut:   timedOut,
		DurationMs: time.Since(started).Milliseconds(),
	}, nil
}

func (s *Server) runExec(ctx context.Context, e *Environment, command, cwd string, timeoutSec int, onProgress func(string)) (execOutput, error) {
	if s.cfg.readOnly {
		return execOutput{}, ErrReadOnly
	}
	if command == "" || len(command) > maxCommandChars {
		return execOutput{}, fmt.Errorf("command must be 1-%d characters", maxCommandChars)
	}
	if timeoutSec == 0 {
		timeoutSec = defaultExecTimeout
	}
	if timeoutSec < 1 || timeoutSec > maxExecTimeout {
		return execOutput{}, fmt.Errorf("timeoutSec must be 1-%d", maxExecTimeout)
	}

	select {
	case s.execSem <- struct{}{}:
		defer func() { <-s.execSem }()
	case <-ctx.Done():
		return execOutput{}, ctx.Err()
	}

	e.lock.RLock()
	defer e.lock.RUnlock()

	if err := s.store.ValidateRoot(e); err != nil {
		return execOutput{}, err
	}
	root, err := canonicalRoot(e.Root(s.cfg.dataDir))
	if err != nil {
		return execOutput{}, err
	}
	cwdFull, err := resolveRead(root, cwd)
	if err != nil {
		return execOutput{}, err
	}
	fi, err := os.Stat(cwdFull)
	if err != nil {
		return execOutput{}, err
	}
	if !fi.IsDir() {
		return execOutput{}, fmt.Errorf("cwd is not a directory")
	}

	shell, shellArgs := shellCommand(command)
	return runProcessCapture(ctx, shell, shellArgs, cwdFull, shellEnv(root, cwdFull), timeoutSec, s.cfg.maxOutput, onProgress, s.cfg.execUser, s.cfg.execGroup)
}

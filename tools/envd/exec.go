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
}

func newCappedBuffer(limit int) *cappedBuffer {
	return &cappedBuffer{limit: limit}
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	room := b.limit - b.buf.Len()
	if room > 0 {
		n := len(p)
		if n > room {
			n = room
		}
		_, _ = b.buf.Write(p[:n])
		b.dropped += int64(len(p) - n)
	} else {
		b.dropped += int64(len(p))
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

func (s *Server) runExec(ctx context.Context, e *Environment, command, cwd string, timeoutSec int) (execOutput, error) {
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

	runCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutSec)*time.Second)
	defer cancel()

	shell, shellArgs := shellCommand(command)
	cmd := exec.Command(shell, shellArgs...)
	cmd.Dir = cwdFull
	cmd.Env = shellEnv(root, cwdFull)
	stdout := newCappedBuffer(s.cfg.maxOutput)
	stderr := newCappedBuffer(s.cfg.maxOutput)
	cmd.Stdout = stdout
	cmd.Stderr = stderr
	setProcessGroup(cmd)

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

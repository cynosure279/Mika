//go:build !windows

package main

import (
	"os"
	"os/exec"
	"syscall"
)

func setProcessGroup(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

func killProcessGroup(cmd *exec.Cmd) {
	if cmd.Process == nil {
		return
	}
	_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
}

func hasMultipleLinks(fi os.FileInfo) bool {
	if st, ok := fi.Sys().(*syscall.Stat_t); ok {
		return st.Nlink > 1
	}
	return false
}

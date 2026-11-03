//go:build windows

package main

import (
	"os"
	"os/exec"
)

func setProcessGroup(cmd *exec.Cmd) {}

func killProcessGroup(cmd *exec.Cmd) {
	if cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

func hasMultipleLinks(fi os.FileInfo) bool {
	return false
}

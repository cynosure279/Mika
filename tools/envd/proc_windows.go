//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
)

func setExecCredential(cmd *exec.Cmd, uid, gid int) error {
	if uid >= 0 {
		return fmt.Errorf("-exec-user is not supported on Windows")
	}
	return nil
}

func validateExecUser(uid, gid int) error {
	if uid >= 0 {
		return fmt.Errorf("-exec-user is not supported on Windows")
	}
	return nil
}

func setGroupUmask() {}

func shellCommand(command string) (string, []string) {
	comspec := os.Getenv("ComSpec")
	if comspec == "" {
		comspec = `C:\Windows\System32\cmd.exe`
	}
	return comspec, []string{"/C", command}
}

// shellEnv keeps only the variables cmd.exe and common CLI tools need. The
// parent environment is not inherited, so server secrets do not reach commands.
func shellEnv(root, cwd string) []string {
	keys := []string{
		"SystemRoot", "SystemDrive", "ComSpec", "WINDIR", "PATHEXT",
		"PATH", "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
	}
	env := make([]string, 0, len(keys)+2)
	for _, key := range keys {
		if value := os.Getenv(key); value != "" {
			env = append(env, key+"="+value)
		}
	}
	env = append(env, "HOME="+root, "MIKA_ENVD=1")
	return env
}

// Windows cannot rename over an existing file. Removing first is not atomic,
// but it keeps the old file until the new one is fully written.
func replaceFile(tmp, full string) error {
	_ = os.Remove(full)
	return os.Rename(tmp, full)
}

func shutdownSignals() []os.Signal {
	return []os.Signal{os.Interrupt}
}

func setProcessGroup(cmd *exec.Cmd) {}

func killProcessGroup(cmd *exec.Cmd) {
	if cmd.Process == nil {
		return
	}
	if err := exec.Command("taskkill", "/T", "/F", "/PID", strconv.Itoa(cmd.Process.Pid)).Run(); err != nil {
		_ = cmd.Process.Kill()
	}
}

func hasMultipleLinks(fi os.FileInfo) bool {
	return false
}

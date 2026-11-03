//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"syscall"
)

func setExecCredential(cmd *exec.Cmd, uid, gid int) error {
	if uid < 0 {
		return nil
	}
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.Credential = &syscall.Credential{Uid: uint32(uid), Gid: uint32(gid), NoSetGroups: true}
	return nil
}

func validateExecUser(uid, gid int) error {
	if uid < 0 {
		return nil
	}
	cmd := exec.Command("/bin/sh", "-c", "true")
	if err := setExecCredential(cmd, uid, gid); err != nil {
		return err
	}
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("cannot run commands as uid %d gid %d: %w", uid, gid, err)
	}
	return nil
}

func setGroupUmask() {
	syscall.Umask(0o002)
}

func shellCommand(command string) (string, []string) {
	return "/bin/sh", []string{"-c", command}
}

func shellEnv(root, cwd string) []string {
	return []string{
		"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
		"HOME=" + root,
		"PWD=" + cwd,
		"TMPDIR=/tmp",
		"LANG=C.UTF-8",
		"LC_ALL=C.UTF-8",
		"TERM=dumb",
		"MIKA_ENVD=1",
	}
}

func replaceFile(tmp, full string) error {
	return os.Rename(tmp, full)
}

func shutdownSignals() []os.Signal {
	return []os.Signal{os.Interrupt, syscall.SIGTERM}
}

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

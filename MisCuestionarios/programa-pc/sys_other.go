//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

func documentsDir() string {
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Documents")
}

func openWindow(url string) {
	if runtime.GOOS == "darwin" {
		exec.Command("open", url).Start()
	} else {
		exec.Command("xdg-open", url).Start()
	}
}

func openFolder(p string) {
	if runtime.GOOS == "darwin" {
		exec.Command("open", p).Start()
	} else {
		exec.Command("xdg-open", p).Start()
	}
}

func fail(msg string) { fmt.Fprintln(os.Stderr, msg); os.Exit(1) }

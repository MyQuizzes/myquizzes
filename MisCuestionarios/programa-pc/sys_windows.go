//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

var (
	shell32  = syscall.NewLazyDLL("shell32.dll")
	ole32    = syscall.NewLazyDLL("ole32.dll")
	user32   = syscall.NewLazyDLL("user32.dll")
	pSHGetKF = shell32.NewProc("SHGetKnownFolderPath")
	pShellEx = shell32.NewProc("ShellExecuteW")
	pCoFree  = ole32.NewProc("CoTaskMemFree")
	pMsgBox  = user32.NewProc("MessageBoxW")
)

// FOLDERID_Documents {FDD39AD0-238F-46AF-ADB4-6C85480369C7}: respeta Documentos movido a OneDrive, a otro disco...
func documentsDir() string {
	g := [16]byte{0xD0, 0x9A, 0xD3, 0xFD, 0x8F, 0x23, 0xAF, 0x46, 0xAD, 0xB4, 0x6C, 0x85, 0x48, 0x03, 0x69, 0xC7}
	var p *uint16
	if r, _, _ := pSHGetKF.Call(uintptr(unsafe.Pointer(&g[0])), 0, 0, uintptr(unsafe.Pointer(&p))); r == 0 && p != nil {
		defer pCoFree.Call(uintptr(unsafe.Pointer(p)))
		return syscall.UTF16ToString((*[1 << 15]uint16)(unsafe.Pointer(p))[:])
	}
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Documents")
}

func hidden(c *exec.Cmd) *exec.Cmd {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return c
}

func shellOpen(target string) {
	v, _ := syscall.UTF16PtrFromString("open")
	t, _ := syscall.UTF16PtrFromString(target)
	pShellEx.Call(0, uintptr(unsafe.Pointer(v)), uintptr(unsafe.Pointer(t)), 0, 0, 1)
}

// Se abre en su propia ventana (Edge o Chrome en modo aplicación); si no hay, en el navegador normal.
func openWindow(url string) {
	env := func(k string) string { return os.Getenv(k) }
	cands := []string{
		filepath.Join(env("ProgramFiles(x86)"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(env("ProgramFiles"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(env("LOCALAPPDATA"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(env("ProgramFiles"), `Google\Chrome\Application\chrome.exe`),
		filepath.Join(env("ProgramFiles(x86)"), `Google\Chrome\Application\chrome.exe`),
		filepath.Join(env("LOCALAPPDATA"), `Google\Chrome\Application\chrome.exe`),
	}
	for _, c := range cands {
		if st, err := os.Stat(c); err == nil && !st.IsDir() {
			if exec.Command(c, "--app="+url, "--window-size=1280,880", "--no-first-run").Start() == nil {
				return
			}
		}
	}
	shellOpen(url)
}

func openFolder(p string) { shellOpen(p) }

func fail(msg string) {
	t, _ := syscall.UTF16PtrFromString(msg)
	c, _ := syscall.UTF16PtrFromString("MyQuizzes")
	pMsgBox.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
	os.Exit(1)
}

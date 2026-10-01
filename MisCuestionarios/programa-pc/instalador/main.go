//go:build windows

// Instalador y desinstalador de MyQuizzes (un solo .exe, sin consola, sin permisos de administrador).
package main

import (
	_ "embed"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

//go:embed payload/myquizzes.exe
var app []byte

const (
	appName = "MyQuizzes"
	version = "1.0.0"
	regKey  = `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\MyQuizzes`
)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")
	ole32    = syscall.NewLazyDLL("ole32.dll")
	pMsgBox  = user32.NewProc("MessageBoxW")
	pUILang  = kernel32.NewProc("GetUserDefaultUILanguage")
	pSHGetKF = shell32.NewProc("SHGetKnownFolderPath")
	pCoFree  = ole32.NewProc("CoTaskMemFree")
)

// ---------------------------------------------------------------- textos (idioma de Windows)
var T = map[string][6]string{ // es, en, fr, pt, it, de
	"ask":     {"¿Instalar MyQuizzes en este equipo?", "Install MyQuizzes on this computer?", "Installer MyQuizzes sur cet ordinateur ?", "Instalar o MyQuizzes neste computador?", "Installare MyQuizzes su questo computer?", "MyQuizzes auf diesem Computer installieren?"},
	"done":    {"MyQuizzes se ha instalado.\n\nTienes un acceso directo en el escritorio y en el menú Inicio.\nTus cuestionarios se guardarán en Documentos\\MyQuizzes.\n\n¿Abrirlo ahora?", "MyQuizzes has been installed.\n\nThere is a shortcut on the desktop and in the Start menu.\nYour quizzes will be saved in Documents\\MyQuizzes.\n\nOpen it now?", "MyQuizzes a été installé.\n\nUn raccourci est sur le bureau et dans le menu Démarrer.\nVos questionnaires seront enregistrés dans Documents\\MyQuizzes.\n\nL’ouvrir maintenant ?", "O MyQuizzes foi instalado.\n\nTens um atalho no ambiente de trabalho e no menu Iniciar.\nOs teus questionários ficam em Documentos\\MyQuizzes.\n\nAbrir agora?", "MyQuizzes è stato installato.\n\nC’è un collegamento sul desktop e nel menu Start.\nI tuoi quiz saranno salvati in Documenti\\MyQuizzes.\n\nAprirlo ora?", "MyQuizzes wurde installiert.\n\nEine Verknüpfung liegt auf dem Desktop und im Startmenü.\nDeine Quizze werden in Dokumente\\MyQuizzes gespeichert.\n\nJetzt öffnen?"},
	"fail":    {"No se ha podido instalar MyQuizzes:\n\n", "MyQuizzes could not be installed:\n\n", "Impossible d’installer MyQuizzes :\n\n", "Não foi possível instalar o MyQuizzes:\n\n", "Impossibile installare MyQuizzes:\n\n", "MyQuizzes konnte nicht installiert werden:\n\n"},
	"unask":   {"¿Desinstalar MyQuizzes?\n\nTus cuestionarios NO se borran: siguen en Documentos\\MyQuizzes.", "Uninstall MyQuizzes?\n\nYour quizzes are NOT deleted: they stay in Documents\\MyQuizzes.", "Désinstaller MyQuizzes ?\n\nVos questionnaires ne sont PAS supprimés : ils restent dans Documents\\MyQuizzes.", "Desinstalar o MyQuizzes?\n\nOs teus questionários NÃO são apagados: continuam em Documentos\\MyQuizzes.", "Disinstallare MyQuizzes?\n\nI tuoi quiz NON vengono eliminati: restano in Documenti\\MyQuizzes.", "MyQuizzes deinstallieren?\n\nDeine Quizze werden NICHT gelöscht: Sie bleiben in Dokumente\\MyQuizzes."},
	"undone":  {"MyQuizzes se ha desinstalado.", "MyQuizzes has been uninstalled.", "MyQuizzes a été désinstallé.", "O MyQuizzes foi desinstalado.", "MyQuizzes è stato disinstallato.", "MyQuizzes wurde deinstalliert."},
	"updated": {"MyQuizzes ya estaba instalado: se ha actualizado.\nTus cuestionarios siguen igual.\n\n¿Abrirlo ahora?", "MyQuizzes was already installed: it has been updated.\nYour quizzes are unchanged.\n\nOpen it now?", "MyQuizzes était déjà installé : il a été mis à jour.\nVos questionnaires sont intacts.\n\nL’ouvrir maintenant ?", "O MyQuizzes já estava instalado: foi atualizado.\nOs teus questionários continuam iguais.\n\nAbrir agora?", "MyQuizzes era già installato: è stato aggiornato.\nI tuoi quiz restano uguali.\n\nAprirlo ora?", "MyQuizzes war schon installiert: Es wurde aktualisiert.\nDeine Quizze bleiben unverändert.\n\nJetzt öffnen?"},
}

func lang() int {
	r, _, _ := pUILang.Call()
	switch r & 0x3ff {
	case 0x09:
		return 1
	case 0x0c:
		return 2
	case 0x16:
		return 3
	case 0x10:
		return 4
	case 0x07:
		return 5
	}
	return 0
}
func tr(k string) string { return T[k][lang()] }

func msg(text string, flags uintptr) int {
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(appName)
	r, _, _ := pMsgBox.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), flags|0x00010000) // MB_SETFOREGROUND
	return int(r)
}

const (
	mbYesNo   = 0x04
	mbIconQ   = 0x20
	mbIconI   = 0x40
	mbIconErr = 0x10
	idYes     = 6
)

func knownFolder(g [16]byte) string {
	var p *uint16
	if r, _, _ := pSHGetKF.Call(uintptr(unsafe.Pointer(&g[0])), 0, 0, uintptr(unsafe.Pointer(&p))); r == 0 && p != nil {
		defer pCoFree.Call(uintptr(unsafe.Pointer(p)))
		return syscall.UTF16ToString((*[1 << 15]uint16)(unsafe.Pointer(p))[:])
	}
	return ""
}

var (
	fDesktop  = [16]byte{0x3A, 0xCC, 0xBF, 0xB4, 0x2C, 0xDB, 0x4C, 0x42, 0xB0, 0x29, 0x7F, 0xE9, 0x9A, 0x87, 0xC6, 0x41} // {B4BFCC3A-DB2C-424C-B029-7FE99A87C641}
	fPrograms = [16]byte{0x77, 0x5D, 0x7F, 0xA7, 0x2B, 0x2E, 0xC3, 0x44, 0xA6, 0xA2, 0xAB, 0xA6, 0x01, 0x05, 0x4A, 0x51} // {A77F5D77-2E2B-44C3-A6A2-ABA601054A51}
)

func run(name string, args ...string) error {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return c.Run()
}

func psq(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

func shortcut(lnk, target, dir string) error {
	os.MkdirAll(filepath.Dir(lnk), 0o755)
	cmd := "$s=(New-Object -ComObject WScript.Shell).CreateShortcut(" + psq(lnk) + ");$s.TargetPath=" + psq(target) +
		";$s.WorkingDirectory=" + psq(dir) + ";$s.IconLocation=" + psq(target+",0") + ";$s.Description='MyQuizzes';$s.Save()"
	return run("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", cmd)
}

func paths() (dir, desk, start string) {
	dir = filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", appName)
	if d := knownFolder(fDesktop); d != "" {
		desk = filepath.Join(d, appName+".lnk")
	}
	p := knownFolder(fPrograms)
	if p == "" {
		p = filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\Start Menu\Programs`)
	}
	start = filepath.Join(p, appName+".lnk")
	return
}

// Si MyQuizzes está abierto, se le pide que se cierre (para poder sustituir el .exe).
func closeRunning() {
	cfg, _ := os.UserConfigDir()
	b, err := os.ReadFile(filepath.Join(cfg, appName, "puerto.txt"))
	if err != nil {
		return
	}
	req, _ := http.NewRequest("POST", "http://127.0.0.1:"+strings.TrimSpace(string(b))+"/api/salir", nil)
	req.Header.Set("X-Requested-With", "creador")
	req.Host = "localhost"
	c := http.Client{Timeout: 2 * time.Second}
	if r, err := c.Do(req); err == nil {
		r.Body.Close()
		time.Sleep(1200 * time.Millisecond)
	}
}

func writeFileRetry(p string, data []byte) error {
	var err error
	for i := 0; i < 20; i++ {
		if err = os.WriteFile(p, data, 0o755); err == nil {
			return nil
		}
		time.Sleep(300 * time.Millisecond)
	}
	return err
}

func install() {
	dir, desk, start := paths()
	exe := filepath.Join(dir, "myquizzes.exe")
	_, statErr := os.Stat(exe)
	update := statErr == nil
	if !update && msg(tr("ask"), mbYesNo|mbIconQ) != idYes {
		return
	}
	closeRunning()
	fail := func(err error) { msg(tr("fail")+err.Error(), mbIconErr) }
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fail(err)
		return
	}
	if err := writeFileRetry(exe, app); err != nil {
		fail(err)
		return
	}
	self, _ := os.Executable()
	if b, err := os.ReadFile(self); err == nil {
		writeFileRetry(filepath.Join(dir, "desinstalar.exe"), b)
	}
	if desk != "" {
		shortcut(desk, exe, dir)
	}
	shortcut(start, exe, dir)
	un := `"` + filepath.Join(dir, "desinstalar.exe") + `" --desinstalar`
	for _, kv := range [][2]string{{"DisplayName", appName}, {"DisplayVersion", version}, {"Publisher", appName},
		{"DisplayIcon", exe + ",0"}, {"InstallLocation", dir}, {"UninstallString", un}, {"QuietUninstallString", un + " --silencio"}} {
		run("reg.exe", "add", regKey, "/v", kv[0], "/t", "REG_SZ", "/d", kv[1], "/f")
	}
	run("reg.exe", "add", regKey, "/v", "NoModify", "/t", "REG_DWORD", "/d", "1", "/f")
	run("reg.exe", "add", regKey, "/v", "NoRepair", "/t", "REG_DWORD", "/d", "1", "/f")
	run("reg.exe", "add", regKey, "/v", "EstimatedSize", "/t", "REG_DWORD", "/d", fmt.Sprint(len(app)/1024+1), "/f")
	k := "done"
	if update {
		k = "updated"
	}
	if msg(tr(k), mbYesNo|mbIconI) == idYes {
		c := exec.Command(exe)
		c.Dir = dir
		c.Start()
	}
}

func uninstall(silent bool) {
	dir, desk, start := paths()
	if !silent && msg(tr("unask"), mbYesNo|mbIconQ) != idYes {
		return
	}
	closeRunning()
	if desk != "" {
		os.Remove(desk)
	}
	os.Remove(start)
	run("reg.exe", "delete", regKey, "/f")
	os.Remove(filepath.Join(dir, "myquizzes.exe"))
	if !silent {
		msg(tr("undone"), mbIconI)
	}
	// este .exe está dentro de la carpeta: se borra cuando termine
	c := exec.Command("cmd.exe", "/c", "ping 127.0.0.1 -n 3 >nul & rmdir /s /q \""+dir+"\"")
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	c.Start()
}

func main() {
	a := strings.Join(os.Args[1:], " ")
	if strings.Contains(a, "--desinstalar") {
		uninstall(strings.Contains(a, "--silencio"))
		return
	}
	install()
}

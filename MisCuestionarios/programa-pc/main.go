// MyQuizzes (PC). Programa sin consola: servidor local + ventana propia (Edge/Chrome en modo app).
// Es el mismo servidor que servidor.py (misma API y mismas protecciones), sin necesitar Python.
package main

import (
	"bytes"
	"crypto/md5"
	"crypto/rand"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed web/index.html
var pageHTML []byte

const (
	appName  = "MyQuizzes"
	wifiPort = 8765 // el móvil descarga de aquí (solo mientras está abierta la conexión, con PIN)
	discPort = 8766 // el móvil pregunta por UDP «¿dónde estás?»
	wifiMin  = 10   // minutos que dura la conexión Wi-Fi
)

var (
	ROOT, CONF, CONFDIR, HTMLFILE string
	LANGS                         = []string{"es", "en", "fr", "pt", "it", "de"}
	lock                          sync.Mutex
	lastSeen                      = time.Now()
	byeAt                         time.Time
	seenMu                        sync.Mutex
)

type M = map[string]any

// ---------------------------------------------------------------- utilidades
var badRe = regexp.MustCompile(`[\\/:*?"<>|\x00-\x1f]`)

func safe(n string) string {
	s := strings.Trim(badRe.ReplaceAllString(n, "-"), " .")
	if r := []rune(s); len(r) > 80 {
		s = string(r[:80])
	}
	if s == "" {
		return "Sin nombre"
	}
	return s
}

func str(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case nil:
		return ""
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	default:
		return fmt.Sprint(x)
	}
}
func list(v any) []any    { l, _ := v.([]any); return l }
func obj(v any) (M, bool) { m, ok := v.(map[string]any); return m, ok }
func truthy(v any) bool   { l, ok := v.([]any); return ok && len(l) > 0 }

func jsonIndent(v any) string { // como json.dumps(..., ensure_ascii=False, indent=1)
	var b bytes.Buffer
	e := json.NewEncoder(&b)
	e.SetEscapeHTML(false)
	e.SetIndent("", " ")
	e.Encode(v)
	return strings.TrimRight(b.String(), "\n")
}
func jsonBytes(v any) []byte {
	var b bytes.Buffer
	e := json.NewEncoder(&b)
	e.SetEscapeHTML(false)
	e.Encode(v)
	return bytes.TrimRight(b.Bytes(), "\n")
}

func hiddenName(n string) bool { return strings.HasPrefix(n, ".") }

// ---------------------------------------------------------------- idioma
func getLang() string {
	b, err := os.ReadFile(CONF)
	if err != nil {
		return ""
	}
	var c M
	if json.Unmarshal(b, &c) != nil {
		return ""
	}
	l := str(c["lang"])
	for _, x := range LANGS {
		if x == l {
			return l
		}
	}
	return ""
}
func setLang(l string) error {
	ok := false
	for _, x := range LANGS {
		ok = ok || x == l
	}
	if !ok {
		return errors.New("idioma")
	}
	os.MkdirAll(filepath.Dir(CONF), 0o755)
	return os.WriteFile(CONF, jsonBytes(M{"lang": l}), 0o644)
}

// ---------------------------------------------------------------- leer la carpeta
func loadQuiz(p string) M {
	b, err := os.ReadFile(p)
	if err != nil {
		return nil
	}
	var v any
	if json.Unmarshal(b, &v) != nil {
		return nil
	}
	q, ok := obj(v)
	if !ok {
		return nil
	}
	if _, has := q["cards"]; !has {
		return nil
	}
	if _, has := q["title"]; !has {
		q["title"] = strings.TrimSuffix(filepath.Base(p), filepath.Ext(p))
	}
	return q
}

func sortedEntries(d string) []os.DirEntry {
	es, _ := os.ReadDir(d)
	sort.SliceStable(es, func(i, j int) bool { return strings.ToLower(es[i].Name()) < strings.ToLower(es[j].Name()) })
	return es
}

func readFolder(p string) M {
	f, q := []any{}, []any{}
	for _, e := range sortedEntries(p) {
		n := e.Name()
		if hiddenName(n) {
			continue
		}
		fp := filepath.Join(p, n)
		if st, err := os.Stat(fp); err == nil && st.IsDir() {
			sub := readFolder(fp)
			sub["n"] = n
			f = append(f, sub)
		} else if strings.HasSuffix(strings.ToLower(n), ".json") {
			if x := loadQuiz(fp); x != nil {
				q = append(q, x)
			}
		}
	}
	return M{"f": f, "q": q}
}

func readTree() M {
	os.MkdirAll(ROOT, 0o755)
	t := readFolder(ROOT)
	t["n"] = "Mis cuestionarios"
	return t
}

// ---------------------------------------------------------------- reglas (segunda barrera)
type badReq struct{ msg string }

func (e badReq) Error() string { return e.msg }

type permErr struct{ msg string }

func (e permErr) Error() string { return e.msg }

func badName(n string) bool {
	t := strings.TrimSpace(n)
	return t == "" || t == "." || t == ".." || badRe.MatchString(n) || strings.HasSuffix(t, ".") || len([]rune(n)) > 80
}

func dup(names []string) bool {
	s := map[string]bool{}
	for _, n := range names {
		k := strings.ToLower(strings.TrimSpace(n))
		if s[k] {
			return true
		}
		s[k] = true
	}
	return false
}

func check(tv any) error {
	t, ok := obj(tv)
	if !ok {
		return badReq{"Datos no válidos."}
	}
	if truthy(t["q"]) {
		return badReq{"No puede haber cuestionarios sueltos en «Mis cuestionarios»."}
	}
	A := list(t["f"])
	var an []string
	for _, av := range A {
		a, ok := obj(av)
		if !ok {
			return badReq{"Datos no válidos."}
		}
		an = append(an, str(a["n"]))
	}
	if dup(an) {
		return badReq{"Hay dos asignaturas con el mismo nombre."}
	}
	for _, av := range A {
		a, _ := obj(av)
		if badName(str(a["n"])) {
			return badReq{fmt.Sprintf("Nombre de asignatura no válido: «%s».", str(a["n"]))}
		}
		if truthy(a["q"]) {
			return badReq{fmt.Sprintf("«%s»: los cuestionarios van dentro de un tema.", str(a["n"]))}
		}
		var tn []string
		for _, xv := range list(a["f"]) {
			x, ok := obj(xv)
			if !ok {
				return badReq{"Datos no válidos."}
			}
			tn = append(tn, str(x["n"]))
		}
		if dup(tn) {
			return badReq{fmt.Sprintf("«%s»: hay dos temas con el mismo nombre.", str(a["n"]))}
		}
		for _, xv := range list(a["f"]) {
			x, _ := obj(xv)
			if badName(str(x["n"])) {
				return badReq{fmt.Sprintf("Nombre de tema no válido: «%s».", str(x["n"]))}
			}
			if truthy(x["f"]) {
				return badReq{fmt.Sprintf("«%s › %s»: no puede haber carpetas dentro de un tema.", str(a["n"]), str(x["n"]))}
			}
			var qn []string
			for _, cv := range list(x["q"]) {
				c, ok := obj(cv)
				if !ok {
					return badReq{"Datos no válidos."}
				}
				qn = append(qn, str(c["title"]))
			}
			if dup(qn) {
				return badReq{fmt.Sprintf("«%s › %s»: hay dos cuestionarios con el mismo nombre.", str(a["n"]), str(x["n"]))}
			}
			for _, t := range qn {
				if badName(t) {
					return badReq{fmt.Sprintf("Nombre de cuestionario no válido: «%s».", t)}
				}
			}
		}
	}
	return nil
}

// ---------------------------------------------------------------- juntar con el disco (guardado al cerrar)
func union(t M, disk M) M {
	k := func(x M, f string) string { return strings.ToLower(strings.TrimSpace(str(x[f]))) }
	var out M
	json.Unmarshal(jsonBytes(t), &out)
	if out == nil {
		return t
	}
	of := list(out["f"])
	for _, av := range list(disk["f"]) {
		a, _ := obj(av)
		var A M
		for _, x := range of {
			if xm, _ := obj(x); xm != nil && k(xm, "n") == k(a, "n") {
				A = xm
			}
		}
		if A == nil {
			A = M{"n": a["n"], "f": []any{}, "q": []any{}}
			of = append(of, A)
		}
		af := list(A["f"])
		for _, tv := range list(a["f"]) {
			tm, _ := obj(tv)
			var X M
			for _, x := range af {
				if xm, _ := obj(x); xm != nil && k(xm, "n") == k(tm, "n") {
					X = xm
				}
			}
			if X == nil {
				X = M{"n": tm["n"], "f": []any{}, "q": []any{}}
				af = append(af, X)
			}
			xq := list(X["q"])
			for _, qv := range list(tm["q"]) {
				q, _ := obj(qv)
				found := false
				for _, y := range xq {
					if ym, _ := obj(y); ym != nil && k(ym, "title") == k(q, "title") {
						found = true
					}
				}
				if !found {
					xq = append(xq, q)
				}
			}
			X["q"] = xq
		}
		A["f"] = af
	}
	out["f"] = of
	if check(out) != nil {
		return t
	}
	return out
}

// ---------------------------------------------------------------- guardar
var junk = map[string]bool{".ds_store": true, "thumbs.db": true, "desktop.ini": true}

func normp(p string) string { a, _ := filepath.Abs(p); return strings.ToLower(filepath.Clean(a)) }

func writeTree(tv any, allowDel bool) error {
	if err := check(tv); err != nil {
		return err
	}
	t, _ := obj(tv)
	os.MkdirAll(ROOT, 0o755)
	type item struct{ p, data string }
	var plan []item
	dirs := map[string]bool{ROOT: true}
	uniq := func(used map[string]bool, n, ext string) string {
		base := safe(n)
		c := base
		for i := 2; used[strings.ToLower(c+ext)]; i++ {
			c = fmt.Sprintf("%s (%d)", base, i)
		}
		used[strings.ToLower(c+ext)] = true
		return c + ext
	}
	var wf func(d string, f M, depth int)
	wf = func(d string, f M, depth int) {
		dirs[d] = true
		used := map[string]bool{}
		if depth < 2 {
			for _, sv := range list(f["f"]) {
				if s, ok := obj(sv); ok {
					wf(filepath.Join(d, uniq(used, str(s["n"]), "")), s, depth+1)
				}
			}
		} else {
			for _, qv := range list(f["q"]) {
				if q, ok := obj(qv); ok {
					plan = append(plan, item{filepath.Join(d, uniq(used, str(q["title"]), ".json")), jsonIndent(q)})
				}
			}
		}
	}
	wf(ROOT, t, 0)
	keep := map[string]bool{}
	for d := range dirs {
		keep[normp(d)] = true
	}
	for _, it := range plan {
		keep[normp(it.p)] = true
	}
	var doomed []string
	filepath.WalkDir(ROOT, func(p string, e os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if p != ROOT && hiddenName(e.Name()) {
			if e.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if !e.IsDir() && !keep[normp(p)] && strings.HasSuffix(strings.ToLower(e.Name()), ".json") && loadQuiz(p) != nil {
			doomed = append(doomed, p)
		}
		return nil
	})
	if len(doomed) > 2 && !allowDel {
		return permErr{fmt.Sprintf("Se iban a borrar %d cuestionarios de golpe y no lo has pedido: no he guardado nada.", len(doomed))}
	}
	var ds []string
	for d := range dirs {
		ds = append(ds, d)
	}
	sort.Strings(ds)
	for _, d := range ds {
		os.MkdirAll(d, 0o755)
	}
	for _, it := range plan {
		if old, err := os.ReadFile(it.p); err == nil && string(old) == it.data {
			continue
		}
		if err := os.WriteFile(it.p+".tmp", []byte(it.data), 0o644); err != nil {
			return err
		}
		if err := os.Rename(it.p+".tmp", it.p); err != nil {
			os.Remove(it.p)
			if err := os.Rename(it.p+".tmp", it.p); err != nil {
				return err
			}
		}
	}
	for _, p := range doomed {
		os.Remove(p)
	}
	// carpetas que ya no se usan: se quitan solo si quedan vacías (de dentro hacia fuera)
	var all []string
	filepath.WalkDir(ROOT, func(p string, e os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if p != ROOT && hiddenName(e.Name()) {
			if e.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if e.IsDir() && p != ROOT {
			all = append(all, p)
		}
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".json.tmp") {
			os.Remove(p)
		}
		return nil
	})
	sort.Slice(all, func(i, j int) bool { return len(all[i]) > len(all[j]) })
	for _, p := range all {
		if keep[normp(p)] {
			continue
		}
		es, _ := os.ReadDir(p)
		for _, e := range es {
			if junk[strings.ToLower(e.Name())] || strings.HasPrefix(e.Name(), "._") {
				os.Remove(filepath.Join(p, e.Name()))
			}
		}
		os.Remove(p) // solo si queda vacía
	}
	return nil
}

func stamp() string {
	h := md5.New()
	filepath.WalkDir(ROOT, func(p string, e os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if p != ROOT && hiddenName(e.Name()) {
			if e.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if p == ROOT {
			return nil
		}
		if st, err := e.Info(); err == nil {
			fmt.Fprintf(h, "%s%d%d", p, st.ModTime().UnixNano(), st.Size())
		}
		return nil
	})
	return hex.EncodeToString(h.Sum(nil))
}

// ---------------------------------------------------------------- Wi-Fi con el móvil
type wifiState struct {
	sync.Mutex
	srv                   *http.Server
	udp                   net.PacketConn
	pin                   string
	until                 time.Time
	sent, fails, got, gen int
	port                  int
	inbox                 any
	inboxN                int
}

var W wifiState

func (w *wifiState) active() bool { return w.srv != nil && time.Now().Before(w.until) }

func localIPs() []string {
	var ips []string
	if c, err := net.Dial("udp", "10.255.255.255:1"); err == nil { // no envía nada: solo elige la interfaz de la red local
		ips = append(ips, c.LocalAddr().(*net.UDPAddr).IP.String())
		c.Close()
	}
	if as, err := net.InterfaceAddrs(); err == nil {
		for _, a := range as {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil {
				s := n.IP.String()
				found := false
				for _, x := range ips {
					found = found || x == s
				}
				if !found {
					ips = append(ips, s)
				}
			}
		}
	}
	out := []string{}
	for _, x := range ips {
		if !strings.HasPrefix(x, "127.") && !strings.HasPrefix(x, "169.254.") {
			out = append(out, x)
		}
	}
	return out
}

func randPin() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(10000))
	return fmt.Sprintf("%04d", n.Int64())
}

func (w *wifiState) start() error {
	w.Lock()
	defer w.Unlock()
	w.stopLocked()
	w.pin = randPin()
	w.until = time.Now().Add(wifiMin * time.Minute)
	w.sent, w.fails, w.got = 0, 0, 0
	w.gen++
	gen := w.gen
	var ln net.Listener
	var err error
	for p := wifiPort; p < wifiPort+20; p++ {
		if ln, err = net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", p)); err == nil {
			w.port = p
			break
		}
	}
	if ln == nil {
		return errors.New("No hay ningún puerto libre para la Wi-Fi.")
	}
	srv := &http.Server{Handler: http.HandlerFunc(wifiHandler), ReadHeaderTimeout: 20 * time.Second}
	w.srv = srv
	go srv.Serve(ln)
	if u, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", discPort)); err == nil {
		w.udp = u
		go w.answer(u)
	}
	time.AfterFunc(wifiMin*time.Minute+time.Second, func() { w.stop(gen) })
	return nil
}

func (w *wifiState) answer(u net.PacketConn) {
	buf := make([]byte, 512)
	host, _ := os.Hostname()
	for {
		n, a, err := u.ReadFrom(buf)
		if err != nil {
			return
		}
		w.Lock()
		ok := w.udp == u && w.active()
		port := w.port
		w.Unlock()
		if ok && strings.TrimSpace(string(buf[:n])) == "MISCUESTIONARIOS?" {
			u.WriteTo([]byte(fmt.Sprintf("MISCUESTIONARIOS %d %s", port, host)), a)
		}
	}
}

func (w *wifiState) stop(gen int) {
	w.Lock()
	defer w.Unlock()
	if gen == 0 || gen == w.gen {
		w.stopLocked()
	}
}
func (w *wifiState) stopLocked() {
	if w.srv != nil {
		s := w.srv
		go s.Close()
	}
	if w.udp != nil {
		w.udp.Close()
	}
	w.srv, w.udp, w.pin = nil, nil, ""
}

func (w *wifiState) status() M {
	w.Lock()
	if w.srv != nil && !w.active() {
		w.stopLocked()
	}
	act := w.active()
	var pin any
	if act {
		pin = w.pin
	}
	var port any
	if w.port != 0 {
		port = w.port
	}
	q := int(time.Until(w.until).Seconds())
	if q < 0 {
		q = 0
	}
	m := M{"activo": act, "pin": pin, "port": port, "ips": localIPs(), "enviados": w.sent, "recibidos": w.got, "inbox": w.inboxN, "quedan": q}
	w.Unlock()
	return m
}

func sendJSON(rw http.ResponseWriter, code int, v any) {
	b := jsonBytes(v)
	rw.Header().Set("Content-Type", "application/json; charset=utf-8")
	rw.Header().Set("Cache-Control", "no-store")
	rw.Header().Set("Content-Length", fmt.Sprint(len(b)))
	rw.WriteHeader(code)
	rw.Write(b)
}

func (w *wifiState) pinOK(r *http.Request) bool {
	w.Lock()
	defer w.Unlock()
	p := r.URL.Query().Get("pin")
	if w.pin != "" && subtle.ConstantTimeCompare([]byte(p), []byte(w.pin)) == 1 {
		return true
	}
	w.fails++
	if w.fails >= 10 { // demasiados intentos: se corta
		w.stopLocked()
	}
	return false
}

// Lo único que se ve desde la red: la biblioteca, y solo con el PIN correcto.
func wifiHandler(rw http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == "GET" && r.URL.Path == "/mc/hola":
		sendJSON(rw, 200, M{"app": "MisCuestionarios"})
	case r.Method == "GET" && r.URL.Path == "/mc/tree":
		W.Lock()
		act := W.active()
		W.Unlock()
		if !act {
			sendJSON(rw, 410, M{"error": "cerrado"})
			return
		}
		if !W.pinOK(r) {
			sendJSON(rw, 403, M{"error": "pin"})
			return
		}
		W.Lock()
		W.sent++
		W.Unlock()
		lock.Lock()
		t := readTree()
		lock.Unlock()
		sendJSON(rw, 200, t)
	case r.Method == "POST" && r.URL.Path == "/mc/subir":
		W.Lock()
		act := W.active()
		W.Unlock()
		if !act {
			sendJSON(rw, 410, M{"error": "cerrado"})
			return
		}
		if !W.pinOK(r) {
			sendJSON(rw, 403, M{"error": "pin"})
			return
		}
		if r.ContentLength > 300<<20 {
			sendJSON(rw, 413, M{"error": "grande"})
			return
		}
		b, err := io.ReadAll(io.LimitReader(r.Body, 300<<20+1))
		var v any
		if err != nil || json.Unmarshal(b, &v) != nil {
			sendJSON(rw, 400, M{"error": "datos"})
			return
		}
		t, ok := obj(v)
		if !ok || t["f"] == nil {
			sendJSON(rw, 400, M{"error": "datos"})
			return
		}
		if _, isList := t["f"].([]any); !isList {
			sendJSON(rw, 400, M{"error": "datos"})
			return
		}
		n := 0
		for _, a := range list(t["f"]) {
			if am, ok := obj(a); ok {
				for _, x := range list(am["f"]) {
					if xm, ok := obj(x); ok {
						n += len(list(xm["q"]))
					}
				}
			}
		}
		W.Lock()
		W.inbox, W.inboxN, W.got = t, W.inboxN+1, W.got+1
		W.Unlock()
		sendJSON(rw, 200, M{"ok": true, "n": n})
	default:
		sendJSON(rw, 404, M{})
	}
}

// ---------------------------------------------------------------- servidor de la ventana (solo este PC)
func touch() { seenMu.Lock(); lastSeen = time.Now(); byeAt = time.Time{}; seenMu.Unlock() }

func handler(rw http.ResponseWriter, r *http.Request) {
	host := r.Host
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	if host != "localhost" && host != "127.0.0.1" { // solo esta máquina y solo nuestra página
		sendJSON(rw, 403, M{})
		return
	}
	p := r.URL.Path
	if r.Method != "GET" && r.Header.Get("X-Requested-With") != "creador" {
		sendJSON(rw, 403, M{})
		return
	}
	if p != "/api/adios" {
		touch()
	}
	body := func() ([]byte, error) { return io.ReadAll(io.LimitReader(r.Body, 1<<30)) }
	switch {
	case r.Method == "GET" && (p == "/" || p == "/index.html"):
		page := pageHTML
		if HTMLFILE != "" {
			if b, err := os.ReadFile(HTMLFILE); err == nil {
				page = b
			}
		}
		if l := getLang(); l != "" {
			page = bytes.Replace(page, []byte("<head>"), []byte(`<head><script>window.MQ_LANG="`+l+`"</script>`), 1)
		}
		rw.Header().Set("Content-Type", "text/html; charset=utf-8")
		rw.Header().Set("Cache-Control", "no-store")
		rw.Write(page)
	case r.Method == "GET" && p == "/api/tree":
		lock.Lock()
		m := M{"tree": readTree(), "stamp": stamp(), "ruta": ROOT}
		lock.Unlock()
		sendJSON(rw, 200, m)
	case r.Method == "GET" && p == "/api/stamp":
		sendJSON(rw, 200, M{"stamp": stamp()})
	case r.Method == "GET" && p == "/api/hola":
		sendJSON(rw, 200, M{"app": appName, "root": ROOT})
	case r.Method == "GET" && p == "/api/wifi":
		sendJSON(rw, 200, W.status())
	case r.Method == "GET" && p == "/api/wifi/inbox":
		W.Lock()
		v := W.inbox
		W.inbox = nil
		W.Unlock()
		sendJSON(rw, 200, v)
	case r.Method == "PUT" && p == "/api/tree":
		b, _ := body()
		var t any
		if err := json.Unmarshal(b, &t); err != nil {
			sendJSON(rw, 400, M{"error": "Datos no válidos."})
			return
		}
		lock.Lock()
		base := r.Header.Get("X-Base")
		if base != "" && base != stamp() { // la carpeta cambió (otra pestaña, a mano, el móvil...)
			if r.Header.Get("X-Final") != "1" {
				m := M{"tree": readTree(), "stamp": stamp()}
				lock.Unlock()
				sendJSON(rw, 409, m)
				return
			}
			if tm, ok := obj(t); ok { // guardado al cerrar: se junta aquí para no perder nada
				t = union(tm, readTree())
			}
		}
		err := writeTree(t, r.Header.Get("X-Borrar") == "1")
		st := stamp()
		lock.Unlock()
		var pe permErr
		var be badReq
		switch {
		case err == nil:
			sendJSON(rw, 200, M{"stamp": st})
		case errors.As(err, &pe):
			sendJSON(rw, 409, M{"error": pe.msg})
		case errors.As(err, &be):
			sendJSON(rw, 400, M{"error": be.msg})
		default:
			sendJSON(rw, 500, M{"error": err.Error()})
		}
	case r.Method == "POST" && p == "/api/abrir":
		b, _ := body()
		var v struct{ Path []string }
		json.Unmarshal(b, &v)
		d := ROOT
		for i, part := range v.Path {
			if i >= 3 {
				break
			}
			s := safe(part)
			if s == "." || s == ".." {
				break
			}
			hit := ""
			for _, e := range sortedEntries(d) {
				if e.IsDir() && !hiddenName(e.Name()) && strings.EqualFold(e.Name(), s) {
					hit = e.Name()
				}
			}
			if hit == "" {
				break
			}
			d = filepath.Join(d, hit)
		}
		openFolder(d)
		sendJSON(rw, 200, M{"ok": true, "ruta": d})
	case r.Method == "POST" && p == "/api/lang":
		b, _ := body()
		var v struct{ Lang string }
		json.Unmarshal(b, &v)
		if err := setLang(v.Lang); err != nil {
			sendJSON(rw, 400, M{"error": err.Error()})
			return
		}
		sendJSON(rw, 200, M{})
	case r.Method == "POST" && p == "/api/wifi":
		b, _ := body()
		var v struct{ Accion string }
		json.Unmarshal(b, &v)
		var err error
		if v.Accion == "start" {
			err = W.start()
		} else {
			W.stop(0)
		}
		if err != nil {
			sendJSON(rw, 500, M{"error": err.Error()})
			return
		}
		sendJSON(rw, 200, W.status())
	case r.Method == "POST" && p == "/api/adios": // la ventana se ha cerrado (o se recarga)
		seenMu.Lock()
		byeAt = time.Now()
		seenMu.Unlock()
		sendJSON(rw, 200, M{})
	case r.Method == "POST" && p == "/api/salir":
		sendJSON(rw, 200, M{})
		go func() { time.Sleep(300 * time.Millisecond); os.Exit(0) }()
	default:
		sendJSON(rw, 404, M{})
	}
}

// ---------------------------------------------------------------- arranque
func main() {
	base := flag.String("base", "", "carpeta de pruebas: usa <base>/Mis cuestionarios y <base>/config.json")
	noOpen := flag.Bool("no-abrir", false, "no abrir la ventana")
	html := flag.String("html", "", "usar este HTML en vez del incluido (desarrollo)")
	idle := flag.Duration("inactivo", 3*time.Minute, "cerrar si nadie usa el programa este tiempo (0 = nunca)")
	flag.Parse()
	HTMLFILE = *html
	if *base != "" {
		ROOT = filepath.Join(*base, "Mis cuestionarios")
		CONFDIR = *base
		CONF = filepath.Join(*base, "config.json")
		*idle = 0
	} else {
		ROOT = filepath.Join(documentsDir(), appName)
		cd, _ := os.UserConfigDir()
		CONFDIR = filepath.Join(cd, appName)
		CONF = filepath.Join(CONFDIR, "config.json")
	}
	os.MkdirAll(CONFDIR, 0o755)
	if lf, err := os.OpenFile(filepath.Join(CONFDIR, "registro.txt"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644); err == nil && *base == "" {
		log.SetOutput(lf)
	}
	os.MkdirAll(ROOT, 0o755)
	portFile := filepath.Join(CONFDIR, "puerto.txt")
	// ¿ya está abierto? → solo se abre otra ventana
	if *base == "" {
		if b, err := os.ReadFile(portFile); err == nil {
			url := "http://localhost:" + strings.TrimSpace(string(b)) + "/"
			c := http.Client{Timeout: 2 * time.Second}
			if r, err := c.Get(url + "api/hola"); err == nil {
				var v M
				json.NewDecoder(r.Body).Decode(&v)
				r.Body.Close()
				if str(v["app"]) == appName {
					openWindow(url)
					return
				}
			}
		}
	}
	var ln net.Listener
	var err error
	port := 0
	for p := 8080; p < 8100; p++ {
		if ln, err = net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p)); err == nil {
			port = p
			break
		}
	}
	if ln == nil {
		fail("No hay ningún puerto libre (8080-8099).")
		return
	}
	url := fmt.Sprintf("http://localhost:%d/", port)
	fmt.Println(appName, "está en marcha en", url)
	fmt.Println("Tus datos se guardan en:", ROOT)
	if *base == "" {
		os.WriteFile(portFile, []byte(fmt.Sprint(port)), 0o644)
		defer os.Remove(portFile)
	}
	log.Println("en marcha", url, ROOT)
	if !*noOpen {
		go func() { time.Sleep(150 * time.Millisecond); openWindow(url) }()
	}
	if *idle > 0 { // sin ventana abierta un rato → se cierra solo
		go func() {
			for range time.Tick(5 * time.Second) {
				seenMu.Lock()
				gone := time.Since(lastSeen) > *idle || (!byeAt.IsZero() && time.Since(byeAt) > 20*time.Second)
				seenMu.Unlock()
				W.Lock()
				wifi := W.active()
				W.Unlock()
				if gone && !wifi {
					log.Println("cerrado por inactividad")
					os.Remove(portFile)
					os.Exit(0)
				}
			}
		}()
	}
	srv := &http.Server{Handler: http.HandlerFunc(handler), ReadHeaderTimeout: 20 * time.Second}
	if err := srv.Serve(ln); err != nil {
		log.Println(err)
	}
}

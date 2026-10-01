#!/usr/bin/env python3
"""Mis cuestionarios (versión PC). Servidor local: solo usa lo que trae Python."""
import http.server, json, os, re, sys, threading, webbrowser, subprocess, shutil, hashlib, socket, secrets, hmac, time

BASE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(BASE, 'Mis cuestionarios')
OUT = os.path.join(BASE, 'Para el móvil')
HTML = os.path.join(BASE, 'creador-cuestionarios.html')
CONF = os.path.join(BASE, 'config.json')          # idioma elegido
LANGS = ('es', 'en', 'fr', 'pt', 'it', 'de')
PORT = 8080
WIFI_PORT = 8765          # el móvil descarga de aquí (solo mientras se está enviando, con PIN)
DISC_PORT = 8766          # el móvil pregunta por UDP «¿dónde estás?»
WIFI_MIN = 10             # minutos que dura el envío

def get_lang():
    try:
        with open(CONF, encoding='utf-8') as f:
            l = json.load(f).get('lang')
        return l if l in LANGS else None
    except Exception:
        return None

def set_lang(l):
    if l not in LANGS: raise ValueError('idioma')
    with open(CONF, 'w', encoding='utf-8') as f: json.dump({'lang': l}, f)

def safe(n):
    return re.sub(r'[\\/:*?"<>|\x00-\x1f]', '-', str(n)).strip(' .')[:80] or 'Sin nombre'

def load_quiz(p):
    try:
        with open(p, encoding='utf-8') as f:
            q = json.load(f)
        if isinstance(q, dict) and 'cards' in q:
            q.setdefault('title', os.path.splitext(os.path.basename(p))[0])
            return q
    except Exception:
        pass
    return None

def read_tree():
    """Lee TODO lo que haya en la carpeta (aunque esté mal colocado); el creador lo ordena."""
    os.makedirs(ROOT, exist_ok=True)
    def folder(p):
        f, q = [], []
        for n in sorted(os.listdir(p), key=str.lower):
            if n.startswith('.'): continue
            fp = os.path.join(p, n)
            if os.path.isdir(fp):
                f.append(dict(n=n, **folder(fp)))
            elif n.lower().endswith('.json'):
                x = load_quiz(fp)
                if x: q.append(x)
        return {'f': f, 'q': q}
    return dict(n='Mis cuestionarios', **folder(ROOT))

def bad_name(n):
    n = str(n)
    return (not n.strip()) or n.strip() in ('.', '..') or re.search(r'[\\/:*?"<>|\x00-\x1f]', n) or n.strip().endswith('.') or len(n) > 80

def check(t):
    """Segunda barrera: reglas de estructura y nombres (el creador ya las aplica)."""
    def dup(names):
        low = [x.strip().lower() for x in names]
        return len(set(low)) < len(low)
    if not isinstance(t, dict): raise ValueError('Datos no válidos.')
    if t.get('q'): raise ValueError('No puede haber cuestionarios sueltos en «Mis cuestionarios».')
    A = t.get('f', [])
    if dup([a.get('n', '') for a in A]): raise ValueError('Hay dos asignaturas con el mismo nombre.')
    for a in A:
        if bad_name(a.get('n', '')): raise ValueError('Nombre de asignatura no válido: «%s».' % a.get('n', ''))
        if a.get('q'): raise ValueError('«%s»: los cuestionarios van dentro de un tema.' % a['n'])
        T = a.get('f', [])
        if dup([x.get('n', '') for x in T]): raise ValueError('«%s»: hay dos temas con el mismo nombre.' % a['n'])
        for x in T:
            if bad_name(x.get('n', '')): raise ValueError('Nombre de tema no válido: «%s».' % x.get('n', ''))
            if x.get('f'): raise ValueError('«%s › %s»: no puede haber carpetas dentro de un tema.' % (a['n'], x['n']))
            Q = x.get('q', [])
            if dup([c.get('title', '') for c in Q]): raise ValueError('«%s › %s»: hay dos cuestionarios con el mismo nombre.' % (a['n'], x['n']))
            for c in Q:
                if bad_name(c.get('title', '')): raise ValueError('Nombre de cuestionario no válido: «%s».' % c.get('title', ''))

JUNK = {'.ds_store', 'thumbs.db', 'desktop.ini'}

LOCK = threading.Lock()

def union(t, disk):
    """Añade a t lo que hay en el disco y t no tiene (asignaturas, temas y cuestionarios)."""
    k = lambda x, f: str(x.get(f, '')).strip().lower() if isinstance(x, dict) else ''
    try:
        out = json.loads(json.dumps(t))
        for a in disk.get('f', []):
            A = next((x for x in out['f'] if k(x, 'n') == k(a, 'n')), None)
            if A is None:
                A = {'n': a.get('n'), 'f': [], 'q': []}; out['f'].append(A)
            for tm in a.get('f', []):
                X = next((x for x in A['f'] if k(x, 'n') == k(tm, 'n')), None)
                if X is None:
                    X = {'n': tm.get('n'), 'f': [], 'q': []}; A['f'].append(X)
                for q in tm.get('q', []):
                    if not any(k(y, 'title') == k(q, 'title') for y in X['q']): X['q'].append(q)
        check(out)
        return out
    except Exception:
        return t

class Conflict(Exception):
    pass

def hidden(p):
    rel = os.path.relpath(p, ROOT)
    return any(x.startswith('.') for x in rel.split(os.sep) if x not in ('', '.'))

def write_tree(t, allow_del=False):
    """Guarda el árbol. Nunca toca carpetas ocultas ni archivos que no sean cuestionarios.
    Si se iban a borrar más de 2 cuestionarios sin que el usuario lo haya pedido, no hace nada."""
    check(t)
    os.makedirs(ROOT, exist_ok=True)
    plan = []          # (ruta, datos) de cada cuestionario
    dirs = set([ROOT])
    def uniq(used, n, ext=''):
        base = safe(n); c = base; i = 2
        while (c + ext).lower() in used:
            c = '%s (%d)' % (base, i); i += 1
        used.add((c + ext).lower())
        return c + ext
    def wf(d, f, depth):
        dirs.add(d)
        used = set()
        if depth < 2:
            for sub in f.get('f', []):
                wf(os.path.join(d, uniq(used, sub.get('n', ''))), sub, depth + 1)
        else:
            for q in f.get('q', []):
                plan.append((os.path.join(d, uniq(used, q.get('title', ''), '.json')), json.dumps(q, ensure_ascii=False, indent=1)))
    wf(ROOT, t, 0)
    keep = set(p for p, _ in plan) | dirs
    norm = lambda p: os.path.normcase(os.path.abspath(p))
    keepn = set(norm(p) for p in keep)
    doomed = []
    for d, ds, files in os.walk(ROOT):
        ds[:] = [x for x in ds if not x.startswith('.')]
        for n in files:
            p = os.path.join(d, n)
            if norm(p) not in keepn and n.lower().endswith('.json') and load_quiz(p): doomed.append(p)
    if len(doomed) > 2 and not allow_del:
        raise PermissionError('Se iban a borrar %d cuestionarios de golpe y no lo has pedido: no he guardado nada.' % len(doomed))
    for d in sorted(dirs): os.makedirs(d, exist_ok=True)
    for p, data in plan:
        try:
            same = open(p, encoding='utf-8').read() == data
        except OSError:
            same = False
        if not same:
            with open(p + '.tmp', 'w', encoding='utf-8') as f: f.write(data)
            os.replace(p + '.tmp', p)
    for p in doomed:
        try: os.remove(p)
        except OSError: pass
    for d, ds, files in os.walk(ROOT, topdown=False):
        if hidden(d): continue
        for n in ds:
            p = os.path.join(d, n)
            if n.startswith('.') or norm(p) in keepn: continue
            try:
                for j in os.listdir(p):
                    if j.lower() in JUNK or j.startswith('._'):
                        try: os.remove(os.path.join(p, j))
                        except OSError: pass
                os.rmdir(p)           # solo si queda vacía
            except OSError: pass

def stamp():
    h = hashlib.md5()
    for d, dirs, files in os.walk(ROOT):
        dirs[:] = sorted(x for x in dirs if not x.startswith('.'))
        for n in sorted(dirs + files):
            p = os.path.join(d, n)
            try:
                s = os.stat(p); h.update(('%s%d%d' % (p, s.st_mtime_ns, s.st_size)).encode())
            except OSError: pass
    return h.hexdigest()

def open_folder(p):
    try:
        if sys.platform.startswith('win'): os.startfile(p)
        elif sys.platform == 'darwin': subprocess.Popen(['open', p])
        else: subprocess.Popen(['xdg-open', p])
    except Exception: pass

def try_adb(path):
    """Si hay 'adb' y un móvil con depuración USB autorizada, lo copia a Descargas."""
    try:
        adb = shutil.which('adb')
        if not adb: return False
        out = subprocess.run([adb, 'devices'], capture_output=True, text=True, timeout=10).stdout
        if not any(l.strip().endswith('\tdevice') for l in out.splitlines()[1:]): return False
        r = subprocess.run([adb, 'push', path, '/sdcard/Download/'], capture_output=True, timeout=120)
        return r.returncode == 0
    except Exception:
        return False


# ---------------------------------------------------------------- envío al móvil por Wi-Fi
def local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('10.255.255.255', 1))          # no envía nada: solo elige la interfaz de la red local
        ips.append(s.getsockname()[0]); s.close()
    except OSError: pass
    try:
        for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if i[4][0] not in ips: ips.append(i[4][0])
    except OSError: pass
    return [x for x in ips if not x.startswith('127.') and not x.startswith('169.254.')]

class Wifi:
    def __init__(self):
        self.lock = threading.Lock(); self.srv = self.udp = None
        self.pin = None; self.until = 0; self.sent = 0; self.fails = 0; self.port = None; self.gen = 0
        self.got = 0; self.inbox = None; self.inbox_n = 0
    def active(self):
        return self.srv is not None and time.time() < self.until
    def start(self):
        with self.lock:
            self._stop()
            self.pin = '%04d' % secrets.randbelow(10000); self.until = time.time() + WIFI_MIN * 60
            self.sent = self.fails = self.got = 0; self.gen += 1; gen = self.gen
            for p in range(WIFI_PORT, WIFI_PORT + 20):
                try:
                    self.srv = http.server.ThreadingHTTPServer(('0.0.0.0', p), HW); self.port = p; break
                except OSError: continue
            if not self.srv: raise OSError('No hay ningún puerto libre para la Wi-Fi.')
            threading.Thread(target=self.srv.serve_forever, daemon=True).start()
            try:
                u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                u.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                u.bind(('', DISC_PORT)); u.settimeout(1); self.udp = u
                threading.Thread(target=self._answer, args=(u,), daemon=True).start()
            except OSError:
                self.udp = None                     # sin descubrimiento: el móvil puede escribir la dirección
            threading.Timer(WIFI_MIN * 60 + 1, lambda: self.stop(gen)).start()
    def _answer(self, u):
        while self.udp is u:
            try: d, a = u.recvfrom(512)
            except socket.timeout: continue
            except OSError: break
            if d.strip() == b'MISCUESTIONARIOS?' and self.active():
                try: u.sendto(('MISCUESTIONARIOS %d %s' % (self.port, socket.gethostname())).encode('utf-8'), a)
                except OSError: pass
    def stop(self, gen=None):
        with self.lock:
            if gen is None or gen == self.gen: self._stop()
    def _stop(self):
        srv, u = self.srv, self.udp
        self.srv = self.udp = None; self.pin = None
        if srv: threading.Thread(target=lambda: (srv.shutdown(), srv.server_close()), daemon=True).start()
        if u:
            try: u.close()
            except OSError: pass
    def status(self):
        if self.srv is not None and not self.active(): self.stop()
        return {'activo': self.active(), 'pin': self.pin if self.active() else None, 'port': self.port,
                'ips': local_ips(), 'enviados': self.sent, 'recibidos': self.got, 'inbox': self.inbox_n,
                'quedan': max(0, int(self.until - time.time()))}

WIFI = Wifi()

class HW(http.server.BaseHTTPRequestHandler):
    """Lo único que se ve desde la red: la biblioteca, y solo con el PIN correcto."""
    def log_message(self, *a): pass
    def send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(b))); self.send_header('Cache-Control', 'no-store')
        self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        path, _, qs = self.path.partition('?')
        if path == '/mc/hola': return self.send(200, {'app': 'MisCuestionarios'})
        if path != '/mc/tree': return self.send(404, {})
        if not WIFI.active(): return self.send(410, {'error': 'cerrado'})
        pin = dict(x.partition('=')[::2] for x in qs.split('&') if x).get('pin', '')
        if not hmac.compare_digest(pin.encode(), (WIFI.pin or '').encode()):
            WIFI.fails += 1
            if WIFI.fails >= 10: WIFI.stop()            # demasiados intentos: se corta
            return self.send(403, {'error': 'pin'})
        WIFI.sent += 1
        self.send(200, read_tree())
    def pin_ok(self):
        qs = self.path.partition('?')[2]
        pin = dict(x.partition('=')[::2] for x in qs.split('&') if x).get('pin', '')
        if hmac.compare_digest(pin.encode(), (WIFI.pin or '').encode()): return True
        WIFI.fails += 1
        if WIFI.fails >= 10: WIFI.stop()
        return False
    def do_POST(self):
        """El móvil envía su biblioteca; el creador del PC la añade y pregunta antes de reemplazar."""
        if self.path.partition('?')[0] != '/mc/subir': return self.send(404, {})
        if not WIFI.active(): return self.send(410, {'error': 'cerrado'})
        if not self.pin_ok(): return self.send(403, {'error': 'pin'})
        n = int(self.headers.get('Content-Length') or 0)
        if n > 300 * 1024 * 1024: return self.send(413, {'error': 'grande'})
        try:
            t = json.loads(self.rfile.read(n).decode('utf-8'))
            if not isinstance(t, dict) or not isinstance(t.get('f'), list): raise ValueError
        except Exception:
            return self.send(400, {'error': 'datos'})
        WIFI.inbox = t; WIFI.inbox_n += 1; WIFI.got += 1
        self.send(200, {'ok': True, 'n': sum(len(x.get('q', [])) for a in t['f'] for x in a.get('f', []))})

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, ctype='application/json'):
        b = body if isinstance(body, bytes) else body.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', ctype + '; charset=utf-8')
        self.send_header('Content-Length', str(len(b)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers(); self.wfile.write(b)
    def ok_origin(self):           # solo esta máquina y solo nuestra página
        host = (self.headers.get('Host') or '').split(':')[0]
        return host in ('localhost', '127.0.0.1')
    def body(self):
        return self.rfile.read(int(self.headers.get('Content-Length') or 0))
    def do_GET(self):
        if not self.ok_origin(): return self.send(403, '{}')
        p = self.path.split('?')[0]
        if p in ('/', '/index.html'):
            try:
                with open(HTML, 'rb') as f: page = f.read()
                l = get_lang()
                if l: page = page.replace(b'<head>', ('<head><script>window.MQ_LANG="%s"</script>' % l).encode(), 1)
                self.send(200, page, 'text/html')
            except OSError:
                self.send(404, 'Falta creador-cuestionarios.html junto a servidor.py', 'text/plain')
        elif p == '/api/tree': self.send(200, json.dumps({'tree': read_tree(), 'stamp': stamp(), 'ruta': ROOT}))
        elif p == '/api/stamp': self.send(200, json.dumps({'stamp': stamp()}))
        elif p == '/api/hola': self.send(200, json.dumps({'app': 'MyQuizzes', 'root': ROOT}))
        elif p == '/api/wifi': self.send(200, json.dumps(WIFI.status()))
        elif p == '/api/wifi/inbox': self.send(200, json.dumps(WIFI.inbox, ensure_ascii=False)); WIFI.inbox = None
        else: self.send(404, '{}')
    def do_PUT(self):
        if not self.ok_origin() or self.headers.get('X-Requested-With') != 'creador': return self.send(403, '{}')
        if self.path.split('?')[0] != '/api/tree': return self.send(404, '{}')
        try:
            t = json.loads(self.body().decode('utf-8'))
            with LOCK:
                base = self.headers.get('X-Base')
                if base and base != stamp():          # la carpeta cambió (otra pestaña, a mano, el móvil...)
                    if self.headers.get('X-Final') != '1':
                        return self.send(409, json.dumps({'tree': read_tree(), 'stamp': stamp()}))
                    t = union(t, read_tree())          # guardado al cerrar: se junta aquí para no perder nada
                write_tree(t, self.headers.get('X-Borrar') == '1')
                st = stamp()
            self.send(200, json.dumps({'stamp': st}))
        except PermissionError as e:
            self.send(409, json.dumps({'error': str(e)}))
        except ValueError as e:
            self.send(400, json.dumps({'error': str(e)}))
        except Exception as e:
            self.send(500, json.dumps({'error': str(e)}))
    def abrir(self):
        try:
            parts = json.loads(self.body().decode('utf-8')).get('path', [])
            d = ROOT
            for part in parts[:3]:
                if safe(part) in ('.', '..'): break
                hit = [n for n in os.listdir(d) if os.path.isdir(os.path.join(d, n)) and n.lower() == safe(part).lower() and not n.startswith('.')]
                if not hit: break
                d = os.path.join(d, hit[0])
            open_folder(d)
            self.send(200, json.dumps({'ok': True, 'ruta': d}))
        except Exception as e:
            self.send(500, json.dumps({'error': str(e)}))
    def do_POST(self):
        if not self.ok_origin() or self.headers.get('X-Requested-With') != 'creador': return self.send(403, '{}')
        p = self.path.split('?')[0]
        if p == '/api/abrir': return self.abrir()
        if p == '/api/adios': return self.send(200, '{}')
        if p == '/api/lang':
            try:
                set_lang(json.loads(self.body().decode('utf-8')).get('lang'))
                return self.send(200, '{}')
            except Exception as e:
                return self.send(400, json.dumps({'error': str(e)}))
        if p == '/api/wifi':
            try:
                acc = json.loads(self.body().decode('utf-8')).get('accion')
                WIFI.start() if acc == 'start' else WIFI.stop()
                return self.send(200, json.dumps(WIFI.status()))
            except Exception as e:
                return self.send(500, json.dumps({'error': str(e)}))
        if p != '/api/movil': return self.send(404, '{}')
        try:
            temas = json.loads(self.body().decode('utf-8'))['temas']
            if os.path.isdir(OUT): shutil.rmtree(OUT)      # carpeta generada: se rehace cada vez
            os.makedirs(OUT, exist_ok=True)
            for t in temas:
                d = os.path.join(OUT, safe(t['a'])); os.makedirs(d, exist_ok=True)
                with open(os.path.join(d, safe(t['t']) + '.html'), 'w', encoding='utf-8') as fh: fh.write(t['html'])
            # Copia completa de la carpeta (con lo que está a medias y las fotos): la app la trae de una vez con «Traer del PC»
            shutil.copytree(ROOT, os.path.join(OUT, 'Mis cuestionarios'), ignore=shutil.ignore_patterns('.*', '*.tmp'))
            adb = try_adb(OUT)
            if not adb: open_folder(OUT)
            self.send(200, json.dumps({'ok': True, 'adb': adb, 'ruta': OUT, 'n': len(temas)}))
        except Exception as e:
            self.send(500, json.dumps({'error': str(e)}))

def main():
    os.makedirs(ROOT, exist_ok=True)
    srv = None
    for port in range(PORT, PORT + 20):
        try:
            srv = http.server.ThreadingHTTPServer(('127.0.0.1', port), H); break
        except OSError: continue
    if not srv: sys.exit('No hay ningún puerto libre.')
    url = 'http://localhost:%d/' % port
    print('MyQuizzes está en marcha en', url)
    print('Tus datos se guardan en:', ROOT)
    print('Cierra esta ventana para terminar.')
    threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try: srv.serve_forever()
    except KeyboardInterrupt: pass

if __name__ == '__main__': main()

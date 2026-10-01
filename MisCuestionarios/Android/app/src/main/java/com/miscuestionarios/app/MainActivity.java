package com.miscuestionarios.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Insets;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedDispatcher;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mis cuestionarios (Android).
 * Guarda igual que la versión PC: carpeta privada "Mis cuestionarios" / Asignatura / Tema / Cuestionario.json
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1, REQ_IMPORT_DIR = 2, REQ_EXPORT_DIR = 3;
    private static final String EXPORT_NAME = "Mis cuestionarios (del móvil)";
    private static final Set<String> JUNK = new HashSet<>(Arrays.asList(".ds_store", "thumbs.db", "desktop.ini"));

    private WebView web, preview;
    private FrameLayout frame;
    private ValueCallback<Uri[]> fileCb;
    private File root;
    private final Object lock = new Object();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new File(getFilesDir(), "Mis cuestionarios");
        migrateOld();
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.parseColor("#1f3a5f"));     // color de detrás de la barra de estado
        web = makeWeb();
        frame.addView(web);
        setContentView(frame);
        // Android 15+ dibuja la app a pantalla completa: se deja hueco para la barra de estado, la de navegación y el teclado
        if (Build.VERSION.SDK_INT >= 30) {
            frame.setOnApplyWindowInsetsListener((v, ins) -> {
                Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            frame.setFitsSystemWindows(true);
            getWindow().setStatusBarColor(Color.parseColor("#1f3a5f"));
        }
        // Botón Atrás: en Android 13+ se usa el sistema nuevo (en Android 16 el antiguo ya no se llama)
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new Chrome());
        web.loadUrl("file:///android_asset/index.html");
    }

    private WebView makeWeb() {
        WebView w = new WebView(this);
        w.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme();
                if ("http".equals(sc) || "https".equals(sc) || "mailto".equals(sc)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });
        return w;
    }

    // ---------------------------------------------------------------- vista "Practicar"
    private void openPreview(String html) {
        closePreview();
        preview = makeWeb();
        preview.setWebChromeClient(new WebChromeClient());
        preview.setBackgroundColor(Color.WHITE);
        frame.addView(preview);
        preview.loadDataWithBaseURL("file:///android_asset/practica.html", html, "text/html", "utf-8", null);
    }

    private boolean closePreview() {
        if (preview == null) return false;
        frame.removeView(preview);
        preview.destroy();
        preview = null;
        web.evaluateJavascript("window.onPreviewClosed&&onPreviewClosed()", null);
        return true;
    }

    @Override
    public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (closePreview()) return;
        web.evaluateJavascript("(window.onBack&&onBack())?1:0", v -> {
            if (!"1".equals(v)) finish();          // en la pantalla principal: salir
        });
    }

    @Override
    protected void onPause() {
        if (web != null) web.evaluateJavascript("window.__flush&&__flush()", null);
        super.onPause();
    }

    private SharedPreferences prefs() { return getSharedPreferences("ajustes", MODE_PRIVATE); }

    private String chooserTitle(boolean img) {
        String l = prefs().getString("lang", "es");
        switch (l == null ? "es" : l) {
            case "en": return img ? "Choose photo" : "Choose files";
            case "fr": return img ? "Choisir une photo" : "Choisir des fichiers";
            case "pt": return img ? "Escolher foto" : "Escolher ficheiros";
            case "it": return img ? "Scegli foto" : "Scegli file";
            case "de": return img ? "Foto wählen" : "Dateien wählen";
            default:   return img ? "Elegir foto" : "Elegir archivos";
        }
    }

    // ---------------------------------------------------------------- selector de archivos (importar, fotos)
    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            boolean img = false;
            for (String a : p.getAcceptTypes()) if (a != null && a.startsWith("image")) img = true;
            Intent get = new Intent(Intent.ACTION_GET_CONTENT);
            get.addCategory(Intent.CATEGORY_OPENABLE);
            get.setType(img ? "image/*" : "*/*");
            if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                get.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            Intent chooser = Intent.createChooser(get, chooserTitle(img));
            try {
                startActivityForResult(chooser, REQ_FILE);
            } catch (Exception e) {
                fileCb.onReceiveValue(null);
                fileCb = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCb == null) return;
            Uri[] out = null;
            if (res == RESULT_OK) {
                if (data != null && data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data != null && data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            fileCb.onReceiveValue(out);
            fileCb = null;
            return;
        }
        if (req == REQ_IMPORT_DIR || req == REQ_EXPORT_DIR) {
            final String kind = req == REQ_IMPORT_DIR ? "import" : "export";
            if (res != RESULT_OK || data == null || data.getData() == null) { toJs(kind, null, "cancel"); return; }
            final Uri tree = data.getData();
            io.execute(() -> {
                try {
                    if (req == REQ_IMPORT_DIR) toJs(kind, readTreeUri(tree).toString(), null);
                    else toJs(kind, exportTo(tree), null);
                } catch (Throwable e) {
                    toJs(kind, null, String.valueOf(e.getMessage()));
                }
            });
        }
    }

    private void toJs(String kind, String data, String err) {
        final String js = "window.onAndroidFolder&&onAndroidFolder(" + JSONObject.quote(kind) + ","
                + (data == null ? "null" : JSONObject.quote(data)) + ","
                + (err == null ? "null" : JSONObject.quote(err)) + ")";
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    private void pickFolder(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            // Empezar en Descargas, que es donde se copia desde el PC
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        } catch (Exception ignored) { }
        startActivityForResult(i, req);
    }

    // ---------------------------------------------------------------- puente con la página
    private class Bridge {
        @JavascriptInterface
        public String load() {
            synchronized (lock) {
                try { return readTree().toString(); } catch (Exception e) { return "null"; }
            }
        }

        @JavascriptInterface
        public String save(String json) { return save(json, ""); }

        /** borrar = "1" cuando el usuario ha borrado, movido o renombrado algo. */
        @JavascriptInterface
        public String save(String json, String borrar) {
            synchronized (lock) {
                try { writeTree(new JSONObject(json), "1".equals(borrar)); return ""; }
                catch (Exception e) { return String.valueOf(e.getMessage()); }
            }
        }

        @JavascriptInterface
        public String exportFile(String name, String text) {
            try { return saveDownload(name, text); }                // la página muestra el aviso en su idioma
            catch (Exception e) { return "ERR:" + e.getMessage(); }
        }

        @JavascriptInterface
        public void toast(String m) { ui.post(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show()); }

        @JavascriptInterface
        public String getLang() { return prefs().getString("lang", null); }

        @JavascriptInterface
        public void setLang(String l) { prefs().edit().putString("lang", l).apply(); }

        @JavascriptInterface
        public void preview(String html) { ui.post(() -> openPreview(html)); }

        @JavascriptInterface
        public void pickFolder(String kind) {
            ui.post(() -> MainActivity.this.pickFolder("export".equals(kind) ? REQ_EXPORT_DIR : REQ_IMPORT_DIR));
        }

        @JavascriptInterface
        public String ruta() { return root.getAbsolutePath(); }

        @JavascriptInterface
        public void wifiReceive(String pin, String dir) {
            io.execute(() -> {
                try { toJs("wifi", wifiFetch(pin == null ? "" : pin.trim(), dir == null ? "" : dir.trim()), null); }
                catch (WifiErr e) { toJs("wifi", null, e.getMessage()); }
                catch (Throwable e) { toJs("wifi", null, "error de red (" + e.getMessage() + ")"); }
            });
        }

        @JavascriptInterface
        public void wifiSend(String pin, String dir) {
            io.execute(() -> {
                try { toJs("wifisend", wifiUpload(pin == null ? "" : pin.trim(), dir == null ? "" : dir.trim()), null); }
                catch (WifiErr e) { toJs("wifisend", null, e.getMessage()); }
                catch (Throwable e) { toJs("wifisend", null, "error de red (" + e.getMessage() + ")"); }
            });
        }
    }

    // ---------------------------------------------------------------- guardar en carpetas (igual que servidor.py)
    private static String safe(String n) {
        String s = String.valueOf(n).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "-");
        s = s.replaceAll("^[ .]+|[ .]+$", "");
        if (s.length() > 80) s = s.substring(0, 80).replaceAll("[ .]+$", "");
        return s.isEmpty() ? "Sin nombre" : s;
    }

    private static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toString("UTF-8");
    }

    private static JSONObject loadQuiz(File f) {
        try {
            JSONObject q = new JSONObject(readFile(f));
            if (!q.has("cards")) return null;
            if (!q.has("title")) q.put("title", f.getName().replaceAll("\\.[^.]*$", ""));
            return q;
        } catch (Exception e) { return null; }
    }

    private static File[] sorted(File d) {
        File[] l = d.listFiles();
        if (l == null) return new File[0];
        Collator c = Collator.getInstance(new Locale("es"));
        c.setStrength(Collator.SECONDARY);
        Arrays.sort(l, (a, b) -> c.compare(a.getName(), b.getName()));
        return l;
    }

    private JSONObject folder(File d, String name) throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray f = new JSONArray(), q = new JSONArray();
        for (File x : sorted(d)) {
            if (x.getName().startsWith(".")) continue;
            if (x.isDirectory()) f.put(folder(x, x.getName()));
            else if (x.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                JSONObject z = loadQuiz(x);
                if (z != null) q.put(z);
            }
        }
        o.put("n", name);
        o.put("f", f);
        o.put("q", q);
        return o;
    }

    private JSONObject readTree() throws JSONException {
        root.mkdirs();
        return folder(root, "Mis cuestionarios");
    }

    private static String uniq(Set<String> used, String n, String ext) {
        String base = safe(n), c = base;
        int i = 2;
        while (used.contains((c + ext).toLowerCase(Locale.ROOT))) c = base + " (" + (i++) + ")";
        used.add((c + ext).toLowerCase(Locale.ROOT));
        return c + ext;
    }

    /** Guarda el árbol. Si se iban a borrar más de 2 cuestionarios sin que el usuario lo pidiera, no hace nada. */
    private void writeTree(JSONObject t, boolean allowDel) throws Exception {
        root.mkdirs();
        Set<String> keep = new HashSet<>();
        List<Object[]> plan = new ArrayList<>();
        planFolder(root, t, 0, keep, plan);
        List<File> doomed = new ArrayList<>();
        findDoomed(root, keep, doomed);
        if (doomed.size() > 2 && !allowDel)
            throw new IOException("se iban a borrar " + doomed.size() + " cuestionarios de golpe; no he guardado nada");
        for (Object[] x : plan) writeQuiz((File) x[0], (String) x[1]);
        for (File f : doomed) f.delete();
        prune(root, keep);
    }

    private void planFolder(File d, JSONObject f, int depth, Set<String> keep, List<Object[]> plan) throws Exception {
        keep.add(d.getAbsolutePath());
        Set<String> used = new HashSet<>();
        if (depth < 2) {
            JSONArray sub = f.optJSONArray("f");
            if (sub != null) for (int i = 0; i < sub.length(); i++) {
                JSONObject s = sub.optJSONObject(i);
                if (s == null) continue;
                planFolder(new File(d, uniq(used, s.optString("n", ""), "")), s, depth + 1, keep, plan);
            }
        } else {
            JSONArray qs = f.optJSONArray("q");
            if (qs != null) for (int i = 0; i < qs.length(); i++) {
                JSONObject q = qs.optJSONObject(i);
                if (q == null) continue;
                File p = new File(d, uniq(used, q.optString("title", ""), ".json"));
                keep.add(p.getAbsolutePath());
                plan.add(new Object[]{p, q.toString(1)});
            }
        }
    }

    private void findDoomed(File d, Set<String> keep, List<File> out) {
        for (File x : sorted(d)) {
            if (x.getName().startsWith(".")) continue;                 // carpetas y archivos ocultos: no se tocan
            if (x.isDirectory()) findDoomed(x, keep, out);
            else if (!keep.contains(x.getAbsolutePath()) && x.getName().toLowerCase(Locale.ROOT).endsWith(".json") && loadQuiz(x) != null) out.add(x);
        }
    }

    private void writeQuiz(File p, String data) throws IOException {
        File d = p.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("no se puede crear " + d.getName());
        boolean same = false;
        if (p.exists()) try { same = readFile(p).equals(data); } catch (IOException ignored) { }
        if (same) return;
        File tmp = new File(p.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(data.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(p)) { p.delete(); if (!tmp.renameTo(p)) throw new IOException("no se puede escribir " + p.getName()); }
    }

    private void prune(File d, Set<String> keep) {
        for (File x : sorted(d)) {
            if (x.getName().startsWith(".")) continue;
            if (x.isDirectory()) {
                prune(x, keep);
                if (!keep.contains(x.getAbsolutePath())) {
                    File[] in = x.listFiles();
                    if (in != null) for (File j : in)
                        if (j.isFile() && (JUNK.contains(j.getName().toLowerCase(Locale.ROOT)) || j.getName().startsWith("."))) j.delete();
                    x.delete();                       // solo si queda vacía
                }
            } else if (x.getName().endsWith(".json.tmp")) {
                x.delete();
            }
        }
    }

    /** Si una versión anterior guardó todo en un único .json, lo pasa a carpetas. */
    private void migrateOld() {
        try {
            if (root.isDirectory() && root.list() != null && root.list().length > 0) return;
            File[] l = getFilesDir().listFiles();
            if (l == null) return;
            for (File x : l) {
                if (!x.isFile() || !x.getName().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                try {
                    JSONObject o = new JSONObject(readFile(x));
                    if (o.has("f") && o.optJSONArray("f") != null) {
                        synchronized (lock) { writeTree(o, true); }
                        x.renameTo(new File(x.getPath() + ".antiguo"));
                        return;
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }


    // ---------------------------------------------------------------- recibir del PC por Wi-Fi
    private static final int WIFI_PORT = 8765, DISC_PORT = 8766;

    private static class WifiErr extends Exception { WifiErr(String c) { super(c); } }

    /** Direcciones de difusión de la red local (Wi-Fi / Ethernet) del móvil. */
    private List<InetAddress> broadcasts() {
        List<InetAddress> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (!(la.getAddress() instanceof Inet4Address)) continue;
                    byte[] a = la.getAddress().getAddress();
                    int len = la.getPrefixLength(), ip = ((a[0] & 255) << 24) | ((a[1] & 255) << 16) | ((a[2] & 255) << 8) | (a[3] & 255);
                    int mask = len == 0 ? 0 : -1 << (32 - len), b = ip | ~mask;
                    out.add(InetAddress.getByAddress(new byte[]{(byte) (b >>> 24), (byte) (b >>> 16), (byte) (b >>> 8), (byte) b}));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Pregunta por UDP «¿dónde estás?» y devuelve "ip:puerto" del PC que conteste. */
    private String discover() throws WifiErr {
        List<InetAddress> bc = broadcasts();
        if (bc.isEmpty()) throw new WifiErr("wifi");
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(800);
            byte[] q = "MISCUESTIONARIOS?".getBytes(StandardCharsets.UTF_8);
            try { bc.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                for (InetAddress a : bc) try { s.send(new DatagramPacket(q, q.length, a, DISC_PORT)); } catch (IOException ignored) { }
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    s.receive(r);
                    String m = new String(r.getData(), 0, r.getLength(), StandardCharsets.UTF_8).trim();
                    if (m.startsWith("MISCUESTIONARIOS ")) {
                        String[] p = m.split(" ");
                        return r.getAddress().getHostAddress() + ":" + Integer.parseInt(p[1]);
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) { }
        throw new WifiErr("nopc");
    }

    private String pcHost(String dir) throws WifiErr {
        String host = dir.replaceFirst("^https?://", "").replaceAll("/.*$", "");
        if (host.isEmpty()) return discover();
        return host.contains(":") ? host : host + ":" + WIFI_PORT;
    }

    /** Envía la biblioteca del móvil al PC (el PC la añade y pregunta antes de reemplazar). */
    private String wifiUpload(String pin, String dir) throws Exception {
        String host = pcHost(dir);
        byte[] body;
        synchronized (lock) { body = readTree().toString().getBytes(StandardCharsets.UTF_8); }
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + "/mc/subir?pin=" + URLEncoder.encode(pin, "UTF-8")).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            catch (IOException e) { throw new WifiErr("nopc"); }
            int code;
            try { code = c.getResponseCode(); }
            catch (IOException e) { throw new WifiErr("nopc"); }
            if (code == 403) throw new WifiErr("pin");
            if (code == 410) throw new WifiErr("cerrado");
            if (code == 413) throw new WifiErr("grande");
            if (code != 200) throw new WifiErr("nopc");
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } finally { c.disconnect(); }
    }

    private String wifiFetch(String pin, String dir) throws Exception {
        String host = pcHost(dir);
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + "/mc/tree?pin=" + URLEncoder.encode(pin, "UTF-8")).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(60000);
        try {
            int code;
            try { code = c.getResponseCode(); }
            catch (IOException e) { throw new WifiErr("nopc"); }
            if (code == 403) throw new WifiErr("pin");
            if (code == 410) throw new WifiErr("cerrado");
            if (code != 200) throw new WifiErr("nopc");
            try (InputStream in = c.getInputStream()) {
                String body = readAll(in);
                new JSONObject(body);                 // comprobar que es lo esperado
                return body;
            }
        } finally { c.disconnect(); }
    }

    // ---------------------------------------------------------------- Descargas
    private String saveDownload(String name, String text) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/html");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new IOException("sin acceso a Descargas");
        try (OutputStream o = cr.openOutputStream(u)) {
            if (o == null) throw new IOException("sin acceso a Descargas");
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        String real = name;
        try (Cursor c = cr.query(u, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) real = c.getString(0);
        } catch (Exception ignored) { }
        return real;
    }

    // ---------------------------------------------------------------- carpetas elegidas por el usuario (SAF)
    private static final String[] COLS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE};

    private static class Doc {
        String id, name, mime;
        long size;
        boolean dir() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }
    }

    private List<Doc> children(Uri tree, String parentId) {
        List<Doc> out = new ArrayList<>();
        Uri u = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(u, COLS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Doc d = new Doc();
                d.id = c.getString(0);
                d.name = c.getString(1);
                d.mime = c.getString(2);
                d.size = c.isNull(3) ? 0 : c.getLong(3);
                out.add(d);
            }
        }
        return out;
    }

    /** Lee una carpeta elegida: {n, d:[subcarpetas], files:[{n,t}]} (solo .json y .html). La página lo interpreta. */
    private JSONObject readTreeUri(Uri tree) throws Exception {
        String id = DocumentsContract.getTreeDocumentId(tree);
        String name = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        return readDir(tree, id, name.isEmpty() ? "Carpeta" : name, 0);
    }

    private JSONObject readDir(Uri tree, String id, String name, int depth) throws Exception {
        JSONObject o = new JSONObject();
        JSONArray d = new JSONArray(), fl = new JSONArray();
        o.put("n", name);
        if (depth <= 4) for (Doc c : children(tree, id)) {
            if (c.name == null || c.name.startsWith(".")) continue;
            if (c.dir()) { d.put(readDir(tree, c.id, c.name, depth + 1)); continue; }
            String low = c.name.toLowerCase(Locale.ROOT);
            if (!(low.endsWith(".json") || low.endsWith(".html") || low.endsWith(".htm"))) continue;
            if (c.size > 40L * 1024 * 1024) continue;
            try (InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, c.id))) {
                if (in == null) continue;
                JSONObject f = new JSONObject();
                f.put("n", c.name);
                f.put("t", readAll(in));
                fl.put(f);
            }
        }
        o.put("d", d);
        o.put("files", fl);
        return o;
    }

    /** Copia la carpeta interna a la carpeta elegida, dentro de "Mis cuestionarios (del móvil)" (se rehace cada vez). */
    private String exportTo(Uri tree) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        ContentResolver cr = getContentResolver();
        for (Doc c : children(tree, rootId))
            if (c.dir() && EXPORT_NAME.equalsIgnoreCase(c.name))
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, c.id));
        Uri out = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, EXPORT_NAME);
        if (out == null) throw new IOException("no se puede crear la carpeta");
        int[] n = {0};
        synchronized (lock) { copyDir(cr, root, out, n); }
        return n[0] + "";
    }

    private void copyDir(ContentResolver cr, File src, Uri dst, int[] n) throws Exception {
        for (File x : sorted(src)) {
            if (x.getName().startsWith(".") || x.getName().endsWith(".tmp")) continue;
            if (x.isDirectory()) {
                Uri d = DocumentsContract.createDocument(cr, dst, DocumentsContract.Document.MIME_TYPE_DIR, x.getName());
                if (d == null) throw new IOException("no se puede crear " + x.getName());
                copyDir(cr, x, d, n);
            } else if (x.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                // "application/octet-stream" para que no añada otra extensión al nombre
                Uri f = DocumentsContract.createDocument(cr, dst, "application/octet-stream", x.getName());
                if (f == null) throw new IOException("no se puede crear " + x.getName());
                try (InputStream in = new FileInputStream(x); OutputStream o = cr.openOutputStream(f)) {
                    if (o == null) throw new IOException("no se puede escribir " + x.getName());
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                }
                n[0]++;
            }
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}

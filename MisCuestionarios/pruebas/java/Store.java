// GENERADO por extraer.py a partir de MainActivity.java (mismo código) para probarlo fuera de Android.
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
import java.util.*;
public class Store {
    File root; final Object lock = new Object(); File filesDir;
    Store(File filesDir) { this.filesDir = filesDir; root = new File(filesDir, "Mis cuestionarios"); }
    File getFilesDir() { return filesDir; }
    String discover() throws WifiErr { throw new WifiErr("nopc"); }
    private static final Set<String> JUNK = new HashSet<>(Arrays.asList(".ds_store", "thumbs.db", "desktop.ini"));
    private static final int WIFI_PORT = 8765, DISC_PORT = 8766;
    private static class WifiErr extends Exception { WifiErr(String c) { super(c); } }

    static String safe(String n) {
        String s = String.valueOf(n).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "-");
        s = s.replaceAll("^[ .]+|[ .]+$", "");
        if (s.length() > 80) s = s.substring(0, 80).replaceAll("[ .]+$", "");
        return s.isEmpty() ? "Sin nombre" : s;
    }

    static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toString("UTF-8");
    }

    static JSONObject loadQuiz(File f) {
        try {
            JSONObject q = new JSONObject(readFile(f));
            if (!q.has("cards")) return null;
            if (!q.has("title")) q.put("title", f.getName().replaceAll("\\.[^.]*$", ""));
            return q;
        } catch (Exception e) { return null; }
    }

    static File[] sorted(File d) {
        File[] l = d.listFiles();
        if (l == null) return new File[0];
        Collator c = Collator.getInstance(new Locale("es"));
        c.setStrength(Collator.SECONDARY);
        Arrays.sort(l, (a, b) -> c.compare(a.getName(), b.getName()));
        return l;
    }

    JSONObject folder(File d, String name) throws JSONException {
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

    JSONObject readTree() throws JSONException {
        root.mkdirs();
        return folder(root, "Mis cuestionarios");
    }

    static String uniq(Set<String> used, String n, String ext) {
        String base = safe(n), c = base;
        int i = 2;
        while (used.contains((c + ext).toLowerCase(Locale.ROOT))) c = base + " (" + (i++) + ")";
        used.add((c + ext).toLowerCase(Locale.ROOT));
        return c + ext;
    }

    void writeTree(JSONObject t, boolean allowDel) throws Exception {
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

    void planFolder(File d, JSONObject f, int depth, Set<String> keep, List<Object[]> plan) throws Exception {
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

    void findDoomed(File d, Set<String> keep, List<File> out) {
        for (File x : sorted(d)) {
            if (x.getName().startsWith(".")) continue;                 // carpetas y archivos ocultos: no se tocan
            if (x.isDirectory()) findDoomed(x, keep, out);
            else if (!keep.contains(x.getAbsolutePath()) && x.getName().toLowerCase(Locale.ROOT).endsWith(".json") && loadQuiz(x) != null) out.add(x);
        }
    }

    void writeQuiz(File p, String data) throws IOException {
        File d = p.getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("no se puede crear " + d.getName());
        boolean same = false;
        if (p.exists()) try { same = readFile(p).equals(data); } catch (IOException ignored) { }
        if (same) return;
        File tmp = new File(p.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(data.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(p)) { p.delete(); if (!tmp.renameTo(p)) throw new IOException("no se puede escribir " + p.getName()); }
    }

    void prune(File d, Set<String> keep) {
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

    void migrateOld() {
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

    String pcHost(String dir) throws WifiErr {
        String host = dir.replaceFirst("^https?://", "").replaceAll("/.*$", "");
        if (host.isEmpty()) return discover();
        return host.contains(":") ? host : host + ":" + WIFI_PORT;
    }

    String wifiUpload(String pin, String dir) throws Exception {
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

    String wifiFetch(String pin, String dir) throws Exception {
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
}

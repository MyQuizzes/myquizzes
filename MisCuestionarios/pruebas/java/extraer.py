"""Copia el código de guardado y Wi-Fi de MainActivity.java a Store.java para probarlo fuera de Android.
Uso:  python extraer.py && javac -encoding UTF-8 -d out org/json/*.java Store.java StoreTest.java
      java -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp out StoreTest [ip:puerto PIN]"""
import re, os
H = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(H, '..', '..', 'Android', 'app', 'src', 'main', 'java', 'com', 'miscuestionarios', 'app', 'MainActivity.java'), encoding='utf-8').read()
def method(sig):
    i = src.index(sig); j = src.index('{', i); d = 0
    for k in range(j, len(src)):
        if src[k] == '{': d += 1
        elif src[k] == '}':
            d -= 1
            if d == 0: return src[i:k + 1]
names = ['private static String safe(', 'private static String readFile(', 'private static String readAll(', 'private static JSONObject loadQuiz(',
         'private static File[] sorted(', 'private JSONObject folder(', 'private JSONObject readTree(', 'private static String uniq(',
         'private void writeTree(', 'private void planFolder(', 'private void findDoomed(', 'private void writeQuiz(', 'private void prune(',
         'private void migrateOld(', 'private String pcHost(', 'private String wifiUpload(', 'private String wifiFetch(']
body = '\n\n'.join('    ' + method(n).replace('private ', '', 1) for n in names)
junk = re.search(r'    private static final Set<String> JUNK = .*?;\n', src).group(0)
wifi = re.search(r'    private static final int WIFI_PORT = .*?;\n', src).group(0)
err = re.search(r'    private static class WifiErr .*?\n', src).group(0)
imports = '\n'.join(l for l in src.splitlines() if l.startswith('import java.') or l.startswith('import org.json'))
open(os.path.join(H, 'Store.java'), 'w', encoding='utf-8').write(f'''// GENERADO por extraer.py a partir de MainActivity.java (mismo código) para probarlo fuera de Android.
{imports}
import java.util.*;
public class Store {{
    File root; final Object lock = new Object(); File filesDir;
    Store(File filesDir) {{ this.filesDir = filesDir; root = new File(filesDir, "Mis cuestionarios"); }}
    File getFilesDir() {{ return filesDir; }}
    String discover() throws WifiErr {{ throw new WifiErr("nopc"); }}
{junk}{wifi}{err}
{body}
}}
''')
print('Store.java generado')

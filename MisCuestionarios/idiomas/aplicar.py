"""Mete las traducciones (traducciones.py) dentro de creador-cuestionarios.html y de la app Android.
Uso: python aplicar.py   (desde esta carpeta)"""
import json, os, re, sys
H = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, H)
from traducciones import T
LANGS = ['en', 'fr', 'pt', 'it', 'de']
pc = os.path.join(H, '..', 'PC', 'creador-cuestionarios.html')
s = open(pc, encoding='utf-8').read()
d = {l: {k: v[i] for k, v in T.items() if v[i] != k} for i, l in enumerate(LANGS)}
t = s[s.index('<template id="tpl">'):s.index('</template>')]
tk = sorted(set(re.findall(r"_\('((?:[^'\\\n]|\\.)*)'", t)))
js = json.dumps(d, ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/')
s = re.sub(r'/\*I18N\*/[\s\S]*?/\*/I18N\*/', lambda m: '/*I18N*/' + js + '/*/I18N*/', s)
s = re.sub(r'/\*TPLK\*/[\s\S]*?/\*/TPLK\*/', lambda m: '/*TPLK*/' + json.dumps(tk, ensure_ascii=False) + '/*/TPLK*/', s)
open(pc, 'w', encoding='utf-8').write(s)
an = os.path.join(H, '..', 'Android', 'app', 'src', 'main', 'assets', 'index.html')
if os.path.isdir(os.path.dirname(an)):
    open(an, 'w', encoding='utf-8').write(s)
print('Listo:', len(T), 'textos x', len(LANGS), 'idiomas;', len(tk), 'de la plantilla de práctica')

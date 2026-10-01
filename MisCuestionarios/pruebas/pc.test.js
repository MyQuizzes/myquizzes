// Pruebas de la versión PC: servidor.py real + creador en Chromium.
// Uso: node pc.test.js   (arranca el servidor en una copia temporal)
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),cp=require('child_process'),os=require('os');
const SRC=path.resolve(__dirname,'../PC');
const DIR=fs.mkdtempSync(path.join(os.tmpdir(),'mcpc-'));
for(const f of ['creador-cuestionarios.html','servidor.py'])fs.copyFileSync(path.join(SRC,f),path.join(DIR,f));
const ROOT=path.join(DIR,'Mis cuestionarios');
const IP=cp.execSync("hostname -I | awk '{print $1}'").toString().trim();
let ok=0,bad=0;const fails=[];
const T=(name,cond,info='')=>{if(cond){ok++;console.log('  ✓',name)}else{bad++;fails.push(name+(info?' → '+info:''));console.log('  ✗',name,info)}};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const ls=d=>{const o=[];const w=(p,r)=>{for(const n of fs.readdirSync(p).sort()){const f=path.join(p,n);const rel=r?r+'/'+n:n;if(fs.statSync(f).isDirectory()){o.push(rel+'/');w(f,rel)}else o.push(rel)}};if(fs.existsSync(d))w(d,'');return o};
const rj=p=>JSON.parse(fs.readFileSync(path.join(ROOT,p),'utf8'));
const req=async(method,p,body,headers={})=>{const r=await fetch('http://127.0.0.1:'+PORT+p,{method,headers:{'X-Requested-With':'creador','Host':'localhost',...headers},body:body&&JSON.stringify(body)});let j=null;try{j=await r.json()}catch(e){}return [r.status,j]};
let PORT=8080,srv;
(async()=>{
 srv=(process.env.MQ_GO?cp.spawn(process.env.MQ_GO,['--base',DIR,'--no-abrir','--html',require('path').join(DIR,'creador-cuestionarios.html')]):cp.spawn('python3',['servidor.py'],{cwd:DIR}));
 let out='';srv.stdout.on('data',d=>out+=d);
 for(let i=0;i<30&&!/localhost:(\d+)/.test(out);i++)await sleep(200);
 PORT=+out.match(/localhost:(\d+)/)[1];
 const b=await chromium.launch();const errs=[];
 const p=await b.newPage({viewport:{width:1100,height:900}});
 p.on('pageerror',e=>errs.push(e.message));
 const answers=[];const dialogs=[];
 p.on('dialog',async d=>{dialogs.push(d.type()+': '+d.message());const a=answers.shift();
   if(d.type()=='prompt')await d.accept(a??'');else if(a===false)await d.dismiss();else await d.accept()});
 const settle=()=>sleep(900);
 const sel=async t=>{await p.click(`#tree .tr:has-text("${t}")`);await sleep(150)};
 const nuevo=async(nombre)=>{answers.push(nombre);await p.click('#nn');await settle()};
 const lastCard=()=>p.locator('#list .cd').last();

 console.log('\n1. Primer arranque e idioma');
 await p.goto('http://localhost:'+PORT+'/');await sleep(800);
 T('sale el selector de idioma la primera vez',await p.isVisible('.lsh'));
 await p.click('[data-lg="es"]');await sleep(1200);
 T('idioma guardado en config.json',fs.existsSync(path.join(DIR,'config.json'))&&JSON.parse(fs.readFileSync(path.join(DIR,'config.json'))).lang=='es');
 T('tras recargar ya no sale el selector',!(await p.isVisible('.lsh')));
 T('se crea la carpeta Mis cuestionarios',fs.existsSync(ROOT));

 console.log('\n2. Crear asignatura, tema y cuestionario');
 await nuevo('Matemáticas');
 T('asignatura creada en disco',fs.existsSync(path.join(ROOT,'Matemáticas')));
 await nuevo('Álgebra');
 await nuevo('Ecuaciones');
 T('tema y cuestionario creados en disco',fs.existsSync(path.join(ROOT,'Matemáticas/Álgebra/Ecuaciones.json')));

 console.log('\n3. Reglas de nombres');
 await sel('Matemáticas');await sel('Mis cuestionarios');
 answers.push('matemáticas');await p.click('#nn');await settle();
 T('no deja repetir nombre (sin importar mayúsculas)',dialogs.at(-1).includes('ya existe')&&ls(ROOT).filter(x=>/^mat/i.test(x)&&x.endsWith('/')&&!x.includes('/',x.indexOf('/')+1)).length==1,dialogs.at(-1));
 answers.push('Física/Química');await p.click('#nn');await settle();
 T('no deja caracteres prohibidos',dialogs.at(-1).includes('caracteres'),dialogs.at(-1));
 answers.push('Historia.');await p.click('#nn');await settle();
 T('no deja acabar en punto',dialogs.at(-1).includes('punto'),dialogs.at(-1));
 answers.push('x'.repeat(81));await p.click('#nn');await settle();
 T('no deja más de 80 caracteres',dialogs.at(-1).includes('80'),dialogs.at(-1));

 console.log('\n4. Tarjetas de los 6 tipos');
 await sel('Ecuaciones');
 // Test A B C (dos correctas)
 await p.click('[data-t="abc"]');let c=lastCard();
 await c.locator('textarea[data-k="q"]').fill('¿Cuáles son pares?');
 const opt=c.locator('input[data-k="x"]');await opt.nth(0).fill('2');await opt.nth(1).fill('3');await opt.nth(2).fill('4');
 await c.locator('input.ck').nth(0).check();await c.locator('input.ck').nth(2).check();
 await c.locator('textarea[data-k="e"]').fill('2 y 4 son pares');
 // V/F
 await p.click('[data-t="vf"]');c=lastCard();await c.locator('textarea[data-k="q"]').fill('0 es natural');await c.locator('select[data-k="v"]').selectOption('0');
 // Pasos con fórmulas por teclado
 await p.click('[data-t="steps"]');c=lastCard();await c.locator('textarea[data-k="q"]').fill('Resolver 2x=4');
 const st=c.locator('input[data-k="t"]');await st.nth(0).fill('Dividir entre 2');await st.nth(1).fill('Resultado');
 await c.locator('.mf').nth(0).click();await p.keyboard.type('x^2');await p.keyboard.press('ArrowRight');await p.keyboard.type('+1/2');
 await c.locator('.mf').nth(1).click();await c.locator('[data-act="kb"]').nth(1).click();await c.locator('.kb').nth(1).locator('[data-key="sqrt"]').click();await c.locator('.kb').nth(1).locator('[data-key="4"]').click();
 // Relacionar
 await p.click('[data-t="rel"]');c=lastCard();await c.locator('textarea[data-k="q"]').fill('Relaciona');
 await c.locator('input[data-k="l"]').nth(0).fill('2²');await c.locator('input[data-k="r"]').nth(0).fill('4');
 await c.locator('input[data-k="l"]').nth(1).fill('3²');await c.locator('input[data-k="r"]').nth(1).fill('9');
 // Clasificar
 await p.click('[data-t="grp"]');c=lastCard();await c.locator('textarea[data-k="q"]').fill('Clasifica');
 await c.locator('input[data-k="n"]').nth(0).fill('Pares');await c.locator('input[data-k="n"]').nth(1).fill('Impares');
 const it=c.locator('input[data-b="items"]');await it.nth(0).fill('2');await it.nth(1).fill('6');await it.nth(2).fill('3');await it.nth(3).fill('5');
 // Foto
 await p.click('[data-t="foto"]');c=lastCard();await c.locator('textarea[data-k="q"]').fill('Un punto');
 const png=path.join(DIR,'p.png');fs.writeFileSync(png,Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg==','base64'));
 await c.locator('input[type=file]').setInputFiles(png);await sleep(600);
 await settle();
 const q=rj('Matemáticas/Álgebra/Ecuaciones.json');
 T('se guardan las 6 tarjetas en el .json',q.cards.length==6&&q.cards.map(x=>x.t).join()=='abc,vf,steps,rel,grp,foto',q.cards.map(x=>x.t).join());
 T('test: 2 correctas marcadas',q.cards[0].opts.filter(o=>o.ok).length==2);
 T('V/F guardado como falso',q.cards[1].v===false);
 T('fórmula escrita con el teclado del PC',q.cards[2].steps[0].f=='x^{2}+\\frac{1}{2}',q.cards[2].steps[0].f);
 T('fórmula con el teclado matemático (raíz)',q.cards[2].steps[1].f=='\\sqrt{4}',q.cards[2].steps[1].f);
 T('la fórmula se ve apilada (MathML)',await p.locator('#list .cd').nth(2).locator('mfrac').count()>0);
 T('clasificar: 2 grupos con 2 elementos',q.cards[4].groups.map(g=>g.items.length).join()=='2,2');
 T('foto guardada (reducida a jpeg)',q.cards[5].img.startsWith('data:image/jpeg'));
 T('el contador dice 6 tarjetas completas',(await p.textContent('#cnt')).includes('6 tarjetas completas'),await p.textContent('#cnt'));

 console.log('\n5. Ordenar y borrar tarjetas');
 await p.locator('#list .cd').nth(1).locator('[data-act="cup"]').click();await settle();
 T('subir tarjeta cambia el orden en disco',rj('Matemáticas/Álgebra/Ecuaciones.json').cards[0].t=='vf');
 await p.locator('#list .cd').nth(0).locator('[data-act="cdn"]').click();await settle();
 answers.push(false);await p.locator('#list .cd').nth(0).locator('[data-act="cdel"]').click();await settle();
 T('cancelar al eliminar no borra',rj('Matemáticas/Álgebra/Ecuaciones.json').cards.length==6);

 console.log('\n6. Recargar: todo sigue');
 await p.reload();await sleep(1200);
 T('tras recargar se ve el cuestionario',await p.locator('#tree .tr:has-text("Ecuaciones")').count()==1);
 await sel('Ecuaciones');
 T('tras recargar siguen las 6 tarjetas',await p.locator('#list .cd').count()==6);

 console.log('\n7. Renombrar, mover y eliminar');
 await nuevo('Aritmética'); // nuevo cuestionario en Álgebra (estamos en un cuestionario)
 await sel('Matemáticas');answers.push('Mates');await p.click('#rn');await settle();
 T('renombrar asignatura renombra la carpeta',fs.existsSync(path.join(ROOT,'Mates/Álgebra/Ecuaciones.json'))&&!fs.existsSync(path.join(ROOT,'Matemáticas')),ls(ROOT).join(' '));
 await sel('Mis cuestionarios');await nuevo('Física');await nuevo('Cinemática');
 await sel('Aritmética');await p.selectOption('#mvsel',{label:'Física / Cinemática'});await settle();
 T('mover cuestionario a otro tema',fs.existsSync(path.join(ROOT,'Física/Cinemática/Aritmética.json'))&&!fs.existsSync(path.join(ROOT,'Mates/Álgebra/Aritmética.json')),ls(ROOT).join(' '));
 await sel('Cinemática');await p.selectOption('#mvsel',{label:'Mates'});await settle();
 T('mover tema a otra asignatura',fs.existsSync(path.join(ROOT,'Mates/Cinemática/Aritmética.json')),ls(ROOT).join(' '));
 fs.writeFileSync(path.join(ROOT,'Mates/Cinemática/roto.json'),'{esto no es json');
 await sel('Cinemática');answers.push(true);await p.click('#rmv');await settle();
 T('eliminar tema borra sus cuestionarios',!fs.existsSync(path.join(ROOT,'Mates/Cinemática/Aritmética.json')));
 T('los .json ilegibles nunca se borran',fs.existsSync(path.join(ROOT,'Mates/Cinemática/roto.json')));
 fs.rmSync(path.join(ROOT,'Mates/Cinemática'),{recursive:true});

 console.log('\n8. Cambios hechos a mano en la carpeta');
 fs.mkdirSync(path.join(ROOT,'Historia/Edad Media'),{recursive:true});
 fs.writeFileSync(path.join(ROOT,'Historia/Edad Media/Fechas.json'),JSON.stringify({title:'Fechas',cards:[{t:'vf',q:'1492',e:'',v:true}]}));
 fs.writeFileSync(path.join(ROOT,'suelto.json'),JSON.stringify({title:'suelto',cards:[{t:'vf',q:'x',e:'',v:true}]}));
 await sleep(9500);
 T('detecta cuestionarios nuevos copiados a mano',await p.locator('#tree .tr:has-text("Fechas")').count()==1);
 T('lo mal colocado va a «General» y avisa',fs.existsSync(path.join(ROOT,'General/General/suelto.json'))&&await p.isVisible('#aviso'),ls(ROOT).join(' '));

 console.log('\n9. Exportar e importar');
 await sel('Álgebra');
 const [dl]=await Promise.all([p.waitForEvent('download'),p.click('#tree .rw.on [data-a="x"]')]);
 const exp=path.join(DIR,'alg.html');await dl.saveAs(exp);
 T('exportar tema descarga un .html',fs.readFileSync(exp,'utf8').includes('<script id="d"'));
 await sel('Historia');
 const [fc]=await Promise.all([p.waitForEvent('filechooser'),p.click('#tree .rw.on [data-a="i"]')]);await fc.setFiles(exp);await settle();
 T('importar tema en otra asignatura',fs.existsSync(path.join(ROOT,'Historia/Álgebra/Ecuaciones.json')),ls(ROOT).join(' '));
 T('lo importado conserva las tarjetas completas',rj('Historia/Álgebra/Ecuaciones.json').cards.length==6);
 await sel('Historia');answers.push(false);
 const [fc2]=await Promise.all([p.waitForEvent('filechooser'),p.click('#tree .rw.on [data-a="i"]')]);await fc2.setFiles(exp);await settle();
 T('importar algo repetido pide confirmación',dialogs.at(-1).includes('ya existe'),dialogs.at(-1));
 await sel('Mis cuestionarios');
 T('no se puede exportar la biblioteca entera',await p.isDisabled('#dl'));

 console.log('\n10. Seguridad del servidor');
 let [s1]=await req('PUT','/api/tree',{f:[],q:[]},{'X-Requested-With':'otro'});
 T('rechaza guardar sin la cabecera del creador',s1==403);
 s1=await new Promise(res=>require('http').get({host:'127.0.0.1',port:PORT,path:'/api/tree',headers:{Host:'evil.com'}},r=>res(r.statusCode)));
 T('rechaza peticiones con otro Host (DNS rebinding)',s1==403);
 let [s2,j2]=await req('PUT','/api/tree',{n:'x',f:[{n:'A',f:[],q:[]},{n:'a',f:[],q:[]}],q:[]});
 T('el servidor rechaza nombres repetidos',s2==400&&/mismo nombre/.test(j2.error),JSON.stringify(j2));
 T('y no ha tocado la carpeta',fs.existsSync(path.join(ROOT,'Mates/Álgebra/Ecuaciones.json')));

 console.log('\n11. Wi‑Fi (PIN)');
 await p.click('#wifi');await sleep(800);let [,w]=await req('GET','/api/wifi');
 T('abre conexión con PIN de 4 cifras',w.activo&&/^\d{4}$/.test(w.pin));
 let r=await fetch(`http://${IP}:${w.port}/mc/tree?pin=${w.pin}`);let tree=await r.json();
 T('el móvil descarga la biblioteca con el PIN',r.status==200&&tree.f.some(a=>a.n=='Mates'));
 r=await fetch(`http://${IP}:${w.port}/api/tree`);T('desde la red no se ve la API interna',r.status==404);
 r=await fetch(`http://${IP}:${w.port}/mc/subir?pin=${w.pin}`,{method:'POST',body:JSON.stringify({n:'x',f:[{n:'Biología',f:[{n:'Célula',f:[],q:[{title:'Orgánulos',cards:[{t:'vf',q:'La mitocondria da energía',e:'',v:true}]}]}],q:[]}],q:[]})});
 T('el móvil puede enviar',r.status==200);
 await sleep(3000);
 T('el PC añade lo recibido del móvil y lo guarda',fs.existsSync(path.join(ROOT,'Biología/Célula/Orgánulos.json')),dialogs.at(-1));
 for(let i=0;i<10;i++)await fetch(`http://${IP}:${w.port}/mc/tree?pin=0000x`);
 [,w]=await req('GET','/api/wifi');
 T('10 PIN incorrectos cierran la conexión',!w.activo);
 r=await fetch(`http://${IP}:${w.port}/mc/tree?pin=0000`).catch(()=>({status:0}));
 T('cerrada ya no responde',r.status!=200);

 console.log('\n12. Idiomas');
 for(const [l,word] of [['en','Practice'],['fr','S’entraîner'],['pt','Praticar'],['it','Esercitati'],['de','Üben'],['es','Practicar']]){
   await p.click('#plang');await p.click(`[data-lg="${l}"]`);await sleep(1300);
   T(`cambiar a ${l}`,(await p.textContent('#test')).trim()==word&&(await p.title())=='MyQuizzes'&&JSON.parse(fs.readFileSync(path.join(DIR,'config.json'))).lang==l,await p.title());
 }
 T('ningún error de JavaScript en el creador',errs.length==0,errs.join(' | '));
 fs.writeFileSync('/tmp/mc-export.html',fs.readFileSync(exp,'utf8'));
 await b.close();srv.kill();
 console.log(`\nPC: ${ok} bien, ${bad} mal`);if(fails.length)console.log('FALLOS:\n - '+fails.join('\n - '));
 fs.writeFileSync('/tmp/pc-result.json',JSON.stringify({ok,bad,fails}));
})().catch(e=>{console.error('ERROR',e);srv&&srv.kill();process.exit(1)});

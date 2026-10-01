// Pruebas de estrés y casos raros (PC + HTML de la app).
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),cp=require('child_process'),os=require('os'),http=require('http');
const SRC=path.resolve(__dirname,'../PC');
let ok=0,bad=0;const fails=[];
const T=(n,c,i='')=>{if(c){ok++;console.log('  ✓',n)}else{bad++;fails.push(n+(i?' → '+i:''));console.log('  ✗',n,i)}};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const IP=cp.execSync("hostname -I | awk '{print $1}'").toString().trim();
function mkdir(){const d=fs.mkdtempSync(path.join(os.tmpdir(),'mcst-'));for(const f of ['creador-cuestionarios.html','servidor.py'])fs.copyFileSync(path.join(SRC,f),path.join(d,f));fs.writeFileSync(path.join(d,'config.json'),'{"lang":"es"}');return d}
async function start(d){const s=(process.env.MQ_GO?cp.spawn(process.env.MQ_GO,['--base',d,'--no-abrir','--html',require('path').join(d,'creador-cuestionarios.html')]):cp.spawn('python3',['servidor.py'],{cwd:d}));let out='',err='';s.stdout.on('data',x=>out+=x);s.stderr.on('data',x=>err+=x);
  for(let i=0;i<50&&!/localhost:(\d+)/.test(out);i++)await sleep(200);s.port=+out.match(/localhost:(\d+)/)[1];s.err=()=>err;return s}
const W=(d,rel,obj)=>{const f=path.join(d,'Mis cuestionarios',rel);fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,typeof obj=='string'?obj:JSON.stringify(obj))};
const raw=(port,method,p,body,headers={})=>new Promise(res=>{const r=http.request({host:'127.0.0.1',port,path:p,method,headers:{Host:'localhost','X-Requested-With':'creador',...(body!=null?{'Content-Length':Buffer.byteLength(body)}:{}),...headers}},rr=>{let b='';rr.on('data',x=>b+=x);rr.on('end',()=>res([rr.statusCode,b]))});r.on('error',e=>res([0,String(e)]));if(body)r.write(body);r.end()});
const row=(pg,name)=>pg.locator('#tree .tr').filter({hasText:new RegExp('^[▾▸·]\\s*'+name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'\\d*$')}).first();
const XSS='<img src=x onerror="window.__xss=1"><script>window.__xss=1</script>"\'&';
(async()=>{
 const b=await chromium.launch();
 // ---------------------------------------------------------------- 1
 console.log('\n1. Contenido malicioso (XSS) en preguntas, opciones y explicaciones');
 let d=mkdir();
 W(d,'Peligro/Tema/Q.json',{title:'Q',cards:[{t:'abc',q:XSS,e:XSS,opts:[{x:XSS,ok:true},{x:'b',ok:false}]},{t:'rel',q:XSS,e:'',pairs:[{l:XSS,r:XSS},{l:'a',r:'b'}]},{t:'grp',q:XSS,e:'',groups:[{n:XSS,items:[XSS]},{n:'g',items:['i']}]},{t:'steps',q:XSS,steps:[{t:XSS,d:'',f:'\\text{'+XSS.replace(/[{}\\]/g,'')+'}'},{t:'b',d:'',f:''}]}]});
 let s=await start(d);let p=await b.newPage();const errs=[];p.on('pageerror',e=>errs.push(e.message));let dlg=0;p.on('dialog',async x=>{dlg++;await x.accept()});
 await p.goto(`http://localhost:${s.port}/`);await sleep(900);await row(p,'Q').click();await sleep(400);
 T('el editor no ejecuta código metido en las tarjetas',!(await p.evaluate(()=>window.__xss)));
 T('el texto peligroso se ve como texto',(await p.inputValue('textarea[data-k="q"]')).includes('<script>'));
 const [pop]=await Promise.all([p.waitForEvent('popup'),p.click('#test')]);await sleep(500);
 for(let i=0;i<4;i++){try{await pop.click('.opt,.cell,.chip',{timeout:500})}catch(e){}}
 T('la práctica tampoco ejecuta código',!(await pop.evaluate(()=>window.__xss)));
 await pop.close();
 const [dl1]=await Promise.all([p.waitForEvent('download'),p.click('#dl')]);const fx=path.join(d,'x.html');await dl1.saveAs(fx);const hx=fs.readFileSync(fx,'utf8');
 T('el HTML exportado no deja «<script>» suelto en los datos',!hx.includes('<script>window.__xss'));
 const ex=await b.newPage();await ex.goto('file://'+fx);await sleep(300);for(let i=0;i<4;i++){try{await ex.click('.opt,.cell,.chip',{timeout:500})}catch(e){}}
 T('abrir el HTML exportado no ejecuta código',!(await ex.evaluate(()=>window.__xss)));await ex.close();
 // modo móvil
 const m=await b.newPage({viewport:{width:390,height:800}});m.on('pageerror',e=>errs.push('M '+e.message));
 const tree=fs.readFileSync(path.join(d,'Mis cuestionarios/Peligro/Tema/Q.json'),'utf8');
 await m.addInitScript(t=>{const S=JSON.stringify({n:'x',f:[{n:'<b>Peligro</b>',f:[{n:'T',f:[],q:[JSON.parse(t)]}],q:[]}],q:[]});window.Android={load:()=>S,save:()=>'',getLang:()=>'es',setLang(){},exportFile:()=>'',toast(){},preview(){},pickFolder(){}}},tree);
 await m.goto('file://'+path.join(SRC,'creador-cuestionarios.html'));await sleep(500);await m.click('.hn[data-o]');await m.click('.ht .hn[data-o]');await sleep(200);
 T('la pantalla principal del móvil no ejecuta código',!(await m.evaluate(()=>window.__xss))&&!(await m.evaluate(()=>!!document.querySelector('#home b b'))));
 T('sin errores de JavaScript con contenido raro',errs.length==0,errs.join('|'));
 s.kill();await p.close();await m.close();
 // ---------------------------------------------------------------- 2
 console.log('\n2. Nombres con acentos, emojis, otros alfabetos y 80 caracteres');
 d=mkdir();s=await start(d);p=await b.newPage();p.on('pageerror',e=>errs.push(e.message));const ans=[];p.on('dialog',async x=>{await x.accept(ans.shift()||'')});
 await p.goto(`http://localhost:${s.port}/`);await sleep(800);
 const names=['Física ⚛️ 2º Bach','日本語の勉強','Ελληνικά — Ñandú','x'.repeat(80)];
 ans.push(names[0]);await p.click('#nn');await sleep(300);ans.push(names[1]);await p.click('#nn');await sleep(300);ans.push(names[2]);await p.click('#nn');await sleep(300);
 await row(p,'Mis cuestionarios').click();ans.push(names[3]);await p.click('#nn');await sleep(1000);
 T('se crean en disco con su nombre exacto',fs.existsSync(path.join(d,'Mis cuestionarios',names[0],names[1],names[2]+'.json'))&&fs.existsSync(path.join(d,'Mis cuestionarios',names[3])),JSON.stringify(fs.readdirSync(path.join(d,'Mis cuestionarios'))));
 await p.reload();await sleep(1000);
 T('al recargar se leen igual',await (await row(p,names[2]).count())==1);
 s.kill();await p.close();
 // ---------------------------------------------------------------- 3
 console.log('\n3. Archivos rotos o raros dentro de la carpeta');
 d=mkdir();
 W(d,'A/T/bueno.json',{title:'bueno',cards:[{t:'vf',q:'x',e:'',v:true}]});
 W(d,'A/T/vacio.json','');W(d,'A/T/roto.json','{"title":');W(d,'A/T/lista.json','[1,2,3]');W(d,'A/T/sincartas.json','{"title":"x"}');
 W(d,'A/T/notas.txt','hola');W(d,'A/T/imagen.png','\x89PNG');W(d,'A/T/tarjeta-mala.json',{title:'mala',cards:[{t:'zzz',q:'?'},{t:'abc',q:'sin opts'},null,5]});
 W(d,'A/T/profundo/mas/aun/q.json',{title:'hondo',cards:[{t:'vf',q:'y',e:'',v:false}]});
 W(d,'.oculta/x.json',{title:'oculto',cards:[]});
 s=await start(d);p=await b.newPage();const e3=[];p.on('pageerror',e=>e3.push(e.message));p.on('dialog',x=>x.accept());
 await p.goto(`http://localhost:${s.port}/`);await sleep(2000);
 T('el creador carga sin errores',e3.length==0,e3.join('|'));
 const tr=await p.evaluate(()=>[...document.querySelectorAll('#tree .tr')].map(x=>x.textContent).join('|'));
 T('lee los buenos y los mal colocados',tr.includes('bueno')&&tr.includes('hondo'),tr);
 T('la tarjeta con tipo desconocido no rompe nada',tr.includes('mala'));
 T('no borra los .json ilegibles',['vacio.json','roto.json','lista.json','sincartas.json'].every(f=>fs.existsSync(path.join(d,'Mis cuestionarios/A/T',f))));
 T('no borra archivos que no son cuestionarios',fs.existsSync(path.join(d,'Mis cuestionarios/A/T/notas.txt'))&&fs.existsSync(path.join(d,'Mis cuestionarios/A/T/imagen.png')));
 T('ignora carpetas ocultas',!tr.includes('oculto')&&fs.existsSync(path.join(d,'Mis cuestionarios/.oculta/x.json')));
 T('el servidor sigue vivo',(await raw(s.port,'GET','/api/stamp'))[0]==200);
 await p.reload();await sleep(1500);
 T('abrir con archivos corruptos NO borra ningún cuestionario',['bueno.json','tarjeta-mala.json'].every(f=>fs.existsSync(path.join(d,'Mis cuestionarios/A/T',f)))||fs.existsSync(path.join(d,'Mis cuestionarios/A/T/bueno.json')),fs.readdirSync(path.join(d,'Mis cuestionarios/A/T')).join(','));
 const m3=await b.newPage({viewport:{width:390,height:800}});const e3m=[];m3.on('pageerror',e=>e3m.push(e.message));let saved3=null;
 await m3.exposeFunction('sv3',j=>{saved3=j});
 await m3.addInitScript(()=>{const S=JSON.stringify({n:'x',f:[{n:'A',f:[{n:'T',f:[],q:[{title:'ok',cards:[{t:'vf',q:'a',e:'',v:true}]},{title:'mala',cards:[null,5,{t:'abc',opts:null},{t:'foto',img:'javascript:alert(1)'}]}]}],q:[]},7,null],q:'no'});window.Android={load:()=>S,save:j=>{sv3(j);return ''},getLang:()=>'es',setLang(){},exportFile:()=>'',toast(){},preview(){},pickFolder(){}}});
 await m3.goto('file://'+path.join(SRC,'creador-cuestionarios.html'));await sleep(800);
 T('móvil: datos corruptos se cargan sin errores',e3m.length==0,e3m.join('|'));
 T('móvil: no se pierde ningún cuestionario al abrir',!saved3||(saved3.includes('"ok"')&&saved3.includes('"mala"')),String(saved3).slice(0,200));
 T('móvil: una «foto» con código se descarta',!String(saved3).includes('javascript:'));
 await m3.close();
 s.kill();await p.close();
 // ---------------------------------------------------------------- 4
 console.log('\n4. Mucho volumen: 20 asignaturas × 10 temas × 10 cuestionarios = 2000');
 d=mkdir();const big={t:'abc',q:'¿Cuánto es 2+2? '.repeat(3),e:'Explicación larga '.repeat(5),opts:[{x:'3',ok:false},{x:'4',ok:true},{x:'5',ok:false}]};
 for(let a=0;a<20;a++)for(let t=0;t<10;t++)for(let q=0;q<10;q++)W(d,`Asig ${a}/Tema ${t}/Q ${q}.json`,{title:`Q ${q}`,cards:Array(8).fill(big)});
 s=await start(d);p=await b.newPage();const e4=[];p.on('pageerror',e=>e4.push(e.message));p.on('dialog',x=>x.accept());
 let t0=Date.now();await p.goto(`http://localhost:${s.port}/`);await p.waitForFunction(()=>document.querySelectorAll('#tree .tr').length>100,null,{timeout:60000});
 const tLoad=Date.now()-t0;T(`carga 2000 cuestionarios en menos de 8 s (${tLoad} ms)`,tLoad<8000);
 await row(p,'Q 3').click();await sleep(300);
 const fq=path.join(d,'Mis cuestionarios/Asig 0/Tema 0/Q 3.json');t0=Date.now();await p.fill('textarea[data-k="q"] >> nth=0','cambiado');
 while(Date.now()-t0<30000&&!fs.readFileSync(fq,'utf8').includes('cambiado'))await sleep(50);
 const tSave=Date.now()-t0;T(`guardar un cambio con 2000 cuestionarios tarda menos de 3 s (${tSave} ms)`,tSave<3000);
 T('solo se ha reescrito el archivo cambiado',JSON.parse(fs.readFileSync(path.join(d,'Mis cuestionarios/Asig 0/Tema 0/Q 3.json'),'utf8')).cards[0].q=='cambiado');
 t0=Date.now();const [st]=await raw(s.port,'GET','/api/stamp');const tSt=Date.now()-t0;
 T(`la comprobación de cambios cada 8 s es rápida (${tSt} ms)`,st==200&&tSt<1500);
 // móvil con 2000
 const tj=(await raw(s.port,'GET','/api/tree'))[1];const m4=await b.newPage({viewport:{width:390,height:800}});m4.on('pageerror',e=>e4.push('M '+e.message));
 await m4.addInitScript(t=>{const S=JSON.stringify(JSON.parse(t).tree);window.Android={load:()=>S,save:()=>'',getLang:()=>'es',setLang(){},exportFile:()=>'',toast(){},preview(){},pickFolder(){}}},tj);
 t0=Date.now();await m4.goto('file://'+path.join(SRC,'creador-cuestionarios.html'));await m4.waitForSelector('.ha');const tM=Date.now()-t0;
 T(`la pantalla del móvil abre con 2000 cuestionarios en menos de 3 s (${tM} ms)`,tM<3000);
 await m4.check('.ha .ck >> nth=0');await sleep(200);
 T('marcar una asignatura entera marca sus 100 cuestionarios',(await m4.textContent('#hsp')).includes('(100)'));
 T('sin errores con mucho volumen',e4.length==0,e4.join('|'));
 s.kill();await p.close();await m4.close();
 // ---------------------------------------------------------------- 5
 console.log('\n5. Foto enorme (4000×3000)');
 d=mkdir();W(d,'A/T/F.json',{title:'F',cards:[]});s=await start(d);p=await b.newPage();p.on('dialog',x=>x.accept());
 await p.goto(`http://localhost:${s.port}/`);await sleep(800);await row(p,'F').click();await p.click('[data-t="foto"]');
 const bigImg=await p.evaluate(()=>{const c=document.createElement('canvas');c.width=4000;c.height=3000;const g=c.getContext('2d');for(let i=0;i<3000;i++){g.fillStyle=`hsl(${i%360},80%,50%)`;g.fillRect(Math.random()*4000,Math.random()*3000,90,90)}return c.toDataURL('image/png')});
 const f5=path.join(d,'big.png');fs.writeFileSync(f5,Buffer.from(bigImg.split(',')[1],'base64'));
 await p.locator('#list input[type=file]').setInputFiles(f5);await sleep(2500);
 const img=JSON.parse(fs.readFileSync(path.join(d,'Mis cuestionarios/A/T/F.json'),'utf8')).cards[0].img;
 const dim=await p.evaluate(src=>new Promise(r=>{const i=new Image();i.onload=()=>r([i.width,i.height]);i.src=src}),img);
 T(`se reduce a 1280 px (${dim.join('×')}, ${(img.length/1024|0)} KB frente a ${(fs.statSync(f5).size/1024|0)} KB)`,Math.max(...dim)==1280&&img.length<fs.statSync(f5).size);
 s.kill();await p.close();
 // ---------------------------------------------------------------- 6
 console.log('\n6. Dos pestañas a la vez (o editar mientras cambias cosas a mano)');
 d=mkdir();W(d,'A/T/Q1.json',{title:'Q1',cards:[]});s=await start(d);
 const p1=await b.newPage(),p2=await b.newPage();for(const x of [p1,p2]){x.on('dialog',async y=>{await y.accept(x===p1?'DeLaPestaña1':'DeLaPestaña2')})}
 await p1.goto(`http://localhost:${s.port}/`);await p2.goto(`http://localhost:${s.port}/`);await sleep(900);
 await row(p1,'T').click();await p1.click('#nn');await sleep(800);
 await row(p2,'T').click();await p2.click('#nn');await sleep(1200);
 const files6=fs.readdirSync(path.join(d,'Mis cuestionarios/A/T')).sort().join(',');
 T('lo creado en una pestaña no se pierde al guardar en la otra',files6=='DeLaPestaña1.json,DeLaPestaña2.json,Q1.json',files6);
 W(d,'A/T/AMano.json',{title:'AMano',cards:[]});await sleep(100);
 await row(p1,'DeLaPestaña1').click();await p1.click('[data-t="vf"]');await sleep(1200);
 T('lo copiado a mano tampoco se pierde si guardas justo después',fs.existsSync(path.join(d,'Mis cuestionarios/A/T/AMano.json')),fs.readdirSync(path.join(d,'Mis cuestionarios/A/T')).join(','));
 s.kill();await p1.close();await p2.close();
 // ---------------------------------------------------------------- 7
 console.log('\n7. Servidor ante peticiones malas');
 d=mkdir();s=await start(d);
 let r=await raw(s.port,'PUT','/api/tree','{esto no es json');T('JSON roto → error controlado',r[0]>=400&&r[0]<600);
 r=await raw(s.port,'PUT','/api/tree',JSON.stringify([1,2]));T('árbol con forma rara → 400',r[0]==400);
 r=await raw(s.port,'PUT','/api/tree',JSON.stringify({f:[{n:'../../fuera',f:[],q:[]}],q:[]}));
 T('nombres con «../» no salen de la carpeta',r[0]==400&&!fs.existsSync(path.join(d,'..','fuera')),r[1]);
 r=await raw(s.port,'POST','/api/abrir',JSON.stringify({path:['..','..','..']}));T('abrir carpeta no sale de «Mis cuestionarios»',!String(r[1]).includes('"ruta": "/"'),r[1]);
 r=await raw(s.port,'GET','/../servidor.py');T('no sirve otros archivos del PC',r[0]==404);
 r=await raw(s.port,'POST','/api/lang',JSON.stringify({lang:'xx'}));T('idioma inválido rechazado',r[0]==400);
 const s2=await start(d);T(`si el puerto está ocupado usa otro (${s.port} → ${s2.port})`,s2.port!=s.port&&s2.port>0);s2.kill();
 r=await raw(s.port,'POST','/api/wifi',JSON.stringify({accion:'start'}));const w=JSON.parse(r[1]);
 const up=(body,pin=w.pin)=>fetch(`http://${IP}:${w.port}/mc/subir?pin=${pin}`,{method:'POST',body}).then(x=>x.status).catch(()=>0);
 T('Wi‑Fi: el móvil envía basura → 400',await up('nada de json')==400);
 T('Wi‑Fi: envía un árbol sin asignaturas → 400',await up(JSON.stringify({x:1}))==400);
 const many=await Promise.all(Array(30).fill(0).map(()=>fetch(`http://${IP}:${w.port}/mc/tree?pin=${w.pin}`).then(x=>x.status).catch(()=>0)));
 T('Wi‑Fi: 30 descargas a la vez',many.every(x=>x==200),many.join(','));
 T('el servidor no ha escrito errores',!/Traceback/.test(s.err()),s.err().slice(0,300));
 await raw(s.port,'POST','/api/wifi',JSON.stringify({accion:'stop'}));s.kill();
 // ---------------------------------------------------------------- 8
 console.log('\n8. Teclado de fórmulas: 2000 pulsaciones al azar');
 d=mkdir();W(d,'A/T/M.json',{title:'M',cards:[{t:'steps',q:'x',steps:[{t:'a',d:'',f:''},{t:'b',d:'',f:''}]}]});s=await start(d);
 p=await b.newPage();const e8=[];p.on('pageerror',e=>e8.push(e.message));await p.goto(`http://localhost:${s.port}/`);await sleep(800);
 await row(p,'M').click();await p.click('[data-act="kb"] >> nth=0');
 const keys=await p.evaluate(()=>[...document.querySelectorAll('.kb')[0].querySelectorAll('[data-key]')].map(x=>x.dataset.key).filter(k=>k!='ABC'));
 const kb=p.locator('.kb').nth(0);let rnd=7;const R=()=>(rnd=(rnd*1103515245+12345)%2147483648)/2147483648;
 for(let i=0;i<400;i++){await kb.locator(`[data-key="${keys[R()*keys.length|0]}"]`).click({timeout:2000})}
 await p.locator('.mf').nth(0).click();for(let i=0;i<600;i++){const k=['x','2','^','/','(',')','Backspace','ArrowLeft','ArrowRight','Enter','a',' ','Home','End','Delete'][R()*15|0];k.length>1?await p.keyboard.press(k):await p.keyboard.type(k)}
 await sleep(800);const f8=JSON.parse(fs.readFileSync(path.join(d,'Mis cuestionarios/A/T/M.json'),'utf8')).cards[0].steps[0].f;
 const bal=[...f8].reduce((a,c)=>a<0?a:a+(c=='{')-(c=='}'),0);
 T('ningún error tras 1000 pulsaciones al azar',e8.length==0,e8.slice(0,3).join('|'));
 T('la fórmula guardada sigue bien formada (llaves equilibradas)',bal==0,f8.slice(0,80));
 T('se puede mostrar sin errores',await p.locator('.mf').nth(0).locator('math').count()>0);
 s.kill();await p.close();
 // ---------------------------------------------------------------- 9
 console.log('\n9. Escribir muy rápido y cerrar la pestaña enseguida');
 d=mkdir();W(d,'A/T/R.json',{title:'R',cards:[{t:'vf',q:'',e:'',v:true}]});s=await start(d);p=await b.newPage();
 await p.goto(`http://localhost:${s.port}/`);await sleep(800);await row(p,'R').click();
 await p.click('textarea[data-k="q"]');await p.keyboard.type('Texto escrito muy deprisa 1234567890',{delay:5});await p.goto('about:blank');await p.close();await sleep(800);
 const q9=JSON.parse(fs.readFileSync(path.join(d,'Mis cuestionarios/A/T/R.json'),'utf8')).cards[0].q;
 T('lo último escrito se guarda aunque cierres al momento',q9=='Texto escrito muy deprisa 1234567890',q9);
 s.kill();
 await b.close();
 console.log(`\nEstrés: ${ok} bien, ${bad} mal`);if(fails.length)console.log('FALLOS:\n - '+fails.join('\n - '));
})().catch(e=>{console.error('ERROR',e);process.exit(1)});

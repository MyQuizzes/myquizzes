// Pruebas de la pantalla de práctica (HTML exportado): contesta bien y mal los 6 tipos de tarjeta.
const {chromium}=require('playwright');const fs=require('fs');
let ok=0,bad=0;const fails=[];
const T=(n,c,i='')=>{if(c){ok++;console.log('  ✓',n)}else{bad++;fails.push(n+(i?' → '+i:''));console.log('  ✗',n,i)}};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const card={
 abc:{t:'abc',q:'¿Pares?',e:'2 y 4',opts:[{x:'2',ok:true},{x:'3',ok:false},{x:'4',ok:true}]},
 vf:{t:'vf',q:'0 es natural',e:'',v:false},
 steps:{t:'steps',q:'Resolver',steps:[{t:'Paso A',d:'',f:'',h:''},{t:'Paso B',d:'',f:'',h:''},{t:'Paso C',d:'',f:'',h:''}]},
 rel:{t:'rel',q:'Relaciona',e:'',pairs:[{l:'2²',r:'4'},{l:'3²',r:'9'},{l:'4²',r:'16'}]},
 grp:{t:'grp',q:'Clasifica',e:'',groups:[{n:'Pares',items:['2','6']},{n:'Impares',items:['3','5']}]},
 foto:{t:'foto',q:'Un punto',e:'',img:'data:image/gif;base64,R0lGODlhAQABAAAAACw='}};
const page=(title,cards,extra={})=>{
  const src=fs.readFileSync('/tmp/mc-export.html','utf8');
  const data=JSON.stringify({title,solo:true,run:true,root:{n:title,f:[],q:[{title,k:'A/T/'+title,cards}]},lang:'es',dec:',',L:{},...extra});
  return src.replace(/<script id="d" type="application\/json">[\s\S]*?<\/script>/,()=>'<script id="d" type="application/json">'+data.replace(/</g,'\\u003c')+'</script>')};
// contesta la tarjeta visible; good=true bien, false mal
async function answer(p,good){
  const tag=await p.textContent('.tag');const q=await p.textContent('.q').catch(()=>'');
  if(tag.startsWith('Foto')){await p.click('#nx');return 'foto'}
  if(tag.startsWith('Verdadero')){await p.click(`.opt[data-v="${good?0:1}"]`);await p.click('#nx');return 'vf'}
  if(tag.startsWith('Test')){const want=good?['2','4']:['3'];for(const w of want)await p.click(`.opt:has(span:text-is("${w}"))`);await p.click('#ck');await p.click('#nx');return 'abc'}
  if(tag.startsWith('Procedimiento')){const order=good?['Paso A','Paso B','Paso C']:['Paso B','Paso A','Paso B','Paso C'];for(const o of order){const b=p.locator(`#pl .opt:text-is("${o}")`);if(await b.count())await b.click();await sleep(650)}await p.click('#nx');return 'steps'}
  if(tag.startsWith('Relacionar')){const L=['2²','3²','4²'],R={'2²':'4','3²':'9','4²':'16'};const A=p.locator('.mt>div').nth(0),B=p.locator('.mt>div').nth(1);
    if(!good){await A.locator('button:text-is("2²")').click();await B.locator('button:text-is("9")').click();await sleep(650)}
    for(const l of L){await A.locator(`button:text-is("${l}")`).click();await B.locator(`button:text-is("${R[l]}")`).click()}await p.click('#nx');return 'rel'}
  if(tag.startsWith('Clasificar')){const G={'2':0,'6':0,'3':1,'5':1};for(const [x,g] of Object.entries(G)){await p.click(`.pool .chip:has-text("${x}")`);await p.click(`.grp[data-g="${good?g:1-g}"]`)}
    await p.click('#ck');await p.click('#nx');return 'grp'}
  throw new Error('tarjeta desconocida: '+tag)}
(async()=>{
 const b=await chromium.launch();const ctx=await b.newContext();const errs=[];
 const all=Object.values(card);
 const f1='/tmp/prac1.html';fs.writeFileSync(f1,page('Todo',all));
 let p=await ctx.newPage();p.on('pageerror',e=>errs.push(e.message));p.on('dialog',d=>d.accept());
 console.log('\n1. Contestar todo bien');
 await p.goto('file://'+f1);await sleep(300);
 T('empieza en la tarjeta 1 de 6',(await p.textContent('#cnt'))=='Tarjeta 1 de 6');
 const seen=[];for(let i=0;i<6;i++)seen.push(await answer(p,true));
 T('salen los 6 tipos (en orden aleatorio)',seen.sort().join()=='abc,foto,grp,rel,steps,vf',seen.join());
 T('nota 10 con todo bien (la foto no cuenta)',(await p.textContent('.score')).startsWith('10,0'),await p.textContent('.score'));
 T('«Sin ningún fallo»',(await p.textContent('#main')).includes('Sin ningún fallo'));
 const best=await p.evaluate(()=>localStorage.getItem('mqk:A/T/Todo:6'));
 T('guarda la mejor nota con la clave estable',best=='10',best);
 console.log('\n2. Contestar todo mal');
 await p.click('#again');for(let i=0;i<6;i++)await answer(p,false);
 const sc=await p.textContent('.score');
 T('nota 0 con todo mal',sc.startsWith('0,0'),sc);
 T('muestra 5 para repasar',await p.locator('.miss').count()==5);
 T('avisa de la mejor nota anterior',(await p.textContent('#main')).includes('Tu mejor nota: 10,0'));
 await p.click('#rm');
 T('«Repetir solo falladas» repite 5 tarjetas',(await p.textContent('#cnt'))=='Tarjeta 1 de 5');
 for(let i=0;i<5;i++)await answer(p,true);
 console.log('\n3. Mitad bien (clasificar puntúa parcial)');
 const f2='/tmp/prac2.html';fs.writeFileSync(f2,page('Grupos',[card.grp,card.vf]));
 await p.goto('file://'+f2);await sleep(200);
 for(let i=0;i<2;i++){const tag=await p.textContent('.tag');
   if(tag.startsWith('Clasificar')){for(const [x,g] of [['2',0],['6',1],['3',1],['5',1]]){await p.click(`.pool .chip:has-text("${x}")`);await p.click(`.grp[data-g="${g}"]`)}await p.click('#ck');
     T('clasificar: 3 de 4 bien colocados',(await p.textContent('#an')).includes('3 de 4'));await p.click('#nx')}
   else await answer(p,true)}
 T('nota 8,8 (1 + 0,75 de 2)',(await p.textContent('.score')).startsWith('8,8'),await p.textContent('.score'));
 console.log('\n4. Varios cuestionarios seguidos + resumen');
 const multi=JSON.stringify({title:'Práctica',solo:false,run:false,lang:'es',dec:',',L:{},root:{n:'Práctica',q:[],f:[{n:'Mates',q:[],f:[{n:'T1',f:[],q:[{title:'Q1',k:'Mates/T1/Q1',cards:[card.vf]},{title:'Q2',k:'Mates/T1/Q2',cards:[card.abc]}]}]}]}});
 const f3='/tmp/prac3.html';fs.writeFileSync(f3,fs.readFileSync('/tmp/mc-export.html','utf8').replace(/<script id="d" type="application\/json">[\s\S]*?<\/script>/,()=>'<script id="d" type="application/json">'+multi+'</script>'));
 await p.goto('file://'+f3);await sleep(200);
 T('con varios sale el menú',(await p.textContent('#main')).includes('Marca varios'));
 await p.click('.qz.fo');await p.click('#rf');
 await answer(p,true);await p.click('#go');await answer(p,false);await p.click('#go');
 T('resumen con nota media 5,0',(await p.textContent('.score')).startsWith('5,0'),await p.textContent('.score'));
 T('resumen lista 2 cuestionarios',await p.locator('.sr').count()==2);
 await p.click('#mn');await sleep(200);await p.click('.qz.fo');await sleep(200);
 T('volver al menú muestra la mejor nota de cada uno',(await p.textContent('#main')).includes('mejor 10,0')&&(await p.textContent('#main')).includes('mejor 0,0'),await p.textContent('#main'));
 console.log('\n5. Otros idiomas en el archivo exportado');
 const src=fs.readFileSync('/tmp/mc-export.html','utf8');
 const I=JSON.parse(fs.readFileSync(__dirname+'/../PC/creador-cuestionarios.html','utf8').match(/\/\*I18N\*\/([\s\S]*?)\/\*\/I18N\*\//)[1]);
 for(const l of ['en','de']){fs.writeFileSync('/tmp/prac-'+l+'.html',page('X',[card.vf],{lang:l,dec:l=='en'?'.':',',L:I[l]}));
   await p.goto('file:///tmp/prac-'+l+'.html');await sleep(200);
   const t=await p.textContent('#main');T(`práctica en ${l}`,l=='en'?t.includes('True or false'):t.includes('Richtig oder falsch'),t.slice(0,60));
   await p.click('.opt[data-v="0"]');await p.click('#nx');const s=await p.textContent('.score');T(`decimales en ${l}`,l=='en'?s.startsWith('10.0'):s.startsWith('10,0'),s)}
 console.log('\n6. Orden aleatorio');
 const f6='/tmp/prac6.html';fs.writeFileSync(f6,page('Orden',[{t:'abc',q:'¿?',e:'',opts:[{x:'Uno',ok:true},{x:'Dos',ok:false},{x:'Tres',ok:false},{x:'Cuatro',ok:false}]}]));
 const seenA=new Set(),firstA={};
 for(let i=0;i<25;i++){await p.goto('file://'+f6);await sleep(60);const o=await p.evaluate(()=>[...document.querySelectorAll('.opt span:last-child')].map(x=>x.textContent).join(','));seenA.add(o);firstA[o.split(',')[0]]=1}
 T(`test A/B/C: las opciones salen en orden distinto (${seenA.size} órdenes en 25 veces)`,seenA.size>=8);
 T('la correcta no está siempre en la A',Object.keys(firstA).length>=3,Object.keys(firstA).join());
 await p.goto('file://'+f6);await sleep(60);await p.click('.opt:has(span:text-is("Uno"))');await p.click('#ck');
 T('elegir la correcta (esté donde esté) cuenta como bien',(await p.textContent('#an')).includes('Correcto'));
 T('las letras siguen siendo A, B, C, D en orden',(await p.evaluate(()=>[...document.querySelectorAll('.opt .bx')].map(x=>x.textContent).join('')))=='ABCD');
 const f7='/tmp/prac7.html';fs.writeFileSync(f7,page('Rel',[card.rel]));const seenL=new Set();
 for(let i=0;i<15;i++){await p.goto('file://'+f7);await sleep(60);seenL.add(await p.evaluate(()=>[...document.querySelectorAll('.mt>div:first-child .cell')].map(x=>x.textContent).join(',')))}
 T(`relacionar: la columna izquierda también se mezcla (${seenL.size} órdenes)`,seenL.size>=3);
 await p.goto('file://'+f7);await sleep(60);await answer(p,true);
 T('relacionar mezclado se corrige bien',(await p.textContent('.score')).startsWith('10,0'),await p.textContent('.score'));
 T('ningún error de JavaScript en la práctica',errs.length==0,errs.join(' | '));
 await b.close();
 console.log(`\nPráctica: ${ok} bien, ${bad} mal`);if(fails.length)console.log('FALLOS:\n - '+fails.join('\n - '));
 fs.writeFileSync('/tmp/prac-result.json',JSON.stringify({ok,bad,fails}));
})().catch(e=>{console.error('ERROR',e);process.exit(1)});

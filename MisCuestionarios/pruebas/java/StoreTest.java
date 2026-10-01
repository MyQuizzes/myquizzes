import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Pruebas del guardado y del Wi‑Fi del móvil (código de MainActivity extraído en Store.java). */
public class StoreTest {
  static int ok=0,bad=0;static List<String> fails=new ArrayList<>();
  static void T(String n,boolean c,String i){if(c){ok++;System.out.println("  ✓ "+n);}else{bad++;fails.add(n+" → "+i);System.out.println("  ✗ "+n+"  "+i);}}
  static void T(String n,boolean c){T(n,c,"");}
  static JSONObject q(String t,int n){JSONObject o=new JSONObject().put("title",t);JSONArray c=new JSONArray();for(int i=0;i<n;i++)c.put(new JSONObject().put("t","vf").put("q","P"+i).put("e","").put("v",true));return o.put("cards",c);}
  static JSONObject f(String n,Object... kids){JSONObject o=new JSONObject().put("n",n);JSONArray F=new JSONArray(),Q=new JSONArray();for(Object k:kids){JSONObject j=(JSONObject)k;if(j.has("cards"))Q.put(j);else F.put(j);}return o.put("f",F).put("q",Q);}
  static Set<String> files(File d){Set<String> s=new TreeSet<>();try{Files.walk(d.toPath()).forEach(p->{String r=d.toPath().relativize(p).toString().replace('\\','/');if(!r.isEmpty())s.add(r+(Files.isDirectory(p)?"/":""));});}catch(IOException e){}return s;}
  public static void main(String[] a) throws Exception {
    File base=Files.createTempDirectory("mcand").toFile();Store s=new Store(base);File R=s.root;
    System.out.println("\n1. Guardar y leer");
    JSONObject tree=f("Mis cuestionarios",f("Matemáticas",f("Álgebra",q("Ecuaciones",3),q("Polinomios",2))),f("Física ⚛️ 日本",f("Tema «1»",q("Fuerzas: \"F=ma\"",1))));
    s.writeTree(tree,false);
    T("crea carpetas y archivos",new File(R,"Matemáticas/Álgebra/Ecuaciones.json").exists()&&new File(R,"Física ⚛️ 日本/Tema «1»/Fuerzas- -F=ma-.json").exists(),files(R).toString());
    JSONObject back=s.readTree();
    T("lo leído coincide con lo guardado (ida y vuelta)",back.toString().contains("Ecuaciones")&&back.toString().contains("Fuerzas: \\\"F=ma\\\""),back.toString().substring(0,Math.min(200,back.toString().length())));
    long mt=new File(R,"Matemáticas/Álgebra/Ecuaciones.json").lastModified();Thread.sleep(1100);
    s.writeTree(tree,false);
    T("guardar lo mismo no reescribe archivos",new File(R,"Matemáticas/Álgebra/Ecuaciones.json").lastModified()==mt);
    System.out.println("\n2. Nombres repetidos y raros");
    s.writeTree(f("x",f("A",f("T",q("Q",1),q("q",1),q("a/b:c*?",1),q("..",1),q("",1)))),true);
    Set<String> fs=files(new File(R,"A/T"));
    T("repetidos (sin importar mayúsculas) → «(2)»",fs.contains("Q.json")&&fs.contains("q (2).json"),fs.toString());
    T("caracteres prohibidos se cambian por «-»",fs.contains("a-b-c--.json"),fs.toString());
    T("«..» y vacío no se salen de la carpeta",fs.contains("Sin nombre.json")&&fs.contains("Sin nombre (2).json")&&!new File(R.getParentFile(),"..json").exists(),fs.toString());
    System.out.println("\n3. Protección contra borrados masivos");
    s.writeTree(f("x",f("A",f("T",q("1",1),q("2",1),q("3",1),q("4",1)))),true);
    try{s.writeTree(f("x",f("A",f("T",q("1",1)))),false);T("borrar 3 de golpe sin permiso → se niega",false,"no lanzó error");}
    catch(IOException e){T("borrar 3 de golpe sin permiso → se niega",true);}
    T("…y no ha borrado nada",new File(R,"A/T/4.json").exists()&&new File(R,"A/T/2.json").exists());
    s.writeTree(f("x",f("A",f("T",q("1",1),q("2",1),q("3",1)))),false);
    T("borrar 1 sin permiso (editar normal) → sí",!new File(R,"A/T/4.json").exists());
    s.writeTree(f("x",f("A",f("T",q("1",1)))),true);
    T("borrar varios con permiso (tú lo pediste) → sí",files(new File(R,"A/T")).equals(new TreeSet<>(List.of("1.json"))),files(new File(R,"A/T")).toString());
    s.writeTree(f("x",f("B",f("T",q("1",1)))),true);
    T("renombrar asignatura quita la carpeta vieja",!new File(R,"A").exists()&&new File(R,"B/T/1.json").exists(),files(R).toString());
    System.out.println("\n4. Lo que no es de la app no se toca");
    new File(R,".oculta").mkdirs();Files.writeString(new File(R,".oculta/x.json").toPath(),q("oculto",1).toString());
    Files.writeString(new File(R,"B/T/roto.json").toPath(),"{esto no es json");
    Files.writeString(new File(R,"B/T/notas.txt").toPath(),"hola");
    Files.writeString(new File(R,"B/T/medio.json.tmp").toPath(),"{}");
    new File(R,"Vieja/Tema").mkdirs();Files.writeString(new File(R,"Vieja/Tema/foto.png").toPath(),"x");
    s.writeTree(f("x",f("B",f("T",q("1",2)))),true);
    T("carpetas ocultas intactas",new File(R,".oculta/x.json").exists());
    T("json ilegible intacto",new File(R,"B/T/roto.json").exists());
    T("archivos que no son cuestionarios intactos",new File(R,"B/T/notas.txt").exists()&&new File(R,"Vieja/Tema/foto.png").exists());
    T("restos .tmp de un corte de luz se limpian",!new File(R,"B/T/medio.json.tmp").exists());
    JSONObject rt=s.readTree();
    T("al leer ignora lo oculto y lo ilegible",!rt.toString().contains("oculto")&&!rt.toString().contains("roto"));
    System.out.println("\n5. Datos raros que mande la página");
    s.writeTree(new JSONObject("{\"f\":[{\"n\":\"C\",\"f\":[{\"n\":\"T\",\"q\":[{\"title\":\"ok\",\"cards\":[]},5,null,\"x\"],\"f\":[]},7],\"q\":[]}],\"q\":[]}"),true);
    T("elementos que no son objetos se ignoran sin fallar",new File(R,"C/T/ok.json").exists());
    try{new JSONObject("{roto");T("JSON roto lanza error controlado",false);}catch(JSONException e){T("JSON roto lanza error controlado",true);}
    System.out.println("\n6. Mucho volumen (2000 cuestionarios)");
    JSONObject big=new JSONObject().put("n","x");JSONArray A=new JSONArray();
    for(int i=0;i<20;i++){JSONArray TT=new JSONArray();for(int t=0;t<10;t++){JSONArray Q=new JSONArray();for(int k=0;k<10;k++)Q.put(q("Q"+k,8));TT.put(new JSONObject().put("n","T"+t).put("f",new JSONArray()).put("q",Q));}A.put(new JSONObject().put("n","A"+i).put("f",TT).put("q",new JSONArray()));}
    big.put("f",A).put("q",new JSONArray());
    long t0=System.currentTimeMillis();s.writeTree(big,true);long t1=System.currentTimeMillis()-t0;
    T("guardar 2000 la primera vez ("+t1+" ms)",t1<20000);
    t0=System.currentTimeMillis();s.writeTree(big,false);long t2=System.currentTimeMillis()-t0;
    T("volver a guardar sin cambios es rápido ("+t2+" ms)",t2<5000);
    t0=System.currentTimeMillis();JSONObject rb=s.readTree();long t3=System.currentTimeMillis()-t0;
    T("leer 2000 ("+t3+" ms)",t3<5000&&rb.optJSONArray("f").length()>=20);
    System.out.println("\n7. Datos de una versión antigua de la app");
    File b2=Files.createTempDirectory("mcold").toFile();Files.writeString(new File(b2,"datos.json").toPath(),f("x",f("Vieja",f("T",q("Q",1)))).toString());
    Store s2=new Store(b2);s2.migrateOld();
    T("se pasan a carpetas al abrir la versión nueva",new File(s2.root,"Vieja/T/Q.json").exists()&&new File(b2,"datos.json.antiguo").exists());
    if(a.length>=2){
      System.out.println("\n8. Wi‑Fi con el servidor del PC de verdad");
      String host=a[0],pin=a[1];
      String tr=s.wifiFetch(pin,host);T("recibir del PC con el PIN",tr.contains("\"f\""));
      try{s.wifiFetch("0000x",host);T("PIN malo → aviso «pin»",false);}catch(Exception e){T("PIN malo → aviso «pin»","pin".equals(e.getMessage()),e.getMessage());}
      try{s.wifiFetch(pin,"127.0.0.1:1");T("dirección mala → «nopc»",false);}catch(Exception e){T("dirección mala → «nopc»","nopc".equals(e.getMessage()),e.getMessage());}
      try{s.wifiFetch(pin,"");T("sin dirección y sin Wi‑Fi → «nopc»",false);}catch(Exception e){T("sin dirección y sin Wi‑Fi → «nopc»","nopc".equals(e.getMessage()),e.getMessage());}
      s.writeTree(f("x",f("DesdeElMovil",f("T",q("Q",2)))),true);
      String r=s.wifiUpload(pin,host);T("enviar al PC",r.contains("ok"),r);
      T("dirección escrita con http:// y barra final también vale",s.wifiFetch(pin,"http://"+host+"/").contains("\"f\""));
    }
    System.out.println("\nJava del móvil: "+ok+" bien, "+bad+" mal");if(!fails.isEmpty())System.out.println("FALLOS:\n - "+String.join("\n - ",fails));
    System.exit(bad>0?1:0);
  }
}

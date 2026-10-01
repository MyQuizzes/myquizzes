package org.json;
import java.util.*;
/** org.json mínimo (mismo comportamiento que el de Android para lo que usa la app). */
public class JSONObject {
  final LinkedHashMap<String,Object> m=new LinkedHashMap<>();
  public JSONObject(){}
  public JSONObject(String s){Object o=new P(s).val();if(!(o instanceof JSONObject))throw new JSONException("no es objeto");m.putAll(((JSONObject)o).m);}
  public JSONObject put(String k,Object v){m.put(k,v==null?NULL:v);return this;}
  public boolean has(String k){return m.containsKey(k);}
  public Object opt(String k){return m.get(k);}
  public JSONArray optJSONArray(String k){Object o=m.get(k);return o instanceof JSONArray?(JSONArray)o:null;}
  public JSONObject optJSONObject(String k){Object o=m.get(k);return o instanceof JSONObject?(JSONObject)o:null;}
  public JSONObject getJSONObject(String k){Object o=m.get(k);if(o instanceof JSONObject)return (JSONObject)o;throw new JSONException("no "+k);}
  public String optString(String k,String d){Object o=m.get(k);return o==null||o==NULL?d:String.valueOf(o);}
  public String optString(String k){return optString(k,"");}
  public static final Object NULL=new Object(){public String toString(){return "null";}};
  public String toString(){StringBuilder b=new StringBuilder();w(this,b,0,0);return b.toString();}
  public String toString(int ind){StringBuilder b=new StringBuilder();w(this,b,ind,0);return b.toString();}
  public static String quote(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;case '/':b.append("\\/");break;default:if(c<0x20||c==0x2028||c==0x2029)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
  static void nl(StringBuilder b,int ind,int lv){if(ind>0){b.append('\n');for(int i=0;i<ind*lv;i++)b.append(' ');}}
  static void w(Object o,StringBuilder b,int ind,int lv){
    if(o instanceof JSONObject){JSONObject j=(JSONObject)o;b.append('{');boolean f=true;for(Map.Entry<String,Object> e:j.m.entrySet()){if(!f)b.append(',');f=false;nl(b,ind,lv+1);b.append(quote(e.getKey())).append(ind>0?": ":":");w(e.getValue(),b,ind,lv+1);}if(!j.m.isEmpty())nl(b,ind,lv);b.append('}');}
    else if(o instanceof JSONArray){JSONArray a=(JSONArray)o;b.append('[');for(int i=0;i<a.l.size();i++){if(i>0)b.append(',');nl(b,ind,lv+1);w(a.l.get(i),b,ind,lv+1);}if(!a.l.isEmpty())nl(b,ind,lv);b.append(']');}
    else if(o instanceof String)b.append(quote((String)o));
    else if(o instanceof Double){double d=(Double)o;b.append(d==Math.rint(d)&&Math.abs(d)<1e15?String.valueOf((long)d):String.valueOf(d));}
    else b.append(String.valueOf(o));}
  static class P{final String s;int i=0;P(String s){this.s=s;}
    void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
    Object val(){ws();if(i>=s.length())throw new JSONException("fin");char c=s.charAt(i);
      if(c=='{'){i++;JSONObject o=new JSONObject();ws();if(s.charAt(i)=='}'){i++;return o;}while(true){ws();String k=(String)val();ws();if(s.charAt(i++)!=':')throw new JSONException(":");o.m.put(k,val());ws();char d=s.charAt(i++);if(d=='}')return o;if(d!=',')throw new JSONException(",");}}
      if(c=='['){i++;JSONArray a=new JSONArray();ws();if(s.charAt(i)==']'){i++;return a;}while(true){a.l.add(val());ws();char d=s.charAt(i++);if(d==']')return a;if(d!=',')throw new JSONException(",");}}
      if(c=='"'){i++;StringBuilder b=new StringBuilder();while(true){char d=s.charAt(i++);if(d=='"')return b.toString();if(d=='\\'){char e=s.charAt(i++);switch(e){case 'n':b.append('\n');break;case 't':b.append('\t');break;case 'r':b.append('\r');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;case 'u':b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;break;default:b.append(e);}}else b.append(d);}}
      int j=i;while(i<s.length()&&",]} \n\r\t".indexOf(s.charAt(i))<0)i++;String t=s.substring(j,i);
      if(t.equals("true"))return true;if(t.equals("false"))return false;if(t.equals("null"))return NULL;
      try{if(t.matches("-?\\d+"))return Long.parseLong(t);return Double.parseDouble(t);}catch(Exception e){throw new JSONException("valor raro: "+t);}}}
}

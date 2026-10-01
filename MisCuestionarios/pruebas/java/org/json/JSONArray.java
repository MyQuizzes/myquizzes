package org.json;
import java.util.*;
public class JSONArray {
  final ArrayList<Object> l=new ArrayList<>();
  public JSONArray(){}
  public JSONArray put(Object o){l.add(o);return this;}
  public int length(){return l.size();}
  public JSONObject optJSONObject(int i){Object o=i<l.size()?l.get(i):null;return o instanceof JSONObject?(JSONObject)o:null;}
  public JSONObject getJSONObject(int i){JSONObject o=optJSONObject(i);if(o==null)throw new JSONException("no obj");return o;}
  public String toString(){StringBuilder b=new StringBuilder();JSONObject.w(this,b,0,0);return b.toString();}
}

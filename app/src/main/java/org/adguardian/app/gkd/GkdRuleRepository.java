package org.adguardian.app.gkd;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GkdRuleRepository {
    private final Context context;
    private final JSONObject apps;
    private final List<Map<String,Object>> globalGroups;
    private final LinkedHashMap<String,List<Map<String,Object>>> appCache=new LinkedHashMap<>(4,0.75f,true);
    public GkdRuleRepository(Context context) throws IOException,JSONException {
        this.context=context;
        JSONObject manifest=read("gkd/manifest.json");
        if (manifest.getInt("schema")!=1 || manifest.getInt("version")!=593
                || manifest.getInt("rule_count")!=2462) throw new JSONException("Invalid GKD bundle");
        apps=manifest.getJSONObject("apps");
        globalGroups=groups(read("gkd/global.json"));
    }
    public List<Map<String,Object>> globals() { return globalGroups; }
    public boolean hasApp(String packageName) { return apps.has(packageName); }
    public List<Map<String,Object>> forPackage(String packageName) throws IOException,JSONException {
        List<Map<String,Object>> found=appCache.get(packageName);
        if(found!=null)return found;
        JSONObject entry=apps.optJSONObject(packageName);
        if(entry==null)return Collections.emptyList();
        found=groups(read("gkd/"+entry.getString("file")));
        appCache.put(packageName,found);
        if(appCache.size()>4)appCache.remove(appCache.keySet().iterator().next());
        return found;
    }
    public void trim() { appCache.clear(); }
    private JSONObject read(String path) throws IOException,JSONException {
        try (InputStream in=context.getAssets().open(path); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> groups(JSONObject object) throws JSONException {
        Object value=convert(object.getJSONArray("groups"));
        return (List<Map<String,Object>>)value;
    }
    private Object convert(Object value) throws JSONException {
        if(value==JSONObject.NULL)return null;
        if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;List<Object> list=new ArrayList<>(array.length());
            for(int i=0;i<array.length();i++)list.add(convert(array.get(i)));
            return list;
        }
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;Map<String,Object> map=new LinkedHashMap<>();
            Iterator<String> keys=object.keys();
            while(keys.hasNext()){String key=keys.next();map.put(key,convert(object.get(key)));}
            return map;
        }
        return value;
    }
}

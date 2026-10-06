package com.dvlce.pidrive;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;

final class Queue {
    static synchronized JSONArray load(Context c) {
        AtomicFile file = new AtomicFile(new File(c.getFilesDir(), "transfers.json"));
        try (InputStream in = file.openRead()) { return new JSONArray(Api.read(in, 32 * 1024 * 1024)); }
        catch (Exception e) { return new JSONArray(); }
    }
    static synchronized void save(Context c, JSONArray items) throws Exception {
        AtomicFile file = new AtomicFile(new File(c.getFilesDir(), "transfers.json"));
        FileOutputStream stream = file.startWrite();
        try { stream.write(items.toString().getBytes("UTF-8")); file.finishWrite(stream); }
        catch (Exception e) { file.failWrite(stream); throw e; }
    }
    static synchronized int add(Context c, JSONArray incoming) throws Exception {
        JSONArray items = load(c);
        java.util.HashSet<String> queued = new java.util.HashSet<>();
        for (int i=0; i<items.length(); i++) {
            JSONObject item=items.getJSONObject(i);
            if (!"deleted".equals(item.optString("status"))) queued.add(key(item));
        }
        int added=0;
        for (int i=0; i<incoming.length(); i++) {
            JSONObject item=incoming.getJSONObject(i);
            if (queued.add(key(item))) { items.put(item); added++; }
        }
        save(c, items); return added;
    }
    private static String key(JSONObject item) { return item.optString("uri")+":"+item.optString("disk")+":"+item.optLong("size")+":"+item.optLong("modified"); }
}

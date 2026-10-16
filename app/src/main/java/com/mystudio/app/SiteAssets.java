package com.mystudio.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * نسخة الموقع المحفوظة داخل التطبيق (assets/site) — تُعرض عند انقطاع الإنترنت
 * حتى يفتح التطبيق كاملاً من أول تشغيل.
 */
public class SiteAssets {

    private final AssetManager assets;
    private final Map<String, Entry> files = new HashMap<>();
    private String entryUrl = null;

    private static class Entry {
        final String path;
        final String mime;

        Entry(String path, String mime) {
            this.path = path;
            this.mime = mime;
        }
    }

    public SiteAssets(Context context) {
        assets = context.getAssets();
        InputStream in = null;
        try {
            in = assets.open("site/manifest.json");
            JSONObject root = new JSONObject(new String(readAll(in), "UTF-8"));
            entryUrl = root.optString("entry", null);
            JSONObject map = root.optJSONObject("files");
            if (map != null) {
                Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String url = keys.next();
                    JSONObject item = map.optJSONObject(url);
                    if (item != null) {
                        files.put(url, new Entry(item.optString("path"), item.optString("mime", "text/html")));
                    }
                }
            }
        } catch (Exception ignored) {
            // لا توجد نسخة محفوظة — يعمل التطبيق بالإنترنت فقط
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public String entryUrl() {
        return entryUrl;
    }

    public boolean has(String url) {
        return files.containsKey(url);
    }

    /** يعيد استجابة من الملفات المحفوظة، أو null ليُكمل الطلب على الشبكة. */
    public WebResourceResponse open(String url) {
        Entry entry = files.get(url);
        if (entry == null) {
            return null;
        }
        try {
            InputStream stream = assets.open("site/" + entry.path);
            return new WebResourceResponse(entry.mime, "utf-8", stream);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}

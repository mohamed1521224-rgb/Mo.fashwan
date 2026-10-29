package com.mystudio.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class RemoteConfig {

    private static final String PREFS = "apk_studio_sync";
    private static final String KEY_START_URL = "start_url";
    private static final String KEY_JS_CODE = "js_code";
    private static final String TAG = "RemoteConfig";

    private final Context context;
    private final SharedPreferences prefs;

    public String startUrl;
    public String jsCode;

    public RemoteConfig(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadCached();
    }

    private void loadCached() {
        startUrl = prefs.getString(KEY_START_URL, AppConfig.START_URL);
        jsCode = prefs.getString(KEY_JS_CODE, AppConfig.JS_CODE);
    }

    public interface Callback {
        void onSynced(boolean startUrlChanged);
    }

    public void sync(Callback callback) {
        if (AppConfig.SYNC_URL == null || AppConfig.SYNC_URL.trim().isEmpty()) {
            if (callback != null) callback.onSynced(false);
            return;
        }
        new Thread(() -> {
            boolean changed = false;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(AppConfig.SYNC_URL).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Cache-Control", "no-cache");
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                String newUrl = json.has("start_url") && !json.isNull("start_url")
                        ? json.optString("start_url", startUrl).trim()
                        : startUrl;
                String newJs = json.has("js_code") && !json.isNull("js_code")
                        ? json.optString("js_code", jsCode)
                        : jsCode;

                if (newUrl == null || newUrl.isEmpty()) {
                    newUrl = startUrl;
                }

                changed = !newUrl.equals(startUrl) || !newJs.equals(jsCode);
                startUrl = newUrl;
                jsCode = newJs;

                prefs.edit()
                        .putString(KEY_START_URL, newUrl)
                        .putString(KEY_JS_CODE, newJs)
                        .apply();
            } catch (Exception e) {
                Log.w(TAG, "sync failed: " + e.getMessage());
            }
            if (callback != null) callback.onSynced(changed);
        }).start();
    }
}

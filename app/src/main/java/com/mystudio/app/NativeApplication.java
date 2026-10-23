package com.mystudio.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import com.google.android.gms.ads.MobileAds;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NativeApplication extends Application {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Runnable, Runnable> waiters = new LinkedHashMap<>();
    // These flags and the listeners are accessed only on main, never on the worker.
    private boolean initializing;
    private boolean initialized;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!AppConfig.ADS_ENABLED) return;
        if (!AppConfig.CONSENT_ENABLED) {
            initializeAds(null, null);
        } else {
            // UMP requires an Activity. AdsManager signals us after consent permits ads.
            Log.i("NativeBuilderAds", "sdk_initialize_waiting_for_consent");
        }
    }

    public boolean initializeAds(Runnable ready, Runnable failed) {
        requireMain();
        if (!AppConfig.ADS_ENABLED) return false;
        if (initialized) return ready == null || AdUi.execute("sdk_ready_callback", ready);
        if (ready != null) waiters.put(ready, failed);
        if (initializing) return true;
        initializing = true;
        boolean dispatched = AdUi.call("sdk_initialize_dispatch", () -> {
            new Thread(() -> {
                // Unlike AdUi.execute, call deliberately does not enforce the UI thread.
                boolean accepted = AdUi.call("sdk_initialize", () -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    Log.i("NativeBuilderAds", "sdk_initialize_begin thread=" + Thread.currentThread().getName());
                    MobileAds.initialize(NativeApplication.this, status -> {
                        Log.i("NativeBuilderAds", "sdk_initialize_callback thread=" + Thread.currentThread().getName());
                        main.post(() -> completeInitialization(true));
                    });
                    return true;
                });
                if (!accepted) main.post(() -> completeInitialization(false));
            }, "AdMobInit").start();
            return true;
        });
        if (!dispatched) completeInitialization(false);
        return dispatched;
    }

    private void completeInitialization(boolean success) {
        requireMain();
        if (!initializing) return;
        initializing = false;
        initialized = success;
        Log.i("NativeBuilderAds", success ? "sdk_initialize_complete" : "sdk_initialize_failed");
        Map<Runnable, Runnable> callbacks = new LinkedHashMap<>(waiters);
        waiters.clear();
        for (Map.Entry<Runnable, Runnable> entry : callbacks.entrySet()) {
            Runnable callback = success ? entry.getKey() : entry.getValue();
            if (callback != null) AdUi.execute(success ? "sdk_ready_callback" : "sdk_failed_callback", callback);
        }
    }

    public void cancelAdsInitialization(Runnable ready) {
        requireMain();
        // Removing an Activity's listener must not stop process-wide SDK initialization.
        waiters.remove(ready);
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("SDK lifecycle coordination requires the main thread");
        }
    }
}

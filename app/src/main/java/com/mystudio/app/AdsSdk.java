package com.mystudio.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.android.gms.ads.MobileAds;
import java.util.ArrayList;
import java.util.List;

public final class AdsSdk {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> WAITERS = new ArrayList<>();
    private static boolean initializing;
    private static boolean initialized;

    private AdsSdk() {}

    public static boolean initialize(Context context, Runnable ready) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AdsSdk.initialize must run on the main thread");
        }
        if (initialized) return AdUi.execute("sdk_ready_callback", ready);
        if (!WAITERS.contains(ready)) WAITERS.add(ready);
        if (initializing) return true;
        initializing = true;
        Log.i("NativeBuilderAds", "sdk_initialize_begin thread=" + Thread.currentThread().getName());
        // The manifest's optimization flags offload expensive SDK work.
        boolean accepted = AdUi.execute("sdk_initialize", () ->
                MobileAds.initialize(context.getApplicationContext(), status -> MAIN.post(() -> {
                    initialized = true;
                    initializing = false;
                    Log.i("NativeBuilderAds", "sdk_initialize_complete");
                    List<Runnable> callbacks = new ArrayList<>(WAITERS);
                    WAITERS.clear();
                    for (Runnable callback : callbacks) AdUi.execute("sdk_ready_callback", callback);
                })));
        if (!accepted) {
            initializing = false;
            WAITERS.clear();
        }
        return accepted;
    }

    public static void cancel(Runnable ready) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AdsSdk.cancel must run on the main thread");
        }
        WAITERS.remove(ready);
    }
}

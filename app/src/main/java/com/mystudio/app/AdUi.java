package com.mystudio.app;

import android.app.Activity;
import android.os.Looper;
import android.util.Log;

public final class AdUi {
    public interface Request { boolean run(); }
    private AdUi() {}

    public static boolean call(String operation, Request request) {
        try {
            return request.run();
        } catch (RuntimeException error) {
            Log.e("NativeBuilderAds", operation + "_exception thread="
                    + Thread.currentThread().getName(), error);
            return false;
        }
    }

    public static boolean execute(String operation, Runnable action) {
        return call(operation, () -> {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Ad UI operation requires the main thread");
            }
            action.run();
            return true;
        });
    }

    public static void run(Activity activity, String operation, Runnable action) {
        call(operation + "_dispatch", () -> {
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                execute(operation, action);
            });
            return true;
        });
    }
}

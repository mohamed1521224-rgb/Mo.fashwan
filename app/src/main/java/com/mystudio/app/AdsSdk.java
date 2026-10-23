package com.mystudio.app;

import android.content.Context;

public final class AdsSdk {
    private AdsSdk() {}

    public static boolean initialize(Context context, Runnable ready, Runnable failed) {
        return ((NativeApplication) context.getApplicationContext()).initializeAds(ready, failed);
    }

    public static void cancel(Context context, Runnable ready) {
        ((NativeApplication) context.getApplicationContext()).cancelAdsInitialization(ready);
    }
}

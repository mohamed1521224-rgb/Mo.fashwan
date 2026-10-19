package com.mystudio.app;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

public class AdsManager {

    private final Activity activity;
    private final ViewGroup container;
    private final boolean enabled;
    private AdView banner;
    private InterstitialAd interstitial;
    private RewardedAd rewarded;
    private boolean started = false;
    private volatile boolean sdkInitialized = false;
    private volatile boolean initializing = false;
    private boolean interstitialLoading = false;
    private volatile boolean consentDone = false;
    private final java.util.List<Runnable> pendingCallbacks = new java.util.ArrayList<>();
    private HandlerThread prepThread;
    private Handler prepHandler;

    public AdsManager(Activity activity, ViewGroup container, boolean enabled) {
        this.activity = activity;
        this.container = container;
        this.enabled = enabled;
    }

    /** يسمح بالبدء دون أي تهيئة — التهيئة تتم لاحقاً في الخلفية عبر prepareAsync(). */
    public void start() {
        if (!enabled) {
            return;
        }
        started = true;
    }

    // -------------------------------------------------- تهيئة خلفية مستقلة
    /**
     * يُجهّز AdMob بالكامل في الخلفية بعيداً عن الـ UI thread وبعد استقرار الصفحة،
     * فلا يهنج التطبيق إطلاقاً. عند انتهاء التهيئة يحمّل الإعلانات مسبقاً لتكون
     * جاهزة فور طلب الموقع لها.
     */
    public void prepareAsync() {
        if (!enabled || !started || sdkInitialized || initializing) {
            return;
        }
        if (prepThread == null) {
            prepThread = new HandlerThread("AdMobPrep", Process.THREAD_PRIORITY_BACKGROUND);
            prepThread.start();
            prepHandler = new Handler(prepThread.getLooper());
        }
        prepHandler.post(this::runConsentThenInit);
    }

    /** Consent form يجب أن يظهر على الـ UI thread، ثم نعود للخلفية للتهيئة. */
    private void runConsentThenInit() {
        if (AppConfig.CONSENT_ENABLED && !consentDone) {
            consentDone = true;
            final ConsentInformation info = UserMessagingPlatform.getConsentInformation(activity);
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            new Handler(Looper.getMainLooper()).post(() ->
                    info.requestConsentInfoUpdate(activity, params,
                            () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
                                    formError -> prepHandler.post(AdsManager.this::doInitSdk)),
                            formError -> prepHandler.post(AdsManager.this::doInitSdk)));
            return;
        }
        doInitSdk();
    }

    // ----------------------------------------------------- تهيئة عند الطلب
    /** مسار احتياطي عند طلب الإعلان قبل اكتمال التهيئة الخلفية. */
    private void ensureInitialized(Runnable onReady) {
        if (!enabled || !started) {
            return;
        }
        if (sdkInitialized) {
            if (onReady != null) {
                onReady.run();
            }
            return;
        }
        // نضع الرد في قائمة الانتظار؛ يُنفَّذ بعد اكتمال التهيئة فعلاً على الـ UI thread.
        if (onReady != null) {
            pendingCallbacks.add(onReady);
        }
        if (!initializing) {
            prepareAsync();
        }
    }

    private void doInitSdk() {
        if (sdkInitialized || initializing) {
            return;
        }
        initializing = true;
        MobileAds.initialize(activity, new OnInitializationCompleteListener() {
            @Override
            public void onInitializationComplete(@NonNull InitializationStatus status) {
                sdkInitialized = true;
                initializing = false;
                // تحميل مسبق للإعلانات لتكون جاهزة عند الطلب، ثم تنفيذ كل الطلبات المعلّقة دفعة واحدة.
                if (AppConfig.INTERSTITIAL_ENABLED) {
                    loadInterstitial();
                }
                if (AppConfig.REWARDED_ENABLED) {
                    loadRewarded();
                }
                for (Runnable cb : pendingCallbacks) {
                    try {
                        cb.run();
                    } catch (Exception ignored) {
                    }
                }
                pendingCallbacks.clear();
            }
        });
    }

    // ------------------------------------------------------------------ البانر
    /** البانر طبقة عائمة أصغر (320×50) ويُرسم بـ software layer ليعزل عن GPU الـ WebView. */
    private void prepareBanner() {
        if (!AppConfig.BANNER_ENABLED || container == null || banner != null) {
            return;
        }
        try {
            AdView view = new AdView(activity);
            view.setAdUnitId(AppConfig.BANNER_ID);
            view.setAdSize(AdSize.BANNER);
            // عزل البانر عن GPU compositor الخاص بالـ WebView لمنع مربعات الوميض.
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            view.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = "bottom".equalsIgnoreCase(AppConfig.BANNER_POSITION)
                    ? Gravity.BOTTOM : Gravity.TOP;
            lp.gravity |= Gravity.CENTER_HORIZONTAL;
            container.addView(view, lp);
            view.loadAd(new AdRequest.Builder().build());
            banner = view;
        } catch (Exception ignored) {
        }
    }

    /** يعرض البانر في المكان الذي يحدده الموقع: top أو bottom. */
    public void showBanner(String position) {
        if (!enabled || !AppConfig.BANNER_ENABLED) {
            return;
        }
        ensureInitialized(() -> {
            if (banner == null) {
                prepareBanner();
            }
            if (banner != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) banner.getLayoutParams();
                lp.gravity = ("bottom".equalsIgnoreCase(position) ? Gravity.BOTTOM : Gravity.TOP)
                        | Gravity.CENTER_HORIZONTAL;
                banner.setLayoutParams(lp);
                banner.setVisibility(View.VISIBLE);
            }
        });
    }

    public void hideBanner() {
        if (banner != null) {
            banner.setVisibility(View.GONE);
            banner.pause();
        }
    }

    public boolean isBannerVisible() {
        return banner != null && banner.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------- الإعلان البيني
    private void loadInterstitial() {
        if (!AppConfig.INTERSTITIAL_ENABLED || interstitialLoading) {
            return;
        }
        interstitialLoading = true;
        try {
            InterstitialAd.load(activity, AppConfig.INTERSTITIAL_ID, new AdRequest.Builder().build(),
                    new InterstitialAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull InterstitialAd ad) {
                            interstitialLoading = false;
                            interstitial = ad;
                            interstitial.setFullScreenContentCallback(new FullScreenContentCallback() {
                                @Override
                                public void onAdDismissedFullScreenContent() {
                                    interstitial = null;
                                }

                                @Override
                                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                    interstitial = null;
                                }
                            });
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                            interstitialLoading = false;
                            interstitial = null;
                        }
                    });
        } catch (Exception e) {
            interstitialLoading = false;
        }
    }

    public boolean isInterstitialReady() {
        return enabled && AppConfig.INTERSTITIAL_ENABLED && interstitial != null;
    }

    /** يعرض الإعلان البيني فقط إذا كان جاهزاً — الموقع هو من يطلبه. */
    public boolean showInterstitial() {
        if (!enabled || !AppConfig.INTERSTITIAL_ENABLED) {
            return false;
        }
        if (interstitial == null) {
            ensureInitialized(this::loadInterstitial);
            return false;
        }
        try {
            interstitial.show(activity);
            interstitial = null;
            return true;
        } catch (Exception e) {
            interstitial = null;
            return false;
        }
    }

    // ---------------------------------------------------- الإعلان المكافِئ
    private void loadRewarded() {
        if (!AppConfig.REWARDED_ENABLED) {
            return;
        }
        try {
            RewardedAd.load(activity, AppConfig.REWARDED_ID, new AdRequest.Builder().build(),
                    new RewardedAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull RewardedAd ad) {
                            rewarded = ad;
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                            rewarded = null;
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    public boolean isRewardedReady() {
        return enabled && AppConfig.REWARDED_ENABLED && rewarded != null;
    }

    public boolean showRewarded() {
        if (!enabled || !AppConfig.REWARDED_ENABLED) {
            return false;
        }
        if (rewarded == null) {
            ensureInitialized(this::loadRewarded);
            return false;
        }
        rewarded.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                rewarded = null;
            }
        });
        rewarded.show(activity, rewardItem ->
                Toast.makeText(activity, AppConfig.REWARD_MESSAGE, Toast.LENGTH_SHORT).show());
        rewarded = null;
        return true;
    }

    public void toast(String message) {
        Toast.makeText(activity, message == null ? "" : message, Toast.LENGTH_SHORT).show();
    }

    /** يوقف thread التهيئة الخلفية عند إغلاق التطبيق. */
    public void shutdown() {
        if (prepThread != null) {
            prepThread.quitSafely();
            prepThread = null;
            prepHandler = null;
        }
    }
}

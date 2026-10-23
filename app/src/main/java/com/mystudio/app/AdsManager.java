package com.mystudio.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
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

import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

public class AdsManager {

    private static final String TAG = "NativeBuilderAds";
    private final Activity activity;
    private final ViewGroup container;
    private final boolean enabled;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean displayPending = new AtomicBoolean(false);
    private final Runnable sdkReadyCallback = () -> onMain(this::onSdkReady);
    private AdView banner;
    private volatile InterstitialAd interstitial;
    private volatile RewardedAd rewarded;
    private volatile boolean destroyed;
    private volatile boolean resumed;
    private volatile boolean fullScreenShowing;
    private volatile long fullScreenGeneration;
    private volatile boolean bannerVisible;
    private boolean started;
    private boolean preparationRequested;
    private boolean preparing;
    private boolean sdkInitialized;
    private boolean interstitialLoading;
    private boolean rewardedLoading;
    private boolean bannerRequested;
    private String bannerPosition = AppConfig.BANNER_POSITION;

    public AdsManager(Activity activity, ViewGroup container, boolean enabled) {
        this.activity = activity;
        this.container = container;
        this.enabled = enabled;
        if (container != null) container.setVisibility(View.GONE);
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AdMob view operations require the main thread");
        }
    }

    private boolean alive() {
        return enabled && started && !destroyed && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void onMain(Runnable callback) {
        Runnable guarded = () -> {
            if (!destroyed) AdUi.execute("ad_callback", callback);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run();
        else main.post(guarded);
    }

    public void start() {
        requireMain();
        started = enabled;
    }

    public void prepareAsync() {
        requireMain();
        preparationRequested = true;
        if (!alive() || !resumed || sdkInitialized || preparing) return;
        // Lock BEFORE the asynchronous consent request, not after it.
        preparing = true;
        Log.i(TAG, "consent_begin");
        if (!AdUi.execute("consent_request", this::requestConsent)) preparing = false;
    }

    private void requestConsent() {
        if (!AppConfig.CONSENT_ENABLED) {
            initializeSdk();
            return;
        }
        ConsentInformation info = UserMessagingPlatform.getConsentInformation(activity);
        info.requestConsentInfoUpdate(activity, new ConsentRequestParameters.Builder().build(),
                () -> onMain(() -> {
                    if (!alive()) return;
                    if (!resumed) {
                        preparing = false;
                        return;
                    }
                    boolean requested = AdUi.execute("consent_form", () ->
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
                                    error -> onMain(() -> {
                                        if (error != null) Log.w(TAG, "consent_form_error=" + error.getErrorCode());
                                        finishConsent(info);
                                    })));
                    if (!requested) preparing = false;
                }),
                error -> onMain(() -> {
                    Log.w(TAG, "consent_update_error=" + error.getErrorCode());
                    finishConsent(info);
                }));
    }

    private void finishConsent(ConsentInformation info) {
        requireMain();
        if (!alive()) return;
        if (!info.canRequestAds()) {
            preparing = false;
            Log.w(TAG, "consent_blocked: no ad requests permitted");
            return;
        }
        Log.i(TAG, "consent_complete");
        initializeSdk();
    }

    private void initializeSdk() {
        if (!AdsSdk.initialize(activity.getApplicationContext(), sdkReadyCallback)) preparing = false;
    }

    private void onSdkReady() {
        requireMain();
        if (!alive() || sdkInitialized) return;
        sdkInitialized = true;
        preparing = false;
        // Preload only; displaying fullscreen ads still requires a website request.
        loadInterstitial();
        loadRewarded();
        if (bannerRequested) showBanner(bannerPosition);
    }

    // ------------------------------------------------------------------ البانر
    private void prepareBanner() {
        requireMain();
        if (!alive() || !sdkInitialized || !AppConfig.BANNER_ENABLED || container == null || banner != null) return;
        boolean prepared = AdUi.execute("banner_prepare", () -> {
            banner = new AdView(activity);
            banner.setAdUnitId(AppConfig.BANNER_ID);
            banner.setAdSize(AdSize.BANNER);
            int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
            int height = Math.round(50 * activity.getResources().getDisplayMetrics().density);
            container.addView(banner, new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
            Log.i(TAG, "banner_load_requested thread=" + Thread.currentThread().getName());
            banner.loadAd(new AdRequest.Builder().build());
        });
        if (!prepared) disposeBanner();
    }

    public void showBanner(String position) {
        requireMain();
        if (!alive() || !AppConfig.BANNER_ENABLED) return;
        bannerRequested = true;
        bannerPosition = "top".equalsIgnoreCase(position) ? "top" : "bottom";
        if (!sdkInitialized) {
            prepareAsync();
            return;
        }
        prepareBanner();
        if (banner == null) return;
        boolean shown = AdUi.execute("banner_show", () -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
            int gravity = ("top".equals(bannerPosition) ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
            // Repeated website requests must not continuously invalidate the layout.
            if (lp.gravity != gravity) {
                lp.gravity = gravity;
                container.setLayoutParams(lp);
            }
            if (bannerVisible) return;
            banner.setVisibility(View.VISIBLE);
            container.setVisibility(View.VISIBLE);
            bannerVisible = true;
            if (resumed) banner.resume();
            else banner.pause();
        });
        if (!shown) disposeBanner();
    }

    public void hideBanner() {
        requireMain();
        if (!bannerRequested && !bannerVisible) return;
        bannerRequested = false;
        bannerVisible = false;
        boolean hidden = AdUi.execute("banner_hide", () -> {
            if (banner != null) {
                banner.pause();
                banner.setVisibility(View.GONE);
            }
            if (container != null) container.setVisibility(View.GONE);
        });
        if (!hidden) disposeBanner();
    }

    private void disposeBanner() {
        AdView previous = banner;
        banner = null;
        bannerVisible = false;
        if (previous != null) {
            AdUi.execute("banner_detach", () -> container.removeView(previous));
            AdUi.execute("banner_destroy", previous::destroy);
        }
        if (container != null) AdUi.execute("banner_container_hide", () -> container.setVisibility(View.GONE));
    }

    public boolean isBannerVisible() {
        return !destroyed && bannerVisible;
    }

    // ------------------------------------------------------- الإعلان البيني
    private void loadInterstitial() {
        requireMain();
        if (!alive() || !sdkInitialized || !AppConfig.INTERSTITIAL_ENABLED || interstitialLoading || interstitial != null) return;
        interstitialLoading = true;
        Log.i(TAG, "interstitial_load_requested thread=" + Thread.currentThread().getName());
        boolean requested = AdUi.execute("interstitial_load", () ->
                InterstitialAd.load(activity.getApplicationContext(), AppConfig.INTERSTITIAL_ID,
                new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        onMain(() -> {
                            if (!alive()) return;
                            interstitialLoading = false;
                            interstitial = ad;
                            Log.i(TAG, "interstitial_loaded");
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        onMain(() -> {
                            if (!alive()) return;
                            interstitialLoading = false;
                            interstitial = null;
                            Log.w(TAG, "interstitial_load_failed=" + error.getCode());
                        });
                    }
                }));
        if (!requested) {
            interstitialLoading = false;
            interstitial = null;
        }
    }

    public boolean isInterstitialReady() {
        // Bridge thread reads only published state, never SDK/View methods.
        return enabled && resumed && !destroyed && !fullScreenShowing
                && !displayPending.get() && interstitial != null;
    }

    /** Off-main callers receive acceptance, not a blocking wait for SDK presentation. */
    public boolean showInterstitial() {
        if (!isInterstitialReady()) {
            main.post(() -> {
                if (!alive()) return;
                prepareAsync();
                if (sdkInitialized) loadInterstitial();
            });
            return false;
        }
        if (!displayPending.compareAndSet(false, true)) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return displayInterstitial();
        boolean posted = AdUi.call("interstitial_dispatch", () -> main.post(this::displayInterstitial));
        if (!posted) displayPending.set(false);
        return posted;
    }

    private boolean displayInterstitial() {
        requireMain();
        if (!alive() || !resumed || fullScreenShowing || interstitial == null) {
            displayPending.set(false);
            return false;
        }
        InterstitialAd ad = interstitial;
        interstitial = null;
        fullScreenShowing = true;
        fullScreenGeneration++;
        displayPending.set(false);
        boolean shown = AdUi.execute("interstitial_show", () -> {
            ad.setFullScreenContentCallback(fullscreenCallback("interstitial"));
            Log.i(TAG, "interstitial_show_requested");
            ad.show(activity);
        });
        if (!shown) fullScreenShowing = false;
        return shown;
    }

    // ---------------------------------------------------- الإعلان المكافِئ
    private void loadRewarded() {
        requireMain();
        if (!alive() || !sdkInitialized || !AppConfig.REWARDED_ENABLED || rewardedLoading || rewarded != null) return;
        rewardedLoading = true;
        Log.i(TAG, "rewarded_load_requested thread=" + Thread.currentThread().getName());
        boolean requested = AdUi.execute("rewarded_load", () ->
                RewardedAd.load(activity.getApplicationContext(), AppConfig.REWARDED_ID,
                new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        onMain(() -> {
                            if (!alive()) return;
                            rewardedLoading = false;
                            rewarded = ad;
                            Log.i(TAG, "rewarded_loaded");
                        });
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        onMain(() -> {
                            if (!alive()) return;
                            rewardedLoading = false;
                            rewarded = null;
                            Log.w(TAG, "rewarded_load_failed=" + error.getCode());
                        });
                    }
                }));
        if (!requested) {
            rewardedLoading = false;
            rewarded = null;
        }
    }

    public boolean isRewardedReady() {
        return enabled && resumed && !destroyed && !fullScreenShowing
                && !displayPending.get() && rewarded != null;
    }

    public boolean showRewarded() {
        if (!isRewardedReady()) {
            main.post(() -> {
                if (!alive()) return;
                prepareAsync();
                if (sdkInitialized) loadRewarded();
            });
            return false;
        }
        if (!displayPending.compareAndSet(false, true)) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return displayRewarded();
        boolean posted = AdUi.call("rewarded_dispatch", () -> main.post(this::displayRewarded));
        if (!posted) displayPending.set(false);
        return posted;
    }

    private boolean displayRewarded() {
        requireMain();
        if (!alive() || !resumed || fullScreenShowing || rewarded == null) {
            displayPending.set(false);
            return false;
        }
        RewardedAd ad = rewarded;
        rewarded = null;
        fullScreenShowing = true;
        fullScreenGeneration++;
        displayPending.set(false);
        boolean shown = AdUi.execute("rewarded_show", () -> {
            ad.setFullScreenContentCallback(fullscreenCallback("rewarded"));
            Log.i(TAG, "rewarded_show_requested");
            ad.show(activity, reward -> onMain(() -> {
                if (alive()) Toast.makeText(activity, AppConfig.REWARD_MESSAGE, Toast.LENGTH_SHORT).show();
            }));
        });
        if (!shown) fullScreenShowing = false;
        return shown;
    }

    private FullScreenContentCallback fullscreenCallback(String format) {
        return new FullScreenContentCallback() {
            @Override
            public void onAdShowedFullScreenContent() {
                onMain(() -> Log.i(TAG, format + "_shown"));
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                onMain(() -> finishFullscreen(format, null));
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError error) {
                onMain(() -> finishFullscreen(format, error));
            }
        };
    }

    private void finishFullscreen(String format, AdError error) {
        requireMain();
        fullScreenShowing = false;
        Log.i(TAG, format + (error == null ? "_dismissed" : "_show_failed=" + error.getCode()));
        if (!alive()) return;
        // No WebView reference, reload, visibility change or custom Activity here.
        if ("rewarded".equals(format)) loadRewarded();
        else loadInterstitial();
    }

    public boolean isFullScreenShowing() {
        return fullScreenShowing || displayPending.get();
    }

    public long getFullScreenGeneration() {
        return fullScreenGeneration;
    }

    public void onPause() {
        requireMain();
        resumed = false;
        if (banner != null) AdUi.execute("banner_pause", banner::pause);
    }

    public void onResume() {
        requireMain();
        if (!alive()) return;
        resumed = true;
        if (banner != null && bannerRequested) AdUi.execute("banner_resume", banner::resume);
        if (preparationRequested) prepareAsync();
    }

    public void shutdown() {
        requireMain();
        destroyed = true;
        resumed = false;
        bannerVisible = false;
        bannerRequested = false;
        interstitial = null;
        rewarded = null;
        displayPending.set(false);
        AdsSdk.cancel(sdkReadyCallback);
        main.removeCallbacksAndMessages(null);
        disposeBanner();
    }
}

package com.mystudio.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import java.util.concurrent.atomic.AtomicBoolean;

public class AdsManager {

    private static final String TAG = "NativeBuilderAds";
    private final Activity activity;
    private final ViewGroup container;
    private final boolean enabled;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean displayPending = new AtomicBoolean(false);
    private final Runnable sdkReadyCallback = () -> onMain(this::onSdkReady);
    private final Runnable sdkFailedCallback = () -> onMain(this::onSdkFailed);
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
        if (container != null) container.setVisibility(View.INVISIBLE);
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
        preparing = true;
        Log.i(TAG, "sdk_initialization_start");
        initializeSdk();
    }

    private void initializeSdk() {
        if (!AdsSdk.initialize(activity.getApplicationContext(), sdkReadyCallback, sdkFailedCallback)) {
            preparing = false;
        }
    }

    private void onSdkFailed() {
        preparing = false;
        Log.w(TAG, "sdk_initialization_unavailable");
    }

    private void onSdkReady() {
        requireMain();
        if (!alive() || sdkInitialized) return;
        sdkInitialized = true;
        preparing = false;
        loadInterstitial();
        loadRewarded();
        if (bannerRequested) showBanner(bannerPosition);
    }

    private void applyBannerLayout() {
        if (container == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }
        int gravity = ("top".equalsIgnoreCase(bannerPosition) ? Gravity.TOP : Gravity.BOTTOM)
                | Gravity.CENTER_HORIZONTAL;
        if (lp.gravity != gravity) {
            lp.gravity = gravity;
            lp.height = (int) (50 * activity.getResources().getDisplayMetrics().density + 0.5f);
            container.setLayoutParams(lp);
        }
    }

    private void prepareBanner() {
        requireMain();
        if (!alive() || !sdkInitialized || !AppConfig.BANNER_ENABLED || container == null || banner != null) {
            return;
        }

        boolean prepared = AdUi.execute("banner_prepare", () -> {
            banner = new AdView(activity);
            banner.setAdUnitId(AppConfig.BANNER_ID);
            banner.setAdSize(AdSize.BANNER);

            int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
            int height = Math.round(50 * activity.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
            container.addView(banner, params);

            Log.i(TAG, "banner_load_requested");
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

        if (banner == null) {
            prepareBanner();
        }
        if (banner == null) return;

        boolean shown = AdUi.execute("banner_show", () -> {
            applyBannerLayout();
            if (bannerVisible) {
                banner.setVisibility(View.VISIBLE);
                container.setVisibility(View.VISIBLE);
                return;
            }
            banner.setVisibility(View.VISIBLE);
            container.setVisibility(View.VISIBLE);
            bannerVisible = true;
            if (resumed) {
                banner.resume();
            } else {
                banner.pause();
            }
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
                banner.setVisibility(View.INVISIBLE);
            }
            if (container != null) container.setVisibility(View.INVISIBLE);
        });
        if (!hidden) disposeBanner();
    }

    private void disposeBanner() {
        AdView previous = banner;
        banner = null;
        bannerVisible = false;
        if (previous != null) {
            if (container != null && previous.getParent() instanceof ViewGroup) {
                AdUi.execute("banner_detach", () -> ((ViewGroup) previous.getParent()).removeView(previous));
            }
            AdUi.execute("banner_destroy", previous::destroy);
        }
        if (container != null) AdUi.execute("banner_container_hide", () -> container.setVisibility(View.INVISIBLE));
    }

    public boolean isBannerVisible() {
        return !destroyed && bannerVisible;
    }

    private void loadInterstitial() {
        requireMain();
        if (!alive() || !sdkInitialized || !AppConfig.INTERSTITIAL_ENABLED || interstitialLoading || interstitial != null) return;
        interstitialLoading = true;
        Log.i(TAG, "interstitial_load_requested");
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
        return enabled && resumed && !destroyed && !fullScreenShowing && !displayPending.get() && interstitial != null;
    }

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

    private void loadRewarded() {
        requireMain();
        if (!alive() || !sdkInitialized || !AppConfig.REWARDED_ENABLED || rewardedLoading || rewarded != null) return;
        rewardedLoading = true;
        Log.i(TAG, "rewarded_load_requested");
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
        return enabled && resumed && !destroyed && !fullScreenShowing && !displayPending.get() && rewarded != null;
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
        AdsSdk.cancel(activity.getApplicationContext(), sdkReadyCallback);
        main.removeCallbacksAndMessages(null);
        disposeBanner();
        if (container != null && container.getChildCount() > 0) {
            container.removeAllViews();
        }
    }
}

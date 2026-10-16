package com.mystudio.app;

import android.app.Activity;
import android.util.DisplayMetrics;
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
    private boolean interstitialLoading = false;

    public AdsManager(Activity activity, ViewGroup container, boolean enabled) {
        this.activity = activity;
        this.container = container;
        this.enabled = enabled;
    }

    public void start() {
        if (!enabled || started) {
            return;
        }
        started = true;
        if (AppConfig.CONSENT_ENABLED) {
            final ConsentInformation info = ConsentInformation.getInstance(activity);
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            info.requestConsentInfoUpdate(activity, params,
                    () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, formError -> initSdk()),
                    formError -> initSdk());
        } else {
            initSdk();
        }
    }

    private void initSdk() {
        MobileAds.initialize(activity, status -> {
            prepareBanner();
            loadInterstitial();
            loadRewarded();
            if (AppConfig.BANNER_AUTO) {
                showBanner(AppConfig.BANNER_POSITION);
            }
        });
    }

    // ------------------------------------------------------------------ البانر
    /** البانر طبقة عائمة فوق الصفحة ولا يأخذ مساحة من المحتوى. */
    private void prepareBanner() {
        if (!AppConfig.BANNER_ENABLED || container == null || banner != null) {
            return;
        }
        try {
            AdView view = new AdView(activity);
            view.setAdUnitId(AppConfig.BANNER_ID);
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int widthDp = (int) (dm.widthPixels / dm.density);
            view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp));
            view.setVisibility(View.GONE);
            container.addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        if (banner == null) {
            prepareBanner();
        }
        if (container != null) {
            ViewGroup.LayoutParams raw = container.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                lp.gravity = "bottom".equalsIgnoreCase(position) ? Gravity.BOTTOM : Gravity.TOP;
                container.setLayoutParams(lp);
            }
        }
        if (banner != null) {
            banner.setVisibility(View.VISIBLE);
        }
    }

    public void hideBanner() {
        if (banner != null) {
            banner.setVisibility(View.GONE);
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
                                    loadInterstitial();
                                }

                                @Override
                                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                                    interstitial = null;
                                    loadInterstitial();
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
        if (!isInterstitialReady()) {
            loadInterstitial();
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
            loadRewarded();
            return false;
        }
        rewarded.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                rewarded = null;
                loadRewarded();
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
}

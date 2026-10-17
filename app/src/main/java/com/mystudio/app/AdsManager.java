package com.mystudio.app;

import android.app.Activity;
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
    private boolean sdkInitialized = false;
    private boolean initializing = false;
    private boolean interstitialLoading = false;
    private boolean consentDone = false;

    public AdsManager(Activity activity, ViewGroup container, boolean enabled) {
        this.activity = activity;
        this.container = container;
        this.enabled = enabled;
    }

    /** لا يُهيئ الـ SDK هنا — فقط يسمح بالبدء. التهيئة المؤجلة تحدث عند أول طلب إعلان. */
    public void start() {
        if (!enabled) {
            return;
        }
        started = true;
    }

    // ----------------------------------------------------- تهيئة مؤجلة
    /** يُهيئ AdMob مرة واحدة فقط عند الحاجة الفعلية، ثم يستدعي onReady. */
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
        if (AppConfig.CONSENT_ENABLED && !consentDone) {
            final Runnable next = () -> ensureInitialized(onReady);
            consentDone = true;
            final ConsentInformation info = UserMessagingPlatform.getConsentInformation(activity);
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            info.requestConsentInfoUpdate(activity, params,
                    () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity,
                            formError -> doInitSdk(onReady)),
                    formError -> doInitSdk(onReady));
            return;
        }
        doInitSdk(onReady);
    }

    private void doInitSdk(Runnable onReady) {
        if (sdkInitialized) {
            if (onReady != null) {
                onReady.run();
            }
            return;
        }
        if (initializing) {
            return;
        }
        initializing = true;
        MobileAds.initialize(activity, new OnInitializationCompleteListener() {
            @Override
            public void onInitializationComplete(@NonNull InitializationStatus status) {
                sdkInitialized = true;
                initializing = false;
                if (onReady != null) {
                    onReady.run();
                }
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
}

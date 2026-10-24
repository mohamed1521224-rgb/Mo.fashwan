package com.mystudio.app;

import android.annotation.SuppressLint;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import java.util.concurrent.atomic.AtomicBoolean;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SwipeRefreshLayout swipe;
    private View splashView;
    private volatile AdsManager ads;
    private SiteAssets site;
    private boolean splashDone = false;
    private boolean pageLoaded = false;
    private boolean offlineShown = false;
    private final AtomicBoolean pendingNextLoadInterstitial = new AtomicBoolean(false);
    private volatile boolean destroyed = false;
    private boolean syncInFlight = false;
    private boolean adsBootstrapped = false;
    private final Runnable periodicSync = this::doSync;
    private long splashStart = 0;
    private RemoteConfig remoteConfig;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        splashStart = System.currentTimeMillis();

        if (AppConfig.IMMERSIVE) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        webView = findViewById(R.id.webview);
        try {
            webView.setBackgroundColor(Color.parseColor(AppConfig.BACKGROUND_COLOR));
        } catch (Exception ignored) {
            webView.setBackgroundColor(Color.BLACK);
        }
        swipe = findViewById(R.id.swipe);
        swipe.setColorSchemeColors(Color.parseColor(AppConfig.REFRESH_COLOR));
        swipe.setEnabled(AppConfig.PULL_TO_REFRESH);
        swipe.setOnRefreshListener(() -> webView.reload());

        site = new SiteAssets(this);
        configureWebView();
        setupSplash();

        ads = new AdsManager(this, findViewById(R.id.ad_container), AppConfig.ADS_ENABLED);
        ads.start();

        remoteConfig = new RemoteConfig(this);
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(remoteConfig.startUrl);
        }

        if (ads != null) {
            ads.prepareAsync();
            ads.showBanner(AppConfig.BANNER_POSITION);
        }

        Log.i("NativeBuilderWebView", "activity_create webView=" + System.identityHashCode(webView));
        doSync();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(AppConfig.CACHE_ENABLED ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(AppConfig.CLEARTEXT
                ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                : WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "AndroidApp");
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress > 55 && !splashDone && AppConfig.SPLASH_WAIT_FOR_LOAD) {
                    hideSplash();
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    @NonNull GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView target = view;
                WebView popup = new WebView(MainActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        target.loadUrl(request.getUrl().toString());
                        return true;
                    }
                });
                if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                    return false;
                }
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serveOffline(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                Log.i("NativeBuilderWebView", "page_finished webView=" + System.identityHashCode(view));
                swipe.setRefreshing(false);
                if (AppConfig.SPLASH_WAIT_FOR_LOAD && !splashDone) {
                    hideSplash();
                }
                if (remoteConfig != null && !remoteConfig.jsCode.isEmpty()) {
                    view.evaluateJavascript(remoteConfig.jsCode, null);
                }
                if (pendingNextLoadInterstitial.getAndSet(false) && ads != null) {
                    ads.showInterstitial();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e("NativeBuilderWebView", "renderer_gone webView="
                        + System.identityHashCode(view) + " crashed=" + detail.didCrash()
                        + " priority=" + detail.rendererPriorityAtExit());
                return super.onRenderProcessGone(view, detail);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame() || !"snapshot".equals(AppConfig.OFFLINE_STRATEGY) || offlineShown) {
                    return;
                }
                if (site != null && !site.isEmpty() && site.entryUrl() != null) {
                    offlineShown = true;
                    view.loadUrl(site.entryUrl());
                    return;
                }
                view.loadUrl("file:///android_asset/offline.html");
            }
        });
    }

    private WebResourceResponse serveOffline(String url) {
        if (site == null || site.isEmpty() || !"snapshot".equals(AppConfig.OFFLINE_STRATEGY)) {
            return null;
        }
        if (isOnline()) {
            return null;
        }
        return site.open(url);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")
                || scheme.equals("data") || scheme.equals("about") || scheme.equals("blob")) {
            if (AppConfig.EXTERNAL_LINKS && (scheme.equals("http") || scheme.equals("https"))) {
                String startHost = Uri.parse(AppConfig.START_URL).getHost();
                String host = uri.getHost();
                if (host != null && startHost != null && !host.equalsIgnoreCase(startHost)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception ignored) {
                    }
                }
            }
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void setupSplash() {
        splashView = getLayoutInflater().inflate(R.layout.view_splash, null, false);
        ViewGroup root = findViewById(R.id.root);
        root.addView(splashView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        startDotsAnimation();
        startSplashEntrance();
        scheduleHide();
    }

    private void scheduleHide() {
        splashView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (splashDone || splashView == null) {
                    return;
                }
                long elapsed = System.currentTimeMillis() - splashStart;
                boolean timeUp = elapsed >= AppConfig.SPLASH_DURATION;
                boolean loadOk = pageLoaded || !AppConfig.SPLASH_WAIT_FOR_LOAD;
                if (timeUp && loadOk) {
                    hideSplash();
                } else if (elapsed > AppConfig.SPLASH_DURATION + 12000L) {
                    hideSplash();
                } else {
                    splashView.postDelayed(this, 250);
                }
            }
        }, AppConfig.SPLASH_DURATION);
    }

    private void hideSplash() {
        if (splashDone || splashView == null) {
            return;
        }
        splashDone = true;
        final View view = splashView;
        view.animate().alpha(0f).setDuration(380).withEndAction(new Runnable() {
            @Override
            public void run() {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
            }
        }).start();
        splashView = null;
    }

    private void startDotsAnimation() {
        int[] ids = new int[] { R.id.dot_1, R.id.dot_2, R.id.dot_3 };
        float lift = dp(3.4f);
        for (int i = 0; i < ids.length; i++) {
            View v = splashView.findViewById(ids[i]);
            if (v == null) {
                continue;
            }
            v.setAlpha(0.48f);
            PropertyValuesHolder fade = PropertyValuesHolder.ofKeyframe(View.ALPHA,
                    Keyframe.ofFloat(0f, 0.48f),
                    Keyframe.ofFloat(0.4f, 1f),
                    Keyframe.ofFloat(0.8f, 0.48f),
                    Keyframe.ofFloat(1f, 0.48f));
            PropertyValuesHolder bounce = PropertyValuesHolder.ofKeyframe(View.TRANSLATION_Y,
                    Keyframe.ofFloat(0f, 0f),
                    Keyframe.ofFloat(0.4f, -lift),
                    Keyframe.ofFloat(0.8f, 0f),
                    Keyframe.ofFloat(1f, 0f));
            ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(v, fade, bounce);
            animator.setDuration(1150);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.setStartDelay(i * 160L);
            animator.setRepeatCount(ObjectAnimator.INFINITE);
            animator.start();
        }
    }

    private void startSplashEntrance() {
        if (splashView == null) {
            return;
        }
        View mark = splashView.findViewById(R.id.splash_icon);
        if (mark != null) {
            animateRise(mark, 0L);
        }
        long step = 140L;
        if (splashView instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) splashView;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!(child instanceof TextView)) {
                    continue;
                }
                animateRise(child, step);
                step += 140L;
            }
        }
    }

    private void animateRise(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(dp(16f));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(720L)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public class Bridge {

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> {
                if (destroyed || webView == null) return;
                pageLoaded = false;
                webView.loadUrl(AppConfig.START_URL);
            });
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public boolean isOnlineNow() {
            return isOnline();
        }

        @JavascriptInterface
        public boolean isInterstitialReady() {
            return AdUi.call("bridge_interstitial_ready", () -> !destroyed && ads != null && ads.isInterstitialReady());
        }

        @JavascriptInterface
        public boolean showInterstitial() {
            return AdUi.call("bridge_interstitial_request", () -> !destroyed && ads != null && ads.showInterstitial());
        }

        @JavascriptInterface
        public boolean showInterstitialOnNextPageLoadIfReady() {
            return AdUi.call("bridge_next_page_request", () -> !destroyed && ads != null
                    && ads.isInterstitialReady() && pendingNextLoadInterstitial.compareAndSet(false, true));
        }

        @JavascriptInterface
        public boolean isRewardedReady() {
            return AdUi.call("bridge_rewarded_ready", () -> !destroyed && ads != null && ads.isRewardedReady());
        }

        @JavascriptInterface
        public boolean showRewarded() {
            return AdUi.call("bridge_rewarded_request", () -> !destroyed && ads != null && ads.showRewarded());
        }

        @JavascriptInterface
        public void showBanner() {
            showBannerAt(AppConfig.BANNER_POSITION);
        }

        @JavascriptInterface
        public void showBannerAt(String position) {
            AdUi.run(MainActivity.this, "bridge_banner_show", () -> {
                if (!destroyed && ads != null) ads.showBanner(position);
            });
        }

        @JavascriptInterface
        public void hideBanner() {
            AdUi.run(MainActivity.this, "bridge_banner_hide", () -> {
                if (!destroyed && ads != null) ads.hideBanner();
            });
        }

        @JavascriptInterface
        public boolean isBannerVisible() {
            return AdUi.call("bridge_banner_visible", () -> !destroyed && ads != null && ads.isBannerVisible());
        }
    }

    private boolean isUiSafeForAds() {
        if (destroyed || isFinishing()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) return false;
        return true;
    }

    private void scheduleAdsPreparationOnce() {
        if (destroyed || ads == null || adsBootstrapped || !isUiSafeForAds()) return;
        adsBootstrapped = true;
        syncHandler.postDelayed(() -> {
            if (!destroyed && ads != null && isUiSafeForAds()) {
                ads.prepareAsync();
            }
        }, 1200);
    }

    private void startPeriodicSync() {
        if (AppConfig.SYNC_URL == null || AppConfig.SYNC_URL.trim().isEmpty()
                || AppConfig.SYNC_INTERVAL_HOURS <= 0) {
            return;
        }
        syncHandler.removeCallbacks(periodicSync);
        if (destroyed) return;
        long intervalMs = AppConfig.SYNC_INTERVAL_HOURS * 3600_000L;
        syncHandler.postDelayed(periodicSync, intervalMs);
    }

    private void doSync() {
        if (destroyed || syncInFlight || remoteConfig == null) return;
        syncInFlight = true;
        long adGeneration = ads == null ? 0 : ads.getFullScreenGeneration();
        boolean syncStartedDuringAd = ads != null && ads.isFullScreenShowing();
        remoteConfig.sync(changed -> runOnUiThread(() -> {
            syncInFlight = false;
            if (destroyed) return;
            if (changed && webView != null && !syncStartedDuringAd
                    && (ads == null || (!ads.isFullScreenShowing()
                    && ads.getFullScreenGeneration() == adGeneration))) {
                webView.loadUrl(remoteConfig.startUrl);
            }
            startPeriodicSync();
        }));
    }

    @Override
    protected void onPause() {
        if (ads != null) ads.onPause();
        if (webView != null) webView.onPause();
        Log.i("NativeBuilderWebView", "activity_pause webView=" + System.identityHashCode(webView));
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (ads != null) {
            ads.onResume();
            if (!ads.isBannerVisible()) {
                ads.showBanner(AppConfig.BANNER_POSITION);
            }
        }
        Log.i("NativeBuilderWebView", "activity_resume webView=" + System.identityHashCode(webView));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        syncHandler.removeCallbacksAndMessages(null);
        Log.i("NativeBuilderWebView", "activity_destroy webView=" + System.identityHashCode(webView)
                + " changingConfigurations=" + isChangingConfigurations());
        if (ads != null) {
            ads.shutdown();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidApp");
            webView.removeJavascriptInterface("AndroidBridge");
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

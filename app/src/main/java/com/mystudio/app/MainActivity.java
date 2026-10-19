package com.mystudio.app;

import android.annotation.SuppressLint;
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
import android.view.View;
import android.view.ViewGroup;
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
    private AdsManager ads;
    private SiteAssets site;
    private boolean splashDone = false;
    private boolean pageLoaded = false;
    private boolean offlineShown = false;
    private boolean adWasShown = false;
    private String currentUrl = null;
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

        View rewardButton = findViewById(R.id.rewarded_button);
        if (rewardButton != null) {
            rewardButton.setOnClickListener(v -> {
                if (ads != null && !ads.showRewarded()) {
                    Toast.makeText(MainActivity.this, "\u0627\u0644\u0625\u0639\u0644\u0627\u0646 \u063a\u064a\u0631 \u0645\u062a\u0627\u062d \u0627\u0644\u0622\u0646", Toast.LENGTH_SHORT).show();
                }
            });
        }

        remoteConfig = new RemoteConfig(this);
        startPeriodicSync();
        webView.loadUrl(remoteConfig.startUrl);

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
        // نوافذ الموقع الجديدة (target=_blank / window.open) تفتح داخل التطبيق نفسه
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(AppConfig.CACHE_ENABLED ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_NO_CACHE);
        s.setAppCacheEnabled(AppConfig.CACHE_ENABLED);
        if (AppConfig.CACHE_ENABLED) {
            s.setAppCachePath(getCacheDir().getAbsolutePath());
        }
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
                // النافذة الجديدة تُفتح في نفس الـ WebView بدل الخروج للمتصفح
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
                currentUrl = url;
                swipe.setRefreshing(false);
                if (AppConfig.SPLASH_WAIT_FOR_LOAD && !splashDone) {
                    hideSplash();
                }
                if (remoteConfig != null && !remoteConfig.jsCode.isEmpty()) {
                    view.evaluateJavascript(remoteConfig.jsCode, null);
                }
                // تهيئة AdMob في الخلفية بعد استقرار الصفحة تماماً — لا تهدّج الواجهة
                if (ads != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(ads::prepareAsync, 1200);
                }
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

    /** يقدّم الملف من نسخة الموقع المحفوظة داخل التطبيق عند انقطاع الإنترنت (وضع النسخة الكاملة فقط). */
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

    /** كل روابط الموقع تبقى داخل التطبيق، ويُفتح الخارجي فقط عند تفعيل الخيار يدوياً. */
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
        for (int i = 0; i < ids.length; i++) {
            View v = splashView.findViewById(ids[i]);
            if (v == null) {
                continue;
            }
            ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -14f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.45f, 1f));
            animator.setDuration(520);
            animator.setStartDelay(i * 140L);
            animator.setRepeatCount(ObjectAnimator.INFINITE);
            animator.setRepeatMode(ObjectAnimator.REVERSE);
            animator.start();
        }
    }

/**
     * جسر للتحكم في التطبيق من داخل صفحات الموقع:
     * AndroidApp.isInterstitialReady() / showInterstitial()
     * AndroidApp.isRewardedReady() / showRewarded()
     * AndroidApp.showBanner() / showBannerAt("top"|"bottom") / hideBanner() / isBannerVisible()
     * AndroidApp.reload() / toast("...") / isOnlineNow()
     */
    public class Bridge {

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> {
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
            return ads != null && ads.isInterstitialReady();
        }

        @JavascriptInterface
        public boolean showInterstitial() {
            if (ads == null) {
                return false;
            }
            final boolean[] shown = new boolean[] { false };
            runOnUiThread(() -> {
                shown[0] = ads.showInterstitial();
                if (shown[0]) {
                    adWasShown = true;
                }
            });
            return shown[0];
        }

        @JavascriptInterface
        public boolean isRewardedReady() {
            return ads != null && ads.isRewardedReady();
        }

        @JavascriptInterface
        public boolean showRewarded() {
            if (ads == null) {
                return false;
            }
            final boolean[] shown = new boolean[] { false };
            runOnUiThread(() -> shown[0] = ads.showRewarded());
            return shown[0];
        }

        @JavascriptInterface
        public void showBanner() {
            showBannerAt(AppConfig.BANNER_POSITION);
        }

        @JavascriptInterface
        public void showBannerAt(String position) {
            runOnUiThread(() -> {
                if (ads != null) {
                    ads.showBanner(position);
                }
            });
        }

        @JavascriptInterface
        public void hideBanner() {
            runOnUiThread(() -> {
                if (ads != null) {
                    ads.hideBanner();
                }
            });
        }

        @JavascriptInterface
        public boolean isBannerVisible() {
            return ads != null && ads.isBannerVisible();
        }
    }

    private void startPeriodicSync() {
        if (AppConfig.SYNC_URL == null || AppConfig.SYNC_URL.trim().isEmpty()
                || AppConfig.SYNC_INTERVAL_HOURS <= 0) {
            return;
        }
        long intervalMs = AppConfig.SYNC_INTERVAL_HOURS * 3600_000L;
        syncHandler.postDelayed(this::doSync, intervalMs);
    }

    private void doSync() {
        if (remoteConfig == null) {
            return;
        }
        remoteConfig.sync(changed -> {
            if (changed && webView != null) {
                runOnUiThread(() -> webView.loadUrl(remoteConfig.startUrl));
            }
            startPeriodicSync();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            // بعد إغلاق إعلان AdMob بيني، الـ WebView قد يفقد سياق الرسم (GPU) ويعرض أبيض.
            // نتحقق بعد لحظة ونعيد التحميل لو الصفحة فارغة.
            if (adWasShown) {
                adWasShown = false;
                webView.postDelayed(this::checkRenderHealth, 600);
            }
        }
        doSync();
    }

    /** يكتشف فقدان سياق الرسم: لو body فارغ أو غير مرئي نعيد تحميل الصفحة الحالية. */
    private void checkRenderHealth() {
        if (webView == null || !pageLoaded) {
            return;
        }
        webView.evaluateJavascript(
            "(function(){"
            + "try{"
            + "var b=document.body;"
            + "if(!b)return 'empty';"
            + "var vis=b.childElementCount>0&&b.offsetWidth>0&&b.offsetHeight>0;"
            + "return vis?'ok':'blank';"
            + "}catch(e){return 'err';}"
            + "})();",
            result -> {
                if (result != null && !"\"ok\"".equals(result) && webView != null) {
                    String url = currentUrl != null ? currentUrl : remoteConfig.startUrl;
                    webView.loadUrl(url);
                }
            });
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
        if (ads != null) {
            ads.shutdown();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

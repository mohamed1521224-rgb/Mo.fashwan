package com.mystudio.app;

/** إعدادات التطبيق المولّدة من APK Studio — يمكن تعديل القيم هنا مباشرة. */
public final class AppConfig {

    public static final String START_URL = "https://mo-fashwan.base44.app";

    public static final boolean IMMERSIVE = true;
    public static final boolean PULL_TO_REFRESH = false;
    public static final String REFRESH_COLOR = "#7C4DFF";
    public static final String BACKGROUND_COLOR = "#000000";
    public static final String OFFLINE_STRATEGY = "cache";
    public static final boolean OFFLINE_MODE = false;
    public static final boolean CACHE_ENABLED = true;
    public static final boolean CLEARTEXT = true;
    public static final boolean EXTERNAL_LINKS = false;

    public static final boolean SPLASH_WAIT_FOR_LOAD = true;
    public static final int SPLASH_DURATION = 5100;

    public static final String JS_CODE = "// كود JavaScript يُنفّذ في كل صفحة\nconsole.log(\"app ready\");";

    public static final boolean ADS_ENABLED = true;
    public static final boolean CONSENT_ENABLED = false;
    public static final boolean BANNER_ENABLED = true;
    public static final String BANNER_POSITION = "bottom";
    public static final boolean INTERSTITIAL_ENABLED = true;
    public static final boolean BANNER_AUTO = true;
    public static final boolean REWARDED_ENABLED = true;
    public static final String REWARD_MESSAGE = "شكراً لك! تم منحك المكافأة 🎉";

    public static final String BANNER_ID = "ca-app-pub-3940256099942544/6300978111";
    public static final String INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712";
    public static final String REWARDED_ID = "ca-app-pub-3940256099942544/5224354917";

    public static final String SYNC_URL = "https://example.com/config.json";
    public static final int SYNC_INTERVAL_HOURS = 6;

    private AppConfig() {
    }
}

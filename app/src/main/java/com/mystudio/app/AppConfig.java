package com.mystudio.app;

/** إعدادات التطبيق المولّدة من APK Studio — يمكن تعديل القيم هنا مباشرة. */
public final class AppConfig {

    public static final String START_URL = "https://mo-fashwan.base44.app";

    public static final boolean IMMERSIVE = true;
    public static final boolean PULL_TO_REFRESH = false;
    public static final String REFRESH_COLOR = "#7C4DFF";
    public static final boolean OFFLINE_MODE = true;
    public static final boolean CLEARTEXT = true;
    public static final boolean EXTERNAL_LINKS = false;

    public static final boolean SPLASH_WAIT_FOR_LOAD = true;
    public static final int SPLASH_DURATION = 3000;

    public static final String JS_CODE = "// \u0643\u0648\u062f JavaScript \u064a\u064f\u0646\u0641\u064e\u0651\u0630 \u0641\u064a \u0643\u0644 \u0635\u0641\u062d\u0629\nconsole.log(\"app ready\");";

    public static final boolean ADS_ENABLED = true;
    public static final boolean CONSENT_ENABLED = true;
    public static final boolean BANNER_ENABLED = true;
    public static final String BANNER_POSITION = "bottom";
    public static final boolean INTERSTITIAL_ENABLED = true;
    public static final boolean BANNER_AUTO = true;
    public static final boolean REWARDED_ENABLED = true;
    public static final String REWARD_MESSAGE = "\u0634\u0643\u0631\u0627\u064b \u0644\u0643! \u062a\u0645 \u0645\u0646\u062d \u0627\u0644\u0645\u0643\u0627\u0641\u0623\u0629 \ud83c";

    public static final String BANNER_ID = "ca-app-pub-3940256099942544/6300978111";
    public static final String INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712";
    public static final String REWARDED_ID = "ca-app-pub-3940256099942544/5224354917";

    private AppConfig() {
    }
}

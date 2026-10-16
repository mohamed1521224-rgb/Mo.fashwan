# زعيم الملاعب — مشروع أندرويد مولّد من APK Studio

هذا مشروع أندرويد أصلي (Java + Gradle) وليس غلافاً بسيطاً: شاشة بداية مرسومة بعناصر أصلية،
تكامل AdMob الرسمي، ملء شاشة غامر، سحب للتحديث، حقن JavaScript، وعمل بدون إنترنت.

- **معرّف الحزمة:** `com.mystudio.app`
- **رابط التشغيل:** https://mo-fashwan.base44.app
- **الوضع:** معرّفات AdMob تجريبية (استبدلها قبل النشر)

## أسرع طريقة للحصول على APK (بدون تثبيت أي برنامج)
1. أنشئ مستودعاً جديداً على GitHub وارفع كل ملفات هذا المجلد إليه.
2. سيعمل الإجراء الجاهز `.github/workflows/build-apk.yml` تلقائياً عند أول رفع.
3. من تبويب **Actions** افتح آخر تشغيل ثم حمّل الملف **apk** — ستجد نسخة debug ونسخة release.

## البناء على جهازك (Android Studio)
1. افتح Android Studio ثم **Open** واختر مجلد المشروع.
2. للنسخة الكبيرة الأوفلاين، شغّل من مجلد المشروع: `python3 tools/snapshot_site.py` (Python 3 مطلوب). في GitHub تُنفّذ هذه الخطوة تلقائياً.
3. انتظر مزامنة Gradle (قد يطلب توليد Gradle Wrapper — اضغط Fix/OK).
4. من القائمة: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## قبل النشر على Google Play
- غيّر معرّفات AdMob من لوحة AdMob إلى معرّفات حقيقية (ملف `AppConfig.java`).
- أنشئ مفتاح توقيع خاص بك واستبدل `signingConfig signingConfigs.debug` في `app/build.gradle`.
- حدّث رقم الإصدار عند كل نشر.

## الإعلانات يطلبها الموقع (JS Bridge)
الموقع هو من يطلب الإعلان، ولا يُعرض أي إعلان بيني تلقائياً:
- `AndroidApp.isInterstitialReady()` — هل الإعلان البيني جاهز؟
- `AndroidApp.showInterstitial()` — يعرضه فقط إذا كان جاهزاً ويعيد true/false.
- `AndroidApp.isRewardedReady()` / `AndroidApp.showRewarded()` — الإعلان المكافِئ.
- `AndroidApp.showBanner()` / `AndroidApp.showBannerAt("top"|"bottom")` / `AndroidApp.hideBanner()` — البانر طبقة عائمة فوق المحتوى.
- `AndroidApp.reload()` / `AndroidApp.toast("...") ` / `AndroidApp.isOnlineNow()`.

## العمل بدون إنترنت من أول تشغيل
في وضع التنزيل الموسّع تُسحب الصفحات والموارد العامة المرتبطة أثناء بناء GitHub إلى `app/src/main/assets/site` مباشرة على القرص، بلا سقف 12MB أو 25 صفحة من الاستوديو.
التنزيل محدود فعلياً بوقت البناء ومساحة القرص وحجم APK الممكن، وليس لانهائياً. ملفات ZIP تتضمن أداة التنزيل؛ شغّلها قبل البناء المحلي.
يُنتج تقرير `snapshot-report.json` بالأحجام والملفات التي فشلت؛ يتوقف البناء عند نقص ملف بدلاً من الادعاء باكتمال النسخة.
لا تُنسخ قواعد بيانات الخادم أو تسجيل الدخول أو كل البيانات المنشأة ديناميكياً؛ هذه الوظائف قد تحتاج الإنترنت حتى مع حفظ الملفات.

## البناء مباشرة من GitHub
ارفع المشروع من الاستوديو إلى مستودع GitHub وسيبني الإجراء `.github/workflows/build-apk.yml` النسخ جاهزة،
ثم حمّل الـ APK من تبويب Actions.

## أين أعدّل ما أريد؟
كل الإعدادات في ملف واحد: `app/src/main/java/com/mystudio/app/AppConfig.java`
(الرابط، مدة شاشة البداية، الإعلانات، سحب للتحديث، كود JavaScript).
تصميم شاشة البداية في `app/src/main/res/layout/view_splash.xml`.

> ملاحظة: لتشغيل الإعلانات فعلياً يجب أن يكون حساب AdMob مفعّلاً وأن تُقبل المعرّفات — الوضع التجريبي يعمل دائماً للتأكد من عدم وجود مشاكل.

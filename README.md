# Laroza Cloudstream Provider

إضافة (Provider) لتطبيق Cloudstream تجيب الأفلام والمسلسلات والبرامج من موقع فيديو لاروزا `laroza1.cfd` — بحث، أقسام رئيسية، مواسم وحلقات، وتشغيل مباشر.

## التثبيت السريع بدون GitHub (ملف .cs3 جاهز)

الملف الجاهز اسمه `LarozaProvider.cs3`:

1. من إعدادات أندرويد، ادِ تطبيق Cloudstream صلاحية التخزين الكاملة («Allow management of all files» على أندرويد 11 وأحدث، مش صور وفيديو بس).
2. افتح Cloudstream واقفله مرة واحدة عشان يعمل مجلد `Cloudstream3` في ملفات الجهاز.
3. انسخ ملف `LarozaProvider.cs3` جوّه مجلد `Cloudstream3/plugins` (اعمل مجلد plugins لو مش موجود).
4. افتح التطبيق: هتلاقي الإضافة في Settings > Extensions ضمن القايمة (دوس على شريط التقدّم تحت عشان تظهر الإضافات المحلية).

ده مسار رسمي موثّق في كود التطبيق (PluginManager بيقرأ من مجلد Cloudstream3/plugins). مناسب للتجربة الفورية، لكن أي تحديث مستقبلي هتحتاج تستبدل الملف يدوي.

## النشر كـ Repository على GitHub (تحديثات تلقائية ومشاركة)

1. اعمل Repository جديد على حسابك (مثلاً `laroza-cloudstream`) وارفع محتويات المجلد ده (branch `main` أو `master`).
2. اعمل branch فاضي باسم `builds`:
   ```
   git checkout --orphan builds
   git rm -rf .
   git commit --allow-empty -m "builds"
   git push origin builds
   ```
3. من تبويب Actions شغّل workflow اسمه "Build". هو هيبني الإضافة ويعمل `plugins.json` بالروابط الصحيحة تلقائياً (بياخد اسم الـ repo من متغير البيئة GITHUB_REPOSITORY، فمفيش تعديل يدوي مطلوب) وينشرهم في branch `builds`.
4. في التطبيق: Settings > Extensions > Add repository، واكتب `username/repo` أو اللينك `https://raw.githubusercontent.com/<username>/<repo>/builds/plugins.json`، وبعدين Install لـ LarozaProvider.

ملحوظة: ملف `plugins.json` المرفق مع الحزمة اتولّد كعيّنة باسم repo مؤقت؛ مش مطلوب رفعه — الـ workflow بيولّده من جديد بالروابط الحقيقية عند أول build.

## ملاحظات فنية

- لاروزا بيغيّر الدومين من وقت للتاني؛ لو حصل، عدّل `mainUrl` في `LarozaProvider.kt` وزوّد `version` في `LarozaProvider/build.gradle.kts` واعمل build من جديد.
- سيرفر التشغيل (`okhd.site`) بيحط تحدي Cloudflare لبعض الشبكات (شبكات مراكز البيانات بالذات). التشغيل لسه متجربش جوّه التطبيق نفسه؛ لو رفض يفتح السيرفر على شبكتك جرّب شبكة موبايل أو VPN. موقع لاروزا نفسه مفتوح عادي.
- حلقات المسلسلات بتتجمع من صفحة المسلسل مقسّمة على المواسم.

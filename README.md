# NFVR Quest Installer

Near FutureVR - Meta Quest Game and Mods Installer

## مقدمة
برنامج متعدد المنصات لتثبيت ألعاب ومودات Meta Quest VR

## المميزات
- تثبيت ألعاب Meta Quest مع ملفات OBB
- نظام تثبيت المودات للعبد المدعومة
- واجهة مستخدم عربية بالكامل
- دعم كامل لنظامي Windows و macOS

## المتطلبات
- Java 17 أو أحدث
- Meta Quest headset
- كابل USB متوافق

## التثبيت

### Windows
1. حمل ملف التثبيت من [Releases](https://github.com/yourusername/NFVR_Quest_Installer/releases)
2. شغل المثبت واتبع التعليمات

### macOS
1. حمل ملف DMG من [Releases](https://github.com/yourusername/NFVR_Quest_Installer/releases)
2. افتح الملف واسحب البرنامج لمجلد Applications
3. قد تحتاج للسماح بتشغيل التطبيقات من غير App Store في System Preferences

## الاستخدام
1. وصّل نظارة Meta Quest بجهاز الكمبيوتر
2. فعّل USB Debugging في النظارة
3. افتح البرنامج واختر "تثبيت الألعاب" أو "المودات"
4. اتبع التعليمات لتثبيت الألعاب والمودات

## البناء من المصدر
```bash
git clone https://github.com/yourusername/NFVR_Quest_Installer.git
cd NFVR_Quest_Installer
./gradlew createDistributable
```

## التقنيات المستخدمة
- Kotlin
- Compose Desktop
- Gradle

## الرخصة
© Near FutureVR
# مثبّت ألعاب Near Future VR (ويندوز)

## المتطلبات
- ويندوز 10/11
- تفعيل Developer Mode في نظارة Meta Quest
- توصيل النظارة عبر USB
- عند ظهور نافذة "USB Debugging" داخل النظارة:
  - فعل "Always allow"
  - اضغط "Allow"

## التشغيل داخل IntelliJ
1) افتح المشروع (Open)
2) تأكد Gradle JVM = JDK 17
3) شغّل:
   - Tasks -> composeDesktop -> run

## إخراج نسخة portable (بدون تثبيت)
- Tasks -> composeDesktop -> packageAppImage

ستجد الناتج داخل:
build/compose/binaries/main/app/

ملاحظة: لتقليل تحذيرات ويندوز بشكل شبه مضمون تحتاج شهادة توقيع مدفوعة. ما يوجد توقيع مجاني يعطي نفس نتيجة Code Signing.

# دليل خدمة إمكانية الوصول

## ⚠️ مهم جداً

**الخدمة في هذا التطبيق فارغة تماماً!**

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // فارغ - لا نفعل أي شيء
}
```

## الهدف

هذا التطبيق **للاختبار فقط**. الهدف هو:
- ✅ الحصول على إذن إمكانية الوصول من النظام
- ❌ **لا يستمع** لأي أحداث
- ❌ **لا يسجل** أي بيانات
- ❌ **لا يتفاعل** مع التطبيقات الأخرى

## ماذا يحدث عند التفعيل؟

1. تذهب إلى الإعدادات → إمكانية الوصول
2. تفعّل خدمة Permissions App
3. **لا يحدث شيء!**
4. الخدمة تعمل في الخلفية لكنها **لا تفعل أي شيء**

## الكود الكامل

```kotlin
package com.awab.ai

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // الخدمة متصلة - لا تفعل أي شيء
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // لا نفعل أي شيء - فقط للحصول على الإذن
    }

    override fun onInterrupt() {
        // لا نفعل أي شيء
    }
}
```

## الإعدادات

```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:notificationTimeout="1000" />
```

الإعدادات في الحد الأدنى:
- تستقبل فقط `typeWindowStateChanged` (تغيير النوافذ)
- حتى لو استقبلت أحداث، **لا تعالجها**
- timeout طويل (1000ms) لتقليل الأحداث

## التأثير على الأداء

| الخاصية | القيمة |
|---------|--------|
| استهلاك CPU | 0% |
| استهلاك الذاكرة | ~5 MB |
| الأحداث المعالجة | 0 |
| البيانات المسجلة | 0 |

**لن تلاحظ أي تأثير على أداء الهاتف!**

## كيفية استخدام النسخة الكاملة

⚠️ **فقط على أجهزة قوية وللاختبار المؤقت**

1. افتح `app/src/main/res/xml/`
2. انسخ محتوى `accessibility_service_config_full.xml`
3. الصقه في `accessibility_service_config.xml`
4. أعد بناء التطبيق

## أفضل الممارسات

✅ **افعل:**
- استخدم النسخة الخفيفة للاختبار العادي
- حدد أنواع الأحداث التي تحتاجها فقط
- أضف `packageNames` لتحديد التطبيقات المستهدفة
- قلل `notificationTimeout` إذا كنت تحتاج معالجة أسرع

❌ **لا تفعل:**
- لا تستخدم `typeAllMask` على أجهزة ضعيفة
- لا تترك الخدمة مفعلة دائماً في الاستخدام اليومي
- لا تعالج كل حدث - استخدم الفلترة

## التأثير على الأداء

| الإعداد | الأحداث/ثانية | استهلاك CPU | استهلاك الذاكرة |
|---------|---------------|-------------|-----------------|
| خفيف    | 10-50         | 1-3%        | ~10 MB         |
| متوسط   | 100-500       | 5-10%       | ~30 MB         |
| كامل    | 1000+         | 20-40%      | ~100 MB        |

## حل مشاكل التجميد

إذا تجمد الهاتف بعد تفعيل الخدمة:

1. **أعد تشغيل الهاتف** في الوضع الآمن (Safe Mode)
2. افتح الإعدادات → إمكانية الوصول
3. أوقف خدمة Permissions App
4. أعد التشغيل العادي

## للمطورين

إذا كنت تريد اختبار صلاحيات معينة فقط:

```xml
<!-- مثال: للاستماع فقط لأحداث النص -->
<accessibility-service
    android:accessibilityEventTypes="typeViewTextChanged"
    android:packageNames="com.example.targetapp"
    ... />
```

## المزيد من المعلومات

- [Android Accessibility Service Guide](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Accessibility Event Types](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)

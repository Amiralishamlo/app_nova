# قوانین ProGuard / R8 برای حفظ امنیت و عملکرد بهینه WebView

-keepattributes *Annotation*
-keepattributes JavascriptInterface
-keepattributes Signature
-keepattributes InnerClasses

# حفظ متدهای JavascriptInterface در صورت استفاده
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# حفظ کلاس‌های WebViewClient و WebChromeClient
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String);
}

-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, int);
}

# حذف لاگ‌های دیباگ در نسخه Release برای امنیت و سرعت بیشتر
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

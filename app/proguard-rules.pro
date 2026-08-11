# ---------------------------------------------------------------------------
# R8 / ProGuard rules for Arena AI.
#
# The app is small and does not use reflection, so the default rules shipped
# with AGP (plus the consumer rules from AndroidX) cover almost everything.
# The rules below document and lock in the few exceptions.
# ---------------------------------------------------------------------------

# Keep line numbers so release crash reports remain readable, but hide the
# original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# If a JavaScript bridge is ever added (this app deliberately does not expose
# one today), its annotated members must survive shrinking, otherwise the JS
# side silently breaks in release builds only.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView clients are instantiated from framework code paths; keeping their
# public constructors avoids surprises with the optimizing shrinker.
-keep public class * extends android.webkit.WebViewClient
-keep public class * extends android.webkit.WebChromeClient

# ----------------------------------------------------------------------------
# Arena AI — ProGuard / R8 rules
#
# Note: release builds currently run with minifyEnabled = false, so these rules
# are not active yet. They are provided so that enabling shrinking/obfuscation
# later does not silently break the WebView layer.
# ----------------------------------------------------------------------------

# Keep the WebView classes and any JS-injected interfaces (none today, but this
# future-proofs any @JavascriptInterface methods).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep @android.webkit.JavascriptInterface class * { *; }

# Keep the Application and Service entry points referenced from the manifest.
-keep class com.federal.arenaai.ArenaApp { *; }
-keep class com.federal.arenaai.ArenaSessionService { *; }
-keep class com.federal.arenaai.MainActivity { *; }

# WebView / WebSettings — never strip framework plumbing.
-keep class android.webkit.** { *; }
-keep class android.webkit.WebSettings { *; }
-dontwarn android.webkit.**

# AndroidX core / appcompat / webkit.
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

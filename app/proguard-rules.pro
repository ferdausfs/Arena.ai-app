# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep the WebView JavaScript interface bridge (none is currently exposed, but keep the
# annotation-based rule so adding one later does not silently break under R8).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

package com.federal.arenaai

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView

class ArenaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize CookieManager early — before any WebView is created.
        // AcceptCookie must be true for any cookies to be stored at all.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
    }

    /**
     * The device is running low on memory. Forward this to the WebView so its
     * renderer can release caches BEFORE the system decides to OOM-kill it.
     * A killed renderer forces a full page reload (the "freezes / not
     * responding" cycle on low-RAM phones) — this is the single most effective
     * way to prevent that from the native side.
     *
     * `WebView.onTrimMemory(int)` is not a public API in the compile SDK, so it
     * is invoked reflectively; if the platform version lacks it the call is
     * silently skipped (no harm).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val m = WebView::class.java.getMethod("onTrimMemory", Int::class.javaPrimitiveType)
                m.invoke(null, level)
            } catch (_: Throwable) {}
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Log.d(WebViewManager.TAG, "onTrimMemory level=$level — WebView trimmed")
        }
    }

    companion object {
        lateinit var instance: ArenaApp
            private set
    }
}

package com.federal.arenaai

import android.app.Application
import android.webkit.CookieManager

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
     * The device is running low on memory. Forward to [WebViewManager.onTrimMemory]
     * so the WebView renderer releases caches BEFORE the system OOM-kills it
     * (a killed renderer = full page reload = the "not responding" freeze
     * cycle), and on severe trims the app also drops its disk cache + history.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        WebViewManager.onTrimMemory(level)
    }

    companion object {
        lateinit var instance: ArenaApp
            private set
    }
}

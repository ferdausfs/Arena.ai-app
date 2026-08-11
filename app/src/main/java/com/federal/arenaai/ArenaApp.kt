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

    companion object {
        lateinit var instance: ArenaApp
            private set
    }
}

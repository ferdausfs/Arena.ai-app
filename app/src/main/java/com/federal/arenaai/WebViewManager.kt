package com.federal.arenaai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewManager {
    private var webView: WebView? = null
    private var mutableContext: MutableContextWrapper? = null
    private var currentUrl: String = "https://arena.ai"
    private var activePopupDialog: Dialog? = null

    /**
     * Listener for WebView lifecycle events that the Activity should handle.
     */
    interface Listener {
        /** Called when the WebView renderer process has crashed and a new WebView is ready.
         *  The Activity should remove the old WebView and attach the new one. */
        fun onWebViewRecreated(newWebView: WebView)
    }

    var listener: Listener? = null

    /**
     * Clean default Android WebView User-Agent to match standard Chrome Mobile browser.
     * Google OAuth checks for '; wv' and 'Version/X.X' in the User-Agent header and rejects
     * requests with '403 disallowed_useragent'. Removing these markers allows Google OAuth,
     * GitHub OAuth, and other identity providers to work seamlessly inside the native WebView.
     */
    fun cleanUserAgent(rawUa: String): String {
        return rawUa
            .replace("; wv", "")
            .replace("; wv;", ";")
            .replace(Regex("Version/[0-9]+\\.[0-9]+\\s*"), "")
    }

    /**
     * Hosts that stay inside the app's WebView (including authentication and identity providers).
     * This keeps the user inside the native app during the entire login flow, storing cookies
     * directly in the app's CookieManager so the user is immediately logged in upon returning.
     */
    fun isAllowedInWebView(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false

        // 1. Internal arena.ai / LMSYS domains
        val internalDomains = listOf(
            "arena.ai",
            "lmarena.ai",
            "lmsys.org",
            "chatbot-arena.org"
        )
        if (internalDomains.any { host == it || host.endsWith(".$it") }) {
            return true
        }

        // 2. Auth / Identity / SSO domains (Google, GitHub, Apple, Microsoft, Clerk, Supabase, Auth0, etc.)
        val authDomains = listOf(
            // Google OAuth & Services
            "accounts.google.com",
            "oauth2.googleapis.com",
            "apis.google.com",
            "myaccount.google.com",
            "ssl.gstatic.com",
            "accounts.youtube.com",
            // GitHub OAuth
            "github.com",
            "api.github.com",
            "gist.github.com",
            // Apple OAuth
            "appleid.apple.com",
            "idmsa.apple.com",
            // Microsoft OAuth
            "login.microsoftonline.com",
            "login.live.com",
            "account.live.com",
            "login.windows.net",
            // Clerk Auth
            "clerk.com",
            "clerk.accounts.dev",
            "accounts.dev",
            // Supabase / Auth0
            "supabase.co",
            "auth0.com",
            // Cloudflare / Bot Protection / Captcha
            "cloudflare.com",
            "challenges.cloudflare.com",
            "recaptcha.net",
            "hcaptcha.com",
            // Hugging Face / Gradio
            "huggingface.co",
            "hf.space",
            "gradio.app"
        )
        if (authDomains.any { host == it || host.endsWith(".$it") }) {
            return true
        }

        // Google country-specific domains (e.g. accounts.google.co.uk)
        if (host.startsWith("accounts.google.")) {
            return true
        }

        return false
    }

    /**
     * Configure cookies for the WebView.
     * Both first-party and third-party cookies are enabled to ensure OAuth session
     * cookies and tokens are stored directly in the app's persistent cookie jar.
     */
    fun configureCookies(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Flush cookies to persistent storage so they survive app restarts and process kills.
     */
    fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // CookieManager may not be initialized yet
        }
    }

    /**
     * Open external links (non-auth, external websites) in user's default external browser.
     */
    fun openInExternalBrowser(context: Context?, uri: Uri) {
        val ctx = context ?: mutableContext?.baseContext ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Recursively unwrap ContextWrapper to find the hosting Activity.
     */
    private fun getActivityFromContext(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }

    /**
     * Dismiss any currently active OAuth popup dialog if open.
     * Returns true if a dialog was dismissed.
     */
    fun dismissActivePopup(): Boolean {
        activePopupDialog?.let { dialog ->
            if (dialog.isShowing) {
                try {
                    dialog.dismiss()
                    return true
                } catch (_: Exception) {}
            }
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getWebView(context: Context): WebView {
        if (webView == null) {
            mutableContext = MutableContextWrapper(context)
            webView = WebView(mutableContext!!).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    // Clean User-Agent so Google OAuth and other providers allow in-app login
                    userAgentString = cleanUserAgent(userAgentString)
                    allowFileAccess = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    setGeolocationEnabled(true)
                }

                // Configure cookies BEFORE loading any page
                configureCookies(this)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme?.lowercase() ?: return false
                        val ctx = view?.context ?: mutableContext?.baseContext

                        // Handle mailto, tel, sms
                        if (scheme == "mailto" || scheme == "tel" || scheme == "sms") {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx?.startActivity(intent)
                            } catch (_: Exception) {}
                            return true
                        }

                        // Handle android intent:// schemes
                        if (scheme == "intent") {
                            try {
                                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                if (intent != null && ctx != null) {
                                    val pm = ctx.packageManager
                                    val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                                    if (info != null) {
                                        ctx.startActivity(intent)
                                    } else {
                                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                        if (fallbackUrl != null) {
                                            val fallbackUri = Uri.parse(fallbackUrl)
                                            if (isAllowedInWebView(fallbackUri)) {
                                                view?.loadUrl(fallbackUrl)
                                            } else {
                                                openInExternalBrowser(ctx, fallbackUri)
                                            }
                                        }
                                    }
                                    return true
                                }
                            } catch (_: Exception) {}
                            return true
                        }

                        // Handle HTTP / HTTPS
                        if (scheme == "http" || scheme == "https") {
                            // Keep internal pages and OAuth/auth pages inside WebView
                            if (isAllowedInWebView(uri)) {
                                return false // Let WebView load it
                            }

                            // Everything else -> external browser
                            openInExternalBrowser(ctx, uri)
                            return true
                        }

                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { currentUrl = it }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { currentUrl = it }
                        // Flush cookies after every page load to persist session
                        flushCookies()
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            view?.loadData(
                                """
                                <html><body style="background:#1a1a2e;color:#fff;font-family:sans-serif;
                                display:flex;flex-direction:column;align-items:center;justify-content:center;
                                height:100vh;margin:0;padding:20px;box-sizing:border-box;">
                                <h2>Connection Error</h2>
                                <p>Unable to load the page. Please check your internet connection.</p>
                                <p><a href="https://arena.ai" style="color:#4fc3f7;">Retry</a></p>
                                </body></html>
                                """.trimIndent(),
                                "text/html",
                                "UTF-8"
                            )
                        }
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        try {
                            webView?.let {
                                if (it.parent != null) {
                                    (it.parent as ViewGroup).removeView(it)
                                }
                                it.destroy()
                            }
                        } catch (_: Exception) {}

                        webView = null
                        mutableContext = null

                        val ctx = view?.context ?: return false
                        val newWebView = getWebView(ctx)
                        newWebView.loadUrl(currentUrl)

                        listener?.onWebViewRecreated(newWebView)
                        return true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                    }

                    /**
                     * Handle OAuth popup windows (window.open).
                     * Creates an in-app popup dialog with a secondary WebView that shares
                     * cookies and automatically closes on window.close(), returning to the
                     * main view logged in.
                     */
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        val activity = getActivityFromContext(view?.context)
                            ?: getActivityFromContext(mutableContext)

                        if (activity == null || activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
                            return false
                        }

                        val popupWebView = WebView(activity).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                userAgentString = cleanUserAgent(userAgentString)
                                allowFileAccess = true
                                setSupportMultipleWindows(true)
                                javaScriptCanOpenWindowsAutomatically = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                            }
                            configureCookies(this)
                        }

                        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen).apply {
                            setContentView(popupWebView)
                            setCancelable(true)
                        }
                        activePopupDialog = dialog

                        popupWebView.webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                super.onCloseWindow(window)
                                try {
                                    dialog.dismiss()
                                } catch (_: Exception) {}
                            }
                        }

                        popupWebView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                val reqUri = request?.url ?: return false
                                val reqScheme = reqUri.scheme?.lowercase() ?: return false

                                if (reqScheme == "mailto" || reqScheme == "tel" || reqScheme == "sms") {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, reqUri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        activity.startActivity(intent)
                                    } catch (_: Exception) {}
                                    return true
                                }

                                if (reqScheme == "http" || reqScheme == "https") {
                                    if (isAllowedInWebView(reqUri)) {
                                        return false
                                    }
                                    openInExternalBrowser(activity, reqUri)
                                    return true
                                }

                                return false
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                super.onPageFinished(v, url)
                                flushCookies()

                                // If popup redirected back to arena.ai authenticated pages and finished,
                                // dismiss popup and load the URL in the main WebView
                                url?.let {
                                    val u = Uri.parse(it)
                                    val host = u.host?.lowercase() ?: ""
                                    if (host == "arena.ai" || host.endsWith(".arena.ai") || host == "lmarena.ai" || host.endsWith(".lmarena.ai")) {
                                        val path = u.path ?: ""
                                        if (path == "/" || path.startsWith("/c/") || path.startsWith("/leaderboard")) {
                                            try {
                                                dialog.dismiss()
                                            } catch (_: Exception) {}
                                            loadUrl(it)
                                        }
                                    }
                                }
                            }
                        }

                        dialog.setOnDismissListener {
                            flushCookies()
                            if (activePopupDialog === dialog) {
                                activePopupDialog = null
                            }
                            try {
                                popupWebView.destroy()
                            } catch (_: Exception) {}

                            // Reload main webView so authenticated session renders immediately
                            webView?.let { mainView ->
                                val mainUrl = mainView.url ?: currentUrl
                                if (mainUrl.contains("arena.ai") || mainUrl.contains("lmarena.ai")) {
                                    mainView.reload()
                                }
                            }
                        }

                        dialog.show()

                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        transport?.webView = popupWebView
                        resultMsg?.sendToTarget()
                        return true
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    WebView.setWebContentsDebuggingEnabled(
                        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    )
                }

                loadUrl(currentUrl)
            }
        } else {
            mutableContext?.baseContext = context
            webView?.let { configureCookies(it) }
        }
        return webView!!
    }

    fun loadUrl(url: String) {
        currentUrl = url
        webView?.loadUrl(url)
    }

    fun getCurrentUrl(): String {
        return webView?.url ?: currentUrl
    }

    fun canGoBack(): Boolean {
        return webView?.canGoBack() == true
    }

    fun goBack() {
        webView?.goBack()
    }

    fun onPause() {
        webView?.onPause()
        flushCookies()
    }

    fun onResume() {
        webView?.onResume()
    }

    fun saveState(outState: android.os.Bundle) {
        try {
            webView?.saveState(outState)
        } catch (_: Exception) {
        }
    }

    fun restoreState(savedInstanceState: android.os.Bundle): Boolean {
        val restored = webView?.restoreState(savedInstanceState)
        return restored != null && restored.size > 0
    }
}

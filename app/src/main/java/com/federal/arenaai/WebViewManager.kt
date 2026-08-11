package com.federal.arenaai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
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
     * Domains that should stay inside the WebView.
     * This includes arena.ai itself plus all known OAuth / auth provider domains
     * used by arena.ai's login flow (Google OAuth, reCAPTCHA, etc.).
     *
     * The matching uses exact match + subdomain match (e.g. "arena.ai" matches
     * "arena.ai" and "www.arena.ai" but not "evil-arena.ai").
     *
     * If the auth provider changes or new ones are added, update this list.
     */
    private val ALLOWED_HOSTS = listOf(
        // Arena.ai and all subdomains
        "arena.ai",
        // Google OAuth / Sign-in
        "accounts.google.com",
        "myaccount.google.com",
        "googleusercontent.com",
        // Google APIs (token exchange, reCAPTCHA verification)
        "googleapis.com",
        "gstatic.com",
        "google.com",
        // reCAPTCHA
        "recaptcha.net",
        // GitHub OAuth (if used by arena.ai)
        "github.com",
        // Clerk auth (possible auth provider for arena.ai)
        "clerk.accounts.dev",
        "accounts.dev"
    )

    private fun isAllowedHost(host: String): Boolean {
        return ALLOWED_HOSTS.any { allowed ->
            // Exact match or proper subdomain match (e.g. "www.arena.ai" ends with ".arena.ai")
            // We do NOT use contains() because "evil-arena.ai".contains("arena.ai") would
            // incorrectly match — subdomain matching requires a dot boundary.
            host == allowed || host.endsWith(".$allowed")
        }
    }

    /**
     * Configure cookies for the WebView. Must be called after the WebView is created.
     * Enables third-party cookies which are required for OAuth flows (e.g. Google sets
     * cookies on accounts.google.com while the top-level page is arena.ai).
     */
    private fun configureCookies(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Flush cookies to persistent storage so they survive app restarts.
     */
    fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // CookieManager may not be initialized yet
        }
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
                    // Remove "; wv" from user agent so Google OAuth doesn't block us.
                    // Google blocks sign-in from embedded WebViews identified by "wv".
                    userAgentString = userAgentString.replace("; wv", "")
                    // Enable file access for potential downloads
                    allowFileAccess = true
                    // Enable zoom controls
                    builtInZoomControls = true
                    displayZoomControls = false
                    // Enable media playback
                    mediaPlaybackRequiresUserGesture = false
                    // Cache mode — use cache when offline
                    cacheMode = WebSettings.LOAD_DEFAULT
                    // Support multiple windows (needed for some OAuth popups)
                    setSupportMultipleWindows(false)
                    // Enable geolocation if arena.ai needs it
                    setGeolocationEnabled(true)
                }

                // Configure cookies BEFORE loading any page
                configureCookies(this)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return false
                        val host = url.host ?: return false

                        // Keep allowed domains (arena.ai + auth providers) in the WebView
                        if (isAllowedHost(host)) {
                            return false
                        }

                        // For external links that are NOT part of the auth flow,
                        // open in the default browser.
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            mutableContext?.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { currentUrl = it }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { currentUrl = it }
                        // Flush cookies after every page load to ensure persistence.
                        // This is critical for auth cookies set during OAuth flows.
                        flushCookies()
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        // Only show error for the main frame, not for sub-resources
                        if (request?.isForMainFrame == true) {
                            // Load a simple error page
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
                        // The WebView renderer process crashed or was killed by the system.
                        // Destroy the old WebView and create a new one.
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

                        // Recreate the WebView — getWebView will create a fresh one
                        // since webView is null. The listener (Activity) will attach it.
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
                        // Could add a progress bar here in the future
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        // Could update the Activity title here
                    }
                }

                // Enable WebView debugging in debug builds
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    WebView.setWebContentsDebuggingEnabled(
                        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    )
                }

                loadUrl(currentUrl)
            }
        } else {
            // Update the context to the new Activity
            mutableContext?.baseContext = context

            // Re-configure cookies in case settings were reset
            webView?.let { configureCookies(it) }
        }
        return webView!!
    }

    /**
     * Load a specific URL in the WebView. Used for deep link handling.
     */
    fun loadUrl(url: String) {
        currentUrl = url
        webView?.loadUrl(url)
    }

    /**
     * Get the current URL loaded in the WebView.
     */
    fun getCurrentUrl(): String {
        return webView?.url ?: currentUrl
    }

    fun canGoBack(): Boolean {
        return webView?.canGoBack() == true
    }

    fun goBack() {
        webView?.goBack()
    }

    /**
     * Called when the Activity is paused. Pauses the WebView timers and JavaScript
     * to save battery, and flushes cookies to disk.
     */
    fun onPause() {
        webView?.onPause()
        flushCookies()
    }

    /**
     * Called when the Activity resumes. Resumes WebView timers and JavaScript.
     */
    fun onResume() {
        webView?.onResume()
    }

    /**
     * Save the WebView state for restoration after process death.
     * Wrapped in try-catch because WebView state can exceed the
     * Binder transaction limit (1MB) on pages with heavy content.
     */
    fun saveState(outState: android.os.Bundle) {
        try {
            webView?.saveState(outState)
        } catch (_: Exception) {
            // State too large for Bundle — rely on KEY_LAST_URL fallback
        }
    }

    /**
     * Restore the WebView state after process death.
     * Returns true if state was successfully restored.
     */
    fun restoreState(savedInstanceState: android.os.Bundle): Boolean {
        val restored = webView?.restoreState(savedInstanceState)
        return restored != null && restored.size() > 0
    }
}

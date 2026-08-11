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
import androidx.browser.customtabs.CustomTabsIntent

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
     * ============================================================================
     * CRITICAL OAUTH POLICY — DO NOT MODIFY WITHOUT READING:
     * ============================================================================
     * Google OAuth must NEVER be loaded in the embedded WebView.
     * Google actively blocks OAuth authorization requests originating from
     * android.webkit.WebView at the server level since 2017 (disallowed_useragent,
     * 400 error, "Access blocked"). This is enforced server-side and there is
     * NO client-side workaround (including user-agent spoofing).
     *
     * Policy: https://developers.googleblog.com/2016/08/modernizing-oauth-interactions-in-native-apps.html
     * Enforcement expanded July 2023.
     *
     * Correct pattern:
     * - Keep arena.ai's own pages inside the WebView (INTERNAL_HOSTS)
     * - Intercept Google OAuth URLs (accounts.google.com, oauth2.googleapis.com, etc.)
     *   in shouldOverrideUrlLoading and launch them in a Chrome Custom Tab
     *   (androidx.browser.customtabs.CustomTabsIntent). Custom Tabs use the real
     *   Chrome browser process/user-agent, which Google's OAuth accepts, while
     *   still feeling integrated (overlay, not full browser app switch).
     *
     * - After Custom Tab completes, arena.ai's OAuth callback redirect
     *   (e.g. https://arena.ai/auth/callback) is caught by the App Link intent-filter
     *   in AndroidManifest.xml, returning control to MainActivity automatically.
     *   MainActivity's handleIntent then loads that URL in the WebView, so the
     *   WebView picks up the new session cookie directly (see cookie continuity below).
     *
     * Cookie/session continuity:
     * - Chrome Custom Tabs use Chrome's cookie store, NOT WebView's CookieManager.
     * - They do NOT share cookies automatically.
     * - Our solution: the Custom Tab flow ends with a redirect to an arena.ai URL.
     *   That redirect triggers an App Link intent, which our MainActivity handles by
     *   loading the same URL (often containing an OAuth code) in the WebView.
     *   The WebView then makes its own request to arena.ai, and the server sets the
     *   session cookie in the WebView's CookieManager store. Thus the WebView
     *   becomes authenticated without needing to share Chrome's cookies.
     * - We also call CookieManager.getInstance().flush() after every page load and
     *   onPause/onDestroy to persist the session.
     * - If for some reason the callback URL no longer contains the code (e.g.,
     *   server already exchanged it in Chrome and only redirects to / dashboard),
     *   the WebView reload of the final arena.ai page will still be unauthenticated
     *   unless the server also handled the code. To mitigate, MainActivity explicitly
     *   reloads arena.ai after returning from Custom Tab (via handleIntent loadUrl).
     *   Manual testing on a real device is still recommended to confirm end-to-end.
     * ============================================================================
     */

    /**
     * Hosts that stay inside the WebView — ONLY arena.ai's own domains.
     * Previous PR #4 incorrectly added OAuth domains here. That breaks Google login.
     * DO NOT add accounts.google.com or other OAuth IdP domains here.
     */
    private val INTERNAL_HOSTS = listOf(
        "arena.ai"
    )

    /**
     * OAuth / Identity Provider hosts that must be opened in Chrome Custom Tabs,
     * NOT in the embedded WebView. Google blocks WebView, others may in future.
     * Keep this list precise: exact host + proper subdomain matching.
     */
    private val OAUTH_CUSTOM_TAB_HOSTS = listOf(
        // Google OAuth — MUST use Custom Tabs (Google policy)
        "accounts.google.com",
        "oauth2.googleapis.com",
        // GitHub OAuth — generally more permissive but treat same for consistency/future-proofing
        "github.com",
        "api.github.com",
        // Clerk (observed possible auth provider for arena.ai)
        "clerk.accounts.dev",
        "clerk.com",
        "accounts.dev",
        // Additional providers that are known to block WebView (defensive)
        "appleid.apple.com",
        "facebook.com",
        "www.facebook.com",
        "login.microsoftonline.com",
        "microsoftonline.com"
    )

    private fun isInternalHost(host: String): Boolean {
        val lower = host.lowercase()
        return INTERNAL_HOSTS.any { allowed ->
            val a = allowed.lowercase()
            lower == a || lower.endsWith(".$a")
        }
    }

    private fun isOAuthHost(host: String): Boolean {
        val lower = host.lowercase()
        return OAUTH_CUSTOM_TAB_HOSTS.any { allowed ->
            val a = allowed.lowercase()
            lower == a || lower.endsWith(".$a")
        }
    }

    /**
     * Configure cookies for the WebView. Must be called after the WebView is created.
     * Third-party cookies are still enabled for arena.ai sub-resources, but OAuth
     * itself no longer relies on third-party cookies in WebView since it runs in Custom Tab.
     */
    private fun configureCookies(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * Flush cookies to persistent storage so they survive app restarts and can be
     * picked up after returning from Custom Tab flow.
     */
    fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // CookieManager may not be initialized yet
        }
    }

    /**
     * Launch a URL in Chrome Custom Tabs. This uses the real browser user-agent
     * which Google OAuth accepts, unlike embedded WebView.
     */
    private fun openInCustomTab(uri: Uri) {
        val ctx = mutableContext ?: return
        try {
            val builder = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                // Dark color scheme to match app theme best-effort; default params keep system handling
                // For full styling you could set toolbar color to match @color primary.

            // Build and launch
            val customTabsIntent = builder.build()
            // Must add NEW_TASK when starting from non-Activity context wrapped in MutableContextWrapper
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Optional: ensure we don't keep multiple Custom Tabs activities stacked
            // The Custom Tab Activity itself will handle App Link redirects back to our app.

            customTabsIntent.launchUrl(ctx, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to external browser if Custom Tabs fails (no Chrome / no provider)
            openInExternalBrowser(uri)
        }
    }

    private fun openInExternalBrowser(uri: Uri) {
        val ctx = mutableContext ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
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
                    // Note: previously we stripped \"; wv\" to try to bypass Google's WebView block.
                    // That hack no longer works and is not sufficient. Google now detects WebView
                    // via additional signals beyond UA. Do NOT rely on UA spoofing.
                    // Keep the stripping for completeness but document it doesn't fix OAuth.
                    userAgentString = userAgentString.replace("; wv", "")
                    allowFileAccess = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(true)
                }

                // Configure cookies BEFORE loading any page
                configureCookies(this)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return false
                        val host = url.host ?: return false

                        // 1. Keep arena.ai (and subdomains) inside the WebView
                        if (isInternalHost(host)) {
                            return false // Let WebView load it
                        }

                        // 2. OAuth / IdP hosts -> open in Chrome Custom Tab (Google-compliant)
                        if (isOAuthHost(host)) {
                            openInCustomTab(url)
                            return true // We handled it
                        }

                        // 3. Everything else -> external browser
                        // (Could also use Custom Tab for external, but requirement says
                        // external browser handoff is okay for non-OAuth; we keep ACTION_VIEW)
                        openInExternalBrowser(url)
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
                        // Critical so that the authenticated session set after OAuth
                        // (when handleIntent loads the callback URL) is saved to disk.
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
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
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

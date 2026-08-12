package com.federal.arenaai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Log tag used across the WebView/upload/download machinery. */
private const val TAG = "ArenaWebView"

object WebViewManager {
    private var webView: WebView? = null
    private var mutableContext: MutableContextWrapper? = null
    private var currentUrl: String = "https://arena.ai"
    private var activePopupDialog: Dialog? = null

    // ---------------------------------------------------------------------
    // File upload (onShowFileChooser) state
    //
    // The ValueCallback contract: it MUST be invoked exactly once, with the
    // chosen Uris or null when the user cancels. Every path (deliver, cancel,
    // re-entrant chooser, renderer crash, popup close) releases it exactly once.
    // ---------------------------------------------------------------------
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** WebView that owns the pending callback (main or popup). */
    private var fileChooserWebView: WebView? = null

    /**
     * Bridge to the Activity's registerForActivityResult launcher. The Activity
     * sets this so onShowFileChooser() can start the system picker and later
     * deliver the result via [deliverFileChooserResult].
     */
    var startFileChooser: ((Intent) -> Unit)? = null

    /** FileProvider URI the camera intent writes to (if the camera option was offered). */
    private var pendingCameraUri: Uri? = null

    // ---------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------
    /** Name of the @JavascriptInterface injected into every WebView. */
    private const val BRIDGE_NAME = "AndroidBridge"

    private const val DOWNLOAD_NOTIFICATION_CHANNEL = "arena_downloads"

    /** Upper bound for JS-bridge blob saves (bytes). ~64 MB decoded. */
    private const val MAX_BLOB_SAVE_BYTES = 64L * 1024 * 1024

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
            // Remove the '; wv' WebView marker that triggers OAuth '403 disallowed_useragent'.
            .replace("; wv", "")
            // Remove the legacy 'Version/X.X' token that also flags embedded WebViews.
            .replace(Regex("\\sVersion/[0-9]+\\.[0-9]+"), "")
            // Collapse any whitespace artifacts left by the removals above.
            .replace(Regex("\\s{2,}"), " ")
            .trim()
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

    // =====================================================================
    // SHARED WebView CONFIGURATION (main WebView AND popup WebViews)
    //
    // Both kinds of WebView get identical upload (onShowFileChooser), download
    // (DownloadListener + JS blob bridge) and security behavior. The popup
    // previously had none of this — a file input or download inside a
    // window.open() popup was completely dead.
    // =====================================================================

    private fun applyCommonSettings(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Clean User-Agent so Google OAuth and other providers allow in-app login
            userAgentString = cleanUserAgent(userAgentString)
            // Security: never expose the local file system (app only loads remote https)
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(false)
        }

        // Configure cookies BEFORE loading any page
        configureCookies(wv)
    }

    /**
     * Install the DownloadListener used by BOTH the main and popup WebViews.
     * http(s) downloads go to the system DownloadManager (with auth cookies);
     * blob:/data: downloads are routed to the in-page JS bridge.
     */
    private fun installDownloadListener(wv: WebView) {
        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            Log.d(TAG, "onDownloadStart (view=${wv.hashCode()}): url=$url mime=$mimetype contentDisposition=$contentDisposition")
            handleDownload(wv, url, userAgent, contentDisposition, mimetype)
        })
    }

    /**
     * Inject the JS download-capture hook. Installed at document start when the
     * WebView supports it, and also re-injected at onPageFinished (idempotent).
     */
    private fun injectDownloadHook(wv: WebView) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ADD_DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, DOWNLOAD_HOOK_JS, emptySet())
                Log.d(TAG, "injectDownloadHook: installed via addDocumentStartJavaScript (view=${wv.hashCode()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "injectDownloadHook: addDocumentStartJavaScript unavailable", e)
        }
    }

    /** Evaluate the download hook in the current document (fallback path). */
    private fun evaluateDownloadHook(wv: WebView) {
        try {
            // The script is self-guarded (window.__arenaDownloadHookInstalled).
            wv.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
        } catch (e: Exception) {
            Log.w(TAG, "evaluateDownloadHook failed", e)
        }
    }

    /**
     * Quote a string for use inside a JavaScript string literal.
     */
    private fun escapeJs(value: String): String {
        return JSONObject.quote(value)
    }

    /**
     * Shared WebViewClient for main and popup WebViews.
     *
     * @param onPageFinishedExtra   popup-only work after each page load (OAuth auto-dismiss).
     * @param onRenderProcessGoneExtra popup-only crash handling; null means "this is the main
     *                                 WebView, recreate the singleton".
     */
    private fun createWebViewClient(
        onPageFinishedExtra: ((WebView?, String?) -> Unit)? = null,
        onRenderProcessGoneExtra: ((WebView?) -> Boolean)? = null
    ): WebViewClient {
        return object : WebViewClient() {
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

                // Handle android intent:// schemes (hardened against intent injection).
                // Strip the explicit component and selector so a malicious page cannot
                // launch arbitrary exported activities via component=/selector=, and only
                // start activities that declare a BROWSABLE category.
                if (scheme == "intent") {
                    try {
                        val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        // Note: setSelector() returns void (the others return Intent),
                        // so build the intent statement-by-statement instead of chaining.
                        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                        parsed.setComponent(null)
                        parsed.setSelector(null)
                        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (ctx != null && parsed.resolveActivity(ctx.packageManager) != null) {
                            ctx.startActivity(parsed)
                        } else {
                            val fallbackUrl = parsed.getStringExtra("browser_fallback_url")
                            if (fallbackUrl != null) {
                                val fallbackUri = Uri.parse(fallbackUrl)
                                if (isAllowedInWebView(fallbackUri)) {
                                    view?.loadUrl(fallbackUrl)
                                } else {
                                    openInExternalBrowser(ctx, fallbackUri)
                                }
                            }
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
                // Only the main singleton updates the persisted "current" URL.
                if (view === webView) {
                    url?.let { currentUrl = it }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view === webView) {
                    url?.let { currentUrl = it }
                }
                // Flush cookies after every page load to persist session
                flushCookies()
                // Ensure the download-capture hook is present in the current document
                view?.let { evaluateDownloadHook(it) }
                onPageFinishedExtra?.invoke(view, url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                val failedUrl = request.url?.toString() ?: currentUrl
                // Avoid recursing if we are already showing the error page.
                if (failedUrl.startsWith("about:")) return

                val html = """
                    <html><body style="background:#1a1a2e;color:#fff;font-family:sans-serif;
                    display:flex;flex-direction:column;align-items:center;justify-content:center;
                    height:100vh;margin:0;padding:20px;box-sizing:border-box;">
                    <h2>Connection Error</h2>
                    <p>Unable to load the page. Please check your internet connection.</p>
                    <p><a href="$failedUrl" style="color:#4fc3f7;">Retry</a></p>
                    </body></html>
                """.trimIndent()
                view?.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", failedUrl)
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                Log.e(TAG, "onRenderProcessGone (view=${view?.hashCode()})")
                // Whatever renderer died may own a pending file chooser — release it.
                releaseFileChooserCallback()
                if (view === webView) {
                    // Main WebView crashed: recreate the singleton.
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
                return onRenderProcessGoneExtra?.invoke(view) ?: false
            }
        }
    }

    /**
     * Shared WebChromeClient for main and popup WebViews.
     *
     * @param onCloseWindow   popup-only: dismiss the hosting Dialog when window.close() fires.
     */
    private fun createWebChromeClient(
        onCloseWindow: ((WebView) -> Unit)? = null
    ): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
            }

            /**
             * Handle <input type="file"> upload choosers — for BOTH the main and the
             * popup WebView. Launches the system content/document picker (with an
             * optional camera-capture action for image inputs) and delivers the chosen
             * Uris back to the page via [deliverFileChooserResult].
             */
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                val acceptTypes = params.acceptTypes?.joinToString(",") ?: ""
                Log.d(
                    TAG,
                    "onShowFileChooser fired (view=${webView?.hashCode()}): acceptTypes=[$acceptTypes] " +
                        "mode=${params.mode} capture=${params.isCaptureEnabled}"
                )

                // Release any previously pending callback before starting a new one.
                releaseFileChooserCallback()
                filePathCallback = callback
                fileChooserWebView = webView
                pendingCameraUri = null

                val launcher = startFileChooser
                if (launcher == null) {
                    Log.w(TAG, "onShowFileChooser: startFileChooser is null (Activity not wired) — returning false")
                    releaseFileChooserCallback()
                    return false
                }

                val chooserIntent = try {
                    buildFileChooserIntent(params)
                } catch (e: Exception) {
                    Log.e(TAG, "onShowFileChooser: buildFileChooserIntent failed", e)
                    releaseFileChooserCallback()
                    return false
                }

                return try {
                    launcher.invoke(chooserIntent)
                    Log.d(TAG, "onShowFileChooser: launcher launched intent=${chooserIntent}")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "onShowFileChooser: launch threw", e)
                    releaseFileChooserCallback()
                    false
                }
            }

            /**
             * Handle OAuth popup windows (window.open) — shared by main and popup WebViews.
             * Creates an in-app popup dialog with a secondary WebView that shares cookies,
             * supports upload/download like the main WebView, and automatically closes on
             * window.close(), returning to the main view logged in.
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                return handleCreateWindow(view, isDialog, isUserGesture, resultMsg)
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                window?.let { onCloseWindow?.invoke(it) }
            }
        }
    }

    /**
     * Build the system file-picker intent for a file chooser request.
     * Wraps the WebView-provided intent in Intent.createChooser() and optionally
     * prepends a camera-capture action for image-capable inputs.
     */
    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val contentIntent = params.createIntent().apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        val cameraIntent = buildCameraIntent(params)
        val chooser = Intent.createChooser(contentIntent, null)
        if (cameraIntent != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
        }
        return chooser
    }

    /**
     * Optional ACTION_IMAGE_CAPTURE intent offered alongside the file picker for
     * image-capable inputs (<input accept="image/*" capture>). The captured photo is
     * written to a FileProvider URI in the app cache; no CAMERA permission is
     * required to hand off to the system camera app via an intent.
     */
    private fun buildCameraIntent(params: WebChromeClient.FileChooserParams): Intent? {
        val ctx = mutableContext?.baseContext ?: return null
        val accept = params.acceptTypes?.joinToString("") ?: ""
        val wantsImage = accept.isEmpty() || accept.contains("*/*") || accept.contains("image/")
        if (!wantsImage) return null

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(ctx.packageManager) == null) {
            Log.d(TAG, "buildCameraIntent: no camera activity — skipping camera option")
            return null
        }

        val outputFile = try {
            val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
            File(dir, "IMG_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
        } catch (e: Exception) {
            Log.e(TAG, "buildCameraIntent: cannot create camera output file", e)
            return null
        }

        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", outputFile)
        pendingCameraUri = uri
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        Log.d(TAG, "buildCameraIntent: offering camera option -> $uri")
        return cameraIntent
    }

    /**
     * Shared window.open() handler. Builds a fully-configured popup WebView
     * (upload + download + bridge included) hosted in a Dialog.
     */
    private fun handleCreateWindow(
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
            // Same settings/cookies/security as the main WebView
            applyCommonSettings(this)
            // Upload + download + bridge support in popups too
            installDownloadListener(this)
            installDownloadBridge(this)
            injectDownloadHook(this)
        }

        var popupTargetUrl: String? = null

        // DayNight-aware popup so it matches the app theme in dark mode.
        val dialog = Dialog(activity, R.style.Arena_PopupDialog).apply {
            setContentView(popupWebView)
            setCancelable(true)
        }
        activePopupDialog = dialog

        popupWebView.webChromeClient = createWebChromeClient(
            onCloseWindow = { _ ->
                try {
                    dialog.dismiss()
                } catch (_: Exception) {}
            }
        )

        popupWebView.webViewClient = createWebViewClient(
            onPageFinishedExtra = { _, url ->
                // If the popup redirected back to an authenticated arena.ai page,
                // remember where to land and dismiss. The dismiss listener performs
                // the single navigation into the main WebView (avoids a double load).
                url?.let {
                    val u = Uri.parse(it)
                    val host = u.host?.lowercase() ?: ""
                    if (host == "arena.ai" || host.endsWith(".arena.ai") || host == "lmarena.ai" || host.endsWith(".lmarena.ai")) {
                        val path = u.path ?: ""
                        if (path == "/" || path.startsWith("/c/") || path.startsWith("/leaderboard")) {
                            popupTargetUrl = it
                            try {
                                dialog.dismiss()
                            } catch (_: Exception) {}
                        }
                    }
                }
            },
            onRenderProcessGoneExtra = { _ ->
                try { popupWebView.destroy() } catch (_: Exception) {}
                try { dialog.dismiss() } catch (_: Exception) {}
                true
            }
        )

        dialog.setOnDismissListener {
            flushCookies()
            if (activePopupDialog === dialog) {
                activePopupDialog = null
            }
            // A pending file chooser owned by this popup must be released.
            releaseFileChooserIfOwnedBy(popupWebView)
            try {
                popupWebView.destroy()
            } catch (_: Exception) {}

            // Single authoritative navigation: load the popup's target (or reload
            // the current page) into the main WebView so the session renders.
            webView?.let { mainView ->
                val dest = popupTargetUrl ?: mainView.url ?: currentUrl
                if (dest.contains("arena.ai") || dest.contains("lmarena.ai")) {
                    mainView.loadUrl(dest)
                }
            }
            popupTargetUrl = null
        }

        dialog.show()

        val transport = resultMsg?.obj as? WebView.WebViewTransport
        transport?.webView = popupWebView
        resultMsg?.sendToTarget()
        return true
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

                applyCommonSettings(this)

                // Handle file downloads (attachments, exported files, etc.) via the
                // system DownloadManager, including the WebView's auth cookies.
                installDownloadListener(this)

                // JS bridge for blob:/data: downloads that DownloadManager cannot fetch.
                installDownloadBridge(this)

                webViewClient = createWebViewClient()

                webChromeClient = createWebChromeClient()

                injectDownloadHook(this)

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

    /**
     * Detach the singleton WebView from a ViewGroup WITHOUT creating a new WebView.
     * Use this from Activity.onDestroy instead of getWebView(), which has the side
     * effect of (re)creating and loading a WebView when the singleton is null.
     */
    fun detachFrom(container: ViewGroup) {
        try {
            webView?.let { w ->
                if (w.parent === container) {
                    container.removeView(w)
                }
            }
        } catch (_: Exception) {}
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

    // =====================================================================
    // FILE CHOOSER RESULT HANDLING
    // =====================================================================

    /**
     * Called by the Activity with the result of the system file picker.
     * Pass the chosen Uris, or null if the user cancelled (releases the callback).
     */
    fun deliverFileChooserResult(uris: Array<Uri>?) {
        Log.d(TAG, "deliverFileChooserResult: uris=${uris?.joinToString() ?: "null (cancelled)"}")
        val cb = filePathCallback
        filePathCallback = null
        fileChooserWebView = null
        try {
            cb?.onReceiveValue(uris)
        } catch (e: Exception) {
            Log.w(TAG, "deliverFileChooserResult: callback threw", e)
        }
    }

    /**
     * The camera EXTRA_OUTPUT URI, if a camera action was offered and the user
     * went through it. Consumed (and cleared) by the Activity's result callback.
     */
    fun consumePendingCameraUri(): Uri? {
        val uri = pendingCameraUri
        pendingCameraUri = null
        return uri
    }

    /** Cancel any in-flight file chooser (e.g. on Activity destroy). */
    fun cancelPendingFileChooser() {
        releaseFileChooserCallback()
    }

    /** Invoke the pending callback with null (cancel) and clear it, exactly once. */
    private fun releaseFileChooserCallback() {
        val cb = filePathCallback
        filePathCallback = null
        fileChooserWebView = null
        try {
            cb?.onReceiveValue(null)
        } catch (e: Exception) {
            Log.w(TAG, "releaseFileChooserCallback: callback threw", e)
        }
    }

    /** Release the pending chooser only if it belongs to the given WebView (popup close). */
    private fun releaseFileChooserIfOwnedBy(wv: WebView) {
        if (fileChooserWebView === wv) {
            releaseFileChooserCallback()
        }
    }

    // =====================================================================
    // DOWNLOAD HANDLING
    // =====================================================================

    /**
     * Dispatch a download to the right handler:
     *  - http(s): system DownloadManager (with WebView cookies + User-Agent)
     *  - blob: : in-page JS bridge (the blob lives in the renderer; only JS can read it)
     *  - data:  : in-page JS bridge (fetch-based), native base64 decode as fallback
     */
    private fun handleDownload(
        wv: WebView?,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?
    ) {
        val ctx = wv?.context ?: mutableContext?.baseContext ?: return

        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                // Blob URLs are renderer-local: DownloadManager and other apps cannot
                // resolve them. Ask the page's JS hook to read the blob and hand the
                // bytes to AndroidBridge.saveBlob().
                Log.d(TAG, "handleDownload: blob: URL -> in-page JS bridge")
                try {
                    wv?.evaluateJavascript(
                        "window.__arenaHandleDownload && window.__arenaHandleDownload(${escapeJs(url)}, '', '');",
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "handleDownload: blob bridge evaluateJavascript failed", e)
                }
            }

            url.startsWith("data:", ignoreCase = true) -> {
                Log.d(TAG, "handleDownload: data: URL -> native base64 decode, JS fetch as fallback")
                // Base64 data URIs decode deterministically without the renderer.
                val savedNatively = saveDataUrlNatively(
                    ctx, url, URLUtil.guessFileName(url, contentDisposition, mimetype)
                )
                if (!savedNatively) {
                    try {
                        wv?.evaluateJavascript(
                            "window.__arenaHandleDownload && window.__arenaHandleDownload(${escapeJs(url)}, '', '');",
                            null
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "handleDownload: data bridge evaluateJavascript failed", e)
                    }
                }
            }

            URLUtil.isNetworkUrl(url) -> {
                Log.d(TAG, "handleDownload: network URL -> DownloadManager")
                enqueueNetworkDownload(ctx, url, userAgent, contentDisposition, mimetype)
            }

            else -> {
                Log.d(TAG, "handleDownload: unsupported scheme ($url) -> external browser attempt")
                try { openInExternalBrowser(ctx, Uri.parse(url)) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Enqueue an http(s) download via the system DownloadManager, forwarding the
     * WebView's User-Agent and auth cookies so authenticated downloads work.
     */
    private fun enqueueNetworkDownload(
        ctx: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype ?: "")
                setTitle(fileName)
                setDescription("Downloading $fileName")
                addRequestHeader("User-Agent", userAgent)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) addRequestHeader("Cookie", cookies)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            val id = dm.enqueue(request)
            Log.d(TAG, "enqueueNetworkDownload: enqueued id=$id url=$url -> $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "enqueueNetworkDownload: failed, falling back to external browser", e)
            // Fallback: let the user's browser attempt the download.
            try { openInExternalBrowser(ctx, Uri.parse(url)) } catch (_: Exception) {}
        }
    }

    /**
     * Native fallback for base64 data: URLs when no WebView is available to run JS.
     * Returns true if the payload was decoded and saved (or ignored as non-base64).
     */
    private fun saveDataUrlNatively(ctx: Context, url: String, fallbackName: String): Boolean {
        return try {
            val comma = url.indexOf(',')
            if (comma < 0) return false
            val meta = url.substring(5, comma)
            val data = url.substring(comma + 1)
            val mime = Regex("^data:([^;,]+)").find(meta)?.groupValues?.get(1)
                ?.ifEmpty { null } ?: "application/octet-stream"
            if (!meta.contains(";base64")) return false // percent-encoded needs the JS path
            val bytes = Base64.decode(data, Base64.DEFAULT)
            if (bytes.isEmpty()) {
                Log.w(TAG, "saveDataUrlNatively: empty payload")
                return true
            }
            val saved = saveBytesToDownloads(ctx, bytes, sanitizeFileName(fallbackName), mime)
            if (saved != null) {
                Log.d(TAG, "saveDataUrlNatively: saved $saved")
                notifyDownloadComplete(ctx, sanitizeFileName(fallbackName), saved)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveDataUrlNatively failed", e)
            false
        }
    }

    /**
     * JS bridge exposed to the page as `AndroidBridge.saveBlob(base64, mime, fileName)`.
     * Receives blob/data download bytes from the injected hook and persists them to
     * the Downloads collection. Implemented as a top-level class with an explicit
     * callback so it has no dependency on the WebViewManager singleton internals.
     */
    private class AndroidBridge(
        private val owner: WebView,
        private val onSaveBlob: (Context, String, String, String) -> Unit
    ) {
        @android.webkit.JavascriptInterface
        fun saveBlob(base64Data: String, mimeType: String, fileName: String) {
            val ctx = owner.context ?: return
            Log.d(
                TAG,
                "AndroidBridge.saveBlob (view=${owner.hashCode()}): base64Len=${base64Data.length} " +
                    "mime=$mimeType name=$fileName"
            )
            onSaveBlob(ctx, base64Data, mimeType, fileName)
        }
    }

    /** Add the JS bridge to a WebView (main or popup). */
    private fun installDownloadBridge(wv: WebView) {
        try {
            wv.addJavascriptInterface(
                AndroidBridge(wv) { ctx, base64, mime, name -> handleBlobSave(ctx, base64, mime, name) },
                BRIDGE_NAME
            )
            Log.d(TAG, "installDownloadBridge: AndroidBridge installed (view=${wv.hashCode()})")
        } catch (e: Exception) {
            Log.e(TAG, "installDownloadBridge failed", e)
        }
    }

    /**
     * Decode and persist a JS-bridge blob download. Runs the heavy work off the
     * JavaBridge thread so large payloads do not block the renderer.
     */
    private fun handleBlobSave(ctx: Context, base64Data: String, mimeType: String, fileName: String) {
        Thread { decodeAndSaveBlob(ctx, base64Data, mimeType, fileName) }.start()
    }

    private fun decodeAndSaveBlob(ctx: Context, base64Data: String, mimeType: String, fileName: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            Log.d(TAG, "decodeAndSaveBlob: decoded ${bytes.size} bytes")
            if (bytes.isEmpty()) {
                Log.w(TAG, "decodeAndSaveBlob: empty payload — ignoring")
                return
            }
            if (bytes.size > MAX_BLOB_SAVE_BYTES) {
                Log.w(TAG, "decodeAndSaveBlob: payload too large (${bytes.size} bytes) — ignoring")
                return
            }
            val name = sanitizeFileName(fileName)
            val mime = mimeType.ifEmpty { guessMimeType(name) }
            val savedUri = saveBytesToDownloads(ctx, bytes, name, mime)
            if (savedUri != null) {
                Log.d(TAG, "decodeAndSaveBlob: saved '$name' -> $savedUri")
                notifyDownloadComplete(ctx, name, savedUri)
            } else {
                Log.e(TAG, "decodeAndSaveBlob: failed to save '$name'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeAndSaveBlob: error", e)
        }
    }

    /**
     * Persist raw bytes to the public Downloads collection.
     * API 29+: MediaStore.Downloads (no permission needed).
     * API 24-28: public Downloads dir requires WRITE_EXTERNAL_STORAGE; when it is not
     * granted, falls back to the app-specific external Downloads dir (always writable).
     */
    private fun saveBytesToDownloads(ctx: Context, bytes: ByteArray, rawName: String, mime: String): Uri? {
        val name = sanitizeFileName(rawName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(ctx, bytes, name, mime)
        } else {
            saveLegacyFile(ctx, bytes, name, mime)
        }
    }

    private fun saveViaMediaStore(ctx: Context, bytes: ByteArray, name: String, mime: String): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "saveViaMediaStore failed", e)
            try { uri?.let { resolver.delete(it, null, null) } } catch (_: Exception) {}
            return null
        }
    }

    private fun saveLegacyFile(ctx: Context, bytes: ByteArray, name: String, mime: String): Uri? {
        return try {
            val hasStoragePermission = ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            val dir = if (hasStoragePermission) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            }
            if (dir == null) {
                Log.w(TAG, "saveLegacyFile: no external storage dir available")
                return null
            }
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
            Log.d(TAG, "saveLegacyFile: wrote ${bytes.size} bytes to ${file.absolutePath}")

            if (hasStoragePermission) {
                // Public Downloads dir — content Uri for the "Download complete"
                // notification (safe to open on API 24+).
                Uri.fromFile(file)
            } else {
                // App-specific dir — expose through FileProvider so the
                // notification can open the saved file.
                FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveLegacyFile failed", e)
            null
        }
    }

    /**
     * Post a "Download complete" notification. On API 33+ this requires the
     * POST_NOTIFICATIONS runtime permission (already requested by the Activity).
     */
    private fun notifyDownloadComplete(ctx: Context, name: String, uri: Uri?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "notifyDownloadComplete: POST_NOTIFICATIONS not granted — skipping")
                    return
                }
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        DOWNLOAD_NOTIFICATION_CHANNEL,
                        "Arena AI Downloads",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Notifications when a download completes" }
                )
            }

            val openIntent = uri?.let {
                // Only content:// Uris can be shared safely on API 24+; raw file://
                // Uris would throw FileUriExposedException in the target viewer.
                if (it.scheme != "content") {
                    null
                } else {
                    Intent(Intent.ACTION_VIEW, it)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val contentIntent = openIntent?.let {
                PendingIntent.getActivity(
                    ctx, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val notification: Notification = NotificationCompat.Builder(ctx, DOWNLOAD_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Download complete")
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
            nm.notify(name.hashCode() and 0x7fffffff, notification)
        } catch (e: Exception) {
            Log.e(TAG, "notifyDownloadComplete failed", e)
        }
    }

    /** Make a JS-provided file name safe for the filesystem. */
    private fun sanitizeFileName(raw: String): String {
        var name = raw
            .replace(Regex("[/\\:*?\">|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (name == "." || name == "..") name = "download"
        if (name.length > 150) name = name.substring(0, 150)
        if (name.isBlank()) name = "download"
        return name
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    // =====================================================================
    // JS DOWNLOAD-CAPTURE HOOK
    //
    // Injected into every page (main + popup). Intercepts <a download> clicks
    // whose href is a blob:/data: URL — the mechanism SPA chat apps (arena.ai
    // included) use for generated downloads — and forwards the bytes to
    // AndroidBridge.saveBlob(). http(s) downloads are left to the native
    // DownloadListener. Also exposed as window.__arenaHandleDownload() so the
    // native DownloadListener path can route blob: URLs back into JS.
    // =====================================================================
    private val DOWNLOAD_HOOK_JS: String = """
        (function () {
          'use strict';
          if (window.__arenaDownloadHookInstalled) return;
          window.__arenaDownloadHookInstalled = true;

          // Keep live references to Blobs handed out by URL.createObjectURL so the
          // bytes can be read even after the page revokes the URL (or revokes before
          // triggering the download click — a common pattern).
          var blobRegistry = new Map();
          var softBlobs = [];
          var SOFT_TTL_MS = 30000;
          var origCreate = URL.createObjectURL;
          var origRevoke = URL.revokeObjectURL;

          if (typeof origCreate === 'function') {
            URL.createObjectURL = function (obj) {
              var url = origCreate.call(URL, obj);
              try {
                if (obj && typeof obj.arrayBuffer === 'function') {
                  blobRegistry.set(url, obj);
                  pruneSoftBlobs();
                }
              } catch (e) {}
              return url;
            };
          }
          if (typeof origRevoke === 'function') {
            URL.revokeObjectURL = function (url) {
              var blob = null;
              try { blob = blobRegistry.get(url) || null; } catch (e) {}
              if (blob) {
                try {
                  blobRegistry.delete(url);
                  softBlobs.push({ url: url, blob: blob, expires: Date.now() + SOFT_TTL_MS });
                } catch (e) {}
              }
              return origRevoke.call(URL, url);
            };
          }

          function pruneSoftBlobs() {
            var now = Date.now();
            while (softBlobs.length && softBlobs[0].expires < now) softBlobs.shift();
          }

          function findBlob(url) {
            pruneSoftBlobs();
            if (blobRegistry.has(url)) return blobRegistry.get(url);
            for (var i = 0; i < softBlobs.length; i++) {
              if (softBlobs[i].url === url) return softBlobs[i].blob;
            }
            return null;
          }

          function sendBlobToNative(blob, fileName, mimeType) {
            if (!blob) return false;
            try {
              var reader = new FileReader();
              reader.onload = function (e) {
                try {
                  var result = String(e.target && e.target.result || '');
                  var comma = result.indexOf(',');
                  var base64 = comma >= 0 ? result.slice(comma + 1) : result;
                  var mime = mimeType || blob.type || 'application/octet-stream';
                  var name = fileName || 'download';
                  AndroidBridge.saveBlob(base64, mime, name);
                } catch (err) {}
              };
              reader.onerror = function () {};
              reader.readAsDataURL(blob);
              return true;
            } catch (e) {
              return false;
            }
          }

          function handleBlobOrData(url, fileName, mimeType) {
            var blob = findBlob(url);
            if (blob) return sendBlobToNative(blob, fileName, mimeType);
            // Unknown blob: URL (e.g. created before the hook, or data: URL) — fetch it.
            try {
              fetch(url)
                .then(function (r) { return r.blob(); })
                .then(function (b) {
                  sendBlobToNative(b, fileName, mimeType || b.type || '');
                })
                .catch(function () {});
              return true;
            } catch (e) {
              return false;
            }
          }

          // Exposed so the native DownloadListener can route blob:/data: URLs here.
          window.__arenaHandleDownload = function (url, fileName, mimeType) {
            if (/^blob:/i.test(url) || /^data:/i.test(url)) {
              return handleBlobOrData(url, fileName || '', mimeType || '');
            }
            return false; // http(s): handled natively by the DownloadListener
          };

          // Intercept clicks on <a download> links pointing at blob:/data: URLs.
          // The capture phase runs before the page's own handlers and before the
          // default navigation, so the download can be captured reliably.
          document.addEventListener('click', function (ev) {
            var el = ev.target;
            while (el && el !== document && !(el.tagName === 'A')) el = el.parentElement;
            var a = (el && el.tagName === 'A') ? el : null;
            if (!a) return;
            if (!a.hasAttribute('download')) return;
            var href = a.getAttribute('href') || '';
            var url = a.href || href;
            if (!/^blob:/i.test(url) && !/^data:/i.test(url)) return;
            var fileName = a.getAttribute('download') || '';
            var mimeType = a.getAttribute('type') || '';
            ev.preventDefault();
            ev.stopPropagation();
            window.__arenaHandleDownload(url, fileName, mimeType);
          }, true);
        })();
    """.trimIndent()
}

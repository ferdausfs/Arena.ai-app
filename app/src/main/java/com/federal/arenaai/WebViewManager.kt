package com.federal.arenaai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.Executors

object WebViewManager {
    const val TAG = "ArenaWebView"

    private var webView: WebView? = null
    private var mutableContext: MutableContextWrapper? = null
    private var currentUrl: String = "https://arena.ai"
    private var activePopupDialog: Dialog? = null

    /**
     * Holds the pending <input type="file"> chooser callback. Must be invoked
     * EXACTLY ONCE with the chosen Uris (or null if cancelled).
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** Camera EXTRA_OUTPUT uri, if the last chooser offered a capture intent. */
    private var pendingCameraUri: Uri? = null

    /** WebView that currently owns [filePathCallback]; cancelled if that view dies. */
    private var fileChooserOwner: WebView? = null

    /**
     * Bridge to the Activity's registerForActivityResult launcher. The Activity
     * sets this so onShowFileChooser() can start the system picker and later
     * deliver the result via [deliverFileChooserResult].
     */
    var startFileChooser: ((Intent) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

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
            Log.e(TAG, "openInExternalBrowser failed for $uri", e)
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
    private fun applyCommonSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Clean User-Agent so Google OAuth and other providers allow in-app login
        settings.userAgentString = cleanUserAgent(settings.userAgentString)
        // Security: never expose the local file system (app only loads remote https).
        // content:// (FileProvider / system picker) still works via allowContentAccess.
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.allowContentAccess = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setGeolocationEnabled(false)
    }

    /**
     * File picker + download listener + JS blob bridge. Applied to BOTH the
     * main WebView and the popup WebView — a window.open() surface that lacks
     * these handlers is a silent upload/download black hole.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun attachFileTransfer(target: WebView) {
        try {
            target.removeJavascriptInterface(ArenaNativeBridge.JS_NAME)
        } catch (_: Exception) {}
        target.addJavascriptInterface(ArenaNativeBridge(), ArenaNativeBridge.JS_NAME)
        FileTransferSupport.installDocumentStartHook(target)
        target.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.d(
                TAG,
                "DownloadListener url=${url.take(160)} mime=$mimetype " +
                    "cd=$contentDisposition len=$contentLength ua=${userAgent?.take(40)}"
            )
            handleDownload(target, url, userAgent, contentDisposition, mimetype)
        }
        Log.d(TAG, "attachFileTransfer on ${target.hashCode()} startFileChooser=${startFileChooser != null}")
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

                applyCommonSettings(settings)

                // Configure cookies BEFORE loading any page
                configureCookies(this)
                attachFileTransfer(this)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return handleShouldOverride(view, request, mutableContext?.baseContext)
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
                        FileTransferSupport.injectDownloadHook(view)
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
                        cancelPendingFileChooser()
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
                        if (newProgress >= 100) {
                            FileTransferSupport.injectDownloadHook(view)
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null) {
                            Log.d(
                                TAG,
                                "console.${consoleMessage.messageLevel()} " +
                                    "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} " +
                                    consoleMessage.message()
                            )
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        callback: ValueCallback<Array<Uri>>,
                        params: FileChooserParams
                    ): Boolean {
                        return showFileChooser(webView, callback, params)
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
                            Log.w(TAG, "onCreateWindow: no live Activity")
                            return false
                        }

                        val popupWebView = WebView(activity).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            applyCommonSettings(settings)
                            configureCookies(this)
                            attachFileTransfer(this)
                        }

                        var popupTargetUrl: String? = null

                        // DayNight-aware popup so it matches the app theme in dark mode.
                        val dialog = Dialog(activity, R.style.Arena_PopupDialog).apply {
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

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                if (newProgress >= 100) {
                                    FileTransferSupport.injectDownloadHook(view)
                                }
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                if (consoleMessage != null) {
                                    Log.d(
                                        TAG,
                                        "popup.console.${consoleMessage.messageLevel()} " +
                                            consoleMessage.message()
                                    )
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams
                            ): Boolean {
                                Log.d(TAG, "popup onShowFileChooser")
                                return showFileChooser(webView, callback, params)
                            }
                        }

                        popupWebView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                return handleShouldOverride(v, request, activity)
                            }

                            override fun onPageFinished(v: WebView?, url: String?) {
                                super.onPageFinished(v, url)
                                flushCookies()
                                FileTransferSupport.injectDownloadHook(v)

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
                            }

                            override fun onRenderProcessGone(v: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                                cancelFileChooserIfOwnedBy(popupWebView)
                                try { popupWebView.destroy() } catch (_: Exception) {}
                                try { dialog.dismiss() } catch (_: Exception) {}
                                return true
                            }
                        }

                        dialog.setOnDismissListener {
                            flushCookies()
                            if (activePopupDialog === dialog) {
                                activePopupDialog = null
                            }
                            cancelFileChooserIfOwnedBy(popupWebView)
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

    /**
     * Shared navigation policy for the main and popup WebViews.
     * OAuth allow-list, hardened intent://, and blob: download intercept.
     */
    private fun handleShouldOverride(
        view: WebView?,
        request: WebResourceRequest?,
        fallbackCtx: Context?
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val ctx = view?.context ?: fallbackCtx

        // In-page blob navigation (some SPAs assign location.href = blobUrl).
        if (scheme == "blob") {
            Log.d(TAG, "shouldOverride blob: $uri")
            view?.let {
                val name = URLUtil.guessFileName(uri.toString(), null, null)
                FileTransferSupport.requestBlobFromPage(it, uri.toString(), name, null)
            }
            return true
        }

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

    /**
     * Handle <input type="file"> upload choosers. Launches the system
     * content/document picker (optionally with a camera capture extra) and
     * delivers the chosen Uris back to the page via [deliverFileChooserResult].
     *
     * Contract: if we return true we WILL invoke [callback] exactly once.
     * If we return false we must NOT invoke it (WebView cancels it).
     */
    private fun showFileChooser(
        webView: WebView?,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        Log.d(
            TAG,
            "onShowFileChooser mode=${params.mode} capture=${params.isCaptureEnabled} " +
                "accept=${params.acceptTypes?.joinToString()} " +
                "launcher=${startFileChooser != null} view=${webView?.hashCode()}"
        )

        // Release any previously pending callback before starting a new one.
        try {
            filePathCallback?.onReceiveValue(null)
        } catch (_: Exception) {}
        filePathCallback = null
        pendingCameraUri = null

        val launcher = startFileChooser
        if (launcher == null) {
            Log.e(TAG, "onShowFileChooser: startFileChooser is null — Activity not wired")
            return false
        }

        val ctx = webView?.context
            ?: mutableContext?.baseContext
            ?: FileTransferSupport.appContext()
        if (ctx == null) {
            Log.e(TAG, "onShowFileChooser: no Context")
            return false
        }

        val launch = try {
            FileTransferSupport.buildChooserLaunch(ctx, params)
        } catch (e: Exception) {
            Log.e(TAG, "buildChooserLaunch failed", e)
            return false
        }

        filePathCallback = callback
        fileChooserOwner = webView
        pendingCameraUri = launch.cameraOutputUri

        return try {
            launcher.invoke(launch.intent)
            Log.d(TAG, "file chooser launched cameraUri=${launch.cameraOutputUri}")
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "file chooser ActivityNotFoundException, retrying raw GET_CONTENT", e)
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                launcher.invoke(Intent.createChooser(fallback, ctx.getString(R.string.file_chooser_title)))
                true
            } catch (e2: Exception) {
                Log.e(TAG, "file chooser fallback failed", e2)
                filePathCallback = null
                fileChooserOwner = null
                pendingCameraUri = null
                try { callback.onReceiveValue(null) } catch (_: Exception) {}
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "file chooser launch failed", e)
            filePathCallback = null
            fileChooserOwner = null
            pendingCameraUri = null
            try { callback.onReceiveValue(null) } catch (_: Exception) {}
            true
        }
    }

    /**
     * Parse the system picker / camera result. Camera capture with EXTRA_OUTPUT
     * typically returns RESULT_OK with a null data uri — we fall back to the
     * FileProvider uri we created before launch.
     */
    fun consumePickerResult(resultCode: Int, data: Intent?): Array<Uri>? {
        val cameraUri = pendingCameraUri
        pendingCameraUri = null
        Log.d(
            TAG,
            "consumePickerResult resultCode=$resultCode data=$data " +
                "dataUri=${data?.data} clip=${data?.clipData?.itemCount} camera=$cameraUri"
        )
        if (resultCode != Activity.RESULT_OK) {
            return null
        }
        val clip = data?.clipData
        val fromClip = if (clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        } else {
            null
        }
        return when {
            fromClip != null -> fromClip
            data?.data != null -> arrayOf(data.data!!)
            cameraUri != null -> arrayOf(cameraUri)
            else -> null
        }
    }

    /**
     * Called by the Activity with the result of the system file picker.
     * Pass the chosen Uris, or null if the user cancelled (releases the callback).
     *
     * Selected content:// uris are copied into app cache and re-exposed via
     * FileProvider so the WebView renderer can still read them after the
     * picker's temporary grant expires.
     */
    fun deliverFileChooserResult(uris: Array<Uri>?) {
        val cb = filePathCallback
        filePathCallback = null
        fileChooserOwner = null
        if (cb == null) {
            Log.w(TAG, "deliverFileChooserResult: no pending callback (uris=${uris?.size})")
            return
        }
        if (uris.isNullOrEmpty()) {
            Log.d(TAG, "deliverFileChooserResult: cancelled / empty")
            try { cb.onReceiveValue(null) } catch (e: Exception) {
                Log.e(TAG, "onReceiveValue(null) failed", e)
            }
            return
        }
        val ctx = mutableContext?.baseContext ?: FileTransferSupport.appContext()
        if (ctx == null) {
            Log.w(TAG, "deliverFileChooserResult: no context, passing raw uris")
            try { cb.onReceiveValue(uris) } catch (e: Exception) {
                Log.e(TAG, "onReceiveValue raw failed", e)
            }
            return
        }
        ioExecutor.execute {
            val copied = uris.map { uri ->
                FileTransferSupport.copyUriToAppCache(ctx, uri) ?: uri
            }.toTypedArray()
            for (uri in copied) {
                try {
                    ctx.grantUriPermission(
                        ctx.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            mainHandler.post {
                Log.d(TAG, "deliverFileChooserResult delivering ${copied.size} uri(s)")
                try {
                    cb.onReceiveValue(copied)
                } catch (e: Exception) {
                    Log.e(TAG, "onReceiveValue copied failed", e)
                }
            }
        }
    }

    /** Cancel the in-flight chooser only if [wv] is the one that started it. */
    fun cancelFileChooserIfOwnedBy(wv: WebView) {
        if (fileChooserOwner === wv) {
            Log.d(TAG, "cancelFileChooserIfOwnedBy ${wv.hashCode()}")
            cancelPendingFileChooser()
        }
    }

    /** Cancel any in-flight file chooser (e.g. on Activity destroy). */
    fun cancelPendingFileChooser() {
        val cb = filePathCallback
        filePathCallback = null
        fileChooserOwner = null
        pendingCameraUri = null
        if (cb != null) {
            Log.d(TAG, "cancelPendingFileChooser")
            try { cb.onReceiveValue(null) } catch (_: Exception) {}
        }
    }

    /**
     * http(s) → system DownloadManager (cookies + UA forwarded).
     * blob: / data: → in-page JS reads the bytes and calls ArenaNative.saveBlob.
     */
    private fun handleDownload(
        source: WebView,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?
    ) {
        val ctx = source.context ?: mutableContext?.baseContext ?: FileTransferSupport.appContext()
        if (ctx == null) {
            Log.e(TAG, "handleDownload: no Context")
            return
        }

        val scheme = Uri.parse(url).scheme?.lowercase()
        Log.d(TAG, "handleDownload scheme=$scheme mime=$mimetype cd=$contentDisposition")

        if (scheme == "data") {
            val fileName = FileTransferSupport.sanitizeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimetype)
            )
            Log.d(TAG, "handleDownload: data: URL — native decode first")
            if (FileTransferSupport.saveDataUrl(ctx, url, fileName)) return
            FileTransferSupport.requestBlobFromPage(source, url, fileName, mimetype)
            return
        }

        if (scheme == "blob") {
            val fileName = FileTransferSupport.sanitizeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimetype)
            )
            Log.d(TAG, "handleDownload: routing blob: to in-page JS name=$fileName")
            FileTransferSupport.requestBlobFromPage(source, url, fileName, mimetype)
            return
        }

        if (!URLUtil.isNetworkUrl(url)) {
            Log.w(TAG, "handleDownload: non-network url, giving up ($url)")
            return
        }

        val ok = FileTransferSupport.enqueueHttpDownload(
            ctx, url, userAgent, contentDisposition, mimetype
        )
        if (!ok) {
            try {
                openInExternalBrowser(ctx, Uri.parse(url))
            } catch (_: Exception) {}
        }
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
}

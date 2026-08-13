package com.federal.arenaai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
     * Bumped every time the pending chooser is replaced or cancelled. The
     * async URI-copy delivery in [deliverFileChooserResult] checks this before
     * invoking the callback so a superseded chooser can never double-invoke a
     * ValueCallback (the ValueCallback contract requires exactly-once).
     */
    private var fileChooserGeneration = 0L

    /**
     * Bridge to the Activity's registerForActivityResult launcher. The Activity
     * sets this so onShowFileChooser() can start the system picker and later
     * deliver the result via [deliverFileChooserResult].
     */
    var startFileChooser: ((Intent) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    /**
     * Dedicated executor for cookie flushes. flushCookies() runs on EVERY
     * onPageFinished — sharing the single ioExecutor (blob saves, upload
     * copies, screenshot encodes) would queue the flush behind potentially
     * seconds of heavy I/O, and if the process dies in between the session
     * cookies are lost ("I have to log in again"). This keeps the flush always
     * fast.
     */
    private val cookieExecutor = Executors.newSingleThreadExecutor()

    /** True while the Activity is visible (set from onForegrounded/onBackgrounded). */
    private var appVisible = true

    // ---------------------------------------------------------------------
    // Responsiveness throttles.
    // flushCookies() fires on EVERY page load; dirSize() walks the whole
    // WebView cache dir (potentially thousands of small files, seconds of
    // I/O). Both are debounced so they can never queue up and make the app
    // feel slow.
    // ---------------------------------------------------------------------
    @Volatile private var lastCookieFlushMs = 0L
    @Volatile private var lastCacheCheckMs = 0L
    /** After a severe memory trim, skip extra work for a while (system is stressed). */
    @Volatile private var lastSevereTrimMs = 0L

    private const val COOKIE_FLUSH_MIN_INTERVAL_MS = 3_000L
    private const val CACHE_CHECK_MIN_INTERVAL_MS = 10 * 60 * 1000L
    private const val SEVERE_TRIM_GRACE_MS = 60_000L

    // ---------------------------------------------------------------------
    // Self-diagnostics dialogs ("debugging tool").
    // When the phone struggles (memory pressure, slow load, renderer closed
    // by the system) the app itself pops up a dialog offering to clean the
    // cache / reload — so the user can react instead of watching the phone
    // freeze. Debounced so it never nags.
    // ---------------------------------------------------------------------
    private const val MEMORY_DIALOG_MIN_INTERVAL_MS = 2 * 60 * 1000L
    private const val SLOW_LOAD_TIMEOUT_MS = 20_000L
    @Volatile private var lastMemoryDialogMs = 0L
    private var slowLoadDialogShown = false

    private val slowLoadCheck = Runnable {
        slowLoadCheckPosted = false
        val wv = webView ?: return@Runnable
        if (!appVisible) return@Runnable
        if (wv.progress >= 100) return@Runnable
        if (slowLoadDialogShown) return@Runnable
        slowLoadDialogShown = true
        showDiagnosticDialog(
            title = "Loading is slow",
            message = "Arena.ai is taking longer than usual to load. " +
                "Cleaning the cache can speed it up.",
            positive = "Clean & reload",
            positiveAction = {
                cleanCacheAndStaging()
                mainHandler.postDelayed({ webView?.reload() }, 500L)
            }
        )
    }
    private var slowLoadCheckPosted = false

    /** Show a cache-clean / reload dialog from the app itself (debug tool). */
    private fun showDiagnosticDialog(
        title: String,
        message: String,
        positive: String,
        positiveAction: () -> Unit
    ) {
        if (!appVisible) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMemoryDialogMs < MEMORY_DIALOG_MIN_INTERVAL_MS) return
        lastMemoryDialogMs = now
        mainHandler.post {
            val activity = getActivityFromContext(mutableContext) ?: return@post
            if (activity.isFinishing || activity.isDestroyed) return@post
            try {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage("$message\n\nTip: you can also close unused apps from Recents.")
                    .setPositiveButton(positive) { _, _ -> positiveAction() }
                    .setNegativeButton("Later", null)
                    .setCancelable(true)
                    .show()
            } catch (_: Exception) {}
        }
    }

    /** Clear the WebView HTTP cache + our staging dirs (uploads/camera/blobs). */
    private fun cleanCacheAndStaging() {
        mainHandler.post {
            try { webView?.clearCache(false) } catch (_: Exception) {}
            try { webView?.clearHistory() } catch (_: Exception) {}
        }
        ioExecutor.execute {
            try {
                val ctx = mutableContext?.baseContext
                if (ctx != null) {
                    for (dirName in listOf("uploads", "camera", "blob-in")) {
                        val dir = File(ctx.cacheDir, dirName)
                        dir.listFiles()?.forEach { f ->
                            try { f.delete() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
            mainHandler.post {
                try {
                    android.widget.Toast.makeText(
                        (mutableContext?.baseContext ?: FileTransferSupport.appContext()),
                        "Cache cleaned",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {}
            }
        }
    }

    private fun scheduleSlowLoadCheck() {
        if (slowLoadCheckPosted) return
        slowLoadCheckPosted = true
        slowLoadDialogShown = false
        lifecycleHandler.removeCallbacks(slowLoadCheck)
        lifecycleHandler.postDelayed(slowLoadCheck, SLOW_LOAD_TIMEOUT_MS)
    }

    private fun cancelSlowLoadCheck() {
        lifecycleHandler.removeCallbacks(slowLoadCheck)
        slowLoadCheckPosted = false
    }

    /**
     * Renderer crash-loop guard. On low-RAM phones the renderer process can be
     * OOM-killed repeatedly; auto-reloading the heavy page each time produces
     * an endless "freeze → reload → freeze" loop that reads as "app not
     * responding". After 2 crashes within 30 s we stop auto-reloading and show
     * a lightweight page instead.
     */
    private var rendererCrashCount = 0
    private var lastRendererCrashMs = 0L

    /**
     * Background CPU governor.
     *
     * The WebView renderer keeps animating and running JS timers as long as it
     * is not paused. If the app stays hidden (screen off / switched away) that
     * burns CPU/GPU continuously — on low-RAM phones the whole device slows
     * down ("the 5 MB app freezes my phone"). Pausing the WebView stops
     * rendering and JS timers; the network connection itself stays open and the
     * session survives in cookies, so returning is instant.
     *
     * Pausing is DELAYED (45 s) so quick app switches and system pickers never
     * pause the page, and the session/streams stay live.
     */
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val pauseRunnable = Runnable {
        if (webViewPaused) return@Runnable
        webViewPaused = true
        try { webView?.onPause() } catch (_: Exception) {}
        try { webView?.pauseTimers() } catch (_: Exception) {}
        Log.d(TAG, "WebView paused (hidden > $PAUSE_AFTER_BACKGROUND_MS ms)")
    }
    private var webViewPaused = false

    private const val PAUSE_AFTER_BACKGROUND_MS = 45_000L

    // ---------------------------------------------------------------------
    // Offline shell: last-viewed snapshot (stored on disk, shown on failure)
    // ---------------------------------------------------------------------
    private const val OFFLINE_DIR = "offline"
    private const val OFFLINE_IMAGE = "offline_shell.jpg"
    private const val OFFLINE_PREFS = "offline_shell"
    /** Skip capturing very large viewports (keeps RAM spike + file size small). */
    private const val OFFLINE_MAX_PIXELS = 4_000_000L

    /**
     * Precomputed base64 JPEG of the last successful arena view. Computed on a
     * background thread at capture time so the offline-shell error path never
     * does file I/O on the UI thread, and embedded as a data URI so the image
     * renders on every device (content:// subresource loading is unreliable).
     */
    @Volatile private var offlineShellBase64: String? = null

    // ---------------------------------------------------------------------
    // Prefetch: warm the disk cache with likely-next pages (once per session)
    // ---------------------------------------------------------------------
    private var prefetchStarted = false
    private val prefetchUrls = listOf(
        "https://arena.ai/leaderboard",
        "https://arena.ai/agent"
    )

    // ---------------------------------------------------------------------
    // Cache size limit (bounded storage use)
    // ---------------------------------------------------------------------
    private const val CACHE_SIZE_LIMIT_BYTES = 80L * 1024 * 1024

    // ---------------------------------------------------------------------
    // ANR watchdog.
    //
    // Android shows "App not responding" when the MAIN thread cannot process
    // input for ~5 s. That can be triggered by external factors (system under
    // memory pressure, GC storms, other apps) that we cannot fully prevent —
    // but when it happens we must be able to SEE where the main thread was
    // stuck. A 1 s heartbeat runs on the main thread; a background check
    // compares it against the clock and, if the main thread stopped updating
    // it, logs the main thread's stack trace ("POSSIBLE ANR").
    // ---------------------------------------------------------------------
    private val watchdogHeartbeatHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastMainThreadBeatMs = 0L
    private var watchdogStarted = false
    private val watchdogBeat = object : Runnable {
        override fun run() {
            lastMainThreadBeatMs = SystemClock.uptimeMillis()
            // Only keep beating while the app is visible. In the background a
            // 1 Hz main-thread message would keep the main looper from ever
            // truly idling (tiny but continuous CPU wakeups on a phone we are
            // trying to keep responsive); the background 5 s CHECK still runs
            // and just reads the stale timestamp.
            if (appVisible) {
                watchdogHeartbeatHandler.postDelayed(this, 1000L)
            }
        }
    }

    /** Start the 1 s heartbeat + 5 s background check (once). */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        lastMainThreadBeatMs = SystemClock.uptimeMillis()
        watchdogHeartbeatHandler.post(watchdogBeat)
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            // The heartbeat pauses when the app is hidden (see watchdogBeat),
            // so a stale timestamp is expected in the background — only report
            // blockages while the app is actually visible.
            if (!appVisible) return@scheduleWithFixedDelay
            val blockedMs = SystemClock.uptimeMillis() - lastMainThreadBeatMs
            if (blockedMs > 8000L) {
                Log.w(TAG, "POSSIBLE ANR: main thread blocked ~${blockedMs / 1000}s — stack:")
                try {
                    Looper.getMainLooper().thread.stackTrace.take(20).forEach {
                        Log.w(TAG, "    at $it")
                    }
                } catch (_: Exception) {}
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    /** Shown instead of auto-reloading when the renderer crash-loops (OOM). */
    private val LOW_MEMORY_PAGE_HTML = """
        <html><body style="background:#1a1a2e;color:#fff;font-family:sans-serif;
        display:flex;flex-direction:column;align-items:center;justify-content:center;
        height:100vh;margin:0;padding:20px;box-sizing:border-box;text-align:center;">
        <h2 style="margin:0 0 12px;">Device is running low on memory</h2>
        <p style="margin:0 0 20px;color:#cbd5e1;max-width:420px;">The page was closed by
        Android to free up memory. Close some other apps and try again.</p>
        <a href="https://arena.ai" style="background:#4fc3f7;color:#0f172a;
        padding:12px 28px;border-radius:8px;text-decoration:none;font-weight:600;">Retry</a>
        </body></html>
    """.trimIndent()

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
     * Runs on its own dedicated executor: flush() can do disk I/O, and this is
     * called from onPageFinished / onPause (UI thread) — but it must NEVER be
     * queued behind blob saves/uploads on the shared ioExecutor, or a process
     * kill in between would lose the session cookies.
     */
    fun flushCookies() {
        // Debounce: onPageFinished fires on every main-frame navigation and
        // SPA reloads can trigger several in quick succession. A flush right
        // after a flush is redundant disk I/O — the previous one already
        // persisted the same cookie state. At most one flush per 3 s.
        val now = SystemClock.uptimeMillis()
        if (now - lastCookieFlushMs < COOKIE_FLUSH_MIN_INTERVAL_MS) return
        lastCookieFlushMs = now
        cookieExecutor.execute {
            try {
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
                // CookieManager may not be initialized yet
            }
        }
    }

    /**
     * Called from Application.onTrimMemory (device low on RAM).
     *
     * 1. Forward to the WebView renderer (reflective: `WebView.onTrimMemory` is
     *    not a public API in the compile SDK) so the renderer frees its own
     *    caches BEFORE the system OOM-kills it — a killed renderer means a full
     *    page reload, which is exactly the "freeze → not responding" cycle.
     * 2. On SEVERE trims, drop the disk cache + navigation history too. Both are
     *    stored on the phone's storage (not RAM), but clearing them lets
     *    Chromium reclaim memory-backed bookkeeping — the app gives storage back
     *    to the OS instead of letting RAM pressure build up.
     */
    fun onTrimMemory(level: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val m = WebView::class.java.getMethod(
                    "onTrimMemory", Int::class.javaPrimitiveType
                )
                m.invoke(null, level)
            } catch (_: Throwable) {}
        }
        when {
            // TRIM_MEMORY_RUNNING_CRITICAL (15) and everything above it —
            // including background TRIM_MEMORY_COMPLETE (80) — counts as severe.
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(TAG, "onTrimMemory severe (level=$level) — clearing disk cache + history")
                lastSevereTrimMs = SystemClock.uptimeMillis()
                // WebView methods MUST run on the main thread (they are View
                // methods); scheduling them from a background thread can crash
                // with "Calling View methods from another thread".
                mainHandler.post {
                    try { webView?.clearCache(false) } catch (_: Exception) {}
                    try { webView?.clearHistory() } catch (_: Exception) {}
                }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.d(TAG, "onTrimMemory level=$level — WebView trimmed")
                // Free the in-memory offline snapshot JPEG (it can be
                // re-captured on the next backgrounding) — every KB counts
                // under memory pressure.
                offlineShellBase64 = null
                // Storage budget: keep the disk cache bounded.
                enforceCacheLimit()
                // Debug tool: tell the user the phone is under pressure and
                // offer a one-tap cache clean (debounced to once/2 min).
                showDiagnosticDialog(
                    title = "Phone is under memory pressure",
                    message = "Your phone is running low on memory. Cleaning the " +
                        "app's cache can help the page load and respond faster.",
                    positive = "Clean cache",
                    positiveAction = { cleanCacheAndStaging() }
                )
            }
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
        // IndexedDB (used by SPA chat history like arena.ai's) requires the
        // HTML5 database flag in WebView; without it the site may fall back to
        // less efficient storage or re-fetch on every load.
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Clean User-Agent so Google OAuth and other providers allow in-app login
        settings.userAgentString = cleanUserAgent(settings.userAgentString)
        // Security: never expose the local file system (app only loads remote https).
        // content:// (FileProvider / system picker) still works via allowContentAccess.
        settings.allowFileAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.setAllowContentAccess(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        // Disk-first caching: Chromium keeps its HTTP cache + IndexedDB +
        // localStorage ON THE PHONE'S STORAGE (app cache dir), not in RAM, and
        // LOAD_DEFAULT reuses it across sessions — so returning to the app
        // re-renders from disk instead of re-downloading the whole SPA.
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

    /**
     * Renderer priority strategy (API 26+).
     *
     * Default is RENDERER_PRIORITY_IMPORTANT: the renderer is protected from
     * being OOM-killed, so under memory pressure the SYSTEM sacrifices other
     * apps — and once there is nothing left to kill, the whole phone freezes
     * ("the phone directly shuts down" instead of an ANR dialog). That is the
     * worst outcome.
     *
     * On low-RAM devices (< 512 MB heap) we deliberately set the renderer to
     * RENDERER_PRIORITY_WAIVED (waived while not visible too): under pressure
     * the RENDERER dies first — we handle it in onRenderProcessGone (with the
     * crash-loop guard) and the page reloads from the disk cache. The phone
     * stays responsive; the worst case is a page reload, never a frozen phone.
     * Devices with >= 512 MB heap keep the platform default.
     */
    private fun applyRendererPolicy(wv: WebView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctx = mutableContext?.baseContext ?: return
        val memoryClass = memoryClassOf(ctx)
        try {
            if (memoryClass < 512) {
                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                Log.w(
                    TAG,
                    "low-RAM device (heap $memoryClass MB): renderer WAIVED — " +
                        "page may reload under memory pressure, phone stays responsive"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyRendererPolicy failed", e)
        }
    }

    /** The app's dalvik heap class in MB (a proxy for "how much RAM this phone has"). */
    private fun memoryClassOf(ctx: Context): Int {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.memoryClass ?: 256
        } catch (_: Exception) {
            256
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getWebView(context: Context): WebView {
        if (webView == null) {
            startWatchdog()
            // Read the last offline snapshot from disk (fresh process launch) —
            // fills the in-memory base64 so the offline shell works even when
            // the app was restarted while offline.
            loadOfflineSnapshotFromDisk(context)
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
                applyRendererPolicy(this)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return handleShouldOverride(view, request, mutableContext?.baseContext)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { currentUrl = it }
                        if (view === webView) {
                            scheduleSlowLoadCheck()
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { currentUrl = it }
                        // A page loaded cleanly — the renderer is healthy again.
                        rendererCrashCount = 0
                        // Flush cookies after every page load to persist session
                        flushCookies()
                        FileTransferSupport.injectDownloadHook(view)
                        // Kick off the once-per-session prefetch. NOTE: the
                        // offline snapshot is intentionally NOT captured here —
                        // a full-screen bitmap + draw on the main thread right
                        // after every page load janks/ANRs low-end phones. It is
                        // captured only on backgrounding (see onBackgrounded).
                        if (view === webView) {
                            cancelSlowLoadCheck()
                            maybePrefetch()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame != true) return
                        // 4xx/5xx on the main frame is usually what the page maps
                        // to "something went wrong" — log it for diagnosis.
                        Log.w(
                            TAG,
                            "HTTP ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} " +
                                "for ${request.url}"
                        )
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame != true) return
                val failedUrl = request.url?.toString() ?: currentUrl
                // Avoid recursing if we are already showing the error page.
                if (failedUrl.startsWith("about:")) return

                // Offline shell: if we have a precomputed base64 snapshot of the
                // last view, show it (with a Retry bar) so the user still sees
                // their last chat while offline. Fall back to the plain error.
                val ctx = view?.context ?: mutableContext?.baseContext
                var snapshotB64 = offlineShellBase64
                // Fresh launch + immediate error: the async disk load may not
                // have finished — do a fast synchronous read as a last resort
                // (only for small snapshots, so this never janks the UI).
                if (snapshotB64 == null && ctx != null) {
                    try {
                        val file = offlineImageFile(ctx)
                        if (file.exists() && file.length() in 1..2_000_000) {
                            val bytes = file.readBytes()
                            snapshotB64 =
                                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            offlineShellBase64 = snapshotB64
                            Log.d(TAG, "offline snapshot loaded synchronously (${bytes.size} bytes)")
                        }
                    } catch (_: Exception) {}
                }
                if (snapshotB64 != null) {
                    val prefs = ctx?.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE)
                    val title = prefs?.getString("title", null)
                    val time = prefs?.getLong("time", 0L) ?: 0L
                    Log.w(TAG, "load failed ($failedUrl) — showing offline shell")
                    view?.loadDataWithBaseURL(
                        "https://arena.ai",
                        offlineShellHtml(snapshotB64, failedUrl, title, time),
                        "text/html",
                        "UTF-8",
                        null
                    )
                    return
                }

                // Escape before interpolating into the href attribute — a URL
                // containing quotes (e.g. a query param) must not break out.
                val escapedUrl = failedUrl
                    .replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")

                val html = """
                    <html><body style="background:#1a1a2e;color:#fff;font-family:sans-serif;
                    display:flex;flex-direction:column;align-items:center;justify-content:center;
                    height:100vh;margin:0;padding:20px;box-sizing:border-box;">
                    <h2>Connection Error</h2>
                    <p>Unable to load the page. Please check your internet connection.</p>
                    <p><a href="$escapedUrl" style="color:#4fc3f7;">Retry</a></p>
                    </body></html>
                """.trimIndent()
                        view?.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", failedUrl)
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        cancelPendingFileChooser()

                        val now = System.currentTimeMillis()
                        rendererCrashCount = if (now - lastRendererCrashMs < 30_000L) {
                            rendererCrashCount + 1
                        } else {
                            1
                        }
                        lastRendererCrashMs = now
                        Log.w(
                            TAG,
                            "onRenderProcessGone (view=${view?.hashCode()}) crash#$rendererCrashCount " +
                                "detail=${detail?.didCrash()}"
                        )

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

                        if (rendererCrashCount >= 2) {
                            // Crash loop (likely OOM on a low-RAM device):
                            // auto-reloading the heavy page again would just
                            // crash again — the "not responding" death spiral.
                            // Show a lightweight page and let the user retry.
                            Log.w(TAG, "renderer crash loop detected — showing low-memory page")
                            newWebView.loadDataWithBaseURL(
                                "https://arena.ai",
                                LOW_MEMORY_PAGE_HTML,
                                "text/html",
                                "UTF-8",
                                "https://arena.ai"
                            )
                            // Debug tool: offer a one-tap "clean cache & reload"
                            // instead of leaving the user on the retry page.
                            showDiagnosticDialog(
                                title = "Page was closed to free memory",
                                message = "Your phone was under memory pressure and " +
                                    "the page was closed. Clean the cache and reload?",
                                positive = "Clean & reload",
                                positiveAction = {
                                    cleanCacheAndStaging()
                                    mainHandler.postDelayed(
                                        { webView?.loadUrl(currentUrl) },
                                        500L
                                    )
                                }
                            )
                        } else {
                            newWebView.loadUrl(currentUrl)
                        }

                        listener?.onWebViewRecreated(newWebView)
                        return true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    // SPA pages (arena.ai is React) can emit hundreds of console
                    // messages; formatting strings for each on the UI thread
                    // janks the app. Always log errors/warnings, cap the rest.
                    private var consoleSpamCount = 0

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        if (newProgress < 10) consoleSpamCount = 0
                        if (newProgress >= 100) {
                            FileTransferSupport.injectDownloadHook(view)
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        val level = consoleMessage.messageLevel()
                        val verbose = level == ConsoleMessage.MessageLevel.LOG ||
                            level == ConsoleMessage.MessageLevel.DEBUG
                        if (!verbose || consoleSpamCount < 30) {
                            if (verbose) consoleSpamCount++
                            Log.d(
                                TAG,
                                "console.${level} " +
                                    "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} " +
                                    consoleMessage.message()
                            )
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        callback: ValueCallback<Array<Uri>>?,
                        params: FileChooserParams?
                    ): Boolean {
                        if (callback == null || params == null) return false
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
                        return createPopup(view, isDialog, isUserGesture, resultMsg)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    WebView.setWebContentsDebuggingEnabled(
                        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    )
                }

                // Renderer priority: the platform default is already
                // RENDERER_PRIORITY_IMPORTANT regardless of visibility, so no
                // explicit policy call is needed (setRendererPriorityPolicy is
                // an instance method, and the docs recommend not changing the
                // default unless handling renderer crashes).
                loadUrl(currentUrl)
            }
        } else {
            // After the activity was finished/swiped from recents while the
            // process survived (foreground service), the old WebView may be
            // destroyed or bound to a dead activity. Using it would crash or
            // show a blank screen ("clear recents makes it work again" —
            // because that kills the process and we start fresh). Detect and
            // recreate instead.
            val existing = webView
            // WebView has no isDestroyed getter; the reliable signal for a
            // stale singleton is that its host Activity is dead (finished /
            // swiped from recents while the process survived via the FGS).
            val staleActivity = existing?.let { getActivityFromContext(it.context)?.isDestroyed } == true
            if (existing == null || staleActivity) {
                Log.w(TAG, "getWebView: stale WebView detected, recreating")
                try { existing?.destroy() } catch (_: Exception) {}
                webView = null
                mutableContext = null
                return getWebView(context)
            }
            mutableContext?.baseContext = context
            webView?.let { configureCookies(it) }
        }
        return webView!!
    }

    /**
     * Shared window.open() handler used by BOTH the main and popup WebViews:
     * creates the in-app OAuth popup dialog with a fully-configured secondary
     * WebView (upload/download/bridge included).
     */
    private fun createPopup(
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

        // Guard: onRenderProcessGone destroys the view, then dialog.dismiss()
        // fires onDismiss which destroys it again — double destroy() on a
        // WebView can throw. Declared before the clients that reference it;
        // the view is a nullable var because the function is declared first.
        var popupDestroyed = false
        var popupWebViewRef: WebView? = null
        fun destroyPopupView() {
            if (popupDestroyed) return
            popupDestroyed = true
            try { popupWebViewRef?.destroy() } catch (_: Exception) {}
        }

        val popupWebView = WebView(activity).apply {
            popupWebViewRef = this
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
                            private var consoleSpamCount = 0

                            override fun onCloseWindow(window: WebView?) {
                                super.onCloseWindow(window)
                                try {
                                    dialog.dismiss()
                                } catch (_: Exception) {}
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                // A window.open() from inside a popup (nested
                                // OAuth window) would otherwise silently fail —
                                // delegate to the same popup handler.
                                return createPopup(view, isDialog, isUserGesture, resultMsg)
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                if (newProgress < 10) consoleSpamCount = 0
                                if (newProgress >= 100) {
                                    FileTransferSupport.injectDownloadHook(view)
                                }
                            }

                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                val level = consoleMessage.messageLevel()
                                val verbose = level == ConsoleMessage.MessageLevel.LOG ||
                                    level == ConsoleMessage.MessageLevel.DEBUG
                                if (!verbose || consoleSpamCount < 30) {
                                    if (verbose) consoleSpamCount++
                                    Log.d(
                                        TAG,
                                        "popup.console.${level} " +
                                            consoleMessage.message()
                                    )
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                callback: ValueCallback<Array<Uri>>?,
                                params: FileChooserParams?
                            ): Boolean {
                                Log.d(TAG, "popup onShowFileChooser")
                                if (callback == null || params == null) return false
                                return showFileChooser(webView, callback, params)
                            }
                        }

                        popupWebView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                                return handleShouldOverride(v, request, activity)
                            }

                            override fun onReceivedHttpError(
                                v: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(v, request, errorResponse)
                                if (request?.isForMainFrame != true) return
                                Log.w(
                                    TAG,
                                    "popup HTTP ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} " +
                                        "for ${request.url}"
                                )
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
                                destroyPopupView()
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
                            destroyPopupView()

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
        // Only intercept MAIN-frame blob navigations. Subframe loads (e.g. a
        // generated PDF/HTML preview shown in an <iframe src="blob:...">) must be
        // left to the renderer — intercepting them would save the blob as a
        // download and break the preview.
        if (scheme == "blob") {
            if (request?.isForMainFrame != true) return false
            Log.d(TAG, "shouldOverride blob (main frame): $uri")
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
        fileChooserGeneration++

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
        val fromClip: Array<Uri>? = if (clip != null && clip.itemCount > 0) {
            (0 until clip.itemCount).mapNotNull { i -> clip.getItemAt(i).uri }.toTypedArray()
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val single: Uri? = data?.data
        return when {
            fromClip != null -> fromClip
            single != null -> arrayOf(single)
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
        val generation = fileChooserGeneration
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
                // A newer chooser (or a cancel) superseded this one while we were
                // copying — the old callback was already released with null, so
                // delivering here would double-invoke it. Skip.
                if (generation != fileChooserGeneration) {
                    Log.w(TAG, "deliverFileChooserResult: chooser superseded, dropping ${copied.size} uri(s)")
                    return@post
                }
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
        fileChooserGeneration++
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

    /**
     * Activity paused (screen off / app switched away). Deliberately do NOT
     * pause the WebView: the app runs a foreground service whose entire purpose
     * is keeping the arena.ai session (SSE streams, websockets, fetch) alive in
     * the background. Calling WebView.onPause() here kills in-flight
     * connections, so the first message sent after returning to the app fails
     * with "something went wrong" and only a manual resend works. Cookies are
     * flushed so the session persists if the process is killed.
     */
    fun onPause() {
        flushCookies()
    }

    /** Activity resumed — nothing to undo since we never paused the WebView. */
    fun onResume() {
        // Intentionally empty (see onPause). Keeping the WebView un-paused is
        // what keeps the session/streams alive across backgrounding.
    }

    /**
     * App fully hidden (onStop): schedule the delayed background pause so the
     * renderer stops burning CPU once the app has been away for a while. Also
     * saves the offline snapshot of what the user was looking at, and checks
     * the cache-size budget.
     */
    fun onBackgrounded() {
        appVisible = false
        captureOfflineSnapshot()
        enforceCacheLimit()
        lifecycleHandler.removeCallbacks(pauseRunnable)
        lifecycleHandler.postDelayed(pauseRunnable, PAUSE_AFTER_BACKGROUND_MS)
    }

    /** App visible again (onStart): cancel any pending pause, resume if paused. */
    fun onForegrounded() {
        appVisible = true
        lifecycleHandler.removeCallbacks(pauseRunnable)
        if (webViewPaused) {
            webViewPaused = false
            try { webView?.resumeTimers() } catch (_: Exception) {}
            try { webView?.onResume() } catch (_: Exception) {}
            Log.d(TAG, "WebView resumed (foreground)")
        }
    }

    // =====================================================================
    // OFFLINE SHELL — the last successfully-loaded arena view is stored on
    // disk (viewport screenshot + URL). When the main frame fails to load
    // (no network, server down), that saved view is shown instead of a blank
    // error, so the user can still SEE their last chat without a connection.
    // =====================================================================

    private fun offlineDir(context: Context): File =
        File(context.cacheDir, OFFLINE_DIR).apply { mkdirs() }

    private fun offlineImageFile(context: Context): File =
        File(offlineDir(context), OFFLINE_IMAGE)

    /**
     * Capture the current viewport to a JPEG. Only the bitmap allocation +
     * view.draw() run on the UI thread (they must); the JPEG compression, file
     * write and base64 encoding run on a background thread so page interaction
     * is never janked by a full-screen screenshot.
     */
    private fun captureOfflineSnapshot() {
        val view = webView ?: return
        if (webViewPaused) return
        val ctx = mutableContext?.baseContext ?: return
        val url = view.url ?: return
        if (!FileTransferSupport.isArenaUrl(url)) return
        // On very low-RAM phones the ~8-16 MB bitmap allocation + draw pass
        // itself can push the device over the edge — skip the offline snapshot
        // there entirely (the phone staying responsive matters more than an
        // offline preview).
        if (memoryClassOf(ctx) < 256) {
            Log.d(TAG, "offline snapshot skipped (very low-RAM device)")
            return
        }
        // The system just told us memory is critically low — a ~10 MB bitmap
        // allocation + draw pass right now would add pressure to a phone that
        // is already struggling. Skip; the snapshot is re-captured later.
        if (SystemClock.uptimeMillis() - lastSevereTrimMs < SEVERE_TRIM_GRACE_MS) {
            Log.d(TAG, "offline snapshot skipped (recent memory pressure)")
            return
        }
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return
        if (w.toLong() * h.toLong() > OFFLINE_MAX_PIXELS) return
        val title = view.title ?: ""
        val timeMs = System.currentTimeMillis()
        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            ioExecutor.execute {
                try {
                    val file = offlineImageFile(ctx)
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    }
                    val bytes = file.readBytes()
                    offlineShellBase64 =
                        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    ctx.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE).edit()
                        .putString("url", url)
                        .putString("title", title)
                        .putLong("time", timeMs)
                        .apply()
                    Log.d(TAG, "offline snapshot saved (${w}x$h) for $url")
                } catch (e: Exception) {
                    Log.e(TAG, "captureOfflineSnapshot io failed", e)
                } finally {
                    try { bitmap.recycle() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureOfflineSnapshot draw failed", e)
        }
    }

    /**
     * Load the saved snapshot from disk into memory (base64). This is what
     * makes the offline shell work across process restarts: capture writes the
     * JPEG to disk, and a later fresh launch reads it back — the error path
     * must not do file I/O on the main thread, so this runs on the IO executor
     * and just fills the @Volatile field.
     */
    private fun loadOfflineSnapshotFromDisk(ctx: Context) {
        ioExecutor.execute {
            try {
                val file = offlineImageFile(ctx)
                if (!file.exists() || file.length() == 0L) return@execute
                if (file.length() > 8L * 1024 * 1024) {
                    Log.w(TAG, "offline snapshot too large (${file.length()}) — ignoring")
                    return@execute
                }
                val bytes = file.readBytes()
                offlineShellBase64 =
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.d(TAG, "offline snapshot loaded from disk (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "loadOfflineSnapshotFromDisk failed", e)
            }
        }
    }

    /** HTML page: stored snapshot (base64 data URI) + "You're offline" + Retry. */
    private fun offlineShellHtml(base64Image: String, failedUrl: String, title: String?, timeMs: Long): String {
        fun esc(s: String): String = s
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val time = if (timeMs > 0) {
            try {
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timeMs))
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        val t = title?.takeIf { it.isNotBlank() } ?: "saved view"
        return """
            <html><body style="margin:0;background:#1a1a2e;color:#fff;font-family:sans-serif;">
            <div style="padding:12px 16px;background:#0f172a;display:flex;align-items:center;gap:12px;flex-wrap:wrap;">
              <div style="flex:1;min-width:200px;">
                <div style="font-weight:700;">You're offline</div>
                <div style="color:#94a3b8;font-size:13px;">${esc(t)} · $time</div>
              </div>
              <a href="${esc(failedUrl)}" style="background:#4fc3f7;color:#0f172a;
                 padding:10px 18px;border-radius:8px;text-decoration:none;font-weight:600;">Retry</a>
            </div>
            <img src="$base64Image" style="width:100%;display:block;"/>
            </body></html>
        """.trimIndent()
    }

    // =====================================================================
    // PREFETCH — after the app has idled on arena.ai, load a couple of
    // high-value pages in a hidden WebView so their JS/CSS/images land in the
    // shared disk cache. Opening them next time then renders from storage
    // instead of the network (much faster). Guarded: skipped on low-RAM
    // devices and on metered (mobile-data) connections.
    // =====================================================================

    private fun maybePrefetch() {
        if (prefetchStarted) return
        prefetchStarted = true
        val ctx = mutableContext?.baseContext ?: return
        if (isMeteredConnection(ctx)) {
            Log.d(TAG, "prefetch skipped (metered connection)")
            return
        }
        if (!hasEnoughMemory(ctx)) {
            Log.d(TAG, "prefetch skipped (low-RAM device)")
            return
        }
        lifecycleHandler.postDelayed({ runPrefetch(ctx) }, 8000L)
    }

    private fun isMeteredConnection(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.isActiveNetworkMetered == true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEnoughMemory(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            // A second, hidden WebView loading arena.ai pages doubles the
            // renderer's memory footprint. Only prefetch on devices with
            // enough heap (>= 512 MB); on low-RAM phones the extra load storm
            // would cause exactly the ANR/freeze we are trying to prevent.
            (am?.memoryClass ?: 0) >= 512 // MB
        } catch (_: Exception) {
            false
        }
    }

    /** Load the prefetch URLs sequentially in a hidden WebView, then destroy. */
    private fun runPrefetch(ctx: Context) {
        // Never prefetch while the app is hidden — spawning a second WebView
        // that loads two heavy pages in the background is exactly the CPU/RAM
        // load that freezes low-end phones.
        if (!appVisible) {
            Log.d(TAG, "prefetch skipped (app not visible)")
            return
        }
        Log.d(TAG, "prefetch starting: $prefetchUrls")
        val prefetchView = try {
            WebView(ctx.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "prefetch: cannot create WebView", e)
            return
        }
        prefetchView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        var destroyed = false
        fun destroyView() {
            if (destroyed) return
            destroyed = true
            try { prefetchView.destroy() } catch (_: Exception) {}
        }
        var index = 0
        prefetchView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString()
                // Only ever prefetch arena.ai pages; anything else (a redirect
                // off-site, an external link) aborts the prefetch chain.
                if (u != null && FileTransferSupport.isArenaUrl(u)) return false
                Log.w(TAG, "prefetch blocked external: $u")
                destroyView()
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.d(TAG, "prefetch cached: $url")
                index++
                if (index < prefetchUrls.size) {
                    view?.loadUrl(prefetchUrls[index])
                } else {
                    destroyView()
                }
            }
        }
        // Safety timeout: never let the hidden WebView live forever.
        lifecycleHandler.postDelayed({ destroyView() }, 60_000L)
        try {
            prefetchView.loadUrl(prefetchUrls[0])
        } catch (e: Exception) {
            Log.e(TAG, "prefetch load failed", e)
            destroyView()
        }
    }

    // =====================================================================
    // CACHE SIZE LIMIT — Chromium stores its HTTP cache on the phone's
    // storage. Measure the app cache dir and, when it exceeds the budget,
    // clear the HTTP cache (cookies/localStorage are untouched) on a
    // background thread. Storage use stays bounded; RAM is not involved.
    // =====================================================================

    /**
     * Staging dirs whose size must NOT count toward the WebView HTTP-cache
     * budget — they hold picked uploads, camera captures, in-flight blob
     * downloads and the offline snapshot, and are reclaimed by their own
     * pruning (pruneStagingCache) / overwrite logic.
     */
    private val cacheExcludeDirs = setOf("uploads", "camera", "blob-in", "offline")

    /** Public: check the cache size now (called from trims and backgrounding). */
    fun enforceCacheLimit() {
        val ctx = mutableContext?.baseContext ?: return
        // Debounced: dirSize() recursively walks the WHOLE WebView cache dir —
        // thousands of small files can take seconds of I/O. It runs on the
        // shared ioExecutor, so an unfettered walk would stall uploads/blob
        // saves and make the app feel slow. Check at most once per 10 minutes,
        // and skip entirely right after a severe memory trim (the trim already
        // cleared the cache).
        val now = SystemClock.uptimeMillis()
        if (now - lastCacheCheckMs < CACHE_CHECK_MIN_INTERVAL_MS) return
        if (now - lastSevereTrimMs < SEVERE_TRIM_GRACE_MS) return
        lastCacheCheckMs = now
        ioExecutor.execute {
            try {
                val size = dirSize(ctx.cacheDir)
                if (size > CACHE_SIZE_LIMIT_BYTES) {
                    Log.w(
                        TAG,
                        "cache ${size / 1048576}MB > ${CACHE_SIZE_LIMIT_BYTES / 1048576}MB — clearing HTTP cache"
                    )
                    mainHandler.post {
                        try { webView?.clearCache(false) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun dirSize(dir: File): Long {
        return if (dir.isFile) {
            dir.length()
        } else {
            dir.listFiles()?.sumOf { f ->
                if (f.isDirectory && f.name in cacheExcludeDirs) {
                    0L // staging dirs are managed by their own pruning
                } else {
                    dirSize(f)
                }
            } ?: 0L
        }
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

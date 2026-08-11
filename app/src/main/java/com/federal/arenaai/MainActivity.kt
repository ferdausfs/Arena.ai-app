package com.federal.arenaai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity hosts the Arena.ai WebView and handles App Links for OAuth callbacks.
 *
 * OAuth flow (Google-compliant):
 * 1. User taps "Login with Google" inside WebView (arena.ai page).
 * 2. WebViewManager.shouldOverrideUrlLoading intercepts accounts.google.com / oauth2.googleapis.com
 *    and launches it in Chrome Custom Tab (CustomTabsIntent) — NOT WebView.
 *    Google blocks embedded WebView server-side; Custom Tabs use real Chrome UA.
 * 3. User authenticates in Custom Tab.
 * 4. Google redirects to arena.ai OAuth callback (e.g. https://arena.ai/auth/callback?code=...)
 *    That navigation hits an intent-filter with autoVerify=true in AndroidManifest.xml.
 *    Because MainActivity is singleTask, the system brings MainActivity to foreground
 *    and delivers the callback URL via onNewIntent().
 * 5. handleIntent() loads that callback URL in the WebView. The WebView makes its own
 *    request to arena.ai, server exchanges code for session and sets Set-Cookie session
 *    header in WebView's CookieManager store. This ensures authenticated session is
 *    visible to WebView even though Custom Tab and WebView do NOT share cookie jars.
 * 6. CookieManager.flush() persists the session.
 * 7. Custom Tab is left in background; user returning via back press will see it but
 *    can close or it may auto-close after App Link. We reload WebView explicitly.
 *
 * Cookie continuity verification:
 * - Custom Tabs use Chrome's cookie store, WebView uses WebView's CookieManager — separate.
 * - Our fix: final OAuth step is always a request to arena.ai done BY the WebView itself
 *   (via handleIntent loadUrl). So WebView gets its own session cookie directly.
 * - We also ensure flush() is called after every page finish and onPause/onDestroy.
 * - Manual testing still required on real device to confirm end-to-end login.
 */
class MainActivity : AppCompatActivity(), WebViewManager.Listener {

    companion object {
        private const val KEY_LAST_URL = "last_url"
        private const val PREF_BATTERY_DISMISSED = "battery_optimization_dismissed"
    }

    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)

        // Register as WebViewManager listener for process-death recovery
        WebViewManager.listener = this

        // Attach WebView
        val webView = WebViewManager.getWebView(this)
        if (webView.parent != null) {
            (webView.parent as ViewGroup).removeView(webView)
        }
        container.addView(webView)

        // Try to restore WebView state after process death
        if (savedInstanceState != null) {
            val restored = WebViewManager.restoreState(savedInstanceState)
            if (!restored) {
                val lastUrl = savedInstanceState.getString(KEY_LAST_URL, "https://arena.ai")
                WebViewManager.loadUrl(lastUrl)
            }
        }

        // Handle deep link intent (e.g., email verification or OAuth callback after Custom Tab)
        handleIntent(intent)

        checkAndRequestPermissions()
        startArenaService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle deep link when app is already running (e.g., OAuth callback returning from Custom Tab)
        intent?.let { handleIntent(it) }
    }

    /**
     * Handle incoming intents — specifically App Links that target arena.ai URLs.
     *
     * This is critical for OAuth:
     * - When Custom Tab navigates to https://arena.ai/auth/callback, the system
     *   routes it here via the intent-filter with autoVerify=true.
     * - We then load that URL in the WebView, so the WebView itself performs the
     *   request and receives the session cookie via Set-Cookie. This solves the
     *   cookie-sharing problem between Custom Tab (Chrome store) and WebView.
     *
     * - After loading, WebView's onPageFinished will flush cookies.
     * - As extra safety, we also flush explicitly and ensure CookieManager is
     *   accepting cookies.
     */
    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme !in listOf("http", "https")) return

        val host = uri.host ?: return
        // Only handle arena.ai URLs (exact match or subdomain)
        if (host == "arena.ai" || host.endsWith(".arena.ai")) {
            // Ensure CookieManager is ready to accept the session cookie
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                // The WebView instance may not exist yet during early launch,
                // but getWebView ensures cookies are configured.
            } catch (_: Exception) {}

            // Load the callback / deep link URL in WebView.
            // This makes WebView perform the request itself, so session cookie lands in WebView's store.
            WebViewManager.loadUrl(uri.toString())

            // Explicitly flush any existing cookies before loading new page to ensure clean state,
            // and after, the flush will happen in onPageFinished as well.
            WebViewManager.flushCookies()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        WebViewManager.saveState(outState)
        outState.putString(KEY_LAST_URL, WebViewManager.getCurrentUrl())
    }

    override fun onPause() {
        super.onPause()
        WebViewManager.onPause()
    }

    override fun onResume() {
        super.onResume()
        WebViewManager.onResume()
        // When returning from Custom Tab, the WebView may have just been loaded
        // with the OAuth callback. Ensure cookies are flushed and WebView is visible.
        // No need to force reload here — handleIntent already loaded the URL.
    }

    private fun startArenaService() {
        val serviceIntent = Intent(this, ArenaSessionService::class.java).apply {
            action = ArenaSessionService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onBackPressed() {
        if (WebViewManager.canGoBack()) {
            WebViewManager.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkBatteryOptimization()
            }
        } else {
            checkBatteryOptimization()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val prefs = getPreferences(Context.MODE_PRIVATE)
            if (prefs.getBoolean(PREF_BATTERY_DISMISSED, false)) return

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Background Execution")
            .setMessage("To keep Arena AI running in the background without reloading, please disable battery optimization for this app.")
            .setPositiveButton("Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Not Now") { _, _ ->
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_BATTERY_DISMISSED, true)
                    .apply()
            }
            .setCancelable(true)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val webView = WebViewManager.getWebView(this)
            if (webView.parent === container) {
                container.removeView(webView)
            }
        } catch (_: Exception) {}
        WebViewManager.flushCookies()
        if (WebViewManager.listener === this) {
            WebViewManager.listener = null
        }
    }

    override fun onWebViewRecreated(newWebView: WebView) {
        runOnUiThread {
            container.removeAllViews()
            if (newWebView.parent != null) {
                (newWebView.parent as ViewGroup).removeView(newWebView)
            }
            container.addView(newWebView)
        }
    }
}

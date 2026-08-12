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
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity hosts the Arena.ai WebView and handles in-app authentication & deep links.
 *
 * In-App Login Architecture:
 * 1. User taps "Login with Google", "GitHub", "Email", etc. inside WebView.
 * 2. WebView User-Agent is formatted with genuine Chrome Mobile tokens (removing '; wv' and 'Version/X.X'),
 *    ensuring Google OAuth does not block embedded WebView with '403 disallowed_useragent'.
 * 3. Both redirect flows and popup window flows (window.open) are handled directly in-app with
 *    shared CookieManager.
 * 4. User completes authentication inside the app.
 * 5. Session cookies and storage are saved directly into the app's CookieManager store.
 * 6. User is immediately returned to Arena.ai inside the app with full logged-in state.
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

        // Wire the system file-picker launcher for <input type="file"> uploads.
        // Must be assigned BEFORE getWebView() so the first page can already pick files.
        WebViewManager.startFileChooser = { intent ->
            try {
                Log.d(WebViewManager.TAG, "fileChooserLauncher.launch action=${intent.action} type=${intent.type}")
                fileChooserLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(WebViewManager.TAG, "fileChooserLauncher.launch failed", e)
                WebViewManager.cancelPendingFileChooser()
            }
        }

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

        // Handle deep link intent (e.g., email verification or direct link)
        handleIntent(intent)

        checkAndRequestPermissions()
        startArenaService()

        // Modern predictive-back handling (onBackPressed() is deprecated from API 33+).
        registerBackHandling()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    /**
     * Handle incoming intents — specifically deep links / App Links for arena.ai.
     */
    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme !in listOf("http", "https")) return

        if (WebViewManager.isAllowedInWebView(uri)) {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
            } catch (_: Exception) {}

            WebViewManager.loadUrl(uri.toString())
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

    private fun registerBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. Close an OAuth popup if one is open.
                if (WebViewManager.dismissActivePopup()) return
                // 2. Navigate WebView history back.
                if (WebViewManager.canGoBack()) {
                    WebViewManager.goBack()
                } else {
                    // 3. No more history -> minimize the app (keep the foreground service alive).
                    moveTaskToBack(true)
                }
            }
        })
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

    /**
     * System file picker / camera for <input type="file"> uploads. Launched by
     * WebViewManager.onShowFileChooser(); the result is delivered back via
     * WebViewManager.deliverFileChooserResult(). A null result is REQUIRED to
     * release the JS ValueCallback when the user cancels.
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(
            WebViewManager.TAG,
            "fileChooser resultCode=${result.resultCode} data=${result.data}"
        )
        val uris = WebViewManager.consumePickerResult(result.resultCode, result.data)
        WebViewManager.deliverFileChooserResult(uris)
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
        // Release any pending file-chooser callback so the next picker works.
        WebViewManager.cancelPendingFileChooser()
        WebViewManager.startFileChooser = null
        // Detach without (re)creating the singleton WebView (getWebView() has that side effect).
        WebViewManager.detachFrom(container)
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

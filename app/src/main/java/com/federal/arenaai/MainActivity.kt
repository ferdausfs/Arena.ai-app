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
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), WebViewManager.Listener {

    companion object {
        private const val KEY_LAST_URL = "last_url"
        // Track if we've already shown the battery dialog to avoid nagging on every launch
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
                // State couldn't be restored — load the last known URL
                val lastUrl = savedInstanceState.getString(KEY_LAST_URL, "https://arena.ai")
                WebViewManager.loadUrl(lastUrl)
            }
        }

        // Handle deep link intent (e.g., email verification link clicked from email app)
        handleIntent(intent)

        // Request Permissions & Battery Exemption
        checkAndRequestPermissions()

        // Start Foreground Service
        startArenaService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Update the activity's intent so getIntent() returns the new one
        setIntent(intent)
        // Handle deep link (e.g., user clicked an arena.ai link in an email
        // and the app was already running in the background)
        intent?.let { handleIntent(it) }
    }

    /**
     * Handle incoming intents — specifically deep links from email verification
     * or OAuth callbacks that target arena.ai URLs.
     */
    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme !in listOf("http", "https")) return

        val host = uri.host ?: return
        // Only handle arena.ai URLs (exact match or subdomain).
        // The intent-filter already restricts delivery, but this is a safety check.
        if (host == "arena.ai" || host.endsWith(".arena.ai")) {
            WebViewManager.loadUrl(uri.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save WebView state so it can be restored after process death
        WebViewManager.saveState(outState)
        outState.putString(KEY_LAST_URL, WebViewManager.getCurrentUrl())
    }

    override fun onPause() {
        super.onPause()
        // Pause WebView to stop JavaScript timers and save battery
        WebViewManager.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Resume WebView to restart JavaScript timers
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

    override fun onBackPressed() {
        if (WebViewManager.canGoBack()) {
            WebViewManager.goBack()
        } else {
            // Do not finish the activity — move to background to preserve the session.
            // The foreground service keeps the WebView alive.
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
        // Whether granted or denied, proceed to battery optimization check.
        // The app works either way — notifications are just nice to have.
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val prefs = getPreferences(Context.MODE_PRIVATE)
            // Don't nag if user already dismissed this dialog once
            if (prefs.getBoolean(PREF_BATTERY_DISMISSED, false)) {
                return
            }

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
                    // Fallback: some devices don't support this specific intent
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {
                        // Ignore — some manufacturers remove this entirely
                    }
                }
            }
            .setNegativeButton("Not Now") { _, _ ->
                // Remember that the user dismissed this so we don't nag again
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_BATTERY_DISMISSED, true)
                    .apply()
            }
            .setCancelable(true)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // If the activity is destroyed (e.g. system reclaims memory), remove the view
        // so it can be re-attached later without leaking the old Activity context.
        try {
            val webView = WebViewManager.getWebView(this)
            if (webView.parent === container) {
                container.removeView(webView)
            }
        } catch (_: Exception) {
            // WebView might be in a bad state — ignore
        }
        // Flush cookies one last time
        WebViewManager.flushCookies()
        // Unregister listener to avoid leaking the Activity
        if (WebViewManager.listener === this) {
            WebViewManager.listener = null
        }
    }

    override fun onWebViewRecreated(newWebView: WebView) {
        // The WebView renderer crashed and a new one was created.
        // Attach the new WebView to our container.
        runOnUiThread {
            container.removeAllViews()
            if (newWebView.parent != null) {
                (newWebView.parent as ViewGroup).removeView(newWebView)
            }
            container.addView(newWebView)
        }
    }
}

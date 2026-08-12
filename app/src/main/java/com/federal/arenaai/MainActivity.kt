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

        // Wire the system file-picker launcher for <input type="file"> uploads
        WebViewManager.startFileChooser = { intent -> fileChooserLauncher.launch(intent) }

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
            // API 24-28: WRITE_EXTERNAL_STORAGE is genuinely required to write
            // JS/blob downloads directly into the PUBLIC Downloads folder. When it
            // is denied, downloads still land in the app-specific Downloads dir.
            requestLegacyStorageIfNeeded()
            checkBatteryOptimization()
        }
    }

    /**
     * API 24-28 only: request WRITE_EXTERNAL_STORAGE so blob/data downloads can be
     * written to the public Downloads directory (MediaStore.Downloads does not exist
     * before API 29). Harmless if denied — saves then fall back to app storage.
     */
    private fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestLegacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private val requestLegacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        // Nothing to do — the save path checks the permission at write time.
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        checkBatteryOptimization()
    }

    /**
     * System file picker for <input type="file"> uploads. Launched by
     * WebViewManager.onShowFileChooser(); the result is delivered back via
     * WebViewManager.deliverFileChooserResult(). Handles multi-select clipData,
     * single data Uris, and camera-capture results (data == null but the
     * EXTRA_OUTPUT FileProvider URI was written).
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val clip = data?.clipData
        val dataUri = data?.data
        // Consume the camera EXTRA_OUTPUT URI if a camera action was offered.
        val cameraUri = WebViewManager.consumePendingCameraUri()

        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            when {
                clip != null && clip.itemCount > 0 ->
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                dataUri != null -> arrayOf(dataUri)
                // Camera capture returns RESULT_OK with data == null; the photo is
                // at the FileProvider EXTRA_OUTPUT URI we passed to the camera.
                cameraUri != null -> arrayOf(cameraUri)
                else -> null
            }
        } else {
            // null is REQUIRED to release the JS callback when the user cancels.
            null
        }
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

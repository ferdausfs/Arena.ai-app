package com.federal.arenaai

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponseInfo

/**
 * PROTOTYPE — arena.ai on a BUNDLED Firefox engine (GeckoView).
 *
 * Why: the main app renders arena.ai with the system WebView (Chrome's engine,
 * updated via Play Store). This activity instead embeds Mozilla's GeckoView, so
 * the app carries its own browser engine and no longer depends on the system
 * WebView/Chrome at all.
 *
 * Deliberately a parallel, self-contained prototype:
 *  - The WebView path (WebViewManager/MainActivity) is untouched.
 *  - Launch via `adb shell am start -n com.federal.arenaai/.GeckoArenaActivity`.
 *
 * Implemented here (parity with the WebView app):
 *  - Same host allow-list / external-browser policy (reuses WebViewManager).
 *  - window.open() popups (OAuth) → new GeckoSession in the same runtime
 *    (same cookie jar/profile) shown in this view; back button returns.
 *  - <input type="file"> uploads via PromptDelegate + system picker.
 *  - http(s) downloads via ContentDelegate + DownloadManager.
 *
 * Known prototype limitations (documented, not implemented):
 *  - blob:/data: downloads need a GeckoView WebExtension to read the blob in
 *    JS and hand it to native — not wired in this prototype.
 *  - No renderer crash-loop guard yet (GeckoView has its own process model).
 *  - GeckoView default user agent is a REAL Firefox UA, so Google OAuth and
 *    reCAPTCHA behave like a normal mobile browser (no UA hacks needed).
 */
@SuppressLint("SetJavaScriptEnabled")
class GeckoArenaActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ArenaGecko"
        private const val START_URL = "https://arena.ai"

        // Clean Firefox-mobile UA (GeckoView's default already is one; kept
        // explicit so OAuth always sees a "real browser" fingerprint).
        private const val GECKO_UA =
            "Mozilla/5.0 (Android 14; Mobile; rv:139.0) Gecko/139.0 Firefox/139.0"
    }

    private lateinit var runtime: GeckoRuntime
    private lateinit var geckoView: GeckoView
    private lateinit var mainSession: GeckoSession
    private var currentSession: GeckoSession? = null
    private val popupSessions = mutableListOf<GeckoSession>()

    // Pending <input type="file"> prompt (GeckoSession.PromptDelegate).
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFilePromptResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val prompt = pendingFilePrompt
        val geckoResult = pendingFilePromptResult
        pendingFilePrompt = null
        pendingFilePromptResult = null
        if (prompt == null || geckoResult == null) return@registerForActivityResult

        val response: GeckoSession.PromptDelegate.PromptResponse = if (result.resultCode == RESULT_OK) {
            val clip = result.data?.clipData
            val uris: Array<Uri> = when {
                clip != null && clip.itemCount > 0 ->
                    (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.toTypedArray()
                result.data?.data != null -> arrayOf(result.data!!.data!!)
                else -> emptyArray()
            }
            if (uris.isEmpty()) {
                prompt.dismiss()
            } else {
                // confirm(Context, Uri[]) hands content:// Uris straight back to
                // the page (Gecko reads them via its own content resolver).
                prompt.confirm(applicationContext, uris)
            }
        } else {
            prompt.dismiss()
        }
        try {
            geckoResult.complete(response)
        } catch (e: Exception) {
            Log.e(TAG, "file prompt complete failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .userAgentOverride(GECKO_UA)
            .remoteDebuggingEnabled(true)
            .build()
        runtime = GeckoRuntime.create(this, settings)

        geckoView = GeckoView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(
            FrameLayout(this).apply {
                addView(geckoView)
            }
        )

        mainSession = createSession()
        currentSession = mainSession
        mainSession.open(runtime)
        geckoView.setSession(mainSession)
        mainSession.loadUri(START_URL)
        Log.d(TAG, "GeckoView started, loading $START_URL (runtime=${runtime.hashCode()})")

        // Back: popup session → main session, otherwise navigate back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val cur = currentSession
                if (cur != null && cur !== mainSession) {
                    switchToSession(mainSession)
                    return
                }
                cur?.goBack() ?: finish()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mainSession.close() } catch (_: Exception) {}
        popupSessions.forEach { s -> try { s.close() } catch (_: Exception) {} }
        popupSessions.clear()
    }

    /** Build a session wired with the same policy as the main one. */
    private fun createSession(): GeckoSession {
        return GeckoSession().apply {
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    Log.d(TAG, "page start: ${url.take(120)}")
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    Log.d(TAG, "page stop success=$success")
                }
            }

            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onExternalResponse(session: GeckoSession, response: WebResponseInfo) {
                    handleDownload(response)
                }
            }

            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    return handleLoadRequest(request)
                }

                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                    return handleNewSession(uri)
                }
            }

            promptDelegate = object : GeckoSession.PromptDelegate {
                override fun onFilePrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.FilePrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    return handleFilePrompt(prompt)
                }
            }
        }
    }

    /**
     * Same navigation policy as the WebView app: allow-listed hosts stay in the
     * engine (arena.ai + OAuth identity providers), everything else opens in the
     * external browser.
     */
    private fun handleLoadRequest(
        request: GeckoSession.NavigationDelegate.LoadRequest
    ): GeckoResult<AllowOrDeny>? {
        val uri = Uri.parse(request.uri)
        val scheme = uri.scheme?.lowercase()

        if (scheme == "mailto" || scheme == "tel" || scheme == "sms") {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        if (scheme == "http" || scheme == "https") {
            if (WebViewManager.isAllowedInWebView(uri)) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
            Log.d(TAG, "external link -> browser: $uri")
            WebViewManager.openInExternalBrowser(this, uri)
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        // blob:, data:, intent:, etc. — let the engine handle them.
        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }

    /**
     * window.open() → a new GeckoSession (same runtime = same cookie jar, so
     * OAuth login shares the session) shown in this view. Only allowed for
     * allow-listed hosts; everything else is refused (window.open returns null).
     */
    private fun handleNewSession(uri: String): GeckoResult<GeckoSession>? {
        Log.d(TAG, "window.open requested: ${uri.take(120)}")
        val u = try {
            Uri.parse(uri)
        } catch (_: Exception) {
            return null
        }
        if (!WebViewManager.isAllowedInWebView(u)) {
            Log.d(TAG, "popup refused for $uri")
            return null
        }
        val popup = createSession()
        popupSessions.add(popup)
        popup.open(runtime)
        switchToSession(popup)
        return GeckoResult.fromValue(popup)
    }

    private fun switchToSession(session: GeckoSession) {
        // Closing a previous popup when we leave it keeps memory bounded.
        currentSession?.let { cur ->
            if (cur !== mainSession && cur !== session) {
                popupSessions.remove(cur)
                try { cur.close() } catch (_: Exception) {}
            }
        }
        currentSession = session
        geckoView.setSession(session)
    }

    /**
     * <input type="file"> → system picker (Files/Gallery). Result is handed to
     * the page via FilePrompt.confirm(Context, Uri[]).
     */
    private fun handleFilePrompt(
        prompt: GeckoSession.PromptDelegate.FilePrompt
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        Log.d(
            TAG,
            "file prompt: type=${prompt.type} mime=${prompt.mimeTypes?.joinToString() ?: ""}"
        )
        pendingFilePrompt = prompt
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        pendingFilePromptResult = result

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            fileChooserLauncher.launch(Intent.createChooser(intent, getString(R.string.file_chooser_title)))
        } catch (e: Exception) {
            Log.e(TAG, "file chooser launch failed", e)
            pendingFilePrompt = null
            pendingFilePromptResult = null
            return GeckoResult.fromValue(prompt.dismiss())
        }
        return result
    }

    /** http(s) downloads → system DownloadManager (with a notification). */
    private fun handleDownload(response: WebResponseInfo) {
        val uriStr = response.uri ?: run {
            Log.w(TAG, "download without uri")
            return
        }
        Log.d(
            TAG,
            "download: $uriStr type=${response.contentType} name=${response.filename}"
        )
        val u = Uri.parse(uriStr)
        val scheme = u.scheme?.lowercase()

        if (scheme == "http" || scheme == "https") {
            try {
                val fileName = response.filename
                    ?: URLUtil.guessFileName(uriStr, null, response.contentType)
                val request = DownloadManager.Request(u).apply {
                    setMimeType(response.contentType ?: "application/octet-stream")
                    setTitle(fileName)
                    setDescription("Downloading $fileName")
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.enqueue(request)
                Log.d(TAG, "DownloadManager enqueued $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "download enqueue failed", e)
            }
        } else {
            // blob:/data: — prototype limitation (needs a GeckoView WebExtension
            // JS bridge to read the blob; not implemented in this prototype).
            Log.w(TAG, "non-http download not handled in prototype: $uriStr")
            toast("Download type not yet supported in the Gecko prototype")
        }
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}

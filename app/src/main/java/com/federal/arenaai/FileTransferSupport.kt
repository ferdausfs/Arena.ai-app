package com.federal.arenaai

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Shared upload / download helpers used by both the main WebView and the
 * OAuth/popup WebView. Keep this logic in one place so a window.open()
 * surface cannot silently drop file pickers or blob downloads.
 */
object FileTransferSupport {
    const val TAG = "ArenaWebView"
    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    private const val DOWNLOAD_CHANNEL_ID = "arena_downloads"
    private const val DOWNLOAD_NOTIFICATION_BASE = 4200

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var hookJs: String? = null
    private var notificationSerial = 0
    @Volatile private var lastCachePruneMs = 0L

    /**
     * Per-WebView URL of the last document the download hook was injected into.
     * onProgressChanged(100) + onPageFinished both fire per load (and arena.ai
     * loads many subframes), so without this guard we re-evaluate a ~9 KB JS
     * string several times per page load on the UI thread — measurable jank on
     * low-end phones. The JS itself is idempotent; this avoids the work.
     */
    private val injectedHookUrl = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<WebView, String>()
    )

    fun providerAuthority(context: Context): String =
        context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX

    fun appContext(): Context? = try {
        ArenaApp.instance
    } catch (_: Exception) {
        null
    }

    fun sanitizeFileName(raw: String?): String {
        val trimmed = raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        val cleaned = trimmed
            .replace(Regex("[^A-Za-z0-9._\\- ()+]"), "_")
            .trim('.', ' ')
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            "download"
        } else {
            cleaned.take(128)
        }
    }

    fun buildChooserLaunch(
        context: Context,
        params: WebChromeClient.FileChooserParams
    ): ChooserLaunch {
        val acceptTypes = params.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        Log.d(
            TAG,
            "buildChooserLaunch mode=${params.mode} capture=${params.isCaptureEnabled} " +
                "accept=${acceptTypes.joinToString()}"
        )

        val contentIntent = try {
            params.createIntent()
        } catch (e: Exception) {
            Log.w(TAG, "FileChooserParams.createIntent() failed; using ACTION_GET_CONTENT", e)
            Intent(Intent.ACTION_GET_CONTENT)
        }.apply {
            if (action.isNullOrBlank()) {
                action = Intent.ACTION_GET_CONTENT
            }
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            if (type.isNullOrBlank()) {
                applyAcceptTypes(acceptTypes)
            }
        }

        val camera = if (params.isCaptureEnabled || acceptsImages(acceptTypes)) {
            buildCameraIntent(context)
        } else {
            null
        }

        if (params.isCaptureEnabled && camera != null) {
            Log.d(TAG, "capture-enabled input → launching camera directly")
            return ChooserLaunch(camera.intent, camera.outputUri)
        }

        // "All files" escape hatch. Sites like arena.ai restrict the input to a
        // fixed accept list (no .zip), which makes the system picker grey out
        // every other file type. Offer an unfiltered picker so the user can still
        // select e.g. a .zip; the SITE's own validation then decides whether to
        // accept it (the app must not bypass that). Only added when the page's
        // accept list actually restricts types.
        val allFilesIntent: Intent? =
            if (acceptTypes.isNotEmpty() && acceptTypes.none { it.equals("*/*", ignoreCase = true) }) {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
            } else {
                null
            }
        if (allFilesIntent != null) {
            Log.d(TAG, "offering 'All files' picker (accept restricted to $acceptTypes)")
        }

        val chooser = Intent.createChooser(
            contentIntent,
            context.getString(R.string.file_chooser_title)
        ).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val initial = mutableListOf<Intent>()
            camera?.let { initial.add(it.intent) }
            allFilesIntent?.let { initial.add(it) }
            if (initial.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, initial.toTypedArray())
            }
        }
        return ChooserLaunch(chooser, camera?.outputUri)
    }

    private fun Intent.applyAcceptTypes(acceptTypes: List<String>) {
        when {
            acceptTypes.isEmpty() -> type = "*/*"
            acceptTypes.size == 1 && !acceptTypes[0].startsWith(".") -> type = acceptTypes[0]
            else -> {
                type = "*/*"
                val mimes = acceptTypes.map { token ->
                    if (token.startsWith(".")) {
                        MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(token.removePrefix(".").lowercase())
                            ?: "*/*"
                    } else {
                        token
                    }
                }.toTypedArray()
                putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            }
        }
    }

    private fun acceptsImages(types: List<String>): Boolean {
        if (types.isEmpty()) return true
        return types.any { raw ->
            val t = raw.lowercase()
            t == "*/*" ||
                t.startsWith("image/") ||
                t in setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".bmp")
        }
    }

    private fun buildCameraIntent(context: Context): CameraCapture? {
        return try {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, providerAuthority(context), file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                clipData = android.content.ClipData.newUri(context.contentResolver, "capture", uri)
            }
            val flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            val resolved = context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            for (info in resolved) {
                context.grantUriPermission(info.activityInfo.packageName, uri, flags)
            }
            Log.d(TAG, "camera intent ready uri=$uri resolved=${resolved.size}")
            CameraCapture(intent, uri)
        } catch (e: Exception) {
            Log.e(TAG, "buildCameraIntent failed", e)
            null
        }
    }

    /**
     * Delete stale files from the upload/camera/blob staging dirs (at most once
     * per hour). Without this the cache would grow forever with every upload
     * and camera capture, eventually filling storage and slowing the phone.
     */
    private fun pruneStagingCache(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCachePruneMs < 60L * 60L * 1000L) return
        lastCachePruneMs = now
        io.execute {
            try {
                val cutoff = now - 7L * 24L * 60L * 60L * 1000L
                for (dirName in listOf("uploads", "camera", "blob-in")) {
                    val dir = File(context.cacheDir, dirName)
                    if (!dir.isDirectory) continue
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.lastModified() < cutoff) {
                            f.delete()
                        }
                    }
                }
                Log.d(TAG, "pruneStagingCache: cleaned staging dirs")
            } catch (_: Exception) {}
        }
    }

    fun copyUriToAppCache(context: Context, uri: Uri): Uri? {
        pruneStagingCache(context)
        // Already one of ours (camera capture EXTRA_OUTPUT, or a previously
        // copied upload) — the file is in our cache and the WebView can read it
        // via content://. Copying it again would duplicate e.g. a 10 MB photo.
        if (uri.authority == providerAuthority(context)) {
            Log.d(TAG, "copyUriToAppCache: uri already ours, skipping copy: $uri")
            return uri
        }
        return try {
            val display = queryDisplayName(context, uri) ?: "upload"
            val safe = sanitizeFileName(display)
            val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
            val dest = File(dir, "${System.currentTimeMillis()}_$safe")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Log.w(TAG, "openInputStream returned null for $uri")
                return null
            }
            val out = FileProvider.getUriForFile(context, providerAuthority(context), dest)
            Log.d(TAG, "copied picker uri=$uri → $out (${dest.length()} bytes)")
            out
        } catch (e: Exception) {
            Log.e(TAG, "copyUriToAppCache failed for $uri", e)
            null
        }
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
    }

    /** Arena / LMSYS hosts where the download hook is allowed to run. */
    private val arenaHosts = setOf(
        "arena.ai",
        "lmarena.ai",
        "lmsys.org",
        "chatbot-arena.org"
    )

    /** True for arena.ai / lmarena.ai / lmsys.org / chatbot-arena.org (+ subdomains). */
    fun isArenaUrl(url: String?): Boolean {
        val host = try {
            Uri.parse(url ?: "").host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return arenaHosts.any { host == it || host.endsWith(".$it") }
    }

    fun injectDownloadHook(webView: WebView?) {
        val view = webView ?: return
        // Nothing loaded yet (or navigating to about:blank) — skip; the hook
        // will be (re)injected from the next onPageFinished/onProgressChanged.
        val url = view.url ?: return
        // Only run on Arena origins — NEVER on OAuth/identity hosts
        // (accounts.google.com, github.com, clerk, ...). The hook patches
        // URL.createObjectURL / window.open / <a>.click; that must not happen
        // on a login page.
        if (!isArenaUrl(url)) return
        // Already injected for this document (progress-100 + page-finished +
        // subframes all funnel here). Skip the evaluateJavascript call.
        if (injectedHookUrl[view] == url) return
        val js = hookJavaScript(view.context) ?: return
        view.post {
            try {
                view.evaluateJavascript(js, null)
                injectedHookUrl[view] = url
            } catch (e: Exception) {
                Log.e(TAG, "injectDownloadHook failed", e)
            }
        }
    }

    /**
     * Decode a `data:` URL in-process (no JS, no Binder). Used when
     * DownloadListener fires with a data: URL.
     *
     * The decode + write are moved to the IO executor: DownloadListener runs on
     * the WebView's listener thread, and a multi-MB base64 payload would stall
     * rendering if decoded there. A size pre-check rejects oversized payloads
     * before any decoding to avoid OOM.
     */
    fun saveDataUrl(context: Context, url: String, fallbackName: String?): Boolean {
        if (!url.startsWith("data:", ignoreCase = true)) return false
        return try {
            val comma = url.indexOf(',')
            if (comma <= 5) return false
            val header = url.substring(5, comma) // after "data:"
            val payload = url.substring(comma + 1)
            val mime = header.substringBefore(';', missingDelimiterValue = header)
                .ifBlank { "application/octet-stream" }
            val base64 = header.contains("base64", ignoreCase = true)
            val estimated = if (base64) {
                payload.length.toLong() * 3L / 4L
            } else {
                payload.length.toLong()
            }
            if (estimated > MAX_BLOB_BYTES) {
                Log.e(TAG, "saveDataUrl rejected: estimated $estimated bytes > $MAX_BLOB_BYTES")
                toast(context, context.getString(R.string.download_too_large))
                return true // handled (rejected)
            }
            io.execute {
                try {
                    val bytes = if (base64) {
                        Base64.decode(payload, Base64.DEFAULT)
                    } else {
                        Uri.decode(payload).toByteArray(Charsets.UTF_8)
                    }
                    if (bytes.isEmpty()) {
                        Log.e(TAG, "saveDataUrl: empty payload")
                        return@execute
                    }
                    persistBytes(context, bytes, mime, fallbackName)
                } catch (e: Exception) {
                    Log.e(TAG, "saveDataUrl decode/save failed", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveDataUrl failed", e)
            false
        }
    }

    fun requestBlobFromPage(webView: WebView, url: String, fileName: String, mime: String?) {
        val hook = hookJavaScript(webView.context) ?: return
        val call = "try{window.__arenaFetchBlob&&window.__arenaFetchBlob(" +
            JSONObject.quote(url) + "," +
            JSONObject.quote(fileName) + "," +
            JSONObject.quote(mime ?: "") +
            ");}catch(e){console.log('[ArenaNative] fetch call failed '+e);}"
        webView.post {
            try {
                // Install (idempotent) then fetch in the SAME evaluate so we
                // do not race two async evaluateJavascript calls.
                webView.evaluateJavascript("$hook\n$call", null)
            } catch (e: Exception) {
                Log.e(TAG, "requestBlobFromPage failed", e)
            }
        }
    }

    private fun hookJavaScript(context: Context): String? {
        hookJs?.let { return it }
        return try {
            val loaded = context.applicationContext.assets
                .open("arena_download_hook.js")
                .bufferedReader()
                .use { it.readText() }
            hookJs = loaded
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "failed to load arena_download_hook.js", e)
            null
        }
    }

    fun enqueueHttpDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ): Boolean {
        if (!URLUtil.isNetworkUrl(url)) {
            Log.w(TAG, "enqueueHttpDownload rejected non-network url scheme=${Uri.parse(url).scheme}")
            return false
        }
        return try {
            val fileName = sanitizeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            )
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType ?: "application/octet-stream")
                setTitle(fileName)
                setDescription(context.getString(R.string.download_in_progress, fileName))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                val cookies = try {
                    CookieManager.getInstance().getCookie(url)
                } catch (_: Exception) {
                    null
                }
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return false
            val id = dm.enqueue(request)
            Log.d(TAG, "DownloadManager enqueued id=$id name=$fileName url=${url.take(120)}")
            toast(context, context.getString(R.string.download_started, fileName))
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueHttpDownload failed url=$url", e)
            false
        }
    }

    fun saveBase64ToDownloads(
        context: Context,
        base64: String,
        mimeType: String?,
        fileName: String?
    ) {
        io.execute {
            try {
                val estimated = (base64.length.toLong() * 3L) / 4L
                if (estimated > MAX_BLOB_BYTES) {
                    Log.e(TAG, "saveBase64 rejected: estimated $estimated bytes > $MAX_BLOB_BYTES")
                    toast(context, context.getString(R.string.download_too_large))
                    return@execute
                }
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                if (bytes.isEmpty()) {
                    Log.e(TAG, "saveBase64 decoded empty buffer")
                    toast(context, context.getString(R.string.download_failed))
                    return@execute
                }
                persistBytes(context, bytes, mimeType, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "saveBase64ToDownloads failed", e)
                toast(context, context.getString(R.string.download_failed))
            }
        }
    }

    /**
     * Persist an already-decoded temp file (chunked blob path) and delete it.
     */
    fun saveFileToDownloads(
        context: Context,
        file: File,
        mimeType: String?,
        fileName: String?
    ) {
        io.execute {
            try {
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "saveFileToDownloads: missing/empty ${file.path}")
                    toast(context, context.getString(R.string.download_failed))
                    return@execute
                }
                if (file.length() > MAX_BLOB_BYTES) {
                    Log.e(TAG, "saveFileToDownloads rejected: ${file.length()} bytes")
                    toast(context, context.getString(R.string.download_too_large))
                    return@execute
                }
                val bytes = file.readBytes()
                persistBytes(context, bytes, mimeType, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "saveFileToDownloads failed", e)
                toast(context, context.getString(R.string.download_failed))
            } finally {
                file.delete()
            }
        }
    }

    private fun persistBytes(
        context: Context,
        bytes: ByteArray,
        mimeType: String?,
        fileName: String?
    ) {
        if (bytes.size > MAX_BLOB_BYTES) {
            Log.e(TAG, "persistBytes rejected: ${bytes.size} bytes > $MAX_BLOB_BYTES")
            toast(context, context.getString(R.string.download_too_large))
            return
        }
        val mime = mimeType?.takeIf { it.isNotBlank() && it.length < 128 }
            ?: "application/octet-stream"
        val name = sanitizeFileName(
            fileName?.takeIf { it.isNotBlank() }
                ?: URLUtil.guessFileName("https://arena.ai/download", null, mime)
        )
        val uri = writeBytes(context, bytes, mime, name)
        if (uri == null) {
            Log.e(TAG, "writeBytes returned null for $name")
            toast(context, context.getString(R.string.download_failed))
            return
        }
        Log.d(TAG, "saved blob name=$name mime=$mime bytes=${bytes.size} uri=$uri")
        notifySaved(context, uri, name, mime)
        toast(context, context.getString(R.string.download_complete, name))
    }

    private fun writeBytes(
        context: Context,
        bytes: ByteArray,
        mime: String,
        fileName: String
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, bytes, mime, fileName)
        } else {
            writeViaPublicDownloads(context, bytes, mime, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(
        context: Context,
        bytes: ByteArray,
        mime: String,
        fileName: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore write failed", e)
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    /**
     * API 24–28: write into the app-specific external Downloads directory.
     * This needs NO storage permission. The file is still user-visible via
     * the notification's ACTION_VIEW (FileProvider) and Android/data/... .
     * Public Downloads would require WRITE_EXTERNAL_STORAGE (dangerous).
     */
    private fun writeViaPublicDownloads(
        context: Context,
        bytes: ByteArray,
        mime: String,
        fileName: String
    ): Uri? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create download dir $dir")
            return null
        }
        val dest = uniqueFile(dir, fileName)
        return try {
            dest.outputStream().use { it.write(bytes) }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(dest.absolutePath),
                arrayOf(mime),
                null
            )
            FileProvider.getUriForFile(context, providerAuthority(context), dest)
        } catch (e: Exception) {
            Log.e(TAG, "legacy write failed", e)
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val sanitized = sanitizeFileName(name)
        var candidate = File(dir, sanitized)
        if (!candidate.exists()) return candidate
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun notifySaved(context: Context, uri: Uri, fileName: String, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // The file IS saved; only the notification is skipped. Log so a
                // missing notification is explainable (permission was denied).
                Log.d(TAG, "notifySaved: POST_NOTIFICATIONS not granted — notification skipped for $fileName")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ensureDownloadChannel(context)
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            val pending = PendingIntent.getActivity(
                context,
                notificationSerial++,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.download_complete, fileName))
                .setContentText(context.getString(R.string.download_saved_to_downloads))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(DOWNLOAD_NOTIFICATION_BASE + (notificationSerial % 1000), notification)
        } catch (e: Exception) {
            Log.e(TAG, "notifySaved failed", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureDownloadChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(DOWNLOAD_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                context.getString(R.string.downloads_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.downloads_channel_desc)
            }
        )
    }

    private fun toast(context: Context, message: String) {
        main.post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    data class ChooserLaunch(val intent: Intent, val cameraOutputUri: Uri?)
    private data class CameraCapture(val intent: Intent, val outputUri: Uri)
}

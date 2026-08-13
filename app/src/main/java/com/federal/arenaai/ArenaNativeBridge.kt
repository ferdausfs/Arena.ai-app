package com.federal.arenaai

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * JS → native bridge. Exposed on every WebView as `window.ArenaNative`.
 *
 * Surface is intentionally tiny: the page can only ask us to persist a
 * blob it already holds. Nothing is readable back into JS.
 *
 * Binder transactions are capped at ~1 MB, so large blobs must be sent
 * in chunks ([saveBlobBegin] / [saveBlobChunk] / [saveBlobEnd]). Each
 * chunk is decoded immediately onto a temp file to avoid assembling a
 * giant base64 String in the heap.
 */
class ArenaNativeBridge {

    private data class Assembly(
        val file: File,
        val out: FileOutputStream,
        val mime: String,
        val fileName: String,
        val createdAt: Long,
        var written: Long = 0
    )

    private val assemblies = ConcurrentHashMap<String, Assembly>()

    /** Drop any assembly whose chunks stopped arriving > 5 minutes ago. */
    private fun dropStaleAssemblies() {
        val cutoff = System.currentTimeMillis() - 5L * 60L * 1000L
        for ((id, a) in assemblies) {
            if (a.createdAt < cutoff) {
                Log.w(FileTransferSupport.TAG, "saveBlobBegin: dropping stale assembly $id")
                drop(id)
            }
        }
    }

    @JavascriptInterface
    fun saveBlob(base64: String?, mimeType: String?, fileName: String?) {
        Log.d(
            FileTransferSupport.TAG,
            "ArenaNative.saveBlob name=$fileName mime=$mimeType b64len=${base64?.length ?: 0}"
        )
        if (base64.isNullOrEmpty()) {
            Log.e(FileTransferSupport.TAG, "saveBlob: empty payload")
            return
        }
        val ctx = FileTransferSupport.appContext() ?: run {
            Log.e(FileTransferSupport.TAG, "saveBlob: Application context unavailable")
            return
        }
        FileTransferSupport.saveBase64ToDownloads(ctx, base64, mimeType, fileName)
    }

    @JavascriptInterface
    fun saveBlobBegin(id: String?, mimeType: String?, fileName: String?) {
        if (id.isNullOrBlank()) return
        val ctx = FileTransferSupport.appContext() ?: return
        drop(id)
        dropStaleAssemblies()
        try {
            val dir = File(ctx.cacheDir, "blob-in").apply { mkdirs() }
            val file = File(dir, "blob_${id.filter { it.isLetterOrDigit() || it == '_' }}.bin")
            val out = FileOutputStream(file)
            assemblies[id] = Assembly(
                file = file,
                out = out,
                mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                fileName = FileTransferSupport.sanitizeFileName(fileName),
                createdAt = System.currentTimeMillis()
            )
            Log.d(FileTransferSupport.TAG, "saveBlobBegin id=$id name=$fileName mime=$mimeType")
        } catch (e: Exception) {
            Log.e(FileTransferSupport.TAG, "saveBlobBegin failed", e)
        }
    }

    @JavascriptInterface
    fun saveBlobChunk(id: String?, chunk: String?) {
        if (id.isNullOrBlank() || chunk.isNullOrEmpty()) return
        val session = assemblies[id] ?: run {
            Log.e(FileTransferSupport.TAG, "saveBlobChunk: no session for $id")
            return
        }
        try {
            val bytes = Base64.decode(chunk, Base64.DEFAULT)
            if (session.written + bytes.size > FileTransferSupport.MAX_BLOB_BYTES) {
                Log.e(FileTransferSupport.TAG, "saveBlobChunk: exceeds MAX_BLOB_BYTES, dropping $id")
                drop(id)
                return
            }
            session.out.write(bytes)
            session.written += bytes.size
        } catch (e: Exception) {
            Log.e(FileTransferSupport.TAG, "saveBlobChunk failed id=$id", e)
            drop(id)
        }
    }

    @JavascriptInterface
    fun saveBlobEnd(id: String?) {
        if (id.isNullOrBlank()) return
        val session = assemblies.remove(id) ?: run {
            Log.e(FileTransferSupport.TAG, "saveBlobEnd: no session for $id")
            return
        }
        try {
            session.out.flush()
            session.out.close()
            Log.d(
                FileTransferSupport.TAG,
                "saveBlobEnd id=$id bytes=${session.written} name=${session.fileName}"
            )
            val ctx = FileTransferSupport.appContext()
            if (ctx == null || session.written == 0L) {
                session.file.delete()
                return
            }
            FileTransferSupport.saveFileToDownloads(
                ctx,
                session.file,
                session.mime,
                session.fileName
            )
        } catch (e: Exception) {
            Log.e(FileTransferSupport.TAG, "saveBlobEnd failed", e)
            try { session.out.close() } catch (_: Exception) {}
            session.file.delete()
        }
    }

    private fun drop(id: String) {
        val session = assemblies.remove(id) ?: return
        try { session.out.close() } catch (_: Exception) {}
        session.file.delete()
    }

    companion object {
        const val JS_NAME = "ArenaNative"
    }
}

package com.nomedia.backup

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.coroutines.resume

/**
 * Triggers the system MediaScanner so images under a folder get (re)indexed into
 * MediaStore after `.nomedia` is removed — or removed again after it's restored.
 *
 * Two strategies:
 *  - [scanDirectory]: hand the folder path to MediaScannerConnection. On modern Android
 *    (mainline MediaProvider, incl. HyperOS) this recursively walks + reconciles the tree,
 *    which correctly ADDS files (when .nomedia is gone) and REMOVES them (when it's back).
 *    This is the PRIMARY, fastest path — the OS does the recursion in native code and we
 *    only wait for one callback.
 *  - [deepScanFiles]: enumerate every media file via SAF and scan each path in batches.
 *    Slower, but a reliable fallback if the directory scan under-populates on some ROM.
 *    Enumerates with a direct ContentResolver cursor (NOT DocumentFile.listFiles()), which
 *    is the same fast technique used in the jp-media-viewer scanner.
 */
class SystemScanner(private val context: Context) {

    private companion object {
        const val TAG = "SystemScanner"
        const val NOMEDIA = ".nomedia"

        // Progress is throttled so a long deep scan never floods the UI with state updates.
        const val PROGRESS_UPDATE_MIN_INTERVAL_MS = 500L
        const val PROGRESS_UPDATE_INTERVAL = 100
    }

    private val resolver get() = context.contentResolver

    data class DeepProgress(
        val scanned: Int,
        val dirsVisited: Int,
        val currentName: String
    )

    /** Kick a recursive system scan of [dirPath]. Suspends until the scanner reports done. */
    suspend fun scanDirectory(dirPath: String): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(context, arrayOf(dirPath), null) { _, uri ->
            if (cont.isActive) cont.resume(uri)
        }
    }

    /**
     * Walk the SAF tree, collect real filesystem paths of media files, and scan them in
     * batches. Returns the number of media files handed to the scanner.
     *
     * @param respectNomedia when true, a subfolder that contains its own `.nomedia` is
     *   skipped entirely (matching MediaStore's hiding behaviour). Default false so the
     *   fallback captures everything the OS directory scan may have missed.
     */
    suspend fun deepScanFiles(
        manager: NomediaManager,
        treeUri: Uri,
        respectNomedia: Boolean = false,
        batchSize: Int = 800,
        onProgress: suspend (DeepProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        // Volume root is constant for the whole tree — compute it once instead of per file.
        val volumeRoot = volumeRootFor(rootDocId) ?: return@withContext 0

        val queue = ArrayDeque<String>().apply { add(rootDocId) }
        val batch = ArrayList<String>(batchSize)
        var scanned = 0
        var dirs = 0
        var lastProgressAt = 0L

        suspend fun emit(name: String, force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastProgressAt < PROGRESS_UPDATE_MIN_INTERVAL_MS) return
            lastProgressAt = now
            onProgress(DeepProgress(scanned, dirs, name))
        }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dirDocId = queue.poll() ?: continue
            dirs++
            val children = queryChildren(treeUri, dirDocId) ?: continue // permission lost / error
            if (respectNomedia && children.any { !it.isDirectory && it.displayName == NOMEDIA }) continue

            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (child.isDirectory) {
                    queue.add(child.documentId)
                } else if (isMedia(child.mimeType, child.displayName)) {
                    docIdToPath(child.documentId, volumeRoot)?.let { batch.add(it) }
                    scanned++
                    if (batch.size >= batchSize) {
                        scanPaths(batch.toTypedArray())
                        batch.clear()
                        emit(child.displayName, false)
                    }
                }
            }
        }
        if (batch.isNotEmpty()) {
            scanPaths(batch.toTypedArray())
            batch.clear()
        }
        emit("", true)
        scanned
    }

    private suspend fun scanPaths(paths: Array<String>) = suspendCancellableCoroutine<Unit> { cont ->
        var remaining = paths.size
        if (remaining == 0) { cont.resume(Unit); return@suspendCancellableCoroutine }
        MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
            synchronized(cont) {
                remaining--
                if (remaining <= 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }

    /**
     * Direct cursor enumeration of a directory's children — the fast path borrowed from
     * jp-media-viewer's MediaScanner. Returns null when the permission is gone or the
     * directory can't be read, so callers can skip rather than crash.
     */
    private fun queryChildren(treeUri: Uri, parentDocId: String): List<ChildEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val entries = mutableListOf<ChildEntry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    entries.add(ChildEntry(docId, name, mime, mime == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
            entries
        } catch (sec: SecurityException) {
            // Persisted permission dropped — caller records this dir as skipped.
            Log.w(TAG, "权限失效，跳过一个目录: $parentDocId", sec)
            null
        } catch (e: Exception) {
            Log.w(TAG, "读取目录失败: $parentDocId", e)
            null
        }
    }

    private fun docIdToPath(docId: String, volumeRoot: String): String? {
        // docId: "primary:Big/sub/img.jpg" -> /storage/emulated/0/Big/sub/img.jpg
        val rel = docId.substringAfter(':', "")
        if (rel.isBlank()) return null
        return "$volumeRoot/$rel"
    }

    private fun volumeRootFor(docId: String): String? {
        val volume = docId.substringBefore(':')
        return if (volume.equals("primary", ignoreCase = true)) {
            android.os.Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
    }

    private fun isMedia(mime: String, name: String): Boolean {
        if (mime.startsWith("image/") || mime.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
            "mp4", "mkv", "webm", "mov", "avi", "3gp"
        )
    }

    /**
     * Count media already indexed under [folderPath] (for live progress). Requires
     * READ_MEDIA_IMAGES/VIDEO. Returns -1 if the query is not permitted/available.
     */
    fun countIndexed(folderPath: String): Int {
        val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
        val args = arrayOf("$folderPath/%")
        var total = 0
        var ok = false
        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use {
                    total += it.count
                    ok = true
                }
            } catch (_: Exception) {
                return -1
            }
        }
        return if (ok) total else -1
    }

    private data class ChildEntry(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val isDirectory: Boolean
    )
}

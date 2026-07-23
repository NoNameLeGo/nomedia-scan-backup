package com.nomedia.backup

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
 *  - [deepScanFiles]: enumerate every media file via SAF and scan each path individually.
 *    Slower, but a reliable fallback if the directory scan under-populates on some ROM.
 */
class SystemScanner(private val context: Context) {

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
     */
    suspend fun deepScanFiles(
        manager: NomediaManager,
        treeUri: Uri,
        batchSize: Int = 800,
        onProgress: suspend (DeepProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val basePath = manager.realPath(treeUri) ?: return@withContext 0
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

        val batch = ArrayList<String>(batchSize)
        var scanned = 0
        var dirs = 0

        // BFS over document ids; derive file path directly from each docId.
        val queue = ArrayDeque<String>()
        queue.add(rootDocId)

        suspend fun flush() {
            if (batch.isEmpty()) return
            scanPaths(batch.toTypedArray())
            scanned += batch.size
            batch.clear()
        }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dirDocId = queue.poll() ?: continue
            dirs++
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            try {
                resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: ""
                        val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(docId)
                        } else if (isMedia(mime, name)) {
                            docIdToPath(docId, basePath)?.let { batch.add(it) }
                            if (batch.size >= batchSize) {
                                flush()
                                onProgress(DeepProgress(scanned, dirs, name))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip unreadable dirs.
            }
        }
        flush()
        onProgress(DeepProgress(scanned, dirs, ""))
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

    private fun docIdToPath(docId: String, @Suppress("UNUSED_PARAMETER") basePath: String): String? {
        // docId: "primary:Big/sub/img.jpg" -> /storage/emulated/0/Big/sub/img.jpg
        val rel = docId.substringAfter(':', "")
        if (rel.isBlank()) return null
        val root = volumeRootFor(docId) ?: return null
        return "$root/$rel"
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
}

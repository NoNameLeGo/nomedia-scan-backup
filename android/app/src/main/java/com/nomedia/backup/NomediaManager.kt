package com.nomedia.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Manages the .nomedia toggle inside a SAF tree, plus deriving the folder's real
 * filesystem path (needed to trigger the system media scanner).
 *
 * "Hidden"  = a `.nomedia` file exists at the folder root -> system won't index it.
 * "Visible" = no `.nomedia` at the root (we rename it to `.nomedia.bak`).
 *
 * We prefer rename (reversible move) over delete/create so the original file is never lost.
 */
class NomediaManager(private val context: Context) {

    companion object {
        const val NOMEDIA = ".nomedia"
        const val NOMEDIA_BAK = ".nomedia.bak"
    }

    enum class Status { HIDDEN, VISIBLE, UNKNOWN }

    private val resolver get() = context.contentResolver

    private data class Child(val docId: String, val name: String)

    private fun rootDocId(treeUri: Uri): String =
        DocumentsContract.getTreeDocumentId(treeUri)

    private fun listRootChildren(treeUri: Uri): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId(treeUri))
        val out = mutableListOf<Child>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                out.add(Child(id, name))
            }
        }
        return out
    }

    private fun childUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun findChild(treeUri: Uri, name: String): Child? =
        listRootChildren(treeUri).firstOrNull { it.name == name }

    fun status(treeUri: Uri): Status = try {
        val children = listRootChildren(treeUri)
        when {
            children.any { it.name == NOMEDIA } -> Status.HIDDEN
            else -> Status.VISIBLE
        }
    } catch (e: Exception) {
        Status.UNKNOWN
    }

    /**
     * Make the folder scannable: rename `.nomedia` -> `.nomedia.bak`.
     * Returns true on success (or if already visible).
     */
    fun moveOut(treeUri: Uri): Boolean {
        val nomedia = findChild(treeUri, NOMEDIA) ?: return true // already visible
        // Clear any stale backup so rename target is free.
        findChild(treeUri, NOMEDIA_BAK)?.let {
            try { DocumentsContract.deleteDocument(resolver, childUri(treeUri, it.docId)) } catch (_: Exception) {}
        }
        return try {
            DocumentsContract.renameDocument(resolver, childUri(treeUri, nomedia.docId), NOMEDIA_BAK)
            findChild(treeUri, NOMEDIA) == null
        } catch (e: Exception) {
            // Fallback: delete outright (still reversible via re-create).
            try {
                DocumentsContract.deleteDocument(resolver, childUri(treeUri, nomedia.docId))
                findChild(treeUri, NOMEDIA) == null
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Re-hide the folder: restore `.nomedia.bak` -> `.nomedia`, or create a fresh `.nomedia`.
     * Returns true on success.
     */
    fun moveIn(treeUri: Uri): Boolean {
        if (findChild(treeUri, NOMEDIA) != null) return true // already hidden
        val bak = findChild(treeUri, NOMEDIA_BAK)
        if (bak != null) {
            try {
                DocumentsContract.renameDocument(resolver, childUri(treeUri, bak.docId), NOMEDIA)
                if (findChild(treeUri, NOMEDIA) != null) return true
            } catch (_: Exception) { /* fall through to create */ }
        }
        return createNomedia(treeUri)
    }

    private fun createNomedia(treeUri: Uri): Boolean {
        return try {
            val rootUri = childUri(treeUri, rootDocId(treeUri))
            val created = DocumentsContract.createDocument(
                resolver, rootUri, "application/octet-stream", NOMEDIA
            ) ?: return false
            // Some providers append an extension; verify and rename back if needed.
            if (findChild(treeUri, NOMEDIA) != null) return true
            // Try to fix a mangled name.
            listRootChildren(treeUri).firstOrNull { it.name.startsWith(NOMEDIA) && it.name != NOMEDIA }?.let {
                try {
                    DocumentsContract.renameDocument(resolver, childUri(treeUri, it.docId), NOMEDIA)
                } catch (_: Exception) {}
            }
            findChild(treeUri, NOMEDIA) != null
        } catch (e: Exception) {
            false
        }
    }

    /** Human-readable folder name from the tree URI. */
    fun displayName(treeUri: Uri): String {
        val docId = rootDocId(treeUri)
        val after = docId.substringAfter(':', docId)
        return after.substringAfterLast('/', after).ifBlank { docId }
    }

    /**
     * Derive the absolute filesystem path of the selected folder from its tree URI.
     * Works for primary shared storage (/storage/emulated/0/...) and named volumes.
     * Returns null if it can't be resolved (e.g. non-standard provider).
     */
    fun realPath(treeUri: Uri): String? {
        val docId = rootDocId(treeUri)
        val parts = docId.split(":", limit = 2)
        if (parts.isEmpty()) return null
        val volume = parts[0]
        val rel = parts.getOrElse(1) { "" }
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (rel.isBlank()) base else "$base/$rel"
    }
}

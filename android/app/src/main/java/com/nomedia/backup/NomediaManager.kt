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

    /** Create a fresh `.nomedia` at the folder root (force-hide). Public so the UI can call it directly. */
    fun createNomedia(treeUri: Uri): Boolean {
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

    // ---- 递归处理整棵子树里的 .nomedia（含子文件夹各自带的 .nomedia） ----

    private fun parentDocId(docId: String): String {
        val idx = docId.lastIndexOf('/')
        return if (idx < 0) docId else docId.substring(0, idx)
    }

    /** 遍历整棵子树（只走目录、不枚举文件），收集所有名为 [name] 的文档 URI。 */
    private fun findByName(treeUri: Uri, name: String): List<Uri> {
        val result = mutableListOf<Uri>()
        val queue = ArrayDeque<String>().apply { add(rootDocId(treeUri)) }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        while (queue.isNotEmpty()) {
            val dirId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val childName = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    if (childName == name) result.add(childUri(treeUri, id))
                    else if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(id)
                }
            } ?: break // 权限失效 / 异常：停止遍历，返回已收集结果
        }
        return result
    }

    fun findAllNomedia(treeUri: Uri): List<Uri> = findByName(treeUri, NOMEDIA)
    fun findAllNomediaBak(treeUri: Uri): List<Uri> = findByName(treeUri, NOMEDIA_BAK)

    /** 把单个 .nomedia 改名为 .nomedia.bak（同目录内先清掉残留的 .bak，避免 rename 冲突）。 */
    private fun safeRenameToBak(treeUri: Uri, nomediaUri: Uri): Boolean {
        val parentId = parentDocId(DocumentsContract.getDocumentId(nomediaUri))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == NOMEDIA_BAK) {
                    try { DocumentsContract.deleteDocument(resolver, childUri(treeUri, c.getString(0))) } catch (_: Exception) {}
                }
            }
        }
        return try {
            DocumentsContract.renameDocument(resolver, nomediaUri, NOMEDIA_BAK)
            true
        } catch (_: Exception) { false }
    }

    /** 把单个 .nomedia.bak 还原为 .nomedia（同目录内先清掉残留的 .nomedia）。 */
    private fun safeRenameToNomedia(treeUri: Uri, bakUri: Uri): Boolean {
        val parentId = parentDocId(DocumentsContract.getDocumentId(bakUri))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == NOMEDIA) {
                    try { DocumentsContract.deleteDocument(resolver, childUri(treeUri, c.getString(0))) } catch (_: Exception) {}
                }
            }
        }
        return try {
            DocumentsContract.renameDocument(resolver, bakUri, NOMEDIA)
            true
        } catch (_: Exception) { false }
    }

    /** 让整棵子树可见：把所有 .nomedia（含子文件夹）改名为 .nomedia.bak。返回处理的数量。 */
    fun setVisible(treeUri: Uri): Int {
        var count = 0
        for (uri in findAllNomedia(treeUri)) {
            if (safeRenameToBak(treeUri, uri)) count++
        }
        return count
    }

    /** 让整棵子树隐藏：把所有 .nomedia.bak 还原为 .nomedia；若整树原本没有任何 .nomedia，则在根目录补建一个。返回处理的数量。 */
    fun setHidden(treeUri: Uri): Int {
        var count = 0
        for (uri in findAllNomediaBak(treeUri)) {
            if (safeRenameToNomedia(treeUri, uri)) count++
        }
        if (findAllNomedia(treeUri).isEmpty()) {
            if (createNomedia(treeUri)) count++
        }
        return count
    }

    /** 整棵子树状态：任意目录含 .nomedia 即视为 HIDDEN。返回 (状态, .nomedia 数量)。 */
    fun statusAll(treeUri: Uri): Pair<Status, Int> {
        val all = findAllNomedia(treeUri)
        return (if (all.isNotEmpty()) Status.HIDDEN else Status.VISIBLE) to all.size
    }
}

package com.nomedia.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * 只管理所选文件夹【根目录】的 .nomedia 开关。
 *
 * "隐藏"  = 根目录有 `.nomedia` -> 系统不索引该文件夹。
 * "可见"  = 根目录无 `.nomedia`（改名为 `.nomedia.bak` 保留可逆）。
 *
 * 开关【只影响根目录】，不递归处理子文件夹：子文件夹自带的 .nomedia 保持原状，
 * 既不会被新增、也不会被改名。优先用改名（rename）而非删除/新建，原始文件不会丢失。
 */
class NomediaManager(private val context: Context) {

    companion object {
        const val NOMEDIA = ".nomedia"
        const val NOMEDIA_BAK = ".nomedia.bak"
    }

    enum class Status { HIDDEN, VISIBLE, UNKNOWN }

    private val resolver get() = context.contentResolver

    private fun rootDocId(treeUri: Uri): String =
        DocumentsContract.getTreeDocumentId(treeUri)

    private fun childUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun listRootChildren(treeUri: Uri): List<Pair<String, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId(treeUri))
        val out = mutableListOf<Pair<String, String>>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                out.add(id to name)
            }
        }
        return out
    }

    private fun findChild(treeUri: Uri, name: String): Pair<String, String>? =
        listRootChildren(treeUri).firstOrNull { it.second == name }

    /** 在根目录新建一个 .nomedia（强制隐藏）。 */
    fun createNomedia(treeUri: Uri): Boolean {
        return try {
            val rootUri = childUri(treeUri, rootDocId(treeUri))
            val created = DocumentsContract.createDocument(
                resolver, rootUri, "application/octet-stream", NOMEDIA
            ) ?: return false
            if (findChild(treeUri, NOMEDIA) != null) return true
            listRootChildren(treeUri).firstOrNull { it.second.startsWith(NOMEDIA) && it.second != NOMEDIA }?.let {
                try {
                    DocumentsContract.renameDocument(resolver, childUri(treeUri, it.first), NOMEDIA)
                } catch (_: Exception) {}
            }
            findChild(treeUri, NOMEDIA) != null
        } catch (e: Exception) {
            false
        }
    }

    /** 文件夹名（来自 tree URI）。 */
    fun displayName(treeUri: Uri): String {
        val docId = rootDocId(treeUri)
        val after = docId.substringAfter(':', docId)
        return after.substringAfterLast('/', after).ifBlank { docId }
    }

    /** 由 tree URI 推导所选文件夹的真实文件系统路径。 */
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

    /** 隐藏：确保根目录有 .nomedia（无则创建）。不触碰任何子文件夹。返回根目录是否处于隐藏状态。 */
    fun setHidden(treeUri: Uri): Boolean {
        if (findChild(treeUri, NOMEDIA) != null) return true
        return createNomedia(treeUri)
    }

    /** 显示：若根目录有 .nomedia 则改名为 .nomedia.bak（可逆）。不触碰子文件夹。 */
    fun setVisible(treeUri: Uri): Boolean {
        val nomedia = findChild(treeUri, NOMEDIA) ?: return true // 已经可见
        findChild(treeUri, NOMEDIA_BAK)?.let {
            try { DocumentsContract.deleteDocument(resolver, childUri(treeUri, it.first)) } catch (_: Exception) {}
        }
        return try {
            DocumentsContract.renameDocument(resolver, childUri(treeUri, nomedia.first), NOMEDIA_BAK)
            findChild(treeUri, NOMEDIA) == null
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(resolver, childUri, nomedia.first)
                findChild(treeUri, NOMEDIA) == null
            } catch (e2: Exception) { false }
        }
    }

    /** 根目录状态：有 .nomedia = HIDDEN。返回 (状态, 根目录 .nomedia 数量 0/1)。 */
    fun statusRoot(treeUri: Uri): Pair<Status, Int> {
        val has = findChild(treeUri, NOMEDIA) != null
        return (if (has) Status.HIDDEN else Status.VISIBLE) to (if (has) 1 else 0)
    }
}

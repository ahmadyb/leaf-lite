package com.leaflite.viewer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Walks SAF document trees the user has granted ("Add folder to library").
 *
 * This is the only discovery channel that is *guaranteed* to work on API 30+,
 * where scoped storage hides other apps' documents from MediaStore. No
 * DocumentFile dependency: the DocumentsContract tree APIs are enough.
 */
class SafTreeScanner(private val context: Context) {

    data class Entry(
        val uri: String,
        val displayName: String,
        val mime: String?,
        val size: Long,
        val dateModified: Long
    )

    /** Recursively collects supported documents under [treeUri]. */
    fun scan(treeUri: Uri, limit: Int = MAX_ROWS): List<Entry> {
        if (!DocumentsContract.isTreeUri(treeUri)) return emptyList()
        val out = ArrayList<Entry>(128)
        walk(treeUri, DocumentsContract.getTreeDocumentId(treeUri), out, limit, depth = 0)
        return out
    }

    private fun walk(tree: Uri, documentId: String, out: MutableList<Entry>, limit: Int, depth: Int) {
        if (out.size >= limit || depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = try {
            context.contentResolver.query(children, projection, null, null, null)
        } catch (t: Throwable) {
            null
        } ?: return

        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val dateIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (it.moveToNext() && out.size < limit) {
                val id = it.getString(idIndex) ?: continue
                val name = it.getString(nameIndex) ?: continue
                val mime = it.getString(mimeIndex)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, id, out, limit, depth + 1)
                } else {
                    if (!isSupported(name, mime)) continue
                    val size = it.getLong(sizeIndex)
                    if (size <= 0L) continue
                    out += Entry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                        displayName = name,
                        mime = mime,
                        size = size,
                        dateModified = it.getLong(dateIndex)
                    )
                }
            }
        }
    }

    private fun isSupported(name: String, mime: String?): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot in 0 until name.length - 1) {
            val ext = name.substring(dot + 1).lowercase()
            if (ext in EXTENSIONS) return true
        }
        if (mime == null) return false
        return DocType.fromMime(mime) != null
    }

    /** Display name of a granted tree root, for the "folders" chip/label. */
    fun treeLabel(treeUri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val MAX_ROWS = 5000
        const val MAX_DEPTH = 12
        val EXTENSIONS = setOf("pdf", "docx", "xlsx", "pptx", "txt", "md", "markdown", "text")
    }
}

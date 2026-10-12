package com.leaflite.viewer.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Discovers documents already on the device through MediaStore.Files.
 *
 * * API 29+: no permission required (scoped storage), so we never prompt.
 * * API 23-28: the caller must hold READ_EXTERNAL_STORAGE; [scan] returns
 *   `null` when it does not, so the UI can fall back to the SAF import card.
 *
 * MIME type is frequently null in MediaStore, so the query always falls back to
 * a file-name match on the five supported extensions.
 */
class MediaStoreScanner(private val context: Context) {

    data class Entry(
        val uri: String,
        val displayName: String,
        val mime: String?,
        val size: Long,
        val dateModified: Long
    )

    fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Returns the discovered entries, or `null` when storage access is denied. */
    fun scan(): List<Entry>? {
        if (!hasStoragePermission()) return null

        val resolver = context.contentResolver
        val baseUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val args = ArrayList<String>()
        val mimeClauses = ArrayList<String>()
        DocType.values().forEach { type ->
            type.mimeTypes.forEach { mime ->
                mimeClauses.add("${MediaStore.Files.FileColumns.MIME_TYPE} = ?")
                args.add(mime)
            }
        }
        val nameClauses = ArrayList<String>()
        EXTENSIONS.forEach { ext ->
            nameClauses.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            args.add("%.$ext")
        }

        val selection = buildString {
            append('(')
            append(mimeClauses.joinToString(" OR "))
            append(" OR ")
            append(nameClauses.joinToString(" OR "))
            append(") AND ")
            append(MediaStore.Files.FileColumns.SIZE).append(" > 0 AND ")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE).append(" = ")
            append(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
        }

        val entries = ArrayList<Entry>(256)
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                baseUri,
                projection,
                selection,
                args.toTypedArray(),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT $MAX_ROWS"
            )
            if (cursor == null) return emptyList()
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                entries += Entry(
                    uri = ContentUris.withAppendedId(baseUri, id).toString(),
                    displayName = name,
                    mime = cursor.getString(mimeIndex),
                    size = cursor.getLong(sizeIndex),
                    dateModified = cursor.getLong(dateIndex) * 1000L
                )
            }
        } catch (security: SecurityException) {
            return null
        } catch (other: Exception) {
            return if (entries.isEmpty()) null else entries
        } finally {
            cursor?.close()
        }
        return entries
    }

    private companion object {
        const val MAX_ROWS = 5000
        val EXTENSIONS = listOf("pdf", "docx", "xlsx", "txt", "md", "markdown", "text")
    }
}

package com.leaflite.viewer.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Discovers documents already on the device through MediaStore.
 *
 * Query strategy per API level:
 *
 * * API 23-28: `MediaStore.Files` with `READ_EXTERNAL_STORAGE` (runtime prompt).
 * * API 29-32: `READ_EXTERNAL_STORAGE` still grants access to other apps'
 *              files (incl. downloads), so ask for it and query both tables.
 * * API 33+:   `READ_EXTERNAL_STORAGE` no longer exists for documents;
 *              All files access (`MANAGE_EXTERNAL_STORAGE`) unlocks the full
 *              MediaStore scan. Without either grant the scan is best-effort
 *              (own files only) and "Add folder to library" is the fallback.
 *
 * MIME type is frequently null in MediaStore, so every query matches on file
 * name extensions as well. `LIMIT` in the sort order is not accepted by every
 * OEM provider, so it is only a fast path; failures retry without it and trim
 * in memory.
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
        // Android 13+ removed the document-wide storage permission; the scan
        // is best-effort there (own files + whatever the ROM still exposes).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Best-effort discovery; own files are returned even without a grant. */
    fun scan(): List<Entry> {
        val entries = LinkedHashMap<String, Entry>(256)

        // Downloads first: on API 29+ other apps' documents live there and the
        // Files table under scoped storage only surfaces media.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collect(MediaStore.Downloads.EXTERNAL_CONTENT_URI, entries)
        }
        collect(MediaStore.Files.getContentUri("external"), entries)
        return entries.values.toList()
    }

    private fun collect(baseUri: android.net.Uri, out: LinkedHashMap<String, Entry>) {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val args = ArrayList<String>()
        val nameClauses = ArrayList<String>()
        DocType.values().forEach { type ->
            type.mimeTypes.forEach { mime ->
                nameClauses.add("${MediaStore.Files.FileColumns.MIME_TYPE} = ?")
                args.add(mime)
            }
        }
        EXTENSIONS.forEach { ext ->
            nameClauses.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            args.add("%.$ext")
        }
        val selection = nameClauses.joinToString(" OR ")

        var cursor: Cursor? = null
        try {
            cursor = try {
                context.contentResolver.query(
                    baseUri,
                    projection,
                    selection,
                    args.toTypedArray(),
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT $MAX_ROWS"
                )
            } catch (rejected: IllegalArgumentException) {
                // Some providers refuse LIMIT in the sort order.
                context.contentResolver.query(
                    baseUri,
                    projection,
                    selection,
                    args.toTypedArray(),
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )
            }
            if (cursor == null) return
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            while (cursor.moveToNext() && out.size < MAX_ROWS) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex) ?: continue
                val size = cursor.getLong(sizeIndex)
                if (size <= 0L) continue
                val uri = ContentUris.withAppendedId(baseUri, id).toString()
                if (uri in out) continue
                out[uri] = Entry(
                    uri = uri,
                    displayName = name,
                    mime = cursor.getString(mimeIndex),
                    size = size,
                    dateModified = cursor.getLong(dateIndex) * 1000L
                )
            }
        } catch (security: SecurityException) {
            // No access to this table on this API level — skip it silently;
            // the SAF folder scan is the reliable channel.
        } catch (other: Exception) {
            // Never fail the whole scan because one collection misbehaves.
        } finally {
            cursor?.close()
        }
    }

    internal companion object {
        const val MAX_ROWS = 5000
        val EXTENSIONS = listOf("pdf", "docx", "xlsx", "pptx", "txt", "md", "markdown", "text")
    }
}

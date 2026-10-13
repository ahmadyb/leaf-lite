package com.leaflite.viewer.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Copies a document into the FileProvider-exposed `cache/shared` directory so
 * other apps can receive it through ACTION_SEND / ACTION_VIEW. Nothing outside
 * the cache is ever shared (see `res/xml/file_paths.xml`).
 */
object ShareOut {

    data class Export(val uri: Uri?, val mime: String?)

    /** Copies [uri]'s content and returns a grantable content URI. */
    fun export(context: Context, uri: Uri, name: String, mime: String?): Export {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "document" }
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, safeName)
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Export(null, null)
            input.use { source ->
                file.outputStream().use { output -> source.copyTo(output) }
            }
            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Export(shareUri, mime ?: "application/octet-stream")
        } catch (t: Throwable) {
            Export(null, null)
        }
    }
}

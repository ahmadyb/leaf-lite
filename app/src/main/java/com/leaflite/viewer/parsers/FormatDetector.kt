package com.leaflite.viewer.parsers

import com.leaflite.viewer.data.DocType
import java.io.InputStream

/**
 * Decides what a file is: extension first, MIME type as corroboration, magic
 * bytes as the last resort (extension-less downloads, mislabelled files).
 *
 * Pure Kotlin/java.io so it can be unit tested on the JVM.
 */
object FormatDetector {

    private const val SNIFF_BYTES = 8192

    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)      // "%PDF-"
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)            // "PK.."

    fun fromName(name: String?): DocType? {
        if (name.isNullOrBlank()) return null
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return when (name.substring(dot + 1).lowercase()) {
            "pdf" -> DocType.PDF
            "docx" -> DocType.DOCX
            "xlsx" -> DocType.XLSX
            "txt", "text" -> DocType.TXT
            "md", "markdown" -> DocType.MD
            else -> null
        }
    }

    fun fromMime(mime: String?): DocType? = DocType.fromMime(mime)

    /**
     * @param open supplies a fresh stream; it may be called twice (once for the
     *             magic bytes, once to probe a ZIP central directory).
     */
    fun detect(name: String?, mime: String?, open: () -> InputStream?): DocType? {
        fromName(name)?.let { return it }
        fromMime(mime)?.let { return it }
        return sniff(open)
    }

    /** Magic-byte sniffing for files without a usable name or MIME type. */
    fun sniff(open: () -> InputStream?): DocType? {
        val stream = try {
            open()
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            val head = ByteArray(SNIFF_BYTES)
            var read = 0
            while (read < 4) {
                val n = stream.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < 4) return null
            when {
                matches(head, PDF_MAGIC) -> DocType.PDF
                matches(head, ZIP_MAGIC) -> probeZip(open)
                looksLikeText(head, read) -> DocType.TXT
                else -> null
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { stream.close() }
        }
    }

    /** OOXML files are ZIPs: look inside to tell Word from Excel. */
    private fun probeZip(open: () -> InputStream?): DocType? {
        val names = Zipx.entryNames(open, limit = 128)
        return when {
            names.contains("word/document.xml") -> DocType.DOCX
            names.contains("xl/workbook.xml") -> DocType.XLSX
            else -> null
        }
    }

    private fun matches(head: ByteArray, magic: ByteArray): Boolean {
        if (head.size < magic.size) return false
        for (i in magic.indices) if (head[i] != magic[i]) return false
        return true
    }

    /** Cheap heuristic: no control bytes (besides tab/LF/CR) means text. */
    private fun looksLikeText(head: ByteArray, length: Int): Boolean {
        for (i in 0 until length) {
            val b = head[i].toInt() and 0xFF
            if (b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0C) continue
            if (b < 0x20) return false
            if (b == 0x7F) return false
        }
        return true
    }
}

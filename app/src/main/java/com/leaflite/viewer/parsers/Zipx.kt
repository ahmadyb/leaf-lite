package com.leaflite.viewer.parsers

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Minimal ZIP reader for OOXML packages (DOCX / XLSX).
 *
 * Everything is streamed straight from the ContentResolver: there is no cache
 * copy on disk and no `ZipFile` (which needs a random-access file). Entries we
 * do not care about are skipped without being inflated into memory.
 */
internal object Zipx {

    /** Streams the entry called [name] into [block]; `null` when not present. */
    inline fun <T> withEntry(
        open: () -> InputStream?,
        name: String,
        block: (InputStream) -> T
    ): T? {
        val zis = ZipInputStream(BufferedInputStream(open() ?: return null))
        return try {
            while (true) {
                val entry = zis.nextEntry ?: return null
                if (!entry.isDirectory && entry.name == name) {
                    return try {
                        block(LimitInputStream(zis, MAX_ENTRY_BYTES))
                    } catch (t: Throwable) {
                        null
                    }
                }
                zis.closeEntry()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } finally {
            runCatching { zis.close() }
        }
    }

    /** The first [limit] entry names in the archive. */
    fun entryNames(open: () -> InputStream?, limit: Int = 128): Set<String> {
        val names = LinkedHashSet<String>()
        forEachEntry(open) { entry, _ ->
            if (names.size < limit) names += entry.name
            false
        }
        return names
    }

    /**
     * Walks the archive. [visitor] returns `true` when it consumed the entry
     * data, `false` to skip the payload.
     */
    inline fun forEachEntry(
        open: () -> InputStream?,
        visitor: (ZipEntry, ZipInputStream) -> Boolean
    ) {
        val zis = ZipInputStream(BufferedInputStream(open() ?: return))
        try {
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    try {
                        visitor(entry, zis)
                    } catch (t: Throwable) {
                        // A single malformed part must never kill the document.
                    }
                }
                zis.closeEntry()
            }
        } finally {
            runCatching { zis.close() }
        }
    }

    const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
}

/**
 * Safety valve: stops reading after [limit] bytes so a hostile or corrupt
 * archive can never exhaust the heap (no `largeHeap` in this app).
 */
internal class LimitInputStream(source: InputStream, private val limit: Long) :
    FilterInputStream(source) {

    private var count = 0L

    @Throws(IOException::class)
    override fun read(): Int {
        ensure()
        val value = super.read()
        if (value >= 0) count++
        return value
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        ensure()
        val read = super.read(buffer, offset, length)
        if (read > 0) count += read
        return read
    }

    @Throws(IOException::class)
    override fun skip(count: Long): Long {
        val skipped = super.skip(count)
        this.count += skipped
        return skipped
    }

    private fun ensure() {
        if (count > limit) throw IOException("Entry exceeds $limit bytes")
    }
}

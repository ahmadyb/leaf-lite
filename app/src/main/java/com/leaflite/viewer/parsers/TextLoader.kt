package com.leaflite.viewer.parsers

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Plain-text loader with BOM detection and a strict-UTF-8 → single-byte
 * fallback chain. Reads at most [MAX_BYTES] so a 500 MB log cannot OOM the app.
 */
object TextLoader {

    /** 2 MB of source text ≈ 4 MB of Java heap once decoded. */
    const val MAX_BYTES = 2 * 1024 * 1024

    data class Result(val text: String, val truncated: Boolean)

    fun load(open: () -> InputStream?): Result {
        val bytes = read(open) ?: return Result("", false)
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): Result {
        val (charset, offset) = detectCharset(bytes)
        val raw = String(bytes, offset, bytes.size - offset, charset)
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        return Result(normalized, bytes.size >= MAX_BYTES)
    }

    private fun read(open: () -> InputStream?): ByteArray? {
        val stream = try {
            open()
        } catch (t: Throwable) {
            null
        } ?: return null
        return try {
            val buffer = ByteArray(MAX_BYTES + 8)
            var total = 0
            while (total < MAX_BYTES) {
                val n = stream.read(buffer, total, buffer.size - total)
                if (n <= 0) break
                total += n
            }
            if (total == buffer.size) buffer else buffer.copyOf(total)
        } catch (io: IOException) {
            null
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * BOM first; otherwise decode strictly as UTF-8 and fall back to
     * windows-1252 (or ISO-8859-1 when the ROM lacks it) for legacy files.
     */
    private fun detectCharset(bytes: ByteArray): Pair<Charset, Int> {
        if (bytes.size >= 3 &&
            (bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        ) return Charsets.UTF_8 to 3
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2
        }
        if (isValidUtf8(bytes)) return Charsets.UTF_8 to 0
        val fallback = try {
            Charset.forName("windows-1252")
        } catch (t: Throwable) {
            Charsets.ISO_8859_1
        }
        return fallback to 0
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (t: Throwable) {
            false
        }
    }
}

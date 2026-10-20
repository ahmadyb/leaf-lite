package com.leaflite.viewer.parsers

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Inflater

/**
 * Best-effort PDF text extractor for find-in-document.
 *
 * Walks the object table, follows /Catalog -> /Pages -> /Page, decompresses
 * content streams and decodes text-showing operators (Tj, TJ, ', "). Text is
 * mapped through each font's /ToUnicode CMap when present (covers most
 * born-digital PDFs) and falls back to Latin-1 for simple fonts. No external
 * libraries; scanned pages without a text layer yield empty strings.
 *
 * This is a search aid, not a rendering engine: hyphenation, kerning-precision
 * and vertical text are approximated.
 */
object PdfTextExtractor {

    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_OBJECT_STREAM_OBJECTS = 4096
    private val FONT_SELECTION = Regex("""^/([A-Za-z0-9_]+)\s+[+-]?(?:\d+(?:\.\d*)?|\.\d+)\s+Tf\b""")

    /** One string per page (empty string when a page has no text layer). */
    fun extract(open: () -> InputStream?): List<String> {
        val bytes = readAll(open()) ?: return emptyList()
        val objects = collectObjects(bytes)
        if (objects.isEmpty()) return emptyList()

        val catalog = objects.values.firstOrNull { hasType(it, "Catalog") } ?: return emptyList()
        val pagesRef = refValue(catalog, "/Pages") ?: return emptyList()
        val out = ArrayList<String>()
        walkPageTree(pagesRef, objects, out, depth = 0)
        return out
    }

    // ------------------------------------------------------------- raw bytes

    private fun readAll(stream: InputStream?): ByteArray? {
        if (stream == null) return null
        return try {
            stream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0
                while (total < MAX_BYTES) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    total += read
                }
                buffer.toByteArray()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** obj number -> raw object body (between `obj` and `endobj`). */
    private fun collectObjects(bytes: ByteArray): Map<Int, ByteArray> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val result = HashMap<Int, ByteArray>()
        val regex = Regex("(\\d+)\\s+\\d+\\s+obj")
        for (match in regex.findAll(text)) {
            val number = match.groupValues[1].toInt()
            val start = match.range.last + 1
            val end = text.indexOf("endobj", start)
            if (end < 0) continue
            if (number !in result) {
                result[number] = bytes.copyOfRange(start, end)
            }
        }
        expandObjectStreams(result)
        return result
    }

    /** Adds ordinary objects packed inside PDF 1.5+ `/ObjStm` containers. */
    private fun expandObjectStreams(objects: MutableMap<Int, ByteArray>) {
        val containers = objects.values.filter { hasType(it, "ObjStm") }
        for (container in containers) {
            val dictionary = String(container, Charsets.ISO_8859_1)
            val count = Regex("/N\\s+(\\d+)").find(dictionary)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..MAX_OBJECT_STREAM_OBJECTS } ?: continue
            val first = Regex("/First\\s+(\\d+)").find(dictionary)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val decoded = streamBytes(container) ?: continue
            if (first < 0 || first > decoded.size) continue

            val header = String(decoded, 0, first, Charsets.ISO_8859_1)
            val pairs = Regex("(\\d+)\\s+(\\d+)")
                .findAll(header)
                .take(count)
                .mapNotNull { match ->
                    val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val offset = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    number to offset
                }
                .toList()
            if (pairs.size != count) continue

            val payloadSize = decoded.size - first
            pairs.forEachIndexed { index, (number, offset) ->
                val endOffset = pairs.getOrNull(index + 1)?.second ?: payloadSize
                if (number <= 0 || offset < 0 || endOffset < offset || endOffset > payloadSize) {
                    return@forEachIndexed
                }
                if (number !in objects) {
                    objects[number] = decoded.copyOfRange(first + offset, first + endOffset)
                }
            }
        }
    }

    // ----------------------------------------------------------- page tree

    private fun walkPageTree(
        ref: String,
        objects: Map<Int, ByteArray>,
        out: MutableList<String>,
        depth: Int
    ) {
        if (depth > 32 || out.size > 4096) return
        val obj = resolve(ref, objects) ?: return
        val body = String(obj, Charsets.ISO_8859_1)
        if (hasType(body, "Page") || (!body.contains("/Type") && body.contains("/Contents"))) {
            out += extractPageText(obj, objects)
            return
        }
        // /Pages node: descend into /Kids
        val kids = body.substringAfter("/Kids", "").let { tail ->
            val open = tail.indexOf('[')
            val close = tail.indexOf(']')
            if (open in 0 until close) tail.substring(open + 1, close) else ""
        }
        for (kid in Regex("\\d+\\s+\\d+\\s+R").findAll(kids)) {
            walkPageTree(kid.value, objects, out, depth + 1)
        }
    }

    // --------------------------------------------------------- page content

    private fun extractPageText(pageObj: ByteArray, objects: Map<Int, ByteArray>): String {
        val body = String(pageObj, Charsets.ISO_8859_1)
        val content = StringBuilder()

        // /Contents may be one ref or an array of refs.
        val contentsTail = body.substringAfter("/Contents", "")
        val contentRefs = Regex("\\d+\\s+\\d+\\s+R").findAll(contentsTail.take(80)).map { it.value }.toList()
        val streams = ArrayList<ByteArray>()
        for (ref in contentRefs) {
            val stream = resolve(ref, objects)?.let { streamBytes(it) }
            if (stream != null) streams += stream
        }
        if (streams.isEmpty()) return ""

        // Font name -> code map, from /Resources /Font.
        val fontMaps = collectFontMaps(body, objects)

        for (stream in streams) {
            content.append(readTextOps(stream, fontMaps))
        }
        return content.toString().replace(Regex("[ \\t]+"), " ").trim()
    }

    private fun collectFontMaps(pageBody: String, objects: Map<Int, ByteArray>): Map<String, CodeMap> {
        val result = HashMap<String, CodeMap>()
        val resourcesTail = pageBody.substringAfter("/Resources", "").take(400)
        val fontDict = resourcesTail.substringAfter("/Font", "")
        val open = fontDict.indexOf("<<")
        val close = fontDict.indexOf(">>")
        if (open !in 0 until close) return result
        val inner = fontDict.substring(open + 2, close)
        // /F1 12 0 R /F2 15 0 R …
        val entry = Regex("/(\\w+)\\s+(\\d+\\s+\\d+\\s+R)")
        for (match in entry.findAll(inner)) {
            val name = match.groupValues[1]
            val fontObj = resolve(match.groupValues[2], objects) ?: continue
            result[name] = fontCodeMap(fontObj, objects)
        }
        return result
    }

    private fun fontCodeMap(fontObj: ByteArray, objects: Map<Int, ByteArray>): CodeMap {
        val body = String(fontObj, Charsets.ISO_8859_1)
        val twoByte = body.contains("/Type0") || body.contains("/Type0 ")
        val toUnicode = Regex("/ToUnicode\\s+(\\d+\\s+\\d+\\s+R)").find(body)
            ?.let { resolve(it.groupValues[1], objects) }
            ?.let { streamBytes(it) }
            ?.let { parseCMap(it) }
        return CodeMap(twoByte, toUnicode ?: HashMap())
    }

    // --------------------------------------------------------------- streams

    /** Returns the decoded stream bytes of a `stream … endstream` object. */
    private fun streamBytes(obj: ByteArray): ByteArray? {
        val body = String(obj, Charsets.ISO_8859_1)
        val marker = body.indexOf("stream")
        if (marker < 0) return null
        var start = marker + "stream".length
        if (start < obj.size && obj[start].toInt().toChar() == '\r') start++
        if (start < obj.size && obj[start].toInt().toChar() == '\n') start++
        var end = body.indexOf("endstream", start)
        if (end < 0) end = obj.size
        val raw = obj.copyOfRange(start, end)
        // Trim the trailing EOL that belongs to `endstream`.
        var trimmed = raw.size
        while (trimmed > 0 && (raw[trimmed - 1] == '\n'.code.toByte() || raw[trimmed - 1] == '\r'.code.toByte())) {
            trimmed--
        }
        val data = raw.copyOfRange(0, trimmed)
        return if (body.contains("/FlateDecode")) inflate(data) ?: data else data
    }

    /**
     * PDF FlateDecode streams are zlib-wrapped (nowrap=false). A few malformed
     * producers write raw DEFLATE instead, so retry that form only if the
     * standards-compliant zlib decode fails.
     */
    private fun inflate(data: ByteArray): ByteArray? =
        inflate(data, nowrap = false) ?: inflate(data, nowrap = true)

    private fun inflate(data: ByteArray, nowrap: Boolean): ByteArray? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream((data.size * 4).coerceAtMost(64 * 1024))
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                if (count > 0) {
                    out.write(chunk, 0, count)
                    if (out.size() > 24 * 1024 * 1024) return null
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    return null
                } else {
                    // No input or output progress: reject rather than loop forever.
                    return null
                }
            }
            out.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            inflater.end()
        }
    }

    // --------------------------------------------------------------- CMaps

    /** src code (as string key of 1-2 bytes hex) -> decoded replacement text. */
    private fun parseCMap(bytes: ByteArray): HashMap<String, String> {
        val map = HashMap<String, String>()
        val text = String(bytes, Charsets.ISO_8859_1)

        for (block in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            for (pair in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1])) {
                map[normalizeKey(pair.groupValues[1])] = utf16Hex(pair.groupValues[2])
            }
        }
        for (block in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            for (entry in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<([0-9A-Fa-f]+)>|\\[([^\\]]*)\\])")
                    .findAll(block.groupValues[1])) {
                val lo = entry.groupValues[1].toInt(16)
                val hi = entry.groupValues[2].toInt(16)
                if (hi - lo > 65535) continue
                val array = entry.groupValues[5]
                if (array.isNotEmpty()) {
                    val targets = Regex("<([0-9A-Fa-f]+)>").findAll(array).map { utf16Hex(it.groupValues[1]) }.toList()
                    for (i in targets.indices) {
                        map[keyFor(lo + i, entry.groupValues[1].length)] = targets[i]
                    }
                } else {
                    val base = entry.groupValues[4].toInt(16)
                    for (i in 0..(hi - lo)) {
                        map[keyFor(lo + i, entry.groupValues[1].length)] = codeToString(base + i)
                    }
                }
            }
        }
        return map
    }

    private fun normalizeKey(hex: String): String = hex.lowercase().padStart(4, '0').let {
        // keep 2-byte keys 4 hex digits, 1-byte keys 2
        if (hex.length <= 2) it.takeLast(2) else it
    }

    private fun keyFor(code: Int, hexLen: Int): String =
        code.toString(16).padStart(hexLen, '0').lowercase().let {
            if (hexLen <= 2) it.takeLast(2) else it
        }

    private fun utf16Hex(hex: String): String {
        if (hex.length < 4) return codeToString(hex.toInt(16))
        val out = StringBuilder()
        var i = 0
        while (i + 3 < hex.length + 1 && i + 1 < hex.length) {
            val unit = hex.substring(i, (i + 4).coerceAtMost(hex.length)).toInt(16)
            if (unit in 0xD800..0xDBFF && i + 7 <= hex.length) {
                val low = hex.substring(i + 4, i + 8).toInt(16)
                out.append(Char(0)) // surrogate pair -> append via code points below
                out.deleteCharAt(out.length - 1)
                out.append(String(Character.toChars(0x10000 + ((unit - 0xD800) shl 10) + (low - 0xDC00))))
                i += 8
            } else {
                out.append(unit.toChar())
                i += 4
            }
        }
        return out.toString()
    }

    private fun codeToString(code: Int): String =
        if (code in 0..0xFFFF) code.toChar().toString() else String(Character.toChars(code))

    // --------------------------------------------------------- text operators

    private class CodeMap(val twoByte: Boolean, val map: HashMap<String, String>)

    private fun readTextOps(stream: ByteArray, fonts: Map<String, CodeMap>): String {
        val text = String(stream, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var font: CodeMap? = null
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' -> {
                    // Keep the complete PDF font-selection syntax together:
                    // `/F1 12 Tf`. The font size is mandatory between name and
                    // Tf, so a font-looking name elsewhere is not a selection.
                    val tail = text.substring(i, (i + 64).coerceAtMost(n))
                    val selection = FONT_SELECTION.find(tail)
                    if (selection != null && selection.range.first == 0) {
                        font = fonts[selection.groupValues[1]]
                        i += selection.range.last + 1
                    } else {
                        i++
                    }
                }
                c == '(' -> {
                    val close = matchParen(text, i)
                    val decoded = decodeLiteral(text.substring(i + 1, close))
                    out.append(decodeString(decoded, font))
                    i = close + 1
                }
                c == '<' && i + 1 < n && text[i + 1] != '<' -> {
                    val close = text.indexOf('>', i + 1)
                    if (close < 0) {
                        i = n
                    } else {
                        val hex = text.substring(i + 1, close).filter { it.isLetterOrDigit() }
                        out.append(decodeString(hexToBytes(hex), font))
                        i = close + 1
                    }
                }
                c == 'T' && i + 1 < n && (text[i + 1] == '*' || text[i + 1] == 'd' || text[i + 1] == 'D') -> {
                    if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                    i += 2
                }
                c == 'E' && i + 1 < n && text[i + 1] == 'T' -> {
                    if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                    i += 2
                }
                else -> i++
            }
        }
        return out.toString()
    }

    private fun matchParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return text.length - 1
    }

    private fun decodeLiteral(raw: String): ByteArray {
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                when {
                    next.isDigit() -> {
                        var value = 0
                        var j = i + 1
                        while (j < raw.length && j < i + 4 && raw[j].isDigit()) {
                            value = value * 8 + (raw[j] - '0')
                            j++
                        }
                        out.write(value and 0xFF)
                        i = j
                        continue
                    }
                    next == 'n' -> out.write('\n'.code)
                    next == 'r' -> out.write('\r'.code)
                    next == 't' -> out.write('\t'.code)
                    next == 'b' -> out.write('\b'.code)
                    next == 'f' -> out.write('\u000C'.code)
                    else -> out.write(next.code)
                }
                i += 2
            } else {
                out.write(c.code)
                i++
            }
        }
        return out.toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val padded = if (hex.length % 2 == 1) hex + "0" else hex
        val out = ByteArray(padded.length / 2)
        for (i in out.indices) {
            out[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun decodeString(bytes: ByteArray, font: CodeMap?): String {
        if (bytes.isEmpty()) return ""
        val map = font?.map ?: HashMap()
        val twoByte = font?.twoByte == true || map.keys.any { it.length > 2 }
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val key: String
            val width: Int
            if (twoByte && i + 1 < bytes.size) {
                key = String.format(
                    "%02x%02x",
                    bytes[i].toInt() and 0xFF,
                    bytes[i + 1].toInt() and 0xFF
                )
                width = 2
            } else {
                key = String.format("%02x", bytes[i].toInt() and 0xFF)
                width = 1
            }
            val mapped = map[key] ?: map[key.padStart(4, '0')]
            if (mapped != null) {
                out.append(mapped)
            } else {
                val code = if (width == 2) {
                    ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                } else {
                    bytes[i].toInt() and 0xFF
                }
                if (code in 32..126 || code in 160..255) out.append(code.toChar())
                else if (code == 0) out.append(' ')
            }
            i += width
        }
        return out.toString()
    }

    // --------------------------------------------------------------- objects

    private fun resolve(ref: String, objects: Map<Int, ByteArray>): ByteArray? {
        val number = ref.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: return null
        return objects[number]
    }

    private fun hasType(body: ByteArray, type: String): Boolean =
        hasType(String(body, Charsets.ISO_8859_1), type)

    private fun hasType(body: String, type: String): Boolean {
        val tail = body.substringAfter("/Type", "")
        if (tail.isEmpty()) return false
        val token = tail.trimStart()
        if (!token.startsWith("/$type")) return false
        // "/Page" must not match "/Pages": the type name ends at a non-name char.
        val after = token.getOrNull(type.length + 1) ?: return true
        return !after.isLetterOrDigit()
    }

    private fun refValue(body: ByteArray, key: String): String? {
        val text = String(body, Charsets.ISO_8859_1)
        val tail = text.substringAfter(key, "")
        return Regex("^\\s*(\\d+\\s+\\d+\\s+R)").find(tail)?.groupValues?.get(1)
    }
}

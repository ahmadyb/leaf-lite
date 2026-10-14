package com.leaflite.viewer.parsers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import android.view.Gravity
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Word (.docx) reader built on java.util.zip + [XmlPullParser].
 *
 * Handles headings (w:pStyle), bold/italic/underline/strike, font size and
 * colour, alignment, bullets and numbering, hyperlinks, tables and inline
 * images. Headers, footers, footnotes, comments and equations are skipped
 * without crashing; tracked insertions are shown, deletions dropped.
 */
class DocxParser(private val open: () -> InputStream?) {

    private companion object {
        const val DOCUMENT = "word/document.xml"
        const val RELS = "word/_rels/document.xml.rels"
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_EDGE = 1600
        const val MAX_BLOCKS = 4000
    }

    sealed class Block {
        data class Para(
            val text: CharSequence,
            val gravity: Int,
            val indent: Int,
            val marker: String?,
            val style: String?
        ) : Block()

        data class Table(val rows: List<List<CharSequence>>, val headerRow: Boolean) : Block()
        data class Image(val bitmap: Bitmap) : Block()
        data class ImageRef(val path: String) : Block()

        /** Explicit `w:br w:type="page"` — forces a page cut when paginating. */
        object PageBreak : Block()
    }

    data class Document(val blocks: List<Block>)

    // ------------------------------------------------------------------ entry

    fun parse(): Document? {
        val imageTargets = HashMap<String, String>()
        val linkTargets = HashMap<String, String>()
        Zipx.withEntry(open, RELS) { parseRelationships(it, imageTargets, linkTargets) }

        val blocks = ArrayList<Block>()
        val walker = Walker(linkTargets, imageTargets, blocks)
        val ok = Zipx.withEntry(open, DOCUMENT) { walker.walk(it) } ?: false
        if (!ok) return null
        return Document(resolveImages(blocks))
    }

    // ------------------------------------------------------------ relationships

    private fun parseRelationships(
        stream: InputStream,
        images: MutableMap<String, String>,
        links: MutableMap<String, String>
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.local() == "Relationship") {
                val id = parser.attr("Id") ?: continue
                val type = parser.attr("Type") ?: ""
                val target = parser.attr("Target") ?: continue
                when {
                    type.endsWith("/image") -> images[id] = resolvePart(target)
                    type.endsWith("/hyperlink") -> links[id] = target
                }
            }
            event = parser.next()
        }
    }

    /** Relationship targets are relative to the part folder (word/). */
    private fun resolvePart(target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        if (target.startsWith("./")) return "word/${target.removePrefix("./")}"
        return "word/$target"
    }

    // ------------------------------------------------------------------ images

    private fun resolveImages(blocks: List<Block>): List<Block> {
        val paths = blocks.filterIsInstance<Block.ImageRef>().map { it.path }.toSet()
        if (paths.isEmpty()) return blocks
        val bitmaps = HashMap<String, Bitmap?>()
        Zipx.forEachEntry(open) { entry, stream ->
            if (entry.name in paths) {
                bitmaps[entry.name] = decodeImage(stream)
                true
            } else {
                false
            }
        }
        return blocks.mapNotNull { block ->
            when (block) {
                is Block.ImageRef -> bitmaps[block.path]?.let { Block.Image(it) }
                else -> block
            }
        }
    }

    private fun decodeImage(stream: InputStream): Bitmap? {
        val bytes = try {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (total < MAX_IMAGE_BYTES) {
                val read = stream.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            buffer.toByteArray()
        } catch (t: Throwable) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeSample(bounds.outWidth, bounds.outHeight)
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            null
        }
    }

    private fun computeSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_IMAGE_EDGE || height / sample > MAX_IMAGE_EDGE) sample *= 2
        return sample
    }

    // ------------------------------------------------------------------ walker

    private inner class Walker(
        private val links: Map<String, String>,
        private val imageTargets: Map<String, String>,
        private val out: MutableList<Block>
    ) {
        // table state
        private var tableDepth = 0
        private var rows: MutableList<List<CharSequence>>? = null
        private var row: MutableList<CharSequence>? = null
        private var cell = SpannableStringBuilder()
        private var cellHasText = false
        private var firstRowHeader = false

        // paragraph state
        private var para: SpannableStringBuilder? = null
        private var paraGravity = Gravity.START
        private var paraIndent = 0
        private var paraStyle: String? = null
        private var paraLevel = 0
        private var paraNumId: String? = null

        // run state
        private var run: StringBuilder? = null
        private var bold = false
        private var italic = false
        private var underline = false
        private var strike = false
        private var sizeSp = 0
        private var color: Int? = null
        private var hyperlink: String? = null
        private var pendingImage: String? = null
        private var inText = false

        // numbering: alternate bullet / decimal per numId, sequential per list
        private val orderedLists = HashMap<String, Boolean>()
        private val counters = HashMap<String, Int>()

        fun walk(stream: InputStream): Boolean {
            val parser = Xml.newPullParser()
            parser.setInput(stream, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> start(parser)
                    XmlPullParser.TEXT -> {
                        if (inText) run?.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> end(parser)
                }
                event = try {
                    parser.next()
                } catch (t: Throwable) {
                    break
                }
            }
            return true
        }

        private fun start(parser: XmlPullParser) {
            val name = parser.name.local()
            when (name) {
                "p" -> {
                    if (tableDepth == 0) {
                        para = SpannableStringBuilder()
                        paraGravity = Gravity.START
                        paraIndent = 0
                        paraStyle = null
                        paraLevel = 0
                        paraNumId = null
                    } else {
                        para = null
                    }
                }
                "pStyle" -> paraStyle = parser.attr("val")
                "jc" -> paraGravity = when (parser.attr("val")) {
                    "center" -> Gravity.CENTER_HORIZONTAL
                    "right", "end" -> Gravity.END
                    else -> Gravity.START
                }
                "numId" -> paraNumId = parser.attr("val")
                "ilvl" -> paraLevel = parser.attr("val")?.toIntOrNull() ?: 0
                "ind" -> {
                    val left = parser.attr("left")?.toIntOrNull() ?: parser.attr("firstLine")?.toIntOrNull()
                    if (left != null && left > 360) paraIndent = 1
                }
                "r" -> {
                    run = StringBuilder()
                    bold = false
                    italic = false
                    underline = false
                    strike = false
                    sizeSp = 0
                    color = null
                    pendingImage = null
                }
                "b", "bCs" -> bold = !off(parser.attr("val"))
                "i", "iCs" -> italic = !off(parser.attr("val"))
                "u" -> underline = (parser.attr("val") ?: "single") != "none"
                "strike", "dstrike" -> strike = !off(parser.attr("val"))
                "sz", "szCs" -> sizeSp = (parser.attr("val")?.toIntOrNull() ?: 0) / 2
                "color" -> color = parseColor(parser.attr("val"))
                "t" -> inText = true
                "br" -> {
                    val type = parser.attr("type")
                    if (type == "page" && tableDepth == 0 && out.size < MAX_BLOCKS) {
                        out.add(Block.PageBreak)
                    } else {
                        run?.append('\n')
                    }
                }
                "tab" -> run?.append('\t')
                "hyperlink" -> hyperlink = parser.attr("id")?.let { links[it] }
                "tbl" -> {
                    tableDepth++
                    if (tableDepth == 1) {
                        rows = ArrayList()
                        firstRowHeader = false
                    }
                }
                "tr" -> if (tableDepth == 1) row = ArrayList()
                "tc" -> if (tableDepth == 1) {
                    cell = SpannableStringBuilder()
                    cellHasText = false
                }
                "tblHeader" -> if (tableDepth == 1 && row?.isEmpty() == true) firstRowHeader = true
                "blip" -> pendingImage = parser.attr("embed")?.let { imageTargets[it] }
                "imagedata" -> pendingImage = parser.attr("id")?.let { imageTargets[it] }
            }
        }

        private fun end(parser: XmlPullParser) {
            when (parser.name.local()) {
                "p" -> finishParagraph()
                "r" -> flushRun()
                "t" -> inText = false
                "hyperlink" -> hyperlink = null
                "tc" -> if (tableDepth == 1) row?.add(cell)
                "tr" -> if (tableDepth == 1) {
                    val current = row
                    if (current != null) rows?.add(current)
                    row = null
                }
                "tbl" -> {
                    tableDepth--
                    if (tableDepth == 0) {
                        val table = rows
                        if (table != null && table.isNotEmpty() && out.size < MAX_BLOCKS) {
                            out.add(Block.Table(table, firstRowHeader))
                        }
                        rows = null
                    }
                }
            }
        }

        private fun flushRun() {
            val text = run ?: return
            run = null
            val image = pendingImage
            pendingImage = null

            val target = para ?: cell
            val start = target.length
            target.append(text)
            if (text.isEmpty()) {
                if (image != null) addImage(image)
                return
            }
            val end = target.length
            if (bold) target.setSpan(StyleSpan(Typeface.BOLD), start, end, SPAN)
            if (italic) target.setSpan(StyleSpan(Typeface.ITALIC), start, end, SPAN)
            if (underline) target.setSpan(UnderlineSpan(), start, end, SPAN)
            if (strike) target.setSpan(StrikethroughSpan(), start, end, SPAN)
            if (sizeSp in 6..72) target.setSpan(AbsoluteSizeSpan(sizeSp, true), start, end, SPAN)
            color?.let { target.setSpan(ForegroundColorSpan(it), start, end, SPAN) }
            hyperlink?.let { target.setSpan(URLSpan(it), start, end, SPAN) }
            if (image != null) addImage(image)
        }

        private fun addImage(path: String) {
            if (out.size < MAX_BLOCKS) out.add(Block.ImageRef(path))
        }

        private fun finishParagraph() {
            val text = para ?: return
            para = null
            if (tableDepth > 0) {
                if (cellHasText) cell.append('\n')
                cell.append(text)
                cellHasText = true
                return
            }
            if (text.isBlank() && paraStyle == null) return
            if (out.size >= MAX_BLOCKS) return
            val marker = buildMarker()
            out.add(
                Block.Para(
                    text = text,
                    gravity = paraGravity,
                    indent = paraIndent,
                    marker = marker,
                    style = paraStyle
                )
            )
        }

        /**
         * Numbering without numbering.xml: the first list id seen becomes a
         * bullet list, the next a numbered one, and so on (documented
         * simplification — see DECISIONS.md).
         */
        private fun buildMarker(): String? {
            val numId = paraNumId
            if (numId.isNullOrEmpty() || numId == "0") {
                return if (paraIndent > 0) "•" else null
            }
            val ordered = orderedLists.getOrPut(numId) { orderedLists.size % 2 == 1 }
            val marker = if (ordered) {
                val next = (counters[numId] ?: 0) + 1
                counters[numId] = next
                "$next."
            } else {
                "•"
            }
            return marker
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun off(value: String?): Boolean =
        value == "0" || value == "false" || value.equals("off", ignoreCase = true)

    private fun parseColor(value: String?): Int? {
        if (value.isNullOrEmpty() || value.equals("auto", ignoreCase = true)) return null
        return try {
            android.graphics.Color.parseColor("#$value")
        } catch (t: Throwable) {
            null
        }
    }
}

/** Local part of an XML name, with or without namespace processing. */
internal fun String.local(): String = substringAfter(':', this)

/** Attribute lookup by local name (namespace-processing independent). */
internal fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).local() == name) return getAttributeValue(i)
    }
    return null
}

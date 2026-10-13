package com.leaflite.viewer.parsers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * PowerPoint (.pptx) reader built on java.util.zip + [XmlPullParser].
 *
 * Extracts per-slide shape geometry (`a:off`/`a:ext` in EMUs), text runs with
 * bold/italic/size/colour, and embedded pictures. Tables are flattened to
 * text. Group shapes are flattened without group transforms (documented
 * simplification). Slide order follows `slideN.xml` numbering.
 */
class PptxParser(private val open: () -> InputStream?) {

    data class Run(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val sizePt: Float,
        val color: Int?
    )

    data class Para(
        val runs: List<Run>,
        val level: Int,
        val bullet: Boolean,
        val align: String?
    )

    sealed class Shape {
        abstract val x: Float
        abstract val y: Float
        abstract val w: Float
        abstract val h: Float

        data class TextBox(
            override val x: Float,
            override val y: Float,
            override val w: Float,
            override val h: Float,
            val paragraphs: List<Para>
        ) : Shape()

        data class Picture(
            override val x: Float,
            override val y: Float,
            override val w: Float,
            override val h: Float,
            val bitmap: Bitmap?
        ) : Shape()
    }

    data class Slide(
        val widthEmu: Float,
        val heightEmu: Float,
        val shapes: List<Shape>
    )

    fun parse(): List<Slide> {
        val slideNames = Zipx.entryNames(open, limit = 4096)
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { name ->
                name.substringAfterLast("slide").substringBefore(".xml").toIntOrNull() ?: 0
            }
        if (slideNames.isEmpty()) return emptyList()

        val (width, height) = readSlideSize()
        val slides = ArrayList<Slide>(slideNames.size)
        for (name in slideNames) {
            if (slides.size >= MAX_SLIDES) break
            val shapes = ArrayList<Shape>()
            val imageTargets = HashMap<String, String>()
            val relsName = "ppt/slides/_rels/${name.substringAfterLast('/')}.rels"
            Zipx.withEntry(open, relsName) { parseRelationships(it, imageTargets) }
            val ok = Zipx.withEntry(open, name) { parseSlide(it, imageTargets, shapes) } ?: false
            if (ok) slides += Slide(width, height, shapes)
        }
        return slides
    }

    private fun readSlideSize(): Pair<Float, Float> {
        var size = DEFAULT_WIDTH to DEFAULT_HEIGHT
        Zipx.withEntry(open, "ppt/presentation.xml") { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.local() == "sldSz") {
                    val cx = parser.attr("cx")?.toFloatOrNull()
                    val cy = parser.attr("cy")?.toFloatOrNull()
                    if (cx != null && cy != null && cx > 0f && cy > 0f) {
                        size = cx to cy
                    }
                    break
                }
                event = parser.next()
            }
            true
        }
        return size
    }

    private fun parseRelationships(stream: InputStream, images: MutableMap<String, String>) {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.local() == "Relationship") {
                val id = parser.attr("Id") ?: continue
                val type = parser.attr("Type") ?: ""
                val target = parser.attr("Target") ?: continue
                if (type.endsWith("/image")) {
                    images[id] = if (target.startsWith("/")) target.removePrefix("/")
                    else "ppt/slides/$target".replace("/slides/../", "/")
                }
            }
            event = parser.next()
        }
    }

    private fun parseSlide(
        stream: InputStream,
        imageTargets: Map<String, String>,
        out: MutableList<Shape>
    ): Boolean {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        val walker = SlideWalker(imageTargets, out)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> walker.start(parser)
                XmlPullParser.TEXT -> walker.text(parser.text)
                XmlPullParser.END_TAG -> walker.end(parser)
            }
            event = try {
                parser.next()
            } catch (t: Throwable) {
                break
            }
        }
        walker.flushShape()
        return true
    }

    // ------------------------------------------------------------- walking

    private inner class SlideWalker(
        private val imageTargets: Map<String, String>,
        private val out: MutableList<Shape>
    ) {
        // current shape geometry
        private var inShape = false
        private var inXfrm = false
        private var x = 0f
        private var y = 0f
        private var w = 0f
        private var h = 0f
        private var shapeIsPic = false
        private var pictureRef: String? = null

        // text accumulation
        private var paragraphs = ArrayList<Para>()
        private var runs = ArrayList<Run>()
        private var run = StringBuilder()
        private var bold = false
        private var italic = false
        private var sizePt = 0f
        private var color: Int? = null
        private var level = 0
        private var align: String? = null
        private var bullet = false
        private var inText = false
        private var tableRows = ArrayList<ArrayList<String>>()
        private var tableRow: ArrayList<String>? = null
        private var tableCell = StringBuilder()
        private var tableDepth = 0

        fun start(parser: XmlPullParser) {
            when (parser.name.local()) {
                "sp", "pic", "graphicFrame" -> {
                    flushShape()
                    inShape = true
                    shapeIsPic = parser.name.local() == "pic"
                    x = 0f; y = 0f; w = 0f; h = 0f
                    pictureRef = null
                    paragraphs = ArrayList()
                    tableRows = ArrayList()
                    tableDepth = 0
                }
                "xfrm" -> if (inShape) inXfrm = true
                "off" -> if (inXfrm) {
                    x = parser.attr("x")?.toFloatOrNull() ?: 0f
                    y = parser.attr("y")?.toFloatOrNull() ?: 0f
                }
                "ext" -> if (inXfrm) {
                    w = parser.attr("cx")?.toFloatOrNull() ?: 0f
                    h = parser.attr("cy")?.toFloatOrNull() ?: 0f
                    inXfrm = false
                }
                "blip" -> pictureRef = parser.attr("embed")?.let { imageTargets[it] }
                "p" -> {
                    if (tableDepth == 0) {
                        runs = ArrayList()
                        level = 0
                        align = null
                        bullet = false
                    } else {
                        tableCell = StringBuilder()
                    }
                }
                "pPr" -> {
                    align = parser.attr("algn")
                    bullet = true // text frames in slides are bullet lists by default
                }
                "lvl" -> level = (parser.attr("val")?.toIntOrNull() ?: 0).coerceIn(0, 4)
                "buNone" -> bullet = false
                "r" -> {
                    run = StringBuilder()
                    bold = false
                    italic = false
                    sizePt = 0f
                    color = null
                }
                "rPr" -> {
                    val sz = parser.attr("sz")?.toIntOrNull()
                    if (sz != null && sz > 0) sizePt = sz / 100f
                    if (parser.attr("b") == "1") bold = true
                    if (parser.attr("i") == "1") italic = true
                }
                "srgbClr" -> {
                    val value = parser.attr("val")
                    if (value != null && run.isEmpty() && !inText) {
                        color = parseColor(value)
                    }
                }
                "t" -> inText = true
                "tbl" -> tableDepth++
                "tr" -> if (tableDepth > 0) tableRow = ArrayList()
                "tc" -> if (tableDepth > 0) tableCell = StringBuilder()
            }
        }

        fun text(chunk: String?) {
            if (inText && chunk != null) run.append(chunk)
        }

        fun end(parser: XmlPullParser) {
            when (parser.name.local()) {
                "t" -> inText = false
                "r" -> {
                    val text = run.toString()
                    run = StringBuilder()
                    if (text.isNotEmpty()) {
                        runs += Run(text, bold, italic, sizePt, color)
                    }
                }
                "p" -> {
                    if (tableDepth > 0) {
                        tableRow?.add(tableCell.toString())
                    } else if (runs.isNotEmpty() || paragraphs.isEmpty()) {
                        paragraphs += Para(runs, level, bullet, align)
                        runs = ArrayList()
                    }
                }
                "tc" -> if (tableDepth > 0) tableRow?.add(tableCell.toString())
                "tr" -> if (tableDepth > 0) {
                    tableRow?.let { tableRows.add(it) }
                    tableRow = null
                }
                "tbl" -> {
                    tableDepth--
                    if (tableDepth == 0 && tableRows.isNotEmpty()) {
                        val paras = tableRows.map { row ->
                            Para(listOf(Run(row.joinToString("   "), false, false, 12f, null)), 0, false, null)
                        }
                        paragraphs += paras
                        tableRows = ArrayList()
                    }
                }
                "sp", "pic", "graphicFrame" -> flushShape()
            }
        }

        fun flushShape() {
            if (!inShape) return
            inShape = false
            if (out.size >= MAX_SHAPES_PER_SLIDE) return
            val shape: Shape = if (shapeIsPic) {
                val path = pictureRef
                val bitmap = if (path != null) decodeImage(path) else null
                if (bitmap == null && paragraphs.isEmpty()) return
                if (bitmap != null) {
                    Shape.Picture(x, y, w, h, bitmap)
                } else {
                    Shape.TextBox(x, y, w, h, paragraphs)
                }
            } else {
                if (paragraphs.isEmpty()) return
                Shape.TextBox(x, y, w, h, paragraphs)
            }
            out += shape
        }

        private fun decodeImage(path: String): Bitmap? {
            var bitmap: Bitmap? = null
            Zipx.withEntry(open, path) { stream ->
                bitmap = decodeBitmap(stream)
                true
            }
            return bitmap
        }
    }

    // -------------------------------------------------------------- helpers

    private fun decodeBitmap(stream: InputStream): Bitmap? {
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
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseColor(value: String?): Int? {
        if (value.isNullOrEmpty() || value.equals("auto", ignoreCase = true)) return null
        return try {
            android.graphics.Color.parseColor("#$value")
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        const val MAX_SLIDES = 256
        const val MAX_SHAPES_PER_SLIDE = 256
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_EDGE = 1600
        const val DEFAULT_WIDTH = 12192000f // 16:9 in EMUs
        const val DEFAULT_HEIGHT = 6858000f
    }
}

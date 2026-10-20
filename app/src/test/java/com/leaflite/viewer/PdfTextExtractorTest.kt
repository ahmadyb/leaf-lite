package com.leaflite.viewer

import com.leaflite.viewer.parsers.PdfTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/**
 * Pure-JVM checks for the from-scratch PDF content-stream text extractor that
 * powers find-in-document. The fixture is the same structure
 * `tools/make_samples.py` writes: uncompressed content streams with Tj.
 */
class PdfTextExtractorTest {

    @Test
    fun `extracts text from a simple uncompressed page`() {
        val bytes = simplePdf("Hello Leaf Lite find me")
        val pages = PdfTextExtractor.extract { ByteArrayInputStream(bytes) }
        assertEquals(1, pages.size)
        assertTrue(pages[0].contains("Hello Leaf Lite find me"))
    }

    @Test
    fun `maps pages in order across the page tree`() {
        val bytes = multiPagePdf(listOf("first page alpha", "second page beta", "third page gamma"))
        val pages = PdfTextExtractor.extract { ByteArrayInputStream(bytes) }
        assertEquals(3, pages.size)
        assertTrue(pages[0].contains("first page alpha"))
        assertTrue(pages[1].contains("second page beta"))
        assertTrue(pages[2].contains("third page gamma"))
    }

    @Test
    fun `extracts searchable text from standard zlib FlateDecode streams`() {
        val bytes = multiPagePdf(
            listOf("Revenue grew by 24 percent in the fourth quarter"),
            compressStreams = true
        )
        val pages = PdfTextExtractor.extract { ByteArrayInputStream(bytes) }
        assertEquals(1, pages.size)
        assertTrue(pages.single().contains("Revenue grew by 24 percent"))
    }

    @Test
    fun `uses compressed ToUnicode maps with Type0 fonts`() {
        val pages = PdfTextExtractor.extract { ByteArrayInputStream(toUnicodePdf()) }
        assertEquals(1, pages.size)
        assertTrue(pages.single().contains("PDFX"))
    }

    @Test
    fun `reads page tree objects packed in a Flate object stream`() {
        val pages = PdfTextExtractor.extract { ByteArrayInputStream(objectStreamPdf()) }
        assertEquals(1, pages.size)
        assertTrue(pages.single().contains("Packed page search phrase"))
    }

    @Test
    fun `garbage input yields no pages instead of throwing`() {
        val garbage = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44)
        val pages = PdfTextExtractor.extract { ByteArrayInputStream(garbage) }
        assertTrue(pages.isEmpty())
    }

    // ------------------------------------------------------------------ fixture

    private fun simplePdf(text: String): ByteArray = multiPagePdf(listOf(text))

    private fun multiPagePdf(
        pageTexts: List<String>,
        compressStreams: Boolean = false
    ): ByteArray {
        val objects = ArrayList<ByteArray>()
        val fontObj = 3 + pageTexts.size * 2
        // 1: catalog
        objects.add(b("<< /Type /Catalog /Pages 2 0 R >>"))
        // 2: pages tree
        val kids = pageTexts.indices.joinToString(" ") { "${3 + it * 2} 0 R" }
        objects.add(b("<< /Type /Pages /Kids [$kids] /Count ${pageTexts.size} >>"))
        pageTexts.forEachIndexed { index, text ->
            val pageObj = 3 + index * 2
            val contentObj = pageObj + 1
            // page object
            objects.add(
                b(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                        "/Contents $contentObj 0 R /Resources << /Font << /F1 $fontObj 0 R >> >> >>"
                )
            )
            val rawStream = b("BT /F1 12 Tf 72 700 Td ($text) Tj ET")
            val stream = if (compressStreams) {
                ByteArrayOutputStream().also { buffer ->
                    DeflaterOutputStream(buffer).use { it.write(rawStream) }
                }.toByteArray()
            } else {
                rawStream
            }
            val filter = if (compressStreams) " /Filter /FlateDecode" else ""
            objects.add(
                b("<< /Length ${stream.size}$filter >>") + "stream\n".toByteArray() +
                    stream + "\nendstream".toByteArray()
            )
        }
        // font object last
        objects.add(b("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"))

        return buildPdf(objects)
    }

    private fun toUnicodePdf(): ByteArray {
        val content = zlib("BT /F1 12 Tf 72 700 Td <0001000200030004> Tj ET")
        val cmap = zlib(
            """begincmap
                1 begincodespacerange
                <0000> <FFFF>
                endcodespacerange
                4 beginbfchar
                <0001> <0050>
                <0002> <0044>
                <0003> <0046>
                <0004> <0058>
                endbfchar
                endcmap"""
        )
        return buildPdf(
            listOf(
                b("<< /Type /Catalog /Pages 2 0 R >>"),
                b("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                b(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                        "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
                ),
                streamObject(content),
                b("<< /Type /Font /Subtype /Type0 /ToUnicode 6 0 R >>"),
                streamObject(cmap)
            )
        )
    }

    private fun objectStreamPdf(): ByteArray {
        val packed = listOf(
            1 to b("<< /Type /Catalog /Pages 2 0 R >>"),
            2 to b("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            3 to b(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] " +
                    "/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
            )
        )
        val header = StringBuilder()
        var offset = 0
        packed.forEach { (number, body) ->
            header.append(number).append(' ').append(offset).append(' ')
            offset += body.size + 1
        }
        val headerBytes = header.toString().toByteArray(Charsets.ISO_8859_1)
        val decodedObjects = ByteArrayOutputStream().apply {
            write(headerBytes)
            packed.forEach { (_, body) ->
                write(body)
                write('\n'.code)
            }
        }.toByteArray()
        val packedStream = zlib(decodedObjects)
        val objectStream = b(
            "<< /Type /ObjStm /N 3 /First ${headerBytes.size} " +
                "/Length ${packedStream.size} /Filter /FlateDecode >>"
        ) + "stream\n".toByteArray() + packedStream + "\nendstream".toByteArray()

        return buildPdfWithIds(
            mapOf(
                4 to streamObject(zlib("BT /F1 12 Tf (Packed page search phrase) Tj ET")),
                5 to b("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
                6 to objectStream
            )
        )
    }

    private fun zlib(text: String): ByteArray = zlib(text.toByteArray(Charsets.ISO_8859_1))

    private fun zlib(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer ->
        DeflaterOutputStream(buffer).use { it.write(bytes) }
    }.toByteArray()

    private fun streamObject(stream: ByteArray): ByteArray =
        b("<< /Length ${stream.size} /Filter /FlateDecode >>") +
            "stream\n".toByteArray() + stream + "\nendstream".toByteArray()

    private fun buildPdf(objects: List<ByteArray>): ByteArray =
        buildPdfWithIds(objects.mapIndexed { index, bytes -> index + 1 to bytes }.toMap())

    private fun buildPdfWithIds(objects: Map<Int, ByteArray>): ByteArray {
        val maxObject = objects.keys.maxOrNull() ?: 0
        val out = ArrayList<Byte>()
        fun addAll(bytes: ByteArray) = out.addAll(bytes.toList())
        addAll("%PDF-1.5\n".toByteArray())
        val offsets = IntArray(maxObject + 1)
        objects.toSortedMap().forEach { (number, body) ->
            offsets[number] = out.size
            addAll("$number 0 obj\n".toByteArray())
            addAll(body)
            addAll("\nendobj\n".toByteArray())
        }
        val xref = out.size
        addAll("xref\n0 ${maxObject + 1}\n".toByteArray())
        addAll("0000000000 65535 f \n".toByteArray())
        for (number in 1..maxObject) {
            if (offsets[number] == 0) {
                addAll("0000000000 00000 f \n".toByteArray())
            } else {
                addAll("%010d 00000 n \n".format(offsets[number]).toByteArray())
            }
        }
        addAll(
            ("trailer\n<< /Size ${maxObject + 1} /Root 1 0 R >>\n" +
                "startxref\n$xref\n%%EOF\n").toByteArray()
        )
        return out.toByteArray()
    }

    private fun b(text: String): ByteArray = text.toByteArray()
}

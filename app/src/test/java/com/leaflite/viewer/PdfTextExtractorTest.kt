package com.leaflite.viewer

import com.leaflite.viewer.parsers.PdfTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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
    fun `garbage input yields no pages instead of throwing`() {
        val garbage = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44)
        val pages = PdfTextExtractor.extract { ByteArrayInputStream(garbage) }
        assertTrue(pages.isEmpty())
    }

    // ------------------------------------------------------------------ fixture

    private fun simplePdf(text: String): ByteArray = multiPagePdf(listOf(text))

    private fun multiPagePdf(pageTexts: List<String>): ByteArray {
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
            val stream = b(
                "BT /F1 12 Tf 72 700 Td ($text) Tj ET"
            )
            objects.add(b("<< /Length ${stream.size} >>") + "stream\n".toByteArray() +
                stream + "\nendstream".toByteArray())
        }
        // font object last
        objects.add(b("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"))

        val out = ArrayList<Byte>()
        fun addAll(bytes: ByteArray) = out.addAll(bytes.toList())
        addAll("%PDF-1.4\n".toByteArray())
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { index, body ->
            offsets[index + 1] = out.size
            addAll("${index + 1} 0 obj\n".toByteArray())
            addAll(body)
            addAll("\nendobj\n".toByteArray())
        }
        val xref = out.size
        addAll("xref\n0 ${objects.size + 1}\n".toByteArray())
        addAll("0000000000 65535 f \n".toByteArray())
        for (i in 1..objects.size) {
            addAll("%010d 00000 n \n".format(offsets[i]).toByteArray())
        }
        addAll(
            ("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n" +
                "startxref\n$xref\n%%EOF\n").toByteArray()
        )
        return out.toByteArray()
    }

    private fun b(text: String): ByteArray = text.toByteArray()
}

package com.leaflite.viewer

import com.leaflite.viewer.data.DocType
import com.leaflite.viewer.parsers.FormatDetector
import com.leaflite.viewer.parsers.Zipx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure-JVM checks for the format sniffing used by the document pipeline. */
class FormatDetectorTest {

    @Test
    fun `extension maps to the right type`() {
        assertEquals(DocType.PDF, FormatDetector.fromName("Q3 report.PDF"))
        assertEquals(DocType.DOCX, FormatDetector.fromName("brief.docx"))
        assertEquals(DocType.XLSX, FormatDetector.fromName("budget.xlsx"))
        assertEquals(DocType.TXT, FormatDetector.fromName("notes.text"))
        assertEquals(DocType.MD, FormatDetector.fromName("README.markdown"))
        assertNull(FormatDetector.fromName("archive.zip"))
        assertNull(FormatDetector.fromName(null))
    }

    @Test
    fun `mime type is used when the name has no extension`() {
        assertEquals(
            DocType.PDF,
            FormatDetector.detect("download", "application/pdf") { null }
        )
        assertEquals(
            DocType.MD,
            FormatDetector.detect(null, "text/x-markdown") { null }
        )
    }

    @Test
    fun `pdf magic bytes are detected`() {
        val bytes = "%PDF-1.7\n%âãÏÓ".toByteArray()
        assertEquals(DocType.PDF, FormatDetector.sniff { ByteArrayInputStream(bytes) })
    }

    @Test
    fun `zip packages are probed for their ooxml part`() {
        val docx = zipOf("word/document.xml" to "<w:document/>")
        assertEquals(DocType.DOCX, FormatDetector.sniff { ByteArrayInputStream(docx) })

        val xlsx = zipOf("xl/workbook.xml" to "<workbook/>")
        assertEquals(DocType.XLSX, FormatDetector.sniff { ByteArrayInputStream(xlsx) })
    }

    @Test
    fun `plain bytes are treated as text`() {
        val text = "Just a plain text file.\nSecond line.".toByteArray()
        assertEquals(DocType.TXT, FormatDetector.sniff { ByteArrayInputStream(text) })
    }

    @Test
    fun `binary garbage is unsupported`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x7F, 0x10)
        assertNull(FormatDetector.sniff { ByteArrayInputStream(bytes) })
    }

    @Test
    fun `zip walker finds entries by name`() {
        val bytes = zipOf("word/document.xml" to "hello", "word/styles.xml" to "styles")
        val names = Zipx.entryNames({ ByteArrayInputStream(bytes) })
        assertEquals(setOf("word/document.xml", "word/styles.xml"), names)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}

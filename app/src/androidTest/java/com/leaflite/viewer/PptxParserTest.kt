package com.leaflite.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leaflite.viewer.parsers.PptxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Parser-level PPTX coverage. A real package also carries
 * ppt/slides/slideLayouts/… and ppt/slides/slideMasters/… whose entry names
 * start with "ppt/slides/slide" — those must never become slides. The deck is
 * assembled in memory so no test asset ships in the APK.
 */
@RunWith(AndroidJUnit4::class)
class PptxParserTest {

    @Test
    fun parseIgnoresLayoutsAndMastersAndReadsRealSlides() {
        val deck = deckBytes()
        val slides = PptxParser { ByteArrayInputStream(deck) }.parse()

        assertEquals("layouts/masters must not become slides", 2, slides.size)
        assertEquals(12192000f, slides[0].widthEmu, 0.5f)
        assertEquals(6858000f, slides[0].heightEmu, 0.5f)

        val allText = slides.flatMap { slide ->
            slide.shapes.flatMap { shape ->
                (shape as? PptxParser.Shape.TextBox)?.paragraphs.orEmpty()
                    .flatMap { para -> para.runs.map { it.text } }
            }
        }
        assertEquals(listOf("First", "Bulleted", "Second"), allText)
        assertFalse(allText.any { it.contains("LAYOUT") })
        assertFalse(allText.any { it.contains("MASTER") })

        val first = slides[0].shapes
            .filterIsInstance<PptxParser.Shape.TextBox>()
            .flatMap { it.paragraphs }
        assertTrue(first.any { it.bullet && it.runs.any { run -> run.text == "Bulleted" } })
        assertTrue(first.any { para -> !para.bullet && para.runs.any { it.text == "First" } })
    }

    // ------------------------------------------------------------- fixtures

    private fun deckBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            // Entries deliberately in non-slide order to exercise the sort.
            put("ppt/slides/slideLayouts/slideLayout1.xml", slideLike("LAYOUT", bulletFirst = false))
            put("ppt/slides/slideMasters/slideMaster1.xml", slideLike("MASTER", bulletFirst = false))
            put("ppt/slides/slide2.xml", slideLike("Second", bulletFirst = false))
            put("ppt/slides/slide1.xml", slideLike("First", bulletFirst = true))
            put(
                "ppt/presentation.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                   <p:presentation xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P">
                     <p:sldMasterIdLst/>
                     <p:sldIdLst/>
                     <p:sldSz cx="12192000" cy="6858000"/>
                   </p:presentation>"""
            )
        }
        return out.toByteArray()
    }

    private fun slideLike(title: String, bulletFirst: Boolean): String {
        val bulletPara = if (bulletFirst) {
            """
            <a:p><a:pPr><a:buChar char="&#8226;"/></a:pPr>
              <a:r><a:rPr lang="en-US"/><a:t>Bulleted</a:t></a:r>
            </a:p>
            """
        } else {
            ""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
          <p:sld xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P">
            <p:cSld>
              <p:spTree>
                <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                <p:grpSpPr/>
                <p:sp>
                  <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                  <p:spPr>
                    <a:xfrm><a:off x="838200" y="228600"/><a:ext cx="8382000" cy="1143000"/></a:xfrm>
                    <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                  </p:spPr>
                  <p:txBody>
                    <a:bodyPr/>
                    <a:lstStyle/>
                    <a:p>
                      <a:r><a:rPr lang="en-US" sz="3600" b="1"/><a:t>$title</a:t></a:r>
                    </a:p>
                    $bulletPara
                  </p:txBody>
                </p:sp>
              </p:spTree>
            </p:cSld>
          </p:sld>"""
    }

    private companion object {
        const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    }
}

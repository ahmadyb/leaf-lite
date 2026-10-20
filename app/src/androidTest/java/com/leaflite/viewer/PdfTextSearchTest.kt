package com.leaflite.viewer

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaflite.viewer.parsers.PdfTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Verifies the same file-URI path used by filesystem-discovered documents. */
@RunWith(AndroidJUnit4::class)
class PdfTextSearchTest {

    @Test
    fun findInViewerReportsTheSamplePdfMatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val file = File(target.cacheDir, "leaf-lite-q3-viewer-search.pdf")
        var viewer: ViewerActivity? = null
        try {
            instrumentation.context.assets.open("Q3-Report.pdf").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val intent = Intent(target, ViewerActivity::class.java).apply {
                data = Uri.fromFile(file)
                putExtra(ViewerActivity.EXTRA_NAME, file.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            viewer = instrumentation.startActivitySync(intent) as ViewerActivity
            val activity = viewer!!

            val readyDeadline = System.currentTimeMillis() + 5_000
            var ready = false
            while (System.currentTimeMillis() < readyDeadline && !ready) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    ready = activity.findViewById<View>(R.id.findButton).visibility == View.VISIBLE
                }
                if (!ready) Thread.sleep(80)
            }
            assertTrue("PDF viewer should finish loading before search", ready)

            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.findButton).performClick()
                activity.findViewById<EditText>(R.id.findInput).setText("Revenue grew 2.4%")
            }
            val expected = activity.getString(R.string.find_count, 1, 1)
            val searchDeadline = System.currentTimeMillis() + 8_000
            var actual = ""
            while (System.currentTimeMillis() < searchDeadline && actual != expected) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    actual = activity.findViewById<TextView>(R.id.findCount).text.toString()
                }
                if (actual != expected) Thread.sleep(80)
            }
            assertEquals("PDF find should report one match", expected, actual)
        } finally {
            viewer?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            file.delete()
        }
    }

    @Test
    fun extractsSearchableTextFromTheRepositoryPdf() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val file = File(target.cacheDir, "leaf-lite-q3-report.pdf")
        try {
            instrumentation.context.assets.open("Q3-Report.pdf").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val pages = PdfTextExtractor.extract {
                target.contentResolver.openInputStream(Uri.fromFile(file))
            }

            assertEquals("the sample report has twelve searchable pages", 12, pages.size)
            assertTrue(pages[0].contains("Leaf Lite sample PDF"))
            assertTrue(pages[0].contains("Revenue grew 2.4%"))
            assertTrue(pages.any { it.contains("page 7 of 12") })
        } finally {
            file.delete()
        }
    }
}

package com.leaflite.viewer.parsers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlin.math.roundToInt

/**
 * Thin wrapper around the platform [PdfRenderer] (built into Android since
 * API 21 — no library, no native code).
 *
 * The renderer is not thread-safe, so every call is synchronised; callers still
 * run it on a background dispatcher. The underlying [ParcelFileDescriptor] is
 * obtained straight from the ContentResolver, so no cache copy is needed.
 */
class PdfEngine private constructor(private val descriptor: ParcelFileDescriptor) {

    private val renderer = PdfRenderer(descriptor)
    private val pageSizes = HashMap<Int, Pair<Int, Int>>()

    val pageCount: Int
        @Synchronized get() = renderer.pageCount

    /** Page size in PostScript points (1/72"). */
    @Synchronized
    fun pageSize(index: Int): Pair<Int, Int> {
        pageSizes[index]?.let { return it }
        val page = renderer.openPage(index)
        return try {
            val size = page.width to page.height
            if (pageSizes.size < 512) pageSizes[index] = size
            size
        } finally {
            page.close()
        }
    }

    /** Height/width ratio of a page, used to size the placeholder. */
    fun pageRatio(index: Int): Float {
        val (width, height) = pageSize(index)
        return if (width == 0) 1.414f else height.toFloat() / width.toFloat()
    }

    /**
     * Renders page [index] into a bitmap roughly [widthPx] wide. The height
     * follows the page aspect ratio and the longest edge is capped so a single
     * page can never blow the memory budget.
     */
    @Synchronized
    fun renderPage(index: Int, widthPx: Int, reuse: Bitmap? = null): Bitmap {
        val page = renderer.openPage(index)
        return try {
            val ratio = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
            var width = widthPx.coerceIn(64, MAX_EDGE)
            var height = (width * ratio).roundToInt().coerceIn(64, MAX_EDGE)
            width = (height / ratio).roundToInt().coerceIn(64, MAX_EDGE)

            val bitmap = when {
                reuse != null && reuse.width == width && reuse.height == height -> {
                    reuse.eraseColor(0)
                    reuse
                }
                else -> {
                    reuse?.recycle()
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
            }
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        private const val MAX_EDGE = 4096

        /** Opens a PDF from any content:// or file:// URI, or `null` on failure. */
        fun open(context: Context, uri: Uri): PdfEngine? = try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            PdfEngine(pfd)
        } catch (security: SecurityException) {
            null // password protected / not readable
        } catch (io: java.io.IOException) {
            null
        } catch (other: RuntimeException) {
            null
        }
    }
}

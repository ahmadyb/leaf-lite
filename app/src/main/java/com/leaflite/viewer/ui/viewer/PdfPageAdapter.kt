package com.leaflite.viewer.ui.viewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.leaflite.viewer.databinding.ItemPdfPageBinding
import com.leaflite.viewer.parsers.PdfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/**
 * Paged PDF list: one page per row, rendered off the main thread into a small
 * LRU of bitmaps (at most [MAX_CACHED] page bitmaps alive, as required).
 *
 * The renderer is single-threaded, so rendering is funnelled through a
 * `limitedParallelism(1)` dispatcher and every job is cancelled with the scope.
 */
class PdfPageAdapter(
    private val engine: PdfEngine,
    private val scope: CoroutineScope,
    private val widthProvider: () -> Int
) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

    private val cache = LinkedHashMap<Int, android.graphics.Bitmap>(MAX_CACHED)
    private val jobs = HashMap<Int, Job>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    private var pageCount = 0

    fun submit(count: Int) {
        pageCount = count
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH =
        PageVH(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageVH, position: Int) = holder.bind(position)

    override fun onViewRecycled(holder: PageVH) = holder.unbind()

    /** Cancels every render job and frees the bitmaps (activity destroyed). */
    fun destroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        cache.values.forEach { runCatching { it.recycle() } }
        cache.clear()
    }

    private fun cachedRender(position: Int, width: Int) {
        if (position !in 0 until pageCount) return
        if (cache.containsKey(position) || jobs.containsKey(position)) return
        jobs[position] = scope.launch {
            val bitmap = render(position, width) ?: return@launch
            jobs.remove(position)
            if (cache.size >= MAX_CACHED) {
                val eldest = cache.entries.firstOrNull() ?: return@launch
                cache.remove(eldest.key)
            }
            cache[position] = bitmap
        }
    }

    private suspend fun render(position: Int, width: Int): android.graphics.Bitmap? = try {
        withContext(renderDispatcher) { engine.renderPage(position, width) }
    } catch (t: Throwable) {
        null
    }

    inner class PageVH(private val binding: ItemPdfPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null
        private var position: Int = RecyclerView.NO_POSITION

        fun bind(page: Int) {
            position = page
            val view = binding.pageImage
            val width = widthProvider().takeIf { it > 0 }
                ?: (view.resources.displayMetrics.widthPixels - 32)
            val renderWidth = width.coerceAtMost(MAX_WIDTH_PX)
            val height = (renderWidth * engine.pageRatio(page)).toInt()

            view.updateLayoutParams<ViewGroup.LayoutParams> { this.height = height }
            view.reset()
            view.contentDescription =
                view.context.getString(com.leaflite.viewer.R.string.content_desc_document_page, page + 1)

            val bitmap = cache[page]
            if (bitmap != null && !bitmap.isRecycled) {
                view.setImageBitmap(bitmap)
                preload(page + 1, renderWidth)
                return
            }
            view.setImageBitmap(null)

            job?.cancel()
            job = scope.launch {
                val rendered = render(page, renderWidth) ?: return@launch
                if (this@PageVH.position != page) {
                    if (cache.size < MAX_CACHED) cache[page] = rendered else rendered.recycle()
                    return@launch
                }
                if (cache.size >= MAX_CACHED) {
                    val eldest = cache.entries.firstOrNull()
                    if (eldest != null) cache.remove(eldest.key)
                }
                cache[page] = rendered
                view.setImageBitmap(rendered)
                preload(page + 1, renderWidth)
            }
        }

        private fun preload(page: Int, width: Int) {
            if (page in 0 until pageCount && !cache.containsKey(page)) {
                cachedRender(page, width)
            }
        }

        fun unbind() {
            job?.cancel()
            job = null
            position = RecyclerView.NO_POSITION
            binding.pageImage.reset()
            binding.pageImage.setImageBitmap(null)
        }
    }

    private companion object {
        const val MAX_CACHED = 3
        const val MAX_WIDTH_PX = 1440
    }
}

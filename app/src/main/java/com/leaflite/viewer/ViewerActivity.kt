package com.leaflite.viewer

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.leaflite.viewer.databinding.ActivityViewerBinding
import com.leaflite.viewer.parsers.PptxParser
import com.leaflite.viewer.parsers.XlsxParser
import com.leaflite.viewer.ui.viewer.PdfPageAdapter
import com.leaflite.viewer.ui.viewer.SheetAdapter
import com.leaflite.viewer.ui.viewer.SlideView
import com.leaflite.viewer.ui.viewer.TextFinder
import com.leaflite.viewer.ui.viewer.ViewerRenderer
import com.leaflite.viewer.ui.viewer.ViewerViewModel
import com.leaflite.viewer.util.matchStatusBarHeight
import kotlinx.coroutines.launch

/**
 * Full-screen document viewer. Slides in from the right, renders PDF, DOCX,
 * XLSX, PPTX, TXT or Markdown, and restores page / scroll / sheet across
 * rotation.
 *
 * v1.1: find-in-document for every format, share sheet, measured DOCX
 * pagination with page indicator, pinch-to-zoom on text documents, and
 * zoom-quality re-rendering for PDF pages.
 *
 * Also the app's ACTION_VIEW entry point ("Open with -> Leaf Lite").
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private val viewModel: ViewerViewModel by lazy {
        ViewModelProvider(this)[ViewerViewModel::class.java]
    }

    private var pdfAdapter: PdfPageAdapter? = null
    private var sheetIndex = 0
    private var pendingScrollY = 0
    private var pendingPage = 0
    private var textScale = 1f

    // find state
    private var textMatches: List<TextFinder.Match> = emptyList()
    private var findIndex = 0
    private var pdfHits: List<Pair<Int, Int>> = emptyList() // page -> occurrence
    private var slideHits: List<Pair<Int, Int>> = emptyList() // slide -> occurrence

    // page accounting for the footer
    private var pageTops = IntArray(0)
    private var pageTotal = 1

    private val prefs get() = (application as LeafLiteApp).prefs
    private val sepia: Boolean get() = prefs.nightMode
    private val compact: Boolean get() = prefs.compactList

    private fun darkPages(): Boolean {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return night && !sepia
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding.topInset.matchStatusBarHeight()
        applyStatusBarIcons()
        overridePendingTransition(R.anim.slide_in_right, R.anim.stay)

        pendingScrollY = savedInstanceState?.getInt(KEY_SCROLL, 0) ?: 0
        pendingPage = savedInstanceState?.getInt(KEY_PAGE, 0) ?: 0
        sheetIndex = savedInstanceState?.getInt(KEY_SHEET, 0) ?: 0
        textScale = savedInstanceState?.getFloat(KEY_SCALE, 1f) ?: 1f

        val uri = intent?.data
            ?: IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java)

        if (uri == null) {
            finish()
            return
        }

        setupChrome()
        observe()
        viewModel.load(uri, intent?.getStringExtra(EXTRA_NAME))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, binding.scrollBody.scrollY)
        outState.putInt(KEY_PAGE, currentPage())
        outState.putInt(KEY_SHEET, sheetIndex)
        outState.putFloat(KEY_SCALE, textScale)
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarIcons()
        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        pdfAdapter?.destroy()
        pdfAdapter = null
        binding.pdfRecycler.adapter = null
        binding.sheetRecycler.adapter = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun setupChrome() {
        binding.backButton.setOnClickListener { closeViewer() }
        binding.starButton.setOnClickListener {
            viewModel.toggleStar()
            Toast.makeText(
                this,
                if (viewModel.starred.value) R.string.toast_starred else R.string.toast_unstarred,
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.shareButton.setOnClickListener { shareDocument() }
        binding.monospaceButton.setOnClickListener {
            viewModel.toggleMonospace()
            reRenderText()
        }
        setupFindBar()
        setupPinchZoom()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.findBar.isVisible) {
                    closeFind()
                } else {
                    closeViewer()
                }
            }
        })

        binding.sheetGridScroll.viewTreeObserver.addOnScrollChangedListener {
            if (binding.sheetHeaderScroll.scrollX != binding.sheetGridScroll.scrollX) {
                binding.sheetHeaderScroll.scrollTo(binding.sheetGridScroll.scrollX, 0)
            }
        }

        binding.scrollBody.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateScrollFooter(scrollY)
        }
    }

    private fun setupFindBar() {
        binding.findButton.setOnClickListener {
            if (binding.findBar.isVisible) {
                closeFind()
            } else {
                binding.findBar.isVisible = true
                binding.findInput.requestFocus()
            }
        }
        binding.findClose.setOnClickListener { closeFind() }
        binding.findNext.setOnClickListener { stepFind(+1) }
        binding.findPrev.setOnClickListener { stepFind(-1) }
        binding.findInput.setOnEditorActionListener { _, _, _ ->
            stepFind(+1)
            true
        }
        binding.findInput.doOnTextChanged { _, _, _, _ -> runFind() }
    }

    private fun closeFind() {
        binding.findBar.isVisible = false
        binding.findInput.text?.clear()
        TextFinder.clear(binding.contentHolder)
        (binding.sheetRecycler.adapter as? SheetAdapter)?.highlightQuery = ""
        textMatches = emptyList()
        pdfHits = emptyList()
        slideHits = emptyList()
        findIndex = 0
    }

    /** Re-runs the search for the current query against the visible format. */
    private fun runFind() {
        val query = binding.findInput.text?.toString().orEmpty()
        TextFinder.clear(binding.contentHolder)
        (binding.sheetRecycler.adapter as? SheetAdapter)?.highlightQuery = query
        textMatches = emptyList()
        pdfHits = emptyList()
        slideHits = emptyList()
        findIndex = 0

        val content = (viewModel.state.value as? ViewerViewModel.State.Ready)?.content
        when (content) {
            is ViewerViewModel.Content.Text, is ViewerViewModel.Content.Word -> {
                textMatches = TextFinder.highlight(
                    binding.contentHolder,
                    query,
                    ContextCompat.getColor(this, R.color.accent_soft),
                    ContextCompat.getColor(this, R.color.accent)
                )
                if (textMatches.isNotEmpty()) TextFinder.scrollTo(binding.scrollBody, textMatches[0])
            }
            is ViewerViewModel.Content.Slides -> {
                slideHits = findInSlides(content.slides, query)
                if (slideHits.isNotEmpty()) scrollToSlide(slideHits[0].first)
            }
            is ViewerViewModel.Content.Sheets -> {
                lifecycleScope.launch {
                    val sheet = viewModel.sheet(sheetIndex) ?: return@launch
                    val rows = sheet.rows.indices.filter { row ->
                        sheet.rows[row].cells.any { it.text.contains(query, ignoreCase = true) }
                    }
                    if (rows.isNotEmpty()) {
                        binding.sheetRecycler.scrollToPosition(rows.first())
                    }
                }
            }
            is ViewerViewModel.Content.Pdf -> {
                lifecycleScope.launch {
                    val pages = viewModel.pdfPageText()
                    pdfHits = pages.flatMapIndexed { page, text ->
                        val hay = text.lowercase()
                        val needle = query.lowercase()
                        val hits = ArrayList<Pair<Int, Int>>()
                        if (needle.isNotEmpty()) {
                            var index = hay.indexOf(needle)
                            while (index >= 0) {
                                hits += page to hits.size
                                index = hay.indexOf(needle, index + needle.length)
                            }
                        }
                        hits
                    }
                    if (pdfHits.isNotEmpty()) scrollToPdfPage(pdfHits[0].first)
                    updateFindCount()
                }
                return
            }
            null -> Unit
        }
        updateFindCount()
    }

    private fun stepFind(direction: Int) {
        val total = findTotal()
        if (total == 0) return
        findIndex = ((findIndex + direction) % total + total) % total
        val content = (viewModel.state.value as? ViewerViewModel.State.Ready)?.content
        when {
            textMatches.isNotEmpty() -> TextFinder.scrollTo(binding.scrollBody, textMatches[findIndex])
            slideHits.isNotEmpty() -> scrollToSlide(slideHits[findIndex].first)
            pdfHits.isNotEmpty() -> scrollToPdfPage(pdfHits[findIndex].first)
        }
        updateFindCount()
    }

    private fun findTotal(): Int = when {
        textMatches.isNotEmpty() -> textMatches.size
        slideHits.isNotEmpty() -> slideHits.size
        pdfHits.isNotEmpty() -> pdfHits.size
        else -> 0
    }

    private fun updateFindCount() {
        val query = binding.findInput.text?.toString().orEmpty()
        binding.findCount.text = when {
            query.isEmpty() -> ""
            findTotal() == 0 -> getString(R.string.find_no_matches)
            else -> getString(R.string.find_count, findIndex + 1, findTotal())
        }
    }

    private fun findInSlides(slides: List<PptxParser.Slide>, query: String): List<Pair<Int, Int>> {
        if (query.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Int, Int>>()
        slides.forEachIndexed { index, slide ->
            var count = 0
            slide.shapes.forEach { shape ->
                if (shape is PptxParser.Shape.TextBox) {
                    shape.paragraphs.forEach { para ->
                        para.runs.forEach { run ->
                            var at = run.text.lowercase().indexOf(query.lowercase())
                            while (at >= 0) {
                                count++
                                at = run.text.lowercase().indexOf(query.lowercase(), at + query.length)
                            }
                        }
                    }
                }
            }
            repeat(count) { hits += index to it }
        }
        return hits
    }

    private fun scrollToSlide(index: Int) {
        binding.scrollBody.post {
            val child = binding.contentHolder.getChildAt(index) ?: return@post
            binding.scrollBody.smoothScrollTo(0, (child.top - dp(12f)).coerceAtLeast(0))
        }
    }

    private fun scrollToPdfPage(page: Int) {
        binding.pdfRecycler.post {
            (binding.pdfRecycler.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(page, 0)
        }
    }

    // -------------------------------------------------------------- pinch zoom

    private val pinchDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var base = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                base = textScale
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                textScale = (base * detector.scaleFactor).coerceIn(0.7f, 2.5f)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                reRenderText(restoreScroll = true)
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPinchZoom() {
        binding.scrollBody.setOnTouchListener { _, event ->
            pinchDetector.onTouchEvent(event)
            false
        }
    }

    // -------------------------------------------------------------- observe

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.title.collect { binding.title.text = it }
                }
                launch {
                    viewModel.subtitle.collect { binding.subtitle.text = it }
                }
                launch {
                    viewModel.starred.collect { starred ->
                        binding.starIcon.setImageResource(
                            if (starred) R.drawable.ic_star_filled else R.drawable.ic_star
                        )
                        binding.starIcon.setColorFilter(
                            ContextCompat.getColor(
                                this@ViewerActivity,
                                if (starred) R.color.star else R.color.icon_idle
                            )
                        )
                        binding.starButton.contentDescription =
                            getString(if (starred) R.string.cd_unstar else R.string.cd_star)
                    }
                }
                launch {
                    viewModel.monospace.collect { monospace ->
                        binding.monospaceButton.alpha = if (monospace) 1f else 0.65f
                    }
                }
                launch {
                    viewModel.state.collect { state -> render(state) }
                }
            }
        }
    }

    // ----------------------------------------------------------------- render

    private fun render(state: ViewerViewModel.State) {
        binding.progress.isVisible = state is ViewerViewModel.State.Loading
        when (state) {
            is ViewerViewModel.State.Loading -> hideAllBodies()
            is ViewerViewModel.State.Ready -> showContent(state.content)
            is ViewerViewModel.State.Error -> showError(state.reason)
        }
    }

    private fun hideAllBodies() {
        binding.scrollBody.isVisible = false
        binding.pdfRecycler.isVisible = false
        binding.sheetContainer.isVisible = false
        binding.emptyState.isVisible = false
        binding.footerBar.isVisible = false
        binding.monospaceButton.isVisible = false
        binding.findButton.isVisible = false
        binding.shareButton.isVisible = false
        binding.findBar.isVisible = false
    }

    private fun showContent(content: ViewerViewModel.Content) {
        hideAllBodies()
        binding.findButton.isVisible = true
        binding.shareButton.isVisible = true
        when (content) {
            is ViewerViewModel.Content.Pdf -> showPdf(content.pageCount)
            is ViewerViewModel.Content.Sheets -> showSheets(content.names)
            is ViewerViewModel.Content.Text -> {
                showScroll(card = false)
                ViewerRenderer.renderText(
                    container = binding.contentHolder,
                    blocks = content.blocks,
                    markdown = content.markdown,
                    sepia = sepia,
                    monospace = viewModel.monospace.value,
                    textScale = textScale
                )
                if (!content.markdown) {
                    binding.footerBar.isVisible = true
                    binding.footer.text = ""
                    binding.monospaceButton.isVisible = true
                }
                restoreScroll()
            }
            is ViewerViewModel.Content.Word -> {
                showScroll(card = true)
                ViewerRenderer.renderDocx(
                    container = binding.contentHolder,
                    blocks = content.blocks,
                    sepia = sepia,
                    darkPages = darkPages(),
                    textScale = textScale
                ) { pages ->
                    pageTotal = pages.coerceAtLeast(1)
                    binding.footerBar.isVisible = true
                    binding.monospaceButton.isVisible = true
                    binding.footer.text = getString(R.string.docx_footer, 1, pageTotal)
                    binding.contentHolder.post { collectPageTops() }
                }
                restoreScroll()
            }
            is ViewerViewModel.Content.Slides -> {
                showScroll(card = false)
                renderSlides(content.slides)
                restoreScroll()
            }
        }
    }

    private fun renderSlides(slides: List<PptxParser.Slide>) {
        binding.contentHolder.removeAllViews()
        val dark = darkPages()
        slides.forEachIndexed { index, slide ->
            val view = SlideView(this).apply {
                this.slide = slide
                darkPages = dark
                contentDescription = getString(R.string.content_desc_slide, index + 1)
            }
            binding.contentHolder.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12f) }
            )
        }
        pageTotal = slides.size.coerceAtLeast(1)
        binding.footerBar.isVisible = true
        binding.footer.text = getString(R.string.slide_footer, 1, pageTotal)
        binding.contentHolder.post { collectPageTops() }
    }

    private fun showScroll(card: Boolean) {
        binding.pdfRecycler.isVisible = false
        binding.sheetContainer.isVisible = false
        binding.emptyState.isVisible = false
        binding.scrollBody.isVisible = true

        if (card) {
            // DOCX: page sheets carry their own paper backgrounds.
            binding.scrollBody.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            binding.contentHolder.background = null
            binding.contentHolder.backgroundTintList = null
            binding.contentHolder.setPadding(
                dp(ViewerRenderer.PAGE_GUTTER_DP), dp(10f),
                dp(ViewerRenderer.PAGE_GUTTER_DP), dp(24f)
            )
        } else {
            binding.scrollBody.setBackgroundColor(
                if (sepia) pageColor() else ContextCompat.getColor(this, R.color.bg)
            )
            binding.contentHolder.background = null
            binding.contentHolder.backgroundTintList = null
            binding.contentHolder.setPadding(dp(12f), dp(10f), dp(12f), dp(40f))
        }
    }

    private fun showPdf(pageCount: Int) {
        val engine = viewModel.engine ?: run {
            showError(ViewerViewModel.Reason.CORRUPT)
            return
        }
        binding.scrollBody.isVisible = false
        binding.pdfRecycler.isVisible = true
        binding.footerBar.isVisible = true
        binding.monospaceButton.isVisible = false

        val adapter = PdfPageAdapter(engine, lifecycleScope) { pageWidth() }
        pdfAdapter = adapter
        binding.pdfRecycler.adapter = adapter
        binding.pdfRecycler.clearOnScrollListeners()
        binding.pdfRecycler.addOnScrollListener(pageScrollListener)
        adapter.submit(pageCount)
        updatePageFooter(1, pageCount)

        if (pendingPage > 0) {
            binding.pdfRecycler.post {
                (binding.pdfRecycler.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pendingPage, 0)
            }
            pendingPage = 0
        }
    }

    private fun showSheets(names: List<String>) {
        binding.scrollBody.isVisible = false
        binding.pdfRecycler.isVisible = false
        binding.sheetContainer.isVisible = true
        binding.footerBar.isVisible = true
        binding.monospaceButton.isVisible = false

        if (names.size > 1) {
            binding.sheetChips.isVisible = true
            binding.sheetChips.setItems(names, sheetIndex) { index -> switchSheet(index) }
        } else {
            binding.sheetChips.isVisible = false
        }
        loadSheet(sheetIndex)
    }

    private fun switchSheet(index: Int) {
        sheetIndex = index
        loadSheet(index)
    }

    private fun loadSheet(index: Int) {
        lifecycleScope.launch {
            binding.progress.isVisible = true
            val sheet = viewModel.sheet(index)
            binding.progress.isVisible = false
            if (sheet == null) {
                showError(ViewerViewModel.Reason.CORRUPT)
                return@launch
            }
            renderSheet(index, sheet)
        }
    }

    private fun renderSheet(index: Int, sheet: XlsxParser.Sheet) {
        val names = (viewModel.state.value as? ViewerViewModel.State.Ready)
            ?.let { (it.content as? ViewerViewModel.Content.Sheets)?.names }
            ?: emptyList()
        ViewerRenderer.buildSheetHeader(binding.sheetHeader, sheet, compact)

        val columns = (sheet.rows.firstOrNull()?.cells?.size ?: sheet.columnCount).coerceAtLeast(1)
        val width = columns * resources.getDimensionPixelSize(R.dimen.cell_width)
        binding.sheetRecycler.layoutParams.width = width
        binding.sheetHeader.layoutParams.width = width
        binding.sheetRecycler.adapter = SheetAdapter(sheet, compact).apply {
            highlightQuery = binding.findInput.text?.toString().orEmpty()
        }

        binding.sheetGridScroll.isVisible = sheet.rows.isNotEmpty()
        binding.footer.text = if (sheet.truncated) {
            getString(
                R.string.sheet_footer_truncated,
                index + 1,
                names.size.coerceAtLeast(1),
                sheet.rows.size,
                sheet.rows.size
            )
        } else {
            getString(R.string.sheet_footer, index + 1, names.size.coerceAtLeast(1), sheet.rows.size)
        }
        if (sheet.rows.isEmpty()) {
            binding.emptyState.isVisible = true
            binding.empty.root.isVisible = true
            binding.empty.emptyText.setText(R.string.xlsx_empty)
            binding.empty.emptyAction.isVisible = false
        }
    }

    private fun showError(reason: ViewerViewModel.Reason) {
        hideAllBodies()
        binding.emptyState.isVisible = true
        binding.empty.root.isVisible = true
        binding.empty.emptyText.setText(
            when (reason) {
                ViewerViewModel.Reason.NOT_FOUND -> R.string.viewer_file_missing
                ViewerViewModel.Reason.TOO_LARGE -> R.string.viewer_too_large
                else -> R.string.viewer_unsupported
            }
        )
        binding.empty.emptyAction.isVisible = reason != ViewerViewModel.Reason.NOT_FOUND
        binding.empty.emptyAction.setOnClickListener { openElsewhere() }
    }

    /** Re-renders the current text document (monospace / pinch zoom). */
    private fun reRenderText(restoreScroll: Boolean = false) {
        val content = (viewModel.state.value as? ViewerViewModel.State.Ready)?.content
        val scrollY = binding.scrollBody.scrollY
        when (content) {
            is ViewerViewModel.Content.Text -> {
                ViewerRenderer.renderText(
                    container = binding.contentHolder,
                    blocks = content.blocks,
                    markdown = content.markdown,
                    sepia = sepia,
                    monospace = viewModel.monospace.value,
                    textScale = textScale
                )
                if (!restoreScroll) {
                    Toast.makeText(
                        this,
                        if (viewModel.monospace.value) R.string.toast_monospace_on
                        else R.string.toast_monospace_off,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            is ViewerViewModel.Content.Word -> {
                ViewerRenderer.renderDocx(
                    container = binding.contentHolder,
                    blocks = content.blocks,
                    sepia = sepia,
                    darkPages = darkPages(),
                    textScale = textScale
                ) { pages ->
                    pageTotal = pages.coerceAtLeast(1)
                    binding.contentHolder.post { collectPageTops() }
                }
            }
            else -> return
        }
        if (restoreScroll) {
            binding.scrollBody.post { binding.scrollBody.scrollTo(0, scrollY) }
        }
    }

    // -------------------------------------------------------------- utilities

    private fun shareDocument() {
        lifecycleScope.launch {
            val export = viewModel.export()
            if (export.uri == null) {
                Toast.makeText(this@ViewerActivity, R.string.toast_share_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = export.mime ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, export.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, null))
            } catch (notFound: ActivityNotFoundException) {
                Toast.makeText(this@ViewerActivity, R.string.toast_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openElsewhere() {
        lifecycleScope.launch {
            val export = viewModel.export()
            if (export.uri == null) {
                Toast.makeText(this@ViewerActivity, R.string.no_viewer_app, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(export.uri, export.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(intent)
            } catch (notFound: ActivityNotFoundException) {
                Toast.makeText(this@ViewerActivity, R.string.no_viewer_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeViewer() {
        finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_out_right)
    }

    private fun restoreScroll() {
        if (pendingScrollY > 0) {
            binding.scrollBody.post { binding.scrollBody.scrollTo(0, pendingScrollY) }
            pendingScrollY = 0
        }
    }

    /** Page sheet / slide tops inside the scroll container (post-layout). */
    private fun collectPageTops() {
        val holder = binding.contentHolder
        val tops = IntArray(holder.childCount)
        for (i in 0 until holder.childCount) {
            tops[i] = holder.getChildAt(i).top
        }
        pageTops = tops
    }

    private fun updateScrollFooter(scrollY: Int) {
        if (pageTops.isEmpty()) return
        var page = 0
        for (i in pageTops.indices) {
            if (scrollY + dp(48f) >= pageTops[i]) page = i
        }
        val content = (viewModel.state.value as? ViewerViewModel.State.Ready)?.content
        binding.footer.text = when (content) {
            is ViewerViewModel.Content.Word ->
                getString(R.string.docx_footer, page + 1, pageTotal)
            is ViewerViewModel.Content.Slides ->
                getString(R.string.slide_footer, page + 1, pageTotal)
            else -> binding.footer.text
        }
    }

    private val pageScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val position = manager.findFirstVisibleItemPosition()
            if (position >= 0) {
                updatePageFooter(position + 1, recyclerView.adapter?.itemCount ?: 0)
            }
        }
    }

    private fun updatePageFooter(page: Int, count: Int) {
        binding.footer.text = getString(R.string.pdf_footer, page, count.coerceAtLeast(1))
    }

    private fun currentPage(): Int {
        val manager = binding.pdfRecycler.layoutManager as? LinearLayoutManager ?: return 0
        return manager.findFirstVisibleItemPosition()
    }

    private fun pageWidth(): Int {
        val measured = binding.pdfRecycler.measuredWidth
        val base = if (measured > 0) measured else resources.displayMetrics.widthPixels
        return (base - dp(28f)).coerceAtLeast(64)
    }

    private fun pageColor(): Int = ContextCompat.getColor(
        this,
        if (sepia) R.color.sepia_page else R.color.card
    )

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun applyStatusBarIcons() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !night
    }

    companion object {
        const val EXTRA_NAME = "com.leaflite.viewer.extra.NAME"
        const val EXTRA_URI = "com.leaflite.viewer.extra.URI"

        private const val KEY_SCROLL = "viewer.scroll"
        private const val KEY_PAGE = "viewer.page"
        private const val KEY_SHEET = "viewer.sheet"
        private const val KEY_SCALE = "viewer.scale"
    }
}

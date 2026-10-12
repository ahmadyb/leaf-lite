package com.leaflite.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.leaflite.viewer.databinding.ActivityViewerBinding
import com.leaflite.viewer.parsers.XlsxParser
import com.leaflite.viewer.ui.viewer.PdfPageAdapter
import com.leaflite.viewer.ui.viewer.SheetAdapter
import com.leaflite.viewer.ui.viewer.ViewerRenderer
import com.leaflite.viewer.ui.viewer.ViewerViewModel
import com.leaflite.viewer.util.matchStatusBarHeight
import kotlinx.coroutines.launch

/**
 * Full-screen document viewer. Slides in from the right, renders PDF, DOCX,
 * XLSX, TXT or Markdown, and restores page / scroll / sheet across rotation.
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

    private val prefs get() = (application as LeafLiteApp).prefs
    private val sepia: Boolean get() = prefs.nightMode
    private val compact: Boolean get() = prefs.compactList

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
        binding.monospaceButton.setOnClickListener {
            viewModel.toggleMonospace()
            reRenderText()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeViewer()
        })

        binding.sheetGridScroll.viewTreeObserver.addOnScrollChangedListener {
            if (binding.sheetHeaderScroll.scrollX != binding.sheetGridScroll.scrollX) {
                binding.sheetHeaderScroll.scrollTo(binding.sheetGridScroll.scrollX, 0)
            }
        }
    }

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
    }

    private fun showContent(content: ViewerViewModel.Content) {
        hideAllBodies()
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
                    monospace = viewModel.monospace.value
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
                ViewerRenderer.renderDocx(binding.contentHolder, content.blocks, sepia)
                restoreScroll()
            }
        }
    }

    private fun showScroll(card: Boolean) {
        binding.pdfRecycler.isVisible = false
        binding.sheetContainer.isVisible = false
        binding.emptyState.isVisible = false
        binding.scrollBody.isVisible = true

        if (card) {
            binding.scrollBody.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            binding.contentHolder.setBackgroundResource(R.drawable.bg_doc_page)
            binding.contentHolder.backgroundTintList = ColorStateList.valueOf(pageColor())
            binding.contentHolder.setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            binding.scrollBody.setPadding(dp(14f), dp(14f), dp(14f), dp(24f))
        } else {
            binding.scrollBody.setBackgroundColor(
                if (sepia) pageColor() else ContextCompat.getColor(this, R.color.bg)
            )
            binding.contentHolder.background = null
            binding.contentHolder.backgroundTintList = null
            binding.contentHolder.setPadding(dp(26f), dp(8f), dp(26f), dp(40f))
            binding.scrollBody.setPadding(0, 0, 0, 0)
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
        val names = viewModel.sheetNames()
        ViewerRenderer.buildSheetHeader(binding.sheetHeader, sheet, compact)

        val columns = (sheet.rows.firstOrNull()?.cells?.size ?: sheet.columnCount).coerceAtLeast(1)
        val width = columns * resources.getDimensionPixelSize(R.dimen.cell_width)
        binding.sheetRecycler.layoutParams.width = width
        binding.sheetHeader.layoutParams.width = width
        binding.sheetRecycler.adapter = SheetAdapter(sheet, compact)

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

    /** Re-renders the current text document (monospace toggle). */
    private fun reRenderText() {
        val content = (viewModel.state.value as? ViewerViewModel.State.Ready)?.content
        if (content is ViewerViewModel.Content.Text) {
            ViewerRenderer.renderText(
                container = binding.contentHolder,
                blocks = content.blocks,
                markdown = content.markdown,
                sepia = sepia,
                monospace = viewModel.monospace.value
            )
            Toast.makeText(
                this,
                if (viewModel.monospace.value) R.string.toast_monospace_on
                else R.string.toast_monospace_off,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // -------------------------------------------------------------- utilities

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
    }
}

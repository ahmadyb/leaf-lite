package com.leaflite.viewer.ui.viewer

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.leaflite.viewer.R
import com.leaflite.viewer.parsers.DocxParser
import com.leaflite.viewer.parsers.MarkdownParser
import com.leaflite.viewer.parsers.XlsxParser
import kotlin.math.abs

/**
 * Turns parsed documents into native views.
 *
 * Everything is plain TextViews / LinearLayouts: cheap to build, cheap to
 * recycle with the screen, and no bitmaps except extracted DOCX images.
 *
 * v1.1: DOCX is paginated into paper-like sheets (measured block heights plus
 * explicit `w:br type="page"` breaks), document run colours that would be
 * unreadable on dark pages are dropped, and every text block honours the
 * pinch-to-zoom [textScale].
 */
object ViewerRenderer {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    /** Side chrome of a DOCX page sheet (must match [showScroll] in the activity). */
    const val PAGE_GUTTER_DP = 14f
    const val PAGE_PAD_DP = 22f

    /** Content width of a page sheet, in px. */
    fun pageContentWidth(context: Context): Int =
        (context.resources.displayMetrics.widthPixels - dp(context, (PAGE_GUTTER_DP + PAGE_PAD_DP) * 2f))
            .coerceAtLeast(120)

    // ----------------------------------------------------------- text / md

    fun renderText(
        container: LinearLayout,
        blocks: List<MarkdownParser.Block>,
        markdown: Boolean,
        sepia: Boolean,
        monospace: Boolean,
        textScale: Float = 1f
    ) {
        container.removeAllViews()
        val context = container.context
        container.addView(tagPill(context, markdown))

        for (block in blocks) {
            when (block) {
                is MarkdownParser.Block.Heading -> {
                    val view = bodyView(context, sepia)
                    when (block.level) {
                        1 -> {
                            TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Leaf_Heading1)
                            applySepia(view, sepia)
                        }
                        2 -> {
                            TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Leaf_Heading2)
                            applySepia(view, sepia)
                            topMargin(view, 22f)
                        }
                        else -> {
                            TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Leaf_Body)
                            applySepia(view, sepia)
                            view.setTypeface(view.typeface, Typeface.BOLD)
                            topMargin(view, 16f)
                        }
                    }
                    view.text = block.text
                    bottomMargin(view, if (block.level <= 1) 12f else 8f)
                    container.addView(view)
                }
                is MarkdownParser.Block.Paragraph -> {
                    val view = bodyView(context, sepia)
                    view.text = block.text
                    if (monospace) {
                        view.typeface = Typeface.MONOSPACE
                        view.textSize = 12.5f
                    }
                    bottomMargin(view, 12f)
                    container.addView(view)
                }
                is MarkdownParser.Block.Code -> {
                    val frame = FrameLayout(context)
                    frame.setBackgroundResource(R.drawable.bg_code_block)
                    val view = AppCompatTextView(context).apply {
                        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_Code)
                        applySepia(this, sepia)
                        text = block.text
                        setPadding(dp(context, 12f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
                    }
                    frame.addView(view)
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(context, 14f) }
                    container.addView(frame, params)
                }
                is MarkdownParser.Block.Quote -> {
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val bar = View(context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.accent)) }
                    row.addView(bar, LinearLayout.LayoutParams(dp(context, 3f), ViewGroup.LayoutParams.MATCH_PARENT))
                    val view = bodyView(context, sepia).apply {
                        text = block.text
                        setTypeface(typeface, Typeface.ITALIC)
                        setPadding(dp(context, 12f), 0, 0, 0)
                    }
                    row.addView(view)
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(context, 8f)
                        bottomMargin = dp(context, 14f)
                    }
                    container.addView(row, params)
                }
                is MarkdownParser.Block.ListBlock -> {
                    block.items.forEachIndexed { index, item ->
                        val view = bodyView(context, sepia)
                        val marker = if (block.ordered) "${block.start + index}. " else "• "
                        val text = SpannableStringBuilder(marker).apply { append(item.text) }
                        val first = dp(context, 4f) + dp(context, 18f) * item.indent
                        val rest = dp(context, 18f) * (item.indent + 1)
                        text.setSpan(LeadingMarginSpan.Standard(rest, rest), 0, text.length, SPAN)
                        view.text = text
                        view.setPadding(dp(context, 4f) + first, 0, 0, 0)
                        bottomMargin(view, 4f)
                        container.addView(view)
                    }
                    bottomMargin(container.getChildAt(container.childCount - 1), 10f)
                }
                MarkdownParser.Block.Rule -> {
                    val rule = View(context).apply {
                        setBackgroundColor(ContextCompat.getColor(context, R.color.line))
                    }
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(context, 1f)
                    ).apply {
                        topMargin = dp(context, 16f)
                        bottomMargin = dp(context, 16f)
                    }
                    container.addView(rule, params)
                }
            }
        }
        applyTextScale(container, textScale)
    }

    /** Single-paragraph plain text (used for .txt files). */
    fun renderPlainText(
        container: LinearLayout,
        text: CharSequence,
        sepia: Boolean,
        monospace: Boolean,
        textScale: Float = 1f
    ) {
        renderText(
            container,
            listOf(MarkdownParser.Block.Paragraph(text)),
            markdown = false,
            sepia = sepia,
            monospace = monospace,
            textScale = textScale
        )
    }

    // ----------------------------------------------------------------- docx

    /**
     * Renders a Word document as a stack of paper-like page sheets.
     *
     * Pagination is measured, not guessed: every block is built at the exact
     * page content width, scaled by [textScale], measured, and then packed
     * into pages of A4-ish proportions. Explicit page breaks cut immediately.
     */
    fun renderDocx(
        container: LinearLayout,
        blocks: List<DocxParser.Block>,
        sepia: Boolean,
        darkPages: Boolean,
        textScale: Float = 1f,
        onPageCount: (Int) -> Unit = {}
    ) {
        container.removeAllViews()
        val context = container.context
        val contentWidth = pageContentWidth(context)
        val exactWidth = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
        val unlimited = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        // Build + measure every block at final width first …
        val items = ArrayList<Pair<View?, Int>>(blocks.size)
        for (block in blocks) {
            if (block is DocxParser.Block.PageBreak) {
                items += null to 0 // explicit page-break marker
                continue
            }
            val view = when (block) {
                is DocxParser.Block.Para -> {
                    val view = bodyView(context, sepia)
                    val text = SpannableStringBuilder()
                    block.marker?.let { marker -> text.append(marker).append(' ') }
                    text.append(adaptColors(block.text, darkPages, sepia))
                    if (block.marker != null || block.indent > 0) {
                        val indent = dp(context, 18f) * (block.indent + 1)
                        text.setSpan(LeadingMarginSpan.Standard(indent, indent), 0, text.length, SPAN)
                    }
                    view.text = text
                    view.gravity = block.gravity
                    applyHeadingStyle(view, block.style, sepia)
                    view
                }
                is DocxParser.Block.Table -> buildTable(context, block, sepia, darkPages, textScale)
                is DocxParser.Block.Image -> ImageView(context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(block.bitmap)
                    contentDescription = context.getString(R.string.docx_image)
                    maxHeight = dp(context, 260f)
                }
                else -> null
            } ?: continue

            view.layoutParams = LinearLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            applyTextScale(view, textScale)
            view.measure(exactWidth, unlimited)
            val params = view.layoutParams as LinearLayout.LayoutParams
            items += view to (view.measuredHeight + params.topMargin + params.bottomMargin)
        }

        // … then pack them into pages (explicit breaks force a cut).
        val pageHeight = (contentWidth * PAGE_HEIGHT_RATIO).toInt()
        val pages = ArrayList<ArrayList<Pair<View, Int>>>()
        var current = ArrayList<Pair<View, Int>>()
        var used = 0
        for (item in items) {
            val view = item.first
            if (view == null) {
                if (current.isNotEmpty()) {
                    pages += current
                    current = ArrayList()
                    used = 0
                }
                continue
            }
            val fits = used + item.second <= pageHeight || current.isEmpty()
            if (!fits) {
                pages += current
                current = ArrayList()
                used = 0
            }
            current += view to item.second
            used += item.second
        }
        if (current.isNotEmpty()) pages += current
        if (pages.isEmpty()) pages += ArrayList()

        pages.forEach { pageItems ->
            val page = pageCard(context, sepia, pageHeight)
            pageItems.forEach { (view, _) ->
                page.addView(view, view.layoutParams as LinearLayout.LayoutParams)
            }
            container.addView(
                page,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 12f) }
            )
        }
        onPageCount(pages.size)
    }

    private const val PAGE_HEIGHT_RATIO = 1.30f

    private fun pageCard(context: Context, sepia: Boolean, minHeight: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_doc_page)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, if (sepia) R.color.sepia_page else R.color.card)
            )
            minimumHeight = minHeight
            setPadding(dp(context, PAGE_PAD_DP), dp(context, 18f), dp(context, PAGE_PAD_DP), dp(context, 22f))
        }

    private fun buildTable(
        context: Context,
        table: DocxParser.Block.Table,
        sepia: Boolean,
        darkPages: Boolean,
        textScale: Float
    ): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet_card)
        }
        val columns = table.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        table.rows.forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex == 0 && table.headerRow && !sepia) {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.sheet_header))
                } else {
                    setBackgroundResource(R.drawable.bg_grid_row)
                }
            }
            for (column in 0 until columns) {
                val value = adaptColors(row.getOrNull(column) ?: "", darkPages, sepia)
                val cell = AppCompatTextView(context).apply {
                    TextViewCompat.setTextAppearance(
                        this,
                        if (rowIndex == 0 && table.headerRow) {
                            R.style.TextAppearance_Leaf_CellHeader
                        } else {
                            R.style.TextAppearance_Leaf_Cell
                        }
                    )
                    applySepia(this, sepia)
                    text = value
                    setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
                    movementMethod = LinkMovementMethod.getInstance()
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                rowView.addView(cell)
                if (column < columns - 1) {
                    rowView.addView(
                        View(context).apply {
                            setBackgroundColor(ContextCompat.getColor(context, R.color.line))
                        },
                        LinearLayout.LayoutParams(dp(context, 1f), ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                }
            }
            root.addView(rowView)
        }
        return root
    }

    // ----------------------------------------------------------------- xlsx

    /** Builds the sticky-style header row that sits above the grid. */
    fun buildSheetHeader(header: LinearLayout, sheet: XlsxParser.Sheet, compact: Boolean) {
        header.removeAllViews()
        val context = header.context
        val width = context.resources.getDimensionPixelSize(R.dimen.cell_width)
        val height = context.resources.getDimensionPixelSize(
            if (compact) R.dimen.cell_height_compact else R.dimen.cell_height
        )
        val first = sheet.rows.firstOrNull()
        val columns = (first?.cells?.size ?: sheet.columnCount).coerceAtLeast(1)
        val paddingH = (12f * context.resources.displayMetrics.density).toInt()

        header.layoutParams?.height = height
        for (column in 0 until columns) {
            val value = first?.cells?.getOrNull(column)?.text ?: columnLabel(column)
            header.addView(
                AppCompatTextView(context).apply {
                    TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_CellHeader)
                    text = value
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(paddingH, 0, paddingH, 0)
                    layoutParams = LinearLayout.LayoutParams(width, height)
                }
            )
        }
    }

    private fun columnLabel(index: Int): String {
        var value = index
        val builder = StringBuilder()
        do {
            builder.insert(0, ('A'.code + (value % 26)).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return builder.toString()
    }

    // -------------------------------------------------------------- helpers

    /**
     * Document run colours are authored for their own page colour. A dark
     * `w:color` run is unreadable on night pages and a near-white run is
     * unreadable on white pages — drop those spans so the theme colour wins.
     */
    fun adaptColors(text: CharSequence, darkPages: Boolean, sepia: Boolean): CharSequence {
        if (text !is Spanned) return text
        val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
        if (spans.isEmpty()) return text
        val copy = SpannableStringBuilder(text)
        for (span in spans) {
            val color = span.foregroundColor
            val luminance = (0.299 * android.graphics.Color.red(color) +
                0.587 * android.graphics.Color.green(color) +
                0.114 * android.graphics.Color.blue(color)) / 255.0
            val drop = when {
                darkPages -> luminance < 0.55
                sepia -> luminance > 0.85
                else -> luminance > 0.90
            }
            if (drop) copy.removeSpan(span)
        }
        return copy
    }

    /** Recursively multiplies every text size (pinch-to-zoom on text docs). */
    fun applyTextScale(root: View, scale: Float) {
        if (abs(scale - 1f) < 0.01f) return
        when (root) {
            is TextView -> root.setTextSize(TypedValue.COMPLEX_UNIT_PX, root.textSize * scale)
            is ViewGroup -> for (i in 0 until root.childCount) {
                applyTextScale(root.getChildAt(i), scale)
            }
        }
    }

    private fun tagPill(context: Context, markdown: Boolean): AppCompatTextView =
        AppCompatTextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_TagPill)
            setBackgroundResource(R.drawable.bg_tag_pill)
            text = context.getString(if (markdown) R.string.tag_markdown else R.string.tag_text)
            val padV = dp(context, 3f)
            val padH = dp(context, 8f)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 6f) }
        }

    private fun bodyView(context: Context, sepia: Boolean): AppCompatTextView =
        AppCompatTextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_Body)
            applySepia(this, sepia)
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun applySepia(view: AppCompatTextView, sepia: Boolean) {
        if (sepia) {
            view.setTextColor(ContextCompat.getColor(view.context, R.color.sepia_text))
        }
    }

    private fun applyHeadingStyle(view: AppCompatTextView, style: String?, sepia: Boolean) {
        when (style) {
            "Title", "Heading1" -> {
                TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Leaf_Heading1)
                applySepia(view, sepia)
            }
            "Heading2" -> {
                TextViewCompat.setTextAppearance(view, R.style.TextAppearance_Leaf_Heading2)
                applySepia(view, sepia)
            }
            "Heading3" -> {
                view.textSize = 16f
                view.setTypeface(view.typeface, Typeface.BOLD)
            }
            "Heading4" -> {
                view.textSize = 15f
                view.setTypeface(view.typeface, Typeface.BOLD)
            }
            "Heading5", "Heading6" -> {
                view.textSize = 14f
                view.setTypeface(view.typeface, Typeface.BOLD)
            }
            "Subtitle" -> {
                view.textSize = 15f
                view.setTypeface(view.typeface, Typeface.ITALIC)
            }
            else -> Unit
        }
    }

    private fun topMargin(view: View, dp: Float) {
        (view.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(view.context, dp)
    }

    private fun bottomMargin(view: View?, dp: Float) {
        val params = view?.layoutParams as? LinearLayout.LayoutParams ?: return
        params.bottomMargin = dp(view.context, dp)
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

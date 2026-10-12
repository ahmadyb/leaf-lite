package com.leaflite.viewer.ui.viewer

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.LeadingMarginSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.leaflite.viewer.R
import com.leaflite.viewer.parsers.DocxParser
import com.leaflite.viewer.parsers.MarkdownParser
import com.leaflite.viewer.parsers.XlsxParser

/**
 * Turns parsed documents into native views.
 *
 * Everything is plain TextViews / LinearLayouts: cheap to build, cheap to
 * recycle with the screen, and no bitmaps except extracted DOCX images.
 */
object ViewerRenderer {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    // ----------------------------------------------------------- text / md

    fun renderText(
        container: LinearLayout,
        blocks: List<MarkdownParser.Block>,
        markdown: Boolean,
        sepia: Boolean,
        monospace: Boolean
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
    }

    /** Single-paragraph plain text (used for .txt files). */
    fun renderPlainText(
        container: LinearLayout,
        text: CharSequence,
        sepia: Boolean,
        monospace: Boolean
    ) {
        renderText(
            container,
            listOf(MarkdownParser.Block.Paragraph(text)),
            markdown = false,
            sepia = sepia,
            monospace = monospace
        )
    }

    // ----------------------------------------------------------------- docx

    fun renderDocx(container: LinearLayout, blocks: List<DocxParser.Block>, sepia: Boolean) {
        container.removeAllViews()
        val context = container.context
        if (sepia) container.setBackgroundColor(ContextCompat.getColor(context, R.color.sepia_page))

        for (block in blocks) {
            when (block) {
                is DocxParser.Block.Para -> {
                    val view = bodyView(context, sepia)
                    val text = SpannableStringBuilder()
                    block.marker?.let { marker -> text.append(marker).append(' ') }
                    text.append(block.text)
                    if (block.marker != null || block.indent > 0) {
                        val indent = dp(context, 18f) * (block.indent + 1)
                        text.setSpan(LeadingMarginSpan.Standard(indent, indent), 0, text.length, SPAN)
                    }
                    view.text = text
                    view.gravity = block.gravity
                    applyHeadingStyle(view, block.style, sepia)
                    if (view.text.isBlank()) {
                        bottomMargin(view, 4f)
                    } else {
                        bottomMargin(view, 10f)
                    }
                    container.addView(view)
                }
                is DocxParser.Block.Table -> container.addView(
                    buildTable(context, block, sepia),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(context, 8f)
                        bottomMargin = dp(context, 14f)
                    }
                )
                is DocxParser.Block.Image -> {
                    val image = ImageView(context).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(block.bitmap)
                        contentDescription = context.getString(R.string.docx_image)
                    }
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(context, 8f)
                        bottomMargin = dp(context, 12f)
                    }
                    image.maxHeight = dp(context, 260f)
                    container.addView(image, params)
                }
                else -> Unit // image references are resolved before rendering
            }
        }
    }

    private fun buildTable(
        context: Context,
        table: DocxParser.Block.Table,
        sepia: Boolean
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
                val value = row.getOrNull(column) ?: ""
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

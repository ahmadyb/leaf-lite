package com.leaflite.viewer.ui.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.leaflite.viewer.R
import com.leaflite.viewer.parsers.PptxParser

/**
 * Draws one PowerPoint slide: shape rectangles positioned from the OOXML
 * `a:off`/`a:ext` geometry (scaled to the view), text runs rendered with
 * [StaticLayout], pictures fitted into their frames.
 */
class SlideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var slide: PptxParser.Slide? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** True when the theme paints dark pages (run colours get adapted). */
    var darkPages: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val slide = this.slide
        val ratio = if (slide != null && slide.widthEmu > 0f) {
            slide.heightEmu / slide.widthEmu
        } else {
            9f / 16f
        }
        val height = (width * ratio).toInt()
        setMeasuredDimension(width, height.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val slide = this.slide ?: return
        val radius = 12f * resources.displayMetrics.density
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        pagePaint.color = ContextCompat.getColor(
            context,
            if (darkPages) R.color.card else Color.WHITE
        )
        canvas.drawRoundRect(rect, radius, radius, pagePaint)
        borderPaint.color = ContextCompat.getColor(context, R.color.line)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val scale = if (slide.widthEmu > 0f) width / slide.widthEmu else 1f
        for (shape in slide.shapes) {
            when (shape) {
                is PptxParser.Shape.Picture -> drawPicture(canvas, shape, scale)
                is PptxParser.Shape.TextBox -> drawText(canvas, shape, scale)
            }
        }
    }

    private fun drawPicture(canvas: Canvas, shape: PptxParser.Shape.Picture, scale: Float) {
        val bitmap = shape.bitmap ?: return
        val dest = RectF(
            shape.x * scale,
            shape.y * scale,
            (shape.x + shape.w) * scale,
            (shape.y + shape.h) * scale
        )
        canvas.save()
        canvas.clipRect(dest)
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destRatio = dest.width() / dest.height().coerceAtLeast(1f)
        val fitted: RectF = if (srcRatio > destRatio) {
            val h = dest.width() / srcRatio
            RectF(dest.left, dest.centerY() - h / 2f, dest.right, dest.centerY() + h / 2f)
        } else {
            val w = dest.height() * srcRatio
            RectF(dest.centerX() - w / 2f, dest.top, dest.centerX() + w / 2f, dest.bottom)
        }
        canvas.drawBitmap(bitmap, null, fitted, null)
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, shape: PptxParser.Shape.TextBox, scale: Float) {
        var top = shape.y * scale
        val left = shape.x * scale
        val maxWidth = (shape.w * scale).coerceAtLeast(48f)
        val bottom = (shape.y + shape.h) * scale

        for (para in shape.paragraphs) {
            if (top > bottom && bottom > 0f) break
            val text = SpannableStringBuilder()
            if (para.bullet) {
                text.append(if (para.level > 0) "– " else "• ")
            }
            var baseSize = 14f
            for (run in para.runs) {
                val start = text.length
                text.append(run.text)
                val color = adaptColor(run.color)
                if (color != null) {
                    text.setSpan(
                        ForegroundColorSpan(color),
                        start,
                        text.length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (run.sizePt > 4f) baseSize = run.sizePt
            }
            if (text.isEmpty()) {
                top += baseSize * scale * 0.5f
                continue
            }

            textPaint.textSize = baseSize * scale
            textPaint.isFakeBoldText = para.runs.any { it.bold }
            textPaint.isUnderlineText = false
            textPaint.typeface = if (para.runs.any { it.italic }) {
                Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            } else {
                Typeface.DEFAULT
            }
            textPaint.color = ContextCompat.getColor(context, R.color.text)

            val indent = para.level * 14f * scale
            val layout = StaticLayout.Builder.obtain(
                text, 0, text.length, textPaint, maxWidth.toInt().coerceAtLeast(48)
            )
                .setAlignment(
                    when (para.align) {
                        "ctr" -> Layout.Alignment.ALIGN_CENTER
                        "r", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                )
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(left + indent, top)
            layout.draw(canvas)
            canvas.restore()
            top += layout.height + 4f * scale
        }
    }

    /** Slide run colours are authored for white slides — adapt for dark pages. */
    private fun adaptColor(color: Int?): Int? {
        if (color == null) return null
        if (!darkPages) return color
        val luminance = (0.299 * Color.red(color) +
            0.587 * Color.green(color) +
            0.114 * Color.blue(color)) / 255.0
        return if (luminance < 0.55) null else color
    }
}

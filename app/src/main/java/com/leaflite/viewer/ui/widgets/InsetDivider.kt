package com.leaflite.viewer.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.leaflite.viewer.R

/**
 * Hairline dividers that start at 62dp (past the 38dp icon tile), exactly like
 * the mockup's `.doc-row + .doc-row::before`.
 */
class InsetDivider(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
        style = Paint.Style.FILL
    }
    private val insetPx = context.resources.getDimension(R.dimen.divider_inset)
    private val thickness = 1f

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.setEmpty()
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position <= 0) continue
            val top = child.top + child.translationY
            canvas.drawRect(
                child.left + insetPx,
                top,
                child.right.toFloat(),
                top + thickness,
                paint
            )
        }
    }
}

package com.leaflite.viewer.util

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/** dp -> px. */
fun View.dp(value: Float): Float = value * resources.displayMetrics.density

/** dp -> px (rounded). */
fun View.dpInt(value: Float): Int = (value * resources.displayMetrics.density).round()

private fun Float.round(): Int = (this + 0.5f).toInt()

/** The mockup's press feedback: a quick scale down and back. */
fun View.pressScale(scale: Float = 0.94f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(scale).scaleY(scale).setDuration(110L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
        }
        false // never swallow the click
    }
}

/** Rounded rectangle, optionally with a 1px hairline stroke. */
fun roundedRect(
    @ColorInt fill: Int,
    radiusPx: Float,
    @ColorInt stroke: Int = 0,
    strokeWidthPx: Float = 0f
): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radiusPx
    if (strokeWidthPx > 0f) setStroke(strokeWidthPx.toInt().coerceAtLeast(1), stroke)
}

/** Material ripple over [content] (API 21+, which is below our minSdk 23). */
fun rippleOver(content: Drawable, @ColorInt rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

@ColorInt
fun View.colorOf(id: Int): Int = ContextCompat.getColor(context, id)

package com.leaflite.viewer.ui.widgets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.leaflite.viewer.R

/**
 * The mockup's pill switch: 46×28dp track, 22dp knob, accent track when ON and
 * a decelerating slide. Hand-drawn on a Canvas — no drawable states, no
 * Material dependency, ~2KB of code.
 */
class PillSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCheckedChange: ((Boolean) -> Unit)? = null

    var isOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateKnob()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackWidth = resources.getDimension(R.dimen.switch_width)
    private val trackHeight = resources.getDimension(R.dimen.switch_height)
    private val knobSize = resources.getDimension(R.dimen.switch_knob)
    private val padding = (3f * resources.displayMetrics.density)
    private val travel = trackWidth - knobSize - padding * 2f

    private var knobPosition = 0f // 0f = off, 1f = on
    private var animator: ValueAnimator? = null

    init {
        minimumWidth = trackWidth.toInt()
        minimumHeight = trackHeight.toInt()
        isClickable = true
    }

    fun setChecked(checked: Boolean, animate: Boolean) {
        if (isOn == checked) {
            if (!animate) {
                knobPosition = if (checked) 1f else 0f
                invalidate()
            }
            return
        }
        isOn = checked
        if (!animate) {
            knobPosition = if (checked) 1f else 0f
            invalidate()
        }
    }

    private fun animateKnob() {
        animator?.cancel()
        val target = if (isOn) 1f else 0f
        animator = ValueAnimator.ofFloat(knobPosition, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                knobPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(trackWidth.toInt(), widthMeasureSpec),
            resolveSize(trackHeight.toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = measuredWidth.toFloat()
        val height = measuredHeight.toFloat()
        val radius = height / 2f

        val offColor = ContextCompat.getColor(context, R.color.switch_off)
        val onColor = ContextCompat.getColor(context, R.color.accent)
        trackPaint.color = blend(offColor, onColor, knobPosition)
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, trackPaint)

        val knobRadius = knobSize / 2f
        val cx = padding + knobRadius + travel * knobPosition
        val cy = height / 2f
        knobPaint.color = ContextCompat.getColor(context, R.color.switch_knob)
        canvas.drawCircle(cx, cy, knobRadius, knobPaint)
        knobPaint.color = Color.argb(38, 0, 0, 0)
        knobPaint.style = Paint.Style.STROKE
        knobPaint.strokeWidth = resources.displayMetrics.density * 0.5f
        canvas.drawCircle(cx, cy, knobRadius, knobPaint)
        knobPaint.style = Paint.Style.FILL
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                isOn = !isOn
                onCheckedChange?.invoke(isOn)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val a = android.graphics.Color.alpha(from) + ((android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t).toInt()
        val r = android.graphics.Color.red(from) + ((android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t).toInt()
        val g = android.graphics.Color.green(from) + ((android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t).toInt()
        val b = android.graphics.Color.blue(from) + ((android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }
}

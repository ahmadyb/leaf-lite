package com.leaflite.viewer.ui.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * PDF page view with pinch-zoom, double-tap zoom and panned navigation.
 *
 * At 1x the view never intercepts touches, so the surrounding RecyclerView
 * keeps scrolling normally. As soon as the page is zoomed (or a second finger
 * lands) it takes over and asks the parent not to intercept.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val fit = Matrix()
    private val drawableRect = RectF()
    private val workingRect = RectF()

    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var animator: ValueAnimator? = null
    private var swapping = false
    private var zoomDirty = false

    var isZoomed: Boolean = false
        private set

    /**
     * Invoked with the current scale whenever a zoom gesture settles (finger
     * up / double-tap animation finished). Owners use it to swap in a sharper
     * bitmap for the zoom level.
     */
    var onZoomSettled: ((Float) -> Unit)? = null

    private val maxScale = 5f
    private val doubleTapScale = 2.5f

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            applyScale(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(event: MotionEvent): Boolean {
            toggleZoom(event.x, event.y)
            return true
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, scaleListener)
    private val gestureDetector = GestureDetector(context, gestureListener).apply {
        setOnDoubleTapListener(gestureListener)
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    // ------------------------------------------------------------------ layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** A new bitmap always starts at 1x, fitted to the view. */
    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        if (!swapping) reset()
    }

    /**
     * Swaps in a higher-resolution bitmap of the *same page* without losing
     * the zoom/pan. The drawable coordinate space grows by `k`, so the matrix
     * is pre-scaled by `1/k` to keep the on-screen view identical.
     *
     * A swap must NEVER reset the user's transform: same-size bitmaps are
     * ignored entirely, and the scale/zoomed state stay untouched.
     */
    fun swapBitmap(bitmap: android.graphics.Bitmap) {
        val oldWidth = drawable?.intrinsicWidth ?: 0
        if (oldWidth <= 0 || bitmap.width <= 0 || oldWidth == bitmap.width) return
        swapping = true
        setImageBitmap(bitmap)
        swapping = false
        val k = bitmap.width.toFloat() / oldWidth.toFloat()
        val correction = Matrix()
        correction.setScale(1f / k, 1f / k)
        matrix.preConcat(correction)
        // drawableRect is in old drawable units; rebuild for the new one
        drawableRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        constrain()
        imageMatrix = matrix
    }

    /** Recomputes the fit matrix (drawable centred and scaled to the view). */
    private fun computeFit() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawWidth = drawable.intrinsicWidth.toFloat()
        val drawHeight = drawable.intrinsicHeight.toFloat()
        if (drawWidth <= 0f || drawHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return
        drawableRect.set(0f, 0f, drawWidth, drawHeight)
        val scaleFactor = min(viewWidth / drawWidth, viewHeight / drawHeight)
        fit.reset()
        fit.postScale(scaleFactor, scaleFactor)
        fit.postTranslate(
            (viewWidth - drawWidth * scaleFactor) / 2f,
            (viewHeight - drawHeight * scaleFactor) / 2f
        )
    }

    /** Drops any zoom/pan (called when the RecyclerView recycles the row). */
    fun reset() {
        animator?.cancel()
        animator = null
        scale = 1f
        isZoomed = false
        computeFit()
        matrix.set(fit)
        imageMatrix = matrix
    }

    // ------------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(isZoomed)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && isZoomed && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!dragging && (kotlin.math.abs(dx) > 4f || kotlin.math.abs(dy) > 4f)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        matrix.postTranslate(dx, dy)
                        constrain()
                        imageMatrix = matrix
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !isZoomed) performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                // Settle only after an actual zoom change — panning must not
                // trigger bitmap swaps (that used to snap the zoom back).
                if (isZoomed && zoomDirty) {
                    zoomDirty = false
                    onZoomSettled?.invoke(scale)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------- zoom

    private fun applyScale(target: Float, focusX: Float, focusY: Float) {
        val next = target.coerceIn(1f, maxScale)
        val factor = next / scale
        if (factor == 1f) return
        matrix.postScale(factor, factor, focusX, focusY)
        scale = next
        isZoomed = scale > 1.01f
        zoomDirty = true
        constrain()
        imageMatrix = matrix
    }

    private fun toggleZoom(focusX: Float, focusY: Float) {
        if (animator?.isRunning == true) return
        val target = if (isZoomed) 1f else doubleTapScale
        val start = scale
        val animator = ValueAnimator.ofFloat(start, target).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                applyScale(it.animatedValue as Float, focusX, focusY)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    zoomDirty = false
                    if (isZoomed) onZoomSettled?.invoke(scale)
                }
            })
        }
        this.animator = animator
        animator.start()
    }

    /** Keeps the page inside the viewport (or centred when it is smaller). */
    private fun constrain() {
        if (drawableRect.isEmpty) return
        workingRect.set(drawableRect)
        matrix.mapRect(workingRect)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        var dx = 0f
        var dy = 0f

        if (workingRect.width() <= viewWidth) {
            dx = viewWidth / 2f - workingRect.centerX()
        } else {
            if (workingRect.left > 0f) dx = -workingRect.left
            else if (workingRect.right < viewWidth) dx = viewWidth - workingRect.right
        }

        if (workingRect.height() <= viewHeight) {
            dy = viewHeight / 2f - workingRect.centerY()
        } else {
            if (workingRect.top > 0f) dy = -workingRect.top
            else if (workingRect.bottom < viewHeight) dy = viewHeight - workingRect.bottom
        }

        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

}

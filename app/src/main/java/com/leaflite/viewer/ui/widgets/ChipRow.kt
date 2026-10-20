package com.leaflite.viewer.ui.widgets

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.leaflite.viewer.R
import com.leaflite.viewer.util.colorOf
import com.leaflite.viewer.util.pressScale
import com.leaflite.viewer.util.rippleOver
import com.leaflite.viewer.util.roundedRect

/**
 * Horizontally scrollable filter chip row (no scrollbar).
 * Inactive: white + hairline border + @color/chip_text.
 * Active: inverted — filled @color/text with a white label.
 */
class ChipRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var onChipSelected: ((Int) -> Unit)? = null

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private val chips = ArrayList<AppCompatTextView>()
    private var selectedIndex = 0

    init {
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setItems(labels: List<String>, selected: Int = 0, listener: ((Int) -> Unit)? = null) {
        listener?.let { onChipSelected = it }
        container.removeAllViews()
        chips.clear()
        labels.forEachIndexed { index, label ->
            val chip = buildChip(label)
            chip.pressScale(0.95f)
            chip.setOnClickListener {
                if (selectedIndex != index) {
                    setSelected(index)
                    onChipSelected?.invoke(index)
                }
            }
            chips += chip
            container.addView(chip)
        }
        selectedIndex = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        applySelection()
    }

    fun setSelected(index: Int) {
        if (index == selectedIndex) return
        selectedIndex = index
        applySelection()
        requestLayout()
    }

    fun selectedIndex(): Int = selectedIndex

    private fun buildChip(label: String): AppCompatTextView {
        val paddingV = (8f * resources.displayMetrics.density).toInt()
        val paddingH = (15f * resources.displayMetrics.density).toInt()
        return AppCompatTextView(context).apply {
            text = label
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_Chip)
            gravity = Gravity.CENTER
            setPadding(paddingH, paddingV, paddingH, paddingV)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8f * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun applySelection() {
        val radius = resources.getDimension(R.dimen.radius_chip)
        val line = colorOf(R.color.line)
        chips.forEachIndexed { index, chip ->
            val active = index == selectedIndex
            val fill = colorOf(if (active) R.color.text else R.color.card)
            val shape: GradientDrawable = if (active) {
                roundedRect(fill, radius)
            } else {
                roundedRect(fill, radius, line, resources.displayMetrics.density)
            }
            chip.background = rippleOver(shape, colorOf(R.color.ripple))
            chip.setTextColor(
                ContextCompat.getColor(context, if (active) R.color.card else R.color.chip_text)
            )
        }
    }
}

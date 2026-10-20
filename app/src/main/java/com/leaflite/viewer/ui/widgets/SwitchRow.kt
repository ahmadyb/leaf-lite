package com.leaflite.viewer.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.leaflite.viewer.R

/**
 * Settings row: bold label + faint sub-label on the left, [PillSwitch] on the
 * right. The whole row is the tap target (48dp minimum).
 */
class SwitchRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onCheckedChange: ((Boolean) -> Unit)? = null

    var isChecked: Boolean
        get() = switch.isOn
        set(value) = switch.setChecked(value, animate = false)

    private val labelView = AppCompatTextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_FileName)
    }
    private val subView = AppCompatTextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_FileSub)
    }
    private val switch = PillSwitch(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target)
        val padH = resources.getDimensionPixelSize(R.dimen.pad_content)
        val padV = (13f * resources.displayMetrics.density).toInt()
        setPadding(padH, padV, padH, padV)
        background = ContextCompat.getDrawable(context, R.drawable.ripple_row)

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(labelView)
            addView(subView)
        }
        addView(
            texts,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (12f * resources.displayMetrics.density).toInt()
            }
        )
        addView(switch, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        isClickable = true
        isFocusable = true
        setOnClickListener {
            switch.isOn = !switch.isOn
            onCheckedChange?.invoke(switch.isOn)
        }
        switch.onCheckedChange = { checked -> onCheckedChange?.invoke(checked) }

        context.theme.obtainStyledAttributes(attrs, R.styleable.SwitchRow, 0, 0).run {
            try {
                labelView.text = getString(R.styleable.SwitchRow_label)
                val sub = getString(R.styleable.SwitchRow_subLabel)
                subView.text = sub
                if (sub.isNullOrEmpty()) subView.visibility = GONE
                switch.setChecked(getBoolean(R.styleable.SwitchRow_checked, false), false)
            } finally {
                recycle()
            }
        }
    }

    fun setLabel(text: CharSequence) {
        labelView.text = text
    }

    fun setSubLabel(text: CharSequence) {
        subView.text = text
        subView.visibility = if (text.isEmpty()) GONE else VISIBLE
    }
}

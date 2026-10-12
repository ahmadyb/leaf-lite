package com.leaflite.viewer.ui.viewer

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.leaflite.viewer.R
import com.leaflite.viewer.databinding.ItemSheetRowBinding
import com.leaflite.viewer.parsers.XlsxParser

/**
 * Spreadsheet grid rows. Each row is a horizontal stack of fixed-width cells so
 * the grid can scroll vertically (RecyclerView) and horizontally (the parent
 * HorizontalScrollView) without any nested-scrolling gymnastics.
 */
class SheetAdapter(
    private val sheet: XlsxParser.Sheet,
    private val compact: Boolean
) : RecyclerView.Adapter<SheetAdapter.RowVH>() {

    override fun getItemCount(): Int = sheet.rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val binding = ItemSheetRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val height = parent.context.resources.getDimensionPixelSize(
            if (compact) R.dimen.cell_height_compact else R.dimen.cell_height
        )
        binding.root.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            height
        )
        return RowVH(binding)
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        holder.bind(sheet.rows[position])
    }

    class RowVH(private val binding: ItemSheetRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: XlsxParser.Row) {
            val container = binding.cells
            container.removeAllViews()
            val width = container.resources.getDimensionPixelSize(R.dimen.cell_width)
            val paddingV = (10f * container.resources.displayMetrics.density).toInt()
            val paddingH = (12f * container.resources.displayMetrics.density).toInt()
            for (cell in row.cells) {
                container.addView(
                    AppCompatTextView(container.context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            width,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Leaf_Cell)
                        text = cell.text
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = if (cell.numeric) {
                            Gravity.CENTER_VERTICAL or Gravity.END
                        } else {
                            Gravity.CENTER_VERTICAL or Gravity.START
                        }
                        setPadding(paddingH, paddingV, paddingH, paddingV)
                        if (cell.numeric) {
                            @SuppressLint("WrongConstant")
                            fontFeatureSettings = "tnum"
                            setTextColor(
                                androidx.core.content.ContextCompat.getColor(
                                    context,
                                    R.color.text
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

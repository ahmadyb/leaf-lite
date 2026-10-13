package com.leaflite.viewer.ui.library

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.leaflite.viewer.R
import com.leaflite.viewer.data.Doc
import com.leaflite.viewer.databinding.ItemDocumentBinding
import com.leaflite.viewer.util.FileSize
import com.leaflite.viewer.util.Formatters

/**
 * Document rows for both the Library and the Starred tab.
 *
 * Rows hold no bitmaps (vector icons only), use stable ids and a DiffUtil
 * callback so 1,000+ documents scroll at 60fps on low-end hardware.
 */
class DocumentAdapter(
    private val onOpen: (Doc) -> Unit,
    private val onStar: (Doc) -> Unit,
    private val onShare: (Doc) -> Unit
) : ListAdapter<Doc, DocumentAdapter.VH>(Diff) {

    var compact: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val doc = getItem(position)
        return if (doc.id != 0L) doc.id else doc.uri.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), compact)
    }

    inner class VH(private val binding: ItemDocumentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(doc: Doc, compact: Boolean) {
            val context = binding.root.context
            val resources = context.resources
            binding.root.setOnLongClickListener {
                onShare(doc)
                true
            }

            val rowHeight = resources.getDimensionPixelSize(
                if (compact) R.dimen.row_height_compact else R.dimen.row_height
            )
            binding.root.updateLayoutParams<RecyclerView.LayoutParams> { height = rowHeight }

            val tile = resources.getDimensionPixelSize(
                if (compact) R.dimen.tile_size_compact else R.dimen.tile_size
            )
            binding.typeTile.updateLayoutParams<android.view.ViewGroup.LayoutParams> {
                width = tile
                height = tile
            }

            binding.name.text = doc.displayName
            binding.sub.text = buildSubLine(context, doc)

            val color = ContextCompat.getColor(context, doc.type.colorRes)
            val cardColor = ContextCompat.getColor(context, R.color.card)
            binding.typeIcon.setImageResource(doc.type.iconRes)
            ImageViewCompat.setImageTintList(binding.typeIcon, ColorStateList.valueOf(color))
            binding.typeTile.backgroundTintList =
                ColorStateList.valueOf(ColorUtils.blendARGB(cardColor, color, 0.12f))

            if (doc.starred) {
                binding.trailing.setImageResource(R.drawable.ic_star_filled)
                ImageViewCompat.setImageTintList(
                    binding.trailing,
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.star))
                )
                binding.starZone.contentDescription =
                    context.getString(R.string.cd_unstar)
            } else {
                binding.trailing.setImageResource(R.drawable.ic_chevron_right)
                ImageViewCompat.setImageTintList(
                    binding.trailing,
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.chevron))
                )
                binding.starZone.contentDescription =
                    context.getString(R.string.cd_star)
            }

            binding.starZone.setOnClickListener { onStar(doc) }
            binding.root.setOnClickListener { onOpen(doc) }
        }

        private fun buildSubLine(context: android.content.Context, doc: Doc): String {
            val parts = ArrayList<String>(3)
            parts += context.getString(doc.type.labelRes)
            parts += FileSize.format(doc.size)
            val date = Formatters.relativeDate(context, doc.dateModified)
            if (date.isNotEmpty()) parts += date
            return parts.joinToString(" · ")
        }
    }

    private object Diff : DiffUtil.ItemCallback<Doc>() {
        override fun areItemsTheSame(oldItem: Doc, newItem: Doc): Boolean =
            oldItem.id == newItem.id && oldItem.uri == newItem.uri

        override fun areContentsTheSame(oldItem: Doc, newItem: Doc): Boolean =
            oldItem == newItem
    }
}

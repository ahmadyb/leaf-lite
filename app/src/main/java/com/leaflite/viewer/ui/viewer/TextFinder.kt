package com.leaflite.viewer.ui.viewer

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView

/**
 * Find-in-document helpers for text-bearing formats (TXT, MD, DOCX, slides,
 * sheets): case-insensitive substring search across every [TextView] in a
 * view tree, accent highlights, and scroll-to-match.
 */
object TextFinder {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    data class Match(val view: TextView, val start: Int, val end: Int)

    /** All case-insensitive occurrences of [query], depth-first. */
    fun findAll(root: View, query: String): List<Match> {
        if (query.isEmpty()) return emptyList()
        val needle = query.lowercase()
        val matches = ArrayList<Match>()
        collectTextViews(root) { view ->
            val text = view.text ?: return@collectTextViews
            val haystack = text.toString().lowercase()
            var index = haystack.indexOf(needle)
            while (index >= 0) {
                matches += Match(view, index, index + query.length)
                index = haystack.indexOf(needle, index + query.length.coerceAtLeast(1))
            }
        }
        return matches
    }

    /** Highlights every match (accent wash); originals are kept in the tag. */
    fun highlight(root: View, query: String, accentBg: Int, accentFg: Int): List<Match> {
        clear(root)
        val matches = findAll(root, query)
        matches.groupBy { it.view }.forEach { (view, ranges) ->
            val original = view.text
            view.tag = original
            val copy = SpannableStringBuilder(original)
            ranges.forEach { match ->
                copy.setSpan(BackgroundColorSpan(accentBg), match.start, match.end, SPAN)
                copy.setSpan(ForegroundColorSpan(accentFg), match.start, match.end, SPAN)
            }
            view.text = copy
        }
        return matches
    }

    /** Restores the pre-highlight text of every view in the tree. */
    fun clear(root: View) {
        collectTextViews(root) { view ->
            val original = view.tag as? CharSequence
            if (original != null) {
                view.text = original
                view.tag = null
            }
        }
    }

    /** Scrolls [scroll] so that [match] is comfortably visible. */
    fun scrollTo(scroll: NestedScrollView?, match: Match) {
        if (scroll == null) return
        val view = match.view
        scroll.post {
            var top = 0f
            var child: View? = view
            while (child != null && child !== scroll) {
                top += child.top
                child = child.parent as? View
            }
            val layout = view.layout ?: return@post
            val line = layout.getLineForOffset(match.start.coerceIn(0, (view.text?.length ?: 1) - 1))
            val y = (top + layout.getLineTop(line) - scroll.height / 3f).toInt()
            scroll.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    private fun collectTextViews(root: View, action: (TextView) -> Unit) {
        when (root) {
            is TextView -> action(root)
            is ViewGroup -> for (i in 0 until root.childCount) {
                collectTextViews(root.getChildAt(i), action)
            }
        }
    }
}

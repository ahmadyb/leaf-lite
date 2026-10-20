package com.leaflite.viewer.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.leaflite.viewer.R
import com.leaflite.viewer.databinding.ViewTabBarBinding
import com.leaflite.viewer.util.addNavigationBarPadding
import com.leaflite.viewer.util.pressScale

/**
 * The three-tab bottom bar (Library / Starred / Settings).
 *
 * Height is 64dp plus the navigation-bar inset, which this view adds to its own
 * bottom padding so the app can stay edge-to-edge on every API level.
 */
class BottomTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Tab { LIBRARY, STARRED, SETTINGS }

    private data class TabView(
        val tab: Tab,
        val group: View,
        val icon: ImageView,
        val label: TextView
    )

    var onTabSelected: ((Tab) -> Unit)? = null

    private var selected: Tab = Tab.LIBRARY
    private lateinit var tabs: List<TabView>

    init {
        orientation = HORIZONTAL
        View.inflate(context, R.layout.view_tab_bar, this)
        val binding = ViewTabBarBinding.bind(this)
        tabs = listOf(
            TabView(Tab.LIBRARY, binding.tabLibrary, binding.tabLibraryIcon, binding.tabLibraryLabel),
            TabView(Tab.STARRED, binding.tabStarred, binding.tabStarredIcon, binding.tabStarredLabel),
            TabView(Tab.SETTINGS, binding.tabSettings, binding.tabSettingsIcon, binding.tabSettingsLabel)
        )
        tabs.forEach { tabView ->
            tabView.group.pressScale(0.94f)
            tabView.group.setOnClickListener { select(tabView.tab) }
        }
        addNavigationBarPadding()
        applySelection()
    }

    fun select(tab: Tab) {
        val changed = selected != tab
        selected = tab
        if (changed) applySelection()
        onTabSelected?.invoke(tab)
    }

    /** Highlights a tab without firing the callback (used when restoring). */
    fun setSelectedQuietly(tab: Tab) {
        selected = tab
        applySelection()
    }

    val currentTab: Tab get() = selected

    private fun applySelection() {
        val activeColor = ContextCompat.getColor(context, R.color.accent)
        val idleColor = ContextCompat.getColor(context, R.color.tab_idle)
        tabs.forEach { tabView ->
            val active = tabView.tab == selected
            val color = if (active) activeColor else idleColor
            ImageViewCompat.setImageTintList(tabView.icon, ColorStateList.valueOf(color))
            tabView.label.setTextColor(color)
        }
    }
}

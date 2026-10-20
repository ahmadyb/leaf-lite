package com.leaflite.viewer.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Sizes [this] spacer view to exactly the status-bar height, so screens can draw
 * edge-to-edge and still keep their content below the status bar.
 */
fun View.matchStatusBarHeight() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        v.updateLayoutParams<ViewGroup.LayoutParams> { height = top }
        insets
    }
    requestWindowInsetsOnce()
}

/**
 * Adds the navigation-bar height to the current bottom padding (used by the
 * bottom tab bar, which sits directly above the system navigation bar).
 */
fun View.addNavigationBarPadding() {
    val basePadding = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        v.updatePadding(bottom = basePadding + bottom)
        insets
    }
    requestWindowInsetsOnce()
}

private fun View.requestWindowInsetsOnce() {
    doOnAttach { ViewCompat.requestApplyInsets(it) }
}

package com.leaflite.viewer.util

import java.util.Locale

/**
 * Human readable file sizes: 0 B, 812 B, 340 KB, 2.4 MB …
 * Pure Kotlin (no framework calls) so it is unit-testable on the JVM.
 */
object FileSize {

    private const val KB = 1024.0

    fun format(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= KB && unit < units.lastIndex) {
            value /= KB
            unit++
        }
        if (unit == 0) return "$bytes B"
        return "${trim(value, unit)} ${units[unit]}"
    }

    private fun trim(value: Double, unit: Int): String {
        val decimals = if (value >= 100.0 || unit >= 3) 0 else 1
        val text = String.format(Locale.US, "%.${decimals}f", value)
        return if (text.endsWith(".0")) text.substring(0, text.length - 2) else text
    }
}

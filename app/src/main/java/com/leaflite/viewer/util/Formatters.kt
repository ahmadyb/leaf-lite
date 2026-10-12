package com.leaflite.viewer.util

import android.content.Context
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Date helpers for the document rows ("Today, 9:41 AM", "Yesterday", "Monday")
 * and for the settings sub-label ("scanned 2 min ago").
 */
object Formatters {

    private const val WEEK_MS = 7L * 24L * 60L * 60L * 1000L

    /** Compact relative date used in the document sub-line. */
    fun relativeDate(context: Context, millis: Long): String {
        if (millis <= 0L) return ""
        return when {
            DateUtils.isToday(millis) -> "Today, ${timeFormat(context).format(millis)}"
            daysAgo(millis) == 1 -> "Yesterday"
            System.currentTimeMillis() - millis < WEEK_MS -> weekday(millis)
            else -> DateUtils.formatDateTime(
                context,
                millis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                    DateUtils.FORMAT_SHOW_YEAR
            )
        }
    }

    /** "2 min ago" / "3 hours ago" style label used by the settings screen. */
    fun relativeAgo(millis: Long): String {
        if (millis <= 0L) return ""
        return DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    private fun timeFormat(context: Context): java.text.DateFormat =
        android.text.format.DateFormat.getTimeFormat(context)

    private fun weekday(millis: Long): String =
        SimpleDateFormat("EEEE", Locale.getDefault()).format(millis)

    private fun daysAgo(millis: Long): Long {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = start.timeInMillis
        val dayMs = 24L * 60L * 60L * 1000L
        return ((todayStart - millis + dayMs - 1) / dayMs)
    }
}

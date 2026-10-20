package com.leaflite.viewer.data

import android.content.Context
import androidx.core.content.edit

/**
 * Tiny typed wrapper over one SharedPreferences file. No framework, no
 * serialization library — a handful of keys is all Leaf Lite needs.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var nightMode: Boolean
        get() = sp.getBoolean(KEY_NIGHT, false)
        set(value) = sp.edit { putBoolean(KEY_NIGHT, value) }

    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN, true)
        set(value) = sp.edit { putBoolean(KEY_KEEP_SCREEN, value) }

    var compactList: Boolean
        get() = sp.getBoolean(KEY_COMPACT, false)
        set(value) = sp.edit { putBoolean(KEY_COMPACT, value) }

    var monospace: Boolean
        get() = sp.getBoolean(KEY_MONOSPACE, false)
        set(value) = sp.edit { putBoolean(KEY_MONOSPACE, value) }

    var readingMode: Boolean
        get() = sp.getBoolean(KEY_READING, false)
        set(value) = sp.edit { putBoolean(KEY_READING, value) }

    var sortOption: SortOption
        get() = SortOption.fromKey(sp.getString(KEY_SORT, null) ?: SortOption.NAME_ASC.key)
        set(value) = sp.edit { putString(KEY_SORT, value.key) }

    var lastScanAt: Long
        get() = sp.getLong(KEY_LAST_SCAN, 0L)
        set(value) = sp.edit { putLong(KEY_LAST_SCAN, value) }

    /** Persisted SAF tree grants ("Add folder to library"). */
    var treeUris: Set<String>
        get() = sp.getStringSet(KEY_TREES, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit { putStringSet(KEY_TREES, value.toSet()) }

    private companion object {
        const val FILE = "leaflite"
        const val KEY_NIGHT = "night_mode"
        const val KEY_KEEP_SCREEN = "keep_screen_on"
        const val KEY_COMPACT = "compact_list"
        const val KEY_MONOSPACE = "monospace"
        const val KEY_READING = "reading_mode"
        const val KEY_SORT = "sort"
        const val KEY_LAST_SCAN = "last_scan_at"
        const val KEY_TREES = "saf_trees"
    }
}

package com.leaflite.viewer.data

/** Shared sort implementation for the Library and Starred lists. */
fun List<Doc>.sortedFor(option: SortOption): List<Doc> = when (option) {
    SortOption.NAME_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    SortOption.NAME_DESC ->
        sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }).asReversed()
    SortOption.DATE_DESC -> sortedByDescending { it.dateModified }
    SortOption.DATE_ASC -> sortedBy { it.dateModified }
    SortOption.SIZE_DESC -> sortedByDescending { it.size }
    SortOption.SIZE_ASC -> sortedBy { it.size }
}

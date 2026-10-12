package com.leaflite.viewer.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leaflite.viewer.LeafLiteApp
import com.leaflite.viewer.data.Doc
import com.leaflite.viewer.data.DocumentRepository
import com.leaflite.viewer.data.ScanResult
import com.leaflite.viewer.data.SortOption
import com.leaflite.viewer.data.sortedFor
import com.leaflite.viewer.data.TypeFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library tab state: documents + search query + type filter + sort order.
 * Everything is derived from the repository flow, so starring a document in the
 * viewer or in the Starred tab updates the list immediately.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: DocumentRepository = (app as LeafLiteApp).repository
    private val prefs = (app as LeafLiteApp).prefs

    val rawDocuments: StateFlow<List<Doc>> = repository.documents
    val scanning: StateFlow<Boolean> = repository.scanning

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(TypeFilter.ALL)
    private val sort = MutableStateFlow(prefs.sortOption)

    val visible: StateFlow<List<Doc>> =
        combine(rawDocuments, query, filter, sort) { docs, text, typeFilter, sortOption ->
            docs.filtered(text, typeFilter).sortedFor(sortOption)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sectionLabel: StateFlow<Pair<Int, Int>> =
        combine(filter, visible) { typeFilter, docs ->
            typeFilter.sectionLabelRes to docs.size
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TypeFilter.ALL.sectionLabelRes to 0)

    val currentFilter: TypeFilter get() = filter.value
    val currentSort: SortOption get() = sort.value
    val currentQuery: String get() = query.value

    fun setQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: TypeFilter) {
        filter.value = value
    }

    fun setSort(value: SortOption) {
        sort.value = value
        prefs.sortOption = value
    }

    fun toggleStar(doc: Doc) {
        repository.toggleStar(doc)
    }

    fun rescan(onDone: (ScanResult) -> Unit) {
        repository.scanAsync(onDone)
    }

    fun refresh() {
        repository.refresh()
    }

    fun import(uris: List<Uri>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val imported = repository.import(uris)
            onDone(imported)
        }
    }

    fun clearCache(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.clearCache()
            onDone()
        }
    }

    fun hasStoragePermission(): Boolean = repository.hasStoragePermission()

    /** True once a runtime request has come back denied (no nagging). */
    var permissionDenied: Boolean = false
        private set

    fun onPermissionResult(granted: Boolean) {
        permissionDenied = !granted
        if (granted) rescan { }
    }

    private var initialScanStarted = false

    /** Kicks off the very first device scan (once per activity lifetime). */
    fun maybeInitialScan() {
        if (initialScanStarted) return
        initialScanStarted = true
        if (hasStoragePermission()) rescan { }
    }

    private fun List<Doc>.filtered(text: String, typeFilter: TypeFilter): List<Doc> {
        val needle = text.trim()
        return filter { doc ->
            (typeFilter.type == null || doc.type == typeFilter.type) &&
                (needle.isEmpty() || doc.displayName.contains(needle, ignoreCase = true))
        }
    }

d.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leaflite.viewer.LeafLiteApp
import com.leaflite.viewer.data.Doc
import com.leaflite.viewer.data.DocumentRepository
import com.leaflite.viewer.data.ScanResult
import com.leaflite.viewer.data.SortOption
import com.leaflite.viewer.data.sortedFor
import com.leaflite.viewer.data.TypeFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library tab state: documents + search query + type filter + sort order.
 * Everything is derived from the repository flow, so starring a document in the
 * viewer or in the Starred tab updates the list immediately.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: DocumentRepository = (app as LeafLiteApp).repository
    private val prefs = (app as LeafLiteApp).prefs

    val rawDocuments: StateFlow<List<Doc>> = repository.documents
    val scanning: StateFlow<Boolean> = repository.scanning

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(TypeFilter.ALL)
    private val sort = MutableStateFlow(prefs.sortOption)

    val visible: StateFlow<List<Doc>> =
        combine(rawDocuments, query, filter, sort) { docs, text, typeFilter, sortOption ->
            docs.filtered(text, typeFilter).sortedFor(sortOption)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sectionLabel: StateFlow<Pair<Int, Int>> =
        combine(filter, visible) { typeFilter, docs ->
            typeFilter.sectionLabelRes to docs.size
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TypeFilter.ALL.sectionLabelRes to 0)

    val currentFilter: TypeFilter get() = filter.value
    val currentSort: SortOption get() = sort.value
    val currentQuery: String get() = query.value

    fun setQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: TypeFilter) {
        filter.value = value
    }

    fun setSort(value: SortOption) {
        sort.value = value
        prefs.sortOption = value
    }

    fun toggleStar(doc: Doc) {
        repository.toggleStar(doc)
    }

    fun rescan(onDone: (ScanResult) -> Unit) {
        repository.scanAsync(onDone)
    }

    fun refresh() {
        repository.refresh()
    }

    fun import(uris: List<Uri>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val imported = repository.import(uris)
            onDone(imported)
        }
    }

    fun clearCache(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.clearCache()
            onDone()
        }
    }

    fun hasStoragePermission(): Boolean = repository.hasStoragePermission()

    /** True once a runtime request has come back denied (no nagging). */
    var permissionDenied: Boolean = false
        private set

    fun onPermissionResult(granted: Boolean) {
        permissionDenied = !granted
        if (granted) rescan { }
    }

    private var initialScanStarted = false

    /** Kicks off the very first device scan (once per activity lifetime). */
    fun maybeInitialScan() {
        if (initialScanStarted) return
        initialScanStarted = true
        if (hasStoragePermission()) rescan { }
    }

    private fun List<Doc>.filtered(text: String, typeFilter: TypeFilter): List<Doc> {
        val needle = text.trim()
        return filter { doc ->
            (typeFilter.type == null || doc.type == typeFilter.type) &&
                (needle.isEmpty() || doc.displayName.contains(needle, ignoreCase = true))
        }
    }

}

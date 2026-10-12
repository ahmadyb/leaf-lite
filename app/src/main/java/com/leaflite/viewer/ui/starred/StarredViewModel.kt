package com.leaflite.viewer.ui.starred

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leaflite.viewer.LeafLiteApp
import com.leaflite.viewer.data.Doc
import com.leaflite.viewer.data.DocumentRepository
import com.leaflite.viewer.data.Prefs
import com.leaflite.viewer.data.sortedFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Starred tab: the same rows as the Library, filtered to starred documents. */
class StarredViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: DocumentRepository = (app as LeafLiteApp).repository
    private val prefs: Prefs = (app as LeafLiteApp).prefs

    private val sort = MutableStateFlow(prefs.sortOption)

    val documents: StateFlow<List<Doc>> =
        combine(repository.documents, sort) { docs, option ->
            docs.filter { it.starred }.sortedFor(option)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Re-reads the persisted sort order (it can change in Settings). */
    fun refreshSort() {
        sort.value = prefs.sortOption
    }

    fun toggleStar(doc: Doc) {
        repository.toggleStar(doc)
    }
}

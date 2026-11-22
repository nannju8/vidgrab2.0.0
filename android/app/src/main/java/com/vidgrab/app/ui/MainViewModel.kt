package com.vidgrab.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.data.LibraryItem
import com.vidgrab.app.data.LibraryRepository
import com.vidgrab.app.data.SuggestApi
import com.vidgrab.app.download.DownloadCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val query = MutableStateFlow("")
    val googleSuggestions = MutableStateFlow<List<String>>(emptyList())

    val library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val privateItems = MutableStateFlow<List<LibraryItem>>(emptyList())

    init {
        // Suggestions Google : debounce(300) + collectLatest, échec silencieux hors ligne
        viewModelScope.launch {
            query.debounce(300).collectLatest { q ->
                googleSuggestions.value = if (q.isBlank()) emptyList() else SuggestApi.fetch(q.trim())
            }
        }
        viewModelScope.launch {
            DownloadCenter.libraryChanged.collect { refreshLibrary() }
        }
        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            library.value = LibraryRepository.loadPublic(getApplication())
        }
    }

    fun refreshPrivate() {
        viewModelScope.launch(Dispatchers.IO) {
            privateItems.value = LibraryRepository.loadPrivate(getApplication())
        }
    }

    fun refreshAll() {
        refreshLibrary()
        refreshPrivate()
    }
}

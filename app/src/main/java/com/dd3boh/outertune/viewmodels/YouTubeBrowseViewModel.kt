package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.models.ContentFilter
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.BrowseResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YouTubeBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val browseId = savedStateHandle.get<String>("browseId")!!
    private val params = savedStateHandle.get<String>("params")

    val result = MutableStateFlow<BrowseResult?>(null)

    init {
        viewModelScope.launch {
            YouTube.browse(browseId, params).onSuccess {
                // Without a blocked artist's songs, albums and face (ArtistBlock); a shelf left empty goes.
                val block = ContentFilter.current.blocked
                result.value = if (block.isEmpty) it else it.copy(
                    items = it.items.map { shelf -> shelf.copy(items = block.apply(shelf.items)) }.filter { shelf -> shelf.items.isNotEmpty() }
                )
            }.onFailure {
                reportException(it)
            }
        }
    }
}

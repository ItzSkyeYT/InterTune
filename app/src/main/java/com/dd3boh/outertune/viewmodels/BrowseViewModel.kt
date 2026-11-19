package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.models.ContentFilter
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.ContentLocale
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YTItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val browseId: String? = savedStateHandle.get<String>("browseId")

    val items = MutableStateFlow<List<YTItem>?>(emptyList())
    val title = MutableStateFlow<String?>("")

    init {
        load()
        // Another language for YouTube (the app's, or the YouTube Music rows): the page loads again in it.
        viewModelScope.launch { ContentLocale.changes.collect { load() } }
    }

    private fun load() {
        viewModelScope.launch {
            browseId?.let {
                YouTube.browse(browseId, null).onSuccess { result ->
                    // Store the title
                    title.value = result.title

                    // Flatten the nested structure to get all YTItems
                    val allItems = result.items.flatMap { it.items }
                    // Without a blocked artist's (ArtistBlock).
                    items.value = ContentFilter.current.blocked.apply(allItems)
                }.onFailure {
                    reportException(it)
                }
            }
        }
    }
}

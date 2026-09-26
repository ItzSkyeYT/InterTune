package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.ItemsPage
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.BrowseEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val browseId = savedStateHandle.get<String>("browseId")!!
    private val params = savedStateHandle.get<String>("params")

    val title = MutableStateFlow("")
    val itemsPage = MutableStateFlow<ItemsPage?>(null)

    /** The first request failed. The page showed its loading shimmer until a page came, forever. */
    val failed = MutableStateFlow(false)

    init {
        load()
    }

    /** The first page, again after a failure. */
    fun load() {
        failed.value = false
        viewModelScope.launch {
            YouTube.artistItems(
                BrowseEndpoint(
                    browseId = browseId,
                    params = params
                )
            ).onSuccess { artistItemsPage ->
                title.value = artistItemsPage.title
                itemsPage.value = ItemsPage(
                    items = artistItemsPage.items.distinctBy { it.id },
                    continuation = artistItemsPage.continuation
                )
            }.onFailure {
                reportException(it)
                failed.value = true
            }
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            val oldItemsPage = itemsPage.value ?: return@launch
            val continuation = oldItemsPage.continuation ?: return@launch
            YouTube.artistItemsContinuation(continuation)
                .onSuccess { artistItemsContinuationPage ->
                    itemsPage.update {
                        ItemsPage(
                            items = (oldItemsPage.items + artistItemsContinuationPage.items).distinctBy { it.id },
                            continuation = artistItemsContinuationPage.continuation
                        )
                    }
                }.onFailure {
                    reportException(it)
                }
        }
    }
}

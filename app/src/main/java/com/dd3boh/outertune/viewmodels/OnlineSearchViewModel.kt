package com.dd3boh.outertune.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.ItemsPage
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.SearchSummaryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // Recover the literal rather than !!. androidx.navigation parses the path segment "null" into
    // an actual null, and the route pattern search/{query} guarantees the segment is present, so a
    // null here can only mean the user searched for the word "null". Without this the nullable
    // nav arg just moves upstream #1190's crash from a verification failure to an NPE.
    val query = savedStateHandle.get<String>("query") ?: "null"
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    /**
     * The filters whose first request failed, SUMMARY for the summary. The page showed its loading
     * shimmer until one came back, so a failed request left it up for as long as the page was open.
     */
    val failed = mutableStateMapOf<String, Boolean>()

    init {
        viewModelScope.launch {
            filter.collect { filter -> load(filter) }
        }
    }

    private suspend fun load(filter: YouTube.SearchFilter?) {
        if (filter == null) {
            if (summaryPage == null) {
                failed.remove(SUMMARY)
                YouTube.searchSummary(query)
                    .onSuccess {
                        summaryPage = it
                    }
                    .onFailure {
                        reportException(it)
                        failed[SUMMARY] = true
                    }
            }
        } else {
            if (viewStateMap[filter.value] == null) {
                failed.remove(filter.value)
                YouTube.search(query, filter)
                    .onSuccess { result ->
                        viewStateMap[filter.value] = ItemsPage(result.items.distinctBy { it.id }, result.continuation)
                    }
                    .onFailure {
                        reportException(it)
                        failed[filter.value] = true
                    }
            }
        }
    }

    /** Asks again for what the current filter shows, after it failed. */
    fun retry() {
        val current = filter.value
        viewModelScope.launch { load(current) }
    }

    companion object {
        const val SUMMARY = ""
    }

    fun loadMore() {
        val filter = filter.value?.value
        viewModelScope.launch {
            if (filter == null) return@launch
            val viewState = viewStateMap[filter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val searchResult = YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                viewStateMap[filter] = ItemsPage((viewState.items + searchResult.items).distinctBy { it.id }, searchResult.continuation)
            }
        }
    }
}

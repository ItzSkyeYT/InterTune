package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.models.AiFilter
import com.dd3boh.outertune.models.ContentFilter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.BlockedArtist
import com.dd3boh.outertune.models.ItemsPage
import com.dd3boh.outertune.models.blockedArtists
import com.dd3boh.outertune.ui.screens.search.SearchFilters
import com.dd3boh.outertune.utils.WhyStopped
import com.dd3boh.outertune.utils.YouTubeTrouble
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.SearchSummaryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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

    /** The chip on, null for All: the one the route names, for a search edited on another page of results. */
    val filter = MutableStateFlow(SearchFilters.of(savedStateHandle.get<String>("filter")))
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    /**
     * The blocked artist this search is the name of (ArtistBlock), or null. Then nothing is asked
     * of YouTube and the page says so in one line, with Unblock; an unblock asks at once.
     */
    var blockedNamed by mutableStateOf(namedHere())
        private set

    /** The pages as YouTube sent them, so that what a block hides goes at once, and an unblock brings it back. */
    private var summaryAsSent: SearchSummaryPage? = null
    private val pagesAsSent = HashMap<String, ItemsPage>()

    /**
     * The pages after the content switches and before the AI filter, which with Block everywhere
     * hides AI results from what is shown, never silently: [aiHidden] says how many, and [showAiResults] shows them.
     */
    private var summaryAll: SearchSummaryPage? = null
    private val itemsAll = HashMap<String, ItemsPage>()

    /** How many results the AI filter hides, by filter, SUMMARY for the summary. */
    val aiHidden = mutableStateMapOf<String, Int>()

    /** Whether the listener asked to see what the AI filter hid, for this search. */
    var aiShown by mutableStateOf(false)
        private set

    /**
     * The filters whose first request failed, SUMMARY for the summary. The page showed its loading
     * shimmer until one came back, so a failed request left it up for as long as the page was open.
     */
    val failed = mutableStateMapOf<String, Boolean>()

    /** What each of those failures came to, for the page to say why (WhyStopped). */
    val failedKind = mutableStateMapOf<String, WhyStopped.Kind>()

    init {
        viewModelScope.launch {
            combine(filter, blockedArtists) { filter, _ -> filter }.collect { filter ->
                blockedNamed = namedHere()
                refilter()
                if (blockedNamed == null) load(filter)
            }
        }
        // An artist marked from its menu here, or the setting changed, shows at once.
        viewModelScope.launch {
            AiFilter.changes.map { Triple(it.mode, it.markedAi, it.notAi) }.distinctUntilChanged().drop(1).collect { showAll() }
        }
    }

    private fun namedHere(): BlockedArtist? = if (Unreleased.ARTIST_BLOCK) ContentFilter.current.blocked.named(query) else null

    /**
     * What is shown again from what was sent, after a block or an unblock: through the switches,
     * then the AI filter, as a page that has just arrived.
     */
    private fun refilter() {
        val hidden = ContentFilter.current
        summaryAsSent?.let { summaryAll = hidden.search(it) }
        for ((key, page) in pagesAsSent) itemsAll[key] = page.copy(items = hidden.apply(page.items))
        showAll()
    }

    private suspend fun load(filter: YouTube.SearchFilter?) {
        if (filter == null) {
            if (summaryPage == null) {
                failed.remove(SUMMARY)
                YouTube.searchSummary(query)
                    .onSuccess {
                        YouTubeTrouble.answered(asAccount = YouTube.useLoginForBrowse)
                        summaryAsSent = it
                        summaryAll = ContentFilter.current.search(it)
                        showSummary()
                    }
                    .onFailure {
                        reportException(it)
                        YouTubeTrouble.failed(it)
                        failedKind[SUMMARY] = WhyStopped.kindOf(it)
                        failed[SUMMARY] = true
                    }
            }
        } else {
            if (viewStateMap[filter.value] == null) {
                failed.remove(filter.value)
                YouTube.search(query, filter)
                    .onSuccess { result ->
                        YouTubeTrouble.answered(asAccount = YouTube.useLoginForBrowse)
                        val sent = ItemsPage(result.items.distinctBy { it.id }, result.continuation)
                        pagesAsSent[filter.value] = sent
                        itemsAll[filter.value] = sent.copy(items = ContentFilter.current.apply(sent.items))
                        showItems(filter.value)
                    }
                    .onFailure {
                        reportException(it)
                        YouTubeTrouble.failed(it)
                        failedKind[filter.value] = WhyStopped.kindOf(it)
                        failed[filter.value] = true
                    }
            }
        }
    }

    private fun showSummary() {
        val page = summaryAll ?: return
        val kept = if (aiShown) AiFilter.Kept(page, 0) else AiFilter.current.search(page)
        summaryPage = kept.shown
        aiHidden[SUMMARY] = kept.hidden
    }

    private fun showItems(key: String) {
        val page = itemsAll[key] ?: return
        val kept = if (aiShown) AiFilter.Kept(page.items, 0) else AiFilter.current.search(page.items)
        viewStateMap[key] = ItemsPage(kept.shown, page.continuation)
        aiHidden[key] = kept.hidden
    }

    private fun showAll() {
        showSummary()
        itemsAll.keys.forEach(::showItems)
    }

    /** The results the AI filter hid, shown for the rest of this search. */
    fun showAiResults() {
        aiShown = true
        showAll()
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
            val viewState = itemsAll[filter] ?: return@launch
            val continuation = viewState.continuation
            if (continuation != null) {
                val searchResult = YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                pagesAsSent[filter]?.let { sent ->
                    pagesAsSent[filter] = ItemsPage((sent.items + searchResult.items).distinctBy { it.id }, searchResult.continuation)
                }
                itemsAll[filter] = ItemsPage((viewState.items + ContentFilter.current.apply(searchResult.items)).distinctBy { it.id }, searchResult.continuation)
                showItems(filter)
            }
        }
    }
}

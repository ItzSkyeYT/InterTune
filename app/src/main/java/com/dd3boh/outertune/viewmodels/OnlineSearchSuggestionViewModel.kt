package com.dd3boh.outertune.viewmodels

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.ContentCountryKey
import android.content.Context
import com.dd3boh.outertune.models.ContentFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SearchHistory
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YTItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineSearchSuggestionViewModel @Inject constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    private val _viewState = MutableStateFlow(SearchSuggestionViewState())
    val viewState = _viewState.asStateFlow()

    init {
        // The language and the country YouTube answers in: a change asks again for what is typed,
        // so the suggestions on screen are never left in the old language.
        val contentLocale = context.dataStore.data
            .map { it[ContentLanguageKey].orEmpty() to it[ContentCountryKey].orEmpty() }
            .distinctUntilChanged()
        viewModelScope.launch {
            combine(query, contentLocale) { query, _ -> query }.flatMapLatest { query ->
                if (query.isEmpty()) {
                    database.searchHistory().map { history ->
                        SearchSuggestionViewState(
                            history = history
                        )
                    }
                } else {
                    val result = YouTube.searchSuggestions(query).getOrNull()
                    database.searchHistory(query)
                        .map { it.take(3) }
                        .map { history ->
                            SearchSuggestionViewState(
                                history = history,
                                suggestions = result?.queries?.filter { query ->
                                    history.none { it.query == query }
                                }.orEmpty(),
                                // Nothing of a blocked artist, the artist included (ArtistBlock).
                                items = ContentFilter.current.blocked.apply(result?.recommendedItems.orEmpty().distinctBy { it.id })
                            )
                        }
                }
            }.collect {
                _viewState.value = it
            }
        }
    }
}

data class SearchSuggestionViewState(
    val history: List<SearchHistory> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val items: List<YTItem> = emptyList(),
)

package com.dd3boh.outertune.viewmodels

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.HistorySource
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.history.HistoryDayGroup
import com.dd3boh.outertune.history.HistoryDays
import com.dd3boh.outertune.history.HistoryEntry
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.HistoryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {
    var historySource = MutableStateFlow(HistorySource.LOCAL)

    val historyPage = mutableStateOf<HistoryPage?>(null)

    /**
     * Local history by day, newest first. Today is read on every change rather than once when the
     * screen opened, so a play after midnight lands under Today and yesterday's under Yesterday.
     * Watched only while the screen is: the listen log is written to every minute of playback.
     */
    val days: StateFlow<List<HistoryDayGroup<HistoryEntry>>> = database.historyPlays()
        .map { rows ->
            HistoryDays.group(rows.map { HistoryEntry.of(it) }, LocalDate.now(), { it.start }, { it.at })
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        fetchRemoteHistory()
    }

    fun fetchRemoteHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            YouTube.musicHistory().onSuccess {
                historyPage.value = it
            }.onFailure {
                reportException(it)
            }
        }
    }
}

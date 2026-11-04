/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The field of Settings > Library and content > Blocked artists that adds one: what is typed is
 * looked for among YouTube's artists, a moment after the typing stops.
 */
@HiltViewModel
class BlockedArtistsViewModel @Inject constructor() : ViewModel() {
    val query = MutableStateFlow("")

    private val _results = MutableStateFlow<List<ArtistItem>>(emptyList())
    val results = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()

    /** What the results are for: "No artist found" is said only of a search that came back. */
    private val _answered = MutableStateFlow<String?>(null)
    val answered = _answered.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            // A request a keystroke would be one per letter.
            query.debounce(350).collectLatest { typed ->
                val asked = typed.trim()
                if (asked.isEmpty()) {
                    _results.value = emptyList()
                    _answered.value = null
                    _searching.value = false
                    return@collectLatest
                }
                _searching.value = true
                val found = YouTube.search(asked, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                _results.value = found?.items?.filterIsInstance<ArtistItem>().orEmpty().distinctBy { it.id }
                // A search that failed found nothing to say "not found" about.
                _answered.value = if (found != null) typed else null
                _searching.value = false
            }
        }
    }
}

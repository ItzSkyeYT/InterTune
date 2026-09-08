/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.CacheSpan
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.di.AppModule.PlayerCache
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The songs sitting in the player cache, which is to say the ones that will play with no network.
 *
 * Distinct from downloads, which are deliberate and permanent. These are the songs the app happened
 * to keep because you played them, and they leave again on their own when the cache fills.
 */
@HiltViewModel
class CachedSongsViewModel @Inject constructor(
    private val database: MusicDatabase,
    @PlayerCache private val playerCache: SimpleCache,
) : ViewModel() {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true

            // Only songs cached in full. isCached over a single chunk is what the player asks
            // before playback, and it is the right question there, but it says yes for a song
            // skipped after twenty seconds. Listing those would make this tab lie about the one
            // thing it exists to tell you.
            val complete = playerCache.keys.filter { key ->
                val length = playerCache.getContentMetadata(key).get("exo_len", -1L)
                length > 0 && playerCache.isCached(key, 0, length)
            }

            if (complete.isEmpty()) {
                _songs.value = emptyList()
                _loading.value = false
                return@launch
            }

            // Most recently touched first, which for a cache is the order that means something.
            val recency = complete.associateWith { key ->
                playerCache.getCachedSpans(key).maxOfOrNull(CacheSpan::lastTouchTimestamp) ?: 0L
            }
            val rows = database.songsByIds(complete).first().associateBy { it.id }

            // Anything in the cache with no library row is dropped rather than invented. It happens
            // for songs played straight from search and never saved; recovering them here would
            // mean a network lookup per song for a list nobody asked to be complete.
            _songs.value = complete
                .sortedByDescending { recency[it] ?: 0L }
                .mapNotNull { rows[it] }

            _loading.value = false
        }
    }

    /** Bytes the cache is currently holding, for the header. */
    fun cacheSize(): Long = playerCache.cacheSpace
}

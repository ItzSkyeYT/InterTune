/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.engine.SongRow
import com.dd3boh.outertune.engine.VersionGroups
import com.dd3boh.outertune.engine.VersionLink
import com.dd3boh.outertune.utils.SongVersions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One version group with more than one member in the song table. */
data class Collision(val base: String, val titles: List<String>)

data class CollisionReport(val groups: List<Collision>, val songs: Int, val multiMemberGroups: Int, val legacyVersionEdges: Int)

/**
 * The developer page's numbers: the collision report says which titles the version rule merges,
 * so a whitelist of marker words can be decided from what the library really holds rather than
 * from guesses, and how many related edges still pair a song with one of its own versions.
 */
@HiltViewModel
class EngineDeveloperViewModel @Inject constructor(private val database: MusicDatabase) : ViewModel() {
    val report = MutableStateFlow<CollisionReport?>(null)

    fun loadReport() = viewModelScope.launch(Dispatchers.Default) {
        runCatching {
            val rows = database.engineSongs()
            val songs = rows.associate { it.id to SongRow(it.id, it.title, it.artistId, it.artistName) }
            val links = database.engineVersionLinks().map { VersionLink(it.songId, it.versionId) }
            val groups = VersionGroups(songs.values, links)
            val members = HashMap<String, MutableList<String>>()
            for (s in songs.values) members.getOrPut(groups.groupOf(s.id)) { ArrayList() }.add(s.title)
            val multi = members.values.filter { it.size > 1 }
            val top = multi.sortedByDescending { it.size }.take(40).map { titles -> Collision(SongVersions.baseTitle(titles.first()), titles.distinct().take(6)) }
            val edges = database.engineEdges()
            val titleOf = songs.mapValues { it.value.title }
            val legacy = edges.count { e -> val a = titleOf[e.songId]; val b = titleOf[e.relatedSongId]; a != null && b != null && SongVersions.isVersionOf(b, a) }
            report.value = CollisionReport(top, songs.size, multi.size, legacy)
        }.onFailure { report.value = CollisionReport(emptyList(), 0, 0, 0) }
    }
}

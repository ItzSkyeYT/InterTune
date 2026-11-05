/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Findings of the UI scan of 5 Nov 2026 (experiments/codebase-scan/2026-11-05-ui.md), each kept
 * fixed. Read from the source: none of them shows without a device.
 */
class UiScanFixesTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `a playlist's rows are picked by row, so a song in it twice is two rows`() {
        val page = source("ui/screens/playlist/LocalPlaylistScreen.kt")
        assertFalse("selection.add(song.song.id)" in page)
        assertTrue("selection.add(song.map.id.toString())" in page)
        assertTrue("isSelected = selection.contains(song.map.id.toString())" in page)
        assertTrue("selection.addAll(shownSongs.map { it.map.id.toString() })" in page)
        assertTrue("Remove from playlist takes the rows picked, not every copy", ".filter { it.map.id.toString() in selection }" in page)
        // And leaves selection mode: the bar stayed up over nothing picked.
        val remove = page.substringAfter("onRemoveFromPlaylist = if (!editable) null else {").substringBefore("SnackbarHost(")
        assertTrue("onExitSelectionMode()" in remove)
    }

    @Test
    fun `the player menu's Remove from playlist outlives the menu`() {
        val menu = source("ui/menu/PlayerMenu.kt")
        val remove = menu.substringAfter("private fun removeFromPlaylist(")
        assertTrue("CoroutineScope(syncCoroutine).launch {" in remove)
        assertFalse("the menu's own scope ends a frame after the tap", "scope: CoroutineScope" in remove)
    }

    @Test
    fun `a playlist's page works out its download state again`() {
        val page = source("ui/screens/playlist/LocalPlaylistScreen.kt")
        assertFalse("//    LaunchedEffect(songs) {" in page)
        assertTrue("downloadState = getDownloadState(songs.map { downloads[it.song.id] })" in page)
    }

    @Test
    fun `Shuffle on a YouTube playlist's page needs no Play button`() {
        val page = source("ui/screens/playlist/OnlinePlaylistScreen.kt")
        assertFalse("playlist.playEndpoint!!" in page)
        assertTrue("playlistId = playlist.playEndpoint?.playlistId ?: shuffleEndpoint.playlistId" in page)
    }

    @Test
    fun `no menu adds a whole list to YouTube before the duplicates question`() {
        for (menu in listOf("AlbumMenu.kt", "YouTubeAlbumMenu.kt", "YouTubePlaylistMenu.kt", "PlaylistMenu.kt")) {
            assertFalse(menu, "YouTube.addPlaylistToPlaylist(" in source("ui/menu/$menu"))
        }
    }

    @Test
    fun `the folder search shows the last query's results, and none for an empty field`() {
        val search = source("viewmodels/LibraryViewModels.kt").substringAfter("fun searchInDir(").substringBefore("\n    }\n")
        assertTrue("searchJob?.cancel()" in search)
        assertTrue(Regex("""if \(query\.isBlank\(\)\) \{\s*filteredSongs\.clear\(\)""").containsMatchIn(search))
        assertTrue("searchJob = viewModelScope.launch" in search)
    }
}

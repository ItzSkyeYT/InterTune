/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playlist.navigationRoute: the one rule Library.kt already used for which screen opening a
 * playlist should go to, now shared with LocalSearchScreen so a followed playlist synced with
 * nothing stored yet opens the same way from both places, showing its real songs instead of an
 * empty, falsely-editable local playlist screen.
 */
class PlaylistTest {
    private fun playlist(isLocal: Boolean, isEditable: Boolean, browseId: String?, songCount: Int) = Playlist(
        playlist = PlaylistEntity(id = "P", name = "P", isLocal = isLocal, isEditable = isEditable, browseId = browseId),
        songCount = songCount,
        downloadCount = 0,
        songThumbnails = emptyList(),
    )

    @Test
    fun `a local playlist is always local_playlist`() {
        assertEquals("local_playlist/P", playlist(isLocal = true, isEditable = false, browseId = "b", songCount = 0).navigationRoute)
    }

    @Test
    fun `an editable synced playlist is local_playlist`() {
        assertEquals("local_playlist/P", playlist(isLocal = false, isEditable = true, browseId = "b", songCount = 0).navigationRoute)
    }

    @Test
    fun `a playlist with no browseId is local_playlist, since there is nothing online to fetch`() {
        assertEquals("local_playlist/P", playlist(isLocal = false, isEditable = false, browseId = null, songCount = 0).navigationRoute)
    }

    @Test
    fun `a followed playlist with songs already stored is local_playlist`() {
        assertEquals("local_playlist/P", playlist(isLocal = false, isEditable = false, browseId = "b", songCount = 5).navigationRoute)
    }

    @Test
    fun `a followed playlist synced with nothing stored yet is online_playlist`() {
        assertEquals("online_playlist/b", playlist(isLocal = false, isEditable = false, browseId = "b", songCount = 0).navigationRoute)
    }
}

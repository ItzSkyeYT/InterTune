/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.models.DontPlay
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import kotlinx.coroutines.flow.map

/** The icon of Don't play this song: a struck-out note, set apart from Not this song's ring and Block artist's. */
val DontPlayIcon = Icons.Rounded.MusicOff

/** The songs the listener chose not to play as they stand, followed. */
@Composable
fun rememberDontPlay(): DontPlay {
    val flow = remember { ContentFilter.changes.map { it.dontPlay } }
    val songs by flow.collectAsState(ContentFilter.current.dontPlay)
    return songs
}

/**
 * The song menus' Don't play this song (Unreleased.ARTIST_BLOCK), beside Block artist; for a
 * song set so ([held], this very upload), Allow this song again in its place.
 */
fun LazyGridScope.dontPlayMenuItem(held: Boolean, onClick: () -> Unit) {
    GridMenuItem(
        icon = DontPlayIcon,
        title = if (held) R.string.menu_dont_play_lift else R.string.menu_dont_play_song,
        onClick = onClick,
    )
}

/**
 * One tap on that item (DontPlay): the song kept as a row of Exclusions until lifted, with the
 * song's own row in the database, so that its other uploads are known by its title and artist,
 * and a line that says what it does. For a song set so, lifted again. Written away from
 * the menu, which closes at that moment. A song playing is passed over by the player at once.
 */
fun toggleDontPlay(context: Context, database: MusicDatabase, song: MediaMetadata, held: Boolean) {
    if (held) {
        database.query { liftDontPlay(song.id) }
        Toaster.show(context, R.string.dont_play_lifted, Toast.LENGTH_LONG)
        return
    }
    val row = ExclusionsViewModel.dontPlay(song.id, song.title, System.currentTimeMillis())
    database.transaction {
        insert(song)
        insertExclusion(row)
    }
    Toaster.show(context, R.string.dont_play_done, Toast.LENGTH_LONG)
}

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
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.models.DontPlay
import com.dd3boh.outertune.models.DislikePlan
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.utils.RatingWrites
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.utils.mayPushToYouTube
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import com.zionhuang.innertube.YouTube
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
 * song set so ([held], this very upload), Allow this song again in its place. With [dislike]
 * (DislikePlan.shown: Unreleased.DISLIKE, a song from YouTube Music) it is Dislike, and Remove
 * dislike, with a thumbs down: the same entry for what a menu hides (MenuChoices).
 */
fun LazyGridScope.dontPlayMenuItem(held: Boolean, dislike: Boolean = false, onClick: () -> Unit) {
    if (dislike) {
        GridMenuItem(
            icon = if (held) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
            title = if (held) R.string.menu_dislike_lift else R.string.menu_dislike,
            onClick = onClick,
        )
        return
    }
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
 *
 * As Dislike (Unreleased.DISLIKE) it does what [DislikePlan] says besides: the song's like taken back
 * here, the way the heart does it but sending nothing ([downloadUtil] takes back a download the
 * liked-songs setting made, when its switch says so), and the thumbs down sent to YouTube Music, or
 * taken back. The playing song's media controls come here too ([liked] read by the caller there).
 */
fun toggleDontPlay(
    context: Context,
    database: MusicDatabase,
    song: MediaMetadata,
    held: Boolean,
    downloadUtil: DownloadUtil? = null,
    liked: Boolean = false,
) {
    val plan = DislikePlan.of(Unreleased.DISLIKE, held, song.isLocal, liked, context.mayPushToYouTube())
    // In tap order with the song's other ratings, the heart's included (RatingWrites).
    when (plan.tell) {
        DislikePlan.Tell.DISLIKE -> RatingWrites.send(song.id) { YouTube.dislikeVideo(song.id) }
        DislikePlan.Tell.REMOVE -> RatingWrites.send(song.id) { YouTube.likeVideo(song.id, false) }
        DislikePlan.Tell.NOTHING -> Unit
    }
    if (!plan.keep) {
        database.query { liftDontPlay(song.id) }
        Toaster.show(context, R.string.dont_play_lifted, Toast.LENGTH_LONG)
        return
    }
    val row = ExclusionsViewModel.dontPlay(song.id, song.title, System.currentTimeMillis())
    database.transaction {
        insert(song)
        insertExclusion(row)
        if (plan.unlikeHere) {
            songRow(song.id)?.takeIf { it.liked }?.localToggleLike()?.let { unliked ->
                update(unliked)
                downloadUtil?.autoDownloadOnLike(unliked)
            }
        }
    }
    Toaster.show(context, if (plan.tell == DislikePlan.Tell.DISLIKE) R.string.dislike_done else R.string.dont_play_done, Toast.LENGTH_LONG)
}

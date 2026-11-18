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
import com.dd3boh.outertune.extensions.isUserLoggedIn
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * What goes to YouTube Music is said once it has answered, as it went: the thumbs down taken, or kept
 * here only. A song lifted again before the answer says nothing more, its lift has; and when it is still
 * liked here, the like goes back to YouTube Music, where the thumbs down had just taken it.
 */
fun toggleDontPlay(
    context: Context,
    database: MusicDatabase,
    song: MediaMetadata,
    held: Boolean,
    downloadUtil: DownloadUtil? = null,
    liked: Boolean = false,
) {
    val plan = DislikePlan.of(Unreleased.DISLIKE, held, song.isLocal, liked, context.mayPushToYouTube(), context.isUserLoggedIn())
    // Said once YouTube Music answers, after the menu has gone: from the app, not from the menu's screen.
    val app = context.applicationContext ?: context
    if (!plan.keep) {
        // On the transaction queue, as the row was written: a quick Dislike after it cannot land first.
        database.transaction { liftDontPlay(song.id) }
        if (plan.tell != DislikePlan.Tell.REMOVE) {
            Toaster.show(context, R.string.dont_play_lifted, Toast.LENGTH_LONG)
            return
        }
        // In tap order with the song's other ratings, the heart's included (RatingWrites).
        RatingWrites.send(song.id) {
            val sent = YouTube.likeVideo(song.id, false).isSuccess
            withContext(Dispatchers.Main) {
                Toaster.show(app, if (sent) R.string.dont_play_lifted else R.string.dislike_lift_not_sent, Toast.LENGTH_LONG)
            }
        }
        return
    }
    val row = ExclusionsViewModel.dontPlay(song.id, song.title, System.currentTimeMillis())
    database.transaction {
        insert(song)
        insertExclusion(row)
        if (plan.unlike == DislikePlan.Unlike.NOW) takeLikeBack(song.id, downloadUtil)
    }
    if (plan.tell != DislikePlan.Tell.DISLIKE) {
        Toaster.show(context, R.string.dont_play_done, Toast.LENGTH_LONG)
        return
    }
    // Room runs its transactions one after another: this one says when the row's is over, written or not.
    val written = CompletableDeferred<Unit>()
    database.transaction { written.complete(Unit) }
    RatingWrites.send(song.id) {
        // The row first, so what is read after the answer is this tap's, a fast failure's included.
        // Bounded: a write that never ran must not hold up the song's later ratings.
        withTimeoutOrNull(10_000) { written.await() }
        val sent = YouTube.dislikeVideo(song.id).isSuccess
        var likedHere = false
        val stillHeld = database.transactionNow {
            isDontPlay(song.id).also { kept ->
                // The thumbs down took the like on YouTube Music: it goes here now, and only now.
                if (sent && kept && plan.unlike == DislikePlan.Unlike.AFTER_DISLIKE) takeLikeBack(song.id, downloadUtil)
                likedHere = songRow(song.id)?.liked == true
            }
        }
        if (!stillHeld) {
            // Asked again: signed out or set to read only meanwhile, the like stays on this phone.
            if (sent && likedHere && app.mayPushToYouTube()) RatingWrites.send(song.id) { YouTube.likeVideo(song.id, true) }
            return@send
        }
        withContext(Dispatchers.Main) {
            Toaster.show(app, if (sent) R.string.dislike_done else R.string.dislike_not_sent, Toast.LENGTH_LONG)
        }
    }
}

/** The song's like taken back here and nothing sent, with the download the liked-songs setting made when its switch says so. */
private fun MusicDatabase.takeLikeBack(id: String, downloadUtil: DownloadUtil?) {
    songRow(id)?.takeIf { it.liked }?.localToggleLike()?.let { unliked ->
        update(unliked)
        downloadUtil?.autoDownloadOnLike(unliked)
    }
}

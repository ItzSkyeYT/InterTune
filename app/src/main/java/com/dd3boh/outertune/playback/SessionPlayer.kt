/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.dd3boh.outertune.utils.nextCoverName
import com.dd3boh.outertune.utils.reportException

/**
 * The player as the media session is given it: the player itself, except that it can say the
 * playing song's cover has changed.
 *
 * The system's media player draws the bitmap in the session's metadata, and media3 1.8.0 loads that
 * bitmap in one place, MediaSessionLegacyStub.updateMetadataIfChanged, which does nothing unless
 * the playing item's MediaMetadata, id, uri or duration differs from the last time it ran. The
 * loader answers a new song with what it has at once and has something better a moment or a
 * connection later (see SessionBitmapLoader.cover): the small cover first and the large one after
 * it, or a plain stand-in when no cover could be had and the cover once it can. Nothing about the
 * song has changed by then, so the better picture would wait for the next song.
 *
 * What has changed is which picture is the cover, and MediaMetadata has a field for that. From
 * then on this player names the cover anew, by the large address or by that address with a mark
 * when that is its name already (nextCoverName), and tells its listeners the metadata changed, as
 * any player does. The session asks the loader again, which has the better cover in hand and
 * answers there and then, and passes the change on to its controllers, one of which builds the
 * notification.
 *
 * Replacing the item in the player (Player.replaceMediaItem) would also make media3 ask again.
 * It is not done: it reaches into the playlist of a player that is playing, and everything that
 * follows the queue would hear a playlist change for the sake of a picture.
 */
class SessionPlayer(player: Player) : ForwardingPlayer(player) {
    private val listeners = LinkedHashSet<Player.Listener>()

    /** The cover address the playing song is stored with, and the name its cover goes by now. */
    private var renamed: Pair<Uri, Uri>? = null

    /** Covers named anew so far, so that each name is a new one. */
    private var named = 0

    init {
        // The new name stands for the cover only while that song plays. The next song is asked
        // for by the address it is stored with, even when it is this song again: that address is
        // what lets the loader answer with the small cover first.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                renamed = null
            }
        })
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        val told = super.getMediaMetadata()
        val (stored, name) = renamed ?: return told
        return if (told.artworkUri == stored) told.buildUpon().setArtworkUri(name).build() else told
    }

    /**
     * A better cover is in hand for the song stored with [stored], whose large address is
     * [sharp]: the large one, or a cover in place of the stand-in. Called on the player's thread.
     * Nobody is told when another song plays by now: its own cover has been asked for since.
     */
    fun coverArrived(stored: Uri, sharp: Uri) {
        if (super.getMediaMetadata().artworkUri != stored) return
        val current = renamed?.second ?: stored
        renamed = stored to Uri.parse(nextCoverName(current.toString(), sharp.toString(), ++named))
        val now = mediaMetadata
        // A picture is never worth the music: whatever a listener makes of this stays in here.
        runCatching { listeners.toList().forEach { it.onMediaMetadataChanged(now) } }
            .onFailure { reportException(it) }
    }

    /**
     * How much of the song is loaded, in percent, as media3 asks it of the session's player every
     * time it tells the system what is playing.
     *
     * media3 works it out from the buffered position and the length with a checked cast, and a
     * phone (an HONOR on Android 16, 0.11.1) had a player whose buffered position was far outside
     * the song: the cast failed ("Out of range: -4249598940685") on the main thread and the app
     * closed, from a figure only the notification's progress bar uses (issue #21). Worked out here
     * the same way, held between 0 and 100.
     */
    override fun getBufferedPercentage(): Int = bufferedPercent(bufferedPosition, duration)

    companion object {
        /** [position] of [duration] in whole percent, 0 to 100 whatever the two say. */
        fun bufferedPercent(position: Long, duration: Long): Int = when {
            position == C.TIME_UNSET || duration == C.TIME_UNSET -> 0
            duration <= 0L -> 100
            else -> (position.coerceIn(0L, duration) * 100 / duration).toInt()
        }
    }
}

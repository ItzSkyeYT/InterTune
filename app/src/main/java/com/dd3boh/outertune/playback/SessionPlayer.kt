/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.dd3boh.outertune.utils.reportException

/**
 * The player as the media session is given it: the player itself, except that it can say the
 * playing song's cover has changed.
 *
 * The system's media player draws the bitmap in the session's metadata, and media3 1.8.0 loads that
 * bitmap in one place, MediaSessionLegacyStub.updateMetadataIfChanged, which does nothing unless
 * the playing item's MediaMetadata, id, uri or duration differs from the last time it ran. The
 * loader answers a new song with the small cover that is on the phone and has the large one a
 * moment later (see CoilBitmapLoader.cover). Nothing about the song has changed by then, so the
 * large one would wait for the next song.
 *
 * What has changed is which picture is the cover, and MediaMetadata has a field for that. From
 * then on this player names the cover by the large address and tells its listeners the metadata
 * changed, as any player does. The session asks the loader again, which has the large cover in
 * hand and answers there and then, and passes the change on to its controllers, one of which
 * builds the notification.
 *
 * Replacing the item in the player (Player.replaceMediaItem) would also make media3 ask again.
 * It is not done: it reaches into the playlist of a player that is playing, and everything that
 * follows the queue would hear a playlist change for the sake of a picture.
 */
class SessionPlayer(player: Player) : ForwardingPlayer(player) {
    private val listeners = LinkedHashSet<Player.Listener>()

    /** The cover address the playing song is stored with, and the large one that has taken its place. */
    private var sharper: Pair<Uri, Uri>? = null

    init {
        // The large address stands for the cover only while that song plays. The next song is asked
        // for by the address it is stored with, even when it is this song again: that address is
        // what lets the loader answer with the small cover first.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                sharper = null
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
        val (stored, sharp) = sharper ?: return told
        return if (told.artworkUri == stored) told.buildUpon().setArtworkUri(sharp).build() else told
    }

    /**
     * The large cover at [sharp] is in hand for the song stored with [stored]. Called on the
     * player's thread. Nobody is told when another song plays by now: its own cover has been asked
     * for since.
     */
    fun coverArrived(stored: Uri, sharp: Uri) {
        if (super.getMediaMetadata().artworkUri != stored) return
        sharper = stored to sharp
        val now = mediaMetadata
        // A picture is never worth the music: whatever a listener makes of this stays in here.
        runCatching { listeners.toList().forEach { it.onMediaMetadataChanged(now) } }
            .onFailure { reportException(it) }
    }
}

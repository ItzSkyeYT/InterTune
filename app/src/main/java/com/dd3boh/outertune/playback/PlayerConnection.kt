/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.getCurrentQueueIndex
import com.dd3boh.outertune.extensions.getQueueWindows
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.lyrics.LyricsLookup
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.utils.SemanticLyrics

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    binder: MediaControllerViewModel,
    val database: MusicDatabase,
) : Player.Listener {
    val TAG = PlayerConnection::class.simpleName.toString()

    val service = binder.getService()!!
    val player = service.player
    val scope = binder.viewModelScope

    val playbackState = MutableStateFlow(player.playbackState)
    private val playWhenReady = MutableStateFlow(player.playWhenReady)
    /**
     * Idle counts as not playing, whatever playWhenReady still says.
     *
     * A player error leaves the player idle with playWhenReady untouched, so this used to report
     * playing over silence: the mini player, the full player and the queue all drew a pause icon,
     * and the tap that would in fact have recovered it, since togglePlayPause prepares an idle
     * player, looked like the tap that would stop the music.
     */
    val isPlaying = combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
        playWhenReady && playbackState != STATE_ENDED && playbackState != STATE_IDLE
    }.stateIn(
        scope, SharingStarted.Lazily,
        player.playWhenReady && player.playbackState != STATE_ENDED && player.playbackState != STATE_IDLE
    )
    val waitingForNetworkConnection: StateFlow<Boolean> = service.waitingForNetworkConnection.asStateFlow()
    val mediaMetadata = MutableStateFlow(player.currentMetadata)
    val currentSong = mediaMetadata.flatMapLatest {
        database.song(it?.id)
    }
    val currentLyrics: Flow<SemanticLyrics> = LyricsLookup.bySong(mediaMetadata) { mediaMetadata ->
        service.lyricsHelper.getLyrics(mediaMetadata) ?: uninitializedLyric
    }

    private val currentMediaItemIndex = MutableStateFlow(-1)

    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())

    var queuePlaylistId = MutableStateFlow<String?>(null)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)

    /**
     * Where the restored song stopped, until it is loaded into the player; null once it is.
     *
     * After a cold start the player is empty until something is played: the saved queue sits in
     * the queue board and only goes into the player on play. The song shown meanwhile comes from
     * the saved queue (see init), but its position and shuffle came from the empty player, so a
     * song that resumes at 1:12 in a shuffled queue showed 0:00 with shuffle off. That read as the
     * position being lost, the very thing 0.11 fixed.
     */
    val restoredPosition = MutableStateFlow<Long?>(null)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)

    init {
        player.addListener(this)

        playbackState.value = player.playbackState
        playWhenReady.value = player.playWhenReady
        queuePlaylistId.value = service.queuePlaylistId
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        currentMediaItemIndex.value = player.currentMediaItemIndex
        shuffleModeEnabled.value = player.shuffleModeEnabled
        repeatMode.value = player.repeatMode

        scope.launch {
            val resumption = if (player.currentMetadata == null) database.getResumptionQueue() else null
            mediaMetadata.value = player.currentMetadata ?: resumption?.getCurrentSong()
            if (resumption != null && player.currentMediaItem == null) {
                restoredPosition.value = resumption.lastSongPos.takeIf { it > 0 }
                shuffleModeEnabled.value = resumption.shuffled
            }
        }
    }

    /**
     * Play or pause, for anything that shows the current song: the two play buttons, and every
     * row, card and tile that stands for it.
     *
     * After a cold start the player is empty and the song shown comes from the saved queue (see
     * init). A toggle on an empty player does nothing, so the row of that song took the tap and
     * stayed silent, while the play button beside it worked: it loaded the saved queue first.
     * The loading is here now, for all of them. setCurrQueue never prepares; the toggle does.
     */
    fun togglePlayPause() {
        loadSavedQueue()
        player.togglePlayPause()
    }

    /**
     * The saved queue into the player, when the player is empty: what anything that acts on the
     * player must do first after a cold start. It never prepares and never plays.
     */
    fun loadSavedQueue() {
        if (player.currentMediaItem == null) service.queueBoard.setCurrQueue()
    }

    fun playQueue(
        queue: Queue,
        shouldResume: Boolean = false,
        replace: Boolean = true,
        isRadio: Boolean = false,
        title: String? = null,
        /** Where the listener started this from. UNKNOWN is honest; a wrong origin is not. */
        origin: PlayOrigin = PlayOrigin.UNKNOWN,
        /** The card's position when [origin] is a row, else -1. */
        originSlot: Int = -1,
        /** When a card was tapped to start this, so the listen can meet its impression. */
        tappedAt: Long? = null,
    ) {
        service.playQueue(
            queue = queue,
            shouldResume = shouldResume,
            replace = replace,
            title = title,
            isRadio = isRadio,
            origin = origin,
            originSlot = originSlot,
            tappedAt = tappedAt,
        )
    }

    /** The listener is choosing the next song themselves; the next transition is not an autoplay. */
    fun markUserChoice() { service.userChoicePending = true }

    /**
     * Add item to queue, right after current playing item
     */
    fun enqueueNext(item: MediaItem) = enqueueNext(listOf(item))

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        service.enqueueNext(items)
    }

    /**
     * Add item to end of current queue
     */
    fun enqueueEnd(item: MediaItem) = enqueueEnd(listOf(item))

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        service.enqueueEnd(items)
    }

    /** The song on screen, which after a cold start the player does not hold yet: see MusicService.toggleLibrary. */
    fun toggleLike() {
        service.toggleLike(mediaMetadata.value?.id)
    }

    fun toggleLibrary() {
        service.toggleLibrary(mediaMetadata.value?.id)
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        error.value = player.playerError
    }

    override fun onPlayWhenReadyChanged(newPlayWhenReady: Boolean, reason: Int) {
        playWhenReady.value = newPlayWhenReady
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem != null) {
            restoredPosition.value = null
            // See onTimelineChanged.
            shuffleModeEnabled.value = player.shuffleModeEnabled
        }
        mediaMetadata.value = mediaItem?.metadata
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (!timeline.isEmpty) {
            restoredPosition.value = null
            // The restored queue's shuffle stood in for the player's while the player was empty
            // (see init), and goes with the position. Playing something else instead of the
            // restored queue leaves the player's own flag as it was, so no change event came to
            // replace the stand-in: shuffle showed on over a queue playing in order, and the
            // first tap turned it on rather than off. Resuming the restored queue does not
            // flicker, because setCurrQueue sets the flag in the same call that loads the songs.
            shuffleModeEnabled.value = player.shuffleModeEnabled
        }
        queueWindows.value = player.getQueueWindows()
        queuePlaylistId.value = service.queuePlaylistId
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    /**
     * Shuffles the queue
     */
    fun triggerShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            reportException(playbackError)
        }
        error.value = playbackError
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    || !window.isLive()
                    || player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive() && window.isDynamic
                    || player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        player.removeListener(this)
    }

    fun softKillPlayer() {
        Log.i(TAG, "Stopping player and uninitializing queue")
        // Paused first, so the queue keeps the point its song had reached: the service saves it
        // on a pause while the song is still loaded. Cleared while playing, the player reports
        // the stop only once the song has gone, too late to read where it was.
        player.pause()
        player.clearMediaItems()
        // Called straight from the player sheet's swipe-to-dismiss gesture, on the UI thread: the
        // queue save must not block it, so it runs on the service's own scope instead of here.
        service.deInitQueue(waitForSave = false)
    }
}

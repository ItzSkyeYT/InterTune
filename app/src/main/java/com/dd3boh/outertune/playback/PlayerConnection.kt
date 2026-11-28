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
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.PlayingQueueKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.getCurrentQueueIndex
import com.dd3boh.outertune.extensions.getQueueWindows
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.lyrics.LyricsLookup
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.extensions.nextRepeatMode
import com.dd3boh.outertune.together.RoomListen
import com.dd3boh.outertune.together.RoomPlayNext
import com.dd3boh.outertune.together.RoomTap
import com.dd3boh.outertune.together.listensOnly
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    /**
     * False once the service this connection was made for has stopped. The service stops by itself
     * when a queue ends with the app in the background, and the app's screen keeps this connection
     * until the next one arrives, a moment after it comes back. What is asked of it in between plays
     * nothing: a song tapped on the widget then opened the app on silence (Pixel 5, 6 Nov 2026).
     */
    val isAlive: Boolean get() = !service.isDestroyed

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

    /**
     * The row of the song the saved queue came back to, read with it (init), until play loads it. The
     * song on screen was shown from that read, and the database was asked for its row only after it:
     * on a phone busy starting, the heart stood empty meanwhile over a liked song (his report of
     * 27 Nov 2026). See RestoredRow.
     */
    private val restoredRow = RestoredRow<Song> { it.id }

    /** The song on screen's row: led by the one read with the saved queue while that is the song, then the database's own. */
    val currentSong = mediaMetadata.flatMapLatest {
        restoredRow.lead(it?.id, database.song(it?.id))
    }
    /** The playing song's lyrics, with the id of the song they are of: the last song's stay up while the next one's are looked up. */
    val currentLyrics: Flow<Pair<String, SemanticLyrics>> = LyricsLookup.bySong(mediaMetadata) { mediaMetadata ->
        mediaMetadata.id to (service.lyricsHelper.getLyrics(mediaMetadata) ?: uninitializedLyric)
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

    /**
     * This phone only listens in a listening room (listensOnly, his word of 26 Nov 2026, Spotify's Jam): shuffle, previous,
     * next, repeat and the seek bar are shown disabled where they are, and the play button is a mute ([roomMuteButton]).
     */
    val roomListens = MutableStateFlow(false)

    /** The play button is a mute: this phone listens, and is not paused here by the phone itself (a call, headphones out). */
    val roomMuteButton = MutableStateFlow(false)

    /** This phone muted in the room: its player plays on in step, silent (MusicService.roomMuted). */
    val roomMuted: StateFlow<Boolean> get() = service.roomMuted

    /**
     * A song or a list started on a phone that only listens in a room: asked first, "Leave the room to play this?" (the host
     * who gave control away: take it back to play this). Null while nothing is asked. [play] leaves (or takes control) and
     * plays it.
     */
    data class LeaveToPlay(val host: Boolean, val play: () -> Unit)

    val leaveToPlay = MutableStateFlow<LeaveToPlay?>(null)

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

        // In a listening room the transport hangs on who controls the music (listensOnly), which no event of the player's says.
        scope.launch {
            service.together.flatMapLatest { room ->
                room?.status?.map { Triple(listensOnly(it), listensOnly(it) && !it.pausedHere, it.roomShuffle) } ?: flowOf(Triple(false, false, null))
            }
                .distinctUntilChanged()
                .collect { (listens, muteButton, _) ->
                    roomListens.value = listens
                    roomMuteButton.value = muteButton
                    // The room's shuffle on a guest's buttons (RoomListen.shownShuffle).
                    shuffleModeEnabled.value = shownShuffle()
                    updateCanSkipPreviousAndNext()
                }
        }

        scope.launch {
            // Only what Play can load: with Persistent queue off the board holds no saved queue,
            // and the rows are the ones saved before the switch went off (SavedQueueShown.song).
            val restored = if (SavedQueueShown.song(player.currentMetadata != null, service.dataStore.get(PersistentQueueKey, true))) {
                database.getResumption(service.dataStore[PlayingQueueKey])
            } else null
            val resumption = restored?.queue
            // Before the song is shown, which asks for its row: see restoredRow.
            if (player.currentMetadata == null) restoredRow.set(restored?.current)
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
        // A guest who listens in a room: the button is a mute, its player in step with the room either way (RoomListen).
        val room = service.together.value
        if (room != null && roomMuteButton.value) {
            room.toggleMute()
            return
        }
        // A saved queue that had played to its end starts again from its first song, as a queue that ends while the
        // app runs does (playSafe): loaded at its end, the first tap played its last moment and stopped.
        if (player.currentMediaItem == null) service.queueBoard.startOverIfEnded()
        loadQueueIfEmpty().togglePlayPause()
    }

    /**
     * The saved queue into the player, when the player is empty: what anything that acts on the
     * player must do first after a cold start. It never prepares and never plays.
     */
    fun loadSavedQueue() {
        loadQueueIfEmpty()
    }

    /**
     * Songs were taken out of the saved queue before play, the player holding nothing yet (the
     * queue sheet after a restart): the song on screen and where it starts are the queue's again.
     * The song the queue was on may be one of those taken out, and the one that followed it is what
     * plays now, from its top (MultiQueueObject.removeAtPlayIndex); a queue with nothing left shows
     * no song. The sheet and the artwork strip read the queue board again too
     * (MusicService.unloadedQueueChanged). Once the player holds the queue its own events say all
     * this, and nothing is done.
     */
    fun storedQueueEdited() {
        if (player.currentMediaItem != null) return
        val queue = service.queueBoard.getCurrentQueue()
        val song = queue?.takeIf { it.getSize() > 0 }?.getCurrentSong()
        mediaMetadata.value = song
        restoredPosition.value = queue?.lastSongPos?.takeIf { song != null && it > 0 }
        service.unloadedQueueChanged.value += 1
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
        /**
         * The card was in Quick picks' row built under the Discover chip: its queue holds only songs
         * never heard, as a Discover something new card's does by its origin (NeverHeardQueue).
         */
        discoverRow: Boolean = false,
        /** Start radio on a song: in a listening room a whole start, not a song tapped (RoomTap). */
        radioAsked: Boolean = false,
    ) {
        val start = {
            service.playQueue(
                queue = queue,
                shouldResume = shouldResume,
                replace = replace,
                title = title,
                isRadio = isRadio,
                origin = origin,
                originSlot = originSlot,
                tappedAt = tappedAt,
                discoverRow = discoverRow,
                radioAsked = radioAsked,
            )
        }
        // On a phone that follows a room (his word of 26 Nov 2026, Spotify's Jam): a guest the host lets control the music
        // plays it for everyone; any other is asked whether to leave the room to play it, as in a Jam.
        // Not while this phone casts on its own (GuestCast): its cast plays its own music, as before (review of 27 Nov 2026).
        val room = service.together.value
        if (room != null && room.following && !room.status.value.ownWay) {
            if (room.controls) {
                scope.launch {
                    val songs = roomSongsOf(queue, radioAsked)
                    if (songs.isEmpty()) Toaster.show(service, service.getString(R.string.together_suggested_files))
                    else room.playForRoom(songs)
                }
                return
            }
            leaveToPlay.value = LeaveToPlay(host = room.owner) {
                if (room.owner) room.takeBack() else room.leave()
                start()
            }
            return
        }
        start()
    }

    /** What a start plays, for the room: the song tapped, or the list from its first song, files on this phone left out. */
    private suspend fun roomSongsOf(queue: Queue, radioAsked: Boolean): List<MediaMetadata> {
        val songs = RoomTap.songOf(queue, radioAsked)?.let { listOf(it) } ?: runCatching { queue.getInitialStatus() }.getOrNull()
            ?.let { status -> status.items.drop(status.mediaItemIndex.coerceAtLeast(0)) }
            .orEmpty()
        return RoomPlayNext.plan(songs).toRoom
    }

    /**
     * The listener is choosing the next song themselves; the next transition is not an autoplay.
     * [songId], the song chosen, is asked of YouTube even if it was set aside as gone (AskedFor).
     */
    fun markUserChoice(songId: String? = null) {
        service.userChoicePending = true
        songId?.let(service::askedFor)
    }

    /** Retry on the player's error: the song is asked of YouTube again, even if it was set aside as gone (AskedFor). */
    fun askAgain() {
        player.currentMediaItem?.mediaId?.let(service::askedFor)
    }

    /**
     * Add item to queue, right after current playing item
     */
    fun enqueueNext(item: MediaItem) = enqueueNext(listOf(item))

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        // A guest following a room has one Play next: it goes to the room (the person in control puts it next),
        // as the queue here is only a copy of theirs.
        if (toRoom(items, atEnd = false)) return
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
        // The same for Add to queue: the end of the room's queue, not of this phone's copy.
        if (toRoom(items, atEnd = true)) return
        service.enqueueEnd(items)
    }

    /**
     * The room this phone follows as a guest, whose queue here is only a copy of the room's: its Play next and Add to
     * queue go to the room ([toRoom]). Null for the person in control, with no room, and while this phone casts on its
     * own (GuestCast): its Play next and Add to queue are its own then.
     */
    private fun followedRoom() = service.together.value?.takeIf { it.following && !it.status.value.ownWay }

    /**
     * Add to queue goes to the end of the room's queue, with no choice of this phone's queues: it follows a room as a
     * guest ([followedRoom]). Every menu's Add to queue asks this as its choice opens (AddToQueueDialog).
     */
    val addsToRoom: Boolean get() = followedRoom() != null

    /**
     * For a guest following a room: sends [items] to the room (next, or at its end) and says so, with what was left
     * out and why. True when it was done here; false for the person in control and a guest on its own way.
     */
    private fun toRoom(items: List<MediaItem>, atEnd: Boolean): Boolean {
        val room = followedRoom() ?: return false
        // Nobody plays for the room (the host away): nothing sent goes anywhere, and the phone's own queue is the room's copy.
        if (room.status.value.nobodyPlays) {
            Toaster.show(service, service.getString(R.string.together_nobody_plays_short))
            return true
        }
        val plan = com.dd3boh.outertune.together.RoomPlayNext.plan(items.mapNotNull { it.metadata })
        val lines = mutableListOf<String>()
        if (plan.toRoom.isNotEmpty()) {
            room.suggestAll(plan.toRoom, atEnd)
            val n = plan.toRoom.size
            lines += when {
                plan.dropped > 0 -> service.resources.getQuantityString(R.plurals.together_suggested_cut, n, n)
                n == 1 -> service.getString(if (atEnd) R.string.together_suggested_end else R.string.together_suggested)
                else -> service.resources.getQuantityString(if (atEnd) R.plurals.together_suggested_many_end else R.plurals.together_suggested_many, n, n)
            }
        }
        // Not added to the guest's own queue either: the room's queue would overwrite it.
        if (plan.localSkipped > 0) lines += service.getString(R.string.together_suggested_files)
        if (lines.isEmpty()) return false
        com.dd3boh.outertune.utils.Toaster.show(service, lines.joinToString("\n"))
        return true
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
            restoredRow.clear()
            // See onTimelineChanged.
            shuffleModeEnabled.value = shownShuffle()
        }
        mediaMetadata.value = mediaItem?.metadata
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (!timeline.isEmpty) {
            restoredPosition.value = null
            restoredRow.clear()
            // The restored queue's shuffle stood in for the player's while the player was empty
            // (see init), and goes with the position. Playing something else instead of the
            // restored queue leaves the player's own flag as it was, so no change event came to
            // replace the stand-in: shuffle showed on over a queue playing in order, and the
            // first tap turned it on rather than off. Resuming the restored queue does not
            // flicker, because setCurrQueue sets the flag in the same call that loads the songs.
            shuffleModeEnabled.value = shownShuffle()
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
        // On a phone that follows a room, the room's shuffle: asked of the one in control, or who decides said (MusicService).
        if (service.roomShuffleAsked()) return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        updateCanSkipPreviousAndNext()
    }

    /**
     * The shuffle the buttons show: the room's on a phone that follows one, whose copy of the room's queue is in the room's
     * order with its own player's shuffle off; the player's own otherwise (RoomListen.shownShuffle, review of 27 Nov 2026).
     */
    private fun shownShuffle(): Boolean {
        val room = service.together.value?.takeIf { it.following && !it.status.value.ownWay }
        return RoomListen.shownShuffle(follows = room != null, roomShuffle = room?.status?.value?.roomShuffle, playerShuffle = player.shuffleModeEnabled)
    }

    /** Repeat's next mode, off, all, one: for the room when this phone, a guest, controls the music with everyone. */
    fun toggleRepeat() {
        // On a phone that follows a room, the room's repeat, as shuffle (MusicService.roomRepeatAsked).
        if (service.roomRepeatAsked()) return
        player.repeatMode = nextRepeatMode(player.repeatMode)
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = shownShuffle()
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
            // A guest who listens in a room: shown disabled where they are (RoomListen).
            if (roomListens.value) {
                canSkipPrevious.value = false
                canSkipNext.value = false
            }
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    /** Next, as the app's Next buttons press it, on [player], the saved queue loaded first. */
    fun skipToNext(player: Player = loadQueueIfEmpty()) {
        player.seekToNext()
    }

    fun dispose() {
        player.removeListener(this)
    }

    /**
     * Loads the saved queue into the player when nothing is loaded yet, as after a cold start, and
     * returns the player to act on in the same tap.
     *
     * That is the service's own player rather than [player]. With casting built in, [player] is the
     * routed player, which only hears of what the queue board loaded on the main looper's next turn.
     * Asked in the same tap it still held an empty list, so play did nothing, previous and a swipe
     * on the artwork were ignored, and it took a second tap. With casting off they are one player.
     */
    fun loadQueueIfEmpty(): Player {
        if (player.currentMediaItem == null) service.queueBoard.setCurrQueue()
        // What the tap does next on it is the listener's, by hand, in a listening room too (MusicService.byHand).
        service.byHand()
        return service.activePlayer
    }

    fun softKillPlayer() {
        Log.i(TAG, "Stopping player and uninitializing queue")
        // Paused first, so the queue keeps the point its song had reached: the service saves it
        // on a pause while the song is still loaded. Cleared while playing, the player reports
        // the stop only once the song has gone, too late to read where it was. While casting the
        // pause goes to the receiver, and the service, which listens to the receiver then, saves
        // the receiver's point the same way.
        player.pause()
        // Dismissing the player ends a cast too; the receiver would otherwise sit there with the
        // phone still awake for it, and the cast button with it gone. After the pause, because
        // the hand back loads the phone's player already paused and reports no pause to save.
        service.castSession?.stop(play = false)
        player.clearMediaItems()
        // Called straight from the player sheet's swipe-to-dismiss gesture, on the UI thread: the
        // queue save must not block it, so it runs on the service's own scope instead of here.
        service.deInitQueue(waitForSave = false)
    }
}

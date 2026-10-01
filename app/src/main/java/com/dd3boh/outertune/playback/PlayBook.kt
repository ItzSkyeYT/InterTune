/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlaybackException
import com.dd3boh.outertune.constants.EndReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * What the book knows about one play: whether sound ever came out of it, whether it stopped on a
 * playback error, and what the transition that moved on from it said. All of it lives on the play
 * itself, so nothing one play is told can be read by another play of the same song.
 *
 * The transition's reason used to be kept by song id, taken when the song's stats arrived, and
 * left in place when the stats said ended, for the radio's anchor. A song that reached the end of
 * its queue and was then left by a seek or a new queue left a SKIPPED or a REPLACED behind that
 * way, and the next play of that song to close with no transition of its own (the service released
 * while it sat paused) took it: a stop written as a skip, which the engine counted against the
 * song and which Rest songs I skip could rest it for.
 */
open class PlayEnd {
    /**
     * Sound has come out of this play, and the service opened its listen then. A play the player
     * only loaded (the queue restored at launch, a song moved past before it started) never
     * sounded.
     *
     * With listen history paused, opening sets this and writes no row, so a play can be opened
     * and have no row. Stats with play time then take the oldest play of the song, which writes
     * nothing either, and stats with none still leave this play alone (see [PlayBook.takeUnplayed]).
     */
    @Volatile var opened = false
        internal set

    /** Stopped on a playback error and not played since: however it is left now, it ended in that error. */
    @Volatile var failed = false
        private set

    /** What the transition that moved on from this play said, as an [EndReason] code; null until one has. */
    @Volatile var transition: Int? = null
        private set

    internal fun fail() { failed = true }
    internal fun recover() { failed = false }
    internal fun leave(endReason: Int) { transition = endReason }
}

/**
 * The plays the listen log is following, by song, and the rules for how each one ended, kept apart
 * from the service so they can be tested without a player. The service passes on what the player
 * says, as it says it, and asks how a play ended once its stats arrive.
 *
 * A song can be playing twice over as far as this is concerned: on repeat one the next play starts
 * before the stats of the one that ended arrive, so the stats take the oldest play of the song and
 * everything about the current play uses the newest.
 */
class PlayBook<P : PlayEnd>(private val remembered: Int = REMEMBERED_SONGS) {
    private val plays = ConcurrentHashMap<String, ConcurrentLinkedDeque<P>>()

    /** The play the player holds now, and its song: what the next transition moves on from. */
    @Volatile private var playing: P? = null
    @Volatile private var playingId: String? = null

    /**
     * How the transition out of each song's latest play described it, for the radio's anchor, kept
     * for the [remembered] songs left most recently. Not read by any listen: a reason a play takes
     * for its row is only ever its own. The anchor looks a few songs back from where playback is, so
     * a song left long ago says nothing it needs, and kept by song with no bound this grew by every
     * song ever left for as long as the service ran.
     */
    private val lastLeft = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > remembered
    }

    /** The newest play of [id]: the one in progress, when [id] is the current item. */
    fun current(id: String): P? = plays[id]?.peekLast()

    /** The oldest play of [id] not yet closed, taken out of the book: the one whose stats have just arrived. */
    fun takeOldest(id: String): P? {
        var oldest: P? = null
        plays.computeIfPresent(id) { _, queue -> oldest = queue.pollFirst(); queue.takeIf { it.isNotEmpty() } }
        return oldest
    }

    /**
     * What stats with no play time for [id] close: the oldest play of [id] that never sounded and
     * is not the one in progress, taken out of the book. Null when there is none, and then nothing
     * is taken.
     *
     * Such stats come from a play the player loaded and never started, and also from a session
     * Media3 opened for an item it only loaded ahead. PlaybackStatsListener starts a session for
     * any period that starts loading (DefaultPlaybackSessionManager.updateSessions), so near the end
     * of a song the next one has a session of its own, and when that item goes without playing its
     * session finishes with no play time. That session is no play in the book. When the listener
     * taps that next track in the album screen, which builds a new queue, the new queue's play of
     * the song begins first, and taking the oldest play of the song took that one: it never opened
     * a row, and its own stats then found no play and wrote it with no origin and no card, as a stop
     * however it was left. A play that sounded is closed by its own stats, and the one in progress
     * has not been left yet, so this never takes either.
     */
    fun takeUnplayed(id: String): P? {
        var taken: P? = null
        plays.computeIfPresent(id) { _, queue ->
            taken = queue.firstOrNull { !it.opened && it !== playing }?.also { queue.removeFirstOccurrence(it) }
            queue.takeIf { it.isNotEmpty() }
        }
        return taken
    }

    /**
     * A media item transition, with the player's reason for it: the play in progress was left that
     * way, and [play] of [id] begins, or nothing does, when the player was emptied.
     *
     * The reason goes on the play that was in progress even when its stats have already arrived and
     * it is out of the book: the transition and the stats race, and [close] waits for it.
     */
    fun transition(reason: Int, id: String?, play: P?) {
        val left = endReasonOf(reason)
        playing?.leave(left)
        playingId?.let { noteLeft(it, left) }
        if (id == null || play == null) {
            playing = null
            playingId = null
            return
        }
        // In progress before it is in the book, so that stats read on another thread never find it
        // there without seeing that it is the play in progress (see [takeUnplayed]).
        playing = play
        playingId = id
        plays.compute(id) { _, queue -> (queue ?: ConcurrentLinkedDeque()).apply { addLast(play) } }
    }

    /** Whether the latest play of [id] that was moved on from was let finish: a natural end, or a repeat. */
    fun leftAtItsEnd(id: String): Boolean = synchronized(lastLeft) { lastLeft[id] } == EndReason.ENDED

    /** [id] was left as [endReason] says, and is now the song left most recently. */
    private fun noteLeft(id: String, endReason: Int) {
        synchronized(lastLeft) {
            lastLeft.remove(id)
            lastLeft[id] = endReason
        }
    }

    /**
     * The player reported an error. [errorId] is the item the error names (see [itemOf]), or null
     * when it names none, and [currentId] is the current item. The newest play of the item the
     * error names stopped on it; an error that names no item is put on the current item's play, the
     * one in progress. An item with no play in the book marks nothing.
     *
     * Which item an error names is Media3's own attribution, and the player stops on any error it
     * reports. In 1.8.0, read in the media/ checkout and checked with javap on the AAR the app builds
     * against, an IO error is raised only for the playing period and handleIoException names that
     * one. A renderer error names the period of the stream the renderer holds
     * (BaseRenderer.createRendererException), or the reading period when it names none
     * (ExoPlayerImplInternal.handleMessage). When the reading period is ahead of the playing one,
     * the player first moves playback on to it, as an automatic transition that
     * ExoPlayerImpl.updatePlaybackInfo reports before the error, and a renderer still holding the
     * old stream names the item just left.
     *
     * The current item is not always the one that failed. Track selection for the item being
     * loaded ahead runs while the current item plays, and an error there (DECODER_QUERY_FAILED
     * from MediaCodecRenderer.supportsFormat, say) is reported with the current item still
     * current. It names the current item as well: the renderer that raised it holds the current
     * item's stream, or holds none and the reading period is named, which is the current item's
     * until the end of it has been read. So the current play is marked, and rightly: the player
     * stopped it there, and it was not the listener who left it.
     */
    fun playerError(errorId: String?, currentId: String?) {
        (errorId ?: currentId)?.let(::current)?.fail()
    }

    /**
     * The player's state, read whenever it or playWhenReady changes, while [currentId] is current.
     * Only READY is music coming out again: BUFFERING after an error is a retry that can fail the
     * same way, and IDLE is where the error left it.
     */
    fun playbackState(currentId: String?, state: Int) {
        if (state == Player.STATE_READY) currentId?.let(::current)?.recover()
    }

    /**
     * How the oldest open play of [id] ended, taking it out of the book. [endedByPlayer] is the
     * stats' own end. The transition that moved on from the play and its stats race, so a play with
     * no reason yet is given [wait] for one to arrive before it is called a stop. A play that failed
     * ended in its error whatever moved on from it, so it does not wait. See
     * [ListenProgress.endReason].
     */
    suspend fun close(id: String, endedByPlayer: Boolean, wait: suspend () -> Unit): Closed<P> {
        val play = takeOldest(id)
        val transition = if (endedByPlayer || play == null || play.failed) null else play.transition ?: run {
            wait()
            play.transition
        }
        return Closed(play, ListenProgress.endReason(endedByPlayer, play?.failed == true, transition))
    }

    /** A play taken out of the book, or null when none was there, and how it ended. */
    class Closed<P>(val play: P?, val endReason: Int)

    companion object {
        /** How many songs the radio anchor's record keeps, well past the ten it looks back over. */
        const val REMEMBERED_SONGS = 100

        /**
         * The item a player error names: the media item of the period in its mediaPeriodId, found
         * in [timeline]. Null when the error names no period, or one [timeline] does not hold.
         */
        fun itemOf(error: PlaybackException, timeline: Timeline): String? {
            val period = (error as? ExoPlaybackException)?.mediaPeriodId ?: return null
            val index = timeline.getIndexOfPeriod(period.periodUid)
            if (index == C.INDEX_UNSET) return null
            val window = timeline.getPeriod(index, Timeline.Period()).windowIndex
            return timeline.getWindow(window, Timeline.Window()).mediaItem.mediaId.takeIf { it.isNotEmpty() }
        }

        /** What a transition says about the play it moved on from, by the player's reason for it. */
        fun endReasonOf(transitionReason: Int): Int = when (transitionReason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> EndReason.ENDED
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> EndReason.SKIPPED
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> EndReason.REPLACED
            else -> EndReason.UNKNOWN
        }
    }
}

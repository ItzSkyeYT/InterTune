/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player
import com.dd3boh.outertune.constants.EndReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * What one play has been told about how it is being left: whether it stopped on a playback error,
 * and what the transition that moved on from it said. Both live on the play itself, so nothing one
 * play is told can be read by another play of the same song.
 *
 * The transition's reason used to be kept by song id, taken when the song's stats arrived, and
 * left in place when the stats said ended, for the radio's anchor. A song that reached the end of
 * its queue and was then left by a seek or a new queue left a SKIPPED or a REPLACED behind that
 * way, and the next play of that song to close with no transition of its own (the service released
 * while it sat paused) took it: a stop written as a skip, which the engine counted against the
 * song and which Rest songs I skip could rest it for.
 */
open class PlayEnd {
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
class PlayBook<P : PlayEnd> {
    private val plays = ConcurrentHashMap<String, ConcurrentLinkedDeque<P>>()

    /** The play the player holds now, and its song: what the next transition moves on from. */
    @Volatile private var playing: P? = null
    @Volatile private var playingId: String? = null

    /**
     * How the transition out of each song's latest play described it, for the radio's anchor. Not
     * read by any listen: a reason a play takes for its row is only ever its own.
     */
    private val lastLeft = ConcurrentHashMap<String, Int>()

    /** The newest play of [id]: the one in progress, when [id] is the current item. */
    fun current(id: String): P? = plays[id]?.peekLast()

    /** The oldest play of [id] not yet closed, taken out of the book: the one whose stats have just arrived. */
    fun takeOldest(id: String): P? {
        var oldest: P? = null
        plays.computeIfPresent(id) { _, queue -> oldest = queue.pollFirst(); queue.takeIf { it.isNotEmpty() } }
        return oldest
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
        playingId?.let { lastLeft[it] = left }
        if (id == null || play == null) {
            playing = null
            playingId = null
            return
        }
        plays.compute(id) { _, queue -> (queue ?: ConcurrentLinkedDeque()).apply { addLast(play) } }
        playing = play
        playingId = id
    }

    /** Whether the latest play of [id] that was moved on from was let finish: a natural end, or a repeat. */
    fun leftAtItsEnd(id: String): Boolean = lastLeft[id] == EndReason.ENDED

    /**
     * The player reported an error while [currentId] was its current item: that item's play in
     * progress stopped on it.
     *
     * The current item is the one that failed. Checked in Media3 1.8.0, both in the media/ checkout
     * and with javap on the AAR the app builds against: an IO error is raised only for the playing
     * period, and ExoPlayerImplInternal.handleIoException tags it with that period; an error in the
     * next item's preparation or loading only stops loading it (isLoadingPossible, hasLoadingError)
     * until playback reaches it. A renderer error met while reading ahead moves the playing period
     * on to the failing item, as an automatic transition, before the error is set, and
     * ExoPlayerImpl.updatePlaybackInfo sends onMediaItemTransition before onPlayerError. So the
     * play that was current when the next item failed ends as a natural end, and the next item's
     * play takes the error.
     */
    fun playerError(currentId: String?) {
        currentId?.let(::current)?.fail()
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
     * no reason yet is given [wait] for one to arrive before it is called a stop. See
     * [ListenProgress.endReason].
     */
    suspend fun close(id: String, endedByPlayer: Boolean, wait: suspend () -> Unit): Closed<P> {
        val play = takeOldest(id)
        val transition = if (endedByPlayer || play == null) null else play.transition ?: run {
            wait()
            play.transition
        }
        return Closed(play, ListenProgress.endReason(endedByPlayer, play?.failed == true, transition))
    }

    /** A play taken out of the book, or null when none was there, and how it ended. */
    class Closed<P>(val play: P?, val endReason: Int)

    companion object {
        /** What a transition says about the play it moved on from, by the player's reason for it. */
        fun endReasonOf(transitionReason: Int): Int = when (transitionReason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> EndReason.ENDED
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> EndReason.SKIPPED
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> EndReason.REPLACED
            else -> EndReason.UNKNOWN
        }
    }
}

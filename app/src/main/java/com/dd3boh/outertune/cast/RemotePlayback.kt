/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import androidx.media3.common.Player
import kotlin.math.abs

/** What the player says it is doing while it casts, as media3's four states. */
enum class RemotePhase { IDLE, BUFFERING, READY, ENDED }

/** A song handed to the remote player: its id, its length if known (ms, else -1), and the app's own item. */
data class RemoteSong<T>(val mediaId: String, val durationMs: Long, val item: T)

/**
 * One song in the remote player's list. [key] stays the same for as long as the song is in the list
 * and no other entry has it, which is what media3 needs of a playlist's ids.
 */
data class RemoteEntry<T>(val key: Long, val mediaId: String, val durationMs: Long, val item: T)

/** One failure, as an object, so the player can tell a new failure from the same one read twice. */
class RemoteError(val message: String)

/** What the remote player asks the receiver to do, in order. */
sealed interface RemoteCommand<out T> {
    /** Send [entry] and start it at [startMs]. Reports about it come back under [ticket]. */
    data class Load<T>(val ticket: Int, val entry: RemoteEntry<T>, val startMs: Long, val play: Boolean) : RemoteCommand<T>

    /** Only for the song loaded under [ticket]; a later load makes it pointless. */
    data class Seek(val ticket: Int, val positionMs: Long) : RemoteCommand<Nothing>

    data object Play : RemoteCommand<Nothing>
    data object Pause : RemoteCommand<Nothing>
    data object Stop : RemoteCommand<Nothing>
}

/**
 * Everything the remote player decides, with no Android in it (design.md section 6).
 *
 * It holds the list the phone's queue put in the player, which song is current, whether playback
 * is wanted, and where the song is. The receiver only ever holds the current song: each change of
 * song is a new [RemoteCommand.Load], numbered by a ticket, and a report is only believed when it is
 * about the latest ticket, so a report about a song the listener has already skipped past cannot
 * move the player. The queue stays the app's: this decides only what happens when a song ends,
 * fails or is skipped, the way ExoPlayer would have.
 *
 * Every change returns the commands the receiver needs, and the caller sends them in order. Not
 * thread safe: the remote player calls it on the main thread only. [now] must be the links' own
 * clock, since positions are carried on from their reports.
 */
class RemotePlayback<T>(private val now: () -> Long) {
    var entries: List<RemoteEntry<T>> = emptyList()
        private set

    /** The current song's place in [entries], or -1 when the list is empty. */
    var index = -1
        private set

    var playWhenReady = false
        private set

    /** Whether [playWhenReady] last changed because of something done on the receiver itself. */
    var changedOnReceiver = false
        private set

    var phase = RemotePhase.IDLE
        private set

    /** Why the last song failed, until the next load; set only while [phase] is IDLE. */
    var error: RemoteError? = null
        private set

    var repeatMode = Player.REPEAT_MODE_OFF

    /** The number of the latest load. Reports about any other are ignored. */
    var ticket = 0
        private set

    /** Bumped whenever [entries] changes, so the player can keep its playlist object otherwise. */
    var version = 0
        private set

    /** The entry the receiver holds, by key, or null when it holds nothing of ours. */
    private var loadedKey: Long? = null

    private var anchorMs = 0L
    private var anchorAt = 0L
    private var moving = false

    /** When the receiver was last asked to play, pause, load or seek, on [now]'s clock. */
    private var askedAt = Long.MIN_VALUE / 2

    /** A seek was asked for within [ASK_GRACE_MS], so reports far from where it went are old. */
    private var seeking = false

    private var autoTransition = false
    private var nextKey = 1L

    val current: RemoteEntry<T>? get() = entries.getOrNull(index)

    /** Where the current song is now, carried on from the last report while it plays. */
    fun positionMs(): Long = position(anchorMs, anchorAt, moving, current?.durationMs ?: -1, now())

    /**
     * The position as it stands, to be carried on by whoever reads it later without asking here
     * again: the player's state is a snapshot, and two snapshots are compared to find a jump.
     */
    fun positionSnapshot(): PositionSnapshot = PositionSnapshot(anchorMs, anchorAt, moving, current?.durationMs ?: -1)

    data class PositionSnapshot(val anchorMs: Long, val anchorAt: Long, val moving: Boolean, val durationMs: Long) {
        fun at(now: Long): Long = position(anchorMs, anchorAt, moving, durationMs, now)
    }

    /**
     * Whether the song changed because the one before it ended (or started over on repeat one), since
     * the last call. The player reports that change as automatic rather than as a skip, which the
     * sleep timer's end of song and the listen history both go by.
     */
    fun takeAutoTransition(): Boolean = autoTransition.also { autoTransition = false }

    /**
     * Sets up what the receiver starts from when casting begins: the phone's list, song, position and
     * whether it was playing. Nothing is sent; [prepare] loads the song.
     */
    fun begin(songs: List<RemoteSong<T>>, startIndex: Int, startMs: Long, play: Boolean, repeat: Int) {
        entries = songs.map(::entryOf)
        version++
        index = if (entries.isEmpty()) -1 else startIndex.coerceIn(entries.indices)
        playWhenReady = play
        repeatMode = repeat
        phase = RemotePhase.IDLE
        loadedKey = null
        anchor(startMs.coerceAtLeast(0), moving = false)
    }

    fun setItems(songs: List<RemoteSong<T>>, startIndex: Int, startMs: Long): List<RemoteCommand<T>> {
        entries = songs.map(::entryOf)
        version++
        val start = startMs.coerceAtLeast(0)
        if (entries.isEmpty()) {
            index = -1
            return clearCurrent(RemotePhase.ENDED)
        }
        index = if (startIndex in entries.indices) startIndex else 0
        if (phase == RemotePhase.IDLE) {
            loadedKey = null
            anchor(start, moving = false)
            return emptyList()
        }
        return load(start)
    }

    fun addItems(at: Int, songs: List<RemoteSong<T>>): List<RemoteCommand<T>> {
        if (songs.isEmpty()) return emptyList()
        val wasEmpty = entries.isEmpty()
        val position = at.coerceIn(0, entries.size)
        entries = entries.toMutableList().apply { addAll(position, songs.map(::entryOf)) }
        version++
        if (wasEmpty) {
            index = 0
            // A player that ran out of songs and is given more starts on them, as ExoPlayer does.
            return if (phase == RemotePhase.ENDED) load(0) else emptyList()
        }
        if (position <= index) index += songs.size
        return emptyList()
    }

    fun moveItems(from: Int, to: Int, newIndex: Int): List<RemoteCommand<T>> {
        val key = current?.key
        val list = entries.toMutableList()
        val moved = list.subList(from, to).toList()
        list.subList(from, to).clear()
        list.addAll(newIndex.coerceIn(0, list.size), moved)
        entries = list
        version++
        if (key != null) index = entries.indexOfFirst { it.key == key }
        return emptyList()
    }

    fun removeItems(from: Int, to: Int): List<RemoteCommand<T>> {
        val removedCurrent = index in from until to
        entries = entries.toMutableList().apply { subList(from, to).clear() }
        version++
        if (!removedCurrent) {
            if (index >= to) index -= to - from
            return emptyList()
        }
        return afterCurrentRemoved(from)
    }

    fun replaceItems(from: Int, to: Int, songs: List<RemoteSong<T>>): List<RemoteCommand<T>> {
        val current = current
        val replacements = songs.map(::entryOf).toMutableList()
        if (current == null || index !in from until to) {
            entries = entries.toMutableList().apply {
                subList(from, to).clear()
                addAll(from, replacements)
            }
            version++
            if (current != null && index >= to) index += replacements.size - (to - from)
            if (current == null && entries.isNotEmpty()) {
                index = 0
                return if (phase == RemotePhase.ENDED) load(0) else emptyList()
            }
            return emptyList()
        }
        // The current song is among those replaced. The same song in its place goes on playing,
        // as ExoPlayer carries on with a media item it can update; anything else is a new song.
        val offset = index - from
        val same = replacements.getOrNull(offset)?.takeIf { it.mediaId == current.mediaId }
        if (same != null) replacements[offset] = same.copy(key = current.key)
        entries = entries.toMutableList().apply {
            subList(from, to).clear()
            addAll(from, replacements)
        }
        version++
        if (same != null) {
            index = from + offset
            return emptyList()
        }
        return afterCurrentRemoved(from + minOf(offset, replacements.size))
    }

    /**
     * [targetIndex] -1 is the current song. A seek within the song the receiver holds is a seek
     * there; anything else loads a song, as a seek to another item or a seek after the end would
     * make ExoPlayer do.
     */
    fun seekTo(targetIndex: Int, positionMs: Long): List<RemoteCommand<T>> {
        if (entries.isEmpty()) return emptyList()
        val target = if (targetIndex < 0) index else targetIndex.coerceIn(entries.indices)
        val position = positionMs.coerceAtLeast(0)
        val holdsIt = target == index && loadedKey != null && loadedKey == current?.key &&
            (phase == RemotePhase.BUFFERING || phase == RemotePhase.READY)
        if (holdsIt) {
            anchor(position, moving = moving)
            askedAt = now()
            seeking = true
            return listOf(RemoteCommand.Seek(ticket, position))
        }
        index = target
        if (phase == RemotePhase.IDLE) {
            loadedKey = null
            anchor(position, moving = false)
            return emptyList()
        }
        return load(position)
    }

    fun setPlayWhenReady(play: Boolean): List<RemoteCommand<T>> {
        if (play == playWhenReady) return emptyList()
        anchor(positionMs(), moving = play && phase == RemotePhase.READY)
        playWhenReady = play
        changedOnReceiver = false
        askedAt = now()
        val holding = loadedKey != null && (phase == RemotePhase.BUFFERING || phase == RemotePhase.READY)
        return if (holding) listOf(if (play) RemoteCommand.Play else RemoteCommand.Pause) else emptyList()
    }

    /** Loads the current song where it stands, after a stop, a failure or the start of casting. */
    fun prepare(): List<RemoteCommand<T>> {
        if (phase != RemotePhase.IDLE) return emptyList()
        error = null
        if (entries.isEmpty()) {
            phase = RemotePhase.ENDED
            return emptyList()
        }
        return load(positionMs())
    }

    fun stop(): List<RemoteCommand<T>> {
        val held = loadedKey != null
        anchor(positionMs(), moving = false)
        phase = RemotePhase.IDLE
        error = null
        loadedKey = null
        // Anything still to come about the song that was playing is about nothing now.
        ticket++
        return if (held) listOf(RemoteCommand.Stop) else emptyList()
    }

    /** The song under [ticket] could not be sent at all: the phone could not get it, or the receiver plays nothing it can be made into. */
    fun onLoadFailed(ticket: Int, message: String): List<RemoteCommand<T>> {
        if (ticket != this.ticket || loadedKey == null) return emptyList()
        fail(message)
        return emptyList()
    }

    /** What the receiver said about the song under [ticket]. */
    fun onReceiver(ticket: Int, status: ReceiverStatus): List<RemoteCommand<T>> {
        // Once the list has ended, the receiver's word on the last song changes nothing: a repeat
        // of its end report must not start the queue again after the listener changed the repeat.
        if (ticket != this.ticket || loadedKey == null || phase == RemotePhase.ENDED) return emptyList()
        val entry = current ?: return emptyList()
        if (status.durationMs > 0 && entry.durationMs <= 0) {
            // The receiver knows the length when the app did not, which a song tapped in search
            // results often does not; the progress bar needs it.
            entries = entries.toMutableList().apply { this[index] = entry.copy(durationMs = status.durationMs) }
            version++
        }
        // A receiver reports every second or so, and a report made just before it heard a pause
        // can arrive just after it: believed, it would start the player again by itself. So for a
        // moment after asking, a report that says the opposite of what was asked is left for the
        // next one.
        val fresh = status.reportedAt - askedAt >= ASK_GRACE_MS
        if (fresh) seeking = false
        // The same goes for where it is: a report from before a seek landed would pull the progress
        // bar back to where the listener left for a second.
        val playingOrPaused = status.state == ReceiverState.PLAYING || status.state == ReceiverState.PAUSED
        if (seeking && playingOrPaused && abs(status.positionMs - positionMs()) > SEEK_SLACK_MS) return emptyList()
        when (status.state) {
            ReceiverState.LOADING -> {
                if (phase != RemotePhase.BUFFERING) {
                    anchor(positionMs(), moving = false)
                    phase = RemotePhase.BUFFERING
                }
            }
            ReceiverState.PLAYING -> {
                if (!playWhenReady) {
                    if (!fresh) return emptyList()
                    playWhenReady = true
                    changedOnReceiver = true
                }
                phase = RemotePhase.READY
                anchorAt(status.positionMs, status.reportedAt, moving = true)
            }
            ReceiverState.PAUSED -> {
                if (playWhenReady) {
                    if (!fresh) return emptyList()
                    playWhenReady = false
                    changedOnReceiver = true
                }
                phase = RemotePhase.READY
                anchorAt(status.positionMs, status.reportedAt, moving = false)
            }
            ReceiverState.ENDED -> return advance()
            ReceiverState.FAILED -> fail(status.error ?: "The song could not be played")
            ReceiverState.IDLE -> {
                // Stopped on the receiver, or another sender took it over. Held paused here, so
                // play loads the song again where it was, which is what an idle ExoPlayer does.
                anchor(positionMs(), moving = false)
                phase = RemotePhase.IDLE
                loadedKey = null
                if (playWhenReady) {
                    playWhenReady = false
                    changedOnReceiver = true
                }
            }
        }
        return emptyList()
    }

    /** What comes after a song that played to its end: the same again, the next, the first, or nothing. */
    private fun advance(): List<RemoteCommand<T>> {
        val next = RemoteQueue.next(index, entries.size, repeatMode)
        if (next == null) {
            anchor(current?.durationMs?.takeIf { it > 0 } ?: positionMs(), moving = false)
            phase = RemotePhase.ENDED
            return emptyList()
        }
        index = next
        autoTransition = true
        return load(0)
    }

    private fun afterCurrentRemoved(nextIndex: Int): List<RemoteCommand<T>> {
        if (entries.isEmpty()) {
            index = -1
            return clearCurrent(RemotePhase.ENDED)
        }
        val next = when {
            nextIndex < entries.size -> nextIndex
            repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> null
        }
        if (next == null) {
            // Nothing after it: the player ends on the last song, as ExoPlayer does.
            index = entries.size - 1
            return clearCurrent(RemotePhase.ENDED)
        }
        index = next
        if (phase == RemotePhase.IDLE) {
            loadedKey = null
            anchor(0, moving = false)
            return emptyList()
        }
        return load(0)
    }

    /** Takes the song off the receiver; an idle player stays idle, any other becomes [phase]. */
    private fun clearCurrent(phase: RemotePhase): List<RemoteCommand<T>> {
        val held = loadedKey != null
        loadedKey = null
        anchor(0, moving = false)
        if (this.phase != RemotePhase.IDLE) this.phase = phase
        ticket++
        return if (held) listOf(RemoteCommand.Stop) else emptyList()
    }

    private fun load(startMs: Long): List<RemoteCommand<T>> {
        val entry = current ?: return clearCurrent(RemotePhase.ENDED)
        ticket++
        seeking = false
        loadedKey = entry.key
        error = null
        phase = RemotePhase.BUFFERING
        anchor(startMs, moving = false)
        askedAt = now()
        return listOf(RemoteCommand.Load(ticket, entry, startMs, playWhenReady))
    }

    private fun fail(message: String) {
        anchor(positionMs(), moving = false)
        error = RemoteError(message)
        phase = RemotePhase.IDLE
        loadedKey = null
    }

    private fun anchor(positionMs: Long, moving: Boolean) = anchorAt(positionMs, now(), moving)

    private fun anchorAt(positionMs: Long, at: Long, moving: Boolean) {
        anchorMs = positionMs
        anchorAt = at
        this.moving = moving
    }

    private fun entryOf(song: RemoteSong<T>) = RemoteEntry(nextKey++, song.mediaId, song.durationMs, song.item)

    companion object {
        /** How long a report that contradicts a play or pause just asked for is put down to lag. */
        const val ASK_GRACE_MS = 2_000L

        /** How far a report may be from where a seek just went and still be about after it. */
        const val SEEK_SLACK_MS = 3_000L

        private fun position(anchorMs: Long, anchorAt: Long, moving: Boolean, durationMs: Long, now: Long): Long {
            val raw = if (moving) anchorMs + (now - anchorAt).coerceAtLeast(0) else anchorMs
            return if (durationMs > 0) raw.coerceIn(0, durationMs) else raw.coerceAtLeast(0)
        }
    }
}

/** Which song follows one that ended, by the repeat mode. Our shuffle reorders the list itself, so the order is the list's. */
object RemoteQueue {
    /** The index after [index] in a list of [size], or null when playback ends there. */
    fun next(index: Int, size: Int, repeatMode: Int): Int? = when {
        size <= 0 || index !in 0 until size -> null
        repeatMode == Player.REPEAT_MODE_ONE -> index
        index + 1 < size -> index + 1
        repeatMode == Player.REPEAT_MODE_ALL -> 0
        else -> null
    }
}

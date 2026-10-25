/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Which songs SABR reads ahead of the player (SabrFile.Ahead): the one playing, and the next one
 * in the queue once the one playing is held to its end. Over SABR the music buffered ahead is
 * YouTube's readahead, about 15 s, so a connection lost mid-song stopped the music within seconds.
 * Read ahead, the song plays on to its end, and the next one starts with no network.
 *
 * - The song playing reads ahead by itself, from the player's first read of it (SabrFile).
 * - Once it is held to its end, the next song is resolved through the player's own resolver
 *   ([resolve]), so that the player finds its address when it gets there, and when that address
 *   is a sabr: one its start reads ahead from its first byte, or from the first one the song
 *   cache does not hold ([cachedUpTo]). A direct address, or a song already on the phone, is
 *   left as it is: only SABR is read ahead. One song at a time, so YouTube never sees two fast
 *   streams of one listener at once.
 * - Every other song lets go of what it held as soon as the queue moves on ([queueAt]): a skip, a
 *   jump, a new queue, shuffle or repeat changed. A song the queue comes back to reads ahead again
 *   when the player reads it again.
 * - [allowed] says whether to at all: the flag, and Data Saver. A refused read ahead, a resolve
 *   that fails, a next song that is not over SABR: all quiet. The song plays as it always did,
 *   and no route is struck for it.
 *
 * Plain Kotlin, so that the JVM tests reach it (SabrAheadTest).
 */
class SabrAhead(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    /**
     * The address the player's resolver has for a song, resolving it when it has none, or null
     * when the song needs none (on the phone already). Blocking, and may throw.
     */
    private val resolve: (songId: String) -> String?,
    /**
     * How much of a song's start the song cache holds, from an earlier play cut short: the player
     * reads that part there, so the read ahead begins after it. Asked after [resolve], which may
     * drop a part that is not of the stream it found.
     */
    private val cachedUpTo: (songId: String) -> Long = { 0L },
    private val starts: SabrStarts = SabrStarts,
    private val log: (String) -> Unit = {},
    /** How long the next song waits before it is resolved: see [PLAYER_FIRST_MS]. */
    private val playerFirstMs: Long = PLAYER_FIRST_MS,
) {
    private var current: String? = null
    private var next: String? = null

    /** The song playing and the next one whose next was read ahead already: once for each pair. */
    private var asked: Pair<String, String>? = null

    /** Whether to read ahead now. Never throws. */
    fun allowedNow(): Boolean = runCatching { allowed() }.getOrDefault(false)

    /** Where the queue is now: the song playing and the next one, as the player has them. */
    fun queueAt(current: String?, next: String?) {
        val follow = synchronized(this) {
            this.current = current
            this.next = next?.takeIf { it != current }
            starts.dropAheadExcept(setOfNotNull(current, this.next))
            nextToRead()
        }
        follow?.let(::readAhead)
    }

    /** [songId] is held to its end (SabrFile.Ahead.onRestHeld): the next song may follow it. */
    fun restHeld(songId: String) {
        val follow = synchronized(this) { if (songId == current) nextToRead() else null }
        follow?.let(::readAhead)
    }

    /** The song playing and the next one, when the next is to be read ahead now. Under the lock. */
    private fun nextToRead(): Pair<String, String>? {
        val playing = current ?: return null
        val after = next ?: return null
        if (asked == playing to after) return null
        if (!starts.restHeld(playing) || !allowedNow()) return null
        return (playing to after).also { asked = it }
    }

    /**
     * Whether [pair] is still the song playing and the next one. When it is not, it may be asked
     * for again: the queue can come back to it.
     */
    private fun stillAt(pair: Pair<String, String>): Boolean = synchronized(this) {
        (current == pair.first && next == pair.second).also { still -> if (!still && asked == pair) asked = null }
    }

    private fun readAhead(pair: Pair<String, String>) {
        val (playing, song) = pair
        scope.launch {
            // The player's own load of the song goes first when there is one: see PLAYER_FIRST_MS.
            delay(playerFirstMs)
            if (!stillAt(pair)) {
                log("SABR ahead: [$song] is no longer next, not resolved ahead")
                return@launch
            }
            val address = try {
                resolve(song)
            } catch (e: Throwable) {
                // Tried again at the queue's next change, a song added to a radio for one.
                synchronized(this@SabrAhead) { if (asked == pair) asked = null }
                log("SABR ahead: [$song], next after [$playing], could not be resolved ahead: ${e.javaClass.simpleName}")
                return@launch
            }
            val stillNext = stillAt(pair)
            when {
                address == null -> log("SABR ahead: [$song], next after [$playing], is on the phone already")
                !stillNext -> log("SABR ahead: [$song] is no longer next, not read ahead")
                !address.startsWith(SabrStarts.SCHEME + "://") -> log("SABR ahead: [$song], next after [$playing], has a direct address: nothing read ahead")
                else -> {
                    val from = runCatching { cachedUpTo(song) }.getOrDefault(0L).coerceAtLeast(0L)
                    val reading = starts.forAddress(address)?.file?.readAhead(from) == true
                    log(
                        "SABR ahead: [$song], next after [$playing], " +
                            if (!reading) "could not be read ahead"
                            else if (from > 0) "read ahead from byte $from, the song cache holding its start"
                            else "read ahead"
                    )
                }
            }
        }
    }

    companion object {
        /**
         * How long the next song waits before it is resolved ahead, so that the player's own load
         * of it goes first when there is one: a load control that reads past the end of the song
         * playing, or a preload, opens the next song as soon as the one playing is read to its
         * end, which over SABR is when it is held to its end. Its resolve is then out, and
         * MusicService.resolveAhead waits for it and takes the address it kept, so the chain is
         * walked once for the song.
         */
        const val PLAYER_FIRST_MS = 2_000L
    }
}

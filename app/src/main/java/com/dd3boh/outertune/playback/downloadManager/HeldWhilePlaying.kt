/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/**
 * The songs the player has open (the one playing, and the next one it may be reading ahead), and
 * the moves into the download folder held back for them.
 *
 * A song's file is never moved or replaced under the player. The player tells a song's container
 * from its first bytes and keeps reading at that container's byte offsets for as long as the song
 * is open: a new file under the same song (an Ogg or M4A file in the folder where it read WebM or
 * MP4 from the cache, or another stream of a Download quality of its own) read at the old offsets
 * is garbage on the next Previous, seek back or reconnect, and the song stops or ends early. So the
 * move waits until the song is no longer open, then goes as it would have.
 *
 * Thread-safe: the player sets what it has open on the main thread, the moves ask from theirs.
 */
class HeldWhilePlaying {
    private var open: Set<String> = emptySet()
    private val held = linkedSetOf<String>()

    /** Whether the player has [id] open now. */
    @Synchronized
    fun isOpen(id: String): Boolean = id in open

    /** Whether the move of [id] waits for the player to let it go. */
    @Synchronized
    fun isHeld(id: String): Boolean = id in held

    /**
     * Of the songs [ids] about to be moved, the ones that can go now. The others are open in the
     * player: they are held, and [playerHas] gives them back once the player lets them go.
     */
    @Synchronized
    fun movableNow(ids: Collection<String>): Set<String> {
        val (later, now) = ids.partition { it in open }
        held += later
        return now.toSet()
    }

    /** The player has [ids] open now. Returns the held songs it no longer has, to be moved now. */
    @Synchronized
    fun playerHas(ids: Set<String>): Set<String> {
        open = ids.toSet()
        val free = held.filterTo(linkedSetOf()) { it !in open }
        held -= free
        return free
    }

    /**
     * Waits while the player has [id] open, [limitMs] at most, asking every [pollMs]: true once it
     * is let go, false when it still has it then. Never longer: a song paused for the night, or the
     * last of a queue that ended, stays open for as long as the app does, and a download waiting on
     * it held one of the three download threads, the move of every other song of its batch (the
     * manager is idle only once none runs) and the download service in the foreground, whose
     * dataSync time Android 15 counts. [sleep] throws when the waiting thread is interrupted (media3
     * stops a download so), which ends the wait as well.
     */
    fun awaitLetGo(id: String, limitMs: Long, pollMs: Long, now: () -> Long, sleep: (Long) -> Unit): Boolean {
        val start = now()
        while (isOpen(id)) {
            val left = limitMs - (now() - start)
            if (left <= 0) return false
            sleep(minOf(pollMs, left))
        }
        return true
    }
}

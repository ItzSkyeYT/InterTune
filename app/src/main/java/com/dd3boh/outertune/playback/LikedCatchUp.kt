/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_DOWNLOADING
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_INVALID
import java.time.LocalDateTime

/**
 * The counting behind the liked-songs catch up: which songs of a run have arrived, which have
 * failed, and whether anything is still to come. No Android in here, so it is tested.
 *
 * The run used to wait for every song in the batch to land, and a song whose download fails never
 * lands. media3 reports the failure as a change to STATE_FAILED, the listener turns that into
 * STATE_INVALID and drops the song from the map, and from then on it reads exactly like a song
 * nobody has queued yet. The row sat at 299 of 300, still offering to stop, until somebody stopped
 * it by hand. The map alone cannot tell a failed song from one media3 has not picked up yet, so the
 * listener also notes how each of the run's songs ended, and the run is over once every song has
 * landed, failed or been taken off the queue.
 */
object LikedCatchUp {

    /** How one of the run's songs finished, as the download listener saw it happen. */
    enum class End {
        /**
         * Kept as well as the map's timestamp because a finished song can still leave the map, when
         * its file is deleted while the run goes on, and it would then read as never started and
         * hold the run open again.
         */
        LANDED,

        FAILED,

        /**
         * Taken off the queue, by the user's own stop or from a song, album or playlist menu. It
         * settles the song without counting as a failure: nothing went wrong, somebody decided
         * against it.
         */
        REMOVED,
    }

    /**
     * Where a run stands. Every song of the batch is in exactly one of the four counts, so they add
     * up to the batch size.
     */
    data class Tally(val landed: Int, val failed: Int, val removed: Int, val open: Int) {
        /** Nothing left to wait for, which ends the run whether or not all of it arrived. */
        val settled: Boolean get() = open == 0
    }

    /**
     * Folds one listener update into the run's endings.
     *
     * Completed, failed and removing are endings. Any other state means media3 is working on the
     * song again, typically because it was queued by hand after failing, so an earlier ending no
     * longer holds. Songs outside the batch belong to somebody else's download and are left alone.
     */
    fun afterUpdate(ends: Map<String, End>, batch: Set<String>, id: String, state: Int): Map<String, End> {
        if (id !in batch) return ends
        return when (state) {
            Download.STATE_COMPLETED -> ends + (id to End.LANDED)
            Download.STATE_FAILED -> ends + (id to End.FAILED)
            Download.STATE_REMOVING -> ends + (id to End.REMOVED)
            else -> ends - id
        }
    }

    /**
     * A song the download service would not take, which happens when the enqueue is refused from
     * the background. media3 never hears of it, so no update will ever arrive to end it, and left
     * open it would hold the run for good. It is settled here as failed, which is the truth: it did
     * not download, and the next run picks it up like any other failure.
     */
    fun neverSent(ends: Map<String, End>, id: String): Map<String, End> = ends + (id to End.FAILED)

    /**
     * Counts the batch.
     *
     * The map comes first, because it says what media3 is doing now: a real timestamp is a song that
     * has landed, and STATE_DOWNLOADING is one queued or in flight, whatever happened to it before.
     * Only when the map has nothing useful does the listener's record of how the song ended decide.
     *
     * A song with neither is still open rather than done. The enqueue goes through the download
     * service and media3 answers a moment later, so right after the enqueue loop most of a large
     * batch looks exactly like this, and counting it as settled would end the run before it had
     * started.
     */
    fun tally(batch: Set<String>, downloads: Map<String, LocalDateTime>, ends: Map<String, End>): Tally {
        var landed = 0
        var failed = 0
        var removed = 0
        var open = 0
        for (id in batch) {
            val live = downloads[id]
            when {
                live == STATE_DOWNLOADING -> open++
                live != null && live != STATE_INVALID -> landed++
                else -> when (ends[id]) {
                    End.LANDED -> landed++
                    End.FAILED -> failed++
                    End.REMOVED -> removed++
                    null -> open++
                }
            }
        }
        return Tally(landed = landed, failed = failed, removed = removed, open = open)
    }
}

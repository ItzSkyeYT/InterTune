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

        /**
         * Somebody took songs off the queue before they arrived, most often all of them at once
         * with the cancel on the download notification, which never goes through the row's own
         * stop. A run like that should read as stopped: finishing it quietly left the row saying
         * "Downloaded 3 liked songs." with nothing about the other 296.
         */
        val cutShort: Boolean get() = removed > 0
    }

    /**
     * Folds one listener update into the run's endings.
     *
     * Completed, failed and removing are endings. Any other state means media3 is working on the
     * song again, typically because it was queued by hand after failing, so an earlier ending no
     * longer holds. That is what lets [tally] trust a recorded ending over the map: while one is
     * there, the last thing the listener heard about the song was that it ended. Songs outside the
     * batch belong to somebody else's download and are left alone.
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
     * The recorded ending comes first and the map only speaks for a song without one. The listener
     * hears every change in order and clears an ending as soon as media3 takes the song up again,
     * so a recorded ending is always the latest word. The map is not: a download scan replaces all
     * of it with a snapshot it read from the database before walking the download folders, which
     * is slow, and that snapshot marks every song media3 still had queued as STATE_DOWNLOADING. A
     * song that lands or fails during the walk gets its ending, and then the snapshot puts it back
     * as downloading. media3 never reports on a finished download again, so with the map first
     * that song stayed open for good and the row stuck one short, just as it did before endings
     * were recorded, and the only way out was a stop that removed the song that had landed. For
     * this to hold, the listener writes the ending before the map, so the moment between the two
     * can only show an ending that is already true.
     *
     * Without an ending, a real timestamp is a song that has landed and anything else is open.
     * Open rather than done, because the enqueue goes through the download service and media3
     * answers a moment later, so right after the enqueue loop most of a large batch has neither,
     * and counting it as settled would end the run before it had started.
     */
    fun tally(batch: Set<String>, downloads: Map<String, LocalDateTime>, ends: Map<String, End>): Tally {
        var landed = 0
        var failed = 0
        var removed = 0
        var open = 0
        for (id in batch) {
            when (ends[id]) {
                End.LANDED -> landed++
                End.FAILED -> failed++
                End.REMOVED -> removed++
                null -> {
                    val live = downloads[id]
                    if (live != null && live != STATE_DOWNLOADING && live != STATE_INVALID) landed++ else open++
                }
            }
        }
        return Tally(landed = landed, failed = failed, removed = removed, open = open)
    }

    /**
     * The songs a stop takes off media3's queue: the run's own that the map shows queued or in
     * flight, and not one of them with a recorded ending.
     *
     * The ending check is there because the map can be a scan's stale snapshot, which still shows
     * a song that has since landed as downloading (see [tally]). media3 applies a removal to a
     * completed download too, deleting it, so trusting the map here threw away a song the run had
     * just fetched. A song that failed or was already removed has nothing left to stop either.
     */
    fun toRemoveOnStop(
        batch: Set<String>,
        downloads: Map<String, LocalDateTime>,
        ends: Map<String, End>,
    ): Set<String> = batch.filterTo(mutableSetOf()) { downloads[it] == STATE_DOWNLOADING && it !in ends }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a run of downloads stands, for the download notification: how many of its songs have
 * ended and how many there are in all. A run is every song queued from the moment the queue was
 * empty until it is empty again, so an album added while another one downloads joins the run.
 *
 * media3's own bar (DownloadNotificationHelper.buildProgressNotification) is the average percent
 * of the songs downloading at that moment, three at the most, and a song that has landed is no
 * longer among the downloads it is handed. So it never counted a song done: a long run stayed low
 * from its first song to its last, each new song starting again from nothing, and a song still
 * waiting for its stream to be found counting as nothing at all.
 *
 * A listener on the download manager, which calls it on the same thread as the download service's
 * own listener, and before it, being added first (DownloadUtil). The counting takes plain values,
 * so it is tested without a download manager.
 */
internal class DownloadRun : DownloadManager.Listener {

    /** The songs of this run that have ended, landed or failed. */
    private val ended = ConcurrentHashMap.newKeySet<String>()

    val endedCount: Int get() = ended.size

    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
        changed(download.request.id, download.state, anyLeft(downloadManager))
    }

    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
        removed(download.request.id, anyLeft(downloadManager))
    }

    private fun anyLeft(downloadManager: DownloadManager) = downloadManager.currentDownloads.any { pending(it.state) }

    /**
     * One song changed to [state]. [anyLeft] is whether any song is still queued or downloading
     * after the change: once none is, the run is over, and the next song starts a new one.
     *
     * A song queued again after it ended, or removed, no longer counts as ended: it is either to
     * come again or gone from the run.
     */
    fun changed(id: String, state: Int, anyLeft: Boolean) {
        if (state == Download.STATE_COMPLETED || state == Download.STATE_FAILED) ended += id else ended -= id
        if (!anyLeft) ended.clear()
    }

    fun removed(id: String, anyLeft: Boolean) {
        ended -= id
        if (!anyLeft) ended.clear()
    }

    /** One of media3's current downloads, as the bar reads it. */
    data class Item(val state: Int, val percent: Float)

    /**
     * The notification's numbers: [done] songs of [total] have ended, and the bar stands at
     * [position] of [max], a hundred to a song, so that a song partway in moves it as well.
     * [indeterminate] while there is nothing to go on yet.
     */
    data class Bar(
        val done: Int,
        val total: Int,
        val position: Int,
        val max: Int,
        val indeterminate: Boolean,
    ) {
        /** Something of the run is still queued or downloading. */
        val running: Boolean get() = total > done
    }

    companion object {

        /** media3's percent for a download that does not know its length (C.PERCENTAGE_UNSET). */
        val UNKNOWN_PERCENT: Float = C.PERCENTAGE_UNSET.toFloat()

        /** Waiting its turn or downloading: a song of the run that is still to come. */
        fun pending(state: Int): Boolean =
            state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING || state == Download.STATE_RESTARTING

        /**
         * The bar for a run with [ended] songs ended and [current] as media3's current downloads.
         *
         * Only the songs downloading count partway, by their percent; one whose length is not known
         * yet (its stream is still being found, or it never says) counts as not begun. Songs being
         * removed and stopped ones are not part of the run. The bar never goes back as a song ends,
         * since its part becomes a whole song, landed or failed.
         */
        fun bar(ended: Int, current: List<Item>): Bar {
            val left = current.filter { pending(it.state) }
            val known = left.filter { it.state != Download.STATE_QUEUED && it.percent != UNKNOWN_PERCENT }
            val partway = known.sumOf { it.percent.coerceIn(0f, 100f).toDouble() }.toInt()
            val total = ended + left.size
            return Bar(
                done = ended,
                total = total,
                position = ended * 100 + partway,
                max = total * 100,
                // No song in yet and none of those downloading knows how far it is: a bar standing
                // at nothing would look stuck, as the old one did.
                indeterminate = ended == 0 && known.isEmpty(),
            )
        }
    }
}

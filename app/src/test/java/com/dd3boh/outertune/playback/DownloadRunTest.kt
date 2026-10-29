/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.playback.DownloadRun.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download notification's bar and count, through a whole run.
 *
 * The bar used to be media3's: the average percent of the songs downloading at that moment, and a
 * song that had landed was no longer among them. A run of 254 songs read "254 songs" with a bar
 * near the start from the first song to the last.
 */
class DownloadRunTest {

    /**
     * media3's download manager as the run sees it: the current downloads, which a song leaves as
     * it lands or fails, and the listener told of each change after the list has changed, as
     * DownloadManager does. Progress is not a change: media3 says nothing as bytes arrive, and the
     * notification reads the percent off the downloads when it is built.
     */
    private class Queue {
        val run = DownloadRun()
        val current = linkedMapOf<String, Item>()

        private fun anyLeft() = current.values.any { DownloadRun.pending(it.state) }

        fun add(vararg ids: String) = ids.forEach {
            current[it] = Item(Download.STATE_QUEUED, DownloadRun.UNKNOWN_PERCENT)
            run.changed(it, Download.STATE_QUEUED, anyLeft())
        }

        /** Its stream is being found: downloading, with no length yet. */
        fun start(id: String) {
            current[id] = Item(Download.STATE_DOWNLOADING, DownloadRun.UNKNOWN_PERCENT)
            run.changed(id, Download.STATE_DOWNLOADING, anyLeft())
        }

        fun progress(id: String, percent: Float) {
            current[id] = Item(Download.STATE_DOWNLOADING, percent)
        }

        fun land(id: String) {
            current.remove(id)
            run.changed(id, Download.STATE_COMPLETED, anyLeft())
        }

        fun fail(id: String) {
            current.remove(id)
            run.changed(id, Download.STATE_FAILED, anyLeft())
        }

        /** Removing first, then gone, as the notification's Cancel and a song's menu do it. */
        fun removing(id: String) {
            current[id] = Item(Download.STATE_REMOVING, DownloadRun.UNKNOWN_PERCENT)
            run.changed(id, Download.STATE_REMOVING, anyLeft())
        }

        fun gone(id: String) {
            current.remove(id)
            run.removed(id, anyLeft())
        }

        val bar get() = DownloadRun.bar(run.endedCount, current.values.toList())

        /** The bar media3 drew, worked out as DownloadNotificationHelper does it. */
        val media3Percent: Int
            get() {
                val downloading = current.values.filter {
                    it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
                }
                if (downloading.isEmpty()) return 0
                return (downloading.filter { it.percent != DownloadRun.UNKNOWN_PERCENT }.sumOf { it.percent.toDouble() } / downloading.size).toInt()
            }
    }

    private fun ids(n: Int) = (1..n).map { "song$it" }

    /** Drives a run three at a time: each song is found, comes in at its own pace and lands. */
    private fun Queue.downloadFirst(songs: List<String>, many: Int) {
        songs.take(many).forEach { id ->
            start(id)
            progress(id, 0f)
            progress(id, 55f)
            land(id)
        }
    }

    private fun share(bar: DownloadRun.Bar) = bar.position.toDouble() / bar.max

    @Test
    fun `a long run counts each song that lands, out of the whole run`() {
        val q = Queue()
        val songs = ids(254)
        q.add(*songs.toTypedArray())
        assertEquals(0, q.bar.done)
        assertEquals(254, q.bar.total)

        q.downloadFirst(songs, 200)
        // The next three are being found, as most of a song's turn goes.
        songs.drop(200).take(3).forEach { q.start(it) }
        val bar = q.bar
        assertEquals(200, bar.done)
        assertEquals(254, bar.total)
        assertEquals(254 * 100, bar.max)
        assertEquals(200 * 100, bar.position)
        assertFalse(bar.indeterminate)
        assertTrue(bar.running)
        // Where the old bar stood at the same moment: at nothing, 200 songs in.
        assertEquals(0, q.media3Percent)
        assertEquals(0.787, share(bar), 0.001)
    }

    @Test
    fun `the bar moves as a song comes in, and never goes back as it lands`() {
        val q = Queue()
        val songs = ids(10)
        q.add(*songs.toTypedArray())
        var last = -1
        fun check(where: String) {
            val now = q.bar.position
            assertTrue("$where: $now after $last", now >= last)
            last = now
        }
        for (id in songs) {
            q.start(id); check("$id found")
            for (p in listOf(0f, 12.5f, 50f, 99.9f, 100f)) {
                q.progress(id, p); check("$id at $p")
            }
            q.land(id)
            if (id != songs.last()) check("$id landed")
        }
        assertEquals(1000, last)
        // The last song's landing ends the run: no download is left to draw a bar for, and the
        // next run counts from nothing.
        assertEquals(0, q.run.endedCount)
        assertTrue(q.current.isEmpty())
    }

    @Test
    fun `three at a time, each song partway in counts its share`() {
        val q = Queue()
        q.add("a", "b", "c", "d")
        q.start("a"); q.progress("a", 50f)
        q.start("b"); q.progress("b", 25f)
        q.start("c")
        val bar = q.bar
        assertEquals(0, bar.done)
        assertEquals(4, bar.total)
        assertEquals(75, bar.position)
        assertEquals(400, bar.max)
        assertFalse(bar.indeterminate)
    }

    @Test
    fun `a song whose length is not known counts as not begun`() {
        val q = Queue()
        q.add("a", "b")
        q.start("a")
        q.progress("a", DownloadRun.UNKNOWN_PERCENT)
        q.start("b")
        q.land("b")
        val bar = q.bar
        assertEquals(1, bar.done)
        assertEquals(2, bar.total)
        assertEquals(100, bar.position)
        assertEquals(200, bar.max)
    }

    @Test
    fun `with nothing to go on yet the bar only moves, and stands still once there is`() {
        val q = Queue()
        q.add("a", "b", "c")
        assertTrue("all queued", q.bar.indeterminate)
        q.start("a")
        assertTrue("a's stream being found", q.bar.indeterminate)
        q.progress("a", 0f)
        assertFalse("a knows its length", q.bar.indeterminate)

        val other = Queue()
        other.add("a", "b")
        other.start("a")
        other.land("a")
        other.start("b")
        assertFalse("one song in", other.bar.indeterminate)
    }

    @Test
    fun `a failed song ends its part, so the bar still reaches the end`() {
        val q = Queue()
        q.add("a", "b", "c")
        q.start("a"); q.progress("a", 30f); q.fail("a")
        q.start("b"); q.land("b")
        q.start("c"); q.progress("c", 100f)
        val bar = q.bar
        assertEquals(2, bar.done)
        assertEquals(3, bar.total)
        assertEquals(bar.max, bar.position)
    }

    @Test
    fun `a song queued again after failing is to come again, not counted twice`() {
        val q = Queue()
        q.add("a", "b", "c")
        q.start("a"); q.fail("a")
        assertEquals(1, q.bar.done)
        q.add("a")
        assertEquals(0, q.bar.done)
        assertEquals(3, q.bar.total)
    }

    @Test
    fun `an album added while another downloads joins the run`() {
        val q = Queue()
        val first = ids(10)
        q.add(*first.toTypedArray())
        q.downloadFirst(first, 4)
        q.add("x1", "x2", "x3", "x4", "x5")
        assertEquals(4, q.bar.done)
        assertEquals(15, q.bar.total)
    }

    @Test
    fun `when the queue is empty the run is over, and the next one starts from nothing`() {
        val q = Queue()
        q.add("a", "b")
        q.start("a"); q.land("a")
        q.start("b"); q.land("b")
        assertEquals(0, q.run.endedCount)
        q.add("c")
        assertEquals(0, q.bar.done)
        assertEquals(1, q.bar.total)
    }

    @Test
    fun `cancelled songs are no part of the run, and the next run starts from nothing`() {
        val q = Queue()
        val songs = ids(12)
        q.add(*songs.toTypedArray())
        q.downloadFirst(songs, 5)
        q.start("song6"); q.progress("song6", 40f)
        // The notification's Cancel: every current download is removed.
        songs.drop(5).forEach { q.removing(it) }
        assertFalse("nothing left to come", q.bar.running)
        assertEquals(0, q.run.endedCount)
        songs.drop(5).forEach { q.gone(it) }
        q.add("z")
        assertEquals(0, q.bar.done)
        assertEquals(1, q.bar.total)
    }

    @Test
    fun `a song taken off the queue by hand leaves the run`() {
        val q = Queue()
        q.add("a", "b", "c")
        q.start("a"); q.land("a")
        q.removing("c"); q.gone("c")
        assertEquals(1, q.bar.done)
        assertEquals(2, q.bar.total)
        assertTrue(q.bar.running)
    }

    @Test
    fun `one song alone reads its own percent`() {
        val q = Queue()
        q.add("a")
        q.start("a"); q.progress("a", 40f)
        val bar = q.bar
        assertEquals(1, bar.total)
        assertEquals(40, bar.position)
        assertEquals(100, bar.max)
    }

    @Test
    fun `a percent out of range is held to a song's share`() {
        val bar = DownloadRun.bar(0, listOf(Item(Download.STATE_DOWNLOADING, 140f), Item(Download.STATE_DOWNLOADING, -3f)))
        assertEquals(100, bar.position)
        assertEquals(200, bar.max)
    }

    @Test
    fun `stopped songs are not counted as to come`() {
        assertFalse(DownloadRun.pending(Download.STATE_STOPPED))
        assertFalse(DownloadRun.pending(Download.STATE_REMOVING))
        assertFalse(DownloadRun.pending(Download.STATE_COMPLETED))
        assertFalse(DownloadRun.pending(Download.STATE_FAILED))
        assertTrue(DownloadRun.pending(Download.STATE_QUEUED))
        assertTrue(DownloadRun.pending(Download.STATE_DOWNLOADING))
        assertTrue(DownloadRun.pending(Download.STATE_RESTARTING))
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A playlist's parts made in one sitting (his ask of 9 Nov 2026): both ends of each song once,
 * those already split passed over, as many splits at once as the phone's memory and cores take,
 * no more windows than there is room for, and a queue that a restart of the app finds again.
 */
class PartsBatchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gb = 1L shl 30
    private val songs = listOf(
        PartsBatch.Song("a", "A", 200_000.0),
        PartsBatch.Song("b", "B", 180_000.0),
        PartsBatch.Song("a", "A again", 200_000.0),
        PartsBatch.Song("c", "C\twith a tab", -1000.0),
    )

    @Test
    fun `both ends of each song once, in the playlist's order`() {
        assertEquals(
            listOf("a tail", "a head", "b tail", "b head", "c tail", "c head"),
            PartsBatch.windows(songs).map { "${it.song.id} ${it.end}" },
        )
    }

    @Test
    fun `as many splits as the memory holds, the cores shared out between them`() {
        val per = PartsBatch.PER_SPLIT
        val reserve = PartsBatch.RESERVE
        // The S25 Ultra's 8 cores with room for three splits: three of two threads.
        assertEquals(PartsBatch.Jobs(3, 2), PartsBatch.jobs(8, reserve + 3 * per + 1, left = 40))
        // Room for one, as on a Pixel 5 with 3 GB free: one, with the editor's four threads.
        assertEquals(PartsBatch.Jobs(1, 4), PartsBatch.jobs(8, 3 * gb, left = 40))
        // Less than the reserve: still one, as the editor would split.
        assertEquals(PartsBatch.Jobs(1, 4), PartsBatch.jobs(8, gb / 2, left = 40))
        // Memory to spare: no more than one for every two cores, nor than MAX_JOBS.
        assertEquals(PartsBatch.Jobs(2, 2), PartsBatch.jobs(4, 64 * gb, left = 40))
        assertEquals(PartsBatch.Jobs(PartsBatch.MAX_JOBS, 3), PartsBatch.jobs(12, 64 * gb, left = 40))
        // Never more than the windows left.
        assertEquals(PartsBatch.Jobs(2, 4), PartsBatch.jobs(8, 64 * gb, left = 2))
        // One core: one thread.
        assertEquals(PartsBatch.Jobs(1, 1), PartsBatch.jobs(1, 64 * gb, left = 40))
    }

    @Test
    fun `windows already split are passed over, as the editor would find them`() {
        val cache = EditorCache(tmp.root, "s", "s")
        val store = PartsStore(File(tmp.root, "mix"))
        val info = WindowInfo(48_000, 2, 0.0, 1_000, 200_000.0)
        fun keep(id: String, end: String, parts: Boolean, partBytes: Long = 4_000) {
            val (pcm, json, _) = cache.files(id, end)
            cache.dir.mkdirs()
            pcm.writeBytes(ByteArray(4_000))
            json.writeText(info.toText())
            if (parts) store.files(id, end).forEach { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(partBytes.toInt())) }
        }
        keep("a", PairLoader.TAIL, parts = true)
        keep("a", PairLoader.HEAD, parts = true)
        keep("b", PairLoader.TAIL, parts = false)
        keep("b", PairLoader.HEAD, parts = true, partBytes = 3_998)
        val all = PartsBatch.windows(songs)
        val done = all.filter { PartsBatch.done(cache, store, it) }.toSet()
        // A's two ends; B's end has no parts, B's opening parts not of its length; C not read.
        assertEquals(listOf("a tail", "a head"), done.map { "${it.song.id} ${it.end}" })
        assertEquals(1, PartsBatch.songsDone(songs, done))
    }

    @Test
    fun `no more windows than there is room for, the first of the playlist first`() {
        val all = PartsBatch.windows(songs)
        val tail = PartsBatch.weight(PairLoader.TAIL)
        val head = PartsBatch.weight(PairLoader.HEAD)
        // A 72 s end and its four parts at 48 kHz in stereo: 69 MB; a 60 s opening: 58 MB.
        assertEquals(69_120_000L, tail)
        assertEquals(57_600_000L, head)
        assertEquals(all, PartsBatch.fitting(all, 64 * gb))
        assertEquals(all.take(3), PartsBatch.fitting(all, 2 * tail + head + head / 2))
        assertTrue(PartsBatch.fitting(all, tail - 1).isEmpty())
    }

    @Test
    fun `the queue keeps each song once across a restart, and lets the done ones go`() {
        val file = File(tmp.root, "mix/parts-queue.tsv")
        PartsBatch.Queue(file).add(songs.take(2))
        PartsBatch.Queue(file).add(songs.drop(2))
        val read = PartsBatch.Queue(file).read()
        assertEquals(listOf("a", "b", "c"), read.map { it.id })
        assertEquals("C with a tab", read[2].title)
        assertEquals(-1000.0, read[2].durationMs, 0.0)
        assertFalse(File(file.path + ".part").exists())
        PartsBatch.Queue(file).remove(setOf("a", "c"))
        assertEquals(listOf("b"), PartsBatch.Queue(file).read().map { it.id })
        PartsBatch.Queue(file).remove(setOf("b"))
        assertFalse(file.exists())
        assertTrue(PartsBatch.Queue(file).read().isEmpty())
    }

    @Test
    fun `one reader or splitter at a time for a window, and a wait the page's end stops`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val other = thread {
            WindowLocks.hold("a", PairLoader.TAIL, { false }) {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        // Another window is free meanwhile.
        assertEquals(1, WindowLocks.hold("a", PairLoader.HEAD, { false }) { 1 })
        val stop = AtomicBoolean(false)
        var thrown: Throwable? = null
        val waiter = thread { thrown = runCatching { WindowLocks.hold("a", PairLoader.TAIL, { stop.get() }) { 2 } }.exceptionOrNull() }
        stop.set(true)
        waiter.join(5_000)
        assertFalse(waiter.isAlive)
        assertTrue("$thrown", thrown is CancellationException)
        release.countDown()
        other.join(5_000)
        assertEquals(3, WindowLocks.hold("a", PairLoader.TAIL, { false }) { 3 })
    }

    @Test
    fun `the editor and a batch read and split a window under its lock, and the editor takes a split made meanwhile`() {
        val audio = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/EditorAudio.kt").readText()
        assertTrue("WindowLocks.hold(song.id, end, cancelled) { readWindow(song, end, cancelled) }" in audio)
        val make = audio.substringAfter("private fun makeParts(").substringBefore("\n    }\n")
        val hold = make.indexOf("WindowLocks.hold(id, end, cancelled)")
        assertTrue(make, hold > 0 && make.indexOf("store.has(id, end, samples)", hold) > hold && make.indexOf("maker.split(", hold) > hold)
    }
}

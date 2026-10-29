/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.ui.component.calculateLineProgress
import com.dd3boh.outertune.ui.component.findCurrentLineIndex
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The lyrics clock (LyricsClock): where the song is between two of the player's own readings, and
 * the line and the lit word coming on the frame they are sung on.
 *
 * The last test plays recorded runs through the lyrics view as it was and as it is: a song, a
 * player that says where it is only when its thread wakes, every 100 to 150 ms as with dynamic
 * scheduling (or every 300 to 375 ms, the longest it sleeps), and a screen drawing 60 frames a
 * second. It counts how many frames after the right one each line and each lit word came. The
 * numbers are printed for the notes of the work.
 */
class LyricsClockTest {

    private val ms = 1_000_000L

    /** Within a millisecond: the reading kept counts for half a millisecond less each second (LyricsClock.LATE_PER_SECOND). */
    private fun near(expected: Long, actual: Long, what: String = "") =
        assertTrue("$what expected about $expected, was $actual", kotlin.math.abs(actual - expected) <= 1)

    @Test
    fun `it stands where the player said until the player is seen to move`() {
        val clock = LyricsClock()
        clock.restart(positionMs = 30_000, atNanos = 0, running = true, speed = 1f)
        // Told to play, the sound starts a moment later: the clock does not run on before it.
        assertEquals(30_000L, clock.at(100 * ms))
        clock.read(30_000, 100 * ms)
        assertEquals(30_000L, clock.at(150 * ms))
        assertFalse(clock.sure(150 * ms))
        // The player moved: from here it runs on.
        clock.read(30_040, 160 * ms)
        assertEquals(30_040L, clock.at(160 * ms))
        near(30_140, clock.at(260 * ms))
    }

    @Test
    fun `it never runs ahead of the song, and soon has it to within a frame`() {
        // The song runs from 0 at 0; the player says so at each wake, 100 to 150 ms apart, and is
        // read at every frame.
        val random = Random(7)
        val wakes = generateSequence(random.nextLong(0, 125)) { it + random.nextLong(100, 151) }.takeWhile { it < 20_000 }.toList()
        fun said(t: Double) = wakes.lastOrNull { it <= t } ?: 0L
        val clock = LyricsClock()
        clock.restart(0, 0, running = true, speed = 1f)
        var frame = 0.0
        var worstAfterASecond = 0.0
        while (frame < 20_000) {
            val readAt = frame + 1.0
            clock.read(said(readAt), (readAt * ms).toLong())
            val shown = clock.at((frame * ms).toLong())
            assertTrue("ahead of the song at $frame: $shown", shown <= frame + 1)
            if (frame > 1_000) worstAfterASecond = maxOf(worstAfterASecond, frame - shown)
            frame += 1000.0 / 60
        }
        assertTrue("behind by $worstAfterASecond ms", worstAfterASecond < 1000.0 / 60)
        assertTrue(clock.sure((frame * ms).toLong()))
    }

    @Test
    fun `paused, it stands where the song stopped, and it runs at the player's speed`() {
        val clock = LyricsClock()
        clock.restart(10_000, 0, running = true, speed = 1.5f)
        clock.read(10_150, 100 * ms)
        near(10_150L + 150, clock.at(200 * ms))
        clock.restart(10_400, 300 * ms, running = false, speed = 1.5f)
        assertEquals(10_400L, clock.at(5_000 * ms))
        clock.read(10_400, 6_000 * ms)
        assertEquals(10_400L, clock.at(9_000 * ms))
        assertEquals(125L, clock.wait(9_000 * ms, untilNextMs = 50, seeking = false, refreshMs = 125))
    }

    @Test
    fun `paused from the app, it stands where it had the song, not at the player's older word`() {
        val clock = LyricsClock()
        clock.restart(10_000, 0, running = true, speed = 1f)
        clock.read(10_100, 100 * ms)
        clock.read(10_116, 116 * ms)
        // Paused at 200 ms: the player still says 10 116, its last wake's; the song was at 10 200.
        clock.restart(10_116, 200 * ms, running = false, speed = 1f, why = LyricsClock.Why.PAUSE)
        near(10_200, clock.at(300 * ms))
        clock.read(10_116, 300 * ms)
        near(10_200, clock.at(400 * ms), "the same old word does not take it back")
        clock.read(10_203, 400 * ms)
        assertEquals("the player's own word once it comes", 10_203L, clock.at(500 * ms))
    }

    @Test
    fun `waiting for data it stands where the player says, and another speed runs on from where it was`() {
        val clock = LyricsClock()
        clock.restart(10_000, 0, running = true, speed = 1f)
        clock.read(10_100, 100 * ms)
        clock.read(10_116, 116 * ms)
        // The player found its data short at 10 150 and says so at 300 ms.
        clock.restart(10_150, 300 * ms, running = false, speed = 1f, why = LyricsClock.Why.CHANGE)
        assertEquals(10_150L, clock.at(400 * ms))
        // Playing again at 500 ms, then faster from 1 000 ms on.
        clock.restart(10_150, 500 * ms, running = true, speed = 1f, why = LyricsClock.Why.CHANGE)
        clock.read(10_250, 600 * ms)
        clock.read(10_266, 616 * ms)
        val before = clock.at(1_000 * ms)
        clock.restart(10_500, 1_000 * ms, running = true, speed = 1.5f, why = LyricsClock.Why.CHANGE)
        assertEquals("no jump at the change of speed", before, clock.at(1_000 * ms))
        near(before + 150, clock.at(1_100 * ms))
    }

    @Test
    fun `a seek back it was not told of starts it again`() {
        val clock = LyricsClock()
        clock.restart(60_000, 0, running = true, speed = 1f)
        clock.read(60_100, 100 * ms)
        clock.read(20_000, 200 * ms)
        assertEquals(20_000L, clock.at(300 * ms))
        clock.read(20_120, 320 * ms)
        near(20_200, clock.at(400 * ms))
    }

    @Test
    fun `it looks at every frame just before a line changes, and while it is not yet sure`() {
        val clock = LyricsClock()
        clock.restart(0, 0, running = true, speed = 1f)
        assertEquals("not sure yet", 0L, clock.wait(0, untilNextMs = 5_000, seeking = false, refreshMs = 125))
        clock.read(10, 10 * ms)
        clock.read(25, 25 * ms)
        assertTrue(clock.sure(30 * ms))
        assertEquals(125L, clock.wait(30 * ms, untilNextMs = 5_000, seeking = false, refreshMs = 125))
        assertEquals(40L, clock.wait(30 * ms, untilNextMs = 100, seeking = false, refreshMs = 125))
        assertEquals(0L, clock.wait(30 * ms, untilNextMs = 50, seeking = false, refreshMs = 125))
        assertEquals("the seek bar is followed as before", 125L, clock.wait(30 * ms, untilNextMs = 50, seeking = true, refreshMs = 125))
    }

    @Test
    fun `the moments a line's look changes at`() {
        val lines = listOf(line(1_000, 2_000), line(2_050, 4_000), line(6_000, 7_000))
        val moments = LyricsMoments(lines)
        assertEquals(0, moments.passed(999))
        assertEquals(1, moments.passed(1_000))
        // The first line is done with from 101 ms past its end, after the second has started.
        assertEquals(2, moments.passed(2_100))
        assertEquals(3, moments.passed(2_101))
        assertEquals(50L, moments.untilNext(2_000))
        assertEquals(1L, moments.untilNext(2_100))
        assertEquals(Long.MAX_VALUE, moments.untilNext(7_101))
    }

    /**
     * The view as it was, and as it is now, over recorded runs. As it was: the player read every
     * 125 ms (every 33 ms word by word) and the line taken from that, the lit words eased towards
     * each read over 100 ms. As it is: the clock, read and asked as the view does now.
     */
    @Test
    fun `lines and lit words change within one frame of their timestamps`() {
        val report = StringBuilder()
        for ((name, wakeFrom, wakeTo) in listOf(Triple("wakes 100-150 ms", 100L, 151L), Triple("wakes 300-375 ms", 300L, 376L))) {
            for (seed in 1..5) {
                val run = Run(seed, wakeFrom, wakeTo)
                // Word by word off: the player was read every 125 ms. On: every 33 ms, by default.
                val linesBefore = run.before(readEvery = 125.0, eased = false)
                val wordsBefore = run.before(readEvery = 33.0, eased = true)
                val linesAfter = run.after(wordByWord = false, refreshMs = 125)
                val wordsAfter = run.after(wordByWord = true, refreshMs = 33)
                report.append("$name, seed $seed\n")
                report.append("  before: lines ${describe(linesBefore.lines)}; word by word, lines ${describe(wordsBefore.lines)}, words ${describe(wordsBefore.words)}\n")
                report.append("  after:  lines ${describe(linesAfter.lines)}; word by word, lines ${describe(wordsAfter.lines)}, words ${describe(wordsAfter.words)}\n")
                for ((what, late) in listOf("lines" to linesAfter.lines, "lines word by word" to wordsAfter.lines, "words" to wordsAfter.words)) {
                    assertTrue("$name seed $seed: $what came early: $late", late.all { it >= 0 })
                    assertTrue("$name seed $seed: $what late by ${late.max()} frames", late.all { it <= 1 })
                }
            }
        }
        println(report)
        File("build/lyrics-clock-runs.txt").writeText(report.toString())
    }

    // What follows plays a run.

    private class Lateness(val lines: List<Int>, val words: List<Int>)

    private fun describe(frames: List<Int>): String =
        if (frames.isEmpty()) "-" else "median ${frames.sorted()[frames.size / 2]}, at most ${frames.max()} frames late " +
            "(%.0f ms on average)".format(java.util.Locale.ROOT, frames.average() * FRAME_MS)

    /**
     * One song: lines of 3 to 8 words, each word 150 to 700 ms, played from its start at speed 1
     * with no move of the timing, a player that says where it is only at its wakes, and frames at
     * 60 a second. Times in ms from the start of the song.
     */
    private class Run(seed: Int, wakeFrom: Long, wakeTo: Long) {
        val random = Random(seed)
        val lines: List<LyricLine> = buildList {
            var at = 2_000L
            repeat(16) {
                val words = mutableListOf<SemanticLyrics.Word>()
                val text = StringBuilder()
                repeat(random.nextInt(3, 9)) { w ->
                    val length = random.nextLong(150, 701)
                    if (w > 0) text.append(' ')
                    val from = text.length
                    text.append("word$w")
                    words.add(SemanticLyrics.Word(at.toULong()..(at + length).toULong(), from until text.length, false))
                    at += length + random.nextLong(0, 81)
                }
                add(LyricLine(text.toString(), words.first().timeRange.first, words.last().timeRange.last, words, null, false))
                at += random.nextLong(0, 1_501)
            }
        }
        val end = lines.last().end.toLong() + 1_000
        val wakes: List<Long> = generateSequence(random.nextLong(0, wakeFrom)) { it + random.nextLong(wakeFrom, wakeTo) }.takeWhile { it < end }.toList()
        val firstFrame = random.nextDouble(0.0, FRAME_MS)
        val frames: List<Double> = generateSequence(firstFrame) { it + FRAME_MS }.takeWhile { it < end }.toList()

        /** What the player says at [t]: where the song was at its last wake. */
        fun said(t: Double): Long {
            var low = 0
            var high = wakes.size
            while (low < high) {
                val middle = (low + high) / 2
                if (wakes[middle] <= t) low = middle + 1 else high = middle
            }
            return if (low == 0) 0L else wakes[low - 1]
        }

        /** The first frame at or after [t]: the one a change at [t] belongs on. */
        fun frameOf(t: Double): Int = ceil((t - firstFrame) / FRAME_MS).toInt().coerceAtLeast(0)

        /** How many frames late each line's start and each word's start (after a line's first) came, from what each frame showed. */
        fun lateness(lineShown: IntArray, progressShown: Array<FloatArray?>?): Lateness {
            val lineLate = lines.indices.map { i ->
                val due = frameOf(lines[i].start.toDouble())
                val shown = (0 until frames.size).first { lineShown[it] >= i }
                shown - due
            }
            val wordLate = if (progressShown == null) emptyList() else lines.indices.flatMap { i ->
                val words = lines[i].words!!
                (1 until words.size).map { j ->
                    // The lit part reaches the start of word j as word j-1 ends (calculateLineProgress):
                    // the moment the song itself gives it that much, to the ms.
                    val at = j.toFloat() / words.size
                    val dueMs = generateSequence(lines[i].start.toLong()) { it + 1 }.first { calculateLineProgress(lines[i], it) >= at - 1e-4f }
                    val due = frameOf(dueMs.toDouble())
                    val shown = (0 until frames.size).first { f -> (progressShown[f]?.get(i) ?: 0f) >= at - 1e-4f }
                    shown - due
                }
            }
            return Lateness(lineLate, wordLate)
        }

        /**
         * The view before the clock: the player read every [readEvery] ms, the line taken from
         * that read and shown on the next frame. With [eased], the lit words too: each read the
         * target of a 100 ms linear ease, started on the next frame from where the last had got to.
         */
        fun before(readEvery: Double, eased: Boolean): Lateness {
            val lineShown = IntArray(frames.size)
            val progress = arrayOfNulls<FloatArray>(frames.size)
            val shown = FloatArray(lines.size)
            val from = FloatArray(lines.size)
            val target = FloatArray(lines.size)
            val startedAt = DoubleArray(lines.size) { -1.0 }
            var read = random.nextDouble(0.0, readEvery)
            var index = -1
            for (f in frames.indices) {
                val frame = frames[f]
                for (i in lines.indices) {
                    if (startedAt[i] >= 0) shown[i] = from[i] + (target[i] - from[i]) * ((frame - startedAt[i]) / 100.0).coerceIn(0.0, 1.0).toFloat()
                }
                while (read <= frame) {
                    val at = said(read)
                    index = findCurrentLineIndex(lines, at)
                    if (eased) for (i in lines.indices) {
                        val now = calculateLineProgress(lines[i], at).coerceIn(0f, 1f)
                        if (now != target[i]) {
                            from[i] = shown[i]
                            target[i] = now
                            startedAt[i] = frame
                        }
                    }
                    read += readEvery
                }
                lineShown[f] = index
                progress[f] = shown.copyOf()
            }
            return lateness(lineShown, if (eased) progress else null)
        }

        /**
         * The view with the clock, as Lyrics does it: the loop's look at each of its wakes, every
         * frame where the clock says so, and with [wordByWord] each line lit word by word read at
         * every frame. A look made between frames shows on the next one; one made in a frame's
         * callback, on that frame.
         */
        fun after(wordByWord: Boolean, refreshMs: Long): Lateness {
            val clock = LyricsClock()
            val moments = LyricsMoments(lines)
            clock.restart(0, 0, running = true, speed = 1f)
            var line = -1
            var currentPos = 0L
            var drawnPos = 0L
            fun look(readAt: Double, drawnAt: Double): Long {
                clock.read(said(readAt), (readAt * MS).toLong())
                val at = LyricsTiming.lyricsPosition(clock.at((drawnAt * MS).toLong()), 0)
                line = findCurrentLineIndex(lines, at)
                if (moments.passed(at) != moments.passed(currentPos)) currentPos = at
                drawnPos = at
                return clock.wait((readAt * MS).toLong(), moments.untilNext(at), false, refreshMs)
            }
            fun lit(i: Int) = wordByWord && currentPos.toULong() in lines[i].start..lines[i].end + 100.toULong()
            val lineShown = IntArray(frames.size)
            val progress = arrayOfNulls<FloatArray>(frames.size)
            var byFrame = false
            var wakeAt = 0.0
            var anyLit = false
            for (f in frames.indices) {
                val frame = frames[f]
                // Wakes of the loop between the last frame and this one.
                while (!byFrame && wakeAt <= frame) {
                    val wait = look(wakeAt, wakeAt)
                    byFrame = wait == 0L
                    if (!byFrame) wakeAt += wait + 1.0
                }
                // This frame's callbacks: a line lit word by word, and the loop when it goes by frames.
                if (anyLit) {
                    clock.read(said(frame + 1.0), ((frame + 1.0) * MS).toLong())
                    drawnPos = LyricsTiming.lyricsPosition(clock.at((frame * MS).toLong()), 0)
                }
                if (byFrame) {
                    val wait = look(frame + 1.0, frame)
                    byFrame = wait == 0L
                    if (!byFrame) wakeAt = frame + 1.0 + wait
                }
                // Composed, then drawn.
                lineShown[f] = line
                anyLit = lines.indices.any { lit(it) }
                progress[f] = FloatArray(lines.size) { i -> if (lit(i)) calculateLineProgress(lines[i], drawnPos).coerceIn(0f, 1f) else 0f }
            }
            return lateness(lineShown, if (wordByWord) progress else null)
        }
    }

    private fun line(start: Long, end: Long) = LyricLine("line", start.toULong(), end.toULong(), null, null, false)

    companion object {
        const val FRAME_MS = 1000.0 / 60
        const val MS = 1_000_000.0
    }
}

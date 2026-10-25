/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lines of the lyrics are composed when how one of them looks changes, and not at every read
 * of the song: the line being sung, the ones done with, the one lit word by word. That line is lit
 * as it is drawn, at each frame, at where the song is then, and its text is measured once. A JVM
 * test cannot compose the view, so its wiring is read from the source, comments taken out, as
 * LyricsOpeningTest does; LyricsClockTest plays the timing itself.
 */
class LyricsLinesTest {

    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    /** The content of one line of the list. */
    private val line = source.substringAfter("contentType = { _, _ -> LYRICS_LINE },", missingDelimiterValue = "")
        .substringBefore("if (lyricsModel == uninitializedLyric) {")

    @Test
    fun `where the lines are is written only when a line's look changes`() {
        val loop = source.substringAfter("LaunchedEffect(lyricsModel, lifecycleOwner) {", missingDelimiterValue = "")
            .substringBefore("\n    }\n")
        assertTrue("the loop could not be found", loop.isNotEmpty())
        assertTrue(loop.contains("if (moments.passed(at) != moments.passed(currentPos)) currentPos = at"))
        // Written in that loop and nowhere else in it, apart from the read made at once.
        assertEquals(loop, 2, Regex("""\bcurrentPos = """).findAll(loop).count())
    }

    @Test
    fun `a line reads where the lines are only through what it shows of it`() {
        assertTrue("the line's content could not be found", line.length > 1000)
        assertTrue(line.contains("val inLine by remember(item) { derivedStateOf { currentPos.toULong() in item.start..item.end + 100.toULong() } }"))
        assertTrue(line.contains("val doneWith by remember(item) { derivedStateOf { currentPos.toULong() > (item.end + 100.toULong()) } }"))
        val reads = Regex("""\bcurrentPos\b(?!\s*=[^=])""").findAll(line).count()
        assertEquals("currentPos is read outside the two derived states", 2, reads)
    }

    @Test
    fun `the line lit word by word is lit as it is drawn, and measured once`() {
        assertTrue(line.contains("progress = { calculateLineProgress(it, drawnPos) },"))
        assertTrue(line.contains("withFrameNanos { frame -> drawnPos = lyricsAt(frame, sliderPositionProvider()) }"))
        assertTrue(line.contains("val lyricLines = remember(item, style, maxW, textMeasurer, density) {"))
        val reveal = source.substringAfter("fun HorizontalReveal(", missingDelimiterValue = "").substringBefore("\n}\n")
        assertTrue(reveal.contains("progress: () -> Float,"))
        assertTrue("read where it is drawn", reveal.contains(".drawWithContent {") && reveal.contains("progress().coerceIn(0f, 1f)"))
        assertFalse("eased behind the song", reveal.contains("animateFloatAsState"))
        // The words light by the song as it plays; still where it stopped while paused.
        assertTrue(line.contains("if (!playerConnection.isPlaying.value) playerConnection.isPlaying.first { it }"))
    }

    @Test
    fun `a long line is cut into the same lines with the same words as before`() {
        // The words of "one two three four five six", timed one after the other, cut as
        // splitTextToLines would cut them on a narrow screen.
        val words = (0 until 6).map { i -> SemanticLyrics.Word((1_000L + i * 400).toULong()..(1_300L + i * 400).toULong(), 0..0, false) }
        val item = LyricLine("one two three four five six", 1_000UL, 3_300UL, words.toMutableList(), null, false)
        val cut = karaokeLines(item, listOf("one two three", "four five six"))
        assertEquals(listOf("one two three", "four five six"), cut.map { it.text })
        assertEquals(listOf(1_000UL, 2_200UL), cut.map { it.start })
        assertEquals(listOf(2_100UL, 3_300UL), cut.map { it.end })
        assertEquals(listOf(3, 3), cut.map { it.words!!.size })
        // One line, or a line whose timing ran out, as before: whole, or with the parent's range.
        assertEquals(listOf(item), karaokeLines(item, listOf("one two three four five six")))
        val short = item.copy(words = words.take(2).toMutableList())
        assertEquals(listOf(1_000UL, 1_000UL), karaokeLines(short, listOf("one two three", "four five six")).map { it.start })
    }

    @Test
    fun `the lines are one kind of item, and a pause, a seek or a change of speed starts the clock again`() {
        assertTrue(source.contains("contentType = { _, _ -> LYRICS_LINE },"))
        assertTrue(source.contains("events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION) -> LyricsClock.Why.JUMP"))
        assertTrue(source.contains("events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !player.playWhenReady -> LyricsClock.Why.PAUSE"))
        assertTrue(source.contains("Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,"))
        assertTrue(source.contains("clock.restart(player.currentPosition, System.nanoTime(), player.isPlaying, player.playbackParameters.speed, why)"))
    }
}

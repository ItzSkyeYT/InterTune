/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.playback.MusicLevels
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The looks of the line sung word by word that are being compared (LyricsWordsLook): their numbers
 * are played here. A JVM test cannot compose the view, so where they are read is taken from the
 * source, comments taken out, as LyricsBlurTest does. How they look is in the recordings and the
 * sheet of the emulator (experiments/features/lyrics-words-better).
 */
class LyricsWordsTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    private fun word(chars: IntRange, ms: LongRange, rtl: Boolean = false) =
        SemanticLyrics.Word(ms.first.toULong()..ms.last.toULong(), chars, rtl)

    private fun piece(startMs: Long, endMs: Long) = LyricsWords.Piece(0, 4, startMs, endMs, false)

    @Test
    fun `the words of a line come in the order they stand in, each inside the text`() {
        val text = "one two three"
        val words = listOf(word(8..12, 3_000L..3_400L), word(0..2, 1_000L..1_300L), word(4..6, 2_000L..2_500L))
        val pieces = LyricsWords.pieces(text, words)
        assertEquals(listOf(0 to 3, 4 to 7, 8 to 13), pieces.map { it.from to it.to })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), pieces.map { it.startMs })
        assertEquals(listOf("one", "two", "three"), pieces.map { text.substring(it.from, it.to) })
    }

    @Test
    fun `a word the text does not hold, or one inside the word before, is left out`() {
        val words = listOf(word(0..2, 0L..100L), word(2..5, 100L..200L), word(40..44, 300L..400L), word(4..30, 500L..600L))
        val pieces = LyricsWords.pieces("one two", words)
        // The second starts inside the first; the third is past the text; the last is cut at its end.
        assertEquals(listOf(0 to 3, 4 to 7), pieces.map { it.from to it.to })
        assertTrue(LyricsWords.pieces("one two", null).isEmpty())
        assertTrue(LyricsWords.pieces("one two", emptyList()).isEmpty())
    }

    @Test
    fun `a word is lit evenly over the time it is sung`() {
        val p = piece(1_000, 2_000)
        assertEquals(0f, LyricsWords.sung(p, 900), 0f)
        assertEquals(0f, LyricsWords.sung(p, 1_000), 0f)
        assertEquals(0.25f, LyricsWords.sung(p, 1_250), 0.001f)
        assertEquals(0.5f, LyricsWords.sung(p, 1_500), 0.001f)
        assertEquals(1f, LyricsWords.sung(p, 2_000), 0f)
        assertEquals(1f, LyricsWords.sung(p, 9_000), 0f)
        // A word with no length is lit at once, and nothing is divided by nothing.
        assertEquals(0f, LyricsWords.sung(piece(1_000, 1_000), 1_000), 0f)
        assertEquals(1f, LyricsWords.sung(piece(1_000, 1_000), 1_001), 0f)
    }

    @Test
    fun `the soft edge starts on the word's first letter and has left its last when it is all sung`() {
        val (left, right, soft) = Triple(100f, 300f, 20f)
        // Nothing sung: the edge's far end is on the first letter, so nothing of the word is lit.
        assertEquals(left, LyricsWords.edge(left, right, 0f, soft, rtl = false), 0f)
        // All sung: the edge's near end, one soft width back, is on the last letter.
        assertEquals(right, LyricsWords.edge(left, right, 1f, soft, rtl = false) - soft, 0.001f)
        // And it moves on evenly between the two.
        val steps = (0..10).map { LyricsWords.edge(left, right, it / 10f, soft, rtl = false) }
        for (i in 1..10) assertEquals(22f, steps[i] - steps[i - 1], 0.01f)
        // Read from the right, the same from the other side.
        assertEquals(right, LyricsWords.edge(left, right, 0f, soft, rtl = true), 0f)
        assertEquals(left, LyricsWords.edge(left, right, 1f, soft, rtl = true) + soft, 0.001f)
    }

    @Test
    fun `B, a word rises as it starts, stays up while the line is sung, and is down right after it`() {
        val p = piece(1_000, 1_400)
        val lineEnd = 5_000L
        assertEquals(0f, LyricsWords.rise(p, 1_000, lineEnd), 0f)
        val way = (1..12).map { LyricsWords.rise(p, 1_000 + it * 20L, lineEnd) }
        for (i in 1 until way.size) assertTrue("never back down on its way up: $way", way[i] >= way[i - 1])
        assertEquals(1f, LyricsWords.rise(p, 1_000 + LyricsWords.RISE_MS, lineEnd), 0f)
        // Up for as long as the line is sung, well after the word itself is over.
        assertEquals(1f, LyricsWords.rise(p, 4_990, lineEnd), 0f)
        assertEquals(1f, LyricsWords.rise(p, lineEnd, lineEnd), 0f)
        assertEquals(0.5f, LyricsWords.rise(p, lineEnd + LyricsWords.SETTLE_MS / 2, lineEnd), 0.01f)
        assertEquals(0f, LyricsWords.rise(p, lineEnd + LyricsWords.SETTLE_MS, lineEnd), 0f)
    }

    @Test
    fun `B, only a word held long swells and glows, from nothing to its fullest and back`() {
        val short = piece(1_000, 1_000 + LyricsWords.HELD_MS - 1)
        val held = piece(1_000, 1_000 + LyricsWords.HELD_MS)
        assertFalse(LyricsWords.held(short))
        assertTrue(LyricsWords.held(held))
        for (at in 900L..2_300L step 50) assertEquals(0f, LyricsWords.swell(short, at), 0f)
        assertEquals(0f, LyricsWords.swell(held, held.startMs), 0f)
        assertEquals(1f, LyricsWords.swell(held, (held.startMs + held.endMs) / 2), 0.001f)
        assertEquals(0f, LyricsWords.swell(held, held.endMs), 0f)
        assertEquals(0f, LyricsWords.swell(held, held.endMs + 500), 0f)
    }

    @Test
    fun `C, a word nods once when it is sung and then rests`() {
        val p = piece(1_000, 3_000)
        assertEquals(0f, LyricsWords.nod(p, 1_000), 0f)
        assertEquals(1f, LyricsWords.nod(p, 1_000 + LyricsWords.NOD_UP_MS), 0.001f)
        val over = 1_000 + LyricsWords.NOD_UP_MS + LyricsWords.NOD_DOWN_MS
        // Nothing beats by itself: once down it stays down, however long the word is held.
        for (at in over..over + 4_000 step 40) assertEquals("at $at", 0f, LyricsWords.nod(p, at), 0f)
        for (at in 1_000L..over step 10) assertTrue(LyricsWords.nod(p, at) in 0f..1f)
    }

    @Test
    fun `C, the nod is as strong as the music is when the word starts`() {
        fun levels(bass: Float, mid: Float, presence: Float) = FloatArray(MusicLevels.VALUES).also {
            it[MusicLevels.BASS] = bass
            it[MusicLevels.MID] = mid
            it[MusicLevels.PRESENCE] = presence
        }
        assertEquals(LyricsWords.NOD_LEAST, LyricsWords.weight(levels(0f, 0f, 1f)), 0f)
        assertEquals(1f, LyricsWords.weight(levels(1f, 0.2f, 1f)), 0f)
        assertEquals(1f, LyricsWords.weight(levels(0.1f, 1f, 1f)), 0f)
        // A loud moment of a quiet passage counts for as much as the passage does in its song.
        assertTrue(LyricsWords.weight(levels(1f, 1f, 0.3f)) < LyricsWords.weight(levels(1f, 1f, 0.9f)))

        val pieces = listOf(piece(1_000, 1_500), piece(2_000, 2_500), piece(3_000, 3_500))
        val weights = FloatArray(3) { -1f }
        var asked = 0
        // Before the first word nothing is weighed, and the music is not asked.
        LyricsWords.weigh(pieces, weights, 500) { asked++; levels(1f, 1f, 1f) }
        assertEquals(0, asked)
        assertTrue(weights.all { it < 0f })
        // The first word as it starts: weighed once, and not again on the frames after.
        LyricsWords.weigh(pieces, weights, 1_010) { asked++; levels(1f, 1f, 1f) }
        LyricsWords.weigh(pieces, weights, 1_030) { asked++; levels(0f, 0f, 1f) }
        assertEquals(1, asked)
        assertEquals(1f, weights[0], 0f)
        // No levels to go by (nothing measured, audio that cannot be read): the word does not nod.
        LyricsWords.weigh(pieces, weights, 2_005) { null }
        assertEquals(0f, weights[1], 0f)
        // Come upon after a seek, its moment past: it does not nod late.
        LyricsWords.weigh(pieces, weights, 3_000 + LyricsWords.NOD_UP_MS + LyricsWords.NOD_DOWN_MS + 1) { levels(1f, 1f, 1f) }
        assertEquals(0f, weights[2], 0f)
    }

    @Test
    fun `C, a line under the sung one follows the view later than the line above it, and arrives`() {
        val ms = 1_000_000L
        val carry = LyricsCarry()
        assertEquals(0f, carry.lag(1, 0), 0f)
        assertFalse(carry.busy(0))
        // The view moves on by 100 pixels over 200 ms, 10 pixels every 20 ms.
        for (step in 1..10) carry.moved(step * 20 * ms, 10f)
        val now = 200 * ms
        assertEquals("the sung line and those above move with the view", 0f, carry.lag(0, now), 0f)
        assertEquals(0f, carry.lag(-3, now), 0f)
        val lags = (1..LyricsWords.STEPS).map { carry.lag(it, now) }
        assertTrue("each is held back: $lags", lags.all { it > 0f })
        for (i in 1 until lags.size) assertTrue("further down, further back: $lags", lags[i] > lags[i - 1])
        // What the view moved in the 45 ms before: 22.5 pixels at 10 every 20 ms.
        assertEquals(22.5f, lags[0], 0.6f)
        assertEquals("past the last step they move as one", lags.last(), carry.lag(LyricsWords.STEPS + 4, now), 0f)
        assertTrue(carry.busy(now))
        // Nothing moves any more: each arrives after its own delay, the nearest first.
        assertEquals(0f, carry.lag(1, now + LyricsWords.STEP_MS * ms), 0.01f)
        assertTrue(carry.lag(3, now + LyricsWords.STEP_MS * ms) > 0f)
        val settled = now + LyricsWords.STEPS * LyricsWords.STEP_MS * ms
        for (steps in 1..LyricsWords.STEPS) assertEquals(0f, carry.lag(steps, settled), 0.01f)
        assertFalse(carry.busy(settled))
    }

    @Test
    fun `C, a move back up the lyrics never pushes a line up into the one above it`() {
        val ms = 1_000_000L
        val carry = LyricsCarry()
        for (step in 1..10) carry.moved(step * 20 * ms, -30f)
        for (steps in 1..LyricsWords.STEPS) assertEquals(0f, carry.lag(steps, 200 * ms), 0f)
        assertFalse(carry.busy(200 * ms))
        // And it keeps no more than it has room for, however long the view moves.
        for (step in 11..400) carry.moved(step * 20 * ms, 5f)
        assertTrue(carry.lag(2, 400 * 20 * ms) > 0f)
    }

    @Test
    fun `a look is one of three by its letter, and anything else is the app's own way`() {
        assertEquals(listOf("A", "B", "C"), LyricsWordsLook.entries.map { it.name })
        for (look in LyricsWordsLook.entries) {
            assertEquals(look, LyricsWordsLook.of(look.name))
            assertTrue("its name in the developer options starts with its letter", look.label.startsWith(look.name + ": "))
        }
        assertNull(LyricsWordsLook.of(""))
        assertNull(LyricsWordsLook.of(null))
        assertNull(LyricsWordsLook.of("b"))
        assertNull(LyricsWordsLook.of("D"))
    }

    @Test
    fun `a trial from the command line names a look and the word by word switch`() {
        assertNull(LyricsWordsLook.trial(null))
        assertEquals(LyricsWordsLook.Trial("B", true), LyricsWordsLook.trial("b,words"))
        assertEquals(LyricsWordsLook.Trial("", true), LyricsWordsLook.trial(" Words , TODAY "))
        assertEquals(LyricsWordsLook.Trial("C", null), LyricsWordsLook.trial("c"))
        assertEquals(LyricsWordsLook.Trial(null, false), LyricsWordsLook.trial("lines,nonsense"))
        for (letter in listOf("a", "b", "c")) {
            assertEquals(letter.uppercase(), LyricsWordsLook.of(LyricsWordsLook.trial(letter)!!.look)!!.name)
        }
    }

    @Test
    fun `only the debug app has the looks, and they have no words of their own`() {
        val flag = Regex("""val LYRICS_WORDS_LOOKS = (.+)""").find(code("constants/Unreleased.kt"))?.groupValues?.get(1)?.trim()
        assertEquals("BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == \"debug\"", flag)
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { Regex("""Unreleased\.LYRICS_WORDS_LOOKS\b""").containsMatchIn(code(it.relativeTo(sources).path)) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf("MainActivity.kt", "ui/component/Lyrics.kt", "ui/screens/settings/fragments/DeveloperFrag.kt"),
            readers,
        )
        // The choice is read behind the flag, its row is a debug build's, and so is the trial's way in.
        val lyrics = code("ui/component/Lyrics.kt")
        assertTrue(lyrics.contains("val wordsLook = if (Unreleased.LYRICS_WORDS_LOOKS) LyricsWordsLook.of(wordsLookStored) else null"))
        assertTrue(code("ui/screens/settings/fragments/DeveloperFrag.kt").contains("if (BuildConfig.DEBUG && Unreleased.LYRICS_WORDS_LOOKS) {\n            LyricsWordsLookRow()"))
        assertTrue(code("MainActivity.kt").contains("if (Unreleased.LYRICS_WORDS_LOOKS) setLyricsWordsTrial(intent)"))
        // The stored choice is read nowhere else, so nothing but the flag's readers can act on it.
        val keyReaders = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "PreferenceKeys.kt" }
            .filter { Regex("""\bLyricsWordsLookKey\b""").containsMatchIn(code(it.relativeTo(sources).path)) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(readers, keyReaders)
        // Nothing for testers or translators.
        for (texts in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            assertFalse(texts, Regex("""name="lyrics_words""").containsMatchIn(File(texts).readText()))
        }
    }

    @Test
    fun `with no look picked the line is drawn the app's own way, and every part of look C hangs on it`() {
        val lyrics = code("ui/component/Lyrics.kt")
        // A look draws the line only when one is picked and the line has word times; else the app's own.
        assertTrue(lyrics.contains("if (wordsLook != null && pieces.isNotEmpty()) LyricsWordsLine("))
        assertTrue(lyrics.contains(") else lyricLines.forEach {"))
        assertTrue(lyrics.contains("progress = { calculateLineProgress(it, drawnPos) },"))
        assertTrue(lyrics.contains("val pieces = remember(item, wordsLook) { if (wordsLook == null) emptyList() else LyricsWords.pieces(item.text, item.words) }"))
        // C's size, its lines that follow later, its measuring of the music and its nod: all only with C picked.
        assertTrue(lyrics.contains("val carried = wordsLook == LyricsWordsLook.C && isSynced && lyricsFancy && !context.isPowerSaver()"))
        assertTrue(lyrics.contains("val aroundSize = if (!carried) null else animateFloatAsState("))
        assertTrue(lyrics.contains("if (aroundSize == null) Modifier else Modifier.graphicsLayer {"))
        assertEquals(2, Regex("""carry\.takeIf \{ carried \}""").findAll(lyrics).count())
        assertTrue(lyrics.contains("if (carried) tap.watch()"))
        assertTrue(lyrics.contains("onDispose { if (carried) tap.unwatch() }"))
        assertTrue(lyrics.contains("val nods = remember(pieces, wordsLook) { if (wordsLook == LyricsWordsLook.C) FloatArray(pieces.size) { -1f } else null }"))
        // Without a carry the view glides as it always did.
        val glide = lyrics.substringAfter("private suspend fun LazyListState.glideToItem(").substringBefore("\n}\n")
        assertTrue(glide.contains("if (carry == null) {\n        animate(0f, (item.offset + offset).toFloat(), animationSpec = spring()) { value, _ ->\n            moved += dispatchRawDelta(value - moved)\n        }\n        return\n    }"))
    }
}

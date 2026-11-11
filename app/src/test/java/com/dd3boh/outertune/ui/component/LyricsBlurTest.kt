/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Progressive blur on the lyrics (LyricsBlur): the line being sung sharp, each line further from it
 * a little more out of focus, and only while the view follows the song. Its numbers are played
 * here; a JVM test cannot compose the view, so where it is read is taken from the source, comments
 * taken out, as LyricsLinesTest does. How it looks is in the pictures of the emulator.
 */
class LyricsBlurTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    private val font = 58f

    @Test
    fun `the line being sung is sharp, and each step away is more out of focus up to a most`() {
        for (strength in LyricsBlur.Strength.entries) {
            val radii = (0..9).map { strength.radius(it, font) }
            assertEquals("$strength", 0f, radii[0])
            assertTrue("$strength: the line next to it is already a little out of focus: $radii", radii[1] >= 1f)
            for (step in 1..5) assertTrue("$strength at $step: $radii", radii[step] > radii[step - 1])
            assertEquals("$strength stops growing: $radii", radii[5], radii[9])
        }
    }

    @Test
    fun `soft is under medium, and medium under strong, at every step`() {
        for (step in 1..7) {
            val (soft, medium, strong) = LyricsBlur.Strength.entries.map { it.radius(step, font) }
            assertTrue("step $step: $soft $medium $strong", soft < medium && medium < strong)
        }
    }

    @Test
    fun `a radius is whole pixels and goes with the size of the lyrics`() {
        for (strength in LyricsBlur.Strength.entries) {
            for (step in 0..6) {
                val radius = strength.radius(step, font)
                assertEquals("$strength at $step", radius, Math.round(radius).toFloat())
            }
            // Twice the size, twice as far out of focus, to a pixel's rounding.
            assertEquals(strength.radius(3, font) * 2, strength.radius(3, font * 2), 1f)
            // However far the line is, never past two fifths of the lyrics' size.
            assertTrue(strength.radius(9, 200f) <= 0.4f * 200f)
        }
    }

    @Test
    fun `a translation stands with its line`() {
        val steps = LyricsBlur.steps(listOf(false, true, false, true, false))
        assertEquals(listOf(0, 0, 1, 1, 2), steps.toList())
        // The second pair is being sung: both of its rows are sharp, the pairs round it one step away.
        assertEquals(listOf(1, 1, 0, 0, 1), steps.indices.map { LyricsBlur.distance(steps, it, 2) })
        // No translations: a row is a line.
        val plain = LyricsBlur.steps(List(4) { false })
        assertEquals(listOf(2, 1, 0, 1), plain.indices.map { LyricsBlur.distance(plain, it, 2) })
        // Lyrics that open on a row marked as a translation still count from their first row.
        assertEquals(listOf(0, 1, 1), LyricsBlur.steps(listOf(true, false, true)).toList())
        assertEquals(0, LyricsBlur.steps(emptyList()).size)
    }

    @Test
    fun `before the first line the first line is the sharp one, and a row that is not there is not blurred`() {
        val steps = LyricsBlur.steps(List(5) { false })
        assertEquals(listOf(0, 1, 2, 3, 4), steps.indices.map { LyricsBlur.distance(steps, it, -1) })
        assertEquals(0, LyricsBlur.distance(steps, 9, 2))
        assertEquals(0, LyricsBlur.distance(steps, 1, 9))
        assertEquals(0, LyricsBlur.distance(IntArray(0), 0, -1))
    }

    @Test
    fun `it is off until a strength is stored`() {
        assertNull(LyricsBlur.strengthOf(""))
        assertNull(LyricsBlur.strengthOf(null))
        assertNull(LyricsBlur.strengthOf("something else"))
        assertEquals(LyricsBlur.Strength.MEDIUM, LyricsBlur.strengthOf("MEDIUM"))
        assertEquals(listOf("SOFT", "MEDIUM", "STRONG"), LyricsBlur.Strength.entries.map { it.name })
    }

    @Test
    fun `a trial from the command line says how strong, which way and whether word by word`() {
        assertNull(LyricsBlur.trial(null))
        assertEquals(LyricsBlur.Trial("MEDIUM", LyricsBlur.How.LAYER, true), LyricsBlur.trial("medium,layer,words"))
        assertEquals(LyricsBlur.Trial("STRONG", LyricsBlur.How.GLYPHS, false), LyricsBlur.trial(" Strong , GLYPHS, lines"))
        // Off is stored as nothing chosen, and what a trial does not name is left as it is.
        assertEquals(LyricsBlur.Trial("", null, null), LyricsBlur.trial("off"))
        assertEquals(LyricsBlur.Trial(null, null, true), LyricsBlur.trial("words"))
        assertEquals(LyricsBlur.Trial(null, null, null), LyricsBlur.trial("nothing it knows"))
        for (stored in listOf("soft", "medium", "strong")) {
            assertEquals(stored.uppercase(), LyricsBlur.strengthOf(LyricsBlur.trial(stored)!!.strength)!!.name)
        }
    }

    @Test
    fun `only the debug app has it, and it has no words of its own`() {
        val flag = Regex("""val LYRICS_BLUR = (.+)""").find(code("constants/Unreleased.kt"))?.groupValues?.get(1)?.trim()
        assertEquals("BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == \"debug\"", flag)
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { Regex("""Unreleased\.LYRICS_BLUR\b""").containsMatchIn(code(it.relativeTo(sources).path)) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf("MainActivity.kt", "ui/component/Lyrics.kt", "ui/screens/settings/fragments/DeveloperFrag.kt"),
            readers,
        )
        // The choice is read behind the flag, its row is a debug build's, and so is the trial's way in.
        assertTrue(code("ui/component/Lyrics.kt").contains("val blur = if (Unreleased.LYRICS_BLUR && LyricsBlur.canDraw(blurHow)) LyricsBlur.strengthOf(blurStored) else null"))
        assertTrue(code("ui/screens/settings/fragments/DeveloperFrag.kt").contains("if (BuildConfig.DEBUG && Unreleased.LYRICS_BLUR) {\n            LyricsBlurRow()"))
        assertTrue(code("MainActivity.kt").contains("if (Unreleased.LYRICS_BLUR) setLyricsBlurTrial(intent)"))
        // Nothing for testers or translators yet.
        for (texts in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            assertFalse(texts, File(texts).readText().contains("lyrics_blur", ignoreCase = true))
        }
    }

    @Test
    fun `the lines are out of focus only while the view follows the song`() {
        val view = code("ui/component/Lyrics.kt")
        // A scroll by hand and picking lines clear it; lyrics with no times and Follow the song off never have it.
        assertTrue(view.contains("val blurFollows by remember { derivedStateOf { lastPreviewTime == 0L && !keep.picking } }"))
        assertTrue(view.contains("if (isSynced && lyricsAutoScroll && blurFollows) {"))
        assertTrue(view.contains("blur.radius(LyricsBlur.distance(blurSteps, index, displayedCurrentLineIndex), blurFontPx)"))
        // Without the trial a line is drawn as it always was, and none keeps a blur going.
        assertTrue(view.contains("val lineBlur = if (blur == null) null else rememberLineBlur("))
        assertTrue(view.contains("modifier = if (lineBlur == null) {\n                                    Modifier.alpha(lineAlpha)"))
        // The line lit word by word is the one being sung: it is never blurred.
        val words = view.substringAfter("if (inLine && lyricsFancy && item.words != null", missingDelimiterValue = "")
            .substringBefore("val isConsumed = doneWith", missingDelimiterValue = "")
        assertTrue("the word by word line could not be found", words.length > 500)
        assertFalse(words.contains("lyricsLineBlur"))
    }

    @Test
    fun `a blur's radius is read as the line is drawn, and a layer's blur is Android 12's`() {
        val blur = code("ui/component/LyricsBlur.kt")
        val drawn = blur.substringAfter("internal fun Modifier.lyricsLineBlur(", missingDelimiterValue = "").substringBefore("\n}\n")
        assertTrue("the modifier could not be found", drawn.length > 300)
        assertTrue(drawn.contains("radius: () -> Float,"))
        assertTrue(drawn.contains("if (r >= 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BlurEffect(r, r, TileMode.Decal) else null"))
        assertTrue(drawn.contains("if (r < LyricsBlur.SHARP || laidOut == null) drawContent() else drawOutOfFocus(laidOut, color, alpha, r)"))
        assertTrue(blur.contains("fun canDraw(how: How = this.how): Boolean = how == How.GLYPHS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        // In focus quickly, out of it at the pace of the glide to the next line.
        assertTrue(LyricsBlur.CLEAR_MS < LyricsBlur.SETTLE_MS)
    }
}

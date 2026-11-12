/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What kind the lyrics are, synced or plain, is asked of the lyrics in hand where it is used. The
 * view keeps the answer it had when it was last drawn (isSynced), and the work it starts then runs
 * a moment later and reads the lyrics again: when two songs' lyrics came between two frames, synced
 * ones and then plain ones (or "Lyrics not found", which is plain lyrics too), the plain ones were
 * taken for synced and the app closed (#146, #148: ClassCastException, UnsyncedLyrics cast to
 * SyncedLyrics, twice in one night). A JVM test cannot compose the view, so its wiring is read from
 * the source, comments taken out, as LyricsLinesTest does; the lines themselves are asked for here.
 */
class LyricsKindTest {

    private val source = File("src/main/java/com/dd3boh/outertune/ui/component/Lyrics.kt").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    /** What the view starts with [opening], up to the brace that closes it. */
    private fun started(opening: String) = source.substringAfter(opening, missingDelimiterValue = "").substringBefore("\n    }\n")

    @Test
    fun `nothing in the view takes the lyrics for one kind without asking them`() {
        val casts = Regex("""\bas\s+(SemanticLyrics\.)?(SyncedLyrics|UnsyncedLyrics)\b""").findAll(source).map { it.value }.toList()
        assertEquals("a cast that lyrics of the other kind close the app on", emptyList<String>(), casts)
    }

    @Test
    fun `the lines are taken from the lyrics as they are when the work runs`() {
        val fill = started("LaunchedEffect(lyricsModel) {")
        assertTrue("the work that fills the lines could not be found", fill.contains("lines.clear()"))
        assertFalse("isSynced is what the view saw when the work was started", Regex("""\bisSynced\b""").containsMatchIn(fill))
        assertTrue(fill, fill.contains("lines.addAll(lyricsLines(model))"))
    }

    @Test
    fun `the loop that follows the song asks the lyrics it reads whether they are synced`() {
        val loop = started("LaunchedEffect(lyricsModel, lifecycleOwner, playerConnection) {")
        assertTrue("the loop could not be found", loop.contains("repeatOnLifecycle("))
        val before = loop.substringBefore("findCurrentLineIndex(")
        assertTrue(before, before.contains("val synced = lyricsModel as? SemanticLyrics.SyncedLyrics"))
        assertTrue(before, before.contains("if (!isSynced || synced == null || synced.text.isEmpty()) {"))
    }

    @Test
    fun `plain lyrics give a line each with no times, whatever the view showed before them`() {
        val plain = SemanticLyrics.UnsyncedLyrics(listOf("Paper boats" to null, "" to null, "on the river" to null))
        val lines = lyricsLines(plain)
        assertEquals(listOf("Paper boats", "", "on the river"), lines.map { it.text })
        assertTrue(lines.toString(), lines.all { it.start == 0UL && it.end == 0UL && it.words == null && it.speaker == null && !it.isTranslated })
        // "Lyrics not found" is plain lyrics too, of one line.
        assertEquals(1, lyricsLines(uninitializedLyric).size)
    }

    @Test
    fun `synced lyrics give their own lines`() {
        val first = LyricLine("Paper boats", 1_000UL, 2_000UL, null, null, false)
        val synced = SemanticLyrics.SyncedLyrics(listOf(first, first.copy(text = "on the river", start = 2_000UL, end = 3_000UL)))
        assertEquals(synced.text, lyricsLines(synced))
        assertEquals(emptyList<LyricLine>(), lyricsLines(SemanticLyrics.SyncedLyrics(emptyList())))
    }
}

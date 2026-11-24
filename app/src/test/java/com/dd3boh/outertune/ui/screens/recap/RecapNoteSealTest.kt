/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A note to next year's you, sealed in its envelope (NoteSeal, his pick of 24 Nov 2026): quick, in order, still with
 * Remove animations on, the paper never in two places, one tick, never faded over the page, and only once the note is
 * kept. Times read from NoteSeal, the rest from the sources.
 */
class RecapNoteSealTest {
    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `it is quick, its steps in order, gone by 1_2 s and resting by 1_7 s`() {
        // Up round the paper, the paper all the way in, the flap shut over it, the wax on, then away.
        assertTrue(NoteSeal.RISE_MS <= NoteSeal.FLAP_FROM)
        assertTrue(NoteSeal.IN_FROM + NoteSeal.IN_MS <= NoteSeal.FLAP_FROM)
        assertTrue(NoteSeal.FLAP_FROM + NoteSeal.FLAP_MS <= NoteSeal.WAX_FROM)
        assertTrue(NoteSeal.WAX_FROM + NoteSeal.WAX_MS <= NoteSeal.GO_FROM)
        assertTrue(NoteSeal.GO_FROM + NoteSeal.GO_MS <= 1_200)
        // The card and the buttons back by 1.7 s.
        assertTrue("rests after ${NoteSeal.TOTAL_MS} ms", NoteSeal.TOTAL_MS <= 1_700)
        assertTrue(NoteSeal.running(0L, still = false))
        assertFalse(NoteSeal.running(NoteSeal.TOTAL_MS.toLong(), still = false))
        // The letter goes all the way in, and the flap from open to shut.
        assertEquals(0f, NoteSeal.intoDp(0L), 0f)
        assertEquals(NoteSeal.INTO_DP, NoteSeal.intoDp((NoteSeal.IN_FROM + NoteSeal.IN_MS).toLong()), 0.01f)
        assertEquals(NoteSeal.FLAP_OPEN, NoteSeal.flapAngle(NoteSeal.FLAP_FROM.toLong()), 0.01f)
        assertEquals(0f, NoteSeal.flapAngle((NoteSeal.FLAP_FROM + NoteSeal.FLAP_MS).toLong()), 0.01f)
        // Up from the foot of the page without going past its place, and away all the way.
        assertEquals(0f, NoteSeal.riseShare(0L), 0f)
        assertEquals(1f, NoteSeal.riseShare(NoteSeal.RISE_MS.toLong()), 0.001f)
        assertTrue((0..NoteSeal.RISE_MS step 5).all { NoteSeal.riseShare(it.toLong()) <= 1.0001f })
        assertEquals(1f, NoteSeal.goShare((NoteSeal.GO_FROM + NoteSeal.GO_MS).toLong()), 0.001f)
    }

    @Test
    fun `with Remove animations on nothing moves, the note is sealed at once, its card and buttons there`() {
        for (ms in listOf(0L, 300L, 900L, 2_000L)) {
            assertFalse(NoteSeal.running(ms, still = true))
            assertFalse(NoteSeal.letterOnPaper(ms, still = true))
            assertEquals(1f, NoteSeal.paperAlpha(ms, still = true), 0f)
            assertEquals(1f, NoteSeal.chromeAlpha(ms, still = true), 0f)
            assertEquals(0f, NoteSeal.chromeBelow(ms, still = true), 0f)
        }
        // And the still story is asked wherever the seal is drawn: the paper, under it and over it.
        val seal = code("ui/screens/recap/RecapNoteSeal.kt")
        assertEquals(3, Regex("""NoteSeal\.running\(ms, s\.still\)""").findAll(seal).count())
        assertTrue("if (s.still || at < 0) return@LaunchedEffect" in seal)
    }

    @Test
    fun `the paper is never in two places, in its envelope until the flap is shut, then its card`() {
        val shut = (NoteSeal.FLAP_FROM + NoteSeal.FLAP_MS).toLong()
        assertTrue(NoteSeal.letterOnPaper(shut - 1, still = false))
        assertEquals(1f, NoteSeal.paperAlpha(shut - 1, still = false), 0f)
        assertFalse(NoteSeal.letterOnPaper(shut, still = false))
        // Hidden behind the shut flap, then the card comes in once the envelope is on its way.
        assertEquals(0f, NoteSeal.paperAlpha(shut, still = false), 0f)
        assertEquals(0f, NoteSeal.paperAlpha(NoteSeal.CARD_FROM - 1L, still = false), 0f)
        assertTrue(NoteSeal.CARD_FROM > NoteSeal.GO_FROM)
        assertEquals(null, NoteSeal.cardShare(NoteSeal.CARD_FROM - 1L))
        // At the end, the card where the paper was and the buttons back.
        val end = NoteSeal.TOTAL_MS.toLong()
        assertEquals(1f, NoteSeal.paperAlpha(end, still = false), 0f)
        assertEquals(1f, NoteSeal.chromeAlpha(end, still = false), 0f)
        assertEquals(0f, NoteSeal.chromeBelow(end, still = false), 0f)
    }

    @Test
    fun `the buttons step aside at once and come back up with the card`() {
        // Gone at once: faded out, Write another (in Seal it's place) showed under the paper for a moment (23 Nov).
        assertEquals(0f, NoteSeal.chromeAlpha(0L, still = false), 0f)
        assertEquals(0f, NoteSeal.chromeAlpha(NoteSeal.CHROME_FROM - 1L, still = false), 0f)
        assertEquals(1f, NoteSeal.chromeBelow(NoteSeal.CHROME_FROM.toLong(), still = false), 0.001f)
        assertTrue(NoteSeal.CHROME_FROM > NoteSeal.CARD_FROM)
    }

    @Test
    fun `the seal is felt once, as the wax lands`() {
        assertTrue(NoteSeal.PRESS_MS in NoteSeal.WAX_FROM..(NoteSeal.WAX_FROM + NoteSeal.WAX_MS))
        val seal = code("ui/screens/recap/RecapNoteSeal.kt")
        assertEquals(1, Regex("""LaunchedEffect\(note\.sealedAt\)""").findAll(seal).count())
        assertEquals(1, Regex("""haptic\.performHapticFeedback\(""").findAll(seal).count())
    }

    @Test
    fun `the envelope is never faded over the page, which turns plum to a muddy brown`() {
        // Seen on the emulator, 24 Nov 2026: it comes up whole from under the foot of the page and leaves past its side.
        val seal = code("ui/screens/recap/RecapNoteSeal.kt")
        assertFalse("saveLayer" in seal)
        val drawing = seal.substringAfter("private fun DrawScope.envelopeBehind(").substringBefore("/** The wax slapped onto")
        assertFalse(Regex("""ENVELOPE\w*\.copy\(alpha""").containsMatchIn(drawing))
    }

    @Test
    fun `it runs at its own pace, slower only when the debug sample asks for a film`() {
        assertEquals(1f, NoteSeal.slow, 0f)
        assertEquals(700L, NoteSeal.since(10_700L, 10_000L))
        try {
            NoteSeal.slow = 3f
            assertEquals(300L, NoteSeal.since(10_900L, 10_000L))
        } finally {
            NoteSeal.slow = 1f
        }
        // Set in one place, the debug sample's receiver (debug builds only, the shell alone may send it).
        val setters = File("src/main/java/com/dd3boh/outertune").walkTopDown()
            .filter { it.extension == "kt" && Regex("""NoteSeal\.slow\s*=""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(listOf("RecapSample.kt"), setters)
        // Every reading of the seal's time goes through it.
        val sources = code("ui/screens/recap/RecapNoteSeal.kt") + code("ui/screens/recap/RecapNoteSlides.kt")
        assertFalse("clock.now - at" in sources)
        assertFalse("clock.now - note.sealedAt" in sources)
    }

    @Test
    fun `it is the Recap's one seal, the takes compared on 23 Nov are gone`() {
        assertFalse(File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapSealLetter.kt").exists())
        assertFalse("RECAP_SEAL_TAKES" in code("constants/Unreleased.kt"))
        assertFalse("RecapSealTakeKey" in code("constants/PreferenceKeys.kt"))
        assertFalse("Recap seal" in code("ui/screens/settings/fragments/DeveloperFrag.kt"))
        assertFalse("getStringExtra(\"seal\")" in code("recap/RecapSample.kt"))
        // The card keeps the wax it was sealed with.
        val slide = code("ui/screens/recap/RecapNoteSlides.kt")
        assertTrue("waxSeal(center, size.minDimension / 2f, mark)" in slide)
        assertTrue("rotationZ = NoteSeal.MARK_TURN" in slide)
    }

    @Test
    fun `sealing still keeps the note first, the envelope starts from the seal kept`() {
        // The note is written to the phone, then kept with the story's time, and only then does the envelope come.
        val screen = code("ui/screens/recap/RecapScreen.kt")
        val seal = screen.substringAfter("fun sealNote() {").substringBefore("\n    }\n")
        assertTrue(seal.indexOf("RecapNote.save(context, recap.year, text, day)") in 0 until seal.indexOf("note.kept(stage.clock.now)"))
        val note = NoteDraft(mutableStateOf("Dear me"))
        note.kept(5_000L)
        assertEquals(5_000L, note.sealedAt)
        assertEquals("Dear me", note.letter)
        // Drawn under and over the paper from that moment, the paper itself moving with it.
        val slide = code("ui/screens/recap/RecapNoteSlides.kt")
        assertTrue("if (note.sealedAt >= 0) SealBehind(s, note, places, WRITE_TURN, Modifier.matchParentSize())" in slide)
        assertTrue("if (note.sealedAt >= 0) SealOver(s, note, places, WRITE_TURN, Modifier.matchParentSize())" in slide)
        assertTrue(".sealPaper(s, note, WRITE_TURN)" in slide)
    }
}

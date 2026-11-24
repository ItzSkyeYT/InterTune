/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ways a note to next year's you is sealed (SealTake, his "god the sealing animation looks so
 * ass", 23 Nov 2026): each short, still with Remove animations on, the paper never in two places,
 * the seal felt once, and the pick the debug app's alone. Times read from SealTakes, the rest from
 * the sources.
 */
class RecapSealTakesTest {
    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val takes = listOf(SealTake.A, SealTake.B, SealTake.C)

    @Test
    fun `each take is short, and the take of before is as it was`() {
        // A has folded and gone in under a second, B's envelope is gone by 1.2 s, C is pressed on by 0.9 s...
        assertTrue(SealTakes.A_CARD_FROM <= 900)
        assertTrue(SealTakes.B_GO_FROM + SealTakes.B_GO_MS <= 1_200)
        assertTrue(SealTakes.C_PRESS_FROM + SealTakes.C_PRESS_MS <= 950)
        // ...and all of them rest, their card and buttons back, by 1.7 s. Before: about 3 s, then its paper back.
        for (take in takes) assertTrue("$take rests after ${SealTakes.totalMs(take)} ms", SealTakes.totalMs(take) <= 1_700)
        assertEquals(SealLetter.TOTAL_MS + 300, SealTakes.totalMs(SealTake.BEFORE))
        assertEquals(SealLetter.PRESS_MS, SealTakes.pressMs(SealTake.BEFORE))
    }

    @Test
    fun `with Remove animations on nothing moves, the note is sealed at once, its card and buttons there`() {
        for (take in SealTake.entries) for (ms in listOf(0L, 300L, 900L, 2_000L)) {
            assertFalse(SealTakes.running(take, ms, still = true))
            assertFalse(SealTakes.letterOnPaper(take, ms, still = true))
            assertFalse(SealTakes.markHidden(take, ms, still = true))
            assertEquals(1f, SealTakes.paperAlpha(take, ms, still = true), 0f)
            assertEquals(1f, SealTakes.chromeAlpha(take, ms, still = true), 0f)
            assertEquals(0f, SealTakes.chromeBelow(take, ms, still = true), 0f)
        }
    }

    @Test
    fun `the paper is never in two places, the letter goes before its sealed card comes`() {
        // A: the folded note is the paper itself, going; the card comes once it has gone.
        assertTrue(SealTakes.letterOnPaper(SealTake.A, SealTakes.A_CARD_FROM - 1L, still = false))
        assertFalse(SealTakes.letterOnPaper(SealTake.A, SealTakes.A_CARD_FROM.toLong(), still = false))
        assertEquals(0f, SealTakes.goAlpha(SealTakes.A_CARD_FROM.toLong(), SealTakes.A_GO_FROM, SealTakes.A_GO_MS), 0.001f)
        assertEquals(0f, SealTakes.paperAlpha(SealTake.A, SealTakes.A_CARD_FROM.toLong(), still = false), 0.001f)
        // B: the flap is shut over the letter before the paper becomes the card, and that is hidden until it comes.
        val shut = (SealTakes.B_FLAP_FROM + SealTakes.B_FLAP_MS).toLong()
        assertTrue(SealTakes.letterOnPaper(SealTake.B, shut - 1, still = false))
        assertFalse(SealTakes.letterOnPaper(SealTake.B, shut, still = false))
        assertEquals(0f, SealTakes.flapAngle(shut), 0.01f)
        assertEquals(SealTakes.FLAP_OPEN, SealTakes.flapAngle(SealTakes.B_FLAP_FROM.toLong()), 0.01f)
        assertEquals(0f, SealTakes.paperAlpha(SealTake.B, SealTakes.B_CARD_FROM - 1L, still = false), 0f)
        assertTrue(SealTakes.B_WAX_FROM >= shut)
        // C: the card is there all along, its own seal waiting until the sheet has been pressed into it.
        assertFalse(SealTakes.letterOnPaper(SealTake.C, 100L, still = false))
        assertEquals(1f, SealTakes.paperAlpha(SealTake.C, 100L, still = false), 0f)
        assertTrue(SealTakes.markHidden(SealTake.C, 100L, still = false))
        assertFalse(SealTakes.markHidden(SealTake.C, (SealTakes.C_PRESS_FROM + SealTakes.C_PRESS_MS).toLong(), still = false))
        for (take in listOf(SealTake.A, SealTake.B, SealTake.BEFORE)) assertFalse(SealTakes.markHidden(take, 100L, still = false))
        // At the end every take leaves its card where the paper was, the buttons back where they were.
        for (take in SealTake.entries) {
            val end = SealTakes.totalMs(take).toLong()
            assertFalse(SealTakes.running(take, end, still = false))
            assertEquals(1f, SealTakes.paperAlpha(take, end, still = false), 0f)
            assertEquals(1f, SealTakes.chromeAlpha(take, end, still = false), 0f)
            assertEquals(0f, SealTakes.chromeBelow(take, end, still = false), 0f)
        }
    }

    @Test
    fun `the buttons step aside while it is sealed and come back up with the card`() {
        for (take in takes) {
            // Gone at once: faded out, Write another (in Seal it's place) showed under the paper for a moment (24 Nov).
            assertEquals(0f, SealTakes.chromeAlpha(take, 0L, still = false), 0f)
            assertEquals(0f, SealTakes.chromeAlpha(take, 100L, still = false), 0f)
            assertEquals(0f, SealTakes.chromeAlpha(take, SealTakes.chromeFrom(take) - 1L, still = false), 0f)
            assertEquals(1f, SealTakes.chromeBelow(take, SealTakes.chromeFrom(take).toLong(), still = false), 0.001f)
        }
        // A and B: once their card has come; C: once its seal is made.
        assertTrue(SealTakes.chromeFrom(SealTake.A) > SealTakes.A_CARD_FROM)
        assertTrue(SealTakes.chromeFrom(SealTake.B) > SealTakes.B_CARD_FROM)
        assertEquals(SealTakes.C_PRESS_FROM, SealTakes.chromeFrom(SealTake.C))
        // The take of before leaves them as they were.
        assertEquals(1f, SealTakes.chromeAlpha(SealTake.BEFORE, 500L, still = false), 0f)
    }

    @Test
    fun `the seal is felt once, while its take runs, where something lands`() {
        for (take in SealTake.entries) assertTrue("$take", SealTakes.pressMs(take) in 1 until SealTakes.totalMs(take))
        assertEquals(SealTakes.A_FOLD_MS, SealTakes.pressMs(SealTake.A))
        assertTrue(SealTakes.pressMs(SealTake.B) in SealTakes.B_WAX_FROM..(SealTakes.B_WAX_FROM + SealTakes.B_WAX_MS))
        assertTrue(SealTakes.pressMs(SealTake.C) in SealTakes.C_PRESS_FROM..(SealTakes.C_PRESS_FROM + SealTakes.C_PRESS_MS))
        // One effect for each seal, keyed on the seal, so another note sealed is felt again and nothing twice.
        val source = code("ui/screens/recap/RecapSealTakes.kt")
        assertEquals(1, Regex("""LaunchedEffect\(note\.sealedAt, take\)""").findAll(source).count())
        assertEquals(1, Regex("""haptic\.performHapticFeedback\(""").findAll(source).count())
    }

    @Test
    fun `A folds once, all the way and never back, and goes`() {
        assertEquals(0f, SealTakes.foldAngle(0L), 0f)
        assertEquals(180f, SealTakes.foldAngle(SealTakes.A_FOLD_MS.toLong()), 0.01f)
        var last = 0f
        for (ms in 0..SealTakes.A_FOLD_MS step 10) {
            val angle = SealTakes.foldAngle(ms.toLong())
            assertTrue("went back at $ms ms", angle >= last)
            last = angle
        }
        assertTrue(SealTakes.A_GO_FROM >= SealTakes.A_FOLD_MS)
        assertEquals(0f, SealTakes.goShare(SealTakes.A_GO_FROM.toLong(), SealTakes.A_GO_FROM, SealTakes.A_GO_MS), 0f)
        assertEquals(1f, SealTakes.goShare(SealTakes.A_CARD_FROM.toLong(), SealTakes.A_GO_FROM, SealTakes.A_GO_MS), 0.001f)
    }

    @Test
    fun `B's letter goes all the way down into its envelope before the flap shuts`() {
        assertEquals(0f, SealTakes.intoDp(0L), 0f)
        assertEquals(SealTakes.INTO_DP, SealTakes.intoDp((SealTakes.B_IN_FROM + SealTakes.B_IN_MS).toLong()), 0.01f)
        assertTrue(SealTakes.B_IN_FROM + SealTakes.B_IN_MS <= SealTakes.B_FLAP_FROM)
        assertTrue(SealTakes.B_RISE_MS <= SealTakes.B_FLAP_FROM)
        assertTrue(SealTakes.B_GO_FROM >= SealTakes.B_WAX_FROM + SealTakes.B_WAX_MS)
    }

    @Test
    fun `C's seal is pressed down to exactly the card's own`() {
        assertEquals(SealTakes.LIFT, SealTakes.pressScale(SealTakes.C_PRESS_FROM.toLong()), 0.001f)
        assertEquals(1f, SealTakes.pressScale((SealTakes.C_PRESS_FROM + SealTakes.C_PRESS_MS).toLong()), 0.001f)
        val lowest = (SealTakes.C_PRESS_FROM..(SealTakes.C_PRESS_FROM + SealTakes.C_PRESS_MS)).minOf { SealTakes.pressScale(it.toLong()) }
        assertTrue("pressed to $lowest", lowest < 1f && lowest > 0.95f)
        // The card's seal is drawn by the same function as the sheet's last frame, turned the same.
        val slide = code("ui/screens/recap/RecapNoteSlides.kt")
        assertTrue("sealMark(take, center, size.minDimension / 2f, mark)" in slide)
        assertTrue("rotationZ = SealTakes.MARK_TURN" in slide)
    }

    @Test
    fun `a ray from a rounded rectangle's middle leaves it where it should`() {
        assertEquals(10f, roundRectReach(1f, 0f, 10f, 5f, 2f), 1e-4f)
        assertEquals(5f, roundRectReach(0f, 1f, 10f, 5f, 2f), 1e-4f)
        // A circle: its radius every way.
        for (k in 0 until 16) {
            val a = 2 * PI * k / 16
            assertEquals(6f, roundRectReach(cos(a).toFloat(), sin(a).toFloat(), 6f, 6f, 6f), 1e-3f)
        }
        // A square's sharp corner, and the same corner rounded, a little short of it.
        val d = (1 / sqrt(2.0)).toFloat()
        assertEquals(hypot(5f, 5f), roundRectReach(d, d, 5f, 5f, 0f), 1e-3f)
        assertTrue(roundRectReach(d, d, 5f, 5f, 2f) < hypot(5f, 5f))
    }

    @Test
    fun `the take is the debug app's to pick, and every other build plays the default`() {
        val flags = code("constants/Unreleased.kt")
        assertTrue("val RECAP_SEAL_TAKES = BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == \"debug\"" in flags)
        val source = code("ui/screens/recap/RecapSealTakes.kt")
        assertTrue("if (!Unreleased.RECAP_SEAL_TAKES) return SealTake.DEFAULT" in source)
        assertEquals(SealTake.DEFAULT, SealTake.of(null))
        assertEquals(SealTake.DEFAULT, SealTake.of("nothing like it"))
        for (take in SealTake.entries) assertEquals(take, SealTake.of(take.name))
        // The row is in the developer options' Recap block, and only with the flag.
        val developer = code("ui/screens/settings/fragments/DeveloperFrag.kt")
        assertTrue("if (Unreleased.RECAP_SEAL_TAKES) RecapSealRow()" in developer)
        assertTrue(developer.indexOf("if (BuildConfig.PREVIEW_FEATURES && Unreleased.RECAP) {") in 0 until developer.indexOf("RecapSealRow()"))
        // The slide asks once, and gives it to what goes under the paper, the paper, what goes over it and its card's seal.
        val slide = code("ui/screens/recap/RecapNoteSlides.kt")
        assertEquals(1, Regex("""rememberSealTake\(\)""").findAll(slide).count())
        assertTrue("SealBehind(take, s, note, places, WRITE_TURN, Modifier.matchParentSize())" in slide)
        assertTrue(".sealPaper(take, s, note, places, WRITE_TURN)" in slide)
        assertTrue("Sealed(s, note, hand, take, places, onBaseline = { first = it })" in slide)
    }

    @Test
    fun `the takes run at their own pace, slower only when the debug sample asks for a film`() {
        assertEquals(1f, SealTakes.slow, 0f)
        assertEquals(700L, SealTakes.since(10_700L, 10_000L))
        try {
            SealTakes.slow = 3f
            assertEquals(300L, SealTakes.since(10_900L, 10_000L))
        } finally {
            SealTakes.slow = 1f
        }
        // Set in one place, the debug sample's receiver (debug builds only, the shell alone may send it).
        val setters = File("src/main/java/com/dd3boh/outertune").walkTopDown()
            .filter { it.extension == "kt" && Regex("""SealTakes\.slow\s*=""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(listOf("RecapSample.kt"), setters)
        // Every reading of a take's time goes through it.
        val takes = code("ui/screens/recap/RecapSealTakes.kt") + code("ui/screens/recap/RecapNoteSlides.kt")
        assertFalse("clock.now - at" in takes)
        assertFalse("clock.now - note.sealedAt" in takes)
    }

    @Test
    fun `sealing still keeps the note first, the take starts from the seal kept`() {
        // The note is written to the phone, then kept with the story's time, and only then does any take start.
        val screen = code("ui/screens/recap/RecapScreen.kt")
        val seal = screen.substringAfter("fun sealNote() {").substringBefore("\n    }\n")
        assertTrue(seal.indexOf("RecapNote.save(context, recap.year, text, day)") in 0 until seal.indexOf("note.kept(stage.clock.now)"))
        val note = NoteDraft(androidx.compose.runtime.mutableStateOf("Dear me"))
        note.kept(5_000L)
        assertEquals(5_000L, note.sealedAt)
        assertEquals("Dear me", note.letter)
    }
}

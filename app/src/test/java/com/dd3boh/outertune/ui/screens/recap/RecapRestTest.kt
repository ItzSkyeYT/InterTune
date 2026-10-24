/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No beat. The calm shapes look, chosen on 24 Oct 2026: things turn and arrive with character,
 * then rest, "just not the constant beating". So nothing in the Recap repeats on its own; while a
 * slide rests the shapes only drift slowly and a little, and the stars turn about once a minute;
 * what arrives arrives once and comes to rest.
 */
class RecapRestTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so that a sentence naming something is not taken for code. */
    private fun code(file: File) = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val recapFiles = listOf("recap", "ui/screens/recap")
        .flatMap { File(sources, it).listFiles()?.toList().orEmpty() }
        .filter { it.extension == "kt" }

    @Test
    fun `nothing in the Recap repeats on its own`() {
        assertTrue("the Recap's files were not found", recapFiles.size >= 20)
        val loops = Regex("""infiniteRepeatable|rememberInfiniteTransition|RepeatMode|repeatable\(|InfiniteTransition""")
        val found = recapFiles.flatMap { file ->
            code(file).lines().mapIndexedNotNull { i, line -> if (loops.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null }
        }
        assertTrue("looping animations in the Recap:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `a resting shape drifts slowly and only a little`() {
        assertEquals(ActorId.entries.toSet(), RecapMotion.Rest.drift.keys)
        for ((id, d) in RecapMotion.Rest.drift) {
            for ((amplitude, period) in listOf(d.ax to d.px, d.ay to d.py, d.ar to d.pr)) {
                if (amplitude > 0f) assertTrue("$id comes round in $period s", period >= RecapMotion.Rest.SLOWEST_S)
            }
            assertTrue("$id wanders ${d.ax} by ${d.ay}", d.ax <= 8f && d.ay <= 8f)
            assertTrue("$id rocks ${d.ar} degrees", d.ar <= 6f)
        }
        assertTrue(RecapMotion.Rest.SLOWEST_S >= 8f)
    }

    @Test
    fun `nothing turns at rest faster than once in twenty seconds`() {
        val every = Choreography.start + SlideKind.entries.flatMap { kind -> (0..3).flatMap { Choreography.of(kind, it) } }
        var spinning = 0
        for (poses in every) for ((id, pose) in poses.set) {
            if (pose.spin > 0f) {
                spinning++
                assertTrue("$id turns once in ${pose.spin} s", pose.spin >= RecapMotion.Rest.SLOWEST_TURN_S)
            }
        }
        assertTrue("the stars were read", spinning >= 10)
        assertTrue(RecapMotion.Rest.SLOWEST_TURN_S >= 20f)
        assertTrue(RecapMotion.Rest.WORKING_TURN_S >= RecapMotion.Rest.SLOWEST_TURN_S)
    }

    @Test
    fun `what arrives comes to rest where it belongs`() {
        val curves = listOf(
            RecapMotion.Settle, RecapMotion.Bouncy, RecapMotion.Spring, RecapMotion.Snap, RecapMotion.Morph, RecapMotion.SlapEasing,
            RecapMotion.BarEasing, RecapMotion.RayEasing, RecapMotion.TileEasing, RecapMotion.CountInEasing, RecapMotion.WipeEasing, RecapMotion.StripEasing,
        )
        for (curve in curves) {
            assertEquals(0f, curve.transform(0f), 1e-3f)
            assertEquals(1f, curve.transform(1f), 1e-3f)
        }
        // The springs go a little past their place once, the settle by about 8% and the livelier one by 20%.
        val settlePeak = (0..100).maxOf { RecapMotion.Settle.transform(it / 100f) }
        val bouncyPeak = (0..100).maxOf { RecapMotion.Bouncy.transform(it / 100f) }
        assertEquals(1.083f, settlePeak, 0.005f)
        assertEquals(1.205f, bouncyPeak, 0.005f)
    }

    @Test
    fun `a number counts up once and lands on its value`() {
        assertEquals(0L, RecapMotion.counted(20_920, 1f, 4f, 400f))
        assertEquals(20_920L, RecapMotion.counted(20_920, 1f, 4f, 2_000f))
        assertEquals(20_920L, RecapMotion.counted(20_920, 1f, 4f, 600_000f))
        var last = 0L
        for (t in 0..2_400 step 16) {
            val v = RecapMotion.counted(20_920, 1f, 4f, t.toFloat())
            assertTrue("went back from $last to $v at $t ms", v >= last)
            last = v
        }
    }

    @Test
    fun `confetti is let go in two places only, number one and the thank-you page`() {
        val stage = code(File(sources, "ui/screens/recap/RecapStage.kt"))
        val all = recapFiles.joinToString("\n") { code(it) }
        assertEquals(1, Regex("""confetti\.burst\(""").findAll(all).count())
        assertEquals(1, Regex("""confetti\.rain\(""").findAll(all).count())
        assertTrue(Regex("""SlideKind\.NUMBER_ONE -> after\([^\n]*\n[^\n]*\n\s*confetti\.burst""").containsMatchIn(stage))
        assertTrue(Regex("""SlideKind\.THANKS -> after\([^\n]*\n\s*confetti\.rain""").containsMatchIn(stage))
    }
}

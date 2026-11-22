/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The colour pairs: one loud paper a slide, words that read on it, and a place on it for every
 * piece of paper. Contrast worked out here the WCAG way, separately from the slides' own sum.
 */
class RecapLookTest {
    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = lum(a)
        val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** [ink] with its alpha laid over [paper]. */
    private fun over(ink: Color, paper: Color) = Color(
        red = ink.red * ink.alpha + paper.red * (1 - ink.alpha),
        green = ink.green * ink.alpha + paper.green * (1 - ink.alpha),
        blue = ink.blue * ink.alpha + paper.blue * (1 - ink.alpha),
    )

    private fun darker(c: Color, k: Float) = Color(c.red * k, c.green * k, c.blue * k)

    private val papers: List<Pair<String, SlidePaper>> =
        listOf("start" to START_PAPER) + SlideKind.entries.flatMap { kind ->
            (0 until if (kind == SlideKind.COUNTDOWN) COUNTDOWN_COLOURS.size else 1).map { "$kind $it" to paperOf(kind, it) }
        }

    @Test
    fun `the words read on every paper, and the quieter words at 4·7 to 1 where the paper allows, grain and all`() {
        for ((name, p) in papers) {
            assertTrue("$name: ${contrast(p.ink, p.paper)}", contrast(p.ink, p.paper) >= 4.5)
            // Where 4.7 cannot be had on the grain's darker paper (the reds), the quieter words are as strong as the rest.
            val plain = contrast(over(p.ink2, p.paper), p.paper)
            val grained = contrast(over(p.ink2, darker(p.paper, 0.935f)), darker(p.paper, 0.935f))
            assertTrue("$name quieter words: $plain", plain >= 4.7)
            assertTrue("$name quieter words on the grain: $grained", grained >= 4.7 || p.ink2.alpha >= 0.999f)
            assertTrue("$name quieter words on the grain: $grained", grained >= 4.5)
            assertTrue("$name: the quieter words are quieter or as strong", p.ink2.alpha in 0.74f..1f)
        }
    }

    @Test
    fun `what is written on the accent reads on it`() {
        for ((name, p) in papers.drop(1)) assertTrue("$name: ${contrast(p.onAccent, p.accent)}", contrast(p.onAccent, p.accent) >= 4.5)
    }

    @Test
    fun `every slide has a paper, a sheet to come in on, and a place or a way out for each shape`() {
        assertEquals(ActorId.entries.toSet(), Choreography.start.single().set.keys)
        for (kind in SlideKind.entries) for (step in 0..3) {
            Choreography.sheet(kind, step)
            val sets = Choreography.of(kind, step)
            assertTrue("$kind $step", sets.isNotEmpty())
            for (set in sets) assertEquals("$kind $step", ActorId.entries.toSet(), set.set.keys)
            for ((id, pose) in sets.first().set) {
                if (!pose.hide && id.kind == ActorKind.SHAPE) assertTrue("$kind $step $id has colours", pose.c.isNotEmpty())
                if (!pose.hide) assertTrue("$kind $step $id has a size", !pose.s.isNaN())
                if (!pose.hide) assertTrue("$kind $step $id has a place", pose.at != null || (!pose.x.isNaN() && !pose.y.isNaN()))
            }
        }
    }

    @Test
    fun `two slides one after the other never share a paper`() {
        val order = listOf(START_PAPER, paperOf(SlideKind.SINCE), paperOf(SlideKind.MINUTES), paperOf(SlideKind.HOUR), paperOf(SlideKind.STREAK),
            paperOf(SlideKind.ARTISTS), paperOf(SlideKind.ONLY_HERE)) +
            COUNTDOWN_COLOURS.indices.map { paperOf(SlideKind.COUNTDOWN, it) } +
            listOf(paperOf(SlideKind.NUMBER_ONE), paperOf(SlideKind.CARD), paperOf(SlideKind.THANKS))
        order.zipWithNext().forEach { (a, b) -> assertNotEquals(a.paper, b.paper) }
        // The first slide is the tunnel's core, blue, so the start screen opens straight into it.
        assertEquals(Choreography.start.single().set.getValue(ActorId.A).c.last(), paperOf(SlideKind.SINCE).paper)
    }
}

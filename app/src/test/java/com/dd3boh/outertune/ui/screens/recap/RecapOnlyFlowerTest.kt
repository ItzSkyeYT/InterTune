/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Only on InterTune's paper flower is posed on the spot "found" (RecapChoreography), which only the
 * slide's own parts can give a place. Tester Ti-Die's year had songs named by ear and no Auto find:
 * nothing marked the spot and the flower sat empty (22 Nov 2026). Whatever the year holds, something
 * on the slide must mark it.
 */
class RecapOnlyFlowerTest {
    private val slides = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapSlides.kt").readText()
    private val slide = slides.substringAfter("internal fun OnlySlide(").substringBefore("/** One more thing only InterTune does")

    @Test
    fun flowerPosedOnFoundIsAnchoredWhenThereIsNoAutoFind() {
        val choreography = File("src/main/java/com/dd3boh/outertune/ui/screens/recap/RecapChoreography.kt").readText()
        assertTrue(choreography.contains("Paper.FLOWER, at = Spot.FOUND"))
        // The slide without an Auto find hands over to the hero that anchors the spot, never to a bare gap.
        assertTrue(slide.contains("?: OnlyHero("))
        assertTrue(!slide.contains("} ?: Grow(room)"))
        val hero = slides.substringAfter("fun ColumnScope.OnlyHero(").substringBefore("/** One more thing only InterTune does")
        assertTrue(hero.contains("anchor(s, Spot.FOUND)"))
    }

    @Test
    fun heroHasSomethingForEveryYear() {
        val hero = slides.substringAfter("fun ColumnScope.OnlyHero(").substringBefore("/** One more thing only InterTune does")
        // A song's cover (a song with no picture has the made one), the sound bars, the rooms' huddle.
        assertTrue(hero.contains("Sticker(song.cover"))
        assertTrue(hero.contains("GraphicEq"))
        assertTrue(hero.contains("People("))
    }
}

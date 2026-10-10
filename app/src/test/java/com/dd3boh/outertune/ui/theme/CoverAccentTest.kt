/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import com.google.material.color.hct.Hct
import com.google.material.color.scheme.SchemeTonalSpot
import com.google.material.color.score.Score
import com.google.material.color.utils.MathUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The covers here are real ones, as the app's palette sees them: eight colours each and the part
 * of the picture each stands for, in thousandths (CoverAccentTrial prints them).
 */
class CoverAccentTest {

    /** A black and white photograph with a breath of yellow in it, which gave lime green controls. */
    private val blackAndWhitePhoto = mapOf(
        0x202018 to 500, 0xd8e0d8 to 121, 0x404040 to 73, 0x788070 to 67,
        0xb0b0a0 to 66, 0xc8c8c0 to 60, 0x989888 to 58, 0x606058 to 55,
    ).opaque()

    /** A black cover. The one bluish swatch is a two-hundredth of it, and the controls were teal. */
    private val blackCover = mapOf(
        0x101010 to 909, 0x101018 to 26, 0x202020 to 21, 0x282830 to 15,
        0x303038 to 13, 0x383838 to 9, 0x404040 to 4, 0x485050 to 4,
    ).opaque()

    /** Greys and nothing else, for which the scorer hands back its stand-in blue. */
    private val greys = mapOf(0x101010 to 204, 0x383838 to 203, 0x686868 to 201, 0xa8a8a8 to 199, 0xe0e0e0 to 193).opaque()

    /** A cover in olive and gold. The scorer takes its dark background, which is the weakest of them. */
    private val oliveAndGold = mapOf(
        0x302818 to 505, 0x605830 to 130, 0x989050 to 124, 0x989880 to 62,
        0x484848 to 59, 0x706858 to 58, 0xe0d8d8 to 31, 0xa8a8a8 to 31,
    ).opaque()

    /** A cover of washed-out blue greens, none of them strong. */
    private val washedOut = mapOf(
        0x809088 to 128, 0x486060 to 127, 0x181820 to 127, 0xc0c0c0 to 124,
        0xa8b0b8 to 124, 0x303840 to 124, 0x687878 to 123, 0x98a0a0 to 123,
    ).opaque()

    /** Greys with one small mark in full red. */
    private val greysWithARedMark = (greys + (0xffd01828.toInt() to 21))

    private fun Map<Int, Int>.opaque() = mapKeys { it.key or (0xff shl 24) }

    private fun accent(colours: Map<Int, Int>, dark: Boolean = true) =
        Hct.fromInt(CoverAccent.scheme(Hct.fromInt(CoverAccent.source(colours)), dark).primary)

    private fun before(colours: Map<Int, Int>, dark: Boolean = true) =
        SchemeTonalSpot(Hct.fromInt(Score.score(colours).first()), dark, 0.0).primary

    @Test
    fun `a black and white photograph with a tint gives an accent that is nearly grey`() {
        assertTrue("it was lime green", Hct.fromInt(before(blackAndWhitePhoto)).chroma > 30)
        for (dark in listOf(true, false)) {
            val now = accent(blackAndWhitePhoto, dark)
            assertTrue("chroma ${now.chroma}", now.chroma < 12)
        }
    }

    @Test
    fun `the tint it keeps is the cover's own`() {
        val source = Hct.fromInt(CoverAccent.source(blackAndWhitePhoto))
        val picked = Hct.fromInt(Score.score(blackAndWhitePhoto).first())
        assertTrue("hue ${source.hue}, the cover's ${picked.hue}", MathUtils.differenceDegrees(source.hue, picked.hue) < 6)
        assertTrue("some of it is left: ${CoverAccent.share(source.chroma)}", CoverAccent.share(source.chroma) in 0.05..0.5)
    }

    @Test
    fun `a black cover gives a grey accent, not the colour of a swatch it has next to none of`() {
        assertTrue("it was teal", Hct.fromInt(before(blackCover)).chroma > 30)
        assertEquals(CoverAccent.GREY, CoverAccent.source(blackCover))
        assertTrue(accent(blackCover).chroma < 5)
    }

    @Test
    fun `a cover of greys gives a grey accent, not the scorer's blue`() {
        assertEquals(0xff4285f4.toInt(), Score.score(greys).first())
        assertEquals(CoverAccent.GREY, CoverAccent.source(greys))
        assertTrue(accent(greys).chroma < 5)
        assertTrue(accent(greys, dark = false).chroma < 5)
    }

    @Test
    fun `no colours at all give a grey accent`() {
        assertEquals(CoverAccent.GREY, CoverAccent.source(emptyMap()))
    }

    @Test
    fun `a cover in colour keeps the accent it had, when the scorer takes its weakest colour too`() {
        assertTrue("the scorer's choice is a weak one", Hct.fromInt(Score.score(oliveAndGold).first()).chroma < CoverAccent.FULL_FROM)
        for (dark in listOf(true, false)) {
            assertEquals(before(oliveAndGold, dark), CoverAccent.scheme(Hct.fromInt(CoverAccent.source(oliveAndGold)), dark).primary)
        }
    }

    @Test
    fun `a strong colour is handed over as it is`() {
        val colours = mapOf(0xffe030d0.toInt() to 125, 0xff28b018.toInt() to 125, 0xff403840.toInt() to 130, 0xffc0b8b8.toInt() to 133)
        val picked = Score.score(colours).first()
        assertEquals(picked, CoverAccent.source(colours))
        assertEquals(before(colours), CoverAccent.scheme(Hct.fromInt(picked), true).primary)
    }

    @Test
    fun `one small mark in full colour on greys still gives its colour`() {
        assertEquals(0xffd01828.toInt(), CoverAccent.source(greysWithARedMark))
        assertEquals(before(greysWithARedMark), CoverAccent.scheme(Hct.fromInt(CoverAccent.source(greysWithARedMark)), true).primary)
    }

    @Test
    fun `a washed-out cover gives its colour, softer`() {
        val was = Hct.fromInt(before(washedOut))
        val now = accent(washedOut)
        assertTrue("chroma ${now.chroma} of ${was.chroma}", now.chroma > 14 && now.chroma < was.chroma - 6)
        assertTrue("hue ${now.hue}, was ${was.hue}", MathUtils.differenceDegrees(now.hue, was.hue) < 8)
    }

    @Test
    fun `the strength rises from nothing at grey to all of it at a colour`() {
        assertEquals(0.0, CoverAccent.share(0.0), 0.0)
        assertEquals(0.0, CoverAccent.share(CoverAccent.GREY_UNTIL), 0.0)
        assertEquals(0.5, CoverAccent.share((CoverAccent.GREY_UNTIL + CoverAccent.FULL_FROM) / 2), 1e-9)
        assertEquals(1.0, CoverAccent.share(CoverAccent.FULL_FROM), 0.0)
        assertEquals(1.0, CoverAccent.share(90.0), 0.0)
        var last = -1.0
        for (tenth in 0..400) {
            val share = CoverAccent.share(tenth / 10.0)
            assertTrue(share >= last); last = share
        }
    }

    @Test
    fun `the app's own colour and any colour that is one give the scheme they always did`() {
        for (argb in listOf(DefaultThemeColorArgb, 0xff4285f4.toInt(), 0xff006879.toInt(), 0xffb07078.toInt())) {
            for (dark in listOf(true, false)) {
                val was = SchemeTonalSpot(Hct.fromInt(argb), dark, 0.0)
                val now = CoverAccent.scheme(Hct.fromInt(argb), dark)
                assertEquals(was.primary, now.primary)
                assertEquals(was.secondaryContainer, now.secondaryContainer)
                assertEquals(was.tertiary, now.tertiary)
                assertEquals(was.surface, now.surface)
                assertEquals(was.surfaceContainerHigh, now.surfaceContainerHigh)
            }
        }
    }

    @Test
    fun `a grey theme is grey all through, its surfaces as well`() {
        for (dark in listOf(true, false)) {
            val scheme = CoverAccent.scheme(Hct.fromInt(CoverAccent.GREY), dark)
            for (colour in listOf(scheme.primary, scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiary, scheme.surface, scheme.surfaceContainerHigh, scheme.outline)) {
                assertTrue("#%06x".format(colour and 0xffffff), Hct.fromInt(colour).chroma < 5)
            }
        }
    }

    @Test
    fun `what is written on a muted accent can still be read`() {
        for (colours in listOf(blackAndWhitePhoto, blackCover, greys, washedOut)) {
            for (dark in listOf(true, false)) {
                val scheme = CoverAccent.scheme(Hct.fromInt(CoverAccent.source(colours)), dark)
                assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.5)
                assertTrue(contrast(scheme.primaryContainer, scheme.onPrimaryContainer) >= 4.5)
                assertTrue(contrast(scheme.surface, scheme.onSurface) >= 4.5)
                assertTrue("the accent stands out from the page", contrast(scheme.primary, scheme.surface) >= 3.0)
            }
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val light = maxOf(Hct.fromInt(a).tone, Hct.fromInt(b).tone)
        val dark = minOf(Hct.fromInt(a).tone, Hct.fromInt(b).tone)
        return com.google.material.color.contrast.Contrast.ratioOfTones(light, dark)
    }

    private companion object {
        /** DefaultThemeColor, which is a Compose colour and so not for a test without Android. */
        const val DefaultThemeColorArgb = 0xFFED5564.toInt()
    }
}

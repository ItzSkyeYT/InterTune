/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import com.google.material.color.dynamiccolor.DynamicScheme
import com.google.material.color.dynamiccolor.Variant
import com.google.material.color.hct.Hct
import com.google.material.color.palettes.TonalPalette
import com.google.material.color.scheme.SchemeTonalSpot
import com.google.material.color.score.Score
import com.google.material.color.utils.MathUtils

/**
 * How much colour the app takes from a cover.
 *
 * The theme was made from the hue of the cover's best colour and nothing else: SchemeTonalSpot
 * gives its accent one strength whatever it is handed. So a black and white photograph with a
 * breath of yellow in its whites came out with lime green controls. A black cover came out teal:
 * the scorer counts the greys' pixels for whatever colour sits near the hue a grey happens to
 * have, and passed a bluish swatch that was a two-hundredth of the picture. And a cover with no
 * colour at all came out blue, the scorer's stand-in for a cover it finds nothing in.
 *
 * Here the strength follows the cover. The hue is still the scorer's choice. How strong it is
 * taken is read off the strongest of the cover's main colours of about that hue, counting only
 * those that are a hundredth of the picture or more. A colour that is one gives the theme it
 * always did. A tint gives a theme tinted about as much, and a cover of greys a grey one.
 *
 * [source] hands that over as one colour, since one colour is what the theme is kept as: its hue
 * is the hue to use, and its chroma says how much of it, which [scheme] reads back.
 */
object CoverAccent {
    /** The chroma, in HCT, up to which a cover's colour counts as grey. */
    const val GREY_UNTIL = 8.0

    /** The chroma from which a cover's colour is taken at full strength. */
    const val FULL_FROM = 20.0

    /** What a cover with no colour to take gives. [scheme] makes a grey theme of it. */
    const val GREY = 0xFF808080.toInt()

    /** Colours this far apart in hue, in degrees, or less are the same colour in lighter and darker. */
    private const val SAME_HUE = 30.0

    /** A colour the cover has less of than this does not say how coloured the cover is. */
    private const val LEAST_SHARE = 0.01

    /** The tone a made-up source is given: every hue has room for [FULL_FROM] and more at it. */
    private const val SOURCE_TONE = 50.0

    /** The chroma a made-up source is given for a cover in full colour: past [FULL_FROM], rounding or not. */
    private const val SOURCE_FULL = FULL_FROM + 4.0

    /**
     * The colour to build the theme from, out of a cover's main colours and how many pixels each
     * stands for.
     */
    fun source(colorsToPopulation: Map<Int, Int>): Int {
        val pick = Score.score(colorsToPopulation, 1, GREY, true).first()
        if (pick == GREY) return GREY
        val picked = Hct.fromInt(pick)
        val all = colorsToPopulation.values.sumOf { it.toLong() }.coerceAtLeast(1)
        val strongest = colorsToPopulation.entries
            .filter { it.value >= LEAST_SHARE * all }
            .map { Hct.fromInt(it.key) }
            .filter { MathUtils.differenceDegrees(it.hue, picked.hue) <= SAME_HUE }
            .maxOfOrNull { it.chroma } ?: 0.0
        return when {
            strongest <= GREY_UNTIL -> GREY
            strongest < FULL_FROM -> Hct.from(picked.hue, strongest, SOURCE_TONE).toInt()
            picked.chroma >= FULL_FROM -> pick
            else -> Hct.from(picked.hue, SOURCE_FULL, SOURCE_TONE).toInt()
        }
    }

    /**
     * The colour to paint something in that is to be the cover's colour, as the widget's
     * background is. For a cover with a colour to take it is the scorer's, as it always was.
     * For one with none ([source] says grey) the scorer's answer is its stand-in blue, or the
     * bluish scrap of a black cover, and a widget painted in that is not the cover's colour at
     * all: such a cover gives the colour it has most of, which for a black one is its black.
     */
    fun paint(colorsToPopulation: Map<Int, Int>): Int {
        if (source(colorsToPopulation) != GREY) return Score.score(colorsToPopulation).first()
        return colorsToPopulation.maxByOrNull { it.value }?.key ?: GREY
    }

    /** The part of the usual strength a source colour of [chroma] is given, from 0 to 1. */
    fun share(chroma: Double): Double = ((chroma - GREY_UNTIL) / (FULL_FROM - GREY_UNTIL)).coerceIn(0.0, 1.0)

    /**
     * The scheme for [source]. SchemeTonalSpot itself for a colour, and its five palettes at a
     * [share] of their chroma for a tint, the surfaces' own slight tint with them.
     */
    fun scheme(source: Hct, dark: Boolean): DynamicScheme {
        val share = share(source.chroma)
        if (share >= 1.0) return SchemeTonalSpot(source, dark, 0.0)
        val hue = source.hue
        return DynamicScheme(
            source,
            Variant.TONAL_SPOT,
            dark,
            0.0,
            TonalPalette.fromHueAndChroma(hue, PRIMARY * share),
            TonalPalette.fromHueAndChroma(hue, SECONDARY * share),
            TonalPalette.fromHueAndChroma(MathUtils.sanitizeDegreesDouble(hue + TERTIARY_TURN), TERTIARY * share),
            TonalPalette.fromHueAndChroma(hue, NEUTRAL * share),
            TonalPalette.fromHueAndChroma(hue, NEUTRAL_VARIANT * share),
        )
    }

    // SchemeTonalSpot's own numbers.
    private const val PRIMARY = 36.0
    private const val SECONDARY = 16.0
    private const val TERTIARY = 24.0
    private const val TERTIARY_TURN = 60.0
    private const val NEUTRAL = 6.0
    private const val NEUTRAL_VARIANT = 8.0
}

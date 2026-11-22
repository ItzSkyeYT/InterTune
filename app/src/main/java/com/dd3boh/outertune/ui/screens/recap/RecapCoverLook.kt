/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dd3boh.outertune.recap.MadeCoverPlan
import com.dd3boh.outertune.recap.RecapMadeCover
import com.dd3boh.outertune.recap.RecapPalette
import com.dd3boh.outertune.ui.player.LivingField
import com.dd3boh.outertune.utils.LocalCovers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The slides that reveal the year's favourites wear the colours of the cover they show (Ti-Die's
 * idea of 22 Nov 2026, "especially the one that reveals the songs having the colors change to
 * match"): the top artists, each step of the countdown and number one. The paper is the cover's
 * main colour and the shapes on it take its two others, as the Living blur finds them
 * (LivingField.mainColours); the words are the Recap's ink or its cream, whichever reads, and the
 * paper is made lighter or darker until they read at [LEAST] to 1 or better, grain and all. The
 * countdown keeps the ink of its big numeral and its list at every step, so each of its papers is
 * light enough for that: its list stays put while a step's paper comes in on its strips and its
 * shapes turn to the new colours. A song from a file with no picture has the colours of the cover
 * the Recap made it (RecapMadeCover). Two pages one after the other never share a paper: where the
 * cover's main colour is too close to the page before or after, one of its others is the paper.
 * The Recap is a celebration: a cover whose main colour is greyer than [FLOOR] (black, white and
 * grey covers, muddy ones) leaves the page its own paper, and only its most vivid colour, where
 * that one clears the floor, goes to the shapes.
 */
internal object CoverLook {
    /** The slides that take their cover's colours. Empty, and every slide keeps its own paper: the one switch. */
    val KINDS: Set<SlideKind> = setOf(SlideKind.ARTISTS, SlideKind.COUNTDOWN, SlideKind.NUMBER_ONE)

    /**
     * A cover's colour makes a paper, or a shape's colour, from this much colour on (OKLab's chroma):
     * as much as the design's palest papers have, its sky and its lilac (about 0.101). A muddy brown
     * (0.079), a dull teal (0.064) and every grey fall short.
     */
    const val FLOOR = 0.1

    /** The words on a paper, and on an accent, read at this much or better. */
    const val LEAST = 4.5

    /** Two papers closer than this in OKLab look the same when one comes in over the other. */
    const val TOO_CLOSE = 0.1

    /** The grain darkens a paper by about 6.5% (PaperGrain): the words are checked on both. */
    private const val GRAIN = 0.935f

    /** How far a colour is made lighter or darker at a time, in OKLab's lightness. */
    private const val STEP = 0.01

    /** The design's own neutrals, which go with any paper and stay as they are. */
    private val NEUTRALS = setOf(RecapInk.Ink, RecapInk.Cream, RecapInk.White)

    /** A page's look: its paper and words, and the two colours its shapes take for the design's loud ones. */
    class Look(val paper: SlidePaper, val shapes: List<Color>)

    /** The covers whose colours the slides need, in the order they come. */
    fun models(slides: List<Slide>): List<Any?> =
        slides.filter { it.kind in KINDS }.flatMap { slide -> (0 until slide.steps).map { slide.cover(it) } }

    /**
     * The look of each page that takes its cover's colours, by its place in the story ([pageOf]):
     * those whose cover [colours] has (its three main colours, the main one first) and nothing for
     * the rest, which keep their own paper. A cover whose main colour is greyer than [floor] leaves
     * the page its own paper too, its shapes in the cover's most vivid colour where that one is not;
     * a [floor] of 0 lets every cover make its page's paper.
     */
    fun plan(slides: List<Slide>, floor: Double = FLOOR, colours: (Any?) -> IntArray?): Map<Int, Look> {
        if (KINDS.isEmpty()) return emptyMap()
        class Page(val kind: SlideKind, val step: Int, val main: IntArray?) {
            /** Its paper is its cover's: the cover's main colour has colour enough. */
            val wears = main != null && vivid(main[0], floor)
        }
        val pages = slides.flatMap { slide ->
            (0 until slide.steps).map { step -> Page(slide.kind, step, if (slide.kind in KINDS) colours(slide.cover(step))?.takeIf { it.isNotEmpty() } else null) }
        }
        val looks = HashMap<Int, Look>()
        var before = START_PAPER.paper
        pages.forEachIndexed { i, page ->
            val main = page.main
            val own = paperOf(page.kind, page.step)
            before = when {
                main == null -> own.paper
                !page.wears -> {
                    // Its own paper, and the cover's most vivid colour on its shapes where that one has colour enough.
                    main.filter { vivid(it, floor) }.maxByOrNull { chroma(Color(it or OPAQUE)) }?.let { looks[i] = Look(own, shapesOn(own.paper, Color(it or OPAQUE))) }
                    own.paper
                }
                else -> {
                    // The page after keeps clear of this one itself where it wears its cover's paper too.
                    val after = pages.getOrNull(i + 1)?.takeIf { !it.wears }?.let { paperOf(it.kind, it.step).paper }
                    val look = of(main, listOfNotNull(before, after), if (page.kind == SlideKind.COUNTDOWN) RecapInk.Ink else null)
                    looks[i] = look
                    look.paper.paper
                }
            }
        }
        return looks
    }

    /** Whether [argb] has [floor] of colour or more. */
    private fun vivid(argb: Int, floor: Double) = chroma(Color(argb or OPAQUE)) >= floor

    /** The shapes on a page's own [paper]: the cover's [vivid] colour, kept clear of the paper, and the ink or the cream, whichever stands out more. */
    private fun shapesOn(paper: Color, vivid: Color): List<Color> =
        listOf(away(vivid, paper), listOf(RecapInk.Cream, RecapInk.Ink).maxBy { distance(it, paper) })

    /**
     * The look a cover of these [main] colours gives a page whose paper must stay clear of those in
     * [avoid]: the first of its colours that does once its words read on it, its words in [ink]
     * where the slide needs that one, else whichever of ink and cream reads better.
     */
    fun of(main: IntArray, avoid: List<Color>, ink: Color? = null): Look {
        val colours = main.map { Color(it or OPAQUE) }.distinct()
        val papers = colours.map { paper(it, ink ?: inkFor(it)) }
        val chosen = papers.indexOfFirst { (p, _) -> avoid.all { distance(p, it) >= TOO_CLOSE } }
        val from = if (chosen >= 0) chosen else 0
        val (paper, words) = if (chosen >= 0) papers[chosen] else apart(colours[0], papers[0], avoid)
        val shapes = (colours.filterIndexed { i, _ -> i != from }.map { away(it, paper) } + listOf(RecapInk.Cream, RecapInk.Ink))
            .distinct().take(2)
        val (accent, onAccent) = if (ink != null) ink to other(ink) else accentOf(shapes.maxBy { chroma(it) }, paper, words)
        return Look(SlidePaper(paper, words, accent, onAccent, shapes), shapes)
    }

    /**
     * [sets] with the design's loud colours taken by [shapes] in turn, the first met by the first,
     * so that the layers of a shape stay two colours; the ink and the cream stay.
     */
    fun recolour(sets: List<Poses>, shapes: List<Color>): List<Poses> {
        if (shapes.isEmpty()) return sets
        val loud = sets.flatMap { set -> ActorId.entries.flatMap { set.set[it]?.c.orEmpty() } }.filter { it !in NEUTRALS }.distinct()
        if (loud.isEmpty()) return sets
        val to = loud.withIndex().associate { (i, c) -> c to shapes[i % shapes.size] }
        return sets.map { set ->
            Poses(set.at, set.set.mapValues { (_, p) -> if (p.c.isEmpty()) p else p.coloured(p.c.map { to[it] ?: it }) }, set.mt, set.me, set.stagger)
        }
    }

    private fun Pose.coloured(colours: List<Color>) = Pose(sh, x, y, at, dx, dy, s, r, dr, colours, k, o, spin, w, hide, mt, me, fit)

    /** [c] made lighter (for the ink) or darker (for the cream), its hue kept, until [words] read on it. */
    private fun paper(c: Color, words: Color): Pair<Color, Color> {
        val step = if (light(words) < 0.5) STEP else -STEP
        var l = light(c)
        var p = c
        while (!reads(words, p) && l in 0.0..1.0) {
            l += step
            p = withLight(c, l)
        }
        return p to words
    }

    /** Where none of the cover's colours keeps clear of [avoid]: its main one further from its words until it does. */
    private fun apart(c: Color, start: Pair<Color, Color>, avoid: List<Color>): Pair<Color, Color> {
        val (first, words) = start
        val step = if (light(words) < 0.5) STEP else -STEP
        var l = light(first)
        var p = first
        while (avoid.any { distance(p, it) < TOO_CLOSE } && (l + step) in 0.0..1.0) {
            l += step
            p = withLight(c, l)
        }
        return p to words
    }

    /** A shape's colour, made lighter or darker where it would be lost on [paper]. */
    private fun away(c: Color, paper: Color): Color {
        if (distance(c, paper) >= TOO_CLOSE) return c
        val ways = if (light(c) >= light(paper)) listOf(STEP, -STEP) else listOf(-STEP, STEP)
        for (step in ways) {
            var l = light(c)
            while (l in 0.0..1.0) {
                val x = withLight(c, l)
                if (distance(x, paper) >= TOO_CLOSE) return x
                l += step
            }
        }
        return c
    }

    /**
     * The accent: [c] moved to the words' side, darker on a light paper and lighter on a dark one,
     * until it reads on the paper as words do and the other ink reads on it.
     */
    private fun accentOf(c: Color, paper: Color, words: Color): Pair<Color, Color> {
        val on = other(words)
        val step = if (light(words) < 0.5) -STEP else STEP
        var l = light(c)
        var a = c
        while (!(reads(a, paper) && contrast(on, a) >= LEAST) && l in 0.0..1.0) {
            l += step
            a = withLight(c, l)
        }
        return a to on
    }

    private fun other(ink: Color) = if (ink == RecapInk.Ink) RecapInk.Cream else RecapInk.Ink

    /** The ink or the cream, whichever reads better on [c], grain and all. */
    private fun inkFor(c: Color): Color = if (worst(RecapInk.Ink, c) >= worst(RecapInk.Cream, c)) RecapInk.Ink else RecapInk.Cream

    private fun reads(words: Color, paper: Color) = worst(words, paper) >= LEAST

    private fun worst(words: Color, paper: Color) =
        minOf(contrast(words, paper), contrast(words, Color(paper.red * GRAIN, paper.green * GRAIN, paper.blue * GRAIN)))

    /** Contrast the WCAG way. */
    fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun lab(c: Color): DoubleArray =
        RecapPalette.oklab((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())

    private fun light(c: Color) = lab(c)[0]

    private fun chroma(c: Color) = lab(c).let { hypot(it[1], it[2]) }

    /** How far apart two colours look, in OKLab. */
    fun distance(a: Color, b: Color): Double {
        val x = lab(a)
        val y = lab(b)
        return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
    }

    /** [c] at lightness [l], as colourful and of the same hue where the screen can show that. */
    private fun withLight(c: Color, l: Double): Color {
        val x = lab(c)
        return Color(RecapPalette.oklch(l.coerceIn(0.0, 1.0), hypot(x[1], x[2]), Math.toDegrees(atan2(x[2], x[1]))))
    }

    /** Fully opaque, as ARGB. */
    private const val OPAQUE = -0x1000000
}

/**
 * The covers' main colours (LivingField.mainColours), read once each and kept while the app runs,
 * by what the image loader is handed: a slide shown again after the phone is turned has them at once.
 */
internal object CoverColours {
    /** Covers are measured this small: about the four thousand pixels the Living blur looks at. */
    private const val PX = 64

    private val known = HashMap<Any, IntArray>()

    fun of(model: Any?): IntArray? = model?.let { synchronized(known) { known[it] } }

    /** Reads those of [models] not read yet: true when any was. A cover that cannot be read is left to its slide's own paper. */
    suspend fun read(context: Context, covers: RecapCovers, models: Collection<Any?>): Boolean {
        var any = false
        for (model in models.filterNotNull().distinct()) {
            if (of(model) != null) continue
            val main = try {
                measure(context, covers, model)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: continue
            synchronized(known) { known[model] = main }
            any = true
        }
        return any
    }

    private suspend fun measure(context: Context, covers: RecapCovers, model: Any): IntArray? {
        val bitmap: Bitmap = if (model is RecapMadeCover) {
            // Its own picture, the file's or its folder's, as RecapCoverFetcher finds it; or the one the Recap made it.
            withContext(Dispatchers.IO) {
                model.path?.let { path -> runCatching { RecapCoverFetcher.embedded(path, PX) ?: LocalCovers.of(context, path, PX) }.getOrNull() }
            } ?: return made(model)
        } else {
            covers.bitmap(model, PX) ?: return null
        }
        return withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            LivingField.mainColours(pixels, bitmap.width, bitmap.height)
        }
    }

    /** A made cover's colours, as RecapCoverFetcher draws it: its paper, its shape's and its words'. */
    fun made(cover: RecapMadeCover): IntArray {
        val (paper, ink, accent) = RecapCoverFetcher.PAPERS[MadeCoverPlan.paper(cover.title, RecapCoverFetcher.PAPERS.size)]
        return intArrayOf(paper.toArgb(), accent.toArgb(), ink.toArgb())
    }
}

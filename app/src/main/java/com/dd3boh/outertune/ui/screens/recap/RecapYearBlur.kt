/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import com.dd3boh.outertune.playback.MusicLevels
import com.dd3boh.outertune.recap.RecapPalette
import com.dd3boh.outertune.ui.player.LivingDiscs
import com.dd3boh.outertune.ui.player.LivingField
import com.dd3boh.outertune.ui.player.LivingMotion
import com.dd3boh.outertune.ui.player.RedrawPace
import com.dd3boh.outertune.ui.player.drawnSmall
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The year card's page on a Living blur of the year's top three covers (his pick of 22 Nov 2026,
 * from living-2-year-card.png): the paper behind the card moves slowly, as the player's Living blur
 * does with nothing to go by, its bass in number one's colour, its voice in number two's and its top
 * in number three's, each cover's main colour where that has colour enough (CoverLook.FLOOR), else
 * its most vivid. The card, its buttons, the paper shapes and the dots stay as they are. The page's
 * words are the Recap's ink or its cream, whichever reads, and the three colours are made lighter or
 * darker until it reads at CoverLook.LEAST to 1 or better wherever the blur rests, grain and all.
 * The picture that is shared gets a still of it for paper, dimmed until its words read on it as they
 * do on the design's blue. A song from a file with no picture gives its made cover's colours
 * (CoverColours). With Remove animations on, it stands still.
 */
internal object YearBlur {
    /** The switch: false, and the year card's page keeps its own cream paper and the card shared its blue. */
    const val ON = true

    /** The shared card's paper is dimmed towards the ink at least this much, and more where its words need it. */
    const val LEAST_DIM = 0.38f

    /** The still's size, for a card of 1080 by 1920: an eighth each way, as the player draws its picture. */
    const val STILL_WIDE = RecapCards.WIDTH / 8
    const val STILL_TALL = RecapCards.HEIGHT / 8

    /** How long the still's ribbons have drifted, from rest: a few seconds of the slow drift, the same every time. */
    private const val STILL_SECONDS = 4f

    /** A patch is drawn up to 3% darker than its place's colour (LivingMotion.grain). */
    private const val PATCH_DARKEST = 0.97f

    /** The grain darkens the page by about 6.5% (PaperGrain): the words are checked on both. */
    private const val GRAIN = 0.935f

    /** How far the colours are made lighter or darker at a time, in OKLab's lightness. */
    private const val STEP = 0.01

    private const val OPAQUE = -0x1000000

    /** The blur's three colours, bass, voice and top, and the page as the slide's words and bars take it. */
    class Look(val colours: IntArray, val paper: SlidePaper)

    /** The look for the top three covers' main colours ([mains], number one's first, null where one could not be read), or null for none. */
    fun of(mains: List<IntArray?>): Look? {
        if (!ON) return null
        return colours(mains)?.let { readable(it) }
    }

    /**
     * Bass, voice and top from the top three covers in turn: each its main colour where that has
     * colour enough, else its most vivid. With fewer than three, the first one's other colours make
     * up the rest. Null when no cover could be read.
     */
    fun colours(mains: List<IntArray?>): IntArray? {
        val read = mains.take(3).filterNotNull().filter { it.isNotEmpty() }
        if (read.isEmpty()) return null
        val picks = read.map { vividOf(it) or OPAQUE }.toMutableList()
        for (c in read[0]) if (picks.size < 3 && (c or OPAQUE) !in picks) picks += c or OPAQUE
        while (picks.size < 3) picks += picks[0]
        return picks.toIntArray()
    }

    private fun vividOf(mains: IntArray): Int = mains[0].takeIf { chroma(it) >= CoverLook.FLOOR } ?: mains.maxBy { chroma(it) }

    /**
     * The page's words in the ink or the cream, whichever reads better on the blur, and [three] made
     * lighter (for the ink) or darker (for the cream) until it reads at rest on each range as the
     * player lights it (LivingField.OWN_REST), on the darkest of its patches, grain and all; for the
     * ink, also half way between two ranges, where the mix of two colours comes out darker than either.
     */
    fun readable(three: IntArray): Look {
        val ink = if (worst(RecapInk.Ink, three) >= worst(RecapInk.Cream, three)) RecapInk.Ink else RecapInk.Cream
        val step = if (ink == RecapInk.Ink) STEP else -STEP
        var by = 0.0
        var colours = three
        while (worst(ink, colours) < CoverLook.LEAST && by in -1.0..1.0) {
            by += step
            colours = IntArray(3) { lighter(three[it], by) }
        }
        val own = paperOf(SlideKind.CARD)
        val under = Color(LivingField.lit(colours[MusicLevels.BASS], LivingField.REST_LIGHT))
        return Look(colours, SlidePaper(under, ink, own.accent, own.onAccent, own.rims))
    }

    /** The colours each range shows at rest, on its darkest patch. */
    fun atRest(colours: IntArray): List<Color> {
        val shown = IntArray(3) { LivingField.lit(colours[it], LivingField.OWN_REST[it] * PATCH_DARKEST) }
        val mixes = listOf(0 to 1, 1 to 2, 0 to 2).map { (a, b) -> LivingField.between(shown[a], shown[b], 0.5f) }
        return (shown.toList() + mixes).map { Color(it or OPAQUE) }
    }

    /** How well [ink] reads on the least readable of what the blur shows at rest, grain and all. */
    private fun worst(ink: Color, colours: IntArray): Double = atRest(colours).minOf { c ->
        min(CoverLook.contrast(ink, c), CoverLook.contrast(ink, Color(c.red * GRAIN, c.green * GRAIN, c.blue * GRAIN)))
    }

    private fun chroma(argb: Int) = lab(argb).let { hypot(it[1], it[2]) }

    private fun lab(argb: Int) = RecapPalette.oklab((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff)

    /** [argb] made [by] lighter in OKLab, its hue kept and as colourful as the screen can show it. */
    private fun lighter(argb: Int, by: Double): Int {
        val x = lab(argb)
        return RecapPalette.oklch((x[0] + by).coerceIn(0.0, 1.0), hypot(x[1], x[2]), Math.toDegrees(atan2(x[2], x[1])))
    }

    /** The player's picture of [colours] for a room [wide] by [tall]: in ribbons, five patches across, as LivingBackground makes it. */
    fun motion(colours: IntArray, wide: Int, tall: Int): LivingMotion {
        val along = LivingField.along(max(wide, tall).toFloat(), min(wide, tall).toFloat(), LivingField.FLOW_ACROSS, LivingField.FLOW_MOST_ALONG)
        return (if (tall >= wide) LivingMotion(LivingField.FLOW_ACROSS, along) else LivingMotion(along, LivingField.FLOW_ACROSS)).apply {
            separate = true
            turnTo(IntArray(count) { colours[MusicLevels.BASS] }, glow = colours[MusicLevels.BASS], mains = colours)
        }
    }

    /**
     * The shared card's paper: the blur, a few seconds into its drift from rest, drawn [wide] by
     * [tall] as LivingDiscs draws it and blurred a little, then dimmed towards the ink until its
     * lightest part is no lighter than the design's blue, which the card's words were made to read on.
     */
    fun still(colours: IntArray, wide: Int = STILL_WIDE, tall: Int = STILL_TALL): IntArray {
        val motion = motion(colours, wide, tall)
        motion.settle()
        repeat((STILL_SECONDS * LivingMotion.STEPS_A_SECOND).toInt()) { motion.step(LivingMotion.STEP_SECONDS, null, true) }
        val pixels = field(motion, wide, tall)
        val blue = RecapInk.Blue.luminance()
        val lightest = pixels.maxOf { Color(it).luminance() }
        val ink = RecapInk.Ink
        var dim = LEAST_DIM
        fun dimmed(c: Color, by: Float) = Color(c.red + (ink.red - c.red) * by, c.green + (ink.green - c.green) * by, c.blue + (ink.blue - c.blue) * by)
        val brightest = Color(pixels.maxBy { Color(it).luminance() })
        while (lightest > 0f && dimmed(brightest, dim).luminance() > blue && dim < 1f) dim += 0.01f
        return IntArray(pixels.size) { i -> dimmed(Color(pixels[i]), min(dim, 1f)).let { c -> (OPAQUE) or (c.red.channel() shl 16) or (c.green.channel() shl 8) or c.blue.channel() } }
    }

    /** The still as a picture for the card. */
    fun stillBitmap(colours: IntArray): Bitmap = Bitmap.createBitmap(still(colours), STILL_WIDE, STILL_TALL, Bitmap.Config.ARGB_8888)

    private fun Float.channel() = (this * 255f).roundToInt().coerceIn(0, 255)

    /**
     * LivingDiscs.field with no Canvas: what shows where the patches leave anything uncovered, the
     * soft discs, the glow on both edges; then a slight blur, as the phone's RenderEffect gives it.
     */
    private fun field(motion: LivingMotion, wide: Int, tall: Int): IntArray {
        val red = FloatArray(wide * tall)
        val green = FloatArray(wide * tall)
        val blue = FloatArray(wide * tall)
        val under = motion.under
        red.fill((under shr 16 and 0xff).toFloat())
        green.fill((under shr 8 and 0xff).toFloat())
        blue.fill((under and 0xff).toFloat())
        fun disc(x: Float, y: Float, halfWidth: Float, halfHeight: Float, color: Int, strength: Float) {
            if (halfWidth <= 0f || halfHeight <= 0f) return
            for (py in max(0, (y - halfHeight).toInt()) until min(tall, (y + halfHeight).toInt() + 1)) {
                for (px in max(0, (x - halfWidth).toInt()) until min(wide, (x + halfWidth).toInt() + 1)) {
                    val nx = (px + 0.5f - x) / halfWidth
                    val ny = (py + 0.5f - y) / halfHeight
                    val inside = max(0f, 1f - (nx * nx + ny * ny))
                    val a = inside * inside * strength.coerceIn(0f, 1f)
                    val at = py * wide + px
                    red[at] += ((color shr 16 and 0xff) - red[at]) * a
                    green[at] += ((color shr 8 and 0xff) - green[at]) * a
                    blue[at] += ((color and 0xff) - blue[at]) * a
                }
            }
        }
        val cellWidth = wide.toFloat() / motion.columns
        val cellHeight = tall.toFloat() / motion.rows
        for (i in 0 until motion.count) {
            val radius = motion.radius(i)
            disc(motion.x(i) * wide, motion.y(i) * tall, radius * cellWidth, radius * cellHeight, motion.color(i), 1f)
        }
        disc(wide / 2f, tall.toFloat(), wide * 0.8f, tall * motion.glowHeight(), motion.glow, motion.glowStrength())
        disc(wide / 2f, 0f, wide * 0.8f, tall * motion.glowHeight() * 0.7f, motion.glow, motion.glowStrength() * 0.8f)
        for (channel in listOf(red, green, blue)) repeat(2) {
            val was = channel.copyOf()
            for (y in 0 until tall) for (x in 0 until wide) {
                var sum = 0f
                var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val px = x + dx
                    val py = y + dy
                    if (px in 0 until wide && py in 0 until tall) {
                        sum += was[py * wide + px]
                        n++
                    }
                }
                channel[y * wide + x] = sum / n
            }
        }
        return IntArray(wide * tall) { i ->
            OPAQUE or (red[i].roundToInt().coerceIn(0, 255) shl 16) or (green[i].roundToInt().coerceIn(0, 255) shl 8) or blue[i].roundToInt().coerceIn(0, 255)
        }
    }
}

/**
 * The year card's page paper as the year's blur (YearBlur), between the papers and the shapes:
 * all of it while the year card's page is up, inside the new sheet while that page comes in, outside
 * the next one's sheet and its edge papers while it goes. Drawn as the player draws its Living blur
 * (LivingDiscs, an eighth of the size, stretched and lightly blurred), redrawn thirty times a second
 * at most and only while it moves ([moving]: the story playing); with Remove animations on, still.
 */
@Composable
internal fun YearBlurPaper(stage: RecapStage, moving: Boolean) {
    val look = stage.year ?: return
    val coming = stage.current.kind == SlideKind.CARD
    val going = !coming && stage.leaving?.page?.kind == SlideKind.CARD
    if (!coming && !going) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = constraints.maxWidth
        val tall = constraints.maxHeight
        val motion = remember(look, wide, tall) { YearBlur.motion(look.colours, wide, tall) }
        val discs = remember { LivingDiscs() }
        val pace = remember { RedrawPace() }
        val stretch = remember { mutableStateOf(Offset(1f, 1f)) }
        val sheet = remember { Path() }
        var frame by remember { mutableLongStateOf(0L) }
        LaunchedEffect(motion, moving) {
            if (stage.still || !moving) {
                // Still: as it rests, drawn once. Held: where it is, until the story goes on.
                if (stage.still) motion.settle()
                frame++
                return@LaunchedEffect
            }
            while (true) {
                withFrameNanos { now -> if (motion.frame(now, pace, playing = true)) frame = now }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // With Remove animations on, the pages cross-fade: so does it. The story's clock is
                    // read only while a page changes, so a resting page is not drawn again for it.
                    val w = stage.wipe
                    alpha = if (w == null || w.kind != WipeKind.FADE) 1f else {
                        val now = stage.clock.now
                        if (w.done(now)) 1f else RecapMotion.Ease.transform(w.progress(now)).let { if (coming) it else 1f - it }
                    }
                }
                .drawWithContent {
                    val w = stage.wipe
                    if (w == null || w.kind == WipeKind.FADE) {
                        drawContent()
                        return@drawWithContent
                    }
                    val now = stage.clock.now
                    when {
                        // Once the sheet is all in: all of it where it came, nothing where it went.
                        w.done(now) -> if (coming) drawContent()
                        coming -> {
                            w.inside(now, size, sheet)
                            clipPath(sheet) { this@drawWithContent.drawContent() }
                        }
                        else -> {
                            // Outside the sheet coming in, and outside the papers at its edge.
                            if (w.kind == WipeKind.GROW && w.rims.isNotEmpty()) w.rim(w.rims.lastIndex, now, sheet) else w.inside(now, size, sheet)
                            clipPath(sheet, ClipOp.Difference) { this@drawWithContent.drawContent() }
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize().drawnSmall(stretch, 1f)) {
                @Suppress("UNUSED_VARIABLE")
                val redrawnAt = frame
                drawIntoCanvas { canvas -> discs.field(canvas.nativeCanvas, motion, size.width, size.height) }
            }
        }
    }
}

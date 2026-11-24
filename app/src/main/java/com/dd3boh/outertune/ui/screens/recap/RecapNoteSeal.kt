/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Matrix as PlainMatrix
import androidx.compose.ui.graphics.Canvas as PictureCanvas

/**
 * A note to next year's you, sealed (his pick of 24 Nov 2026, of three takes filmed after "god the sealing animation
 * looks so ass" on the letter and wax stamp of 22 Nov): a plum envelope, the colour of "next year's you", lined with
 * the slide's sky, slides up from the foot of the page round the paper as the paper goes down into it; its flap shuts
 * over it with depth; a wax seal of the app's mark is slapped on, as the Recap's stickers are; and it flies off the
 * right of the page, to next year, as the sealed card comes in where the paper was. Gone by 1.2 s, the card settled
 * and the buttons back by 1.65 s, one firm tick as the wax lands.
 *
 * The real paper does its own moving (sealPaper): it goes down into the pocket, is hidden once the flap is shut over
 * it, and comes back as the sealed card. The envelope is drawn under it (its back, its lining, its flap while open:
 * SealBehind) and over it (its pocket, its flap shutting, the wax: SealOver). It is never faded: plum fading over the
 * lemon page goes through a muddy brown (seen on the emulator, 24 Nov), so it comes on from below the foot of the page
 * and leaves past its right side, whole. Everything moves in drawing only, on the story's clock; with Remove
 * animations on there is nothing and the note is sealed at once. Times in ms after the seal, as plain arithmetic
 * (RecapNoteSealTest).
 */
internal object NoteSeal {
    const val RISE_MS = 420
    const val IN_FROM = 120
    const val IN_MS = 320
    const val FLAP_FROM = 440
    const val FLAP_MS = 180
    const val WAX_FROM = 620
    const val WAX_MS = 180
    const val GO_FROM = 840
    const val GO_MS = 360
    const val CARD_FROM = 980

    /** The sealed card coming in where the paper was, as the paper first came: from below, turned, a touch past and back. */
    const val CARD_MS = 640

    /** The buttons and the line under the paper: gone the moment it is sealed, back up as the card lands. */
    const val CHROME_FROM = CARD_FROM + 250
    const val CHROME_IN_MS = 420

    /** Where in the wax's slap it lands: the firm tick. */
    const val SLAP_LANDS = 0.6f
    val PRESS_MS: Int = WAX_FROM + (WAX_MS * SLAP_LANDS).toInt()

    /** When it is over and everything rests. */
    val TOTAL_MS: Int = maxOf(CARD_FROM + CARD_MS, CHROME_FROM + CHROME_IN_MS)

    /** The sealed card's seal is turned this much on its paper (Sealed). */
    const val MARK_TURN = -10f

    /** How far the letter goes down into its envelope, in dp. */
    const val INTO_DP = 22f

    /** The flap, open: just past upright, in degrees from shut. */
    const val FLAP_OPEN = 110f

    /** The envelope up from the foot of the page: quick, then gently into place, never past it. */
    val Rise: Easing = RecapMotion.Snap

    /** The flap pushed shut: quicker and quicker, and down. */
    val Shut: Easing = CubicBezierEasing(0.6f, 0f, 0.9f, 0.6f)

    /** The letter lowered into its envelope. */
    val Into: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /**
     * Debug only: how many times slower it runs, to film it on an emulator that draws few frames a second (the debug
     * sample's `--ei slow 3`). 1 in every build otherwise.
     */
    @Volatile
    var slow: Float = 1f

    /** How far into the seal one made at [at] is at [now], both on the story's clock. */
    fun since(now: Long, at: Long): Long = if (slow <= 1f) now - at else ((now - at) / slow).toLong()

    /** Whether it is moving [ms] after the seal: never with Remove animations on. */
    fun running(ms: Long, still: Boolean): Boolean = !still && ms >= 0 && ms < TOTAL_MS

    /** Whether the paper still holds the words just sealed: until the flap is shut over it. */
    fun letterOnPaper(ms: Long, still: Boolean): Boolean = running(ms, still) && ms < FLAP_FROM + FLAP_MS

    /** How much the buttons and the line under the paper show: none from the seal until the card has come, then up. */
    fun chromeAlpha(ms: Long, still: Boolean): Float {
        if (!running(ms, still)) return 1f
        if (ms < CHROME_FROM) return 0f
        return RecapMotion.Spring.transform(((ms - CHROME_FROM) / CHROME_IN_MS.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /** How far under their place they are as they come back up, as a share of the Recap's rise (RecapMotion.UP_DISTANCE). */
    fun chromeBelow(ms: Long, still: Boolean): Float {
        if (!running(ms, still) || ms < CHROME_FROM) return 0f
        return 1f - RecapMotion.Spring.transform(((ms - CHROME_FROM) / CHROME_IN_MS.toFloat()).coerceIn(0f, 1f))
    }

    /** How far up from the foot of the page the envelope has come, 0 to 1. */
    fun riseShare(ms: Long): Float = Rise.transform((ms / RISE_MS.toFloat()).coerceIn(0f, 1f))

    /** How far down into its envelope the letter has gone, in dp. */
    fun intoDp(ms: Long): Float = INTO_DP * Into.transform(((ms - IN_FROM) / IN_MS.toFloat()).coerceIn(0f, 1f))

    /** The flap, in degrees: [FLAP_OPEN] open, 0 shut on the pocket. */
    fun flapAngle(ms: Long): Float = FLAP_OPEN * (1f - Shut.transform(((ms - FLAP_FROM) / FLAP_MS.toFloat()).coerceIn(0f, 1f)))

    /** How far off the page the envelope has gone, 0 to 1, on the Recap's toss: off slowly, then quicker and quicker. */
    fun goShare(ms: Long): Float = if (ms < GO_FROM) 0f else RecapMotion.TossEasing.transform(((ms - GO_FROM) / GO_MS.toFloat()).coerceIn(0f, 1f))

    /** The sealed card coming in: 0 to 1 of its way on the Recap's settle, or null before it. */
    fun cardShare(ms: Long): Float? =
        if (ms < CARD_FROM) null else RecapMotion.Settle.transform(((ms - CARD_FROM) / CARD_MS.toFloat()).coerceIn(0f, 1f))

    /** How much the real paper shows: the letter until the flap is shut over it, then nothing until its card comes in. */
    fun paperAlpha(ms: Long, still: Boolean): Float {
        if (!running(ms, still) || ms < FLAP_FROM + FLAP_MS) return 1f
        if (ms < CARD_FROM) return 0f
        return RecapMotion.Settle.transform(((ms - CARD_FROM) / (CARD_MS * 0.3f)).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

/** Where the seal finds the paper: plain holders, read in drawing only. */
internal class SealPlaces {
    /** The paper's box as last laid out with the field in it, the size the letter keeps while it is sealed. */
    var letter: IntSize = IntSize.Zero
    var box: LayoutCoordinates? = null

    /** The paper's box on the screen, once it is laid out. */
    fun boxInRoot(): Offset? = box?.takeIf { it.isAttached }?.positionInRoot()
}

// ---- how far things go, in dp and degrees

/** The sealed card comes from this far below its place, turned this much more. */
private const val CARD_FROM_BELOW = 110f
private const val CARD_TURN = 7f

/** The envelope comes up turned this much more, and leaves rising this much, turning and a little smaller. */
private const val ENVELOPE_TURN = 6f
private const val GO_UP = 80f
private const val GO_TURN = 12f
private const val GO_SHRINK = 0.1f
private const val ENVELOPE_CORNER = 6f

/** The envelope gives a little under the wax, for this long. */
private const val DIP_MS = 160
private const val DIP_DP = 3f

/** The wax on the envelope: half its square (about a sixth of the envelope across), and its turn once pressed. */
private const val WAX_R = 30f
private const val WAX_TURN = -8f

/** The flap's eye, this many of its lengths away: enough depth to read as a flap turning, not a squash. */
private const val EYE = 9f

private val ENVELOPE = RecapInk.Plum
private val ENVELOPE_FLAP = lerp(RecapInk.Plum, Color.White, 0.1f)
private val ENVELOPE_BOTTOM = lerp(RecapInk.Plum, Color.White, 0.05f)
private val ENVELOPE_INSIDE = lerp(RecapInk.Plum, RecapInk.Ink, 0.35f)
private val SEAM = RecapInk.Ink.copy(alpha = 0.24f)
private val EDGE_LIT = Color.White.copy(alpha = 0.24f)
private val WAX_DEEP = Color(0xFFDA4757)
private val WAX_SHADE = Color(0xFFB0303F)

/**
 * The real paper while it is sealed: going down into its envelope along its own turn, hidden once the flap is shut over
 * it, then the sealed card coming in where it was. Nothing at rest.
 */
internal fun Modifier.sealPaper(s: SlideScope, note: NoteDraft, turn: Float): Modifier = graphicsLayer {
    val at = note.sealedAt
    if (at < 0) return@graphicsLayer
    val ms = NoteSeal.since(s.stage.clock.now, at)
    if (!NoteSeal.running(ms, s.still)) return@graphicsLayer
    alpha = NoteSeal.paperAlpha(ms, s.still)
    if (ms < NoteSeal.FLAP_FROM + NoteSeal.FLAP_MS) intoEnvelope(ms, turn) else cardComing(ms)
}

/** The letter going down into its envelope, along its own turn. */
private fun GraphicsLayerScope.intoEnvelope(ms: Long, turn: Float) {
    val d = NoteSeal.intoDp(ms).dp.toPx()
    val r = Math.toRadians(turn.toDouble())
    translationX = (-d * sin(r)).toFloat()
    translationY = (d * cos(r)).toFloat()
}

private fun GraphicsLayerScope.cardComing(ms: Long) {
    val e = NoteSeal.cardShare(ms) ?: return
    translationY = (1f - e) * CARD_FROM_BELOW.dp.toPx()
    rotationZ = (1f - e) * CARD_TURN
}

/** The buttons and the line under the paper while it is sealed: out of the way at once, then back up as the card lands. */
internal fun Modifier.sealChrome(s: SlideScope, note: NoteDraft): Modifier = graphicsLayer {
    val at = note.sealedAt
    if (at < 0) return@graphicsLayer
    val ms = NoteSeal.since(s.stage.clock.now, at)
    alpha = NoteSeal.chromeAlpha(ms, s.still)
    translationY = NoteSeal.chromeBelow(ms, s.still) * RecapMotion.UP_DISTANCE * s.unit
}

/** What goes under the paper while it is sealed: the envelope's shadow, its back and lining, and its flap while open. */
@Composable
internal fun SealBehind(s: SlideScope, note: NoteDraft, places: SealPlaces, turn: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    val kit = remember { SealKit() }
    Canvas(modifier) {
        val at = note.sealedAt
        if (at < 0) return@Canvas
        val ms = NoteSeal.since(s.stage.clock.now, at)
        if (!NoteSeal.running(ms, s.still)) return@Canvas
        envelopeBehind(ms, Envelope.of(this, s, places, size), turn, kit, grain)
    }
}

/**
 * What is drawn over the paper while it is sealed, on the story's clock from the seal: the envelope's pocket, its flap
 * once it comes over, the wax, and all of it going; one firm tick as the wax lands.
 */
@Composable
internal fun SealOver(s: SlideScope, note: NoteDraft, places: SealPlaces, turn: Float, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(note.sealedAt) {
        val at = note.sealedAt
        if (s.still || at < 0) return@LaunchedEffect
        val press = at + (NoteSeal.PRESS_MS * NoteSeal.slow).toLong()
        if (s.stage.clock.now >= press) return@LaunchedEffect
        snapshotFlow { s.stage.clock.now }.first { it >= press }
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    val kit = remember { SealKit() }
    Canvas(modifier) {
        val at = note.sealedAt
        if (at < 0) return@Canvas
        val ms = NoteSeal.since(s.stage.clock.now, at)
        if (!NoteSeal.running(ms, s.still)) return@Canvas
        envelopeOver(ms, Envelope.of(this, s, places, size), turn, kit, grain)
    }
}

/** What one canvas keeps from frame to frame: paths, the flap's matrix, the lining. */
internal class SealKit {
    val path = Path()
    val path2 = Path()
    val path3 = Path()
    val shade = Path()
    val matrix = PlainMatrix()
    val src = FloatArray(8)
    val dst = FloatArray(8)
    private var lining: ShaderBrush? = null

    /** The envelope's lining: the slide's sky, cream dots on it. */
    fun liner(scope: DrawScope): ShaderBrush = lining ?: with(scope) {
        val side = 14.dp.toPx().roundToInt().coerceAtLeast(4)
        val tile = ImageBitmap(side, side)
        val canvas = PictureCanvas(tile)
        val paint = Paint()
        paint.color = RecapInk.Sky
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        paint.color = RecapInk.Cream.copy(alpha = 0.6f)
        canvas.drawCircle(Offset(side / 2f, side / 2f), 2.2.dp.toPx(), paint)
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)).also { lining = it }
    }
}

/**
 * The envelope in the paper's own frame (its top left at the paper's, turned with it): a little wider than the paper,
 * its top 10 dp under the paper's; [pw] and [ph] the paper's own size, [box] its box; [below] how far under its place it
 * starts, under the foot of the page, and [away] how far right it goes, past the page's side, in pixels.
 */
private class Envelope(val pw: Float, val ph: Float, val box: Size, val left: Float, val top: Float, val w: Float, val h: Float, val below: Float, val away: Float) {
    /** The flap's length from its hinge, where the sides meet in a V, and the bottom flap's point. */
    val flap = h * 0.56f
    val vee = h * 0.5f
    val apex = h * 0.54f

    companion object {
        /** The envelope for the paper of [places] (its box as the field had it, else [now]), on the stage of [s]. */
        fun of(scope: DrawScope, s: SlideScope, places: SealPlaces, now: Size): Envelope = with(scope) {
            val box = places.letter.let { if (it.width > 0 && it.height > 0) Size(it.width.toFloat(), it.height.toFloat()) else now }
            val pw = box.width - 8.dp.toPx()
            val ph = box.height - 18.dp.toPx()
            val left = -9.dp.toPx()
            val top = 10.dp.toPx()
            val w = pw + 18.dp.toPx()
            val h = ph + 26.dp.toPx()
            // From under the foot of the page to off its right side, wherever the paper stands; a page's worth each
            // way until its box has been placed.
            val stage = s.stage.size
            val origin = s.stage.anchors.origin
            val at = places.boxInRoot()
            val margin = 16.dp.toPx()
            val below = if (at == null || stage == Size.Zero) 600.dp.toPx() else origin.y + stage.height - (at.y + 18.dp.toPx() + top) + margin
            val away = if (at == null || stage == Size.Zero) box.width * 1.3f else origin.x + stage.width - (at.x + 4.dp.toPx() + left) + margin + h * 0.25f
            Envelope(pw, ph, box, left, top, w, h, below.coerceAtLeast(h), away.coerceAtLeast(w))
        }
    }
}

/** Draws [block] in the paper's own frame: NotePaper lies 4 dp in, 18 dp down, turned [turn] about its middle. */
private inline fun DrawScope.inPaper(pw: Float, ph: Float, turn: Float, block: DrawScope.() -> Unit) {
    val left = 4.dp.toPx()
    val top = 18.dp.toPx()
    withTransform({
        translate(left, top)
        rotate(turn, Offset(pw / 2f, ph / 2f))
    }, block)
}

/** Draws [block] in the envelope's own frame (its top left at 0, 0) as it comes up, gives under the wax and goes. */
private inline fun DrawScope.envelopeMoving(ms: Long, e: Envelope, block: DrawScope.() -> Unit) {
    val up = NoteSeal.riseShare(ms)
    val go = NoteSeal.goShare(ms)
    val press = NoteSeal.PRESS_MS
    val dip = if (ms >= press && ms < press + DIP_MS) sin(PI * (ms - press) / DIP_MS).toFloat() * DIP_DP.dp.toPx() else 0f
    val centre = Offset(e.left + e.w / 2f, e.top + e.h / 2f)
    val shrink = 1f - GO_SHRINK * go
    val rise = GO_UP.dp.toPx()
    withTransform({
        translate(go * e.away, (1f - up) * e.below + dip - go * rise)
        rotate((1f - up) * ENVELOPE_TURN + go * GO_TURN, centre)
        scale(shrink, shrink, centre)
        translate(e.left, e.top)
    }, block)
}

/** The envelope's outline, its corners a little round, into [out]. */
private fun DrawScope.envelopeOutline(out: Path, e: Envelope) {
    out.rewind()
    out.addRoundRect(RoundRect(0f, 0f, e.w, e.h, CornerRadius(ENVELOPE_CORNER.dp.toPx())))
}

/**
 * A soft plum shadow under [path], [drop] down, as the papers' own: rings a little wider and fainter each, which is
 * what a blur looks like and costs nothing on any phone.
 */
private fun DrawScope.softShadow(path: Path, strength: Float, drop: Float) {
    if (strength <= 0.01f) return
    translate(0f, drop) {
        val step = 2.dp.toPx()
        for (i in 1..5) drawPath(path, RecapInk.Shade.copy(alpha = 0.035f * strength), style = Stroke(width = i * step, join = StrokeJoin.Round))
        drawPath(path, RecapInk.Shade.copy(alpha = 0.1f * strength))
    }
}

/** Under the paper: the envelope's shadow, its back and lining, and its flap while open behind the letter. */
private fun DrawScope.envelopeBehind(ms: Long, e: Envelope, turn: Float, kit: SealKit, grain: Brush) {
    // Once shut, all of it is in front (envelopeOver).
    if (ms >= NoteSeal.FLAP_FROM + NoteSeal.FLAP_MS) return
    inPaper(e.pw, e.ph, turn) {
        envelopeMoving(ms, e) {
            envelopeOutline(kit.path, e)
            softShadow(kit.path, 1f, 4.dp.toPx())
            drawPath(kit.path, ENVELOPE_INSIDE)
            val inset = 5.dp.toPx()
            drawRect(kit.liner(this), Offset(inset, inset), Size(e.w - 2f * inset, e.h - inset))
            drawRect(RecapInk.Shade.copy(alpha = 0.12f), Offset(inset, inset), Size(e.w - 2f * inset, e.h - inset))
            val phi = NoteSeal.flapAngle(ms)
            if (phi > 90f) envelopeFlap(e, phi, kit, grain, inside = true)
        }
    }
}

/** Over the paper: the envelope's pocket, its flap once it comes over, the wax, and all of it going. */
private fun DrawScope.envelopeOver(ms: Long, e: Envelope, turn: Float, kit: SealKit, grain: Brush) {
    if (ms >= NoteSeal.GO_FROM + NoteSeal.GO_MS) return
    val phi = NoteSeal.flapAngle(ms)
    val shut = ms >= NoteSeal.FLAP_FROM + NoteSeal.FLAP_MS
    inPaper(e.pw, e.ph, turn) {
        envelopeMoving(ms, e) {
            envelopeOutline(kit.path, e)
            // Shut, it is all in front of the paper: its shadow comes with it.
            if (shut) softShadow(kit.path, 1f, 4.dp.toPx() + 6.dp.toPx() * NoteSeal.goShare(ms))
            clipPath(kit.path) {
                pocket(e, kit, grain)
                if (phi <= 90f) envelopeFlap(e, phi, kit, grain, inside = false)
            }
            if (ms >= NoteSeal.WAX_FROM) wax(ms, e, kit)
        }
    }
}

/** The pocket: the sides folded in to meet in a V where the letter goes in, the bottom flap over them. */
private fun DrawScope.pocket(e: Envelope, kit: SealKit, grain: Brush) {
    with(kit.shade) {
        rewind()
        moveTo(0f, 0f)
        lineTo(e.w / 2f, e.vee)
        lineTo(e.w, 0f)
        lineTo(e.w, e.h)
        lineTo(0f, e.h)
        close()
    }
    drawPath(kit.shade, ENVELOPE)
    drawPath(kit.shade, grain)
    // The sides' edges, where the letter went in, catch the light.
    val thin = 1.5.dp.toPx()
    drawLine(EDGE_LIT, Offset(0f, 0f), Offset(e.w / 2f, e.vee), thin)
    drawLine(EDGE_LIT, Offset(e.w, 0f), Offset(e.w / 2f, e.vee), thin)
    with(kit.path2) {
        rewind()
        moveTo(0f, e.h)
        lineTo(e.w / 2f, e.apex)
        lineTo(e.w, e.h)
        close()
    }
    drawPath(kit.path2, ENVELOPE_BOTTOM)
    drawPath(kit.path2, grain)
    drawLine(SEAM, Offset(0f, e.h), Offset(e.w / 2f, e.apex), 1.dp.toPx())
    drawLine(SEAM, Offset(e.w, e.h), Offset(e.w / 2f, e.apex), 1.dp.toPx())
}

/**
 * The matrix that takes the flap lying flat to its picture turned [deg] towards the eye about its hinge, with depth:
 * the flap from [left] to [right], hinged at [hinge], [len] long, lying below the hinge when shut. False when it stands
 * edge on.
 */
private fun flapMatrix(kit: SealKit, left: Float, right: Float, hinge: Float, len: Float, deg: Float): Boolean {
    val a = Math.toRadians(deg.toDouble())
    val sinA = sin(a).toFloat()
    val cosA = cos(a).toFloat()
    if (abs(cosA) < 0.02f) return false
    val far = hinge + len
    val eye = EYE * len
    val k = eye / (eye - len * sinA)
    val farNow = hinge + len * cosA * k
    val cx = (left + right) / 2f
    kit.src[0] = left; kit.src[1] = far; kit.src[2] = right; kit.src[3] = far
    kit.src[4] = right; kit.src[5] = hinge; kit.src[6] = left; kit.src[7] = hinge
    kit.dst[0] = cx + (left - cx) * k; kit.dst[1] = farNow; kit.dst[2] = cx + (right - cx) * k; kit.dst[3] = farNow
    kit.dst[4] = right; kit.dst[5] = hinge; kit.dst[6] = left; kit.dst[7] = hinge
    return kit.matrix.setPolyToPoly(kit.src, 0, kit.dst, 0, 4)
}

/** The flap, turned [phi] from shut, with depth: its lining while open ([inside]), its outside as it comes down. */
private fun DrawScope.envelopeFlap(e: Envelope, phi: Float, kit: SealKit, grain: Brush, inside: Boolean) {
    if (!flapMatrix(kit, 0f, e.w, 0f, e.flap, phi)) return
    val lift = sin(Math.toRadians(phi.toDouble())).toFloat()
    val tip = 10.dp.toPx()
    with(kit.path3) {
        rewind()
        moveTo(0f, 0f)
        lineTo(e.w, 0f)
        lineTo(e.w / 2f + tip, e.flap - tip * 0.5f)
        lineTo(e.w / 2f - tip, e.flap - tip * 0.5f)
        close()
    }
    val liner = if (inside) kit.liner(this) else null
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val saved = native.save()
        native.concat(kit.matrix)
        if (liner != null) {
            drawPath(kit.path3, liner)
            drawPath(kit.path3, RecapInk.Shade.copy(alpha = 0.12f + 0.12f * lift))
            drawPath(kit.path3, ENVELOPE, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
        } else {
            // Nearly down, its shadow on the pocket.
            if (lift < 0.7f) translate(0f, 2.dp.toPx()) { drawPath(kit.path3, RecapInk.Shade.copy(alpha = 0.24f * (1f - lift))) }
            drawPath(kit.path3, ENVELOPE_FLAP)
            drawPath(kit.path3, grain)
            drawPath(kit.path3, RecapInk.Shade.copy(alpha = 0.2f * lift))
            drawPath(kit.path3, SEAM, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
        }
        native.restoreToCount(saved)
    }
}

/** The wax slapped onto the flap's point, as the Recap's stickers slap on: big, down past its size, then its size. */
private fun DrawScope.wax(ms: Long, e: Envelope, kit: SealKit) {
    val p = ((ms - NoteSeal.WAX_FROM) / NoteSeal.WAX_MS.toFloat()).coerceIn(0f, 1f)
    val lands = NoteSeal.SLAP_LANDS
    val down = RecapMotion.SlapEasing.transform((p / lands).coerceAtMost(1f))
    val k = if (p < lands) 1.7f + (0.94f - 1.7f) * down else 0.94f + 0.06f * RecapMotion.SlapEasing.transform((p - lands) / (1f - lands))
    val centre = Offset(e.w / 2f, e.flap - 16.dp.toPx())
    rotate(WAX_TURN + 18f * (1f - down), centre) {
        scale(k, k, centre) {
            waxSeal(centre, WAX_R.dp.toPx(), kit.path, alpha = (p / 0.25f).coerceIn(0f, 1f))
        }
    }
}

/**
 * The wax seal, on the envelope and then on the sealed card (Sealed): a blob of the app's red, never quite round, the
 * stamp's round impression in it and the app's mark pressed in. [r] is half its square (the card's is 52 dp across).
 */
internal fun DrawScope.waxSeal(centre: Offset, r: Float, path: Path, alpha: Float = 1f) {
    blobPath(path, centre, r * 0.94f)
    translate(0f, 2.dp.toPx()) { drawPath(path, RecapInk.Shade.copy(alpha = 0.25f * alpha)) }
    drawPath(path, RecapRed.copy(alpha = alpha))
    val inner = r * 0.64f
    drawCircle(WAX_DEEP.copy(alpha = alpha), inner, centre)
    val corner = centre - Offset(inner, inner)
    val across = Size(inner * 2f, inner * 2f)
    val thin = 1.5.dp.toPx()
    // Pressed in: its rim in shade above, lit below.
    drawArc(Color.Black.copy(alpha = 0.18f * alpha), 200f, 140f, false, corner, across, style = Stroke(thin))
    drawArc(Color.White.copy(alpha = 0.3f * alpha), 20f, 140f, false, corner, across, style = Stroke(thin))
    playMark(centre + Offset(0.8.dp.toPx(), 0.8.dp.toPx()), r * 0.36f, path, WAX_SHADE.copy(alpha = alpha))
    playMark(centre, r * 0.36f, path, RecapInk.Cream.copy(alpha = 0.94f * alpha))
}

/** The Recap's soft blob (Paper.BLOB) round [centre], [outer] to its farthest point, into [out]. */
private fun blobPath(out: Path, centre: Offset, outer: Float) {
    val points = PaperShapes.of(Paper.BLOB)
    out.rewind()
    for (k in 0 until PaperShapes.N) {
        val x = centre.x + (points[2 * k] - 50f) / 50f * outer
        val y = centre.y + (points[2 * k + 1] - 50f) / 50f * outer
        if (k == 0) out.moveTo(x, y) else out.lineTo(x, y)
    }
    out.close()
}

/** The app's play mark, a triangle [t] from its middle to its point, into [path] and drawn. */
private fun DrawScope.playMark(centre: Offset, t: Float, path: Path, colour: Color) {
    path.rewind()
    path.moveTo(centre.x - t * 0.6f, centre.y - t)
    path.lineTo(centre.x + t, centre.y)
    path.lineTo(centre.x - t * 0.6f, centre.y + t)
    path.close()
    drawPath(path, colour)
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.constants.RecapSealTakeKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Matrix as PlainMatrix
import androidx.compose.ui.geometry.lerp as lerpOffset
import androidx.compose.ui.graphics.Canvas as PictureCanvas
import androidx.compose.ui.util.lerp as lerpFloat

/**
 * How a note to next year's you is sealed, the moment after Seal it (his "god the sealing animation
 * looks so ass", 23 Nov 2026, on the letter and wax stamp that went to testers the night before).
 * Takes to compare on a screen, picked in the debug app's developer options ("DEBUG: Recap seal",
 * Unreleased.RECAP_SEAL_TAKES); every other build plays [DEFAULT]. Each is short, moves in drawing
 * only, on the story's clock, and with Remove animations on is no animation at all: the note is
 * sealed at once, as before.
 *
 * - [BEFORE]: the letter of 22 Nov (SealLetter): folded in three, into an envelope, the flap shut, a
 *   wax stamp pressed on, then off; about 3 s.
 * - [A]: the note's own taped paper folds once, its upper half down over the lower, and slides away
 *   to the right, to next year; its sealed card then comes in as the paper first did. Under 1 s.
 * - [B]: an envelope in the slide's plum comes up round the paper, the paper goes down into it, the
 *   flap shuts, a wax seal of the app's mark is slapped on and the envelope flies off. About 1.2 s.
 * - [C]: the note's sheet gathers into the seal on its own card: it shrinks to a disc, turns the
 *   app's red, takes the Recap's scalloped seal shape (Paper.SEAL) and is pressed on. About 0.9 s.
 */
internal enum class SealTake(val label: String) {
    BEFORE("Before: letter, envelope and wax stamp"),
    A("A: folds once and goes"),
    B("B: into its envelope"),
    C("C: pressed into a seal");

    companion object {
        /** The take every build without the developer's choice plays, and the debug app until another is picked. */
        val DEFAULT = B

        /** The take stored under RecapSealTakeKey, or [DEFAULT]. */
        fun of(stored: String?): SealTake = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

/** The take this build plays: the debug app's pick, or [SealTake.DEFAULT]. */
@Composable
internal fun rememberSealTake(): SealTake {
    if (!Unreleased.RECAP_SEAL_TAKES) return SealTake.DEFAULT
    val stored by rememberPreference(key = RecapSealTakeKey, defaultValue = "")
    return SealTake.of(stored)
}

/**
 * Debug builds only: which take seals a note in the Recap ([SealTake]), to see each on a screen.
 * A look to judge, not a setting, so its words are not translated.
 */
@Composable
internal fun RecapSealRow() {
    val (stored, onStoredChange) = rememberPreference(key = RecapSealTakeKey, defaultValue = "")
    ListPreference(
        title = { Text("DEBUG: Recap seal") },
        icon = { Icon(Icons.Rounded.Science, null) },
        selectedValue = SealTake.of(stored),
        values = SealTake.entries,
        valueText = { it.label + if (it == SealTake.DEFAULT) " (default)" else "" },
        onValueSelected = { onStoredChange(it.name) },
    )
}

/**
 * The takes' times, in ms after the seal, and where each part of a take is at a moment: plain
 * arithmetic, tested on the JVM (RecapSealTakesTest).
 */
internal object SealTakes {
    // ---- A: the upper half folds down, then the folded note goes, then its card comes

    const val A_FOLD_MS = 420
    const val A_GO_FROM = 440
    const val A_GO_MS = 420
    const val A_CARD_FROM = A_GO_FROM + A_GO_MS

    // ---- B: the envelope comes up round the paper, which goes down into it; the flap; the wax; away

    const val B_RISE_MS = 360
    const val B_IN_FROM = 80
    const val B_IN_MS = 320
    const val B_FLAP_FROM = 400
    const val B_FLAP_MS = 180
    const val B_WAX_FROM = 580
    const val B_WAX_MS = 180
    const val B_GO_FROM = 800
    const val B_GO_MS = 360
    const val B_CARD_FROM = 940

    // ---- C: the sheet gathers into the seal, then the seal is pressed on

    const val C_MORPH_FROM = 40
    const val C_MORPH_MS = 640
    const val C_PRESS_FROM = C_MORPH_FROM + C_MORPH_MS
    const val C_PRESS_MS = 220

    /** The sealed card coming in where the paper was, as the paper first came: from below, turned, a touch past and back. */
    const val CARD_MS = 640

    /** The buttons and the line under the paper: gone as it is sealed, back up as the card lands. */
    const val CHROME_OUT_MS = 120
    const val CHROME_IN_MS = 420
    const val CHROME_AFTER_CARD_MS = 250

    /** Where in the wax's slap it lands, and in the seal's press it touches the paper: the firm tick. */
    const val SLAP_LANDS = 0.6f
    const val PRESS_LANDS = 0.45f

    /** C's seal comes in a little above the paper, this much larger, and is pressed down to its size. */
    const val LIFT = 1.14f

    /** The scalloped seal's points reach this far out of half its square. */
    const val STAR_OUT = 0.96f

    /** The sealed card's seal is turned this much on its paper (Sealed). */
    const val MARK_TURN = -10f

    /** How far B's letter goes down into its envelope, in dp. */
    const val INTO_DP = 22f

    /** B's flap, open: just past upright, in degrees from shut. */
    const val FLAP_OPEN = 110f

    /** The fold: off slowly, quick through upright, down softly. */
    val Fold: Easing = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)

    /** The flap pushed shut: quicker and quicker, and down. */
    val Shut: Easing = CubicBezierEasing(0.6f, 0f, 0.9f, 0.6f)

    /** The letter lowered into its envelope. */
    val Into: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** When the take is over and everything rests, in ms after the seal. */
    fun totalMs(take: SealTake): Int = when (take) {
        SealTake.BEFORE -> SealLetter.TOTAL_MS + 300
        SealTake.A -> max(A_CARD_FROM + CARD_MS, chromeFrom(take) + CHROME_IN_MS)
        SealTake.B -> max(B_CARD_FROM + CARD_MS, chromeFrom(take) + CHROME_IN_MS)
        SealTake.C -> max(C_PRESS_FROM + C_PRESS_MS, chromeFrom(take) + CHROME_IN_MS)
    }

    /** When the seal is felt: A's fold landing, B's wax and C's seal touching the paper. */
    fun pressMs(take: SealTake): Int = when (take) {
        SealTake.BEFORE -> SealLetter.PRESS_MS
        SealTake.A -> A_FOLD_MS
        SealTake.B -> B_WAX_FROM + (B_WAX_MS * SLAP_LANDS).toInt()
        SealTake.C -> C_PRESS_FROM + (C_PRESS_MS * PRESS_LANDS).toInt()
    }

    /** Whether [take] is moving [ms] after the seal: never with Remove animations on. */
    fun running(take: SealTake, ms: Long, still: Boolean): Boolean = !still && ms >= 0 && ms < totalMs(take)

    /** Whether the paper still holds the words just sealed: A's until it has gone, B's until the flap is shut over it. */
    fun letterOnPaper(take: SealTake, ms: Long, still: Boolean): Boolean = running(take, ms, still) && when (take) {
        SealTake.A -> ms < A_CARD_FROM
        SealTake.B -> ms < B_FLAP_FROM + B_FLAP_MS
        SealTake.C, SealTake.BEFORE -> false
    }

    /** Whether the sealed card's own seal waits: C's, until the sheet has been pressed into it. */
    fun markHidden(take: SealTake, ms: Long, still: Boolean): Boolean =
        take == SealTake.C && running(take, ms, still) && ms < C_PRESS_FROM + C_PRESS_MS

    /** When the buttons come back. */
    fun chromeFrom(take: SealTake): Int = when (take) {
        SealTake.A -> A_CARD_FROM + CHROME_AFTER_CARD_MS
        SealTake.B -> B_CARD_FROM + CHROME_AFTER_CARD_MS
        SealTake.C, SealTake.BEFORE -> C_PRESS_FROM
    }

    /** How much the buttons and the line under the paper show; 1 at rest and in the take of before. */
    fun chromeAlpha(take: SealTake, ms: Long, still: Boolean): Float {
        if (take == SealTake.BEFORE || !running(take, ms, still)) return 1f
        val back = chromeFrom(take)
        if (ms < back) return (1f - ms / CHROME_OUT_MS.toFloat()).coerceIn(0f, 1f)
        return RecapMotion.Spring.transform(((ms - back) / CHROME_IN_MS.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /** How far under their place they are as they come back up, as a share of the Recap's rise (RecapMotion.UP_DISTANCE). */
    fun chromeBelow(take: SealTake, ms: Long, still: Boolean): Float {
        if (take == SealTake.BEFORE || !running(take, ms, still)) return 0f
        val back = chromeFrom(take)
        if (ms < back) return 0f
        return 1f - RecapMotion.Spring.transform(((ms - back) / CHROME_IN_MS.toFloat()).coerceIn(0f, 1f))
    }

    /** A's fold, in degrees: 0 flat, 180 with its upper half down on the lower one. */
    fun foldAngle(ms: Long): Float = 180f * Fold.transform((ms / A_FOLD_MS.toFloat()).coerceIn(0f, 1f))

    /** How far a going from [from] has gone, 0 to 1, on the Recap's toss: off slowly, then quicker and quicker. */
    fun goShare(ms: Long, from: Int, length: Int): Float =
        if (ms < from) 0f else RecapMotion.TossEasing.transform(((ms - from) / length.toFloat()).coerceIn(0f, 1f))

    /** A going's fade, over its last 30%. */
    fun goAlpha(ms: Long, from: Int, length: Int): Float = 1f - (((ms - from) / length.toFloat() - 0.7f) / 0.3f).coerceIn(0f, 1f)

    /** The sealed card coming in from [from]: 0 to 1 of its way on the Recap's settle, or null before it. */
    fun cardShare(ms: Long, from: Int): Float? =
        if (ms < from) null else RecapMotion.Settle.transform(((ms - from) / CARD_MS.toFloat()).coerceIn(0f, 1f))

    fun cardAlpha(ms: Long, from: Int): Float =
        if (ms < from) 0f else RecapMotion.Settle.transform(((ms - from) / (CARD_MS * 0.3f)).coerceIn(0f, 1f)).coerceIn(0f, 1f)

    /** How much the real paper shows: the letter, then its sealed card. */
    fun paperAlpha(take: SealTake, ms: Long, still: Boolean): Float {
        if (!running(take, ms, still)) return 1f
        return when (take) {
            SealTake.BEFORE -> paperUnderLetter(ms, still)
            SealTake.A -> if (ms < A_CARD_FROM) goAlpha(ms, A_GO_FROM, A_GO_MS) else cardAlpha(ms, A_CARD_FROM)
            SealTake.B -> if (ms < B_FLAP_FROM + B_FLAP_MS) 1f else cardAlpha(ms, B_CARD_FROM)
            SealTake.C -> 1f
        }
    }

    /** How far down into its envelope B's letter has gone, in dp. */
    fun intoDp(ms: Long): Float = INTO_DP * Into.transform(((ms - B_IN_FROM) / B_IN_MS.toFloat()).coerceIn(0f, 1f))

    /** B's flap, in degrees: [FLAP_OPEN] open, 0 shut on the pocket. */
    fun flapAngle(ms: Long): Float = FLAP_OPEN * (1f - Shut.transform(((ms - B_FLAP_FROM) / B_FLAP_MS.toFloat()).coerceIn(0f, 1f)))

    /** C's seal being pressed on: its size, from lifted ([LIFT]) to a touch under (0.97), then 1. */
    fun pressScale(ms: Long): Float {
        val p = ((ms - C_PRESS_FROM) / C_PRESS_MS.toFloat()).coerceIn(0f, 1f)
        if (p < PRESS_LANDS) {
            val q = p / PRESS_LANDS
            return LIFT + (0.97f - LIFT) * q * q
        }
        val q = 1f - (p - PRESS_LANDS) / (1f - PRESS_LANDS)
        return 0.97f + 0.03f * (1f - q * q)
    }
}

/** Where the takes find the paper and its card's seal: plain holders, read in drawing only. */
internal class SealPlaces {
    /** The paper's box as last laid out with the field in it, the size the letter keeps while it is sealed. */
    var letter: IntSize = IntSize.Zero
    var box: LayoutCoordinates? = null
    var mark: LayoutCoordinates? = null

    /** A's clip, made again for each frame into the same path. */
    val clip = Path()

    /** The middle of the sealed card's seal in the paper box's pixels, once it is laid out. */
    fun markCentre(): Offset? {
        val b = box ?: return null
        val m = mark ?: return null
        if (!b.isAttached || !m.isAttached) return null
        return b.localPositionOf(m, Offset(m.size.width / 2f, m.size.height / 2f))
    }
}

// ---- how far things go, in dp and degrees

/** A's folded note and B's envelope go off to the right, this many times the paper's box across, rising a little and turning. */
private const val GO_AWAY_X = 1.15f
private const val GO_AWAY_UP_A = 60f
private const val GO_AWAY_UP_B = 80f
private const val GO_AWAY_TURN = 12f
private const val GO_AWAY_SHRINK = 0.1f

/** The sealed card comes from this far below its place, turned this much more. */
private const val CARD_FROM_BELOW = 110f
private const val CARD_TURN = 7f

/** B's envelope comes up from this far below its place, turned this much more. */
private const val ENVELOPE_FROM_BELOW = 150f
private const val ENVELOPE_TURN = 6f
private const val ENVELOPE_CORNER = 6f

/** The envelope gives a little under the wax, for this long. */
private const val DIP_MS = 160
private const val DIP_DP = 3f

/** The wax on the envelope: half its square, and its turn once pressed. */
private const val WAX_R = 21f
private const val WAX_TURN = -8f

/** A flap's eye, this many of its lengths away: enough depth to read as a flap turning, not a squash. */
private const val EYE = 9f

private val ENVELOPE = RecapInk.Plum
private val ENVELOPE_FLAP = lerp(RecapInk.Plum, Color.White, 0.1f)
private val ENVELOPE_BOTTOM = lerp(RecapInk.Plum, Color.White, 0.05f)
private val ENVELOPE_INSIDE = lerp(RecapInk.Plum, RecapInk.Ink, 0.35f)
private val SEAM = RecapInk.Ink.copy(alpha = 0.24f)
private val EDGE_LIT = Color.White.copy(alpha = 0.24f)
private val PAPER_BACK = Color(0xFFF3EDE2)
private val WAX_DEEP = Color(0xFFDA4757)
private val WAX_SHADE = Color(0xFFB0303F)

/**
 * The real paper while a take runs: fading, A's folded note going (only its lower half: the upper
 * one is the flap drawn over it), B's letter going down into its envelope, and then the sealed card
 * coming in where it was. Nothing at rest. The take of before only hides it, as it did.
 */
internal fun Modifier.sealPaper(take: SealTake, s: SlideScope, note: NoteDraft, places: SealPlaces, turn: Float): Modifier =
    graphicsLayer {
        val at = note.sealedAt
        if (at < 0) return@graphicsLayer
        val ms = s.stage.clock.now - at
        if (!SealTakes.running(take, ms, s.still)) return@graphicsLayer
        alpha = SealTakes.paperAlpha(take, ms, s.still)
        when (take) {
            SealTake.A -> if (ms < SealTakes.A_CARD_FROM) goingAway(ms) else cardComing(ms, SealTakes.A_CARD_FROM)
            SealTake.B -> if (ms < SealTakes.B_FLAP_FROM + SealTakes.B_FLAP_MS) intoEnvelope(ms, turn) else cardComing(ms, SealTakes.B_CARD_FROM)
            SealTake.C, SealTake.BEFORE -> Unit
        }
    }.drawWithContent {
        val at = note.sealedAt
        val ms = s.stage.clock.now - at
        if (take == SealTake.A && at >= 0 && SealTakes.running(take, ms, s.still) && ms < SealTakes.A_CARD_FROM) {
            lowerHalf(places.clip, size, turn)
            clipPath(places.clip) { this@drawWithContent.drawContent() }
        } else {
            drawContent()
        }
    }

/** A's folded note going off to the right, the flap over it moved the same way (foldAndGo). */
private fun GraphicsLayerScope.goingAway(ms: Long) {
    val go = SealTakes.goShare(ms, SealTakes.A_GO_FROM, SealTakes.A_GO_MS)
    translationX = go * size.width * GO_AWAY_X
    translationY = -go * GO_AWAY_UP_A.dp.toPx()
    rotationZ = go * GO_AWAY_TURN
    scaleX = 1f - GO_AWAY_SHRINK * go
    scaleY = scaleX
}

/** B's letter going down into its envelope, along its own turn. */
private fun GraphicsLayerScope.intoEnvelope(ms: Long, turn: Float) {
    val d = SealTakes.intoDp(ms).dp.toPx()
    val r = Math.toRadians(turn.toDouble())
    translationX = (-d * sin(r)).toFloat()
    translationY = (d * cos(r)).toFloat()
}

private fun GraphicsLayerScope.cardComing(ms: Long, from: Int) {
    val e = SealTakes.cardShare(ms, from) ?: return
    translationY = (1f - e) * CARD_FROM_BELOW.dp.toPx()
    rotationZ = (1f - e) * CARD_TURN
}

/** The buttons and the line under the paper while a take runs: out of the way, then back up as the card lands. */
internal fun Modifier.sealChrome(take: SealTake, s: SlideScope, note: NoteDraft): Modifier = graphicsLayer {
    val at = note.sealedAt
    if (at < 0) return@graphicsLayer
    val ms = s.stage.clock.now - at
    alpha = SealTakes.chromeAlpha(take, ms, s.still)
    translationY = SealTakes.chromeBelow(take, ms, s.still) * RecapMotion.UP_DISTANCE * s.unit
}

/**
 * What goes under the paper while it is sealed: B's envelope, its back, its lining and its flap
 * while that is open behind the letter. Nothing for the other takes.
 */
@Composable
internal fun SealBehind(take: SealTake, s: SlideScope, note: NoteDraft, places: SealPlaces, turn: Float, modifier: Modifier = Modifier) {
    if (take != SealTake.B) return
    val measurer = rememberTextMeasurer()
    val hand = remember { noteHand(19.sp) }
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    val kit = remember { SealKit() }
    Canvas(modifier) {
        val at = note.sealedAt
        if (at < 0) return@Canvas
        val ms = s.stage.clock.now - at
        if (!SealTakes.running(take, ms, s.still)) return@Canvas
        val sheet = kit.sheet(this, at, places.letter, note.letter, measurer, hand, grain)
        envelopeBehind(ms, sheet, turn, kit)
    }
}

/**
 * What is drawn over the paper while it is sealed, on the story's clock from the seal, with the
 * tick felt where it lands: A's flap, B's envelope's pocket, flap and wax, C's sheet gathering into
 * its seal; for the take of before, its letter (SealLetterCanvas), as it was.
 */
@Composable
internal fun SealOver(take: SealTake, s: SlideScope, note: NoteDraft, places: SealPlaces, turn: Float, modifier: Modifier = Modifier) {
    if (take == SealTake.BEFORE) {
        SealLetterCanvas(s, note.sealedAt, note.letter, modifier)
        return
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(note.sealedAt, take) {
        val at = note.sealedAt
        if (s.still || at < 0) return@LaunchedEffect
        val press = at + SealTakes.pressMs(take)
        if (s.stage.clock.now >= press) return@LaunchedEffect
        snapshotFlow { s.stage.clock.now }.first { it >= press }
        haptic.performHapticFeedback(if (take == SealTake.A) HapticFeedbackType.SegmentTick else HapticFeedbackType.Confirm)
    }
    val measurer = rememberTextMeasurer()
    val hand = remember { noteHand(19.sp) }
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    val kit = remember { SealKit() }
    Canvas(modifier) {
        val at = note.sealedAt
        if (at < 0) return@Canvas
        val ms = s.stage.clock.now - at
        if (!SealTakes.running(take, ms, s.still)) return@Canvas
        val sheet = kit.sheet(this, at, places.letter, note.letter, measurer, hand, grain)
        when (take) {
            SealTake.A -> foldAndGo(ms, sheet, turn, kit)
            SealTake.B -> envelopeOver(ms, sheet, turn, kit)
            SealTake.C -> pressedIntoSeal(ms, sheet, turn, kit, places)
            SealTake.BEFORE -> Unit
        }
    }
}

/**
 * The letter's paper as it was when sealed: its box, the paper's own size in it, its words laid out
 * as the field had them, its grain and line height, and pictures of it made once for the seal.
 */
internal class LetterSheet(val box: Size, val pw: Float, val ph: Float, val words: TextLayoutResult, val grain: Brush, val line: Float) {
    /** Room above the paper in the flap's pictures, for its tape. */
    var top = 0f
    var flapFront: ImageBitmap? = null
    var flapBack: ImageBitmap? = null
    var bare: ImageBitmap? = null
    var writing: ImageBitmap? = null
}

/** What one canvas of a take keeps from frame to frame: paths, a matrix, the sheet of the seal under way. */
internal class SealKit {
    val path = Path()
    val path2 = Path()
    val path3 = Path()
    val shade = Path()
    val matrix = PlainMatrix()
    val src = FloatArray(8)
    val dst = FloatArray(8)
    val layer = Paint()
    var liner: ShaderBrush? = null
    private var sheet: LetterSheet? = null
    private var sheetFor = Long.MIN_VALUE

    /** The sheet of the seal made at [at], measured and pictured once. */
    fun sheet(scope: DrawScope, at: Long, letter: IntSize, words: String, measurer: TextMeasurer, hand: TextStyle, grain: Brush): LetterSheet {
        sheet?.let { if (sheetFor == at) return it }
        val made = with(scope) {
            val box = if (letter.width > 0 && letter.height > 0) Size(letter.width.toFloat(), letter.height.toFloat()) else size
            val pw = box.width - 8.dp.toPx()
            val ph = box.height - 18.dp.toPx()
            val laid = measurer.measure(words, hand, constraints = Constraints(maxWidth = (pw - 44.dp.toPx()).toInt().coerceAtLeast(1)))
            LetterSheet(box, pw, ph, laid, grain, hand.lineHeight.toPx())
        }
        sheet = made
        sheetFor = at
        return made
    }

    /** The envelope's lining: the slide's sky, cream dots on it. */
    fun liner(scope: DrawScope): ShaderBrush = liner ?: with(scope) {
        val side = 14.dp.toPx().roundToInt().coerceAtLeast(4)
        val tile = ImageBitmap(side, side)
        val canvas = PictureCanvas(tile)
        val paint = Paint()
        paint.color = RecapInk.Sky
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        paint.color = RecapInk.Cream.copy(alpha = 0.6f)
        canvas.drawCircle(Offset(side / 2f, side / 2f), 2.2.dp.toPx(), paint)
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)).also { liner = it }
    }
}

// ---- the paper as NotePaper draws it, in its own frame: its top left at 0, 0

/** Draws [block] in the paper's own frame: NotePaper lies 4 dp in, 18 dp down, turned [turn] about its middle. */
private inline fun DrawScope.inPaper(pw: Float, ph: Float, turn: Float, block: DrawScope.() -> Unit) {
    val left = 4.dp.toPx()
    val top = 18.dp.toPx()
    withTransform({
        translate(left, top)
        rotate(turn, Offset(pw / 2f, ph / 2f))
    }, block)
}

/** A point of the paper's own frame in the paper box's pixels. */
private fun DrawScope.paperPoint(x: Float, y: Float, pw: Float, ph: Float, turn: Float): Offset {
    val r = Math.toRadians(turn.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    val dx = x - pw / 2f
    val dy = y - ph / 2f
    return Offset(4.dp.toPx() + pw / 2f + dx * c - dy * s, 18.dp.toPx() + ph / 2f + dx * s + dy * c)
}

/** A's lower half of the paper in the box of [box], with room round it for its shadow: what stays as the upper half folds down. */
private fun DrawScope.lowerHalf(out: Path, box: Size, turn: Float) {
    val pw = box.width - 8.dp.toPx()
    val ph = box.height - 18.dp.toPx()
    val room = 40.dp.toPx()
    val a = paperPoint(-room, ph / 2f, pw, ph, turn)
    val b = paperPoint(pw + room, ph / 2f, pw, ph, turn)
    val c = paperPoint(pw + room, ph + room, pw, ph, turn)
    val d = paperPoint(-room, ph + room, pw, ph, turn)
    out.rewind()
    out.moveTo(a.x, a.y)
    out.lineTo(b.x, b.y)
    out.lineTo(c.x, c.y)
    out.lineTo(d.x, d.y)
    out.close()
}

/** The paper's face: white, its grain, its blue rules from under the first line to its foot, and the words on them. */
private fun DrawScope.paperFront(sheet: LetterSheet, words: Float = 1f) {
    drawRoundRect(RecapInk.White, Offset.Zero, Size(sheet.pw, sheet.ph), CornerRadius(4.dp.toPx()))
    drawRect(sheet.grain, Offset.Zero, Size(sheet.pw, sheet.ph))
    val rule = RecapInk.Blue.copy(alpha = 0.22f)
    val thick = 1.dp.toPx()
    var y = 26.dp.toPx() + sheet.words.getLineBaseline(0) + 3.dp.toPx()
    while (y < sheet.ph - 6.dp.toPx()) {
        drawRect(rule, Offset(0f, y), Size(sheet.pw, thick))
        y += sheet.line
    }
    if (words > 0f) drawText(sheet.words, topLeft = Offset(22.dp.toPx(), 26.dp.toPx()), alpha = words)
}

/** The paper's back: a shade warmer, its grain, the words showing through faintly. */
private fun DrawScope.paperBack(sheet: LetterSheet) {
    drawRoundRect(PAPER_BACK, Offset.Zero, Size(sheet.pw, sheet.ph), CornerRadius(4.dp.toPx()))
    drawRect(sheet.grain, Offset.Zero, Size(sheet.pw, sheet.ph))
    drawText(sheet.words, topLeft = Offset(22.dp.toPx(), 26.dp.toPx()), alpha = 0.07f)
}

/** The strip of tape across its top edge, as NotePaper has it. */
private fun DrawScope.paperTape(pw: Float) {
    rotate(-4f, Offset(pw / 2f, 1.dp.toPx())) {
        val corner = Offset(pw / 2f - 46.dp.toPx(), -12.dp.toPx())
        val tape = Size(92.dp.toPx(), 26.dp.toPx())
        drawRect(Color.Black.copy(alpha = 0.12f), corner + Offset(0f, 1.dp.toPx()), tape)
        drawRect(RecapInk.Cream.copy(alpha = 0.78f), corner, tape)
    }
}

/** A picture of [width] by [height] pixels, drawn once by [block]. */
private fun DrawScope.picture(width: Float, height: Float, block: DrawScope.() -> Unit): ImageBitmap {
    val image = ImageBitmap(ceil(width).toInt().coerceAtLeast(1), ceil(height).toInt().coerceAtLeast(1))
    CanvasDrawScope().draw(this, layoutDirection, PictureCanvas(image), Size(image.width.toFloat(), image.height.toFloat())) { block() }
    return image
}

/**
 * A soft plum shadow under [path], [drop] down, as the papers' own: rings a little wider and fainter
 * each, which is what a blur looks like and costs nothing on any phone.
 */
private fun DrawScope.softShadow(path: Path, strength: Float, drop: Float) {
    if (strength <= 0.01f) return
    translate(0f, drop) {
        val step = 2.dp.toPx()
        for (i in 1..5) drawPath(path, RecapInk.Shade.copy(alpha = 0.035f * strength), style = Stroke(width = i * step, join = StrokeJoin.Round))
        drawPath(path, RecapInk.Shade.copy(alpha = 0.1f * strength))
    }
}

/** Draws [block] at [alpha] as one picture, so that its parts do not show through one another. */
private inline fun DrawScope.faded(alpha: Float, kit: SealKit, block: DrawScope.() -> Unit) {
    if (alpha <= 0.002f) return
    if (alpha >= 0.998f) {
        block()
        return
    }
    kit.layer.alpha = alpha
    drawIntoCanvas { it.saveLayer(Rect(-size.width, -size.height, size.width * 3f, size.height * 3f), kit.layer) }
    block()
    drawIntoCanvas { it.restore() }
}

private fun smooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The matrix that takes a flap lying flat to its picture turned [deg] towards the eye about its
 * hinge, with depth: the flap from [left] to [right], hinged at [hinge], [len] long, lying above the
 * hinge when [up] and below it otherwise. [SealKit.dst] keeps where its four corners went. False
 * when it stands edge on.
 */
private fun flapMatrix(kit: SealKit, left: Float, right: Float, hinge: Float, len: Float, deg: Float, up: Boolean): Boolean {
    val a = Math.toRadians(deg.toDouble())
    val sinA = sin(a).toFloat()
    val cosA = cos(a).toFloat()
    if (abs(cosA) < 0.02f) return false
    val dir = if (up) -1f else 1f
    val far = hinge + dir * len
    val eye = EYE * len
    val k = eye / (eye - len * sinA)
    val farNow = hinge + dir * len * cosA * k
    val cx = (left + right) / 2f
    kit.src[0] = left; kit.src[1] = far; kit.src[2] = right; kit.src[3] = far
    kit.src[4] = right; kit.src[5] = hinge; kit.src[6] = left; kit.src[7] = hinge
    kit.dst[0] = cx + (left - cx) * k; kit.dst[1] = farNow; kit.dst[2] = cx + (right - cx) * k; kit.dst[3] = farNow
    kit.dst[4] = right; kit.dst[5] = hinge; kit.dst[6] = left; kit.dst[7] = hinge
    return kit.matrix.setPolyToPoly(kit.src, 0, kit.dst, 0, 4)
}

/** Draws [block] through the flap's matrix, made by [flapMatrix]. */
private inline fun DrawScope.throughFlap(kit: SealKit, block: DrawScope.() -> Unit) {
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val saved = native.save()
        native.concat(kit.matrix)
        block()
        native.restoreToCount(saved)
    }
}

// ---- A: the taped paper folds once and goes

private fun DrawScope.flapPictures(sheet: LetterSheet) {
    sheet.top = 16.dp.toPx()
    val hinge = sheet.ph / 2f
    sheet.flapFront = picture(sheet.pw, hinge + sheet.top) {
        translate(0f, sheet.top) {
            clipRect(0f, -sheet.top, sheet.pw, hinge) {
                paperFront(sheet)
                paperTape(sheet.pw)
            }
        }
    }
    sheet.flapBack = picture(sheet.pw, hinge + sheet.top) {
        translate(0f, sheet.top) {
            clipRect(0f, -sheet.top, sheet.pw, hinge) {
                paperTape(sheet.pw)
                paperBack(sheet)
            }
        }
    }
}

/**
 * A: the upper half of the paper, drawn over its real lower half, folds down with depth (its face
 * turning from the light, then its back with the words showing through), and the folded note goes:
 * with the real paper, which the layer of sealPaper moves the same way.
 */
private fun DrawScope.foldAndGo(ms: Long, sheet: LetterSheet, turn: Float, kit: SealKit) {
    if (ms >= SealTakes.A_CARD_FROM) return
    if (sheet.flapFront == null) flapPictures(sheet)
    val phi = if (ms >= SealTakes.A_GO_FROM) 180f else SealTakes.foldAngle(ms)
    val go = SealTakes.goShare(ms, SealTakes.A_GO_FROM, SealTakes.A_GO_MS)
    val pivot = Offset(sheet.box.width / 2f, sheet.box.height / 2f)
    val rise = GO_AWAY_UP_A.dp.toPx()
    faded(SealTakes.goAlpha(ms, SealTakes.A_GO_FROM, SealTakes.A_GO_MS), kit) {
        withTransform({
            if (go > 0f) {
                translate(go * sheet.box.width * GO_AWAY_X, -go * rise)
                rotate(go * GO_AWAY_TURN, pivot)
                scale(1f - GO_AWAY_SHRINK * go, 1f - GO_AWAY_SHRINK * go, pivot)
            }
        }) {
            inPaper(sheet.pw, sheet.ph, turn) { foldingHalf(sheet, phi, kit) }
        }
    }
}

private fun DrawScope.foldingHalf(sheet: LetterSheet, phi: Float, kit: SealKit) {
    val hinge = sheet.ph / 2f
    if (!flapMatrix(kit, 0f, sheet.pw, hinge, hinge, phi, up = true)) return
    val lift = sin(Math.toRadians(phi.toDouble())).toFloat()
    if (phi < 90f) {
        // Its own shadow on the slide, as the paper's, fading as it stands up...
        with(kit.shade) {
            rewind()
            moveTo(kit.dst[0], kit.dst[1])
            lineTo(kit.dst[2], kit.dst[3])
            lineTo(kit.dst[4], kit.dst[5])
            lineTo(kit.dst[6], kit.dst[7])
            close()
        }
        // (Above the crease only: the lower half is the real paper, with its own.)
        val far = 200.dp.toPx()
        clipRect(-far, -far, sheet.pw + far, hinge) { softShadow(kit.shade, 0.7f * (1f - lift), 3.dp.toPx()) }
        // ...and its shade on the lower half under it as it stands up to the light.
        val reach = hinge * 0.5f * lift + 2.dp.toPx()
        clipRect(0f, hinge, sheet.pw, sheet.ph) {
            drawRect(
                Brush.verticalGradient(listOf(RecapInk.Shade.copy(alpha = 0.16f * lift), Color.Transparent), startY = hinge, endY = hinge + reach),
                Offset(0f, hinge), Size(sheet.pw, reach),
            )
        }
    } else {
        // Coming down over the lower half: a shade just ahead of its edge.
        val edge = kit.dst[1].coerceIn(hinge, sheet.ph)
        val reach = 6.dp.toPx() + 22.dp.toPx() * lift
        clipRect(0f, hinge, sheet.pw, sheet.ph) {
            drawRect(
                Brush.verticalGradient(listOf(RecapInk.Shade.copy(alpha = 0.05f + 0.15f * lift), Color.Transparent), startY = edge, endY = edge + reach),
                Offset(0f, edge), Size(sheet.pw, reach),
            )
        }
    }
    val picture = (if (phi < 90f) sheet.flapFront else sheet.flapBack) ?: return
    throughFlap(kit) {
        drawImage(picture, Offset(0f, -sheet.top))
        // Turned from the light it darkens, most when it stands on its edge.
        drawRect(RecapInk.Shade.copy(alpha = (if (phi < 90f) 0.2f else 0.14f) * lift), Offset.Zero, Size(sheet.pw, hinge))
    }
    if (phi >= 179f) {
        // Folded: its crease catches the light.
        drawRect(Color.White.copy(alpha = 0.55f), Offset(0f, hinge), Size(sheet.pw, 1.dp.toPx()))
    }
}

// ---- B: into its envelope

/** B's envelope in the paper's frame: a little wider than the paper, its top 10 dp under the paper's. */
private class EnvelopeShape(val left: Float, val top: Float, val w: Float, val h: Float) {
    /** The flap's length from its hinge, where the sides meet in a V, and the bottom flap's point. */
    val flap = h * 0.56f
    val vee = h * 0.5f
    val apex = h * 0.54f
}

private fun DrawScope.envelopeOf(sheet: LetterSheet) = EnvelopeShape(-9.dp.toPx(), 10.dp.toPx(), sheet.pw + 18.dp.toPx(), sheet.ph + 26.dp.toPx())

/** Draws [block] in the envelope's own frame (its top left at 0, 0) as it comes up, gives under the wax and goes. */
private inline fun DrawScope.envelopeMoving(ms: Long, sheet: LetterSheet, e: EnvelopeShape, block: DrawScope.() -> Unit) {
    val up = RecapMotion.Settle.transform((ms / SealTakes.B_RISE_MS.toFloat()).coerceIn(0f, 1f))
    val go = SealTakes.goShare(ms, SealTakes.B_GO_FROM, SealTakes.B_GO_MS)
    val press = SealTakes.pressMs(SealTake.B)
    val dip = if (ms >= press && ms < press + DIP_MS) sin(PI * (ms - press) / DIP_MS).toFloat() * DIP_DP.dp.toPx() else 0f
    val centre = Offset(e.left + e.w / 2f, e.top + e.h / 2f)
    val shrink = 1f - GO_AWAY_SHRINK * go
    val below = ENVELOPE_FROM_BELOW.dp.toPx()
    val rise = GO_AWAY_UP_B.dp.toPx()
    withTransform({
        translate(go * sheet.box.width * GO_AWAY_X, (1f - up) * below + dip - go * rise)
        rotate((1f - up) * ENVELOPE_TURN + go * GO_AWAY_TURN, centre)
        scale(shrink, shrink, centre)
        translate(e.left, e.top)
    }, block)
}

/** The envelope's outline, its corners a little round, into [out]. */
private fun DrawScope.envelopeOutline(out: Path, e: EnvelopeShape) {
    out.rewind()
    out.addRoundRect(RoundRect(0f, 0f, e.w, e.h, CornerRadius(ENVELOPE_CORNER.dp.toPx())))
}

/** B, under the paper: the envelope's shadow, its back and lining, and its flap while open behind the letter. */
private fun DrawScope.envelopeBehind(ms: Long, sheet: LetterSheet, turn: Float, kit: SealKit) {
    // Once shut, all of it is in front (envelopeOver).
    if (ms >= SealTakes.B_FLAP_FROM + SealTakes.B_FLAP_MS) return
    val e = envelopeOf(sheet)
    val shows = (ms / (SealTakes.B_RISE_MS * 0.25f)).coerceIn(0f, 1f)
    inPaper(sheet.pw, sheet.ph, turn) {
        faded(shows, kit) {
            envelopeMoving(ms, sheet, e) {
                envelopeOutline(kit.path, e)
                softShadow(kit.path, 1f, 4.dp.toPx())
                drawPath(kit.path, ENVELOPE_INSIDE)
                val inset = 5.dp.toPx()
                drawRect(kit.liner(this), Offset(inset, inset), Size(e.w - 2f * inset, e.h - inset))
                drawRect(RecapInk.Shade.copy(alpha = 0.12f), Offset(inset, inset), Size(e.w - 2f * inset, e.h - inset))
                val phi = SealTakes.flapAngle(ms)
                if (phi > 90f) envelopeFlap(e, phi, kit, sheet, inside = true)
            }
        }
    }
}

/** B, over the paper: the envelope's pocket, its flap once it comes over, the wax, and all of it going. */
private fun DrawScope.envelopeOver(ms: Long, sheet: LetterSheet, turn: Float, kit: SealKit) {
    if (ms >= SealTakes.B_GO_FROM + SealTakes.B_GO_MS) return
    val e = envelopeOf(sheet)
    val shows = (ms / (SealTakes.B_RISE_MS * 0.25f)).coerceIn(0f, 1f) * SealTakes.goAlpha(ms, SealTakes.B_GO_FROM, SealTakes.B_GO_MS)
    val phi = SealTakes.flapAngle(ms)
    val shut = ms >= SealTakes.B_FLAP_FROM + SealTakes.B_FLAP_MS
    inPaper(sheet.pw, sheet.ph, turn) {
        faded(shows, kit) {
            envelopeMoving(ms, sheet, e) {
                envelopeOutline(kit.path, e)
                // Shut, it is all in front of the paper: its shadow comes with it.
                if (shut) softShadow(kit.path, 1f, 4.dp.toPx() + 6.dp.toPx() * SealTakes.goShare(ms, SealTakes.B_GO_FROM, SealTakes.B_GO_MS))
                clipPath(kit.path) {
                    pocket(e, kit, sheet)
                    if (phi <= 90f) envelopeFlap(e, phi, kit, sheet, inside = false)
                }
                if (ms >= SealTakes.B_WAX_FROM) wax(ms, e, kit)
            }
        }
    }
}

/** The pocket: the sides folded in to meet in a V where the letter goes in, the bottom flap over them. */
private fun DrawScope.pocket(e: EnvelopeShape, kit: SealKit, sheet: LetterSheet) {
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
    drawPath(kit.shade, sheet.grain)
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
    drawPath(kit.path2, sheet.grain)
    drawLine(SEAM, Offset(0f, e.h), Offset(e.w / 2f, e.apex), 1.dp.toPx())
    drawLine(SEAM, Offset(e.w, e.h), Offset(e.w / 2f, e.apex), 1.dp.toPx())
}

/** The flap, turned [phi] from shut, with depth: its lining while open ([inside]), its outside as it comes down. */
private fun DrawScope.envelopeFlap(e: EnvelopeShape, phi: Float, kit: SealKit, sheet: LetterSheet, inside: Boolean) {
    if (!flapMatrix(kit, 0f, e.w, 0f, e.flap, phi, up = false)) return
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
    throughFlap(kit) {
        if (liner != null) {
            drawPath(kit.path3, liner)
            drawPath(kit.path3, RecapInk.Shade.copy(alpha = 0.12f + 0.12f * lift))
            drawPath(kit.path3, ENVELOPE, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
        } else {
            // Nearly down, its shadow on the pocket.
            if (lift < 0.7f) translate(0f, 2.dp.toPx()) { drawPath(kit.path3, RecapInk.Shade.copy(alpha = 0.24f * (1f - lift))) }
            drawPath(kit.path3, ENVELOPE_FLAP)
            drawPath(kit.path3, sheet.grain)
            drawPath(kit.path3, RecapInk.Shade.copy(alpha = 0.2f * lift))
            drawPath(kit.path3, SEAM, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

/** The wax slapped onto the flap's point, as the Recap's stickers slap on: big, down past its size, then its size. */
private fun DrawScope.wax(ms: Long, e: EnvelopeShape, kit: SealKit) {
    val p = ((ms - SealTakes.B_WAX_FROM) / SealTakes.B_WAX_MS.toFloat()).coerceIn(0f, 1f)
    val lands = SealTakes.SLAP_LANDS
    val down = RecapMotion.SlapEasing.transform((p / lands).coerceAtMost(1f))
    val k = if (p < lands) 1.7f + (0.94f - 1.7f) * down else 0.94f + 0.06f * RecapMotion.SlapEasing.transform((p - lands) / (1f - lands))
    val centre = Offset(e.w / 2f, e.flap - 16.dp.toPx())
    rotate(WAX_TURN + 18f * (1f - down), centre) {
        scale(k, k, centre) {
            sealMark(SealTake.B, centre, WAX_R.dp.toPx(), kit.path, alpha = (p / 0.25f).coerceIn(0f, 1f))
        }
    }
}

// ---- C: the sheet gathers into its seal

private fun DrawScope.sheetPictures(sheet: LetterSheet) {
    sheet.bare = picture(sheet.pw, sheet.ph) { paperFront(sheet, words = 0f) }
    sheet.writing = picture(sheet.pw, sheet.ph) { drawText(sheet.words, topLeft = Offset(22.dp.toPx(), 26.dp.toPx())) }
}

/**
 * C: the sheet with the words, over its card, shrinks towards the card's seal as a paper shape does
 * in the Recap (slow, all at once, slow), rounds into a disc, turns red, takes the seal's points,
 * and is pressed down onto the card: the card's own seal takes over, the same in every pixel.
 */
private fun DrawScope.pressedIntoSeal(ms: Long, sheet: LetterSheet, turn: Float, kit: SealKit, places: SealPlaces) {
    val r0 = 26.dp.toPx()
    val target = places.markCentre() ?: paperPoint(sheet.pw - 48.dp.toPx(), 26.dp.toPx() + sheet.line * 2f, sheet.pw, sheet.ph, turn)
    val markTurn = turn + SealTakes.MARK_TURN
    if (ms >= SealTakes.C_PRESS_FROM) {
        if (ms >= SealTakes.C_PRESS_FROM + SealTakes.C_PRESS_MS) return
        val f = SealTakes.pressScale(ms)
        val lift = ((f - 1f) / (SealTakes.LIFT - 1f)).coerceIn(0f, 1f)
        rotate(markTurn, target) { sealMark(SealTake.C, target, r0 * f, kit.path, lift) }
        return
    }
    if (sheet.bare == null) sheetPictures(sheet)
    val e = RecapMotion.Morph.transform(((ms - SealTakes.C_MORPH_FROM) / SealTakes.C_MORPH_MS.toFloat()).coerceIn(0f, 1f))
    val start = Offset(4.dp.toPx() + sheet.pw / 2f, 18.dp.toPx() + sheet.ph / 2f)
    val centre = lerpOffset(start, target, e)
    val rot = turn + SealTakes.MARK_TURN * e
    val outer = r0 * SealTakes.STAR_OUT * SealTakes.LIFT
    val w = lerpFloat(sheet.pw, 2f * outer, e)
    val h = lerpFloat(sheet.ph, 2f * outer, e)
    val corner = lerpFloat(4.dp.toPx(), min(w, h) / 2f, smooth(0f, 0.75f, e))
    sheetOutline(kit.path, w / 2f, h / 2f, corner, outer, smooth(0.7f, 1f, e))
    val hard = smooth(0.75f, 1f, e)
    withTransform({
        translate(centre.x, centre.y)
        rotate(rot, Offset.Zero)
    }) {
        // None at first: the card under it has its own. Soft while it is a sheet, then the seal's.
        softShadow(kit.path, smooth(0f, 0.2f, e) * (1f - hard), 3.dp.toPx())
        if (hard > 0f) translate(0f, 5.dp.toPx()) { drawPath(kit.path, RecapInk.Shade.copy(alpha = 0.19f * hard)) }
        clipPath(kit.path) {
            val k = h / sheet.ph
            withTransform({
                scale(k, k, Offset.Zero)
                translate(-sheet.pw / 2f, -sheet.ph / 2f)
            }) {
                sheet.bare?.let { drawImage(it) }
                val words = 1f - smooth(0.1f, 0.5f, e)
                if (words > 0f) sheet.writing?.let { drawImage(it, alpha = words) }
            }
            drawPath(kit.path, RecapRed.copy(alpha = smooth(0.3f, 0.8f, e)))
            // The tape stays on the card: where the sheet still lies under it, it is over the sheet.
            withTransform({
                rotate(-rot, Offset.Zero)
                translate(-centre.x, -centre.y)
            }) {
                inPaper(sheet.pw, sheet.ph, turn) { paperTape(sheet.pw) }
            }
        }
        val mark = smooth(0.78f, 1f, e)
        if (mark > 0f) {
            drawCircle(Color.Black.copy(alpha = 0.12f * mark), r0 * SealTakes.LIFT * 0.7f, Offset.Zero, style = Stroke(2.dp.toPx()))
            playMark(Offset.Zero, r0 * SealTakes.LIFT * 0.4f, kit.shade, RecapInk.Cream.copy(alpha = mark))
        }
    }
}

/**
 * The sheet's outline about its middle, into [out]: a rectangle of half sizes [hw] by [hh] with
 * corners of [corner], becoming the seal's star of [outer] as [star] goes from 0 to 1.
 */
private fun sheetOutline(out: Path, hw: Float, hh: Float, corner: Float, outer: Float, star: Float) {
    out.rewind()
    if (star <= 0f) {
        out.addRoundRect(RoundRect(-hw, -hh, hw, hh, CornerRadius(corner)))
        return
    }
    val points = PaperShapes.of(Paper.SEAL)
    for (k in 0 until PaperShapes.N) {
        val a = -PI / 2 + 2 * PI * k / PaperShapes.N
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val box = roundRectReach(c, s, hw, hh, corner)
        val point = outer * hypot(points[2 * k] - 50f, points[2 * k + 1] - 50f) / 50f
        val reach = box + (point - box) * star
        if (k == 0) out.moveTo(reach * c, reach * s) else out.lineTo(reach * c, reach * s)
    }
    out.close()
}

/** How far a ray from the middle of a rounded rectangle of half sizes [hw] by [hh] goes along ([c], [s]) before it leaves it. */
internal fun roundRectReach(c: Float, s: Float, hw: Float, hh: Float, corner: Float): Float {
    val ac = abs(c)
    val asn = abs(s)
    var t = min(if (ac > 1e-6f) hw / ac else Float.MAX_VALUE, if (asn > 1e-6f) hh / asn else Float.MAX_VALUE)
    val r = corner.coerceIn(0f, min(hw, hh))
    val ix = hw - r
    val iy = hh - r
    if (r > 0f && abs(t * c) > ix && abs(t * s) > iy) {
        // In a corner: where the ray leaves the corner's circle.
        val qx = ix * sign(c)
        val qy = iy * sign(s)
        val b = c * qx + s * qy
        val d = b * b - (qx * qx + qy * qy - r * r)
        t = b + sqrt(max(0f, d))
    }
    return t
}

// ---- the seal on a sealed card

/**
 * The seal on a sealed card, drawn the way its take sealed it: [SealTake.B]'s wax, [SealTake.C]'s
 * scalloped seal, or the red disc of before. [r] is half its square (the card's is 52 dp across),
 * [lift] from 0 (pressed on) to 1 (held just above the paper), [alpha] how much it shows.
 */
internal fun DrawScope.sealMark(take: SealTake, centre: Offset, r: Float, path: Path, lift: Float = 0f, alpha: Float = 1f) {
    when (take) {
        SealTake.B -> waxSeal(centre, r, path, alpha)
        SealTake.C -> scallopSeal(centre, r, path, lift, alpha)
        SealTake.A, SealTake.BEFORE -> discSeal(centre, r, path, alpha)
    }
}

/** The red disc of before, the app's mark on it. */
private fun DrawScope.discSeal(centre: Offset, r: Float, path: Path, alpha: Float) {
    drawCircle(RecapInk.Shade.copy(alpha = 0.25f * alpha), r * 0.98f, centre + Offset(0f, 2.dp.toPx()))
    drawCircle(RecapRed.copy(alpha = alpha), r * 0.94f, centre)
    drawCircle(Color.Black.copy(alpha = 0.12f * alpha), r * 0.72f, centre, style = Stroke(2.dp.toPx()))
    playMark(centre, r * 0.4f, path, RecapInk.Cream.copy(alpha = alpha))
}

/** The Recap's seal shape (Paper.SEAL: 24 soft points) in the app's red, its mark on it. */
private fun DrawScope.scallopSeal(centre: Offset, r: Float, path: Path, lift: Float, alpha: Float) {
    shapePath(path, Paper.SEAL, centre, r * SealTakes.STAR_OUT)
    translate(0f, 2.dp.toPx() + 3.dp.toPx() * lift) { drawPath(path, RecapInk.Shade.copy(alpha = (0.25f - 0.06f * lift) * alpha)) }
    drawPath(path, RecapRed.copy(alpha = alpha))
    drawCircle(Color.Black.copy(alpha = 0.12f * alpha), r * 0.7f, centre, style = Stroke(2.dp.toPx()))
    playMark(centre, r * 0.4f, path, RecapInk.Cream.copy(alpha = alpha))
}

/** A blob of wax, never quite round, the stamp's round impression in it and the app's mark pressed in. */
private fun DrawScope.waxSeal(centre: Offset, r: Float, path: Path, alpha: Float) {
    shapePath(path, Paper.BLOB, centre, r * 0.94f)
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

/** One of the Recap's paper shapes round [centre], [outer] to its farthest point, into [out]. */
private fun shapePath(out: Path, shape: Paper, centre: Offset, outer: Float) {
    val points = PaperShapes.of(shape)
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

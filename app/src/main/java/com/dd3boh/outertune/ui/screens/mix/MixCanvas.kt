/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.LaneState
import com.dd3boh.outertune.mix.edit.Shapes
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.aGoneAt
import com.dd3boh.outertune.mix.edit.laneAt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** What a tap on the canvas has selected: one handle, or every exit (or entry) of a song. */
sealed interface Selection {
    data class Handle(val id: LaneId) : Selection
    data class Bracket(val side: Side) : Selection
}

/** The canvas's own state, apart from the spec: the view, the zoom, what is held or selected. */
@Stable
class CanvasState {
    /** 0 the Songs view, 1 the lanes (Bands or Parts), between while it folds. */
    var view by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)

    /** The first bar shown when zoomed in. */
    var start by mutableDoubleStateOf(Double.NaN)
    var adjust by mutableStateOf<Lane?>(null)
    var selection by mutableStateOf<Selection?>(null)
    var held by mutableStateOf<String?>(null)

    /** How far a song is pulled past its wall, in px, springing back on release. */
    var overA by mutableFloatStateOf(0f)
    var overB by mutableFloatStateOf(0f)

    /** The label that floats over a drag: its text and where. */
    var float by mutableStateOf<Pair<String, Offset>?>(null)
    var solo by mutableStateOf<LaneId?>(null)

    /** A brief cross-fade of brightness after a preset: 1 just after, 0 when settled. */
    var flash by mutableFloatStateOf(0f)
}

/** The colours that mean something, fixed across themes (DESIGN.md 3.4), and the theme's own. */
class MixColors(
    val dark: Boolean,
    val primary: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
) {
    val vocals = if (dark) Color(0xFFEC70A0) else Color(0xFFC2185B)
    val drumsMids = if (dark) Color(0xFFF2AA4C) else Color(0xFFB26A00)
    val bassLows = if (dark) Color(0xFF5C98FF) else Color(0xFF1E63D6)
    val melody = if (dark) Color(0xFF68C89E) else Color(0xFF1B7F55)
    val highs = if (dark) Color(0xFFECE9E3) else Color(0xFF5F5B55)
    val filter = if (dark) Color(0xFFB48CFF) else Color(0xFF6A3FC8)
    val tickOk = if (dark) Color(0xFF7FD69A) else Color(0xFF1B7F55)
    val tickDrift = drumsMids

    fun lane(lane: Lane): Color = when (lane) {
        Lane.LOWS, Lane.BASS -> bassLows
        Lane.MIDS, Lane.DRUMS -> drumsMids
        Lane.HIGHS -> highs
        Lane.VOCALS -> vocals
        Lane.MELODY -> melody
    }
}

/**
 * The canvas laid out for one frame: where each band sits, where each bar falls, and everything a
 * finger can take, worked out once and used by both the drawing and the gestures so they never
 * disagree. Every length is in px.
 */
class CanvasLayout(
    val editor: MixEditor,
    val spec: TransitionSpec,
    val width: Float,
    density: Density,
    val view: Float,
    val zoom: Float,
    startWanted: Double,
    val adjust: Lane?,
    val small: Boolean,
) {
    val dp = density.density
    val pad = 12 * dp
    val lanes: List<Lane> = spec.lanes.lanes

    /** The order the Songs view stacks them, outermost first. */
    val stack: List<Lane> = if (spec.lanes == LaneSet.PARTS) listOf(Lane.BASS, Lane.MELODY, Lane.DRUMS, Lane.VOCALS) else listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)

    class Band(val top: Float, val height: Float) {
        val bottom get() = top + height
        val centre get() = top + height / 2
    }

    private fun mix(a: Band, b: Band, t: Float) = Band(a.top + (b.top - a.top) * t, a.height + (b.height - a.height) * t)

    val a: Band
    val aStrip: Band
    val ruler: Band
    val bStrip: Band
    val b: Band
    val total: Float
    val overview: Band?

    /** Each lane's band in the Songs view, in the lanes view, and now. */
    val laneSongs = HashMap<LaneId, Band>()
    val laneLanes = HashMap<LaneId, Band>()
    val lane = HashMap<LaneId, Band>()

    init {
        if (adjust != null) {
            a = Band(0f, 120 * dp); aStrip = Band(120 * dp, 0f); ruler = Band(120 * dp, 28 * dp); bStrip = Band(148 * dp, 0f); b = Band(148 * dp, 120 * dp)
            overview = null
            for (side in Side.entries) for (l in lanes) {
                val band = if (side == Side.A) a else b
                laneSongs[LaneId(side, l)] = band; laneLanes[LaneId(side, l)] = band; lane[LaneId(side, l)] = band
            }
            total = 268 * dp
        } else {
            val sA = Band(0f, 88 * dp)
            val sAStrip = Band(88 * dp, 16 * dp)
            val sRuler = Band(104 * dp, 28 * dp)
            val sBStrip = Band(132 * dp, 16 * dp)
            val sB = Band(148 * dp, 88 * dp)
            val lh = (if (spec.lanes == LaneSet.PARTS) if (small) 24 else 28 else if (small) 28 else 36) * dp
            val gap = 2 * dp
            val sh = lanes.size * lh + (lanes.size - 1) * gap
            val pA = Band(0f, sh)
            val pRuler = Band(sh, 28 * dp)
            val pB = Band(sh + 28 * dp, sh)
            a = mix(sA, pA, view)
            aStrip = mix(sAStrip, Band(sh, 0f), view)
            ruler = mix(sRuler, pRuler, view)
            bStrip = mix(sBStrip, Band(sh + 28 * dp, 0f), view)
            b = mix(sB, pB, view)
            lanes.forEachIndexed { i, l ->
                val ia = LaneId(Side.A, l)
                val ib = LaneId(Side.B, l)
                laneSongs[ia] = sA; laneSongs[ib] = sB
                laneLanes[ia] = Band(i * (lh + gap), lh); laneLanes[ib] = Band(sh + 28 * dp + i * (lh + gap), lh)
                lane[ia] = mix(laneSongs[ia]!!, laneLanes[ia]!!, view); lane[ib] = mix(laneSongs[ib]!!, laneLanes[ib]!!, view)
            }
            var t = 236 * dp + (2 * sh + 28 * dp - 236 * dp) * view
            overview = if (zoom > 1.001f) Band(t + 4 * dp, 16 * dp).also { t += 20 * dp } else null
            total = t
        }
    }

    val fitStart = editor.fitStart(spec)
    val fitEnd = editor.fitEnd(spec)
    val axis = width - 2 * pad
    val ppb: Double = axis / (fitEnd - fitStart) * zoom
    val start: Double = if (zoom <= 1.001f || startWanted.isNaN()) fitStart else startWanted.coerceIn(fitStart, max(fitStart, fitEnd - axis / ppb))
    val length = spec.lengthBars
    val perA = editor.perA(spec)

    fun x(u: Double): Float = (pad + (u - start) * ppb).toFloat()
    fun u(x: Float): Double = start + (x - pad) / ppb

    /** Whether the lanes are apart enough to take handles. */
    val lanesApart: Boolean get() = adjust == null && view > 0.98f
    val songsView: Boolean get() = adjust == null && view < 0.02f

    /** One column a dp wide across the time axis. */
    val columns: Int = max(1, (axis / dp).toInt())
    fun columnU(i: Int): Double = u(pad + (i + 0.5f) * dp)

    // ---- what a finger can take ----

    class Grip(val id: LaneId, val at: Offset, val ux: Double)
    class RampEnd(val id: LaneId, val at: Offset)
    class Bracket(val side: Side, val x1: Float, val x2: Float, val y: Float, val labelX: Float, val label: String, val same: Boolean)
    class LaneLabel(val id: LaneId, val rect: Rect, val text: String)

    val grips = ArrayList<Grip>()
    val rampEnds = ArrayList<RampEnd>()
    val curveLanes = ArrayList<Pair<LaneId, Offset>>()
    val brackets = ArrayList<Bracket>()
    val labels = ArrayList<LaneLabel>()

    fun placeTargets(words: EditorWords, measure: (String) -> Float) {
        grips.clear(); rampEnds.clear(); curveLanes.clear(); brackets.clear(); labels.clear()
        if (spec.plain || adjust != null) return
        val bStart = editor.bStartU(spec)
        if (lanesApart) {
            for (side in Side.entries) for (l in lanes) {
                val id = LaneId(side, l)
                val band = lane[id]!!
                val curve = spec.curves[id]
                if (curve != null) {
                    curve.gain.forEach { k -> curveLanes += id to Offset(x(k.u), band.top + 2 * dp + (1 - k.value.toFloat()) * (band.height - 4 * dp)) }
                    continue
                }
                if (id in spec.muted) continue
                val m = spec.moves[id] ?: continue
                val ux = when (m.how) {
                    How.KEEP -> length
                    How.FROM_START -> bStart
                    else -> m.u
                }
                grips += Grip(id, Offset(x(ux), band.centre), ux)
                if (m.len > 0 && m.how !in MixEditor.FIXED) {
                    val y = if (side == Side.A) band.bottom - 4 * dp else band.top + 4 * dp
                    rampEnds += RampEnd(id, Offset(x(m.u + m.len), y))
                }
            }
            for (side in Side.entries) for (l in lanes) {
                val id = LaneId(side, l)
                val band = lane[id]!!
                var text = words.laneName(l)
                if (id in spec.muted) text = words.mutedLabel(text)
                val w = measure(text) + 10 * dp
                labels += LaneLabel(id, Rect(pad + 2 * dp, band.centre - 10 * dp, pad + 2 * dp + w, band.centre + 10 * dp), text)
            }
        }
        if (songsView) {
            for (side in Side.entries) {
                val ids = editor.bracketIds(spec, side)
                if (ids.isEmpty()) continue
                val moves = ids.mapNotNull { spec.moves[it] }
                var u1 = Double.POSITIVE_INFINITY
                var u2 = Double.NEGATIVE_INFINITY
                for (m in moves) {
                    val s = when (m.how) { How.KEEP -> length; How.FROM_START -> bStart; else -> m.u }
                    val e = if (m.how in MixEditor.FIXED) s else m.end
                    u1 = min(u1, s); u2 = max(u2, e)
                }
                val first = moves.first()
                val same = moves.all { it.how == first.how && abs(it.u - first.u) < 1e-6 && abs(it.len - first.len) < 1e-6 }
                val where = if (first.how == How.FROM_START) "" else words.position(u1, spec)
                val label = when {
                    side == Side.A && same && first.how == How.CUT -> words.cutAt(where)
                    side == Side.A && same -> words.howFrom(first.how, where)
                    side == Side.A -> words.leaves(where)
                    same && first.how == How.FROM_START -> words.fromItsStart()
                    same -> words.howInFrom(first.how, where)
                    else -> words.comes(where)
                }
                val strip = if (side == Side.A) aStrip else bStrip
                val (text, tw) = fitted(label, width - 2 * pad, measure)
                val x1 = x(u1)
                val x2 = x(u2)
                val mid = if (same) x1 + 10 * dp + tw / 2 else (x1 + x2) / 2
                val labelX = if (tw <= width - 2 * pad) mid.coerceIn(pad + tw / 2, width - pad - tw / 2) else width / 2
                brackets += Bracket(side, x1, x2, strip.centre, labelX, text, same)
            }
        }
    }

    /**
     * A bracket's label and its width with its pill, shortened with an ellipsis to fit in [room]:
     * a long language at the largest font size on a narrow phone makes one wider than the canvas,
     * which had no place to go and threw while the page was drawn.
     */
    private fun fitted(label: String, room: Float, measure: (String) -> Float): Pair<String, Float> {
        var text = label
        var tw = measure(text) + 14 * dp
        var keep = label.length
        while (tw > room && keep > 1) {
            keep--
            text = label.take(keep).trimEnd() + "\u2026"
            tw = measure(text) + 14 * dp
        }
        return text to tw
    }

    /** What lies under [p]: the nearest grip within 24 dp wins, as the equalizer's graph does. */
    sealed interface Hit {
        data class GripHit(val grip: Grip) : Hit
        data class RampHit(val end: RampEnd) : Hit
        data class CurveLane(val id: LaneId) : Hit
        data class LabelHit(val label: LaneLabel) : Hit
        data class BracketHit(val bracket: Bracket) : Hit
        data class LaneHit(val side: Side) : Hit
        data object Ruler : Hit
        data object Overview : Hit
        data class Point(val id: LaneId, val index: Int) : Hit
        data class CurveLine(val id: LaneId) : Hit
        data object Nothing : Hit
    }

    fun hit(p: Offset, tolerance: Float = 24 * dp): Hit {
        overview?.let { if (p.y >= it.top - 2 * dp) return Hit.Overview }
        if (p.y >= ruler.top && p.y < ruler.bottom) return Hit.Ruler
        if (adjust != null) {
            for (side in Side.entries) {
                val id = LaneId(side, adjust)
                val band = if (side == Side.A) a else b
                val keys = spec.curves[id]?.gain ?: continue
                var best = -1
                var bestD = Float.MAX_VALUE
                keys.forEachIndexed { i, k ->
                    val d = (Offset(x(k.u), adjustY(band, k.value)) - p).getDistance()
                    if (d < tolerance && d < bestD) { best = i; bestD = d }
                }
                if (best >= 0) return Hit.Point(id, best)
                if (p.y >= band.top && p.y <= band.bottom) {
                    val g = Shapes.eased(keys, u(p.x))
                    return if (abs(p.y - adjustY(band, g)) < 16 * dp) Hit.CurveLine(id) else Hit.Nothing
                }
            }
            return Hit.Nothing
        }
        var bestGrip: Grip? = null
        var bestD = Float.MAX_VALUE
        for (g in grips) {
            val d = (g.at - p).getDistance()
            if (d <= tolerance && d < bestD) { bestGrip = g; bestD = d }
        }
        var bestEnd: RampEnd? = null
        for (e in rampEnds) {
            val d = (e.at - p).getDistance()
            if (d <= 16 * dp && d < bestD) { bestEnd = e; bestD = d }
        }
        if (bestEnd != null) return Hit.RampHit(bestEnd)
        if (bestGrip != null) return Hit.GripHit(bestGrip)
        for ((id, at) in curveLanes) if ((at - p).getDistance() <= 16 * dp) return Hit.CurveLane(id)
        for (l in labels) if (l.rect.inflate(4 * dp).contains(p)) return Hit.LabelHit(l)
        for (br in brackets) {
            val tw = 60 * dp
            if (p.x >= min(br.x1, br.labelX - tw) - 12 * dp && p.x <= max(br.x2, br.labelX + tw) + 12 * dp && abs(p.y - br.y) <= 13 * dp) return Hit.BracketHit(br)
        }
        if (p.y < ruler.top) return Hit.LaneHit(Side.A)
        if (p.y >= b.top - 16 * dp) return Hit.LaneHit(Side.B)
        return Hit.Nothing
    }

    fun adjustY(band: Band, value: Double): Float = band.top + 6 * dp + (1 - value.toFloat()) * (band.height - 12 * dp)
}

/** A cutoff's place on a log scale from 20 Hz to 20 kHz, 0 to 1. */
internal fun logPlace(hz: Double): Float = (ln(hz / 20) / ln(1000.0)).toFloat().coerceIn(0f, 1f)

/** How much of a band a filter lets through: a 2nd-order slope from the band's middle. */
internal fun passed(lane: Lane, state: LaneState): Float {
    val f = when (lane) {
        Lane.LOWS, Lane.BASS -> 60.0
        Lane.HIGHS -> 10_000.0
        Lane.DRUMS -> 3_000.0
        else -> 1_000.0
    }
    var g = 1.0
    state.lowPassHz?.let { g /= sqrt(1 + (f / it).pow(4)) }
    state.highPassHz?.let { g /= sqrt(1 + (it / f).pow(4)) }
    return g.toFloat()
}

/** Everything the drawing reads, gathered so the draw lambda stays small. */
class CanvasInputs(
    val layout: CanvasLayout,
    val aBands: BandEnvelopes,
    val aStartMs: Double,
    val bBands: BandEnvelopes,
    val bStartMs: Double,
    val colors: MixColors,
    val words: EditorWords,
    val measurer: TextMeasurer,
    val state: CanvasState,
    val loading: Boolean,
    val shimmer: Float,
)

private fun DrawScope.label(m: TextMeasurer, text: String, x: Float, y: Float, color: Color, size: Float = 11f, weight: FontWeight = FontWeight.Medium, alpha: Float = 1f) {
    val r = m.measure(text, TextStyle(fontSize = size.sp, fontWeight = weight, color = color))
    drawText(r, topLeft = Offset(x, y - r.size.height / 2f), alpha = alpha)
}

private fun DrawScope.scrimLabel(c: CanvasInputs, text: String, right: Float, y: Float, alpha: Float = 0.9f) {
    val r = c.measurer.measure(text, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = c.colors.onSurfaceVariant))
    val w = r.size.width + 10 * c.layout.dp
    drawRoundRect(c.colors.surface.copy(alpha = 0.72f), Offset(right - w, y - 9 * c.layout.dp), Size(w, 18 * c.layout.dp), CornerRadius(4 * c.layout.dp))
    drawText(r, topLeft = Offset(right - w + 5 * c.layout.dp, y - r.size.height / 2f), alpha = alpha)
}

/** Draws the whole canvas but the playhead, which is drawn over it on its own. */
fun DrawScope.drawMixCanvas(c: CanvasInputs) {
    val g = c.layout
    val spec = g.spec
    val col = c.colors
    val laneBottom = g.b.bottom
    clipRect(g.pad - 1, 0f, size.width - g.pad + 1, size.height) {
        // The change under a 4% wash, with hairlines at its edges.
        val x0 = g.x(0.0)
        val x1 = g.x(g.length)
        drawRect(col.onSurface.copy(alpha = 0.04f), Offset(x0, 0f), Size(x1 - x0, laneBottom))
        drawRect(col.onSurface.copy(alpha = 0.2f), Offset(x0 - 0.5f, 0f), Size(1f, laneBottom))
        drawRect(col.onSurface.copy(alpha = 0.2f), Offset(x1 - 0.5f, 0f), Size(1f, laneBottom))
        // Bar lines, stronger every 4 bars.
        if (spec.bars > 0) {
            val end = g.u(size.width - g.pad)
            var k = ceil(g.start).toInt()
            while (k <= end + 1e-9) {
                val a = if (Math.floorMod(k, 4) == 0) 0.14f else 0.07f
                val x = g.x(k.toDouble())
                drawRect(col.onSurface.copy(alpha = a), Offset(x - 0.5f, g.a.top), Size(1f, g.a.height))
                drawRect(col.onSurface.copy(alpha = a), Offset(x - 0.5f, g.b.top), Size(1f, g.b.height))
                k++
            }
        }
        if (c.loading) {
            drawShimmer(c)
        } else if (g.adjust != null) {
            drawAdjust(c)
        } else {
            if (spec.introTwice > 0) drawIntroTwice(c)
            drawSide(c, Side.A)
            drawSide(c, Side.B)
            drawWalls(c)
            drawTicks(c)
            if (g.lanesApart) {
                drawCurvesAndGrips(c)
                drawLaneLabels(c)
            }
            if (g.songsView) drawBrackets(c)
            if (spec.plain) drawPlain(c)
        }
        drawRuler(c)
    }
    g.overview?.let { drawOverview(c, it) }
}

private fun DrawScope.drawShimmer(c: CanvasInputs) {
    val g = c.layout
    for (band in listOf(g.a, g.b)) {
        val r = CornerRadius(8 * g.dp)
        drawRoundRect(c.colors.surfaceHigh.copy(alpha = 0.7f), Offset(g.pad, band.top + 6 * g.dp), Size(g.axis, band.height - 12 * g.dp), r)
        val gx = g.pad + (g.axis + 160 * g.dp) * c.shimmer - 80 * g.dp
        drawRoundRect(
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to Color.White.copy(alpha = 0f), 0.5f to Color.White.copy(alpha = 0.07f), 1f to Color.White.copy(alpha = 0f),
                startX = gx - 80 * g.dp, endX = gx + 80 * g.dp,
            ),
            topLeft = Offset(g.pad, band.top + 6 * g.dp), size = Size(g.axis, band.height - 12 * g.dp), cornerRadius = r,
        )
    }
}

/** A song's bands for every column, as heard and as they are, both against the song's loud level. */
private class SideLevels(val full: Map<Lane, FloatArray>, val heard: Map<Lane, FloatArray>, val states: Map<Lane, Array<LaneState>>)

private fun levels(c: CanvasInputs, side: Side): SideLevels {
    val g = c.layout
    val spec = g.spec
    val n = g.columns
    val bands = if (side == Side.A) c.aBands else c.bBands
    val origin = if (side == Side.A) c.aStartMs else c.bStartMs
    val ref = bands.reference
    val full = HashMap<Lane, FloatArray>()
    val heard = HashMap<Lane, FloatArray>()
    val states = HashMap<Lane, Array<LaneState>>()
    val wallU = if (side == Side.A) g.editor.aWallU(spec) else g.editor.bWallU(spec)
    val bStartU = g.editor.bStartU(spec)
    val du = g.dp / g.ppb
    for (lane in g.lanes) {
        val id = LaneId(side, lane)
        val f = FloatArray(n)
        val h = FloatArray(n)
        val st = Array(n) { i ->
            val u0 = g.columnU(i) - du / 2
            val u1 = u0 + du
            val ms0 = (if (side == Side.A) spec.aMsAt(u0) else spec.bMsAt(u0)) - origin
            val ms1 = (if (side == Side.A) spec.aMsAt(u1) else spec.bMsAt(u1)) - origin
            var v = bands.peak(lane, ms0, ms1) / ref
            // Walls fade over 12 dp; B is nothing before its first frame.
            val x = g.x(g.columnU(i))
            val wall = if (side == Side.A) ((x - g.x(wallU)) / (12 * g.dp) + 1).coerceIn(0f, 1f) else ((g.x(wallU) - x) / (12 * g.dp) + 1).coerceIn(0f, 1f)
            v *= wall
            if (side == Side.B && g.columnU(i) < bStartU) v = 0f
            f[i] = v
            val s = spec.laneAt(id, g.columnU(i))
            h[i] = (v * s.gain * passed(lane, s)).toFloat()
            s
        }
        full[lane] = f
        heard[lane] = h
        states[lane] = st
    }
    return SideLevels(full, heard, states)
}

/** How a summed level is drawn in the Songs view, and one band's in the lanes view. */
private fun stacked(v: Float): Float = min(1f, v).pow(0.8f)
private fun alone(v: Float): Float = min(1f, v / 0.6f).pow(0.8f)

private fun DrawScope.drawSide(c: CanvasInputs, side: Side) {
    val g = c.layout
    val spec = g.spec
    val n = g.columns
    val lv = levels(c, side)
    val t = g.view
    val off = if (side == Side.A) c.state.overA else c.state.overB
    val stack = g.stack
    // Cumulative sums from the inside out, so each band is a ring around the ones inside it.
    fun cumulative(of: Map<Lane, FloatArray>): Map<Lane, FloatArray> {
        val out = HashMap<Lane, FloatArray>()
        var acc = FloatArray(n)
        for (j in stack.indices.reversed()) {
            val v = of[stack[j]]!!
            val next = FloatArray(n) { acc[it] + v[it] }
            out[stack[j]] = next
            acc = next
        }
        return out
    }
    val cumFull = cumulative(lv.full)
    val cumHeard = cumulative(lv.heard)
    // A's music after its last move and B's before its first frame are drawn faint.
    val faintFrom = if (side == Side.A && !spec.plain) g.x(spec.aGoneAt()) else Float.MAX_VALUE
    val faintTo = if (side == Side.B && spec.bStartMs > 1e-6) g.x(0.0) else -Float.MAX_VALUE
    translateX(off) {
        for (pass in 0..1) {
            for ((j, lane) in stack.withIndex()) {
                val id = LaneId(side, lane)
                val songsBand = g.laneSongs[id]!!
                val lanesBand = g.laneLanes[id]!!
                val cS = songsBand.centre
                val hS = songsBand.height / 2 - 3 * g.dp
                val cP = lanesBand.centre
                val hP = lanesBand.height / 2 - 1.5f * g.dp
                val centre = cS + (cP - cS) * t
                val vals = if (pass == 0) lv.full[lane]!! else lv.heard[lane]!!
                val cum = if (pass == 0) cumFull else cumHeard
                val inner = if (j + 1 < stack.size) cum[stack[j + 1]] else null
                val path = Path()
                val outer = FloatArray(n) { i -> stacked(cum[lane]!![i]) * hS * (1 - t) + alone(vals[i]) * hP * t }
                val inside = FloatArray(n) { i -> (inner?.let { stacked(it[i]) * hS } ?: 0f) * (1 - t) }
                for (i in 0 until n) {
                    val x = g.pad + (i + 0.5f) * g.dp
                    if (i == 0) path.moveTo(x, centre - outer[i]) else path.lineTo(x, centre - outer[i])
                }
                for (i in n - 1 downTo 0) path.lineTo(g.pad + (i + 0.5f) * g.dp, centre - inside[i])
                path.close()
                for (i in 0 until n) {
                    val x = g.pad + (i + 0.5f) * g.dp
                    if (i == 0) path.moveTo(x, centre + inside[i]) else path.lineTo(x, centre + inside[i])
                }
                for (i in n - 1 downTo 0) path.lineTo(g.pad + (i + 0.5f) * g.dp, centre + outer[i])
                path.close()
                val colour = c.colors.lane(lane)
                if (pass == 0) {
                    for ((from, to, a) in listOf(Triple(faintTo, faintFrom, 0.2f), Triple(-Float.MAX_VALUE, faintTo, 0.12f), Triple(faintFrom, Float.MAX_VALUE, 0.12f))) {
                        if (to <= from) continue
                        clipRect(max(from - off, 0f), 0f, min(to - off, size.width), size.height) { drawPath(path, colour.copy(alpha = a)) }
                    }
                } else {
                    val solo = c.state.solo
                    val a = 0.85f * (if (solo != null && solo != id) 0.3f else 1f) * (1f - 0.35f * c.state.flash)
                    drawPath(path, colour.copy(alpha = a))
                }
            }
        }
        // Filter washes: a low-pass from the top, a high-pass from the bottom.
        if (!spec.plain) {
            if (t > 0.5f) {
                for (lane in g.lanes) {
                    val band = g.lane[LaneId(side, lane)]!!
                    val st = lv.states[lane]!!
                    for (i in 0 until n) {
                        val s = st[i]
                        if (s.gain <= 0.01) continue
                        val x = g.pad + i * g.dp
                        s.lowPassHz?.let { drawRect(c.colors.filter.copy(alpha = 0.25f), Offset(x, band.top), Size(g.dp, (1 - logPlace(it)) * band.height)) }
                        s.highPassHz?.let { val d = logPlace(it) * band.height; drawRect(c.colors.filter.copy(alpha = 0.25f), Offset(x, band.bottom - d), Size(g.dp, d)) }
                    }
                }
            } else {
                val band = if (side == Side.A) g.a else g.b
                for (i in 0 until n) {
                    var lp = 0f
                    var hp = 0f
                    for (lane in g.lanes) {
                        val s = lv.states[lane]!![i]
                        if (s.gain <= 0.01) continue
                        s.lowPassHz?.let { lp = max(lp, 1 - logPlace(it)) }
                        s.highPassHz?.let { hp = max(hp, logPlace(it)) }
                    }
                    val x = g.pad + i * g.dp
                    if (lp > 0) drawRect(c.colors.filter.copy(alpha = 0.18f), Offset(x, band.top), Size(g.dp, lp * band.height * 0.9f))
                    if (hp > 0) drawRect(c.colors.filter.copy(alpha = 0.18f), Offset(x, band.bottom - hp * band.height * 0.9f), Size(g.dp, hp * band.height * 0.9f))
                }
            }
        }
        // Echo: dots after the exit that die away, and the word.
        if (side == Side.A && !spec.plain) {
            for (lane in g.lanes) {
                val id = LaneId(Side.A, lane)
                val m = spec.moves[id] ?: continue
                if (id in spec.curves || id in spec.muted || m.how != How.ECHO) continue
                val songsBand = g.laneSongs[id]!!
                val lanesBand = g.laneLanes[id]!!
                val centre = songsBand.centre + (lanesBand.centre - songsBand.centre) * t
                val hS = songsBand.height / 2 - 3 * g.dp
                val hP = lanesBand.height / 2 - 1.5f * g.dp
                val full = lv.full[lane]!!
                val iSt = ((g.x(m.u) - g.pad) / g.dp).toInt().coerceIn(0, n - 1)
                val iEnd = ((g.x(m.end) - g.pad) / g.dp).toInt().coerceIn(0, n - 1)
                var level0 = 0f
                for (i in iSt..iEnd) level0 = max(level0, full[i] * 0.7f)
                val colour = c.colors.lane(lane)
                var x = g.x(m.u)
                val echoBars = Shapes.ECHO_BEATS * spec.beat
                while (x < g.x(m.end + 1.5)) {
                    val u = g.u(x)
                    val i = ((x - g.pad) / g.dp).toInt()
                    if (i in 0 until n) {
                        val lvl = if (u < m.end) max(lv.heard[lane]!![i], level0 * 0.6f) else level0 * 0.5.pow((u - m.end) / echoBars).toFloat()
                        if (lvl >= 0.02f) {
                            val hh = stacked(lvl) * hS * (1 - t) + alone(lvl) * hP * t
                            drawCircle(colour.copy(alpha = 0.95f), 1.3f * g.dp, Offset(x, centre - hh))
                            drawCircle(colour.copy(alpha = 0.95f), 1.3f * g.dp, Offset(x, centre + hh))
                        }
                    }
                    x += 3 * g.dp
                }
                val y = if (t > 0.5f) lanesBand.centre else songsBand.top + 9 * g.dp
                val lx = min(g.x(m.end) + 3 * g.dp, size.width - g.pad - 30 * g.dp)
                label(c.measurer, c.words.echoWord(), lx, y, colour, 10f)
            }
        }
    }
    // What of A is never heard, at the end of its lane.
    if (side == Side.A && !spec.plain) {
        val lost = g.editor.unheardMs(spec)
        if (lost >= 1000 && g.x(spec.aGoneAt()) < size.width - g.pad - 36 * g.dp) {
            scrimLabel(c, c.words.notHeard(lost), size.width - g.pad - 2 * g.dp, g.a.bottom - 11 * g.dp)
        }
    }
}

private inline fun DrawScope.translateX(dx: Float, block: DrawScope.() -> Unit) {
    if (dx == 0f) block() else translate(left = dx, block = block)
}

private fun DrawScope.drawIntroTwice(c: CanvasInputs) {
    val g = c.layout
    val spec = g.spec
    val x0 = max(g.x(-spec.introTwice.toDouble()), g.pad)
    val x1 = min(g.x(0.0), size.width - g.pad)
    if (x1 <= x0) return
    val band = g.b
    clipRect(x0, band.top, x1, band.bottom) {
        // B's first bars from the copy, under A, before B itself starts.
        for (lane in g.stack) {
            val path = Path()
            val n = g.columns
            for (i in 0 until n) {
                val u = g.columnU(i)
                val ms = spec.b.atMs + (u + spec.introTwice) * spec.b.barMs - c.bStartMs
                val h = alone(c.bBands.peak(lane, ms, ms + spec.b.barMs / g.ppb * g.dp) / c.bBands.reference) * (band.height / 2 - 3 * g.dp)
                val x = g.pad + (i + 0.5f) * g.dp
                if (i == 0) path.moveTo(x, band.centre - h) else path.lineTo(x, band.centre - h)
            }
            for (i in n - 1 downTo 0) {
                val u = g.columnU(i)
                val ms = spec.b.atMs + (u + spec.introTwice) * spec.b.barMs - c.bStartMs
                val h = alone(c.bBands.peak(lane, ms, ms + spec.b.barMs / g.ppb * g.dp) / c.bBands.reference) * (band.height / 2 - 3 * g.dp)
                path.lineTo(g.pad + (i + 0.5f) * g.dp, band.centre + h)
            }
            path.close()
            drawPath(path, c.colors.lane(lane).copy(alpha = 0.3f))
        }
        var x = x0 - band.height
        while (x < x1) {
            drawLine(c.colors.onSurface.copy(alpha = 0.22f), Offset(x, band.bottom), Offset(x + band.height, band.top), 1f)
            x += 6 * g.dp
        }
    }
    label(c.measurer, c.words.introTwiceWord(), x0 + 3 * g.dp, band.top + 9 * g.dp, c.colors.onSurfaceVariant, 10f)
}

private fun DrawScope.drawWalls(c: CanvasInputs) {
    val g = c.layout
    val dash = PathEffect.dashPathEffect(floatArrayOf(3 * g.dp, 3 * g.dp))
    val colour = c.colors.onSurfaceVariant.copy(alpha = 0.8f)
    val xa = g.x(g.editor.aWallU(g.spec))
    val xb = g.x(g.editor.bWallU(g.spec))
    if (xa > g.pad && xa < size.width - g.pad) drawLine(colour, Offset(xa, g.a.top), Offset(xa, g.a.bottom), 1f, pathEffect = dash)
    if (xb > g.pad && xb < size.width - g.pad) drawLine(colour, Offset(xb, g.b.top), Offset(xb, g.b.bottom), 1f, pathEffect = dash)
}

/** Downbeat ticks where the songs meet: green within 10 ms of each other, amber where they drift. */
private fun DrawScope.drawTicks(c: CanvasInputs) {
    val g = c.layout
    val spec = g.spec
    if (spec.plain || spec.bars == 0) return
    val gridA = g.editor.gridA(spec) ?: return
    val gridB = g.editor.gridB(spec) ?: return
    val aEnd = g.editor.aEndU(spec)
    val bStart = g.editor.bStartU(spec)
    for (k in 0..floor(g.length + 1e-9).toInt()) {
        val msA = gridA.beatMs(gridA.downbeatNear(spec.aMsAt(k.toDouble())))
        val msB = gridB.beatMs(gridB.downbeatNear(spec.bMsAt(k.toDouble())))
        val uA = (msA - spec.a.atMs) / g.perA
        val uB = (msB - spec.b.atMs) / spec.b.barMs
        val ok = abs((uA - uB) * spec.b.barMs) <= 10.5
        val colour = if (ok) c.colors.tickOk else c.colors.tickDrift
        if (uA <= aEnd) drawRect(colour, Offset(g.x(uA) - g.dp + c.state.overA, g.a.bottom - 4 * g.dp), Size(2 * g.dp, 4 * g.dp))
        if (uB >= bStart) drawRect(colour, Offset(g.x(uB) - g.dp + c.state.overB, g.b.top), Size(2 * g.dp, 4 * g.dp))
    }
}

private fun mixWhite(c: Color, a: Float) = lerp(c, Color.White, a)

private fun DrawScope.drawCurvesAndGrips(c: CanvasInputs) {
    val g = c.layout
    val spec = g.spec
    if (spec.plain) return
    for (side in Side.entries) for (lane in g.lanes) {
        val id = LaneId(side, lane)
        val band = g.lane[id]!!
        val colour = c.colors.lane(lane)
        // The volume across the lane.
        val path = Path()
        for (i in 0 until g.columns) {
            val gain = spec.laneAt(id, g.columnU(i)).gain.toFloat()
            val y = band.top + 2 * g.dp + (1 - gain) * (band.height - 4 * g.dp)
            val x = g.pad + (i + 0.5f) * g.dp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.colors.surface.copy(alpha = 0.55f), style = Stroke(3.5f * g.dp, join = StrokeJoin.Round))
        drawPath(path, mixWhite(colour, 0.35f).copy(alpha = 0.95f), style = Stroke(1.5f * g.dp, join = StrokeJoin.Round))
    }
    for ((_, at) in g.curveLanes) {
        drawCircle(c.colors.onSurface, 3.5f * g.dp, at)
    }
    for (e in g.rampEnds) {
        val colour = c.colors.lane(e.id.lane)
        val path = Path()
        val dir = if (e.id.side == Side.A) -1f else 1f
        val tip = Offset(e.at.x, e.at.y - dir * 4 * g.dp)
        path.moveTo(tip.x, tip.y)
        path.lineTo(tip.x - 5 * g.dp, tip.y + dir * 6 * g.dp)
        path.lineTo(tip.x + 5 * g.dp, tip.y + dir * 6 * g.dp)
        path.close()
        drawPath(path, colour)
    }
    for (grip in g.grips) {
        val colour = c.colors.lane(grip.id.lane)
        val band = g.lane[grip.id]!!
        drawRect(colour.copy(alpha = 0.9f), Offset(grip.at.x - g.dp, band.top), Size(2 * g.dp, band.height))
        val held = c.state.held == grip.id.key
        val selected = (c.state.selection as? Selection.Handle)?.id == grip.id
        val r = (if (held) 12 else 9) * g.dp
        if (selected) drawCircle(c.colors.primary, r + 3 * g.dp, grip.at, style = Stroke(2 * g.dp))
        drawCircle(colour, r, grip.at)
        drawCircle(c.colors.surface, 3.5f * g.dp, grip.at)
        val m = spec.moves[grip.id]
        if (m?.how == How.KEEP || m?.how == How.FROM_START) drawCircle(c.colors.surface, r, grip.at, style = Stroke(2 * g.dp))
    }
}

private fun DrawScope.drawLaneLabels(c: CanvasInputs) {
    val g = c.layout
    for (l in g.labels) {
        drawRoundRect(c.colors.surface.copy(alpha = 0.78f), l.rect.topLeft + Offset(0f, 2 * g.dp), Size(l.rect.width, 16 * g.dp), CornerRadius(4 * g.dp))
        label(c.measurer, if (c.state.solo == l.id) c.words.soloLabel(l.text) else l.text, l.rect.left + 5 * g.dp, l.rect.center.y, c.colors.onSurface)
    }
}

private fun DrawScope.drawBrackets(c: CanvasInputs) {
    val g = c.layout
    for (br in g.brackets) {
        val selected = (c.state.selection as? Selection.Bracket)?.side == br.side
        val colour = if (selected) c.colors.primary else c.colors.onSurfaceVariant
        val dir = if (br.side == Side.A) -1f else 1f
        val off = if (br.side == Side.A) c.state.overA else c.state.overB
        val x1 = br.x1 + off
        val x2 = br.x2 + off
        val y = br.y
        val r = c.measurer.measure(br.label, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (selected) c.colors.primary else c.colors.onSurface))
        val tw = r.size.width + 14 * g.dp
        if (!br.same && x2 - x1 > 2f) {
            val p = Path()
            p.moveTo(x1, y + dir * 6 * g.dp); p.lineTo(x1, y); p.lineTo(x2, y); p.lineTo(x2, y + dir * 6 * g.dp)
            drawPath(p, colour.copy(alpha = 0.9f), style = Stroke(1.5f * g.dp))
        } else {
            drawCircle(colour.copy(alpha = 0.9f), 5 * g.dp, Offset(x1, y))
            if (x2 - x1 > 2f) drawLine(colour.copy(alpha = 0.9f), Offset(x1, y), Offset(x2, y), 1.5f * g.dp)
        }
        val lx = br.labelX + off
        drawRoundRect(c.colors.surface, Offset(lx - tw / 2, y - 8 * g.dp), Size(tw, 16 * g.dp), CornerRadius(8 * g.dp))
        drawRoundRect(colour.copy(alpha = if (selected) 1f else 0.55f), Offset(lx - tw / 2 + 0.5f, y - 7.5f * g.dp), Size(tw - 1, 15 * g.dp), CornerRadius(7.5f * g.dp), style = Stroke(g.dp))
        drawText(r, topLeft = Offset(lx - r.size.width / 2f, y - r.size.height / 2f))
    }
}

private fun DrawScope.drawPlain(c: CanvasInputs) {
    val g = c.layout
    val text = c.words.plainOnCanvas()
    val r = c.measurer.measure(text, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.colors.onSurface))
    val w = min(r.size.width + 20 * g.dp, size.width - 2 * g.pad)
    drawRoundRect(c.colors.surfaceHigh.copy(alpha = 0.96f), Offset(size.width / 2 - w / 2, g.b.centre - 14 * g.dp), Size(w, 28 * g.dp), CornerRadius(14 * g.dp))
    drawText(r, topLeft = Offset(size.width / 2 - r.size.width / 2f, g.b.centre - r.size.height / 2f))
}

private fun DrawScope.drawRuler(c: CanvasInputs) {
    val g = c.layout
    val spec = g.spec
    val r = g.ruler
    val colour = c.colors.onSurfaceVariant
    val end = g.u(size.width - g.pad)
    if (spec.bars > 0) {
        val beats = spec.b.beatsPerBar
        var q = ceil(g.start * beats - 1e-9).toInt()
        while (q <= end * beats + 1e-9) {
            val bar = Math.floorMod(q, beats) == 0
            val x = g.x(q.toDouble() / beats)
            drawRect(colour.copy(alpha = if (bar) 0.6f else 0.3f), Offset(x - 0.5f, r.bottom - (if (bar) 7 else 4) * g.dp), Size(1f, (if (bar) 7 else 4) * g.dp))
            q++
        }
        val showBeats = g.ppb > 130 * g.dp
        var k = ceil(g.start).toInt()
        while (k <= end + 1e-9) {
            val a = if (k < 0 || k >= g.length) 0.45f else 0.95f
            label(c.measurer, c.words.barName(k.toDouble(), g.length), g.x(k.toDouble()) + 3 * g.dp, r.centre - 2 * g.dp, colour, alpha = a)
            if (showBeats) for (b in 2..beats) label(c.measurer, b.toString(), g.x(k + (b - 1.0) / beats) + 2 * g.dp, r.centre - 2 * g.dp, colour, 10f, FontWeight.Normal, 0.4f)
            k++
        }
    } else {
        val barS = spec.b.barMs / 1000
        var q = ceil(g.start * barS * 2 - 1e-9).toInt()
        while (q <= end * barS * 2 + 1e-9) {
            val whole = Math.floorMod(q, 2) == 0
            val x = g.x(q / 2.0 / barS)
            drawRect(colour.copy(alpha = if (whole) 0.6f else 0.3f), Offset(x - 0.5f, r.bottom - (if (whole) 7 else 4) * g.dp), Size(1f, (if (whole) 7 else 4) * g.dp))
            q++
        }
        var s = ceil(g.start * barS / 2).toInt() * 2
        while (s <= end * barS + 1e-9) {
            val a = if (s < 0 || s > spec.seconds) 0.45f else 0.95f
            label(c.measurer, if (s == 0) c.words.seconds(0.0) else s.toString(), g.x(s / barS) + 3 * g.dp, r.centre - 2 * g.dp, colour, alpha = a)
            s += 2
        }
    }
}

private fun DrawScope.drawOverview(c: CanvasInputs, r: CanvasLayout.Band) {
    val g = c.layout
    val spec = g.spec
    drawRoundRect(c.colors.surfaceHigh, Offset(g.pad, r.top), Size(g.axis, r.height), CornerRadius(4 * g.dp))
    val n = g.columns
    val span = g.fitEnd - g.fitStart
    for (i in 0 until n step 2) {
        val u = g.fitStart + span * i / n
        val a = c.aBands.peak(Lane.MIDS, spec.aMsAt(u) - c.aStartMs, spec.aMsAt(u + span / n) - c.aStartMs) / c.aBands.reference
        val b = c.bBands.peak(Lane.MIDS, spec.bMsAt(u) - c.bStartMs, spec.bMsAt(u + span / n) - c.bStartMs) / c.bBands.reference
        val x = g.pad + i * g.dp
        drawRect(c.colors.onSurfaceVariant.copy(alpha = 0.45f), Offset(x, r.centre - min(7 * g.dp, a * 10 * g.dp)), Size(1.5f * g.dp, min(7 * g.dp, a * 10 * g.dp)))
        drawRect(c.colors.onSurfaceVariant.copy(alpha = 0.45f), Offset(x, r.centre), Size(1.5f * g.dp, min(7 * g.dp, b * 10 * g.dp)))
    }
    val wx = (g.pad + (g.start - g.fitStart) / span * g.axis).toFloat()
    val ww = g.axis / g.zoom
    drawRect(c.colors.primary.copy(alpha = 0.16f), Offset(wx, r.top), Size(ww, r.height))
    drawRect(c.colors.primary, Offset(wx + 0.5f, r.top + 0.5f), Size(ww - 1, r.height - 1), style = Stroke(1f))
}

private fun DrawScope.drawAdjust(c: CanvasInputs) {
    val g = c.layout
    val spec = g.spec
    val lane = g.adjust ?: return
    val colour = c.colors.lane(lane)
    for (side in Side.entries) {
        val id = LaneId(side, lane)
        val band = if (side == Side.A) g.a else g.b
        val bands = if (side == Side.A) c.aBands else c.bBands
        val origin = if (side == Side.A) c.aStartMs else c.bStartMs
        val hh = band.height / 2 - 8 * g.dp
        val n = g.columns
        val du = g.dp / g.ppb
        for (pass in 0..1) {
            val v = FloatArray(n) { i ->
                val u = g.columnU(i)
                val ms0 = (if (side == Side.A) spec.aMsAt(u - du / 2) else spec.bMsAt(u - du / 2)) - origin
                val ms1 = (if (side == Side.A) spec.aMsAt(u + du / 2) else spec.bMsAt(u + du / 2)) - origin
                val full = bands.peak(lane, ms0, ms1) / bands.reference
                if (pass == 0) full else spec.laneAt(id, u).let { (full * it.gain * passed(lane, it)).toFloat() }
            }
            val path = Path()
            for (i in 0 until n) { val x = g.pad + i * g.dp; val h = alone(v[i]) * hh; if (i == 0) path.moveTo(x, band.centre - h) else path.lineTo(x, band.centre - h) }
            for (i in n - 1 downTo 0) path.lineTo(g.pad + i * g.dp, band.centre + alone(v[i]) * hh)
            path.close()
            drawPath(path, colour.copy(alpha = if (pass == 0) 0.16f else 0.5f))
        }
        drawRect(c.colors.onSurface.copy(alpha = 0.25f), Offset(g.pad, g.adjustY(band, 1.0) - 0.5f), Size(g.axis, 1f))
        drawRect(c.colors.onSurface.copy(alpha = 0.25f), Offset(g.pad, g.adjustY(band, 0.0) - 0.5f), Size(g.axis, 1f))
        val line = Path()
        for (i in 0 until n) { val y = g.adjustY(band, spec.laneAt(id, g.columnU(i)).gain); val x = g.pad + i * g.dp + 0.5f; if (i == 0) line.moveTo(x, y) else line.lineTo(x, y) }
        drawPath(line, mixWhite(colour, 0.2f), style = Stroke(2 * g.dp))
        spec.curves[id]?.gain?.forEachIndexed { i, k ->
            val held = c.state.held == "${id.key}:$i"
            val at = Offset(g.x(k.u), g.adjustY(band, k.value))
            drawCircle(colour, (if (held) 6f else 4.5f) * g.dp, at)
            drawCircle(c.colors.surface, (if (held) 6f else 4.5f) * g.dp, at, style = Stroke(2 * g.dp))
        }
        val title = c.words.laneOf(lane, side)
        label(c.measurer, title, g.pad + 4 * g.dp, if (side == Side.A) band.bottom - 8 * g.dp else band.top + 8 * g.dp, c.colors.onSurfaceVariant)
    }
}

/** The bar a ruler tap plays from: the bar, or the beat when zoomed in. */
fun rulerTapU(layout: CanvasLayout, x: Float): Double {
    val u = layout.u(x)
    return if (layout.zoom >= 3f) floor(u * 4) / 4 else floor(u)
}

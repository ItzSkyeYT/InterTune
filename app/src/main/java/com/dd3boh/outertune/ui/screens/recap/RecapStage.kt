/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One page of the story: the start screen ([at] 0), a slide, or one step of the countdown. [visit] is new each time one comes up. */
@Immutable
internal class PageState(val at: Int, val step: Int, val kind: SlideKind?, val visit: Int, val start: Long, val paper: SlidePaper)

/** The page that went, drawn for a moment longer: under the new sheet ([covered]), or fading. */
@Immutable
internal class Leaving(val page: PageState, val since: Long, val covered: Boolean, val keepMs: Int)

/** How the story moves to a page. */
internal enum class How { ON, BACK, INSTANT, DIVE }

internal enum class WipeKind { GROW, STRIPS, FADE }

/**
 * A new sheet of paper coming in over the last: growing as a shape out of a point, two papers of
 * other colours just ahead of its edge, or sliding in as strips; with Remove animations on, a
 * cross-fade. Worked out from the clock, as the design's drawWipe, so any frame can be drawn.
 */
internal class Wipe(
    val kind: WipeKind,
    val under: Color,
    val over: Color,
    val rims: List<Color>,
    private val outline: FloatArray?,
    private val centre: Offset,
    private val reach: Float,
    private val strips: Int,
    private val vertical: Boolean,
    val start: Long,
    val duration: Int,
    private val unit: Float,
) {
    fun done(now: Long) = now >= start + duration
    fun progress(now: Long) = ((now - start).toFloat() / duration).coerceIn(0f, 1f)

    private fun growth(now: Long): Pair<Float, Float> {
        val e = RecapMotion.WipeEasing.transform(progress(now))
        val k = if (e < 0.55f) e / 0.55f else (e - 0.55f) / 0.45f
        val r = if (e < 0.55f) 0.5f * unit + (reach * 0.35f - 0.5f * unit) * k else reach * 0.35f + reach * 0.65f * k
        val turn = if (e < 0.55f) -0.5f + 0.32f * k else -0.18f + 0.18f * k
        return r to turn
    }

    private fun shape(out: Path, radius: Float, turn: Float) {
        val pts = outline ?: return
        val c = cos(turn)
        val s = sin(turn)
        out.rewind()
        for (p in 0 until PaperShapes.N) {
            val dx = (pts[2 * p] - 50f) / 50f * radius
            val dy = (pts[2 * p + 1] - 50f) / 50f * radius
            val x = centre.x + dx * c - dy * s
            val y = centre.y + dx * s + dy * c
            if (p == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
    }

    /** What the new sheet covers at [now], on a screen of [size], into [out]. */
    fun inside(now: Long, size: Size, out: Path) {
        when (kind) {
            WipeKind.GROW -> growth(now).let { (r, turn) -> shape(out, r, turn) }
            WipeKind.STRIPS -> {
                out.rewind()
                for (i in 0 until strips) {
                    val q = RecapMotion.StripEasing.transform(((now - start - i * RecapMotion.STRIP_GAP_MS).toFloat() / RecapMotion.STRIP_MS).coerceIn(0f, 1f))
                    if (q <= 0f) continue
                    // Each a pixel over its neighbours, so no hairline of the old slide shows between them.
                    if (vertical) {
                        val x0 = i * size.width / strips - 0.5f
                        val x1 = (i + 1) * size.width / strips + 0.5f
                        val h = size.height * q
                        if (i % 2 == 1) out.addRect(Rect(x0, size.height - h, x1, size.height)) else out.addRect(Rect(x0, 0f, x1, h))
                    } else {
                        val y0 = i * size.height / strips - 0.5f
                        val y1 = (i + 1) * size.height / strips + 0.5f
                        val w = size.width * q
                        if (i % 2 == 1) out.addRect(Rect(size.width - w, y0, size.width, y1)) else out.addRect(Rect(0f, y0, w, y1))
                    }
                }
            }
            WipeKind.FADE -> out.rewind()
        }
    }

    /** Rim [i] (0 the nearer) at [now]: a little bigger and a little behind in the turn, so it shows as a paper edge. */
    fun rim(i: Int, now: Long, out: Path) {
        val (r, turn) = growth(now)
        val p = progress(now)
        val grow = min(1f, p * 6f)
        if (i == 0) shape(out, r * 1.05f + 9f * unit * grow, turn - 0.05f) else shape(out, r * 1.1f + 18f * unit * grow, turn - 0.1f)
    }
}

/** Where the parts of each page are on the screen, as they report it: what the paper shapes sit on, and the words confetti passes behind. */
internal class RecapAnchors {
    private val spots = HashMap<String, Rect>()
    private val kept = HashMap<String, Kept>()
    var origin = Offset.Zero

    /** Words on page [visit] kept clear of confetti: a box for each of their lines, where they rest, and when they begin to arrive, in ms after the page came up. */
    private class Kept(val visit: Int, val from: Long, val lines: List<Rect>)

    fun put(visit: Int, name: String, bounds: Rect) {
        spots["$visit/$name"] = bounds.translate(-origin)
    }

    fun get(visit: Int, name: String): Rect? = spots["$visit/$name"]

    /** Words on page [visit] that confetti passes behind from [from] ms after the page came up (Modifier.overConfetti). */
    fun words(visit: Int, name: String, from: Long, lines: List<Rect>) {
        kept["$visit/$name"] = Kept(visit, from, lines.map { it.translate(-origin) })
    }

    /** The lines of words on page [visit] that confetti is not drawn on, [since] ms after the page came up: those that have begun to arrive. */
    fun behind(visit: Int, since: Long): List<Rect> = kept.values.filter { it.visit == visit && since >= it.from }.flatMap { it.lines }

    fun forget(keep: Set<Int>) {
        spots.keys.removeAll { key -> key.substringBefore('/').toIntOrNull() !in keep }
        kept.values.removeAll { it.visit !in keep }
    }
}

/** Marks this part of a slide as the spot [name], for the paper shapes to sit on. Before any entrance, so it is where the part rests. */
internal fun Modifier.anchor(s: SlideScope, name: String): Modifier = onGloballyPositioned {
    s.stage.anchors.put(s.visit, name, it.boundsInRoot())
}

/**
 * Marks these words of a slide as ones that confetti passes behind: from step [at], as they begin
 * to arrive, it is not drawn on them, so they read clearly while it falls. A text that gives its
 * [layout] counts each of its lines as a box of its own, so the end of a short last line is not
 * kept clear for nothing; otherwise the part is one box. Before any entrance, so it is where the
 * words rest.
 */
internal fun Modifier.overConfetti(s: SlideScope, name: String, at: Float, layout: () -> TextLayoutResult? = { null }): Modifier = onGloballyPositioned {
    val o = it.positionInRoot()
    val text = layout()
    val lines = if (text == null || text.lineCount == 0) listOf(Rect(o, it.size.toSize()))
    else List(text.lineCount) { i -> Rect(o.x + text.getLineLeft(i), o.y + text.getLineTop(i), o.x + text.getLineRight(i), o.y + text.getLineBottom(i)) }
    s.stage.anchors.words(s.visit, name, (at * RecapMotion.STEP_MS).toLong(), lines)
}

/** Where a text's lines were laid out, for [overConfetti]: a plain holder rather than state, read only once the text is placed. */
internal class TextLines {
    var layout: TextLayoutResult? = null
}

/**
 * Everything on the Recap's stage that is not a slide's own words: the pages and how one gives
 * way to the next, the paper shapes and where they go, the confetti, all moved by [frame] once a
 * frame from the story's clock. Slides only say what is on them and when it arrives.
 */
@Stable
internal class RecapStage(private val slides: List<Slide>, val still: Boolean, startAt: Int, startStep: Int) {
    val clock = RecapClock()
    val actors = ActorId.entries.map { Actor(it) }
    val anchors = RecapAnchors()
    val confetti = RecapConfetti()

    /** The stage in pixels, and pixels for one of the design's 414 by 736. */
    var size = Size.Zero
        private set
    var unit by mutableFloatStateOf(1f)
        private set

    /**
     * Where the story's room begins, in pixels: under the status bar; and how tall the bars, the
     * pause and the close button are under it. The design has no status bar over its slides, and its
     * bars take the top 48 of its 736: a slide's pieces are placed so that this band of the design
     * falls on the bars here, whatever the screen's height, and the pieces drawn below the design's
     * bars stay clear of the buttons on a short screen too.
     */
    var top = 0f
    var chrome = 0f

    private var visits = 0
    var current by mutableStateOf(page(startAt, startStep, How.INSTANT))
        private set
    var leaving by mutableStateOf<Leaving?>(null)
        private set

    /** The start screen going while its tunnel opens: when it began, or -1. */
    var startGoing by mutableLongStateOf(-1L)
        private set
    var opening = false
        private set
    var wipe by mutableStateOf<Wipe?>(null)
        private set

    /** With Remove animations on: when the shapes last faded out and back in, changing where they were. */
    var shapesFaded = -1L
        private set

    /** When the last part of each page will have arrived, as its parts say (SlideScope.arrives). */
    private val settles = HashMap<Int, Long>()

    fun arrives(visit: Int, endMs: Long) {
        if (endMs > (settles[visit] ?: 0L)) settles[visit] = endMs
    }

    /**
     * When everything on [page] has settled, in ms after it came up: its words and pictures
     * arrived, its numbers landed, its shapes' last move made, number one's confetti over.
     */
    fun settleOf(page: PageState): Long {
        var end = settles[page.visit] ?: 0L
        if (!still) {
            val last = posesOf(page).last()
            end = max(end, (last.at * RecapMotion.STEP_MS).toLong() + max(last.mt, last.set.values.maxOf { it.mt }) + RecapMotion.SHAPES_SETTLE_MS)
            if (page.kind == SlideKind.NUMBER_ONE) end = max(end, (RecapMotion.CONFETTI_NUMBER_ONE_AT * RecapMotion.STEP_MS).toLong() + RecapMotion.CONFETTI_OVER_MS)
        }
        return end
    }

    /** How long [page] stays before the story moves on by itself: it settles, then rests. 0 for the thank-you page, which stays until it is left. */
    fun lengthOf(page: PageState): Long =
        if (page.at == 0 || slides[page.at - 1].ms <= 0L) 0L else settleOf(page) + RecapMotion.REST_MS

    private class Job(val at: Long, val visit: Int, val run: () -> Unit)
    private val jobs = mutableListOf<Job>()

    private class Pending(val visit: Int, val poses: Poses, val move: (Int) -> Move, val since: Long, var frames: Int = 0, val then: (() -> Unit)?)
    private var pending = mutableListOf<Pending>()
    private var introDone = false

    /** Told whenever the story moves to a page, to keep where it is across a turn of the phone. */
    var onPage: (Int, Int) -> Unit = { _, _ -> }

    private fun paperFor(at: Int, step: Int) = if (at == 0) START_PAPER else paperOf(slides[at - 1].kind, step)

    private fun page(at: Int, step: Int, how: How) =
        PageState(at, step, if (at == 0) null else slides[at - 1].kind, ++visits, clock.now, paperFor(at, step))

    fun resize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val first = size == Size.Zero
        size = Size(width, height)
        unit = min(width / 414f, height / 736f)
        if (first) placeFirst()
    }

    /** The design's place ([x], [y] of 414 by 736) on this screen. */
    fun at(x: Float, y: Float) = Offset(x / 414f * size.width, y / 736f * size.height)

    /**
     * Where a piece the design puts [y] down (of 736) goes on this screen. On a slide: the design's
     * band of bars ([BARS] of 736) onto the bars here ([top] to [top] + [chrome]), and the rest of
     * its height onto the rest of the screen's. On the start screen, which has no bars and whose
     * words are laid out on the whole height, the whole height.
     */
    fun pieceY(y: Float): Float = when {
        current.at == 0 -> y / 736f * size.height
        y <= BARS -> top + y / BARS * chrome
        else -> top + chrome + (y - BARS) / (736f - BARS) * (size.height - top - chrome)
    }

    private companion object {
        /** The design's bars at the top: 58 of its 892 pixels, 48 of the 736 its pieces are placed on. */
        const val BARS = 48f
    }

    private val middle get() = Offset(size.width / 2f, size.height / 2f)

    /** The first page, once the stage has its size: the start screen's pieces spring on, or a slide's are put in place. */
    private fun placeFirst() {
        val page = current
        val sets = posesOf(page)
        if (page.at == 0 && !still) {
            whenReady(page.visit, sets.first(), { Move(jump = true) }) {
                // Put in place at once, then made small and see-through, and grown into place one after another.
                actors.forEach { it.shrinkFrom(0.12f, -50f, clock.now) }
                after(40, page.visit) {
                    placeAll(page.visit, sets.first()) { i -> Move(delay = i * RecapMotion.INTRO_STAGGER_MS, mt = RecapMotion.INTRO_MS, layerStep = RecapMotion.INTRO_LAYER_MS) }
                }
            }
        } else {
            whenReady(page.visit, if (still) sets.last() else sets.first(), { Move(jump = true) })
            scheduleLater(page, sets)
            enter(page)
        }
    }

    private fun posesOf(page: PageState): List<Poses> = page.kind?.let { Choreography.of(it, page.step) } ?: Choreography.start

    /** Moves the story to page ([at], [step]): the new sheet comes in, the old page goes, the shapes take their new places. */
    fun go(at: Int, step: Int, how: How) {
        val from = current
        if (from.at == at && from.step == step && how != How.INSTANT) return
        wipe = null
        val next = page(at, step, how)
        val sheet = makeWipe(from, next, how)
        leaving = if (how == How.INSTANT || how == How.DIVE) null
        else Leaving(from, clock.now, covered = sheet != null && sheet.kind != WipeKind.FADE, keepMs = max(RecapMotion.KEEP_LEAVING_MS, (sheet?.duration ?: 0) + 30))
        startGoing = -1L
        current = next
        wipe = sheet
        onPage(at, step)
        anchors.forget(setOfNotNull(next.visit, leaving?.page?.visit))
        settles.keys.retainAll(setOfNotNull(next.visit, leaving?.page?.visit))

        pending.clear()
        val sets = posesOf(next)
        when {
            still && how != How.INSTANT -> {
                // The shapes fade out where they are and come back where the slide leaves them.
                shapesFaded = clock.now
                after(RecapMotion.CALM_SHAPES_JUMP_MS.toLong(), next.visit) { whenReady(next.visit, sets.last(), { Move(jump = true) }) }
            }
            still || how == How.INSTANT -> whenReady(next.visit, if (still) sets.last() else sets.first(), { Move(jump = true) })
            how == How.DIVE -> whenReady(next.visit, sets.first(), { i -> Move(delay = i * RecapMotion.DIVE_STAGGER_MS, mt = RecapMotion.DIVE_MS) }) { growA(next.visit) }
            else -> whenReady(next.visit, sets.first(), { i -> Move(delay = i * sets.first().stagger, mt = sets.first().mt, me = sets.first().me) })
        }
        scheduleLater(next, sets)
        enter(next)
    }

    private fun scheduleLater(page: PageState, sets: List<Poses>) {
        if (still) return
        for (later in sets.drop(1)) {
            after((later.at * RecapMotion.STEP_MS).toLong(), page.visit) {
                whenReady(page.visit, later, { i -> Move(delay = i * later.stagger, mt = later.mt, me = later.me) })
            }
        }
    }

    /** What a page does once it is up: confetti for number one and on the thank-you page. */
    private fun enter(page: PageState) {
        confetti.clear()
        if (still) return
        when (page.kind) {
            SlideKind.NUMBER_ONE -> after((RecapMotion.CONFETTI_NUMBER_ONE_AT * RecapMotion.STEP_MS).toLong(), page.visit) {
                val hero = anchors.get(page.visit, Spot.HERO)?.center ?: at(207f, 237f)
                confetti.burst(clock.now, hero, size, unit)
            }
            SlideKind.THANKS -> after((RecapMotion.CONFETTI_THANKS_AT * RecapMotion.STEP_MS).toLong(), page.visit) {
                confetti.rain(clock.now, size, unit)
            }
            else -> Unit
        }
    }

    private fun makeWipe(from: PageState, to: PageState, how: How): Wipe? {
        if (how == How.INSTANT || size == Size.Zero) return null
        var sheet = to.kind?.let { Choreography.sheet(it, to.step) } ?: return null
        if (sheet is Sheet.Dive) {
            if (how == How.DIVE) return null
            sheet = sheet.again
        }
        val now = clock.now
        if (still) return Wipe(WipeKind.FADE, from.paper.paper, to.paper.paper, emptyList(), null, Offset.Zero, 0f, 0, false, now, RecapMotion.CROSSFADE_MS, unit)
        return when (sheet) {
            is Sheet.Grow -> {
                val centre = when {
                    how == How.BACK -> at(-30f, 368f)
                    sheet.from != null -> actors[sheet.from.ordinal].last?.let { Offset(it.x, it.y) } ?: middle
                    sheet.at != null -> at(sheet.at.first, sheet.at.second)
                    else -> middle
                }
                val far = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
                    .maxOf { hypot(it.x - centre.x, it.y - centre.y) }
                Wipe(
                    WipeKind.GROW, from.paper.paper, to.paper.paper, to.paper.rims, PaperShapes.of(sheet.shape), centre,
                    far / PaperShapes.inner(sheet.shape) * 1.04f, 0, false, now, RecapMotion.WIPE_MS, unit,
                )
            }
            is Sheet.Strips -> Wipe(
                WipeKind.STRIPS, from.paper.paper, to.paper.paper, emptyList(), null, Offset.Zero, 0f, sheet.strips, sheet.vertical,
                now, RecapMotion.STRIP_MS + sheet.strips * RecapMotion.STRIP_GAP_MS, unit,
            )
            is Sheet.Dive -> null
        }
    }

    /**
     * Open Recap: the start screen's words go and its stickers are thrown off, its big blob grows
     * into a tunnel that fills the screen while the other pieces fly outwards, and the first slide,
     * blue as the tunnel's core, is there when it opens. With Remove animations on, a short wait and
     * the first slide.
     */
    fun open() {
        if (opening || current.at != 0) return
        opening = true
        startGoing = clock.now
        val visit = current.visit
        if (still) {
            after(RecapMotion.CALM_OPEN_MS, visit) {
                opening = false
                go(1, 0, How.INSTANT)
            }
            return
        }
        val now = clock.now
        val centre = middle
        actors.forEachIndexed { i, a ->
            val p = a.last ?: return@forEachIndexed
            if (a.id == ActorId.A) {
                // Big enough that its core covers the farthest corner when the slide comes in.
                val far = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
                    .maxOf { hypot(it.x - p.x, it.y - p.y) }
                val needed = 2f * far / (0.73f * PaperShapes.inner(Paper.BLOB))
                val e = RecapMotion.TunnelEasing.transform(RecapMotion.THROUGH_MS.toFloat() / RecapMotion.TUNNEL_MS)
                val tunnel = max(2400f * unit, p.s + (needed - p.s) / e)
                a.place(p.asPose(r = 40f), p.x, p.y, tunnel, now, Move(mt = RecapMotion.TUNNEL_MS, me = RecapMotion.TunnelEasing), unit, centre)
            } else {
                val ring = a.id == ActorId.R
                a.place(
                    p.asPose(r = p.r + 90f, o = if (ring) 0f else p.o),
                    centre.x + (p.x - centre.x) * 3.2f, centre.y + (p.y - centre.y) * 3.2f, if (ring) p.s * 2.6f else p.s,
                    now, Move(delay = (i - 1) * RecapMotion.SCATTER_STAGGER_MS, mt = RecapMotion.SCATTER_MS, me = RecapMotion.ScatterEasing), unit, centre,
                )
            }
        }
        after(RecapMotion.THROUGH_MS, visit) {
            opening = false
            go(1, 0, How.DIVE)
        }
    }

    /** After the dive: the first slide's blob starts small where it belongs and grows into its place. */
    private fun growA(visit: Int) {
        val a = actors[ActorId.A.ordinal]
        val p = a.last ?: return
        a.place(p.asPose(o = 0f), p.x, p.y, p.s * 0.25f, clock.now, Move(jump = true), unit, middle)
        after(30, visit) { a.place(p.asPose(), p.x, p.y, p.s, clock.now, Move(mt = RecapMotion.DIVE_GROW_MS), unit, middle) }
    }

    private fun Placed.asPose(r: Float = this.r, o: Float = this.o) = Pose(sh, s = s, r = r, c = c, k = k, o = o, spin = spin, w = w / unit)

    private fun after(ms: Long, visit: Int, run: () -> Unit) {
        jobs += Job(clock.now + ms, visit, run)
    }

    private fun whenReady(visit: Int, poses: Poses, move: (Int) -> Move, then: (() -> Unit)? = null) {
        pending += Pending(visit, poses, move, clock.now, then = then)
    }

    private fun placeAll(visit: Int, poses: Poses, move: (Int) -> Move) {
        val now = clock.now
        actors.forEachIndexed { i, a ->
            val p = poses.set[a.id] ?: Pose.HIDE
            var x = Float.NaN
            var y = Float.NaN
            var side = if (p.s.isNaN()) Float.NaN else p.s * unit
            if (p.at != null) {
                val spot = anchors.get(visit, p.at)
                val c = spot?.center ?: at(207f, 368f)
                x = c.x + p.dx * unit
                y = c.y + p.dy * unit
                if (!p.fit.isNaN() && spot != null && spot.width > 0f) side = p.fit * spot.width
            } else {
                if (!p.x.isNaN()) x = p.x / 414f * size.width
                if (!p.y.isNaN()) y = pieceY(p.y)
            }
            val m = move(i)
            val own = if (p.mt > 0 || p.me != null) Move(m.delay, if (p.mt > 0) p.mt else m.mt, p.me ?: m.me, m.ms, m.layerStep, m.jump) else m
            a.place(p, x, y, side, now, own, unit, middle)
        }
    }

    /** Once a frame: the clock moves on (not while [paused]), and whatever is due happens. */
    fun frame(dtMs: Double, paused: Boolean) {
        val dt = if (paused) 0.0 else dtMs
        clock.advance(dt)
        val now = clock.now
        if (dt > 0.0) {
            val due = jobs.filter { it.at <= now }
            jobs.removeAll(due)
            for (job in due) if (job.visit == current.visit) job.run()
        }
        // Shapes wait for the parts of the slide they sit on to be laid out: a frame or two.
        if (pending.isNotEmpty() && size != Size.Zero) {
            pending.forEach { it.frames++ }
            val ready = pending.filter { p ->
                p.visit != current.visit || p.frames > 4 || p.poses.set.values.mapNotNull { it.at }.all { anchors.get(p.visit, it) != null }
            }
            pending.removeAll(ready)
            for (p in ready) if (p.visit == current.visit) {
                placeAll(p.visit, p.poses, p.move)
                p.then?.invoke()
            }
        }
        for (a in actors) a.turn(dt.toFloat(), still)
        wipe?.let { w ->
            if (w.done(now)) {
                wipe = null
                if (leaving?.covered == true) leaving = null
            }
        }
        leaving?.let { if (now - it.since >= it.keepMs) leaving = null }
        confetti.cull(now, size)
    }

    /** How opaque the shapes are at [now]: with Remove animations on they fade out and back in as a slide changes. */
    fun shapesAlpha(now: Long): Float {
        if (shapesFaded < 0) return 1f
        val t = (now - shapesFaded).toFloat() / RecapMotion.CALM_SHAPES_MS
        return when {
            t >= 1f -> 1f
            t < 0.4f -> 1f - t / 0.4f
            t < 0.55f -> 0f
            else -> (t - 0.55f) / 0.45f
        }
    }
}

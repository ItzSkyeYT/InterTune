/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.edit.Key as CurveKey
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Shapes
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the canvas asks of the page around it. */
interface CanvasActions {
    fun playFrom(u: Double)
    fun scrub(u: Double)
    fun scrubDone()
    fun say(limit: Limit)
    fun note(text: Int)
    fun laneMenu(id: LaneId, action: LaneAction)
    fun enterAdjust(lane: com.dd3boh.outertune.mix.edit.Lane)
}

enum class LaneAction { MUTE, ALONE, KEEP, ADJUST }

/**
 * The canvas: the two songs, the ruler and the playhead, and every gesture of DESIGN.md 3.5. A
 * finger's first 8 dp decide whether it scrolls the page or moves something; a grip is taken only
 * within 24 dp, the nearest winning; a drag is one undo step, committed when the finger is lifted.
 */
@Composable
fun MixCanvasView(
    vm: MixEditorViewModel,
    editor: MixEditor,
    spec: TransitionSpec,
    pair: LoadedPair?,
    state: CanvasState,
    words: EditorWords,
    colors: MixColors,
    small: Boolean,
    playU: () -> Double,
    actions: CanvasActions,
    shimmer: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val layout = CanvasLayout(editor, spec, width, density, state.view, state.zoom, state.start, state.adjust, small)
        layout.placeTargets(words) { text -> measurer.measure(text, androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)).size.width.toFloat() }
        val current = rememberUpdatedState(layout)
        val loading = pair == null
        val heightDp = with(density) { layout.total.toDp() }
        var laneMenu by remember { mutableStateOf<CanvasLayout.LaneLabel?>(null) }
        var lastTap by remember { mutableStateOf(0L to 0f) }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .semantics { contentDescription = words.summary(editor) }
                // The system's back gesture takes the screen's edges; on the canvas a drag from
                // the edge moves a song or a handle instead (Android keeps 200 dp of it per edge).
                .systemGestureExclusion()
                .focusable()
                .onKeyEvent { e ->
                    // A keyboard: arrows move what is selected a beat, with shift a bar.
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val dir = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onKeyEvent false }
                    val sel = state.selection ?: return@onKeyEvent false
                    val d = dir * if (e.isShiftPressed) 1.0 else editor.step()
                    vm.edit {
                        val ids = when (sel) { is Selection.Handle -> listOf(sel.id); is Selection.Bracket -> bracketIds(this.spec, sel.side) }
                        var next = this.spec
                        for (id in ids) next = handleMoved(next, id, (next.moves[id]?.u ?: 0.0) + d)
                        apply(next)
                    }
                    true
                }
                // Started again when the pair is read again, so a gesture never edits a page that is gone.
                .pointerInput(editor, pair, words) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = current.value
                        if (pair == null) return@awaitEachGesture
                        val before = editor.spec
                        val dp = g.dp
                        var hit = g.hit(down.position)
                        var moved = false
                        var fine = false
                        var limit: Limit? = null
                        var lastSnap: Double? = null
                        var grab = 0.0
                        val startView = state.start.takeIf { !it.isNaN() } ?: g.start
                        when (val h = hit) {
                            is CanvasLayout.Hit.GripHit -> { state.held = h.grip.id.key; grab = g.u(down.position.x) - h.grip.ux }
                            is CanvasLayout.Hit.Point -> state.held = "${h.id.key}:${h.index}"
                            else -> {}
                        }
                        while (true) {
                            val event = withTimeoutOrNull(HOLD_MS) { awaitPointerEvent() }
                            if (event == null) {
                                // Held still: a song's lane now moves by beats; a point held long goes.
                                val h = hit
                                if (h is CanvasLayout.Hit.LaneHit && !fine) {
                                    fine = true
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                if (h is CanvasLayout.Hit.Point && !moved) {
                                    val keys = editor.spec.curves[h.id]?.gain.orEmpty()
                                    if (keys.size <= 2) {
                                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                        actions.note(R.string.mix_adjust_two_points)
                                    } else {
                                        editor.live(editor.curveSet(editor.spec, h.id, keys.filterIndexed { i, _ -> i != h.index }))
                                        vm.adjustEdited = true
                                        vm.publish()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    // The finger is done with this point, however long it stays.
                                    hit = CanvasLayout.Hit.Nothing
                                    moved = true
                                }
                                continue
                            }
                            if (event.changes.count { it.pressed } >= 2) {
                                // Two fingers: zoom from 1x to 8x about their middle.
                                state.held = null
                                state.float = null
                                if (moved) { editor.live(before); vm.publish() }
                                pinch(event.changes.filter { it.pressed }.map { it.position }, g, state)
                                return@awaitEachGesture
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                finish(vm, editor, state, actions, hit, moved, before, limit, down.position, g, haptic, scope, lastTap) { lastTap = it }
                                if (hit is CanvasLayout.Hit.LabelHit && !moved) laneMenu = (hit as CanvasLayout.Hit.LabelHit).label
                                break
                            }
                            val d = change.position - down.position
                            if (!moved) {
                                if (d.getDistance() < touchSlop) continue
                                if (hit is CanvasLayout.Hit.LaneHit) {
                                    // Down first scrolls the page; across only once 8 dp more across than down.
                                    if (abs(d.y) >= abs(d.x) + 8 * dp) { hit = CanvasLayout.Hit.Nothing; break }
                                    if (abs(d.x) < abs(d.y) + 8 * dp) continue
                                }
                                if (hit is CanvasLayout.Hit.Nothing || hit is CanvasLayout.Hit.LabelHit || hit is CanvasLayout.Hit.CurveLane || hit is CanvasLayout.Hit.CurveLine) break
                                moved = true
                                if (hit !is CanvasLayout.Hit.GripHit) state.selection = null
                            }
                            change.consume()
                            when (val h = hit) {
                                is CanvasLayout.Hit.GripHit -> {
                                    state.selection = null
                                    val next = editor.handleMoved(before, h.grip.id, g.u(change.position.x) - grab)
                                    val u = next.moves[h.grip.id]?.u ?: 0.0
                                    if (lastSnap != u) {
                                        if (lastSnap != null) haptic.performHapticFeedback(if (abs(u - u.roundToInt()) < 1e-9) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                                        lastSnap = u
                                    }
                                    editor.live(next); vm.publish()
                                    state.float = words.position(u, next) to Offset(g.x(u), g.lane[h.grip.id]!!.top)
                                }
                                is CanvasLayout.Hit.RampHit -> {
                                    val m = before.moves[h.end.id] ?: continue
                                    val next = editor.rampMoved(before, h.end.id, g.u(change.position.x) - m.u)
                                    val len = next.moves[h.end.id]?.len ?: 0.0
                                    if (lastSnap != len) { if (lastSnap != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick); lastSnap = len }
                                    editor.live(next); vm.publish()
                                    state.float = words.ramp(len, next) to Offset(g.x(m.u + len), g.lane[h.end.id]!!.top)
                                }
                                is CanvasLayout.Hit.BracketHit -> {
                                    val du = g.u(change.position.x) - g.u(down.position.x)
                                    val next = editor.bracketMoved(before, h.bracket.side, du)
                                    val ids = editor.bracketIds(before, h.bracket.side)
                                    val moved0 = ids.firstOrNull { (before.moves[it]?.how ?: How.FADE) !in MixEditor.FIXED_PLACE }
                                    val applied = moved0?.let { (next.moves[it]?.u ?: 0.0) - (before.moves[it]?.u ?: 0.0) } ?: 0.0
                                    if (lastSnap != applied) { if (lastSnap != null) haptic.performHapticFeedback(if (abs(applied - applied.roundToInt()) < 1e-9) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick); lastSnap = applied }
                                    editor.live(next); vm.publish()
                                    state.selection = Selection.Bracket(h.bracket.side)
                                    state.float = ((if (applied > 0) "+" else if (applied < 0) "-" else "") + words.ramp(abs(applied), next)) to Offset(h.bracket.labelX, h.bracket.y - 10 * dp)
                                }
                                is CanvasLayout.Hit.LaneHit -> {
                                    val side = h.side
                                    val per = if (side == Side.A) g.perA else before.b.barMs
                                    val raw = d.x / g.ppb
                                    val (next, why) = editor.anchorMoved(before, side, raw * per, fine)
                                    limit = why
                                    val over = if (why != null) (d.x * 0.3f).coerceIn(-28 * dp, 28 * dp) else 0f
                                    if (side == Side.A) state.overA = over else state.overB = over
                                    val at = if (side == Side.A) next.a.atMs else next.b.atMs
                                    if (lastSnap != at) { if (lastSnap != null) haptic.performHapticFeedback(if (fine) HapticFeedbackType.SegmentFrequentTick else HapticFeedbackType.SegmentTick); lastSnap = at }
                                    editor.live(next); vm.publish()
                                    val title = if (side == Side.A) words.aTitle else words.bTitle
                                    val text = "$title ${words.clockTenths(at)}"
                                    state.float = (if (fine) words.byBeats(text) else text) to Offset(down.position.x + d.x, (if (side == Side.A) g.a.top else g.b.top) + 14 * dp)
                                }
                                is CanvasLayout.Hit.Ruler -> {
                                    val u = editor.snap(g.u(change.position.x)).coerceIn(g.fitStart, g.fitEnd)
                                    actions.scrub(u)
                                    state.float = words.position(u, spec) to Offset(g.x(u), g.ruler.top)
                                }
                                is CanvasLayout.Hit.Overview -> {
                                    val span = g.fitEnd - g.fitStart
                                    state.start = (startView + d.x / g.axis * span).coerceIn(g.fitStart, g.fitEnd - g.axis / g.ppb)
                                }
                                is CanvasLayout.Hit.Point -> {
                                    val keys = editor.spec.curves[h.id]?.gain ?: continue
                                    val i = h.index
                                    val beat = before.beat
                                    val lo = if (i > 0) keys[i - 1].u + beat else g.fitStart
                                    val hi = if (i < keys.size - 1) keys[i + 1].u - beat else g.fitEnd
                                    val u = ((g.u(change.position.x) * 4).roundToInt() / 4.0).coerceIn(lo, maxOf(lo, hi))
                                    val band = if (h.id.side == Side.A) g.a else g.b
                                    val value = ((1 - (change.position.y - band.top - 6 * dp) / (band.height - 12 * dp)) * 20).roundToInt() / 20.0
                                    val next = keys.toMutableList().also { it[i] = CurveKey(u, value.coerceIn(0.0, 1.0)) }
                                    editor.live(editor.curveSet(editor.spec, h.id, next)); vm.publish()
                                    state.float = "${(value.coerceIn(0.0, 1.0) * 100).roundToInt()}%" to Offset(g.x(u), g.adjustY(band, value) - 8 * dp)
                                }
                                else -> {}
                            }
                        }
                        state.held = null
                        state.float = null
                    }
                },
        ) {
            drawMixCanvas(CanvasInputs(layout, pair?.a?.bands ?: EMPTY_BANDS, pair?.a?.pcm?.info?.startMs ?: 0.0, pair?.b?.bands ?: EMPTY_BANDS, pair?.b?.pcm?.info?.startMs ?: 0.0, colors, words, measurer, state, loading, shimmer))
        }
        // The playhead, over the canvas on its own, so playing redraws only this.
        Canvas(Modifier.matchParentSize()) {
            val x = layout.x(playU())
            if (x < layout.pad - 1 || x > size.width - layout.pad + 1) return@Canvas
            drawRect(colors.primary, Offset(x - layout.dp, 0f), androidx.compose.ui.geometry.Size(2 * layout.dp, layout.b.bottom))
            drawCircle(colors.primary, 4 * layout.dp, Offset(x, layout.ruler.centre + 7 * layout.dp))
        }
        // The label that follows a drag.
        state.float?.let { (text, at) ->
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset((at.x - 40 * layout.dp).roundToInt().coerceIn(0, (width - 120 * layout.dp).roundToInt().coerceAtLeast(0)), (at.y - 30 * layout.dp).roundToInt().coerceAtLeast(0)) }
                    .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        // TalkBack: each handle a control with its moves as actions, each song's anchor too.
        if (layout.lanesApart && !spec.plain) {
            for (grip in layout.grips) {
                val id = grip.id
                val m = spec.moves[id] ?: continue
                val name = words.laneName(id.lane)
                val title = if (id.side == Side.A) words.aTitle else words.bTitle
                val text = if (id.side == Side.A) stringResource(R.string.mix_a11y_exit, name, title, words.position(grip.ux, spec), words.howName(m.how))
                else stringResource(R.string.mix_a11y_entry, name, title, words.position(grip.ux, spec), words.howName(m.how))
                val earlier = stringResource(R.string.mix_a11y_beat_earlier)
                val later = stringResource(R.string.mix_a11y_beat_later)
                val barEarlier = stringResource(R.string.mix_a11y_bar_earlier)
                val barLater = stringResource(R.string.mix_a11y_bar_later)
                val longer = stringResource(R.string.mix_how_longer)
                val shorter = stringResource(R.string.mix_how_shorter)
                val hows = How.entries.filter { it.fits(id.side) }
                val howNames = hows.map { words.howName(it) }
                Box(
                    Modifier
                        .offset { IntOffset((grip.at.x - 12 * layout.dp).roundToInt(), (grip.at.y - 12 * layout.dp).roundToInt()) }
                        .size(24.dp)
                        .semantics {
                            contentDescription = text
                            customActions = listOf(
                                CustomAccessibilityAction(earlier) { vm.edit { apply(handleMoved(this.spec, id, m.u - this.spec.beat)) }; true },
                                CustomAccessibilityAction(later) { vm.edit { apply(handleMoved(this.spec, id, m.u + this.spec.beat)) }; true },
                                CustomAccessibilityAction(barEarlier) { vm.edit { apply(handleMoved(this.spec, id, m.u - 1)) }; true },
                                CustomAccessibilityAction(barLater) { vm.edit { apply(handleMoved(this.spec, id, m.u + 1)) }; true },
                                CustomAccessibilityAction(longer) { vm.edit { stepLength(listOf(id), true) }; true },
                                CustomAccessibilityAction(shorter) { vm.edit { stepLength(listOf(id), false) }; true },
                            ) + hows.mapIndexed { i, how -> CustomAccessibilityAction(howNames[i]) { vm.edit { setHow(listOf(id), how) }; true } }
                        },
                )
            }
        }
        if (layout.adjust == null && !spec.plain) {
            for (side in Side.entries) {
                val band = if (side == Side.A) layout.a else layout.b
                val title = if (side == Side.A) words.aTitle else words.bTitle
                val at = if (side == Side.A) spec.a.atMs else spec.b.atMs
                val text = stringResource(R.string.mix_a11y_anchor, title, words.clockTenths(at))
                val earlier = stringResource(R.string.mix_a11y_bar_earlier)
                val later = stringResource(R.string.mix_a11y_bar_later)
                val per = if (side == Side.A) layout.perA else spec.b.barMs
                Box(
                    Modifier
                        .offset { IntOffset((layout.width - layout.pad - 48 * layout.dp).roundToInt(), band.top.roundToInt()) }
                        .size(48.dp, 24.dp)
                        .semantics {
                            contentDescription = text
                            customActions = listOf(
                                CustomAccessibilityAction(earlier) { moveAnchor(vm, editor, actions, side, per); true },
                                CustomAccessibilityAction(later) { moveAnchor(vm, editor, actions, side, -per); true },
                            )
                        },
                )
            }
        }
        // A lane name's menu.
        laneMenu?.let { l ->
            Box(Modifier.offset { IntOffset(l.rect.left.roundToInt(), l.rect.bottom.roundToInt()) }) {
                DropdownMenu(expanded = true, onDismissRequest = { laneMenu = null }) {
                    val muted = l.id in spec.muted
                    DropdownMenuItem(text = { Text(stringResource(if (muted) R.string.mix_lane_unmute else R.string.mix_lane_mute)) }, onClick = { laneMenu = null; actions.laneMenu(l.id, LaneAction.MUTE) })
                    DropdownMenuItem(text = { Text(stringResource(if (state.solo == l.id) R.string.mix_lane_alone_stop else R.string.mix_lane_alone)) }, onClick = { laneMenu = null; actions.laneMenu(l.id, LaneAction.ALONE) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mix_lane_keep)) }, onClick = { laneMenu = null; actions.laneMenu(l.id, LaneAction.KEEP) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mix_lane_adjust)) }, onClick = { laneMenu = null; actions.laneMenu(l.id, LaneAction.ADJUST) })
                }
            }
        }
        // The how bar, over the handle or the bracket it was opened from.
        state.selection?.let { sel ->
            HowBar(vm, editor, spec, sel, layout, words, onCustomize = { lane -> state.selection = null; actions.enterAdjust(lane) })
        }
    }
}

private fun moveAnchor(vm: MixEditorViewModel, editor: MixEditor, actions: CanvasActions, side: Side, deltaMs: Double) {
    val before = editor.spec
    val (next, limit) = editor.anchorMoved(before, side, deltaMs, byBeats = false)
    vm.edit { apply(next) }
    limit?.let(actions::say)
}

/** Two fingers on the canvas: the zoom follows them about the bar between them. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.pinch(start: List<Offset>, g: CanvasLayout, state: CanvasState) {
    if (start.size < 2) return
    val d0 = maxOf(20f, (start[0] - start[1]).getDistance())
    val z0 = state.zoom
    val midX = (start[0].x + start[1].x) / 2
    val u = g.u(midX)
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.size < 2) return
        event.changes.forEach { it.consume() }
        val d = (pressed[0].position - pressed[1].position).getDistance()
        val zoom = (z0 * d / d0).coerceIn(1f, 8f)
        state.zoom = zoom
        val ppb = g.axis / (g.fitEnd - g.fitStart) * zoom
        state.start = u - (midX - g.pad) / ppb
    }
}

/** A gesture's end: a tap's meaning, or a drag's undo step. */
private fun finish(
    vm: MixEditorViewModel,
    editor: MixEditor,
    state: CanvasState,
    actions: CanvasActions,
    hit: CanvasLayout.Hit,
    moved: Boolean,
    before: TransitionSpec,
    limit: Limit?,
    at: Offset,
    g: CanvasLayout,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    scope: kotlinx.coroutines.CoroutineScope,
    lastTap: Pair<Long, Float>,
    tapped: (Pair<Long, Float>) -> Unit,
) {
    state.held = null
    state.float = null
    if (!moved) {
        when (hit) {
            is CanvasLayout.Hit.GripHit -> state.selection = Selection.Handle(hit.grip.id)
            is CanvasLayout.Hit.BracketHit -> state.selection = Selection.Bracket(hit.bracket.side)
            is CanvasLayout.Hit.CurveLane -> actions.enterAdjust(hit.id.lane)
            is CanvasLayout.Hit.Ruler -> {
                val now = System.currentTimeMillis()
                if (now - lastTap.first < DOUBLE_TAP_MS && abs(at.x - lastTap.second) < 24 * g.dp) {
                    // A double tap zooms to 3x on that bar, and back.
                    tapped(0L to 0f)
                    val u = kotlin.math.floor(g.u(at.x)) + 0.5
                    val zoom = if (state.zoom > 1.001f) 1f else 3f
                    val ppb = g.axis / (g.fitEnd - g.fitStart) * zoom
                    state.zoom = zoom
                    state.start = u - (at.x - g.pad) / ppb
                } else {
                    tapped(now to at.x)
                    actions.playFrom(rulerTapU(g, at.x).coerceIn(g.fitStart, g.fitEnd))
                }
            }
            is CanvasLayout.Hit.CurveLine -> {
                val keys = editor.spec.curves[hit.id]?.gain ?: return
                val u = (g.u(at.x) * 4).roundToInt() / 4.0
                if (keys.any { abs(it.u - u) < 1e-9 }) return
                val value = (Shapes.eased(keys, u) * 20).roundToInt() / 20.0
                editor.live(editor.curveSet(editor.spec, hit.id, keys + CurveKey(u, value)))
                vm.adjustEdited = true
                vm.publish()
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
            is CanvasLayout.Hit.Point, is CanvasLayout.Hit.LabelHit -> {}
            else -> state.selection = null
        }
        return
    }
    when (hit) {
        is CanvasLayout.Hit.Ruler -> actions.scrubDone()
        is CanvasLayout.Hit.LaneHit -> {
            if (limit != null) {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                actions.say(limit)
                scope.launch {
                    val from = if (hit.side == Side.A) state.overA else state.overB
                    val spring = Animatable(from)
                    spring.animateTo(0f, tween(200)) { if (hit.side == Side.A) state.overA = value else state.overB = value }
                }
            } else {
                state.overA = 0f; state.overB = 0f
            }
            if (editor.commit(before)) vm.publish()
        }
        is CanvasLayout.Hit.GripHit -> {
            if (editor.handleReleased(hit.grip.id)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            editor.commit(before)
            vm.publish()
        }
        is CanvasLayout.Hit.RampHit, is CanvasLayout.Hit.BracketHit -> {
            state.selection = null
            editor.commit(before)
            vm.publish()
        }
        is CanvasLayout.Hit.Point -> {
            vm.adjustEdited = true
            vm.publish()
        }
        else -> {}
    }
}

private const val HOLD_MS = 400L
private const val DOUBLE_TAP_MS = 320L

private val EMPTY_BANDS = BandEnvelopes(10.0, FloatArray(0), FloatArray(0), FloatArray(0))

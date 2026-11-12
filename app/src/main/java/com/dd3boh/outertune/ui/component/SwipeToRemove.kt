/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.tan

/**
 * What decides whether a swipe on a row takes it away (#141), as plain numbers so that a test can
 * hold every case. A row goes only when the finger lets go of it: it was the Material swipe box
 * that took it as the finger passed half way, with the finger still down and no way to take it
 * back, or at the end of any quick flick, however short. Now a swipe must be clearly sideways, from
 * its first move to where the finger lets go, and long enough, and only then does letting go count.
 *
 * Every measure is the finger's own, from where it first touched the row: [dx] along the row, [dy]
 * across it, in pixels, either way.
 */
object SwipeToRemove {
    /**
     * How far from the horizontal a swipe may stray, from the first touch to the letting go. The box
     * it replaces took anything under 45 degrees, since it only watched the way it was going along.
     */
    const val MAX_ANGLE_DEGREES = 30f

    /** The same limit as a rise over a run: across may be this much of along. */
    private val MAX_SLOPE = tan(Math.toRadians(MAX_ANGLE_DEGREES.toDouble())).toFloat()

    /** How far along the row the finger has to go before letting go takes it: the middle of the row. */
    const val DISTANCE_SHARE = 0.5f

    /** ...but never more than this on a wide row, about three centimetres of thumb. */
    const val DISTANCE_CAP_DP = 200f

    /** What a touch that is still moving, a little, has become. */
    enum class Start {
        /** Not yet far enough either way to say. */
        WAIT,

        /** A swipe: the row follows the finger from here. */
        SWIPE,

        /** Not a swipe: left to the list, which scrolls, to the handle, to a long press. */
        NOT_A_SWIPE,
    }

    /**
     * How far the finger goes along a row [rowWidthPx] wide before letting go takes the row: the
     * middle of it, and on a wide row (a tablet, the queue on its side) [DISTANCE_CAP_DP] at most.
     */
    fun distance(rowWidthPx: Float, density: Float): Float =
        min(rowWidthPx * DISTANCE_SHARE, DISTANCE_CAP_DP * density)

    /** Whether a move of [dx] along and [dy] across is within [MAX_ANGLE_DEGREES] of the horizontal. */
    fun sideways(dx: Float, dy: Float): Boolean = abs(dx) > 0f && abs(dy) <= abs(dx) * MAX_SLOPE

    /**
     * What a touch that has moved [dx], [dy] from where it began has become, with [slop] the distance
     * a finger may wander on a plain touch. Along first: the move that first gets past the slop
     * along the row is judged by its angle then, a swipe if it is clearly sideways and not one if it
     * is not, and the row is the finger's from there on. Across first, or a move too steep, is a
     * scroll or a drag, and it never turns into a swipe half way.
     */
    fun start(dx: Float, dy: Float, slop: Float): Start = when {
        abs(dx) >= slop -> if (sideways(dx, dy)) Start.SWIPE else Start.NOT_A_SWIPE
        abs(dy) >= slop -> Start.NOT_A_SWIPE
        else -> Start.WAIT
    }

    /**
     * Whether the row goes: only when the finger [released] it, [distance] or more along the row
     * ([distance] for the row's width, see above), and clearly sideways from where it touched to
     * where it lets go. Dragged back short of [distance] before letting go, it does not.
     */
    fun removes(dx: Float, dy: Float, distance: Float, released: Boolean): Boolean =
        released && abs(dx) >= distance && sideways(dx, dy)

    /**
     * What the bin shows while the finger is still down: whether letting go now would take the
     * row. The reveal is full when it would, and dimmed when it would not.
     */
    fun ready(dx: Float, dy: Float, distance: Float): Boolean = removes(dx, dy, distance, released = true)
}

/**
 * A row that is swiped sideways, either way, with a bin behind it, and goes only when the finger lets
 * go of it far enough along and clearly sideways ([SwipeToRemove]). Held half way, the bin shows
 * and the song is still in its place; dragged back, it stays; a scroll, a drag by the handle, a
 * tap or a long press are never a swipe. [onRemove] is asked once the row has slid out of sight, and
 * says whether the row is gone: if it says no, the row slides back.
 *
 * The bin is the look of the swipe that adds a song to the queue (DragActionIcon in
 * AnchorDraggable): the same colours, a block as wide as the row has moved, its icon next to the
 * row's edge, dim until the swipe is long enough, with the same tick as it gets there.
 */
@Composable
fun SwipeToRemoveBox(
    onRemove: () -> Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Delete,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnRemove by rememberUpdatedState(onRemove)
    val painter = rememberVectorPainter(icon)
    val block = MaterialTheme.colorScheme.primary
    val tint = MaterialTheme.colorScheme.onPrimary

    // Where the row is, from where it rests: the finger's move less the slop it took to start, and
    // either way. Read in drawing and layout only, so a swipe recomposes nothing.
    var offset by remember { mutableFloatStateOf(0f) }
    var ready by remember { mutableStateOf(false) }
    // The row is sliding out or back after the finger let go. A new touch waits for it.
    var settling by remember { mutableStateOf(false) }

    // The finger let go: slide the row out and ask for it to go, or slide it back. Only here is the
    // row ever asked to go, and only once the finger has let go.
    val settle = remember {
        { remove: Boolean, edge: Float ->
            scope.launch {
                settling = true
                try {
                    if (remove) {
                        animate(offset, if (offset >= 0f) edge else -edge, animationSpec = tween(160, easing = FastOutLinearInEasing)) { value, _ -> offset = value }
                        if (currentOnRemove()) return@launch
                    }
                    ready = false
                    animate(offset, 0f, animationSpec = tween(200, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
                } finally {
                    settling = false
                }
            }
            Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Not on the first touch's say: a handle or a button under it takes its own touch,
                    // and then takes the moves too, which ends this one below.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (settling) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val distance = SwipeToRemove.distance(size.width.toFloat(), density)
                    var swiping = false
                    var along = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.fastFirstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) {
                            // Taken from us, by a scroll, the list, a drag by the handle, or the system.
                            if (swiping) settle(false, 0f)
                            return@awaitEachGesture
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val up = change.changedToUpIgnoreConsumed()
                        if (!swiping) {
                            // A tap, or a long press that ended: not ours.
                            if (up) return@awaitEachGesture
                            when (SwipeToRemove.start(dx, dy, slop)) {
                                SwipeToRemove.Start.WAIT -> continue
                                SwipeToRemove.Start.NOT_A_SWIPE -> return@awaitEachGesture
                                SwipeToRemove.Start.SWIPE -> {
                                    swiping = true
                                    along = sign(dx)
                                }
                            }
                        }
                        change.consume()
                        if (up) {
                            settle(SwipeToRemove.removes(dx, dy, distance, released = true), size.width.toFloat())
                            return@awaitEachGesture
                        }
                        offset = dx - along * slop
                        val now = SwipeToRemove.ready(dx, dy, distance)
                        if (now != ready) {
                            ready = now
                            haptic.performHapticFeedback(if (now) HapticFeedbackType.SegmentFrequentTick else HapticFeedbackType.SegmentTick)
                        }
                    }
                }
            }
    ) {
        // Behind the row: the bin, on the side the row left.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (offset == 0f) 0f else if (ready) 1f else 0.6f }
                .drawBehind {
                    val o = offset
                    if (o == 0f) return@drawBehind
                    val width = min(abs(o), size.width)
                    val left = if (o > 0f) 0f else size.width - width
                    drawRect(block, Offset(left, 0f), Size(width, size.height))
                    clipRect(left, 0f, left + width, size.height) {
                        val iconSize = 50.dp.toPx()
                        val pad = 10.dp.toPx()
                        val x = if (o > 0f) left + width - pad - iconSize else left + pad
                        translate(x, (size.height - iconSize) / 2f) {
                            with(painter) { draw(size = Size(iconSize, iconSize), colorFilter = ColorFilter.tint(tint)) }
                        }
                    }
                }
        )
        Box(
            modifier = Modifier.graphicsLayer { translationX = offset },
            content = content
        )
    }
}

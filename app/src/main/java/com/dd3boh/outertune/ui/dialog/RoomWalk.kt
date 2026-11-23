/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import kotlin.math.roundToInt

/**
 * The walk over the room's sheet that follows the tour's pages (RoomTour), Mel's word of 22 Nov 2026 on seeing the
 * pages: "for the tutorial it should also everything happens in the listen together menu". The real sheet stays where
 * it is, dimmed round one part at a time, with one short line on it and Next or Skip, as apps' coach marks do. Only
 * the parts on screen for this phone's role get a stop: each part is marked in the sheet from its top (WalkFrom) to
 * its foot (WalkTo), and a part that is not drawn has no height. Back steps out at once.
 */
internal enum class WalkStop(@StringRes val line: Int, @StringRes val hostLine: Int = line) {
    ROLE(R.string.together_walk_role),
    INVITE(R.string.together_walk_invite),
    PLAYING(R.string.together_walk_playing),
    PROPOSAL(R.string.together_walk_proposal),
    PEOPLE(R.string.together_walk_people, R.string.together_walk_people_host),
    SETTINGS(R.string.together_walk_settings),
    LEAVE(R.string.together_walk_leave, R.string.together_walk_close),
}

/** The walk is asked for by the tour's last page, and the room's sheet on screen takes it up. */
internal object RoomWalkAsk {
    var wanted by mutableStateOf(false)

    /** How many room pages are on screen to walk: none, and the tour's Done asks for nothing. */
    var areas = 0
}

/** The stops on screen, in order, from where their marks are: those that have a height. */
internal fun walkStops(tops: Map<WalkStop, Float>, feet: Map<WalkStop, Float>): List<WalkStop> =
    WalkStop.entries.filter { stop -> (feet[stop] ?: 0f) - (tops[stop] ?: 0f) > 1f }

internal class RoomWalkState {
    val tops = mutableStateMapOf<WalkStop, Float>()
    val feet = mutableStateMapOf<WalkStop, Float>()
    var area: LayoutCoordinates? = null
    var height by mutableStateOf(0f)
    var at by mutableIntStateOf(-1)

    val on: Boolean get() = at >= 0

    fun start() {
        at = 0
    }

    fun end() {
        at = -1
    }
}

internal val LocalRoomWalk = staticCompositionLocalOf<RoomWalkState?> { null }

/**
 * Where [stop] begins in the sheet: [inset] lower than the mark, for a part that starts with its own space above it
 * (the role line's), so the outline does not reach into the line before it.
 */
@Composable
internal fun WalkFrom(stop: WalkStop, inset: Dp = 0.dp) = WalkMark(stop, top = true, inset = inset)

/** Where [stop] ends in the sheet. */
@Composable
internal fun WalkTo(stop: WalkStop) = WalkMark(stop, top = false)

@Composable
private fun WalkMark(stop: WalkStop, top: Boolean, inset: Dp = 0.dp) {
    val walk = LocalRoomWalk.current ?: return
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { inset.toPx() }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(0.dp)
            .onGloballyPositioned { mark ->
                val area = walk.area ?: return@onGloballyPositioned
                if (!area.isAttached || !mark.isAttached) return@onGloballyPositioned
                val y = area.localPositionOf(mark, Offset.Zero).y + insetPx
                if (top) walk.tops[stop] = y else walk.feet[stop] = y
            }
    )
}

/** The room's page, with its walk over it when one is asked for. */
@Composable
internal fun RoomWalkArea(owner: Boolean, content: @Composable () -> Unit) {
    val walk = remember { RoomWalkState() }
    DisposableEffect(Unit) {
        RoomWalkAsk.areas++
        onDispose { RoomWalkAsk.areas-- }
    }
    val wanted = RoomWalkAsk.wanted
    LaunchedEffect(wanted) {
        if (wanted) {
            RoomWalkAsk.wanted = false
            walk.start()
        }
    }
    CompositionLocalProvider(LocalRoomWalk provides walk) {
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { walk.area = it }
                .onSizeChanged { walk.height = it.height.toFloat() }
        ) {
            content()
            if (walk.on) WalkOver(walk, owner)
        }
    }
}

/** The sheet dimmed round the part [walk] is at, with its line under or over it. */
@Composable
private fun WalkOver(walk: RoomWalkState, owner: Boolean) {
    val stops = walkStops(walk.tops, walk.feet)
    val stop = stops.getOrNull(walk.at)
    if (stop == null) {
        LaunchedEffect(Unit) { walk.end() }
        return
    }
    BackHandler { walk.end() }
    val pad = 8.dp
    val top by animateFloatAsState(walk.tops.getValue(stop), tween(350), label = "walkTop")
    val foot by animateFloatAsState(walk.feet.getValue(stop), tween(350), label = "walkFoot")
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    val edge = MaterialTheme.colorScheme.primary
    var bubbleHeight by remember { mutableStateOf(0) }
    val bring = remember { BringIntoViewRequester() }
    // Taps on the sheet under it do nothing while the walk is on.
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(androidx.compose.ui.platform.LocalDensity.current) { walk.height.toDp() })
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Canvas(Modifier.fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { walk.height.toDp() })) {
            val out = 24.dp.toPx()
            val gap = pad.toPx()
            val hole = RoundRect(Rect(-gap, top - gap, size.width + gap, foot + gap), CornerRadius(16.dp.toPx()))
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(-out, -out * 4, size.width + out, size.height + out))
                addRoundRect(hole)
            }
            drawPath(dim, scrim)
            drawRoundRect(
                edge,
                topLeft = Offset(hole.left, hole.top),
                size = Size(hole.width, hole.height),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
        }
        // Under the part where it fits, over it near the foot of the sheet.
        val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { (pad + 12.dp).toPx() }
        val under = foot + gapPx + bubbleHeight <= walk.height
        val y = if (under) foot + gapPx else top - gapPx - bubbleHeight
        val last = walk.at >= stops.lastIndex
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, y.roundToInt()) }
                .onSizeChanged { bubbleHeight = it.height }
                .bringIntoViewRequester(bring),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
                Text(stringResource(if (owner) stop.hostLine else stop.line), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        "${walk.at + 1}/${stops.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp).weight(1f),
                    )
                    if (!last) TextButton(onClick = { walk.end() }) { Text(stringResource(R.string.together_tour_skip)) }
                    Button(onClick = { if (last) walk.end() else walk.at += 1 }) {
                        Text(stringResource(if (last) R.string.action_done else R.string.action_next))
                    }
                }
            }
        }
    }
    LaunchedEffect(stop, bubbleHeight) { bring.bringIntoView() }
}

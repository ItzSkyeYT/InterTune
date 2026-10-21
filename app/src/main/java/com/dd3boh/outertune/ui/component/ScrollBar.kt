/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

// The scrollbar beside the app's long lists and grids: grab the thumb and the list follows the
// finger. It is our own since issue #34. The library used before worked the list's position out
// again from its layout on every touch event; that count flips as rows slide under the top
// padding, so long lists shook back and forth under a steady finger. Here the list is measured
// once when the thumb is grabbed, and from then on the finger alone says where it goes. The
// arithmetic is in ScrollbarGeometry.kt, where tests run it.

private val ThumbThickness = 8.dp
private val EdgeGap = 8.dp // between the thumb and the edge of the screen
private val HideShift = 14.dp // how far the thumb slides out as it fades
private const val HideDelayMillis = 2000

@Composable
fun LazyColumnScrollbar(
    state: LazyListState,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
    ),
) {
    val list = remember(state) { ListScrolling(state) }
    Scrollbar(list, modifier)
}

@Composable
fun LazyVerticalGridScrollbar(
    state: LazyGridState,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier.windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
    ),
) {
    val grid = remember(state) { GridScrolling(state) }
    Scrollbar(grid, modifier)
}

@Composable
private fun Scrollbar(list: Scrolling, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // The list as it is laid out now, null when it has nothing to scroll, and the thumb for it.
    val snapshot by remember(list) { derivedStateOf { list.layout() } }
    val atRest by remember(list) { derivedStateOf { ScrollbarGeometry.of(snapshot) } }
    val restingTop by remember(list) { derivedStateOf { atRest?.thumbTop(snapshot) ?: 0f } }
    var held by remember(list) { mutableStateOf<HeldThumb?>(null) }
    var letGo by remember(list) { mutableStateOf<ScrollbarLetGo?>(null) }

    val shown = held != null || list.isScrolling
    val visibility by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(if (shown) 125 else 500, if (shown) 0 else HideDelayMillis, FastOutSlowInEasing),
        label = "scrollbar visibility",
    )
    val shift by animateDpAsState(
        if (shown) 0.dp else HideShift,
        tween(if (shown) 125 else 500, if (shown) 0 else HideDelayMillis, FastOutSlowInEasing),
        label = "scrollbar shift",
    )
    val colour by animateColorAsState(
        if (held != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        tween(50),
        label = "scrollbar colour",
    )
    // A thumb that has faded away takes no touch, so the rows have the edge to themselves.
    val touchable by remember { derivedStateOf { visibility > 0f } }
    val draggableState = rememberDraggableState { delta -> held?.moveBy(delta) }
    if (held == null && letGo == null && atRest == null) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (!constraints.hasBoundedHeight || constraints.maxHeight <= 0) return@BoxWithConstraints
        val trackPx = constraints.maxHeight.toFloat()
        // The last press, while the list is as it left it: the thumb stays as the finger left it.
        val kept = letGo?.stillAt(snapshot, trackPx)
        // While held, and after until the list moves, the thumb keeps the length it had at the grab.
        val thumbFrac = (held?.drag ?: kept)?.geometry?.thumbFrac ?: atRest?.thumbFrac ?: return@BoxWithConstraints

        // The thumb, and the one place on the edge that takes a touch: as long as the thumb and as
        // wide as it with its gap on both sides. Before, a strip the height of the list took every
        // touch along the edge, over each row's menu button and drag handle.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                // Read where the thumb is drawn, so a moving list only moves the thumb.
                .offset { IntOffset(0, ((held?.top ?: kept?.top ?: restingTop) * trackPx).roundToInt()) }
                .width(ThumbThickness + EdgeGap * 2)
                .fillMaxHeight(thumbFrac)
                .then(
                    if (touchable || held != null) Modifier.draggable(
                        state = draggableState,
                        orientation = Orientation.Vertical,
                        onDragStarted = {
                            // A grab where the last press let go carries it on while the list is
                            // still as it left it, else the list is measured afresh.
                            val drag = letGo?.stillAt(snapshot, trackPx) ?: atRest?.let { ScrollbarDrag(it, restingTop, trackPx) }
                            if (drag != null) {
                                val grabbed = HeldThumb(drag)
                                held = grabbed
                                letGo = null
                                scope.launch {
                                    try {
                                        grabbed.follow(list)
                                    } finally {
                                        // Once the last move is done, the thumb stays where the finger
                                        // left it until the list moves, then goes by the list's own place.
                                        if (held === grabbed) {
                                            held = null
                                            letGo = ScrollbarLetGo(grabbed.drag, list.layout())
                                        }
                                    }
                                }
                            }
                        },
                        onDragStopped = { held?.release() },
                    ) else Modifier
                )
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        alpha = visibility
                        translationX = (if (rtl) -shift else shift).toPx()
                    }
                    .padding(end = EdgeGap)
                    .width(ThumbThickness)
                    .fillMaxHeight()
                    .background(colour, CircleShape)
            )
        }
    }
}

/** The thumb while a finger holds it: the press's arithmetic, and what Compose needs on top. */
private class HeldThumb(val drag: ScrollbarDrag) {
    /** Where the finger has put the thumb. It is drawn here until it is let go. */
    var top by mutableFloatStateOf(drag.top)
        private set

    // Only the newest position counts: when touch events come faster than the list scrolls, the
    // next scroll goes straight to the last of them instead of working through each in turn.
    private val positions = Channel<Float>(Channel.CONFLATED)

    fun moveBy(deltaPx: Float) {
        if (drag.moveBy(deltaPx)) {
            top = drag.top
            positions.trySend(drag.top)
        }
    }

    fun release() {
        positions.close()
    }

    /** Scrolls the list after the thumb, one move at a time, until the thumb is let go. */
    suspend fun follow(list: Scrolling) {
        for (position in positions) {
            try {
                drag.goTo(position, list)
            } catch (e: CancellationException) {
                // Another scroll took the list over for a moment, a screen bringing the playing
                // song into view for one. Every move goes to a place, not by an amount, so the
                // finger's next move puts the list back under the thumb.
                currentCoroutineContext().ensureActive()
            }
        }
    }
}

/** What the scrollbar needs from a list or a grid. A grid is counted in lines. */
private interface Scrolling : ScrollbarList {
    val isScrolling: Boolean
}

private class ListScrolling(private val state: LazyListState) : Scrolling {
    override val isScrolling: Boolean get() = state.isScrollInProgress

    override fun layout(): ScrollbarSnapshot {
        val info = state.layoutInfo
        return ScrollbarSnapshot(
            firstIndex = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            rows = info.visibleItemsInfo.map { ScrollbarRow(it.index, it.offset, it.size) },
            total = info.totalItemsCount,
            contentEnd = info.viewportEndOffset - info.afterContentPadding,
            spacing = info.mainAxisItemSpacing,
        )
    }

    override suspend fun scrollTo(row: Int, offsetPx: Int) =
        state.scrollToItem(row.coerceIn(0, max(0, state.layoutInfo.totalItemsCount - 1)), offsetPx)
}

private class GridScrolling(private val state: LazyGridState) : Scrolling {
    override val isScrolling: Boolean get() = state.isScrollInProgress

    override fun layout(): ScrollbarSnapshot {
        val info = state.layoutInfo
        return ScrollbarGrid.snapshot(
            cells = cells(info),
            firstItem = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            totalItems = info.totalItemsCount,
            columns = columns(info),
            contentEnd = info.viewportEndOffset - info.afterContentPadding,
            spacing = info.mainAxisItemSpacing,
        )
    }

    override suspend fun scrollTo(row: Int, offsetPx: Int) {
        val info = state.layoutInfo
        state.scrollToItem(ScrollbarGrid.firstItemOf(row, cells(info), columns(info), info.totalItemsCount), offsetPx)
    }

    private fun cells(info: LazyGridLayoutInfo) = info.visibleItemsInfo.filter { it.row >= 0 }.map {
        ScrollbarCell(it.index, it.row, it.column, it.offset.y, it.size.height)
    }

    private fun columns(info: LazyGridLayoutInfo) = max(1, info.maxSpan)
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.MediaMetadataListItem

/**
 * The playing song kept in sight in a long queue (#81, his pick of 5 Nov 2026, "Held at the edge").
 * Once its row starts to leave the list, a copy of it is held at the edge it is leaving by: at the
 * top under the status bar, or at the bottom over the bar of controls, and the list passes beneath.
 */
internal object QueueHeld {
    enum class Edge { NONE, TOP, BOTTOM }

    /** A row of the list as it is laid out: its place in the list, where it starts and its height, in pixels. */
    data class Laid(val index: Int, val offset: Int, val size: Int)

    /** Rows of room the list needs for one to be held: the held one and two of the list's beside it. */
    const val MIN_ROWS = 3

    /**
     * Where the row of the song playing, the [playing]th of the list, is held, given the rows laid
     * out and the part of the list where rows are in clear sight, from [top] to [bottom] (the top of
     * the bar that floats over the list's foot), in the rows' own offsets.
     *
     * Held as soon as any of the row has gone past an edge, and put right at that edge. That is
     * where the row itself is at that moment, so the copy appears over it and takes what is left of
     * it out of sight, and on the way back the row comes out from under the copy in its place:
     * nothing jumps, half visible or not, and the song never shows twice. With all of the row in
     * sight nothing is held, and a row at rest right against an edge is in sight.
     *
     * Nothing either before the rows are laid out, without a song playing in the list, or in a list
     * too short to give up a row ([MIN_ROWS]).
     */
    fun edge(laid: List<Laid>, playing: Int, top: Int, bottom: Int): Edge {
        if (playing < 0 || laid.isEmpty()) return Edge.NONE
        val row = laid.firstOrNull { it.index == playing }
        if (bottom - top < (row ?: laid.first()).size * MIN_ROWS) return Edge.NONE
        if (row == null) return when {
            playing < laid.first().index -> Edge.TOP
            playing > laid.last().index -> Edge.BOTTOM
            else -> Edge.NONE
        }
        return when {
            row.offset < top -> Edge.TOP
            row.offset + row.size > bottom -> Edge.BOTTOM
            else -> Edge.NONE
        }
    }

    /**
     * Whether the list is one a row is held over: not over search results, a selection, a row being
     * dragged (the copy would sit where the drag scrolls the list), or a queue other than the one
     * playing, which the list shows from the list of queues (detached). Nor before the list has
     * been [placed] at the song playing since the queue opened: it opens at its first song for a
     * frame or two, and the song would show held at the bottom as the sheet comes up.
     */
    fun holds(searching: Boolean, selecting: Boolean, detached: Boolean, dragging: Boolean, placed: Boolean): Boolean =
        !searching && !selecting && !detached && !dragging && placed
}

/** The soft edge on the side of the held row that faces the list, so the list reads as passing beneath it. */
private val HeldShadow = 10.dp

/**
 * The held row: the song's own row (its picture with the playing mark, title, artist and length,
 * its ⋮) as a bar across the whole list in the colour of the playing row's highlight, with a chevron
 * where the drag handle is, pointing the way to the song. A tap puts the list back at the song as
 * the queue opens on it ([onShowPlaying]); the ⋮ opens the row's own menu ([onMenu]). A drag that
 * starts on it moves the list: see [movesListFromHeldRow].
 */
@Composable
internal fun QueueHeldRow(
    song: MediaMetadata,
    edge: QueueHeld.Edge,
    isPlaying: Boolean,
    isNew: Boolean,
    thumbnailSize: Int,
    /** The list's room at its sides, which its rows keep: the bar goes edge to edge, the row inside it. */
    rowPadding: PaddingValues,
    onShowPlaying: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val top = edge == QueueHeld.Edge.TOP
    // Over a black sheet a shadow needs this much to show at all; on a light one it read as a dark line.
    val shadow = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.55f else 0.2f
    Box(
        modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                // Darkest against the bar, gone a shadow's length into the list.
                val h = HeldShadow.toPx()
                val y = if (top) size.height else -h
                val dark = Color.Black.copy(alpha = shadow)
                val mid = Color.Black.copy(alpha = shadow * 0.4f)
                val stops = if (top) arrayOf(0f to dark, 0.45f to mid, 1f to Color.Transparent)
                else arrayOf(0f to Color.Transparent, 0.55f to mid, 1f to dark)
                drawRect(
                    brush = Brush.verticalGradient(*stops, startY = y, endY = y + h),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, h),
                )
            }
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClickLabel = stringResource(R.string.queue_held_row_label), onClick = onShowPlaying)
            .padding(rowPadding)
    ) {
        MediaMetadataListItem(
            mediaMetadata = song,
            // The playing row's own look, in the bar's colour: its rounded highlight melts into the bar.
            isActive = true,
            isPlaying = isPlaying,
            isNew = isNew,
            preferredSize = thumbnailSize,
            trailingContent = {
                IconButton(onClick = onMenu) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.options)
                    )
                }
                // In the drag handle's place and size. The bar's label says where a tap goes.
                Icon(
                    imageVector = if (top) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 16.dp)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A drag that starts on the held row moves the list beneath it, as it would on the row it covers,
 * and lets go into the list's own fling. For the box that holds the list and the row, not the row:
 * the row goes once the song is back in sight, and a drag or fling held by it would stop dead there.
 * The list's rows take their own drags first, so this only ever has the ones that start on the held
 * row. A drag, not a second scrollable on the list: a scrollable would also take part in the scrolls
 * the list hands up to the queue's sheet (rememberQueueSheetScroll), which decide when it closes.
 */
@Composable
internal fun Modifier.movesListFromHeldRow(list: LazyListState): Modifier {
    val fling = ScrollableDefaults.flingBehavior()
    val state = remember(list) { ListDrag(list) }
    return draggable(
        state = state,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> list.scroll { with(fling) { performFling(-velocity) } } },
    )
}

/**
 * The list's own scroll, for a drag: a finger moves the rows with it, the other way to a scroll. The
 * drag holds the list as a finger on it does, so it stops a fling that is still going.
 */
private class ListDrag(private val list: LazyListState) : DraggableState {
    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        list.scroll(dragPriority) {
            val scroll = this
            object : DragScope {
                override fun dragBy(pixels: Float) {
                    scroll.scrollBy(-pixels)
                }
            }.block()
        }
    }

    override fun dispatchRawDelta(delta: Float) {
        list.dispatchRawDelta(-delta)
    }
}

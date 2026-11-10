/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Keeps a swipe that began in a scrolling area of the Simple sheet from closing the sheet, and the
 * editor under it. A Material sheet takes whatever a scroll leaves over: a drag of its own while the
 * finger moves, and, once it lifts, the speed that is left. A quick swipe down in the list scrolled
 * part of the way ran to the list's top and on, with its speed, to the sheet's end (the Pixel 5,
 * 10 Nov 2026). Here a swipe that scrolled the area ends there: only one that begins where the area
 * cannot scroll, at its top, goes on to the sheet, as one on the sheet's handle or header does.
 *
 * Placed above the area's `verticalScroll`, below the sheet's own connection.
 */
internal class ScrollsBeforeSheet : NestedScrollConnection {
    /** The swipe under way has scrolled the area. */
    private var scrolled = false
    private var lastMoveNanos = 0L

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput) {
            // One swipe is a run of moves; a pause of a moment before the next means a new one.
            val now = System.nanoTime()
            if (now - lastMoveNanos > SWIPE_GAP_NANOS) scrolled = false
            lastMoveNanos = now
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (consumed.y != 0f) scrolled = true
        // What an area that scrolled cannot take is not the sheet's to drag.
        return if (scrolled && source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val keep = scrolled
        scrolled = false
        // The speed the area had left when it reached its top is not the sheet's to settle with.
        return if (keep) Velocity(0f, available.y) else Velocity.Zero
    }

    private companion object {
        /** Longer than a pause inside one swipe, shorter than a fling and a new touch. */
        const val SWIPE_GAP_NANOS = 300_000_000L
    }
}

/** An area of the Simple sheet that scrolls: a swipe that begins in it scrolls it and goes no further ([ScrollsBeforeSheet]). */
@Composable
internal fun Modifier.scrollsBeforeSheet(): Modifier {
    val guard = remember { ScrollsBeforeSheet() }
    return nestedScroll(guard)
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateMapOf

/**
 * Where the things the tour talks about actually are on screen.
 *
 * A tour that points at the interface cannot be written as a list of coordinates: the search bar is
 * somewhere else on a tablet, the bottom bar moves when the mini player is up, and any number
 * written down today is wrong on the next device. So the interface reports where its own pieces
 * are, with [tourTarget], and the overlay reads that.
 *
 * A map rather than anything cleverer because there is exactly one tour, it runs in one activity,
 * and entries are cheap: a Rect per decorated element, replaced whenever the layout moves.
 */
object TourTargets {

    private val bounds = mutableStateMapOf<String, Rect>()

    fun put(id: String, rect: Rect) {
        bounds[id] = rect
    }

    fun forget(id: String) {
        bounds.remove(id)
    }

    /** Null when the element is not on screen, which is the tour's cue to move the user first. */
    operator fun get(id: String): Rect? = bounds[id]
}

/**
 * Marks this composable as something the tour can point at.
 *
 * Root coordinates, not window ones, so the hole lands in the same space the overlay draws in.
 * Cleared when the composable leaves, because a stale rectangle is worse than a missing one: the
 * tour would happily cut a hole over empty screen and swear the button was there.
 */
@Composable
fun Modifier.tourTarget(id: String): Modifier {
    DisposableEffect(id) { onDispose { TourTargets.forget(id) } }
    return onGloballyPositioned { TourTargets.put(id, it.boundsInRoot()) }
}

/**
 * One stop on the tour.
 *
 * @param targetId the element to point at, or null for something with no home on screen, which is
 *   shown as a plain card in the middle. The welcome and the sign off are the only two of those;
 *   anything else with no target is a slide, and a slideshow is what this replaced.
 * @param route where the target lives. The tour navigates there before pointing, because half of
 *   explaining a setting is showing which menu it is buried in.
 */
data class TourStop(
    val id: String,
    val targetId: String?,
    val route: String?,
    val title: Int,
    val body: Int,
    val sinceVersionCode: Int,
)

/**
 * Ids for the elements the tour knows about.
 *
 * Constants rather than loose strings because the two ends are written in different files and a
 * typo in either is invisible: the tour simply never finds the target and skips the stop.
 */
object Tour {
    const val SEARCH_BAR = "search_bar"
    const val RECOGNISE = "recognise_button"
    const val SETTINGS = "settings_button"
    const val QUICK_PICKS_CHIPS = "quick_picks_chips"
    const val NAV_LIBRARY = "nav_library"
}

/** The running tour, or nothing. Hoisted here so the overlay and the launcher share one. */
class TourState {
    var stops by mutableStateOf<List<TourStop>>(emptyList())
        private set

    var index by mutableIntStateOf(0)
        private set

    var running by mutableStateOf(false)
        private set

    val current: TourStop? get() = stops.getOrNull(index)

    /**
     * Starts the tour, minus anything that is not on screen to be pointed at.
     *
     * Not a defensive guard: the Quick picks chips only exist while Quick picks is drawing from the
     * engine, so on a good half of installs that stop has no target. Without the filter the overlay
     * falls back to a card in the middle of the screen, and the result is a step describing a
     * control that is not there, with a counter claiming it is one of five.
     */
    fun start(stops: List<TourStop>) {
        val visible = stops.filter { it.targetId == null || TourTargets[it.targetId] != null }
        // An offer to be shown around, with nothing left to show.
        if (visible.none { it.targetId != null }) return
        this.stops = visible
        index = 0
        running = true
    }

    fun next() {
        if (index < stops.lastIndex) index++ else stop()
    }

    fun back() {
        if (index > 0) index--
    }

    fun stop() {
        running = false
        index = 0
        stops = emptyList()
    }
}

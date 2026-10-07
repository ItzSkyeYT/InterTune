/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
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

    /** Everything that is there to be pointed at, in view or scrolled out of it. */
    private val there = mutableStateMapOf<String, Place>()

    private class Place(val bringer: BringIntoViewRequester) {
        var size = IntSize.Zero
    }

    internal fun arrive(id: String, bringer: BringIntoViewRequester) {
        there[id] = Place(bringer)
    }

    internal fun put(id: String, rect: Rect, size: IntSize) {
        there[id]?.size = size
        // Scrolled out of view, what is left of it inside the root is nothing, and a hole cut
        // there would be a hole over something else.
        if (rect.isEmpty) bounds.remove(id) else bounds[id] = rect
    }

    fun forget(id: String) {
        bounds.remove(id)
        there.remove(id)
    }

    /** Null when the element is not on screen, which is the tour's cue to move the user first. */
    operator fun get(id: String): Rect? = bounds[id]

    /** Whether the element exists on the screen that is showing, even if it has to be scrolled to. */
    fun known(id: String): Boolean = id in there

    /**
     * Scrolls the element into view, with [room] pixels to spare above and below it: the bubble
     * needs somewhere to stand, and the bottom of a list sits under the mini player and the bar.
     */
    suspend fun bring(id: String, room: Float) {
        val place = there[id] ?: return
        place.bringer.bringIntoView(Rect(0f, -room, place.size.width.toFloat(), place.size.height + room))
    }
}

/**
 * Marks this composable as something the tour can point at.
 *
 * Root coordinates, not window ones, so the hole lands in the same space the overlay draws in.
 * Cleared when the composable leaves, because a stale rectangle is worse than a missing one: the
 * tour would happily cut a hole over empty screen and swear the button was there.
 *
 * It can also be scrolled to, since the tour now goes into Settings, where what it points at is
 * as often below the fold as above it.
 */
@Composable
fun Modifier.tourTarget(id: String): Modifier {
    val bringer = remember { BringIntoViewRequester() }
    DisposableEffect(id) {
        TourTargets.arrive(id, bringer)
        onDispose { TourTargets.forget(id) }
    }
    return bringIntoViewRequester(bringer).onGloballyPositioned { TourTargets.put(id, it.boundsInRoot(), it.size) }
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
    const val HISTORY = "home_history"

    // Settings: its four groups, the rows the welcome back leads through, and the settings themselves.
    const val SETTINGS_YOU = "settings_group_you"
    const val SETTINGS_LOOK_AND_SOUND = "settings_group_look_and_sound"
    const val SETTINGS_KEPT = "settings_group_kept"
    const val SETTINGS_REST = "settings_group_rest"
    const val ROW_LOOK_AND_FEEL = "settings_row_look_and_feel"
    const val ROW_PLAYER = "settings_row_player"
    const val SETTING_PLAYER_BACKGROUND = "setting_player_background"
    const val SETTING_SHARE_LINKS = "setting_share_links"
    const val SETTING_SPATIAL_AUDIO = "setting_spatial_audio"

    /**
     * The routes those live on. Null on a stop means Home, and Home's own screen at that: not the
     * one the app opens on, which is whichever tab was chosen as the default.
     */
    const val ROUTE_HOME = "home"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_LOOK_AND_FEEL = "settings/appearance"
    const val ROUTE_PLAYER = "settings/player"
}

/**
 * What the welcome back page has asked the tour for, and which cards have been looked at.
 *
 * "Show me" cannot start the tour at once: the screen has to be gone to, and what is pointed at
 * has to arrive on it first. That wait used to live only in a coroutine started by the tap, while
 * the note that a tour was on its way was saved. A rotation in that moment ended the coroutine
 * and kept the note, and what came back had neither a tour nor the page for the rest of the run.
 * So what is kept now is the request itself. Whoever comes back after the rotation finds it still
 * asked for and starts it again.
 *
 * It is also why a card is ticked here and not at the tap: only when the tour is up is there
 * anything that has been seen.
 */
class WelcomeShow {
    private var asked by mutableStateOf(emptyList<String>())
    private var askedFrom by mutableStateOf<String?>(null)

    /** The cards looked at this time round: ids of new things, and [SETTINGS_WALK]. */
    var lookedAt by mutableStateOf(emptyList<String>())
        private set

    /** Whether a tour has been asked for and has not started yet. */
    val waiting: Boolean get() = asked.isNotEmpty()

    /** The stops asked for, saved by id like a running tour's. Empty when nothing is waiting. */
    val stops: List<TourStop> get() = asked.mapNotNull { id -> ALL_TOUR_STOPS.firstOrNull { it.id == id } }

    /** "Show me" on [card], a new thing's id or [SETTINGS_WALK]. */
    fun ask(card: String, stops: List<TourStop>) {
        askedFrom = card
        asked = stops.map { it.id }
    }

    /** The tour that was asked for is up: its card is ticked. */
    fun shown() {
        askedFrom?.let(::looked)
        notShown()
    }

    /** There was nothing to point at: the card stays as it was. */
    fun notShown() {
        asked = emptyList()
        askedFrom = null
    }

    /** A card that is looked at without a tour, as the widget is. */
    fun looked(card: String) {
        if (card !in lookedAt) lookedAt = lookedAt + card
    }

    /** The page opened afresh. */
    fun startOver() {
        notShown()
        lookedAt = emptyList()
    }

    companion object {
        /** The card asked from, how many stops were asked for, those stops, then the cards looked at. */
        val Saver: Saver<WelcomeShow, Any> = listSaver(
            save = { show ->
                if (!show.waiting && show.lookedAt.isEmpty()) emptyList()
                else listOf<Any>(show.askedFrom.orEmpty(), show.asked.size) + show.asked + show.lookedAt
            },
            restore = { saved ->
                WelcomeShow().apply {
                    val count = saved[1] as Int
                    asked = saved.subList(2, 2 + count).map { it as String }
                    askedFrom = (saved[0] as String).takeIf { asked.isNotEmpty() }
                    lookedAt = saved.drop(2 + count).map { it as String }
                }
            }
        )
    }
}

/** The running tour, or nothing. Hoisted here so the overlay and the launcher share one. */
class TourState {
    var stops by mutableStateOf<List<TourStop>>(emptyList())
        private set

    var index by mutableIntStateOf(0)
        private set

    var running by mutableStateOf(false)
        private set

    /**
     * Set when somebody asks for the welcome back page from Settings, and cleared by whoever
     * shows it. Here because this is the one thing the navigation graph and the activity share.
     */
    var welcomeAsked by mutableStateOf(false)

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
        // A stop on another screen is taken on trust: what it points at does not exist until the
        // tour has gone there.
        val visible = stops.filter { it.targetId == null || it.route != null || TourTargets.known(it.targetId) }
        // An offer to be shown around, with nothing left to show.
        if (visible.none { it.targetId != null }) return
        this.stops = visible
        index = 0
        running = true
    }

    fun next() {
        if (index < stops.lastIndex) index++ else stop()
    }

    /**
     * Steps back one stop.
     *
     * @return the stop it stepped back to when that one lives on another screen than the stop
     *   just left, so that whoever owns the screens can go back there too: a tour that has gone
     *   from the list of settings into one of them and is stepped back would otherwise describe
     *   a row that is a screen behind. Null when the screen can stay where it is.
     */
    fun back(): TourStop? {
        if (index == 0) return null
        val left = stops[index]
        index--
        return current?.takeIf { it.route != left.route }
    }

    fun stop() {
        running = false
        index = 0
        stops = emptyList()
    }

    companion object {
        /**
         * Keeps a running tour, and the stop it is on, across the activity being recreated. A
         * rotation recreates it, and a plain remember then lost the tour, which the launcher saw as
         * never started and began again from the first stop. The stops are saved by id and come
         * back from [ALL_TOUR_STOPS].
         */
        val Saver: Saver<TourState, Any> = listSaver(
            save = { state -> if (state.running) listOf<Any>(state.index) + state.stops.map { it.id } else emptyList() },
            restore = { saved ->
                TourState().apply {
                    val stops = saved.drop(1).mapNotNull { id -> ALL_TOUR_STOPS.firstOrNull { it.id == id } }
                    if (stops.isNotEmpty()) {
                        this.stops = stops
                        index = (saved[0] as Int).coerceIn(0, stops.lastIndex)
                        running = true
                    }
                }
            }
        )
    }
}

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

    /** The screens that are drawn, by route, and how many of each: see [screenArrived]. */
    private val screens = mutableStateMapOf<String, Int>()

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

    /**
     * As [put], from the row itself, and only from the row that holds the mark. Stepping back to
     * a screen and forward again, the screen going out is still laid out while the same screen
     * comes in, and its rows slide away under the same ids: the hole must not follow those.
     */
    internal fun put(id: String, bringer: BringIntoViewRequester, rect: Rect, size: IntSize) {
        if (there[id]?.bringer === bringer) put(id, rect, size)
    }

    /**
     * The row marked [id] has gone. Only the row that holds the mark takes it away, for the same
     * reason: the one going out is disposed after the one coming in has arrived, and forgetting
     * by id alone then left the tour with nothing to point at on a screen that was right there.
     */
    internal fun leave(id: String, bringer: BringIntoViewRequester) {
        if (there[id]?.bringer === bringer) forget(id)
    }

    fun forget(id: String) {
        bounds.remove(id)
        there.remove(id)
    }

    /**
     * A screen has been drawn. The tour asks for this before it decides that a setting it meant
     * to point at is not on this install: a row that has not reported yet and a row that is not
     * there look the same until the screen it would be on is known to be up.
     *
     * Counted, since going back and forth the screen that leaves is still drawn while the same
     * one arrives.
     */
    fun screenArrived(route: String) {
        screens[route] = (screens[route] ?: 0) + 1
    }

    fun screenLeft(route: String) {
        val still = (screens[route] ?: 0) - 1
        if (still > 0) screens[route] = still else screens.remove(route)
    }

    /** Whether the screen at [route] is drawn, with everything on it that marks itself. */
    fun drawn(route: String): Boolean = route in screens

    /** Null when the element is not on screen, which is the tour's cue to move the user first. */
    operator fun get(id: String): Rect? = bounds[id]

    /** Whether the element exists on the screen that is showing, even if it has to be scrolled to. */
    fun known(id: String): Boolean = id in there

    /**
     * The row that holds the mark now, as something to tell one arrival of it from the next: a
     * stop that was scrolled to on a screen going out has to be scrolled to again on the same
     * screen coming in.
     */
    fun holder(id: String): Any? = there[id]

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
        onDispose { TourTargets.leave(id, bringer) }
    }
    return bringIntoViewRequester(bringer).onGloballyPositioned { TourTargets.put(id, bringer, it.boundsInRoot(), it.size) }
}

/**
 * One stop on the tour.
 *
 * @param targetId the element to point at, or null for something with no home on screen, which is
 *   shown as a plain card in the middle. The welcome is one, and so is the question the tutorial
 *   ends on, whether to go on into the settings; anything else with no target is a slide, and a
 *   slideshow is what this replaced.
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

    // The settings the closer look stops at (SETTINGS_CLOSER_LOOK), by the screen they are on.
    const val SETTING_LOGIN = "setting_login"
    const val SETTING_LASTFM = "setting_lastfm"
    const val SETTING_YTM_SYNC = "setting_ytm_sync"
    const val SETTING_SYNC_MODE = "setting_sync_mode"
    const val SETTING_QUICK_PICKS_SOURCE = "setting_quick_picks_source"
    const val SETTING_CONTENT_LANGUAGE = "setting_content_language"
    const val SETTING_CONTENT_COUNTRY = "setting_content_country"
    const val SETTING_LOCAL_MEDIA = "setting_local_media"
    const val SETTING_SCAN_PATHS = "setting_scan_paths"
    const val SETTING_SCANNER_SENSITIVITY = "setting_scanner_sensitivity"
    const val SETTING_DARK_THEME = "setting_dark_theme"
    const val SETTING_LIQUID_GLASS = "setting_liquid_glass"
    const val SETTING_TAB_ARRANGEMENT = "setting_tab_arrangement"
    const val SETTING_DEFAULT_TAB = "setting_default_tab"
    const val SETTING_AUTO_LOAD_MORE = "setting_auto_load_more"
    const val SETTING_MEDIA_BUTTONS = "setting_media_buttons"
    const val SETTING_AUDIO_QUALITY = "setting_audio_quality"
    const val SETTING_TRANSITION_FADE = "setting_transition_fade"
    const val SETTING_HEAD_TRACKING = "setting_head_tracking"
    const val SETTING_RECOGNISE_KEEP_LISTENING = "setting_recognise_keep_listening"
    const val SETTING_RECOGNISE_PAUSE = "setting_recognise_pause"
    const val SETTING_RECOGNISE_AUTO_ADD = "setting_recognise_auto_add"
    const val SETTING_RECOGNISE_SECONDS = "setting_recognise_seconds"
    const val SETTING_LYRICS_SOURCES = "setting_lyrics_sources"
    const val SETTING_LYRICS_PREFER_LOCAL = "setting_lyrics_prefer_local"
    const val SETTING_LYRICS_POSITION = "setting_lyrics_position"
    const val SETTING_LYRICS_FONT_SIZE = "setting_lyrics_font_size"
    const val SETTING_LIKED_AUTODOWNLOAD = "setting_liked_autodownload"
    const val SETTING_DOWNLOAD_FOLDER = "setting_download_folder"
    const val SETTING_SONG_CACHE = "setting_song_cache"
    const val SETTING_BACKUP = "setting_backup"
    const val SETTING_RESTORE = "setting_restore"
    const val SETTING_AUTO_BACKUP = "setting_auto_backup"
    const val SETTING_PAUSE_HISTORY = "setting_pause_history"
    const val SETTING_PAUSE_REMOTE_HISTORY = "setting_pause_remote_history"
    const val SETTING_POLLS = "setting_polls"
    const val SETTING_UPDATE_CHECK = "setting_update_check"
    const val SETTING_BACKGROUND_CHECK = "setting_background_check"
    const val SETTING_CHECK_NOW = "setting_check_now"
    const val SETTING_QUICK_PICKS_LEAN = "setting_quick_picks_lean"
    const val SETTING_ADVENTUROUSNESS = "setting_adventurousness"
    const val SETTING_LEARN_FROM_LISTENING = "setting_learn_from_listening"
    const val SETTING_EXCLUSIONS = "setting_exclusions"

    /**
     * The routes those live on. Null on a stop means Home, and Home's own screen at that: not the
     * one the app opens on, which is whichever tab was chosen as the default.
     */
    const val ROUTE_HOME = "home"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_LOOK_AND_FEEL = "settings/appearance"
    const val ROUTE_PLAYER = "settings/player"

    // The rest of what the Settings list leads to (SETTINGS_CATEGORIES).
    const val ROUTE_ACCOUNT = "settings/account_sync"
    const val ROUTE_LIBRARY = "settings/library"
    const val ROUTE_LOCAL = "settings/local"
    const val ROUTE_RECOGNITION = "settings/recognition"
    const val ROUTE_LYRICS = "settings/library/lyrics"
    const val ROUTE_STORAGE = "settings/storage"
    const val ROUTE_BACKUP = "settings/backup"
    const val ROUTE_PRIVACY = "settings/privacy"
    const val ROUTE_UPDATES = "settings/updates"
    const val ROUTE_RECOMMENDATIONS = "settings/recommendations"
    const val ROUTE_ADVANCED = "settings/advanced"
    const val ROUTE_ABOUT = "settings/about"
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

    /**
     * Whether the last step taken was a step back, which [leaveOut] goes on in the direction of.
     * Not kept across a rotation: it matters for the instant a stop is arrived at and no longer.
     */
    private var steppedBack = false

    val current: TourStop? get() = stops.getOrNull(index)

    /**
     * Whether the tour is on a question about what comes after it: a card with nothing to point
     * at that is not the opening one. The tutorial ends on one, CLOSER_LOOK_QUESTION.
     */
    val asking: Boolean get() = running && index > 0 && current?.targetId == null

    /**
     * Whether the tour is on the last stop that points at something. That is its last step, and
     * the button there reads Done, even with a question to follow: the question is about another
     * tour, and somebody who has had enough has had all of this one.
     */
    val onLastStep: Boolean get() = current?.targetId != null && stops.drop(index + 1).none { it.targetId != null }

    /**
     * Where "Next category" leads: the first stop on the screen after the one this stop is on, or
     * null when there is none.
     *
     * Only in a tour that goes from one screen of Settings to another, which is the closer look.
     * The tutorial's step from Home into Settings, and a new thing's from the list into its
     * screen, are the way to one place and not a row of places to pass over.
     */
    val nextScreen: TourStop?
        get() {
            val here = current?.route ?: return null
            if (!here.startsWith(Tour.ROUTE_SETTINGS + "/")) return null
            return stops.drop(index + 1).firstOrNull { it.route != here }
        }

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
        steppedBack = false
        // "Show me" on a question: the tour it offers takes this one's place. Stopped first, so
        // that an offer with nothing left to show ends the tour and is not a button that did
        // nothing.
        val offered = if (asking) current?.let(::tourOfferedBy) else null
        if (offered != null) {
            stop()
            start(offered)
        } else if (index < stops.lastIndex) index++ else stop()
        passOver()
    }

    /** "Next category": on to [nextScreen], past whatever is left of this one. */
    fun skipScreen() {
        val to = nextScreen ?: return
        steppedBack = false
        index = stops.indexOf(to)
        passOver()
    }

    /**
     * Having arrived at a stop: while it is one on a screen that is already drawn, with its
     * setting not on it, it is left out before a bubble is ever put up for it. A stop on a screen
     * the tour has yet to go to cannot be asked this way, and the overlay leaves that one out
     * when the screen arrives without it.
     */
    private fun passOver() {
        while (running) {
            val stop = current ?: return
            val id = stop.targetId ?: return
            val route = stop.route ?: return
            if (!TourTargets.drawn(route) || TourTargets.known(id)) return
            leaveOut()
        }
    }

    /**
     * Leaves out the stop the tour is on, because what it points at turned out not to be on its
     * screen: a setting hidden while signed out, or behind another switch, or behind a flag. A
     * stop on another screen is taken on trust when the tour starts (see [start]), so this is
     * where the same rule is applied to it, once its screen is up to be asked.
     *
     * The tour is then on the stop that came next, or, stepping back, on the one before, and it
     * is over when nothing is left to point at.
     */
    fun leaveOut() {
        val gone = index
        val left = stops.filterIndexed { at, _ -> at != gone }
        val to = if (steppedBack && gone > 0) gone - 1 else gone
        if (to !in left.indices || left.none { it.targetId != null }) return stop()
        stops = left
        index = to
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
        steppedBack = true
        val left = stops[index]
        index--
        passOver()
        return current?.takeIf { it.route != left.route }
    }

    fun stop() {
        running = false
        steppedBack = false
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

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased

/**
 * The build 0.11 ships as.
 *
 * Named rather than repeated, because every step added for this release has to carry the same
 * number and one of them being wrong is invisible until somebody is shown the wrong thing.
 */
private const val V0_11 = 88

/**
 * The tour, as stops that point at real controls.
 *
 * This replaced a slideshow. Showing somebody a picture of a feature and showing them where the
 * button is are different jobs, and only the second one survives them closing the app.
 */
val TOUR_STOPS = listOf(
    TourStop(
        id = "welcome",
        targetId = null,
        route = null,
        title = R.string.walkthrough_title_first,
        body = R.string.walkthrough_description,
        sinceVersionCode = 0,
    ),
    TourStop(
        id = "search",
        targetId = Tour.SEARCH_BAR,
        route = null,
        title = R.string.tour_search_title,
        body = R.string.tour_search_body,
        sinceVersionCode = 0,
    ),
    TourStop(
        id = "quick_picks",
        targetId = Tour.QUICK_PICKS_CHIPS,
        route = null,
        title = R.string.tour_quickpicks_title,
        body = R.string.tour_quickpicks_body,
        sinceVersionCode = V0_11,
    ),
    TourStop(
        id = "recognise",
        targetId = Tour.RECOGNISE,
        route = null,
        title = R.string.walkthrough_recognition_title,
        body = R.string.tour_recognise_body,
        sinceVersionCode = V0_11,
    ),
    TourStop(
        id = "library",
        targetId = Tour.NAV_LIBRARY,
        route = null,
        title = R.string.tour_library_title,
        body = R.string.tour_library_body,
        sinceVersionCode = 0,
    ),
    TourStop(
        id = "settings",
        targetId = Tour.SETTINGS,
        route = null,
        title = R.string.walkthrough_settings_title,
        body = R.string.tour_settings_body,
        sinceVersionCode = 0,
    ),
)

/**
 * The first build 0.11.5 can ship as. Nothing below it has what its stops point at.
 */
private const val V0_11_5 = 92

/**
 * A walk round Settings: its four groups, one bubble each.
 *
 * Settings is thirteen screens and the rows already say what is in each. What they do not give is
 * the shape of the whole, which is what somebody who is lost wants: four groups, and which of them
 * to open for what.
 */
val SETTINGS_TOUR = listOf(
    TourStop("settings_you", Tour.SETTINGS_YOU, Tour.ROUTE_SETTINGS, R.string.tour_settings_you_title, R.string.tour_settings_you_body, 0),
    TourStop("settings_look_and_sound", Tour.SETTINGS_LOOK_AND_SOUND, Tour.ROUTE_SETTINGS, R.string.tour_settings_look_title, R.string.tour_settings_look_body, 0),
    TourStop("settings_kept", Tour.SETTINGS_KEPT, Tour.ROUTE_SETTINGS, R.string.tour_settings_kept_title, R.string.tour_settings_kept_body, 0),
    TourStop("settings_rest", Tour.SETTINGS_REST, Tour.ROUTE_SETTINGS, R.string.tour_settings_rest_title, R.string.tour_settings_rest_body, 0),
)

/** What "Show me" does for a [NewThing]. */
enum class NewThingAction {
    /** Go to where it lives and point at it: [NewThing.stops]. */
    SHOW,

    /** Ask the launcher to put the widget on the home screen. */
    ADD_WIDGET,
}

/**
 * Something that is new, as the welcome back page lists it.
 *
 * @param release the version it came with, as people know it. The page groups by it.
 * @param stops where it lives, as the way there: for a setting, the row to open in Settings and
 *   then the setting itself, because half of explaining a setting is showing which menu it is in.
 *   Each of those bubbles says what the setting does, in a sentence, unless what it points at
 *   already says so on screen: the row in Settings lists other things than this one, and most
 *   settings are a name and a value with no description under them. A bubble that only says
 *   "it is in here" or "choose it here" leaves somebody at a switch they cannot place.
 * @param there false where this build or this install does not have it, and it is then not
 *   listed: a card whose button has nowhere to lead is worse than no card.
 */
class NewThing(
    val id: String,
    val release: String,
    val sinceVersionCode: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val icon: ImageVector,
    val action: NewThingAction = NewThingAction.SHOW,
    val stops: List<TourStop> = emptyList(),
    val there: (Install) -> Boolean = { true },
)

/**
 * How this install is set up, for the things that are only there on some of them.
 *
 * @param quickPicksChips whether Quick picks has its row of chips, which it only has while it
 *   draws from Best recommendations or Try both (QuickPicksSource.hasChips). Somebody on
 *   YouTube's row or their library's has nothing there to be shown.
 */
class Install(val quickPicksChips: Boolean = true)

/** Newest first: what somebody sees at the top is what they have never seen before. */
val NEW_THINGS = listOf(
    NewThing(
        id = "living_blur",
        release = "0.11.5",
        sinceVersionCode = V0_11_5,
        title = R.string.new_living_blur_title,
        body = R.string.new_living_blur_body,
        icon = Icons.Rounded.GraphicEq,
        stops = listOf(
            TourStop("living_blur_row", Tour.ROW_LOOK_AND_FEEL, Tour.ROUTE_SETTINGS, R.string.look_and_feel, R.string.new_living_blur_way, V0_11_5),
            TourStop("living_blur_setting", Tour.SETTING_PLAYER_BACKGROUND, Tour.ROUTE_LOOK_AND_FEEL, R.string.player_background_style, R.string.new_living_blur_here, V0_11_5),
        ),
        there = { Unreleased.LIVING_BACKGROUND },
    ),
    NewThing(
        id = "share_links",
        release = "0.11.5",
        sinceVersionCode = V0_11_5,
        title = R.string.new_share_links_title,
        body = R.string.new_share_links_body,
        icon = Icons.Rounded.Share,
        stops = listOf(
            TourStop("share_links_row", Tour.ROW_LOOK_AND_FEEL, Tour.ROUTE_SETTINGS, R.string.look_and_feel, R.string.new_share_links_way, V0_11_5),
            TourStop("share_links_setting", Tour.SETTING_SHARE_LINKS, Tour.ROUTE_LOOK_AND_FEEL, R.string.share_link_kind_title, R.string.new_share_links_here, V0_11_5),
        ),
        there = { Unreleased.SHARE_PAGE },
    ),
    NewThing(
        id = "quick_picks",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.new_quick_picks_title,
        body = R.string.tour_quickpicks_body,
        icon = Icons.Rounded.Recommend,
        stops = TOUR_STOPS.filter { it.id == "quick_picks" },
        there = { it.quickPicksChips },
    ),
    NewThing(
        id = "recognise",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.walkthrough_recognition_title,
        body = R.string.new_recognise_body,
        icon = Icons.Rounded.Hearing,
        stops = TOUR_STOPS.filter { it.id == "recognise" },
    ),
    NewThing(
        id = "widget",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.new_widget_title,
        body = R.string.new_widget_body,
        icon = Icons.Rounded.Widgets,
        action = NewThingAction.ADD_WIDGET,
    ),
    NewThing(
        id = "history",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.new_history_title,
        body = R.string.new_history_body,
        icon = Icons.Rounded.History,
        stops = listOf(
            TourStop("history_tile", Tour.HISTORY, null, R.string.history, R.string.new_history_here, V0_11),
        ),
    ),
    NewThing(
        id = "spatial_audio",
        release = "0.11",
        sinceVersionCode = V0_11,
        title = R.string.spatial_audio,
        body = R.string.new_spatial_body,
        icon = Icons.Rounded.SurroundSound,
        stops = listOf(
            TourStop("spatial_row", Tour.ROW_PLAYER, Tour.ROUTE_SETTINGS, R.string.player_and_audio, R.string.new_spatial_way, V0_11),
            TourStop("spatial_setting", Tour.SETTING_SPATIAL_AUDIO, Tour.ROUTE_PLAYER, R.string.spatial_audio, R.string.new_spatial_here, V0_11),
        ),
    ),
)

/**
 * What the welcome back page lists for somebody whose last walkthrough was at [seenVersionCode]:
 * everything that came after it and that this build and this [install] have. Nothing for a first
 * install, which gets the tour instead, and with [everything] the lot, for the entry in Settings.
 */
fun newThingsFor(
    seenVersionCode: Int,
    buildVersionCode: Int = BuildConfig.VERSION_CODE,
    everything: Boolean = false,
    install: Install = Install(),
): List<NewThing> {
    val here = NEW_THINGS.filter { it.there(install) }
    if (everything) return here
    if (seenVersionCode <= 0) return emptyList()
    return here.filter { it.sinceVersionCode in (seenVersionCode + 1)..buildVersionCode }
}

/** Every stop there is, for bringing a running tour back after the activity was recreated. */
val ALL_TOUR_STOPS: List<TourStop> get() = (TOUR_STOPS + SETTINGS_TOUR + NEW_THINGS.flatMap { it.stops }).distinctBy { it.id }

/**
 * The same rule as [walkthroughFor], applied to the tour.
 *
 * A first install is walked round the app and then, with [settingsWalk], round Settings. The
 * tour's last stop points at the way in, and ending there left the one place people get lost in
 * as the one place nobody showed them. Somebody who has had the tour gets only the stops that
 * are new since, never the walk: the welcome back page offers it, and they can say no.
 */
fun tourFor(
    seenVersionCode: Int,
    buildVersionCode: Int = BuildConfig.VERSION_CODE,
    settingsWalk: Boolean = Unreleased.WELCOME_BACK,
): List<TourStop> {
    val shipped = TOUR_STOPS.filter { it.sinceVersionCode <= buildVersionCode }
    return if (seenVersionCode <= 0) shipped + (if (settingsWalk) SETTINGS_TOUR else emptyList())
    else shipped.filter { it.sinceVersionCode > seenVersionCode }
}

/** Every stop, for the entry in settings. */
fun tourAll(): List<TourStop> = TOUR_STOPS

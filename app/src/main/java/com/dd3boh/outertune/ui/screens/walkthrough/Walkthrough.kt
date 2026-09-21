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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R

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

/** The same rule as [walkthroughFor], applied to the tour. */
fun tourFor(seenVersionCode: Int): List<TourStop> {
    val shipped = TOUR_STOPS.filter { it.sinceVersionCode <= BuildConfig.VERSION_CODE }
    return if (seenVersionCode <= 0) shipped
    else shipped.filter { it.sinceVersionCode > seenVersionCode }
}

/** Every stop, for the entry in settings. */
fun tourAll(): List<TourStop> = TOUR_STOPS

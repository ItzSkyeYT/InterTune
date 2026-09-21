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
 * One thing worth knowing, and where to go and use it.
 *
 * @param sinceVersionCode the build this arrived in. Somebody updating from an older build is
 *   shown it; somebody who was already here when it landed is not. A step with 0 is part of the
 *   app rather than news, so only a fresh install sees it.
 * @param route where the step is, when there is somewhere to send people. The walkthrough is not
 *   a slideshow about the app, it is a way into it, and a step nobody can act on is a page of
 *   release notes with extra steps.
 */
data class WalkthroughStep(
    val id: String,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val sinceVersionCode: Int,
    val route: String? = null,
    @StringRes val action: Int? = null,
)

/**
 * The build 0.11 ships as.
 *
 * Named rather than repeated, because every step added for this release has to carry the same
 * number and one of them being wrong is invisible until somebody is shown the wrong thing.
 */
private const val V0_11 = 88

/**
 * Everything the walkthrough can show, oldest first.
 *
 * Order is the order they are shown in. New arrivals go at the end rather than at the top: someone
 * updating sees only the new ones anyway, and a fresh install should meet the app before it meets
 * its latest feature.
 */
val WALKTHROUGH_STEPS = listOf(
    WalkthroughStep(
        id = "library",
        icon = Icons.Rounded.LibraryMusic,
        title = R.string.walkthrough_library_title,
        body = R.string.walkthrough_library_body,
        sinceVersionCode = 0,
        route = "settings/local",
        action = R.string.walkthrough_action_open,
    ),
    WalkthroughStep(
        id = "home",
        icon = Icons.Rounded.Home,
        title = R.string.walkthrough_home_title,
        body = R.string.walkthrough_home_body,
        sinceVersionCode = 0,
    ),
    WalkthroughStep(
        id = "backups",
        icon = Icons.Rounded.Backup,
        title = R.string.walkthrough_backup_title,
        body = R.string.walkthrough_backup_body,
        sinceVersionCode = 0,
        route = "settings/storage",
        action = R.string.walkthrough_action_open,
    ),

    // 0.11
    WalkthroughStep(
        id = "recommendations",
        icon = Icons.Rounded.Recommend,
        title = R.string.walkthrough_recommendations_title,
        body = R.string.walkthrough_recommendations_body,
        sinceVersionCode = V0_11,
        route = "settings/recommendations",
        action = R.string.walkthrough_action_open,
    ),
    WalkthroughStep(
        id = "recognition",
        icon = Icons.Rounded.GraphicEq,
        title = R.string.walkthrough_recognition_title,
        body = R.string.walkthrough_recognition_body,
        sinceVersionCode = V0_11,
        route = "recognition",
        action = R.string.walkthrough_action_try,
    ),
    WalkthroughStep(
        id = "spatial",
        icon = Icons.Rounded.Headphones,
        title = R.string.walkthrough_spatial_title,
        body = R.string.walkthrough_spatial_body,
        sinceVersionCode = V0_11,
        route = "settings/player",
        action = R.string.walkthrough_action_open,
    ),
    WalkthroughStep(
        id = "widget",
        icon = Icons.Rounded.Widgets,
        title = R.string.walkthrough_widget_title,
        body = R.string.walkthrough_widget_body,
        sinceVersionCode = V0_11,
    ),
    WalkthroughStep(
        id = "announcements",
        icon = Icons.Rounded.Campaign,
        title = R.string.walkthrough_announcements_title,
        body = R.string.walkthrough_announcements_body,
        sinceVersionCode = V0_11,
        route = "settings/privacy",
        action = R.string.walkthrough_action_open,
    ),
    WalkthroughStep(
        id = "settings",
        icon = Icons.Rounded.Tune,
        title = R.string.walkthrough_settings_title,
        body = R.string.walkthrough_settings_body,
        sinceVersionCode = V0_11,
        route = "settings",
        action = R.string.walkthrough_action_open,
    ),
)

/**
 * What to show somebody who last ran [seenVersionCode].
 *
 * A fresh install has never seen anything and gets the lot. Everyone else gets only what has
 * arrived since, which is the whole point: an update should not walk a returning user through the
 * library scanner they have been using for months.
 *
 * Returns empty when there is nothing new, and the caller shows nothing at all rather than an
 * empty walkthrough congratulating them on being up to date.
 */
fun walkthroughFor(seenVersionCode: Int): List<WalkthroughStep> {
    // Nothing from a build this one is not. Steps are written and marked with the release they
    // will ship in, often before that release exists, and without this they are shown by every
    // build in between and cannot be dismissed: skipping records the running version, which is
    // still lower than theirs, so they come back on the next launch for ever.
    val shipped = WALKTHROUGH_STEPS.filter { it.sinceVersionCode <= BuildConfig.VERSION_CODE }

    // Never walked through anything means a new install, and a new install gets the lot.
    // Filtering on "newer than 0" alone dropped every step marked 0, which is exactly the set
    // written for somebody who has never seen the app.
    return if (seenVersionCode <= 0) shipped
    else shipped.filter { it.sinceVersionCode > seenVersionCode }
}

/**
 * Everything, for the entry in settings, where it is asked for rather than offered.
 *
 * Deliberately not filtered by version. On a released build every step has shipped and the filter
 * would do nothing; on a development build it is the only way to review the steps written for the
 * release being built.
 */
fun walkthroughAll(): List<WalkthroughStep> = WALKTHROUGH_STEPS

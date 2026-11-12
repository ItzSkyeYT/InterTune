/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.utils.LocalLandscape

/** In a screen's saved state: it was opened upright, as a page of its own. */
private const val OPENED_UPRIGHT = "settings_opened_upright"

/** In the list's saved state: what the two panes are to open, the screen from the list first. */
internal const val SETTINGS_PANES_OPEN = "settings_panes_open"

/**
 * What the two panes hold for a screen that was a page of its own, the screen from the list first
 * and the one shown last: [route] alone for a screen of the list, under its entry for one further
 * in; null for anything else.
 */
internal fun settingsChainFor(route: String): List<String>? =
    settingsOpened(emptyList(), route, settingsGroups().flatten().map { it.route }, settingsFurtherIn())

/**
 * Settings follow the phone as it turns. Upright a screen of the list is a page of its own; on its
 * side the list and the screen stand side by side on the list's page (SettingsTwoPane). A screen
 * opened upright and still open as the window became wide stayed a page of its own, stretched across
 * the window, when the list turned the same way showed the two panes (7 Nov 2026). Now it goes back
 * to the list, with that screen open beside it. A screen opened while the window was already wide,
 * from a link or the Mix editor's Settings, stays as it was opened, and so does any screen while the
 * tour runs. Turning back upright is the list's own (SettingsScreen).
 */
@Composable
fun SettingsFollowTheTurn(navController: NavController, tourRunning: Boolean) {
    if (!Unreleased.SETTINGS_TWO_PANE) return
    val current by navController.currentBackStackEntryAsState()
    val entry = current ?: return
    val route = entry.destination.route ?: return
    val wide = LocalLandscape.current.windowWidth >= SettingsTwoPaneMinWidth
    LaunchedEffect(entry.id, wide) {
        val chain = settingsChainFor(route) ?: return@LaunchedEffect
        val handle = entry.savedStateHandle
        if (!wide) {
            handle[OPENED_UPRIGHT] = true
            return@LaunchedEffect
        }
        if (handle.get<Boolean>(OPENED_UPRIGHT) != true || tourRunning) return@LaunchedEffect
        val list = runCatching { navController.getBackStackEntry(Tour.ROUTE_SETTINGS) }.getOrNull() ?: return@LaunchedEffect
        list.savedStateHandle[SETTINGS_PANES_OPEN] = ArrayList(chain)
        navController.popBackStack(Tour.ROUTE_SETTINGS, inclusive = false)
    }
}

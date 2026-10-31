/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/**
 * A page of Settings that only some builds have, at the top of the list: its place among the screens, the card that leads to it
 * from the upright list, its screen, and the rows the search finds on it.
 *
 * The one such page is the Testers page of a build for testers (src/preview, testerlock), reached through
 * TesterLock.testers, which every other build answers with null. This file knows nothing of what the page holds, and carries
 * none of its words: Settings asks, and draws what comes back.
 */
internal interface ExtraSettingsPage {
    /** Where it is, what it is called and its picture, in the list of the two panes and in the search. */
    val category: SettingsCategory

    /** The rows of it that a search can find, held to the screen's rows by a test. */
    val entries: List<SettingEntry>

    /** Its card at the top of the upright list. */
    @Composable
    fun ListCard(navController: NavController)

    /** The screen the entry leads to, drawn as the navigation draws it and in the right pane. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen(navController: NavController, scrollBehavior: TopAppBarScrollBehavior)
}

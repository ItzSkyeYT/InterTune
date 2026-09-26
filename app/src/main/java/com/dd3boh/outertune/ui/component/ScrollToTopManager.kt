/*
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun ScrollToTopManager(navController: NavController, lazyListState : LazyListState) {
    ScrollToTopManager(navController) { lazyListState.animateScrollToItem(0) }
}

/**
 * For a grid. Library's Albums, Artists and Playlists handed over their list's state even when
 * showing the grid, so tapping the Library tab again did not bring the grid back to the top.
 */
@Composable
fun ScrollToTopManager(navController: NavController, lazyGridState: LazyGridState) {
    ScrollToTopManager(navController) { lazyGridState.animateScrollToItem(0) }
}

@Composable
private fun ScrollToTopManager(navController: NavController, scrollToTop: suspend () -> Unit) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val requested = backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsState()
    val currentScrollToTop by rememberUpdatedState(scrollToTop)

    LaunchedEffect(requested?.value) {
        if (requested?.value == true) {
            currentScrollToTop()
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }
}

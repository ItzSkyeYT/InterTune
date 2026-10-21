/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * A screen's own Back (leave a selection, clear a filter, close a search) that steps aside while
 * the player is open over the screen: Back is then the player's. The player's handler is put in
 * place first, and a screen's, put in place later, wins: one Back cleared a Library filter under
 * the open player and left the player open (seen on a Pixel 5, 21 Oct 2026).
 */
@Composable
fun ScreenBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val player = LocalPlayerSheet.current
    val playerOpen = player != null && !player.isCollapsed && !player.isDismissed
    BackHandler(enabled = enabled && !playerOpen, onBack = onBack)
}

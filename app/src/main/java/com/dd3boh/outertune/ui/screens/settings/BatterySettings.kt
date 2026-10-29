/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.screens.settings.fragments.BatteryUseFrag

/**
 * Battery use on a page of its own, in the list under Storage and downloads (his word, 26 Oct
 * 2026). It changes the whole app, the theme, the frames a second, syncs and downloads, not only
 * what plays, so under Player and audio nobody looking for the battery would find it. Only where
 * Unreleased.BATTERY_MODES is on: the list's row and the route ask it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatterySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            BatteryUseFrag()
        }
    }

    FloatingTopBar(title = stringResource(R.string.battery_use), navController = navController)
}

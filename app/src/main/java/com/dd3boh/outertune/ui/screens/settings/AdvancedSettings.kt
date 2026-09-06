/*
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.imageLoader
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DevSettingsKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.DeveloperFrag
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference

/**
 * Maintenance, and the way in to the developer block.
 *
 * This was "Experimental", sat behind a warning triangle, and held two settings that were not
 * experiments at all: forcing the tablet layout, which is now under Look and feel, and the queue
 * limit, which is now under Player and audio. What is left really is advanced, so it says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current

    val (devSettings, onDevSettingsChange) = rememberPreference(DevSettingsKey, defaultValue = false)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
    ) {
        // Only the ordinary rows get the 16dp gutter every other settings screen has. The dev
        // block below stays edge to edge on purpose: its colour swatches are fillMaxWidth
        // backgrounds that are meant to bleed.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            PreferenceGroupTitle(
                title = stringResource(R.string.settings_debug)
            )

            PreferenceEntry(
                title = { Text(stringResource(R.string.flush_image_cache_title)) },
                description = stringResource(R.string.flush_image_cache_description),
                icon = { Icon(Icons.Rounded.Delete, null) },
                onClick = {
                    context.imageLoader.memoryCache?.clear()
                }
            )

            SwitchPreference(
                title = { Text(stringResource(R.string.dev_settings_title)) },
                description = stringResource(R.string.dev_settings_description),
                icon = { Icon(Icons.Rounded.DeveloperMode, null) },
                checked = devSettings,
                onCheckedChange = onDevSettingsChange
            )
        }

        if (devSettings) {
            DeveloperFrag(navController)
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.advanced)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}

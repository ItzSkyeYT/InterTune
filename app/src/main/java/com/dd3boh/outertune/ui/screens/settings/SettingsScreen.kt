/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalUpdateChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain

val SETTINGS_TAG = "Settings"

/**
 * The way in to everything else.
 *
 * Eleven entries in four cards, and every one carries a line saying what is behind it, because a
 * list of bare nouns makes you open three screens to find one switch. The cards group by what you
 * came here to change: your music, how the app presents it, what it keeps on the device, and the
 * app itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    // Sourced from the checker rather than the persisted flag. The flag says "an update existed
    // once"; this says "there is one now, and here it is", which is what the row needs to show.
    val pendingUpdate by LocalUpdateChecker.current.available.collectAsState()
    val updateBadgeLabel = stringResource(R.string.update_available_title)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_account_sync)) },
                description = stringResource(R.string.settings_account_sync_description),
                icon = { Icon(Icons.Rounded.AccountCircle, null) },
                onClick = { navController.navigate("settings/account_sync") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_library_and_content)) },
                description = stringResource(R.string.settings_library_description),
                icon = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null) },
                onClick = { navController.navigate("settings/library") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.local_player_settings_title)) },
                description = stringResource(R.string.settings_local_description),
                icon = { Icon(Icons.Rounded.SdCard, null) },
                onClick = { navController.navigate("settings/local") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.look_and_feel)) },
                description = stringResource(R.string.settings_look_and_feel_description),
                icon = { Icon(Icons.Rounded.Palette, null) },
                onClick = { navController.navigate("settings/appearance") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_and_audio)) },
                description = stringResource(R.string.settings_player_description),
                icon = { Icon(Icons.Rounded.PlayArrow, null) },
                onClick = { navController.navigate("settings/player") }
            )
            // Promoted from a link buried on Library and content. Lyrics are a whole screen of
            // settings that people go looking for by name.
            PreferenceEntry(
                title = { Text(stringResource(R.string.lyrics_settings_title)) },
                description = stringResource(R.string.settings_lyrics_description),
                icon = { Icon(Icons.Rounded.Lyrics, null) },
                onClick = { navController.navigate("settings/library/lyrics") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_storage_and_downloads)) },
                description = stringResource(R.string.settings_storage_description),
                icon = { Icon(Icons.Rounded.Storage, null) },
                onClick = { navController.navigate("settings/storage") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_privacy_and_history)) },
                description = stringResource(R.string.settings_privacy_description),
                icon = { Icon(Icons.Rounded.Shield, null) },
                onClick = { navController.navigate("settings/privacy") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_updates)) },
                // The one place the app tells you an update exists without being asked.
                icon = {
                    BadgedBox(
                        badge = {
                            if (pendingUpdate != null) {
                                Badge(modifier = Modifier.semantics {
                                    contentDescription = updateBadgeLabel
                                })
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Update, null)
                    }
                },
                description = pendingUpdate?.let {
                    stringResource(R.string.update_available, it.versionName)
                } ?: stringResource(R.string.settings_updates_description),
                onClick = { navController.navigate("settings/updates") }
            )
            // Held for 0.11 along with the engine it describes: see Unreleased. The log it would
            // show still runs, because 0.11 needs a history to arrive to; Privacy and history is
            // where it is turned off and cleared, and that screen ships.
            if (Unreleased.ENGINE) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.recommendations)) },
                    icon = { Icon(Icons.Rounded.AutoAwesome, null) },
                    description = stringResource(R.string.settings_recommendations_description),
                    onClick = { navController.navigate("settings/recommendations") }
                )
            }
            PreferenceEntry(
                title = { Text(stringResource(R.string.advanced)) },
                description = stringResource(R.string.settings_advanced_description),
                icon = { Icon(Icons.Rounded.Tune, null) },
                onClick = { navController.navigate("settings/advanced") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.about)) },
                description = stringResource(R.string.settings_about_description),
                icon = { Icon(Icons.Rounded.Info, null) },
                onClick = { navController.navigate("settings/about") }
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
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

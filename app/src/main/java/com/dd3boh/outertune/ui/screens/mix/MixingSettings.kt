/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.automix.MixStores
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * The row in Player and audio's Audio card, under Fade between tracks, that opens [MixingSettings]
 * (DESIGN.md 2.8). It stands where the prototype's one AutoMix switch stood: with Mix, each
 * playlist has its own switch.
 */
@Composable
fun MixingEntry(navController: NavController) {
    PreferenceEntry(
        title = { Text(stringResource(R.string.mixing)) },
        description = stringResource(R.string.mixing_description),
        icon = { Icon(painterResource(R.drawable.ic_mix), null) },
        onClick = { navController.navigate(MixRoutes.SETTINGS) },
    )
}

/**
 * Settings, Player and audio, Mixing: where songs mix, parts (not in this build), and the saved
 * transitions with a way to forget them all. The mixer download and the prepared mixes come with
 * parts, and get their rows here then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixingSettings(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = rememberSavedTransitions()
    var confirming by remember { mutableStateOf(false) }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp),
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.mixing_where))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.mixing_where_line)) },
                icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                onClick = null,
            )
        }
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.mixing_parts))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.mixing_parts_none)) },
                icon = { Icon(Icons.Rounded.Layers, null) },
                onClick = null,
            )
        }
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.mixing_storage))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.mixing_saved)) },
                description = saved.size.toString(),
                icon = { Icon(Icons.Rounded.Bookmarks, null) },
                trailingContent = {
                    TextButton(enabled = saved.isNotEmpty(), onClick = { confirming = true }) {
                        Text(stringResource(R.string.mixing_clear))
                    }
                },
                onClick = null,
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            text = { Text(pluralStringResource(R.plurals.mixing_clear_confirm, saved.size, saved.size)) },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch(Dispatchers.IO + NonCancellable) { MixStores.transitions(context).clear() }
                }) { Text(stringResource(R.string.mixing_clear)) }
            },
        )
    }

    FloatingTopBar(title = stringResource(R.string.mixing), navController = navController)
}

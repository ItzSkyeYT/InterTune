/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPollChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.PollChecker
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Where the occasional question is turned on, off, and inspected.
 *
 * Mirrors the Updates screen, because it is the same kind of thing: an optional feature that talks
 * to the network, which somebody should be able to find, understand and switch off in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PollSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pollChecker = LocalPollChecker.current

    val (enabled, onEnabledChange) = rememberPreference(PollsEnabledKey, defaultValue = false)
    val poll: PollChecker.Poll? by pollChecker.current.collectAsState()
    var checking by remember { mutableStateOf(false) }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.grp_polls))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = { Text(stringResource(R.string.polls_enabled)) },
                description = stringResource(R.string.polls_enabled_description),
                icon = { Icon(Icons.Rounded.Poll, null) },
                checked = enabled,
                onCheckedChange = {
                    onEnabledChange(it)
                    if (it) coroutineScope.launch {
                        // Wait for the preference to actually land before looking. The setter is
                        // fire and forget, so checking straight after it read the old value and
                        // reported "no question right now" to somebody who had just switched it on.
                        context.dataStore.edit { prefs -> prefs[PollsEnabledKey] = true }
                        pollChecker.check(force = true)
                    }
                }
            )
        }

        if (enabled) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                PreferenceEntry(
                    title = {
                        Text(
                            stringResource(
                                if (poll != null) R.string.polls_one_waiting
                                else R.string.polls_none
                            )
                        )
                    },
                    description = poll?.banner,
                    icon = { Icon(Icons.Rounded.Refresh, null) },
                    isEnabled = !checking,
                    onClick = {
                        checking = true
                        coroutineScope.launch {
                            pollChecker.check(force = true)
                            checking = false
                        }
                    }
                )
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.polls_forget)) },
                    description = stringResource(R.string.polls_forget_description),
                    icon = { Icon(Icons.Rounded.HistoryToggleOff, null) },
                    onClick = {
                        coroutineScope.launch {
                            pollChecker.forgetAll()
                            pollChecker.check(force = true)
                        }
                    }
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.grp_polls)) },
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

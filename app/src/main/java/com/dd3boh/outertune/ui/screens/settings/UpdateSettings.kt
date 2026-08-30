/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.LocalUpdateChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import androidx.compose.material3.TopAppBar
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.UpdateChecker
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Everything to do with app updates, in one place.
 *
 * Was originally two rows tucked into About. Updates are not trivia about the app, they are a thing
 * the user acts on, so they get their own section rather than living under the licences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateChecker = LocalUpdateChecker.current

    val (enabled, onEnabledChange) = rememberPreference(UpdateCheckEnabledKey, defaultValue = false)
    val update: UpdateChecker.Update? by updateChecker.available.collectAsState()
    var checking by remember { mutableStateOf(false) }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.grp_updates))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = { Text(stringResource(R.string.update_check)) },
                description = stringResource(R.string.update_check_description),
                icon = { Icon(Icons.Rounded.Update, null) },
                checked = enabled,
                onCheckedChange = {
                    onEnabledChange(it)
                    // Check straight away on opt in, otherwise the switch appears to do nothing
                    // for up to six hours.
                    if (it) coroutineScope.launch { updateChecker.check(force = true) }
                }
            )

            PreferenceEntry(
                title = { Text(stringResource(R.string.check_for_update)) },
                description = if (checking) stringResource(R.string.checking_for_update) else null,
                icon = { Icon(Icons.Rounded.Refresh, null) },
                isEnabled = !checking,
                onClick = {
                    coroutineScope.launch {
                        checking = true
                        val result = updateChecker.check(force = true)
                        checking = false
                        if (result == null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.no_updates_available),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }

        update?.let { found ->
            PreferenceGroupTitle(title = stringResource(R.string.update_available_title))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.update_available, found.versionName)) },
                    // Link to the release page rather than the apk, so the notes can be read before
                    // anything is downloaded. There is deliberately no in-app installer.
                    description = stringResource(R.string.update_available_description),
                    icon = { Icon(Icons.Rounded.Download, null) },
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, found.releaseUrl.toUri()))
                    }
                )

                PreferenceEntry(
                    title = { Text(stringResource(R.string.update_skip)) },
                    onClick = {
                        coroutineScope.launch { updateChecker.dismiss(found.versionCode) }
                    }
                )
            }
        }

        PreferenceGroupTitle(title = stringResource(R.string.app_info_title))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.update_installed_version)) },
                description = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onClick = { }
            )
        }
    }

    // Every settings screen draws its own bar. Without this one there is no way back except the
    // system gesture, which on a tablet in landscape is not obvious at all.
    TopAppBar(
        title = { Text(stringResource(R.string.grp_updates)) },
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

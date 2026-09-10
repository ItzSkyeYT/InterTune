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
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutoInstallUpdatesKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import androidx.compose.material3.TopAppBar
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import androidx.compose.material.icons.rounded.Schedule
import com.dd3boh.outertune.constants.BackgroundCheckHoursKey
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.utils.BackgroundCheckWorker
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.UpdateChecker
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import com.dd3boh.outertune.LocalUpdateInstaller
import com.dd3boh.outertune.utils.InstallSource
import com.dd3boh.outertune.utils.installSource
import com.dd3boh.outertune.utils.fdroidPageUrl
import com.dd3boh.outertune.utils.UpdateInstaller

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
    val fromFdroid = remember { context.installSource() == InstallSource.F_DROID }
    val coroutineScope = rememberCoroutineScope()
    val (backgroundHours, onBackgroundHoursChange) =
        rememberPreference(BackgroundCheckHoursKey, defaultValue = 0)

    // Asked for when background checking is switched on, not at launch. Without it the worker runs,
    // finds the update or the question, and then silently cannot say so, which is indistinguishable
    // from the setting not working. Only Android 13 and up has the runtime permission.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val updateChecker = LocalUpdateChecker.current

    val (enabled, onEnabledChange) = rememberPreference(UpdateCheckEnabledKey, defaultValue = false)
    val update: UpdateChecker.Update? by updateChecker.available.collectAsState()
    var checking by remember { mutableStateOf(false) }

    val (autoInstall, onAutoInstallChange) =
        rememberPreference(AutoInstallUpdatesKey, defaultValue = false)

    val installer = LocalUpdateInstaller.current
    val installState by installer.state.collectAsState()

    // The state lives on a singleton, so a finished or failed run would otherwise still be showing
    // the next time this screen is opened.
    DisposableEffect(Unit) {
        onDispose { installer.acknowledge() }
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.grp_updates))

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            // A copy that came from F-Droid gets updated by F-Droid. Checking GitHub is still
            // allowed, because knowing a release exists before F-Droid has built it is worth
            // something, but the description has to stop claiming this is how it gets installed.
            if (fromFdroid) PreferenceEntry(
                title = { Text(stringResource(R.string.update_fdroid_source)) },
                description = stringResource(R.string.update_fdroid_note),
                icon = { Icon(Icons.Rounded.Update, null) },
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, fdroidPageUrl(context.packageName).toUri())
                    )
                }
            )

            SwitchPreference(
                title = { Text(stringResource(R.string.update_check)) },
                description = stringResource(
                    if (fromFdroid) R.string.update_check_description_fdroid
                    else R.string.update_check_description
                ),
                icon = { Icon(Icons.Rounded.Update, null) },
                checked = enabled,
                onCheckedChange = {
                    onEnabledChange(it)
                    // Check straight away on opt in, otherwise the switch appears to do nothing
                    // for up to six hours.
                    if (it) coroutineScope.launch { updateChecker.check(force = true) }
                }
            )

            // Off by default. It cannot make installing silent, because Android will not allow
            // that, so it is worded as what it actually does: fetch it ahead of time.
            // Hidden entirely on F-Droid rather than merely disabled. Fetching an apk ahead of
            // time is useless there: F-Droid signs its own builds, so Android refuses to install
            // the GitHub one over it, and the download would be ten megabytes spent on a failure.
            if (!fromFdroid) SwitchPreference(
                title = { Text(stringResource(R.string.update_auto)) },
                description = stringResource(R.string.update_auto_description),
                icon = { Icon(Icons.Rounded.Download, null) },
                checked = autoInstall,
                onCheckedChange = onAutoInstallChange,
                isEnabled = enabled,
            )

            // Governs questions too, which is why the copy says so. Two separate schedules for
            // two small requests would wake the device twice to answer one question.
            ListPreference(
                title = { Text(stringResource(R.string.background_check_interval)) },
                icon = { Icon(Icons.Rounded.Schedule, null) },
                selectedValue = backgroundHours,
                values = BackgroundCheckWorker.INTERVAL_CHOICES,
                valueText = {
                    when (it) {
                        0 -> stringResource(R.string.background_check_off)
                        1 -> stringResource(R.string.background_check_hour)
                        24 -> stringResource(R.string.background_check_daily)
                        else -> stringResource(R.string.background_check_hours, it)
                    }
                },
                onValueSelected = {
                    onBackgroundHoursChange(it)
                    // Applied immediately rather than at next launch, and handed the chosen value
                    // rather than left to re-read a preference that has not landed yet.
                    BackgroundCheckWorker.schedule(context, it)
                    if (it > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            InfoLabel(stringResource(R.string.background_check_interval_desc))

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
                // Download and install, matching the progress-row idiom used by the loudness
                // repair and the liked-songs catch up: the description carries the state and a tap
                // stops it while it runs.
                // An F-Droid install is F-Droid's to update. Downloading a differently signed
                // apk over it is not merely redundant, Android refuses it, so this row becomes a
                // way out to the store instead of a way to fail.
                if (fromFdroid) PreferenceEntry(
                    title = { Text(stringResource(R.string.update_prompt_fdroid)) },
                    description = stringResource(R.string.update_fdroid_note),
                    icon = { Icon(Icons.Rounded.Download, null) },
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, fdroidPageUrl(context.packageName).toUri())
                        )
                    }
                ) else PreferenceEntry(
                    title = { Text(stringResource(R.string.update_install)) },
                    description = when (val st = installState) {
                        is UpdateInstaller.State.Downloading ->
                            if (st.total > 0) stringResource(
                                R.string.update_downloading,
                                ((st.downloaded * 100) / st.total).toInt()
                            ) else stringResource(R.string.update_downloading_indeterminate)

                        UpdateInstaller.State.AwaitingConfirmation ->
                            stringResource(R.string.update_awaiting_confirmation)

                        UpdateInstaller.State.NeedsPermission ->
                            stringResource(R.string.update_needs_permission)

                        is UpdateInstaller.State.Failed ->
                            stringResource(R.string.update_install_failed, st.reason)

                        UpdateInstaller.State.Idle ->
                            stringResource(R.string.update_install_description)
                    },
                    icon = { Icon(Icons.Rounded.Download, null) },
                    onClick = {
                        when (installState) {
                            is UpdateInstaller.State.Downloading -> installer.cancel()

                            // Android will not let us ask until this is granted, so send the user
                            // straight to the one screen that grants it.
                            UpdateInstaller.State.NeedsPermission ->
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        "package:${context.packageName}".toUri()
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )

                            else -> installer.downloadAndInstall(found.downloadUrl, found.sizeBytes)
                        }
                    }
                )

                // Kept, because reading what changed before installing is reasonable, and because
                // it is the way out if the install will not work on this device.
                PreferenceEntry(
                    title = { Text(stringResource(R.string.update_open_page)) },
                    description = stringResource(R.string.update_available_description),
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
                onClick = null
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

/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.utils.BuiltInKeys
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.constants.Unreleased

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Links
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.LYRIC_FETCH_TIMEOUT
import com.dd3boh.outertune.constants.MAX_LM_SCANNER_JOBS
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.constants.SNACKBAR_VERY_SHORT
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ContributorCard
import com.dd3boh.outertune.ui.component.ContributorInfo
import com.dd3boh.outertune.ui.component.ContributorType.CUSTOM
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SettingsClickToReveal
import com.dd3boh.outertune.ui.component.button.IconLabelButton
import com.dd3boh.outertune.utils.installedFrom
import com.dd3boh.outertune.utils.scanners.FFmpegScanner
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dd3boh.outertune.ui.component.ProblemReportDialog
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget
import androidx.compose.material.icons.rounded.VolunteerActivism
import com.dd3boh.outertune.ui.dialog.SupportDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    var reportingProblem by rememberSaveable { mutableStateOf(false) }
    var supporting by rememberSaveable { mutableStateOf(false) }
    if (supporting) SupportDialog(onDismiss = { supporting = false })
    if (reportingProblem) ProblemReportDialog(onDismiss = { reportingProblem = false })

    val showDebugInfo = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "userdebug"

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A fork's build is not InterTune, and says so in place of InterTune's name and logo (FORKS.md).
        if (BuiltInKeys.official) {
            Image(
                painter = painterResource(R.drawable.launcher_monochrome),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground, BlendMode.SrcIn),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                    .clickable { }
            )

            Row(
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    // Was hardcoded, so the rebrand's string sweep never reached it.
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
        } else {
            Text(
                text = stringResource(R.string.unofficial_build),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.FLAVOR}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.width(4.dp))

            if (showDebugInfo) {
                Spacer(Modifier.width(4.dp))

                Text(
                    text = BuildConfig.BUILD_TYPE.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp
                        )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        if (BuiltInKeys.official) Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            IconLabelButton(
                text = "GitHub",
                painter = painterResource(R.drawable.github),
                onClick = { uriHandler.openUri("https://github.com/ItzSkyeYT/InterTune") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // InterTune's own wiki. It used to point at upstream's, which sent our users to another
            // project's documentation for an app that has diverged from it.
            IconLabelButton(
                text = stringResource(R.string.wiki),
                icon = Icons.Outlined.Info,
                onClick = { uriHandler.openUri("https://github.com/ItzSkyeYT/InterTune/wiki") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(Modifier.height(96.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                PreferenceEntry(
                    modifier = Modifier.settingRow(R.string.attribution_title),
                    title = { Text(stringResource(R.string.attribution_title)) },
                    icon = { Icon(Icons.Rounded.Groups, null) },
                    onClick = {
                        navController.toSettings("settings/about/attribution")
                    }
                )
                PreferenceEntry(
                    modifier = Modifier.settingRow(R.string.oss_licenses_title),
                    title = { Text(stringResource(R.string.oss_licenses_title)) },
                    icon = { Icon(Icons.Rounded.Description, null) },
                    onClick = {
                        navController.toSettings("settings/about/oss_licenses")
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                // These MUST point at the fork. Upstream's tracker is not the place for bugs in a
                // build upstream did not ship, and it is already busy with reports of the 403 this
                // fork exists to fix.
                // A guided tour is help, not a settings category. It sat at the top level of
                // Settings between Player and audio and Song recognition, where a row that runs
                // something reads like a screen full of switches.
                PreferenceEntry(
                    modifier = Modifier.settingRow(R.string.walkthrough_start),
                    title = { Text(stringResource(R.string.walkthrough_start)) },
                    icon = { Icon(Icons.Rounded.Explore, null) },
                    onClick = {
                        navController.navigate("walkthrough")
                    }
                )
                if (Unreleased.WELCOME_BACK) {
                    PreferenceEntry(
                        modifier = Modifier.settingRow(R.string.welcome_back_entry),
                        title = { Text(stringResource(R.string.welcome_back_entry)) },
                        icon = { Icon(Icons.Rounded.WavingHand, null) },
                        onClick = {
                            navController.navigate("whatsnew")
                        }
                    )
                    PreferenceEntry(
                        modifier = Modifier.settingRow(R.string.tour_closer_entry),
                        title = { Text(stringResource(R.string.tour_closer_entry)) },
                        icon = { Icon(Icons.Rounded.SettingsSuggest, null) },
                        onClick = {
                            navController.navigate("settings_tour")
                        }
                    )
                }
                PreferenceEntry(
                    modifier = Modifier.tourTarget(Tour.SETTING_BUG_REPORT).settingRow(R.string.help_bug_report_action),
                    title = { Text(stringResource(R.string.help_bug_report_action)) },
                    icon = { Icon(Icons.Rounded.BugReport, null) },
                    onClick = {
                        // With the log to hand first, where there is one to show: see ProblemLog.
                        if (Unreleased.PROBLEM_REPORT) reportingProblem = true else uriHandler.openUri(Links.ISSUES)
                    }
                )
                // InterTune's community, not a fork's (FORKS.md).
                if (BuiltInKeys.official) {
                    PreferenceEntry(
                        modifier = Modifier.settingRow(R.string.help_support_forum),
                        title = { Text(stringResource(R.string.help_support_forum)) },
                        icon = { Icon(Icons.AutoMirrored.Rounded.Help, null) },
                        onClick = {
                            uriHandler.openUri(Links.DISCUSSIONS)
                        }
                    )
                    PreferenceEntry(
                        modifier = Modifier.settingRow(R.string.help_discord),
                        title = { Text(stringResource(R.string.help_discord)) },
                        icon = { Icon(Icons.Rounded.Forum, null) },
                        onClick = {
                            uriHandler.openUri(Links.DISCORD)
                        }
                    )
                    PreferenceEntry(
                        modifier = Modifier.settingRow(R.string.help_translate),
                        title = { Text(stringResource(R.string.help_translate)) },
                        icon = { Icon(Icons.Rounded.Translate, null) },
                        onClick = {
                            uriHandler.openUri(Links.TRANSLATE)
                        }
                    )
                }
                // InterTune's support and address, not a fork's (FORKS.md).
                if (BuiltInKeys.official) {
                    // Another way to help.
                    PreferenceEntry(
                        modifier = Modifier.settingRow(R.string.support_title),
                        title = { Text(stringResource(R.string.support_title)) },
                        icon = { Icon(Icons.Rounded.VolunteerActivism, null) },
                        onClick = { supporting = true },
                    )
                    PreferenceEntry(
                        modifier = Modifier.settingRow(R.string.help_contact_email_inquiries),
                        title = { Text(stringResource(R.string.help_contact_email_inquiries)) },
                        description = Links.EMAIL,
                        icon = { Icon(Icons.Rounded.Email, null) },
                        onClick = {
                            val clipData = ClipData.newPlainText(
                                context.getString(R.string.app_name),
                                AnnotatedString(Links.EMAIL)
                            )
                            clipboardManager.nativeClipboard.setPrimaryClip(clipData)
                            // Android 13 and later say it was copied themselves; before, nothing did.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsClickToReveal(stringResource(R.string.app_info_title)) {
                    val info = mutableListOf<String>(
                        "FFMetadataEx: $ENABLE_FFMETADATAEX",
                        "LM scanner concurrency: $MAX_LM_SCANNER_JOBS",
                        "LYRIC_FETCH_TIMEOUT: $LYRIC_FETCH_TIMEOUT",
                        "OOBE_VERSION: $OOBE_VERSION",
                        "SNACKBAR_VERY_SHORT: $SNACKBAR_VERY_SHORT"
                    )
                    if (ENABLE_FFMETADATAEX) {
                        info.add("FFMetadataEx version: ${FFmpegScanner.VERSION_STRING}")
                        info.add("FFmpeg version: ${FfmpegLibrary.getVersion()}")
                        info.add("FFmpeg isAvailable: ${FfmpegLibrary.isAvailable()}")
                    }

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        info.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                SettingsClickToReveal(stringResource(R.string.device_info_title)) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Asked of Android once, as the fold opens.
                        val installedFrom = remember { context.installedFrom() }
                        val info = mutableListOf<String>(
                            "Device: ${Build.BRAND} ${Build.DEVICE} (${Build.MODEL})",
                            "Installed from: $installedFrom",
                            "Manufacturer: ${Build.MANUFACTURER}",
                            "HW: ${Build.BOARD} (${Build.HARDWARE})",
                            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()})",
                            "Android: ${Build.VERSION.SDK_INT} (${Build.ID})",
                            Build.DISPLAY,
                            Build.PRODUCT,
                            Build.FINGERPRINT,
                            Build.VERSION.SECURITY_PATCH
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            info.add("SOC: ${Build.SOC_MODEL} (${Build.SOC_MANUFACTURER})")
                            info.add("SKU: ${Build.SKU} (${Build.ODM_SKU})")
                        }

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            info.forEach {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (ENABLE_FFMETADATAEX) {
                ContributorCard(
                    contributor = ContributorInfo(
                        name = "FFmpeg",
                        description = stringResource(R.string.ffmpeg_lgpl),
                        type = listOf(CUSTOM),
                        // Pinned to the submodule commit this build actually ships, not to main.
                        // This is the LGPL 2.1 source offer for the full flavour, and the offer is
                        // ours to keep even though the module is not: a link to someone else's
                        // branch head stops being an offer the day they move the file, and it
                        // describes whatever they have changed it to rather than what is in this
                        // APK. Bump it with the submodule.
                        url = "https://github.com/OuterTune/ffMetadataEx/blob/" +
                                "5374cd8b5eaf603c09ea8199aaf11311a3e60254/Modules.md"
                    )
                )
            }
        }

    }

    FloatingTopBar(title = stringResource(R.string.about), navController = navController)
}

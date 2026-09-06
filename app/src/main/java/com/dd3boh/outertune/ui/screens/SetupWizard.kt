/*
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.LocalPollChecker
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalUpdateChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_FILTERS
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.constants.AutoInstallUpdatesKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.EnabledFiltersKey
import com.dd3boh.outertune.constants.EnabledTabsKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.LibraryFilterKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.YtmSyncKey
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconLabelButton
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.screens.Screens.LibraryFilter
import com.dd3boh.outertune.ui.screens.settings.fragments.AccountFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.LocalScannerFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.LocalizationFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.ThemeAppFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.ThemePlayerFrag
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.formatFileSize
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.rememberNullablePreference
import com.dd3boh.outertune.utils.scanners.stringFromUriList
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.zionhuang.innertube.utils.parseCookieString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizard(
    navController: NavController,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // "I have a backup" used to navigate to a screen that was nothing but these two rows. That
    // screen is now a group part way down Storage and downloads, so the link landed you on
    // Downloads with a scroll ahead of you. Restoring is one file picker, so just open it.
    val backupRestoreViewModel: BackupRestoreViewModel = hiltViewModel()
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupRestoreViewModel.restore(uri)
    }
    val layoutDirection = LocalLayoutDirection.current
    val uriHandler = LocalUriHandler.current

    var oobeStatus by rememberPreference(OobeStatusKey, defaultValue = 0)

    // Leaving setup writes a preference and then navigates. The setter behind oobeStatus is fire
    // and forget, so doing both in one breath is a race: MainActivity can re-read the old value
    // while the write is still in flight and send you straight back into the wizard, which by then
    // is showing its last page with no navigation bar, because the write has finally landed and
    // the bar's own gate now fails. You are stuck there until the app is restarted.
    //
    // Waiting for the write before navigating removes the window entirely. Same fault, and the
    // same fix, as the polls opt-in.
    val finishSetup: () -> Unit = {
        coroutineScope.launch {
            context.dataStore.edit { it[OobeStatusKey] = OOBE_VERSION }
            navController.navigateUp()
        }
        Unit
    }

    // content prefs
    var filter by rememberEnumPreference(LibraryFilterKey, LibraryFilter.ALL)


    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    // This drove LyricTrimKey, so turning sync off during setup did nothing (isAutoSyncEnabled
    // reads YtmSyncKey, which onboarding never wrote) and quietly toggled lyric trimming instead.
    val (ytmSync, onYtmSyncChange) = rememberPreference(YtmSyncKey, defaultValue = true)

    // local media prefs
    val (localLibEnable, onLocalLibEnableChange) = rememberPreference(LocalLibraryEnableKey, defaultValue = true)
    val (autoScan, onAutoScanChange) = rememberPreference(AutomaticScannerKey, defaultValue = true)
    val (enabledTabs, onEnabledTabsChange) = rememberPreference(EnabledTabsKey, defaultValue = DEFAULT_ENABLED_TABS)
    val (enabledFilters, onEnabledFiltersChange) = rememberPreference(EnabledFiltersKey, defaultValue = DEFAULT_ENABLED_FILTERS)

    LaunchedEffect(localLibEnable) {
        var containsFolders = enabledTabs.contains('F')
        if (localLibEnable && !containsFolders) {
            onEnabledTabsChange(enabledTabs + "F")
        } else if (!localLibEnable && containsFolders) {
            onEnabledTabsChange(enabledTabs.filterNot { it == 'F' })
        }

        containsFolders = enabledFilters.contains('F')
        if (!localLibEnable && containsFolders) {
            onEnabledFiltersChange(enabledFilters.filterNot { it == 'F' })
        }
    }

    BackHandler {
        if (oobeStatus > 0) {
            oobeStatus -= 1
        } else {
            // user may not dismiss via back
        }
    }

    val navBar = @Composable {
        val onFinalStep = oobeStatus == OOBE_VERSION - 1
        val canGoBack = oobeStatus > 0

        // Back, progress, forward: one row, one centre line, the two buttons the same shape at
        // either end of the content column rather than at the screen's edges.
        //
        // Back used to be a bare icon and forward a floating button in the corner, which made the
        // two halves of the same decision look like different kinds of control. They are now the
        // same button mirrored, so the pair reads as one thing.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Present but dead on the welcome page. There is nowhere back to from the first step,
            // and removing it there would leave the row lopsided on exactly the screen that sets
            // the first impression.
            FloatingActionButton(
                onClick = {
                    if (canGoBack) {
                        oobeStatus -= 1
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    }
                },
                containerColor = if (canGoBack) FloatingActionButtonDefaults.containerColor
                else MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation),
                contentColor = if (canGoBack) contentColorFor(FloatingActionButtonDefaults.containerColor)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                elevation = if (canGoBack) FloatingActionButtonDefaults.elevation()
                else FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }

            // The determinate indicator draws whatever value it is handed with no interpolation,
            // so the bar teleported a fifth of its width per tap. 400ms deliberately outlives the
            // 300ms step transition, so the bar is still moving as the new step settles.
            val stepProgress by animateFloatAsState(
                targetValue = oobeStatus.toFloat() / (OOBE_VERSION - 1),
                animationSpec = tween(400, easing = FastOutSlowInEasing),
                label = "oobeProgress"
            )

            LinearProgressIndicator(
                progress = { stepProgress },
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
                    .height(4.dp),
            )

            FloatingActionButton(
                onClick = {
                    if (oobeStatus == 1) {
                        filter = LibraryFilter.ALL // hax
                    }

                    // Never leave oobeStatus at OOBE_VERSION without popping. That value fails the
                    // bar's gate, while AnimatedContent coerces the step and keeps painting the
                    // exit page, so the user would be looking at a page with no control on it.
                    if (!onFinalStep) {
                        oobeStatus += 1
                    } else {
                        finishSetup()
                    }

                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
            ) {
                Icon(
                    imageVector = if (onFinalStep) Icons.Rounded.Check
                    else Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = stringResource(
                        if (onFinalStep) R.string.action_done else R.string.action_next
                    ),
                )
            }
        }
    }

    Scaffold(
        bottomBar = {
            // Through to the exit page, so there is always a way back. Still excludes step 0, where
            // the BackHandler deliberately refuses to go lower and a Back control would be dead.
            if (oobeStatus < OOBE_VERSION) {
                Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                        .fillMaxWidth()
                ) {
                    // Centred as a block, but given the same width the content gets, so the
                    // controls sit under the columns they belong to instead of bunching in the
                    // middle of the screen. SpaceAround around a wrap-content row was doing
                    // nothing except centring one crowded lump.
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 720.dp)
                                .fillMaxWidth()
                        ) {
                            navBar()
                        }
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(
                    PaddingValues(
                        start = paddingValues.calculateStartPadding(layoutDirection),
                        top = 0.dp,
                        end = paddingValues.calculateEndPadding(layoutDirection),
                        bottom = paddingValues.calculateBottomPadding()
                    )
                )
                .fillMaxSize()
        ) {
            // Keyed on the step. One shared ScrollState only clamps to the new step's maximum
            // rather than resetting, so advancing from a scrolled page landed you part way down a
            // page you had never seen. The saver keeps the position across a rotation.
            val stepScrollState = rememberSaveable(oobeStatus, saver = ScrollState.Saver) {
                ScrollState(0)
            }

            Column(
                modifier = Modifier
                    // Width, not size. fillMaxSize inside a vertical scroll makes the content at
                    // least a viewport tall, and the inset spacer above then pushes it over, so
                    // every page could be dragged a little even when everything already fitted.
                    // Wrapping the height means it only scrolls when there is genuinely more.
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(stepScrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 8.dp))

                // Each step used to hard cut. Entering the wizard from MainActivity slides and
                // fades, so the wizard felt stiller than the app that launched it.
                //
                // Fade through, not cross dissolve: the incoming fade waits for the outgoing one
                // to finish, otherwise both headlines are legible at once and it reads muddy.
                // Fixed 48dp of travel rather than a fraction of the width: a fraction gives a
                // tablet a long heavy sweep and a phone a short one for the same duration.
                // The inner Column is required, because AnimatedContent stacks its children at
                // (0,0) and most branches emit a run of siblings that rely on the outer
                // horizontalAlignment. coerceIn guards the terminal state: finishing sets
                // oobeStatus to OOBE_VERSION, which has no branch.
                // The slide lambdas get no Density receiver, so resolve the travel here.
                val enterTravelPx = with(LocalDensity.current) { 48.dp.roundToPx() }
                val exitTravelPx = with(LocalDensity.current) { 24.dp.roundToPx() }

                AnimatedContent(
                    targetState = oobeStatus.coerceIn(0, OOBE_VERSION - 1),
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideIntoContainer(
                            towards = if (forward) AnimatedContentTransitionScope.SlideDirection.Start
                            else AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(300, easing = LinearOutSlowInEasing)
                        ) { enterTravelPx } + fadeIn(tween(220, delayMillis = 110, easing = LinearOutSlowInEasing)))
                            .togetherWith(
                                slideOutOfContainer(
                                    towards = if (forward) AnimatedContentTransitionScope.SlideDirection.Start
                                    else AnimatedContentTransitionScope.SlideDirection.End,
                                    animationSpec = tween(200, easing = FastOutLinearInEasing)
                                ) { exitTravelPx } + fadeOut(tween(110, easing = FastOutLinearInEasing))
                            )
                            // Snapped, and unclipped: the scroll container's maxValue would
                            // otherwise swing every frame and tug the viewport.
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    label = "oobeStep"
                ) { step ->
                    Column(
                        modifier = Modifier.widthIn(max = 720.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (step) {
                            0 -> { // landing page
                                Image(
                                    painter = painterResource(R.drawable.launcher_monochrome),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary, BlendMode.SrcIn),
                                    modifier = Modifier
                                        // Sized explicitly. Without this it drew at the drawable's
                                        // intrinsic 108dp, so the one screen that sets the first
                                        // impression was the one whose hero size was an accident.
                                        .size(112.dp)
                                        .clip(CircleShape)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceColorAtElevation(
                                                NavigationBarDefaults.Elevation
                                            )
                                        )
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        }
                                        .padding(16.dp)
                                )

                                Text(
                                    text = stringResource(R.string.oobe_welcome_message),
                                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                    textAlign = TextAlign.Center
                                )

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 28.dp, end = 16.dp, bottom = 16.dp)
                                ) {
                                    OobeFeatureRow(
                                        title = stringResource(R.string.oobe_ytm_integration),
                                        description = stringResource(R.string.oobe_ytm_integration_description),
                                        icon = Icons.Rounded.MusicNote,
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                    OobeFeatureRow(
                                        title = stringResource(R.string.oobe_ad_free_exp),
                                        description = stringResource(R.string.oobe_ad_free_exp_description),
                                        icon = Icons.Rounded.Block,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    OobeFeatureRow(
                                        title = stringResource(R.string.oobe_cross_platform_sync),
                                        description = stringResource(R.string.oobe_cross_platform_sync_description),
                                        icon = Icons.Rounded.Sync,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                    OobeFeatureRow(
                                        title = stringResource(R.string.oobe_local_music_support),
                                        description = stringResource(R.string.oobe_local_music_support_description),
                                        icon = Icons.Rounded.SdCard,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Spacer(Modifier.height(24.dp))
                                InfoLabel(
                                    text = stringResource(R.string.oobe_welcome_tip),
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                )
                                Spacer(Modifier.height(24.dp))

                                // Both secondary actions on the left, together, with the right
                                // side left to the one primary action.
                                //
                                // SpaceBetween put Skip hard right, which landed it against the
                                // continue button: leaving setup and carrying on with it, touching,
                                // at the same visual weight. The end padding reserves the floating
                                // button's footprint so the row can never run under it.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, end = 88.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(
                                        onClick = {
                                            restoreLauncher.launch(arrayOf("application/octet-stream"))
                                        }
                                    ) {
                                        Text(
                                            text = stringResource(R.string.oobe_use_backup),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            finishSetup()
                                        }
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_skip),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }

                            // appearance
                            1 -> OobeStep(
                                icon = Icons.Rounded.DarkMode,
                                title = stringResource(R.string.look_and_feel),
                                subtitle = stringResource(R.string.oobe_interface_subtitle),
                            ) {


                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    ThemeAppFrag()

                                    // Player look, including liquid glass. Setup is where someone decides
                                    // how the app should look, and the now playing screen is the screen
                                    // they will spend the most time staring at.
                                    ThemePlayerFrag()
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    LocalizationFrag()
                                }
                            }

                            // account
                            2 -> OobeStep(
                                icon = Icons.Rounded.AccountCircle,
                                title = stringResource(R.string.oobe_ytm_logon_title),
                                subtitle = stringResource(R.string.oobe_ytm_logon_subtitle),
                            ) {


                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AccountFrag(navController)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SwitchPreference(
                                        title = { Text(stringResource(R.string.ytm_sync)) },
                                        icon = { Icon(Icons.Rounded.Sync, null) },
                                        checked = ytmSync,
                                        onCheckedChange = onYtmSyncChange,
                                        isEnabled = isLoggedIn
                                    )
                                }
                            }

                            // local media
                            3 -> OobeStep(
                                icon = Icons.Rounded.LibraryMusic,
                                title = stringResource(R.string.oobe_local_media_title),
                                subtitle = stringResource(R.string.oobe_local_media_subtitle),
                            ) {

                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SwitchPreference(
                                        title = { Text(stringResource(R.string.local_library_enable_title)) },
                                        description = stringResource(R.string.local_library_enable_description),
                                        icon = { Icon(Icons.Rounded.SdCard, null) },
                                        checked = localLibEnable,
                                        onCheckedChange = onLocalLibEnableChange
                                    )
                                }

                                AnimatedVisibility(localLibEnable) {
                                    Column {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        ElevatedCard(
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            SwitchPreference(
                                                title = { Text(stringResource(R.string.auto_scanner_title)) },
                                                description = stringResource(R.string.auto_scanner_description),
                                                icon = { Icon(Icons.Rounded.Autorenew, null) },
                                                checked = autoScan,
                                                onCheckedChange = onAutoScanChange
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        ElevatedCard(
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            PreferenceGroupTitle(
                                                title = stringResource(R.string.grp_manual_scanner)
                                            )


                                            LocalScannerFrag()
                                        }
                                    }

                                }
                            }

                            // downloads
                            4 -> {
                                val downloadUtil = LocalDownloadUtil.current
                                val (downloadPath, onDownloadPathChange) = rememberPreference(DownloadPathKey, "")
                                val (maxSongCacheSize, onMaxSongCacheSizeChange) = rememberPreference(
                                    key = MaxSongCacheSizeKey,
                                    defaultValue = 0
                                )
                                val (scanPaths, onScanPathsChange) = rememberPreference(ScanPathsKey, defaultValue = "")

                                var showDlPathDialog: Boolean by remember {
                                    mutableStateOf(false)
                                }


                                OobeStep(
                                    icon = Icons.Rounded.Download,
                                    title = stringResource(R.string.oobe_downloads_title),
                                    subtitle = stringResource(R.string.oobe_downloads_subtitle),
                                ) {

                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    PreferenceEntry(
                                        title = { Text(stringResource(R.string.dl_main_path_title)) },
                                        onClick = {
                                            showDlPathDialog = true
                                        },
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                InfoLabel(stringResource(R.string.dl_oobe_tooltip))

                                // Was a second 80dp hero with its own headline, which made one page
                                // look like two and pushed the cache picker off the bottom. Downloads
                                // and the cache are the same subject, so the cache is a group inside
                                // the page rather than a page of its own.
                                Spacer(Modifier.height(24.dp))
                                PreferenceGroupTitle(title = stringResource(R.string.song_cache))
                                InfoLabel(stringResource(R.string.oobe_cache_subtitle))
                                Spacer(Modifier.height(8.dp))

                                ElevatedCard(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    ListPreference(
                                        title = { Text(stringResource(R.string.song_cache_max_size)) },
                                        selectedValue = maxSongCacheSize,
                                        values = listOf(0, 128, 256, 512, 1024, 2048, 4096, 8192, -1),
                                        valueText = {
                                            when (it) {
                                                0 -> stringResource(androidx.compose.ui.R.string.state_off)
                                                -1 -> stringResource(R.string.unlimited)
                                                else -> formatFileSize(it * 1024 * 1024L)
                                            }
                                        },
                                        onValueSelected = onMaxSongCacheSizeChange
                                    )
                                    InfoLabel(stringResource(R.string.restart_to_apply_changes))
                                    Spacer(Modifier.height(12.dp))
                                }

                                if (showDlPathDialog) {
                                    var tempFilePath by remember {
                                        mutableStateOf<Uri?>(null)
                                    }
                                    LaunchedEffect(downloadPath) {
                                        tempFilePath = uriListFromString(downloadPath).firstOrNull()
                                    }

                                    ActionPromptDialog(
                                        titleBar = {
                                            Text(
                                                text = stringResource(R.string.dl_main_path_title),
                                                style = MaterialTheme.typography.titleLarge,
                                            )
                                        },
                                        onDismiss = {
                                            showDlPathDialog = false
                                            tempFilePath = null
                                        },
                                        onConfirm = {
                                            tempFilePath?.let { f ->
                                                val uris = stringFromUriList(listOfNotNull(f))
                                                onDownloadPathChange(uris)
                                            }

                                            showDlPathDialog = false
                                            tempFilePath = null

                                            coroutineScope.launch(dlCoroutine) {
                                                delay(1000)
                                                downloadUtil.cd()
                                                downloadUtil.scanDownloads()
                                            }
                                        },
                                        onReset = {
                                            tempFilePath = null
                                        },
                                        onCancel = {
                                            showDlPathDialog = false
                                            tempFilePath = null
                                        },
                                        isInputValid = uriListFromString(scanPaths).none {
                                            // download path cannot a scan path, or a subdir of a scan path
                                            tempFilePath.toString().length <= it.toString().length && tempFilePath.toString()
                                                .contains(it.toString())
                                        }
                                    ) {

                                        val dirPickerLauncher = rememberLauncherForActivityResult(
                                            ActivityResultContracts.OpenDocumentTree()
                                        ) { uri ->
                                            if (tempFilePath.toString() == uri.toString()) return@rememberLauncherForActivityResult
                                            if (uri?.path != null) {
                                                // Take persistable URI permission
                                                val contentResolver = context.contentResolver
                                                val takeFlags: Int =
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                contentResolver.takePersistableUriPermission(uri, takeFlags)

                                                tempFilePath = uri
                                            }
                                        }

                                        val valid = uriListFromString(scanPaths).none {
                                            // download path cannot a scan path, or a subdir of a scan path
                                            tempFilePath.toString().length <= it.toString().length && tempFilePath.toString()
                                                .contains(it.toString())
                                        }

                                        Text(
                                            text = stringResource(R.string.dl_main_path_description),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        Spacer(Modifier.padding(vertical = 8.dp))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                                .border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                                    RoundedCornerShape(ThumbnailCornerRadius)
                                                )
                                                .background(if (valid) Color.Transparent else MaterialTheme.colorScheme.errorContainer)
                                        ) {
                                            tempFilePath?.let {
                                                Text(
                                                    text = it.toString(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        }

                                        // add folder button
                                        Column {
                                            Button(onClick = { dirPickerLauncher.launch(null) }) {
                                                Text(stringResource(R.string.scan_paths_add_folder))
                                            }

                                            InfoLabel(
                                                text = stringResource(R.string.scan_paths_tooltip),
                                                modifier = Modifier.padding(vertical = 16.dp)
                                            )

                                            if (!valid) {
                                                InfoLabel(
                                                    text = stringResource(R.string.scanner_rejected_dir),
                                                    isError = true,
                                                    modifier = Modifier.padding(top = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                }
                            }

                            // exit page
                            5 -> {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .padding(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(R.string.oobe_complete_title),
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.oobe_complete),
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                    )
                                    UpdateOptInCard()

                                    PollsOptInCard()

                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                    ) {
                                        IconLabelButton(
                                            text = "GitHub",
                                            icon = Icons.Rounded.Code,
                                            onClick = { uriHandler.openUri("https://github.com/ItzSkyeYT/InterTune") },
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )

                                        IconLabelButton(
                                            text = "Wiki",
                                            icon = Icons.Outlined.Info,
                                            onClick = { uriHandler.openUri("https://github.com/ItzSkyeYT/InterTune/wiki") },
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                    Text(
                                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.FLAVOR}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}


/**
 * The hero for one setup step: a badged icon, a headline and a line of explanation.
 *
 * Extracted because steps 1 to 4 each hand-rolled the identical block, five copies in all, with the
 * same magic numbers pasted each time. Restyling meant editing five places and hoping.
 *
 * The badge is the same construction the poll banner uses, at a larger size. A bare tinted glyph on
 * a dark page reads as an icon in a settings list; the same glyph in a filled circle reads as the
 * subject of the page, which is what a setup step wants.
 */
@Composable
private fun OobeHero(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(34.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One setup step: its hero, and whatever it is asking for.
 *
 * Two panes when there is room, one when there is not. A tablet in landscape was showing a 720dp
 * ribbon with roughly 280dp of dead margin down each side, on every step, which made setup look
 * like a phone screen someone had forgotten to finish. Side by side, the hero explains the page
 * while the controls sit next to it, and the crammed steps stop needing to scroll at all.
 *
 * [content] is a ColumnScope lambda because every step's body is a stack of cards, and because the
 * settings fragments it calls are themselves ColumnScope extensions.
 */
@Composable
private fun OobeStep(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OobeHero(icon, title, subtitle)
        Spacer(Modifier.height(28.dp))
        content()
    }
}

@Composable
private fun OobeFeatureRow(
    title: String,
    description: String?,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // No clickable. These four cards took a tap, buzzed, and did nothing, which is worse than not
    // responding at all: it teaches somebody on their first screen that things here do not work.
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // The tint was being passed in and thrown away: every icon drew in primary, so four
            // cards meant to look distinct looked identical. Badged, so the colour actually reads.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Asks, once, whether to check for updates, and afterwards shows the answer.
 *
 * While the question is open it is two buttons rather than a switch. A switch has a default, and a
 * default is an answer nobody gave: the preference stays unset and there is no way to tell "left it
 * alone" from "said no". Both buttons write the preference, so afterwards it is set either way and
 * nothing asks again. That distinction is what lets the app ask a second time after restoring a
 * backup from a version that predates this setting, without pestering anyone who already declined.
 *
 * Once answered the card does not disappear, it becomes the setting. Disappearing was the bug: this
 * page is what "Enter configurator" replays, so on any install that had already answered, which is
 * every install more than five minutes old, the final page silently dropped the one thing it
 * offered and read as broken. Showing the current value is what the rest of the wizard already
 * does, since steps 1 to 4 embed the real settings fragments rather than onboarding copies.
 */
@Composable
fun UpdateOptInCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateChecker = LocalUpdateChecker.current

    // Nullable on purpose. null is "never asked", which is not "said no".
    val choice by rememberNullablePreference(UpdateCheckEnabledKey)
    val (autoInstall, onAutoInstallChange) = rememberPreference(AutoInstallUpdatesKey, defaultValue = false)

    // Opting in checks straight away, otherwise the answer appears to do nothing for hours.
    // Same reason as the switch in Settings > Updates.
    fun answer(enabled: Boolean) {
        coroutineScope.launch {
            context.dataStore.edit { it[UpdateCheckEnabledKey] = enabled }
            if (enabled) updateChecker.check(force = true)
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        val answered = choice
        if (answered == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.oobe_update_check_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.oobe_update_check_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    TextButton(onClick = { answer(false) }) {
                        Text(stringResource(R.string.oobe_update_check_no))
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(onClick = { answer(true) }) {
                        Text(stringResource(R.string.oobe_update_check_yes))
                    }
                }
            }
        } else {
            // Deliberately the same component and title string as the row in Settings > Updates, so
            // it reads as "this is that setting" rather than a copy of it.
            SwitchPreference(
                title = { Text(stringResource(R.string.update_check)) },
                description = stringResource(R.string.oobe_update_check_answered),
                icon = { Icon(Icons.Rounded.Update, null) },
                checked = answered,
                onCheckedChange = { answer(it) }
            )

            // A dependent row rather than a card of its own, the same shape Settings > Updates
            // uses. Offering to download updates automatically to somebody who has just declined
            // update checking is incoherent, so it only exists once they have said yes.
            AnimatedVisibility(visible = answered) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.update_auto)) },
                    description = stringResource(R.string.oobe_update_auto_description),
                    icon = { Icon(Icons.Rounded.Download, null) },
                    checked = autoInstall,
                    onCheckedChange = onAutoInstallChange,
                )
            }
        }
    }
}

/**
 * The other question worth asking during setup.
 *
 * A sibling of [UpdateOptInCard] rather than a variation of it: same nullable preference trick, so
 * "never asked" stays distinguishable from "said no", and the same two shapes. Someone who skips
 * the wizard has not answered, and can still be asked later.
 */
@Composable
fun PollsOptInCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pollChecker = LocalPollChecker.current

    val choice by rememberNullablePreference(PollsEnabledKey)

    fun answer(enabled: Boolean) {
        coroutineScope.launch {
            // Write first, then look. The setter is fire and forget, so checking immediately after
            // it reads the old value and reports that there is nothing to ask.
            context.dataStore.edit { it[PollsEnabledKey] = enabled }
            if (enabled) pollChecker.check(force = true)
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        val answered = choice
        if (answered == null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.polls_opt_in_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.polls_opt_in_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    TextButton(onClick = { answer(false) }) {
                        Text(stringResource(R.string.polls_opt_in_no))
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(onClick = { answer(true) }) {
                        Text(stringResource(R.string.polls_opt_in_yes))
                    }
                }
            }
        } else {
            SwitchPreference(
                title = { Text(stringResource(R.string.polls_enabled)) },
                description = stringResource(R.string.oobe_polls_answered),
                icon = { Icon(Icons.Rounded.Poll, null) },
                checked = answered,
                onCheckedChange = { answer(it) }
            )
        }
    }
}

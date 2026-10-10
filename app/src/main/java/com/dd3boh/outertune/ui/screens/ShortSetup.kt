/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.dd3boh.outertune.LocalPollChecker
import com.dd3boh.outertune.LocalUpdateChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.applyNewInstallDefaults
import com.dd3boh.outertune.constants.markFirstSetup
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.InstallSource
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.installSource
import com.dd3boh.outertune.utils.lastFmQuestionAskable
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.BackupRestoreViewModel
import com.zionhuang.innertube.utils.parseCookieString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// The pages, numbered as OobeStatusKey stores them while setup is open.
private const val PAGE_WELCOME = 0
private const val PAGE_SIGN_IN = 1
private const val PAGE_CHOICES = 2

// How long a page has been up before its buttons do anything. See where it is read.
private const val SETTLE_MS = 600L

/**
 * First-run setup in three pages (Unreleased.SHORT_SETUP).
 *
 * The wizard it stands in for (SetupWizard) has six: a list of selling points, look and feel, the
 * account, local media, the downloads folder, and an exit page with five questions on five
 * cards. Its first page did not fit a Pixel 5, with the ways on under the fold and nothing to say
 * so. Most of what it asks has a default that nobody needs to be asked about in their first
 * minute, and all of it is in Settings.
 *
 * This asks for what has no default. The first page says what the app is and offers the two ways
 * on. The second is signing in, or not. The third is the five questions as five switches, and
 * Done (SetupChoices). Look and feel, local media and the downloads folder are left at what the
 * old pages showed, which is what anybody tapping Next through them got.
 *
 * Under every page is the row the wizard has under its own: back, a line that says how far
 * along, and on, which is a tick on the last page (SetupNavRow). It is outside the pages, so it
 * stays where it is while they change, and none of them can be without it. The pages keep only
 * what is not going on: restoring a backup, and signing in.
 *
 * A page's button is never under the fold either: it stands below what scrolls, and beside it in
 * a window too short to stack the two, which is a phone on its side.
 *
 * What is stored is what the wizard stores: the page in OobeStatusKey while setup is open,
 * OOBE_VERSION once it is done, and the mark of a first setup with what follows from it
 * (markFirstSetup, applyNewInstallDefaults). MainActivity reads only that, so it cannot tell the
 * two apart, and the tutorial starts after this one as it does after the other.
 */
@Composable
fun ShortSetup(
    navController: NavController,
    /** Outlives this screen, for the checks that a yes starts as setup closes. */
    appScope: CoroutineScope,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val updateChecker = LocalUpdateChecker.current
    val pollChecker = LocalPollChecker.current

    // Restoring is one file picker, opened from here as the wizard opens it. The restore runs on
    // after the picker closes and restarts the app when it is done, so the buttons wait for it.
    val backupRestoreViewModel: BackupRestoreViewModel = hiltViewModel()
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupRestoreViewModel.restore(uri)
    }
    val restoring by backupRestoreViewModel.restoreInProgress.collectAsState()

    var storedPage by rememberPreference(OobeStatusKey, defaultValue = 0)
    // Setup opened by an update from a wizard with more pages starts on a number past the last
    // of these: it opens on the questions, as it opened on the old exit page.
    val page = storedPage.coerceIn(PAGE_WELCOME, PAGE_CHOICES)

    // Whether this is a new install is settled now, not as setup finishes. See markFirstSetup.
    LaunchedEffect(Unit) {
        context.dataStore.edit { markFirstSetup(it) }
    }

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val signedIn = remember(innerTubeCookie) { "SAPISID" in parseCookieString(innerTubeCookie) }
    val accountName by rememberPreference(AccountNameKey, "")
    // One PackageManager call for the page, as on the card this line comes from.
    val fromFdroid = remember { context.installSource() == InstallSource.F_DROID }

    // The switches. Read once and waited for, as rememberPreference reads: they have to stand
    // where they will stay from the page's first frame, or Done could be tapped over a switch
    // that then moves. Only Done writes them.
    var choices by rememberSaveable(stateSaver = ChoicesSaver) {
        val stored = runBlocking(Dispatchers.IO) { context.dataStore.data.first() }
        mutableStateOf(SetupChoices.from(stored, lastFmQuestionAskable()))
    }

    // Once only, and the writes are waited for before leaving, for the reasons given at
    // SetupWizard's finishSetup: MainActivity would otherwise read the old page number and open
    // setup again, and a second tap would run all of this over a screen that has gone.
    var finishing by remember { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!finishing) {
            finishing = true
            val answers = choices
            coroutineScope.launch {
                // The answers first, in an edit of their own, and only then the mark that setup
                // is done. MainActivity opens the catch-up screen when setup is done and an
                // answer is missing, and it reads each of them through a collector of its own.
                // Written in one edit they could reach it a frame apart, setup done before the
                // answers, and it would ask the five questions that had just been answered.
                context.dataStore.edit { answers.store(it) }
                context.dataStore.edit {
                    applyNewInstallDefaults(it)
                    it[OobeStatusKey] = OOBE_VERSION
                }
                // What a yes on the old cards starts: a look for a release now and not hours
                // on, and the first question or announcement fetched.
                if (answers.updates) appScope.launch { updateChecker.check(force = true) }
                appScope.launch { pollChecker.check(force = answers.questions || answers.news) }

                // Back to what setup was opened over, as the wizard goes.
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack("setup_wizard", inclusive = true)
                } else {
                    navController.navigate(
                        navController.graph.startDestinationId, null,
                        navOptions { popUpTo("setup_wizard") { inclusive = true } },
                    )
                }
            }
        }
    }

    // Nothing goes on for a page's first moment. The button that goes on stands in one place
    // under every page, and the second tap of a double tap, a quarter of a second after the
    // first, landed on a page nobody had seen yet: twice on was past signing in and then Done,
    // and five answers were given unread.
    var settledPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(page) {
        delay(SETTLE_MS)
        settledPage = page
    }
    val settled = settledPage == page

    // Back is a page back, and nothing on the first page: setup is not left by Back.
    val canGoBack = page > PAGE_WELCOME && !finishing
    // Not on from the first page while a backup is being restored: the app restarts when it is.
    val canGoOn = !finishing && !(page == PAGE_WELCOME && restoring)
    val back: () -> Unit = {
        if (canGoBack) storedPage = page - 1
    }
    val forward: () -> Unit = {
        if (canGoOn && settled) {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            if (page < PAGE_CHOICES) storedPage = page + 1 else finish()
        }
    }
    BackHandler(onBack = back)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // The bars, and the camera's cutout, which is at the side on a turned phone.
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val beside = maxHeight < 480.dp && maxWidth > maxHeight
            // The wizard's own step from page to page. The slide lambdas get no Density.
            val enterTravelPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            val exitTravelPx = with(LocalDensity.current) { 24.dp.roundToPx() }

            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        val towards = if (targetState > initialState) AnimatedContentTransitionScope.SlideDirection.Start
                        else AnimatedContentTransitionScope.SlideDirection.End
                        (slideIntoContainer(towards, tween(300, easing = LinearOutSlowInEasing)) { enterTravelPx } +
                            fadeIn(tween(220, delayMillis = 110, easing = LinearOutSlowInEasing)))
                            .togetherWith(
                                slideOutOfContainer(towards, tween(200, easing = FastOutLinearInEasing)) { exitTravelPx } +
                                    fadeOut(tween(110, easing = FastOutLinearInEasing))
                            )
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    label = "setupPage",
                ) { shown ->
                    val scroll = rememberSaveable(shown, saver = ScrollState.Saver) { ScrollState(0) }
                    // A page on its way out does nothing either.
                    val live = settled && shown == page
                    when (shown) {
                        PAGE_WELCOME -> SetupPage(
                            beside = beside,
                            scroll = scroll,
                            centred = true,
                            content = {
                                // No logo in a window too short for it and the five lines both: the lines say more.
                                if (!beside) {
                                    Image(
                                        painter = painterResource(R.drawable.launcher_monochrome),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary, BlendMode.SrcIn),
                                        modifier = Modifier
                                            .size(104.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                                            .padding(14.dp)
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                                SetupHeading(
                                    title = stringResource(R.string.oobe_welcome_message),
                                    body = stringResource(R.string.setup_welcome_body),
                                    large = true,
                                )
                                Spacer(Modifier.height(if (beside) 12.dp else 24.dp))
                                SetupOverview(close = beside)
                            },
                            buttons = {
                                SetupButton(
                                    text = stringResource(R.string.oobe_use_backup),
                                    primary = false,
                                    enabled = !restoring,
                                    busy = restoring,
                                    onClick = { if (live) restoreLauncher.launch(arrayOf("application/octet-stream")) },
                                )
                            },
                        )

                        PAGE_SIGN_IN -> SetupPage(
                            beside = beside,
                            scroll = scroll,
                            centred = true,
                            content = {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccountCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                Spacer(Modifier.height(if (beside) 12.dp else 20.dp))
                                SetupHeading(
                                    title = stringResource(R.string.setup_sign_in_title),
                                    body = when {
                                        !signedIn -> stringResource(R.string.setup_sign_in_body)
                                        // The name arrives a moment after the cookie does.
                                        accountName.isEmpty() -> stringResource(R.string.setup_signed_in)
                                        else -> stringResource(R.string.setup_signed_in_as, accountName)
                                    },
                                )
                            },
                            buttons = {
                                if (signedIn) {
                                    // The sign-in page comes back here by itself, so this is
                                    // where a sign-in made as the wrong account is first seen,
                                    // and where it can be made again.
                                    SetupButton(
                                        text = stringResource(R.string.setup_other_account),
                                        primary = false,
                                        onClick = { if (live) navController.navigate("login") },
                                    )
                                } else {
                                    SetupButton(
                                        text = stringResource(R.string.setup_sign_in),
                                        onClick = { if (live) navController.navigate("login") },
                                    )
                                }
                            },
                        )

                        else -> {
                            SetupPage(
                                beside = beside,
                                scroll = scroll,
                                centred = false,
                                content = {
                                    Spacer(Modifier.height(if (beside) 0.dp else 16.dp))
                                    SetupHeading(
                                        title = stringResource(R.string.setup_choices_title),
                                        body = stringResource(R.string.setup_choices_body),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    // The titles are the ones these switches have in Settings, so
                                    // each reads as that setting and not as a copy of it.
                                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                        SwitchPreference(
                                            title = { Text(stringResource(R.string.update_check)) },
                                            description = stringResource(
                                                if (fromFdroid) R.string.setup_updates_line_fdroid else R.string.setup_updates_line
                                            ),
                                            checked = choices.updates,
                                            onCheckedChange = { choices = choices.copy(updates = it) },
                                        )
                                        SwitchPreference(
                                            title = { Text(stringResource(R.string.polls_enabled)) },
                                            description = stringResource(R.string.setup_questions_line),
                                            checked = choices.questions,
                                            onCheckedChange = { choices = choices.copy(questions = it) },
                                        )
                                        SwitchPreference(
                                            title = { Text(stringResource(R.string.news_enabled)) },
                                            description = stringResource(R.string.setup_news_line),
                                            checked = choices.news,
                                            onCheckedChange = { choices = choices.copy(news = it) },
                                        )
                                        SwitchPreference(
                                            title = { Text(stringResource(R.string.usage_count_enabled)) },
                                            description = stringResource(R.string.setup_count_line),
                                            checked = choices.count,
                                            onCheckedChange = { choices = choices.copy(count = it) },
                                        )
                                        // Not there at all in a build that cannot ask Last.fm.
                                        choices.lastFm?.let { on ->
                                            SwitchPreference(
                                                title = { Text(stringResource(R.string.lastfm_opt_in_enabled)) },
                                                description = stringResource(R.string.setup_lastfm_line),
                                                checked = on,
                                                onCheckedChange = { choices = choices.copy(lastFm = it) },
                                            )
                                        }
                                    }
                                },
                                // The tick under the page is Done. A switch still under the
                                // fold when it is tapped stays as it stood.
                                buttons = null,
                            )
                        }
                    }
                }
                SetupNavRow(
                    progress = setupProgress(page),
                    last = page == PAGE_CHOICES,
                    canGoBack = canGoBack,
                    canGoOn = canGoOn,
                    onBack = {
                        if (canGoBack) haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        back()
                    },
                    onForward = forward,
                )
            }
        }
    }
}

/** How far along [page] is, for the line under it: nothing on the first page, all of it on the last. */
internal fun setupProgress(page: Int): Float =
    page.coerceIn(PAGE_WELCOME, PAGE_CHOICES).toFloat() / PAGE_CHOICES

/**
 * Back, how far along, and on: one row under every page, the two buttons the same shape at either
 * end of the line, as under the wizard's pages. On is a tick on the [last] page, where it is Done.
 *
 * Back is there on the first page too, and dead: there is nowhere back to, and without it the
 * row would be lopsided on the page that makes the first impression.
 */
@Composable
private fun SetupNavRow(
    progress: Float,
    last: Boolean,
    canGoBack: Boolean,
    canGoOn: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            SetupNavButton(enabled = canGoBack, onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            // Longer than the 300 ms a page takes to change, so the line is still moving as the
            // new page settles.
            val shown by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(400, easing = FastOutSlowInEasing),
                label = "setupProgress",
            )
            LinearProgressIndicator(
                progress = { shown },
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
                    .height(4.dp),
            )
            SetupNavButton(enabled = canGoOn, onClick = onForward) {
                Icon(
                    imageVector = if (last) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = stringResource(if (last) R.string.action_done else R.string.action_next),
                )
            }
        }
    }
}

/** One end of the row. Dead, it keeps its place and loses its colour. */
@Composable
private fun SetupNavButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (enabled) FloatingActionButtonDefaults.containerColor
        else MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation),
        contentColor = if (enabled) contentColorFor(FloatingActionButtonDefaults.containerColor)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        elevation = if (enabled) FloatingActionButtonDefaults.elevation()
        else FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        modifier = if (enabled) Modifier else Modifier.semantics { disabled() },
        content = content,
    )
}

/** The five switches across a rotation: see SetupChoices.positions. */
private val ChoicesSaver = Saver<SetupChoices, String>(
    save = { it.positions() },
    restore = { SetupChoices.ofPositions(it) },
)

/**
 * What the app does, in five lines under the welcome: somebody who has just installed it is told
 * before being asked anything. A line each and no more, the way the old wizard's first page
 * listed what the app is for, in plain words.
 */
@Composable
private fun SetupOverview(close: Boolean) {
    val lines = listOf(
        Icons.Rounded.LibraryMusic to R.string.setup_does_youtube_music,
        Icons.Rounded.SdCard to R.string.setup_does_files,
        Icons.Rounded.OfflinePin to R.string.setup_does_downloads,
        Icons.Rounded.AutoAwesome to R.string.setup_does_picks,
        Icons.Rounded.Block to R.string.setup_does_no_adverts,
    )
    Column(
        // Closer together where the window is short, so that all five are seen without scrolling.
        verticalArrangement = Arrangement.spacedBy(if (close) 8.dp else 14.dp),
        modifier = Modifier.widthIn(max = 360.dp),
    ) {
        for ((icon, line) in lines) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(line),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * One page of setup: what it says, and its own button when it has one.
 *
 * The button is outside what scrolls, so it is in view whatever the size of the screen or of the
 * type. Upright it stands under the page. In a window too short for that ([beside]) the page has
 * the left half and the button the right, so that a phone on its side shows both without
 * scrolling either. A page with none has the whole room.
 *
 * @param centred whether a page shorter than its room stands in the middle of it, as a page of a
 *   few words does, or at the top, as a list does
 */
@Composable
private fun SetupPage(
    beside: Boolean,
    scroll: ScrollState,
    centred: Boolean,
    content: @Composable ColumnScope.() -> Unit,
    buttons: (@Composable ColumnScope.() -> Unit)?,
) {
    val arrangement = if (centred) Arrangement.Center else Arrangement.Top
    if (beside && buttons != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = arrangement,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .moreBelow(scroll, MaterialTheme.colorScheme.background)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                content = content,
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 24.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.widthIn(max = 360.dp),
                    content = buttons,
                )
            }
        }
        return
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = arrangement,
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .moreBelow(scroll, MaterialTheme.colorScheme.background)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
        if (buttons != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .widthIn(max = 408.dp)
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 6.dp),
                content = buttons,
            )
        }
    }
}

/**
 * The foot of what scrolls fading into the [page] while more of it lies below. A list cut exactly
 * between two rows looks whole, and the tick under it is Done whether its end was seen or not.
 */
private fun Modifier.moreBelow(scroll: ScrollState, page: Color): Modifier = drawWithContent {
    drawContent()
    if (scroll.canScrollForward) {
        val fade = 40.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, page), startY = size.height - fade, endY = size.height),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade),
        )
    }
}

/** A page's title and the sentence under it. */
@Composable
private fun SetupHeading(title: String, body: String, large: Boolean = false) {
    Text(
        text = title,
        style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .semantics { heading() }
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    )
}

/**
 * What a page offers beside going on, the width of the page: signing in is the filled one
 * ([primary]), and restoring a backup is tonal and not a bare line of text, being as good a way
 * as going on. [busy] puts a spinner before the words.
 */
@Composable
private fun SetupButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
    val label: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, textAlign = TextAlign.Center)
    }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { label() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { label() }
    }
}

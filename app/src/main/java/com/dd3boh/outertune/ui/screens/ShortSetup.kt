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
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.constants.applyNewInstallDefaults
import com.dd3boh.outertune.constants.markFirstSetup
import com.dd3boh.outertune.ui.utils.shrinkToOneLine
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
 * on. The second is signing in, or not. The third is the questions of the old exit page, one at
 * a time with a yes and a no, and Done (SetupQuestions). Look and feel, local media and the
 * downloads folder are left at what the old pages showed, which is what anybody tapping Next
 * through them got.
 *
 * Under every page is the row the wizard has under its own: back, a line that says how far
 * along, and on, which is a tick on the last page (SetupNavRow). It is outside the pages, so it
 * stays where it is while they change, and none of them can be without it. The pages keep only
 * what is not going on: restoring a backup, signing in, and, in a build that has the import
 * (Unreleased.LIBRARY_IMPORT), bringing playlists over from another app.
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

    // The questions of the last page and the answers given to them. What is stored is read once
    // and waited for, as rememberPreference reads: the page has to stand as it will stay from
    // its first frame, or Done could be tapped over a row that then changes. Only Done writes
    // an answer down.
    var questions by rememberSaveable(saver = QuestionsSaver) {
        val stored = runBlocking(Dispatchers.IO) { context.dataStore.data.first() }
        mutableStateOf(SetupQuestions.from(stored, lastFmQuestionAskable()))
    }

    // Once only, and the writes are waited for before leaving, for the reasons given at
    // SetupWizard's finishSetup: MainActivity would otherwise read the old page number and open
    // setup again, and a second tap would run all of this over a screen that has gone.
    var finishing by remember { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!finishing) {
            finishing = true
            // What a question without an answer by now is taken as: see SetupQuestions.choices.
            val answers = questions.choices()
            coroutineScope.launch {
                // The answers first, in an edit of their own, and only then the mark that setup
                // is done. MainActivity opens the catch-up screen when setup is done and an
                // answer is missing, and it reads each of them through a collector of its own.
                // Written in one edit they could reach it a frame apart, setup done before the
                // answers, and it would ask the questions that had just been answered.
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
                                    Box(contentAlignment = Alignment.Center) {
                                        // The accent glowing behind the logo: the page opens on colour, not on a grey disc.
                                        Box(
                                            modifier = Modifier
                                                .size(168.dp)
                                                .background(
                                                    Brush.radialGradient(
                                                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f), Color.Transparent)
                                                    ),
                                                    CircleShape,
                                                )
                                        )
                                        Image(
                                            painter = painterResource(R.drawable.launcher_monochrome),
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer, BlendMode.SrcIn),
                                            modifier = Modifier
                                                .size(104.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .padding(14.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                SetupHeading(
                                    title = stringResource(R.string.oobe_welcome_message),
                                    body = stringResource(R.string.setup_welcome_body),
                                    large = true,
                                )
                                Spacer(Modifier.height(if (beside) 12.dp else 20.dp))
                                SetupOverview(close = beside)
                            },
                            buttons = {
                                // Somebody who comes from another service has playlists there
                                // and no backup of this app. The import is a screen of its own,
                                // gone to as the sign-in page is, and left it comes back here.
                                // The two stand side by side: one under the other, the second
                                // took the room of the last line of the overview on a small phone.
                                if (Unreleased.LIBRARY_IMPORT) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(IntrinsicSize.Max),
                                    ) {
                                        SetupButton(
                                            text = stringResource(R.string.oobe_use_backup),
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            primary = false,
                                            narrow = true,
                                            enabled = !restoring,
                                            busy = restoring,
                                            onClick = { if (live) restoreLauncher.launch(arrayOf("application/octet-stream")) },
                                        )
                                        SetupButton(
                                            text = stringResource(R.string.setup_import_playlists),
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            primary = false,
                                            narrow = true,
                                            enabled = !restoring,
                                            onClick = { if (live) navController.navigate("settings/backup/import") },
                                        )
                                    }
                                } else {
                                    SetupButton(
                                        text = stringResource(R.string.oobe_use_backup),
                                        primary = false,
                                        enabled = !restoring,
                                        busy = restoring,
                                        onClick = { if (live) restoreLauncher.launch(arrayOf("application/octet-stream")) },
                                    )
                                }
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
                                // The page grows as it is answered, a row above the card each time.
                                fromFoot = true,
                                content = {
                                    Spacer(Modifier.height(if (beside) 0.dp else 16.dp))
                                    SetupHeading(
                                        title = stringResource(R.string.setup_choices_title),
                                        body = stringResource(R.string.setup_choices_body),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    SetupQuestionsBody(
                                        questions = questions,
                                        onChange = { questions = it },
                                        // Not from a page on its way out, nor in a page's first moment.
                                        live = live,
                                        scroll = scroll,
                                        closing = stringResource(R.string.setup_all_answered),
                                    )
                                },
                                // The tick under the page is Done, whenever it is tapped, with
                                // every question answered or none.
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

/**
 * The last page across a rotation, or a stay in the background: the answers given and the
 * question in the card (SetupQuestions.saved). Anything else that comes back restores nothing,
 * and the page starts from what is stored.
 */
private val QuestionsSaver = Saver<MutableState<SetupQuestions>, String>(
    save = { it.value.saved() },
    restore = { saved -> SetupQuestions.ofSaved(saved, lastFmQuestionAskable())?.let { mutableStateOf(it) } },
)

/**
 * What the app does, in five lines under the welcome: somebody who has just installed it is told
 * before being asked anything. A line each and no more, the way the old wizard's first page
 * listed what the app is for, in plain words.
 */
@Composable
private fun SetupOverview(close: Boolean) {
    val rows = listOf(
        Triple(Icons.Rounded.LibraryMusic, R.string.setup_does_youtube_music, R.string.setup_does_youtube_music_more),
        Triple(Icons.Rounded.SdCard, R.string.setup_does_files, R.string.setup_does_files_more),
        Triple(Icons.Rounded.OfflinePin, R.string.setup_does_downloads, R.string.setup_does_downloads_more),
        Triple(Icons.Rounded.AutoAwesome, R.string.setup_does_picks, R.string.setup_does_picks_more),
        Triple(Icons.Rounded.Block, R.string.setup_does_no_adverts, R.string.setup_does_no_adverts_more),
    )
    if (close) {
        // A window too short for five rows of two lines: the five as plain lines, which all show.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            for ((icon, title, _) in rows) {
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
                        text = stringResource(title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth(),
    ) {
        rows.forEachIndexed { at, (icon, title, more) ->
            // Each row comes up a moment after the one above it.
            var there by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(120L + 70L * at)
                there = true
            }
            val shown by animateFloatAsState(
                targetValue = if (there) 1f else 0f,
                animationSpec = tween(320, easing = LinearOutSlowInEasing),
                label = "setupRow",
            )
            // One block of five: round at its two ends, nearly square where the rows meet.
            val outer = 22.dp
            val inner = 6.dp
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(
                    topStart = if (at == 0) outer else inner,
                    topEnd = if (at == 0) outer else inner,
                    bottomStart = if (at == rows.lastIndex) outer else inner,
                    bottomEnd = if (at == rows.lastIndex) outer else inner,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = shown
                        translationY = (1f - shown) * 16.dp.toPx()
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(more),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
 * @param fromFoot whether a page longer than its room is held by its foot, and it is its head
 *   that goes out of sight. The last page is: it grows as it is answered, and what was just
 *   put, with its two answers, is the last thing on it. Held by its head, each answer would
 *   leave the next question under the fold. The scroll then counts from the foot, so 0 is the
 *   foot whatever the page has grown to
 */
@Composable
private fun SetupPage(
    beside: Boolean,
    scroll: ScrollState,
    centred: Boolean,
    fromFoot: Boolean = false,
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
                    .moreBelow(scroll, MaterialTheme.colorScheme.background, fromFoot)
                    .verticalScroll(scroll, reverseScrolling = fromFoot)
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
                .moreBelow(scroll, MaterialTheme.colorScheme.background, fromFoot)
                .verticalScroll(scroll, reverseScrolling = fromFoot)
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
 *
 * A page held by its foot ([fromFoot]) has what it does not show above it, so its head fades the
 * same way. Its scroll counts from the foot: on is up, and back is down.
 *
 * The catch-up screen (OptInCatchUp) scrolls its questions the same way, over its own colour.
 */
internal fun Modifier.moreBelow(scroll: ScrollState, page: Color, fromFoot: Boolean = false): Modifier = drawWithContent {
    drawContent()
    val fade = 40.dp.toPx()
    if (if (fromFoot) scroll.canScrollBackward else scroll.canScrollForward) {
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, page), startY = size.height - fade, endY = size.height),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade),
        )
    }
    if (fromFoot && scroll.canScrollForward) {
        drawRect(
            brush = Brush.verticalGradient(listOf(page, Color.Transparent), startY = 0f, endY = fade),
            size = Size(size.width, fade),
        )
    }
}

/**
 * A page's title and the sentence under it.
 *
 * The first page's title ([large]) gets smaller, down to three quarters of its size, to keep to
 * one line: the list under it is as long as the page, and in Russian a title of two lines
 * pushed the last of its four cards under the button.
 */
@Composable
private fun SetupHeading(title: String, body: String, large: Boolean = false) {
    val style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
    Text(
        text = title,
        style = style,
        autoSize = if (large) style.shrinkToOneLine(smallest = 21.sp) else null,
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
 * as going on. [busy] puts a spinner before the words. Two of them share a row on the first page:
 * each is given half of it through [modifier], and [narrow] leaves the words more of that half.
 */
@Composable
private fun SetupButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
    narrow: Boolean = false,
) {
    val sized = Modifier
        .fillMaxWidth()
        .then(modifier)
        .heightIn(min = 48.dp)
    val padding = if (narrow) PaddingValues(horizontal = 12.dp, vertical = 8.dp) else ButtonDefaults.ContentPadding
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
        Button(onClick = onClick, enabled = enabled, modifier = sized, contentPadding = padding) { label() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = sized, contentPadding = padding) { label() }
    }
}

/**
 * The questions under a page's heading, one at a time: a row for each question that has its
 * answer, in the order they are put, and under the rows the card of the question that is open,
 * or [closing], the line that closes the page, when none is. Setup's last page shows this, and
 * so does the catch-up screen (OptInCatchUp), which puts the same questions to an install that
 * was never asked them: one drawing for the two, and one rule for what a tap does.
 *
 * Nothing here writes an answer down. A tap changes what the page holds ([onChange], a
 * SetupQuestions), and whoever shows this stores it: setup's tick, or the other screen's Done.
 *
 * @param live whether a tap does anything now: not on a page that is on its way out, and not
 *   while the answers are being stored
 * @param scroll the scroll of the page this is on, which is held by its foot (SetupPage), so
 *   that 0 is the card: an answer or a tap on a row brings the page back to it
 */
@Composable
internal fun SetupQuestionsBody(
    questions: SetupQuestions,
    onChange: (SetupQuestions) -> Unit,
    live: Boolean,
    scroll: ScrollState,
    closing: String,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // One PackageManager call for the page, as on the card this line comes from.
    val fromFdroid = remember { context.installSource() == InstallSource.F_DROID }

    // A question takes no answer in its first moment, as a page takes no tap in its own (see
    // SETTLE_MS). Its two answers stand where the answers of the one before it stood, so the
    // second tap of a double tap would answer a question that nobody had read.
    var settledQuestion by remember { mutableStateOf<SetupQuestion?>(null) }
    LaunchedEffect(questions.open) {
        delay(SETTLE_MS)
        settledQuestion = questions.open
    }

    // Not from a page or a card on its way out, and not in a question's first moment.
    val answer: (SetupQuestion, Boolean) -> Unit = { question, yes ->
        if (live && question == questions.open && question == settledQuestion) {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            onChange(questions.answer(question, yes))
            // Back to the card, for somebody who had scrolled up to the rows.
            coroutineScope.launch { scroll.animateScrollTo(0) }
        }
    }
    val reopen: (SetupQuestion) -> Unit = { question ->
        if (live) {
            onChange(questions.reopen(question))
            coroutineScope.launch { scroll.animateScrollTo(0) }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        for (question in questions.asked) {
            val given = questions.answers[question]
            // A row makes its own room above the card as its answer is given, and pushes the
            // card down. One given before is there from the first frame.
            AnimatedVisibility(visible = given != null) {
                SetupAnswerRow(
                    texts = question.texts(fromFdroid),
                    yes = given == true,
                    open = question == questions.open,
                    onClick = { reopen(question) },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    AnimatedContent(
        targetState = questions.open,
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = {
            // One question fades and the next comes in its place, and the room it takes changes
            // with it and not at a jump. No slide: the row coming in above is already moving
            // the card, and a second movement across it would be one too many.
            fadeIn(tween(220, delayMillis = 90, easing = LinearOutSlowInEasing))
                .togetherWith(fadeOut(tween(90, easing = FastOutLinearInEasing)))
                .using(SizeTransform(clip = false))
        },
        label = "setupQuestion",
    ) { open ->
        if (open == null) {
            Text(
                text = closing,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 20.dp)
                    // For a screen reader to say as it comes: the button it stood on has gone.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            SetupQuestionCard(
                texts = open.texts(fromFdroid),
                onAnswer = { yes -> answer(open, yes) },
            )
        }
    }
}

/**
 * What a question shows: its icon, its words in the card with the sentence under them, and the
 * few words it goes by once it is a row.
 */
private data class QuestionTexts(val icon: ImageVector, val question: Int, val line: Int, val short: Int)

/**
 * The icons are the ones the same questions have on their cards and in Settings. A word from
 * InterTune, which is news and questions as one, has the icon of news.
 */
private fun SetupQuestion.texts(fromFdroid: Boolean) = when (this) {
    SetupQuestion.UPDATES -> QuestionTexts(
        icon = Icons.Rounded.Update,
        question = R.string.setup_updates_question,
        // A copy from F-Droid is kept up to date without this, and is told so.
        line = if (fromFdroid) R.string.setup_updates_line_fdroid else R.string.setup_updates_line,
        short = R.string.setup_updates_short,
    )
    SetupQuestion.WORD -> QuestionTexts(
        icon = Icons.Rounded.Campaign,
        question = R.string.setup_word_question,
        line = R.string.setup_word_line,
        short = R.string.setup_word_short,
    )
    SetupQuestion.COUNT -> QuestionTexts(
        icon = Icons.Rounded.Groups,
        question = R.string.setup_count_question,
        line = R.string.setup_count_line,
        short = R.string.setup_count_short,
    )
    SetupQuestion.LASTFM -> QuestionTexts(
        icon = Icons.Rounded.Hub,
        question = R.string.setup_lastfm_question,
        line = R.string.setup_lastfm_line,
        short = R.string.setup_lastfm_short,
    )
}

/**
 * A question that has its answer, on one line: its icon, a few words for it, and the answer at
 * the end, which is measured before the words and so shows whatever their length. A tap puts the
 * question back in the card, which a screen reader says after the words and the answer. [open]
 * is the row whose question is in the card now.
 */
@Composable
private fun SetupAnswerRow(
    texts: QuestionTexts,
    yes: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val words = if (open) colors.onSecondaryContainer else colors.onSurface
    val quiet = if (open) colors.onSecondaryContainer else colors.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (open) colors.secondaryContainer else colors.surfaceContainerHigh)
            .clickable(
                onClickLabel = stringResource(R.string.setup_change_answer),
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = texts.icon,
            contentDescription = null,
            tint = quiet,
            modifier = Modifier.size(22.dp),
        )
        SetupOneLine(
            text = stringResource(texts.short),
            style = MaterialTheme.typography.bodyLarge,
            color = words,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(if (yes) R.string.setup_yes else R.string.setup_no),
            style = MaterialTheme.typography.labelLarge,
            // Colour for a yes only: a column of answers shows at a glance what was agreed to.
            color = if (yes) colors.primary else quiet,
            maxLines = 1,
        )
    }
}

/**
 * One question: its badge, the question as the card's heading, a sentence on what a yes does,
 * and the two answers side by side, half of the width each whatever their words.
 */
@Composable
private fun SetupQuestionCard(texts: QuestionTexts, onAnswer: (Boolean) -> Unit) {
    val question = stringResource(texts.question)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // The badge of the first page's rows, a size up.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Icon(
                    imageVector = texts.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = question,
                style = MaterialTheme.typography.titleLarge.copy(lineHeight = QUESTION_LINE),
                autoSize = QuestionSize(
                    headline = MaterialTheme.typography.titleLarge.fontSize,
                    smallest = MaterialTheme.typography.titleMedium.fontSize,
                ),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(texts.line),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val no = stringResource(R.string.setup_no_thanks)
                val yes = stringResource(R.string.setup_yes)
                SetupAnswerButton(
                    text = no,
                    spoken = stringResource(R.string.setup_answer_spoken, no, question),
                    primary = false,
                    onClick = { onAnswer(false) },
                    modifier = Modifier.weight(1f),
                )
                SetupAnswerButton(
                    text = yes,
                    spoken = stringResource(R.string.setup_answer_spoken, yes, question),
                    primary = true,
                    onClick = { onAnswer(true) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// The room between a question's lines as a share of its size, which is a headline's own share,
// so that it follows whatever size the question is given.
private val QUESTION_LINE = 1.27.em

/**
 * The size of a question in its card: a headline's while that takes two lines at most, and a
 * step smaller for as long as it would take a third, down to the size of a row's title. There it
 * takes the lines it needs: smaller before longer, and never cut.
 */
private data class QuestionSize(val headline: TextUnit, val smallest: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = headline.value
        while (size > smallest.value && performLayout(constraints, text, size.sp).lineCount > 2) size -= 2f
        return size.coerceAtLeast(smallest.value).sp
    }
}

/**
 * One of a question's two answers: a yes is the filled one ([primary]) and a no is tonal, as the
 * pages' own buttons are. Its words stay on one line. [spoken] is what a screen reader says in
 * their place: the answer and the question it answers.
 */
@Composable
private fun SetupAnswerButton(
    text: String,
    spoken: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val button = modifier
        .heightIn(min = 52.dp)
        .semantics { contentDescription = spoken }
    // Less of a margin than a button has by itself: half a card is not much room for two words.
    val margin = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val label: @Composable RowScope.() -> Unit = {
        SetupOneLine(text = text, style = MaterialTheme.typography.labelLarge)
    }
    if (primary) {
        Button(onClick = onClick, modifier = button, contentPadding = margin, content = label)
    } else {
        FilledTonalButton(onClick = onClick, modifier = button, contentPadding = margin, content = label)
    }
}

/**
 * One line of [text] that gets smaller before it would be cut. An answer has half a card, and a
 * row's words share theirs with the answer: neither has a second line to break a word onto.
 */
@Composable
private fun SetupOneLine(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = 1.sp),
        modifier = modifier,
    )
}

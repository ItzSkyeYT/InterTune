/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RecapPopUpShownKey
import com.dd3boh.outertune.constants.RecapRevealedEarlyKey
import com.dd3boh.outertune.constants.RecapWatchedKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.recap.RecapCalendar
import com.dd3boh.outertune.recap.RecapDoor
import com.dd3boh.outertune.recap.RecapReader
import com.dd3boh.outertune.recap.RecapSound
import com.dd3boh.outertune.ui.utils.OpensOnScreen
import com.dd3boh.outertune.utils.editOrSay
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.RecapState
import com.dd3boh.outertune.viewmodels.RecapViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The Recap's one way onto the screen, drawn over everything in MainActivity.
 *
 * From the reveal day (RecapCalendar) it opens by itself, once a year: full screen, and gone with
 * the cross, Back or Done. Not over setup, nor over anything else that came up first ([quiet]: the
 * questions after an update, the welcome page, a tour, an update being offered, the account
 * notice); it waits until they are gone. And only for a year with enough music in it. It is
 * written down as shown the moment it opens, whatever is done with it, so it never comes back by
 * itself; the banner in Settings is there for that ([RecapBanner]). A Recap opened another way
 * first, from its notification or a banner, counts as shown too ([countAsPopUp]).
 *
 * The day is read again whenever the app comes back to the front and when the date changes
 * ([rememberToday]): a music app's screen lives for days, and one left open on the evening before
 * gets the pop-up on the reveal day as well.
 *
 * Whatever asks RecapDoor opens it as well: that banner, the notification, the developer options'
 * row, the sample of a debug build.
 */
@Composable
fun RecapEntry(setupDone: Boolean, quiet: Boolean, onOpenPlaylist: (String) -> Unit) {
    if (!Unreleased.RECAP) return
    val database = LocalDatabase.current
    val context = LocalContext.current
    val (shownYear, setShownYear) = rememberPreference(RecapPopUpShownKey, defaultValue = 0)
    val revealedEarly by rememberPreference(RecapRevealedEarlyKey, defaultValue = false)
    // The developer options' "today is the reveal day" counts only in a build that has its row
    // (PREVIEW_FEATURES, as DeveloperFrag): a backup can bring the switch into a release.
    val early = revealedEarly && BuildConfig.PREVIEW_FEATURES
    val today = rememberToday()
    var open by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(setupDone, quiet, shownYear, early, today) {
        if (!setupDone || quiet || open != null) return@LaunchedEffect
        // The date as it looks: [today] says when to look again.
        val now = LocalDate.now()
        if (!RecapCalendar.popUpOwed(now, shownYear, early)) return@LaunchedEffect
        val year = RecapCalendar.yearOnShow(now, early) ?: return@LaunchedEffect
        if (!RecapReader.hasEnough(database, year)) return@LaunchedEffect
        setShownYear(year)
        open = year
    }

    val requested by RecapDoor.requested.collectAsState()
    LaunchedEffect(requested) {
        val year = requested ?: return@LaunchedEffect
        open = year
        RecapDoor.opened()
        countAsPopUp(context, year)
    }

    // Opened by itself, or by a door asked from outside the screen: its window only while the app is on screen.
    open?.let { year -> OpensOnScreen { RecapDialog(year, onClose = { open = null }, onOpenPlaylist = onOpenPlaylist) } }
}

/**
 * A Recap opened another way, from its notification or a banner, counts as its year's pop-up
 * (RecapCalendar.popUpShownBy): watched, then closed, it does not open again by itself, once what
 * held the pop-up back is gone or the next time the app opens. Read and written in one go, so the
 * switch just changed (the early reveal, the debug sample) is the one that counts; kept even if
 * the screen goes meanwhile, as rememberPreference keeps its writes.
 */
private suspend fun countAsPopUp(context: Context, year: Int) = withContext(NonCancellable) {
    context.editOrSay { prefs ->
        val revealedEarly = prefs[RecapRevealedEarlyKey] == true
        val shown = RecapCalendar.popUpShownBy(LocalDate.now(), year, revealedEarly && BuildConfig.PREVIEW_FEATURES) ?: return@editOrSay
        if ((prefs[RecapPopUpShownKey] ?: 0) < shown) prefs[RecapPopUpShownKey] = shown
    }
}

/**
 * Today, read again whenever it may have moved on while the screen stayed: the app back in front,
 * and the date, the clock or the time zone changed while it is up. Remembered once, a screen kept
 * open from the evening of 1 December never saw the reveal day.
 */
@Composable
internal fun rememberToday(): LocalDate {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var today by remember { mutableStateOf(LocalDate.now()) }
    DisposableEffect(context, lifecycle) {
        val back = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) today = LocalDate.now() }
        val moved = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                today = LocalDate.now()
            }
        }
        lifecycle.addObserver(back)
        val changes = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // A system broadcast still reaches a receiver that is not exported.
        ContextCompat.registerReceiver(context, moved, changes, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(back)
            runCatching { context.unregisterReceiver(moved) }
        }
    }
    return today
}

/**
 * The year whose Recap the banner in Settings offers: the one on show (RecapCalendar), when it
 * holds enough music. Null before the first reveal day, and for a year with too little in it.
 */
@Composable
fun rememberRecapOnShow(): Int? {
    if (!Unreleased.RECAP) return null
    val database = LocalDatabase.current
    val revealedEarly by rememberPreference(RecapRevealedEarlyKey, defaultValue = false)
    // Read again as the day moves on: Home stays on screen for days.
    val today = rememberToday()
    val year = remember(revealedEarly, today) { RecapCalendar.yearOnShow(today, revealedEarly && BuildConfig.PREVIEW_FEATURES) }
    val enough by produceState(false, year) {
        value = year != null && runCatching { RecapReader.hasEnough(database, year) }.getOrDefault(false)
    }
    return year.takeIf { enough }
}

/**
 * The year whose Recap the banner at the top of Home opens, or null: [rememberRecapOnShow]'s, in
 * its season only (RecapCalendar.onHome), and only until it was watched as far as its year card
 * (RecapWatchedKey, his ask of 22 Nov 2026). Settings and the Stats page keep their banner the whole year.
 */
@Composable
fun rememberRecapOnHome(): Int? {
    val year = rememberRecapOnShow()
    val revealedEarly by rememberPreference(RecapRevealedEarlyKey, defaultValue = false)
    val watched by rememberPreference(RecapWatchedKey, defaultValue = 0)
    val today = rememberToday()
    val inSeason = remember(year, revealedEarly, today) {
        year != null && RecapCalendar.onHome(today, revealedEarly && BuildConfig.PREVIEW_FEATURES)
    }
    return year.takeIf { inSeason && watched < (year ?: 0) }
}

/**
 * The Recap in a window of its own over the whole screen, edge to edge: the story's dark runs
 * under the status bar and the navigation bar, whose icons are drawn light on it. Back closes it.
 * However it is closed, its sound is told first (RecapSound.close): the listener's music carries
 * on, or the last song goes on in their queue. [onOpenPlaylist] is asked to open the playlist
 * the year card saved, after the Recap has closed (that is a close like any other).
 */
@Composable
fun RecapDialog(year: Int, onClose: () -> Unit, onOpenPlaylist: (String) -> Unit, viewModel: RecapViewModel = hiltViewModel()) {
    val service = LocalPlayerConnection.current?.service
    val sound = remember(viewModel, service) { viewModel.sound(service) }
    val closed by rememberUpdatedState(onClose)
    val opening by rememberUpdatedState(onOpenPlaylist)
    val close = remember(viewModel) { { viewModel.closeSound(); closed() } }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
            windowTitle = stringResource(R.string.recap_name),
        ),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            onDispose { }
        }
        RecapHost(year, close, viewModel, sound, onOpenPlaylist = { id -> close(); opening(id) })
    }
}

/** The year's Recap worked out afresh (the year goes on until the 31st), then told. */
@Composable
private fun RecapHost(year: Int, onClose: () -> Unit, viewModel: RecapViewModel, sound: RecapSound, onOpenPlaylist: (String) -> Unit) {
    LaunchedEffect(year) { viewModel.load(year) }
    val state by viewModel.state.collectAsState()
    // The story draws its own paper; behind the working screen, the start screen's red.
    Box(Modifier.fillMaxSize().then(if (state is RecapState.Ready) Modifier else Modifier.background(RecapNight))) {
        when (val s = state) {
            RecapState.Working -> Working()
            RecapState.Nothing -> NothingYet(year, onClose)
            is RecapState.Ready -> RecapStory(s.shown, onOpened = { viewModel.opened(year) }, onClose = onClose, viewModel = viewModel, onOpenPlaylist = onOpenPlaylist, sound = sound)
        }
    }
}

/**
 * While the year is worked out, a moment on most phones: the mark on the start screen's red
 * paper, a lemon burst behind it turning slowly, once in RecapMotion.Rest.WORKING_TURN_S; still
 * with Remove animations on. Nothing pulses.
 */
@Composable
private fun Working() {
    val still = remember { RecapMotion.still() }
    var turn by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            turn = (turn + (now - last) / 1e9f * 360f / RecapMotion.Rest.WORKING_TURN_S) % 360f
            last = now
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(128.dp)) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }) {
                val pts = PaperShapes.of(Paper.BURST)
                val path = Path()
                for (i in 0 until PaperShapes.N) {
                    val x = pts[2 * i] / 100f * size.width
                    val y = pts[2 * i + 1] / 100f * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                paperShadow(path, size.width / 128f, 1f)
                drawPath(path, RecapInk.Lemon)
            }
            RecapMark(64.dp)
        }
        Text(
            stringResource(R.string.recap_working),
            fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RecapInk.Ink,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

/** A year with too little in it, or a read that failed: said plainly, with the way out. */
@Composable
private fun NothingYet(year: Int, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        CloseButton(onClose, Modifier.align(Alignment.TopEnd).padding(8.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.align(Alignment.Center).widthIn(max = 360.dp).padding(horizontal = 32.dp),
        ) {
            RecapMark(56.dp)
            Text(
                stringResource(R.string.recap_too_little, year.toString()),
                fontSize = 18.sp, fontWeight = FontWeight.Medium, color = RecapInk.Ink, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                stringResource(R.string.recap_done),
                style = RecapType.button, color = RecapInk.Cream,
                modifier = Modifier
                    .padding(top = 28.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(RecapInk.Ink)
                    .recapClick(onClose)
                    .padding(horizontal = 22.dp)
                    .wrapCentre(),
            )
        }
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RecapPopUpShownKey
import com.dd3boh.outertune.constants.RecapRevealedEarlyKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.recap.RecapCalendar
import com.dd3boh.outertune.recap.RecapDoor
import com.dd3boh.outertune.recap.RecapReader
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.RecapState
import com.dd3boh.outertune.viewmodels.RecapViewModel
import java.time.LocalDate

/**
 * The Recap's one way onto the screen, drawn over everything in MainActivity.
 *
 * From the reveal day (RecapCalendar) it opens by itself, once a year: full screen, and gone with
 * the cross, Back or Done. Not over setup, nor over anything else that came up first ([quiet]: the
 * questions after an update, the welcome page, a tour, an update being offered, the account
 * notice); it waits until they are gone. And only for a year with enough music in it. It is
 * written down as shown the moment it opens, whatever is done with it, so it never comes back by
 * itself; the banner in Settings is there for that ([RecapBanner]).
 *
 * Whatever asks RecapDoor opens it as well: that banner, the developer options' row, the sample
 * of a debug build.
 */
@Composable
fun RecapEntry(setupDone: Boolean, quiet: Boolean) {
    if (!Unreleased.RECAP) return
    val database = LocalDatabase.current
    val (shownYear, setShownYear) = rememberPreference(RecapPopUpShownKey, defaultValue = 0)
    val revealedEarly by rememberPreference(RecapRevealedEarlyKey, defaultValue = false)
    // The developer options' "today is the reveal day" counts in a debug build only.
    val early = revealedEarly && BuildConfig.DEBUG
    val today = remember { LocalDate.now() }
    var open by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(setupDone, quiet, shownYear, early) {
        if (!setupDone || quiet || open != null) return@LaunchedEffect
        if (!RecapCalendar.popUpOwed(today, shownYear, early)) return@LaunchedEffect
        val year = RecapCalendar.yearOnShow(today, early) ?: return@LaunchedEffect
        if (!RecapReader.hasEnough(database, year)) return@LaunchedEffect
        setShownYear(year)
        open = year
    }

    val requested by RecapDoor.requested.collectAsState()
    LaunchedEffect(requested) {
        val year = requested ?: return@LaunchedEffect
        open = year
        RecapDoor.opened()
    }

    open?.let { year -> RecapDialog(year, onClose = { open = null }) }
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
    val year = remember(revealedEarly) { RecapCalendar.yearOnShow(LocalDate.now(), revealedEarly && BuildConfig.DEBUG) }
    val enough by produceState(false, year) {
        value = year != null && runCatching { RecapReader.hasEnough(database, year) }.getOrDefault(false)
    }
    return year.takeIf { enough }
}

/**
 * The Recap in a window of its own over the whole screen, edge to edge: the story's dark runs
 * under the status bar and the navigation bar, whose icons are drawn light on it. Back closes it.
 */
@Composable
fun RecapDialog(year: Int, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
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
        RecapHost(year, onClose)
    }
}

/** The year's Recap worked out afresh (the year goes on until the 31st), then told. */
@Composable
private fun RecapHost(year: Int, onClose: () -> Unit, viewModel: RecapViewModel = hiltViewModel()) {
    LaunchedEffect(year) { viewModel.load(year) }
    val state by viewModel.state.collectAsState()
    // The story draws its own paper; behind the working screen, the start screen's red.
    Box(Modifier.fillMaxSize().then(if (state is RecapState.Ready) Modifier else Modifier.background(RecapNight))) {
        when (val s = state) {
            RecapState.Working -> Working()
            RecapState.Nothing -> NothingYet(year, onClose)
            is RecapState.Ready -> RecapStory(s.shown, onOpened = { viewModel.opened(year) }, onClose = onClose)
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

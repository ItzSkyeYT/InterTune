/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.LocalMediaPermissionAskedKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first

/**
 * When the system is asked for access to the music on the device.
 *
 * It has been asked for by the automatic scan at start (scanInit), once. The scan does not run
 * until setup is done, and nothing runs it again in that launch, so the prompt came up at the
 * first start after setup, or as soon as the phone was turned, which makes the activity anew: a
 * minute into the app, over whatever was on screen, for music on the device that nobody had
 * mentioned. Local media is on by default, so that was everybody.
 *
 * With the short setup ([whereNeeded], Unreleased.SHORT_SETUP) it is asked for where somebody has
 * gone that needs it: the Folders tab or the local scanner as it opens, once between them, and
 * local media as it is turned on in Settings. The scan at start no longer asks: without access it
 * scans the downloads and leaves local media be, as it does today for whoever said no. What asks
 * on a tap is as it was: the banner over Folders and the Library lists, and Scan.
 *
 * Without the flag every answer here is today's.
 */
object MediaPermissionAsk {
    /** At the automatic scan at start, on finding access missing. */
    fun atStart(askedBefore: Boolean, whereNeeded: Boolean = Unreleased.SHORT_SETUP): Boolean =
        !whereNeeded && !askedBefore

    /**
     * As Folders or the local scanner opens. Once: [askedBefore] is the mark the scan at start
     * has always left, so whoever was asked by it before an update is not asked again here. Not
     * while a tour is going round, which opens Local media by itself.
     */
    fun onOpening(
        localMediaOn: Boolean,
        granted: Boolean,
        askedBefore: Boolean,
        tourRunning: Boolean,
        whereNeeded: Boolean = Unreleased.SHORT_SETUP,
    ): Boolean = whereNeeded && localMediaOn && !granted && !askedBefore && !tourRunning

    /** As local media is turned on in Settings. Every time: that is somebody asking for it. */
    fun onTurningOn(granted: Boolean, whereNeeded: Boolean = Unreleased.SHORT_SETUP): Boolean =
        whereNeeded && !granted
}

private fun Context.mayReadMusic() = checkSelfPermission(MEDIA_PERMISSION_LEVEL) == PackageManager.PERMISSION_GRANTED

/** The system's prompt, and the mark that it has been put. A denial shows the activity's toast. */
private suspend fun Context.askForMusic() {
    dataStore.edit { it[LocalMediaPermissionAskedKey] = true }
    (this as? MainActivity)?.run { permissionLauncher.launch(MEDIA_PERMISSION_LEVEL) }
}

/**
 * In a destination that needs the music on the device: asks for access as it opens, if
 * [MediaPermissionAsk.onOpening] says so.
 */
@Composable
fun AskForMusicOnOpening(tourRunning: Boolean) {
    if (!Unreleased.SHORT_SETUP) return
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        if (MediaPermissionAsk.onOpening(
                localMediaOn = prefs[LocalLibraryEnableKey] ?: true,
                granted = context.mayReadMusic(),
                askedBefore = prefs[LocalMediaPermissionAskedKey] ?: false,
                tourRunning = tourRunning,
            )
        ) {
            context.askForMusic()
        }
    }
}

/**
 * The same round a screen that has to be drawn anew once access has been given, which is Folders.
 *
 * Folders decides as it opens whether to show its banner about the missing permission. Until now
 * that was only ever answered by tapping the banner, and the banner hides itself on that tap.
 * Asked for from here and allowed, it would stay up over a screen that has what it wants. The
 * system's prompt pauses the app, so coming back to the front is when to look again.
 */
@Composable
fun AskForMusicOnOpening(tourRunning: Boolean, content: @Composable () -> Unit) {
    if (!Unreleased.SHORT_SETUP) {
        content()
        return
    }
    AskForMusicOnOpening(tourRunning)

    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.mayReadMusic()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { granted = context.mayReadMusic() }
    }
    key(granted) { content() }
}

/** For the switch that turns local media on, once it has been turned on. */
suspend fun askForMusicOnTurningOn(context: Context) {
    if (MediaPermissionAsk.onTurningOn(granted = context.mayReadMusic())) context.askForMusic()
}

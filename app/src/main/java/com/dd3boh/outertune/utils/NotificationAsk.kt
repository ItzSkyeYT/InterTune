/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.constants.NotificationsAskedKey
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android's own question about notifications, put once, as something whose notification is the
 * point of it is used (the whole-app scan of 28 Nov 2026, platform finding 2).
 *
 * From Android 13 an app asks for notifications itself, and InterTune asked only for song
 * recognition and the update check. On a fresh install the sleep timer's countdown with +5 min,
 * +15 min and Stop, the downloads' progress, the move of the downloads into a folder and a room's
 * proposals never showed, and nothing said why.
 *
 * Now the first of these to be used asks: a sleep timer started from its sheet, a download asked
 * for (a song, an album, a playlist, Liked songs), the move into the folder, a room started or
 * joined. Once for all of them, ever ([NotificationsAskedKey]). Never at start, and never after a
 * no to Android's question, which Android tells (shouldShowRequestPermissionRationale); after two
 * it stops asking by itself. What was used goes ahead whatever the answer. No words of the app's:
 * Android's question is the ask.
 */
object NotificationAsk {
    private val uses = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Heard by MainActivity while it is on screen, which asks ([askForNotificationsOnce]). A use
     * with no screen to ask on is let go, never kept for the next start.
     */
    val used: SharedFlow<Unit> = uses.asSharedFlow()

    /** Something whose notification is the point of it is being used: the one call of every place. */
    fun atUse() {
        uses.tryEmit(Unit)
    }

    /** Whether Android's question is put now. */
    fun shouldAsk(sdk: Int, granted: Boolean, askedBefore: Boolean, saidNo: Boolean): Boolean =
        sdk >= Build.VERSION_CODES.TIRAMISU && !granted && !askedBefore && !saidNo
}

/**
 * The question, if [NotificationAsk.shouldAsk] says so, through MainActivity's one launcher for it.
 * The mark is left first, so two uses at once never ask twice.
 */
suspend fun MainActivity.askForNotificationsOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val permission = Manifest.permission.POST_NOTIFICATIONS
    val ask = NotificationAsk.shouldAsk(
        sdk = Build.VERSION.SDK_INT,
        granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
        askedBefore = dataStore.get(NotificationsAskedKey, defaultValue = false),
        saidNo = shouldShowRequestPermissionRationale(permission),
    )
    if (!ask) return
    dataStore.edit { it[NotificationsAskedKey] = true }
    Log.i("NotificationAsk", "Asking Android for notifications, once")
    notificationLauncher.launch(permission)
}

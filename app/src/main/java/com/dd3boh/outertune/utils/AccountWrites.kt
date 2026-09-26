/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import com.dd3boh.outertune.App
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.constants.YtmSyncModeKey
import com.dd3boh.outertune.extensions.isUserLoggedIn
import com.dd3boh.outertune.extensions.toEnum

/**
 * The one rule for whether the app may change anything in the YouTube Music account: someone is
 * signed in, and sync is set to read and write. "Read only" promises it never changes anything
 * there, and a push with nobody signed in has no account to go to (a like made signed out used to
 * be sent anyway, answered with an error nobody read).
 *
 * Read and write is the default, as it always was, so nothing changes for anyone who never
 * touched the setting.
 */
fun mayPushToYouTube(loggedIn: Boolean, mode: SyncMode): Boolean = loggedIn && mode == SyncMode.RW

fun Context.mayPushToYouTube(): Boolean =
    mayPushToYouTube(isUserLoggedIn(), dataStore[YtmSyncModeKey].toEnum(defaultValue = SyncMode.RW))

/**
 * For the entities' toggles, which have no Context. False before the app has started, which also
 * keeps unit tests that toggle an entity off the network.
 */
fun mayPushToYouTube(): Boolean =
    runCatching { App.instance.mayPushToYouTube() }.getOrDefault(false)

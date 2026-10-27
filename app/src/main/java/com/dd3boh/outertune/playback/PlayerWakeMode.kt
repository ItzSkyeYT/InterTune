/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C

/**
 * What the player keeps awake while it plays: the processor always, and Wi-Fi at full power only
 * while it loads.
 *
 * Media3's network mode holds a WIFI_MODE_FULL_HIGH_PERF lock for as long as music plays, which
 * keeps Wi-Fi out of power save with the screen off and the app in the background. A song is
 * loaded in bursts of a megabyte, about a minute of music each, and a song from the cache, a
 * download or a file needs no network at all, so the lock was held through whole songs for
 * nothing. It is now taken when the player starts to load and let go when it stops: MusicService
 * asks this at each change.
 */
object PlayerWakeMode {
    /** The processor and Wi-Fi while the player loads, the processor alone otherwise. */
    fun forLoading(isLoading: Boolean): Int = if (isLoading) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL
}

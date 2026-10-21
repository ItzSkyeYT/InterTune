/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

/**
 * When to get the n step ready before the first song needs it, decided here without Android so
 * that it is tested. MusicService asks once, a few seconds after it starts.
 *
 * The first app client's address of a process makes NewPipeExtractor fetch YouTube's player
 * script, about 0.7 MB, and compile its n function. On 20 Oct 2026 the first song waited 11 s
 * with nothing written in between, and the next took 0.3 s. Fetched a few seconds after the
 * service starts, it is paid while nobody waits.
 *
 * Only on a network that costs nothing. The service also starts for the widget, a media button
 * and Android Auto, and on mobile data 0.7 MB at every start, for a song that may never be
 * played, is not the app's to spend: there the first song that needs the script fetches it.
 */
object Warmup {
    /** How long after the service starts. */
    const val AFTER_MS = 3_000L

    /** Whether this process has fetched the script ahead already: a service made again in the same process does not. */
    @Volatile
    var warmedInProcess = false

    /**
     * Whether to fetch the player script now: a network that reaches the internet and costs
     * nothing, and Data Saver off for the app. Once a process.
     */
    fun newPipe(
        validated: Boolean,
        dataSaver: Boolean,
        unmetered: Boolean,
        alreadyWarmed: Boolean,
    ): Boolean = !alreadyWarmed && validated && !dataSaver && unmetered
}

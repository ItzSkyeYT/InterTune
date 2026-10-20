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
 * service starts, it is paid while nobody waits. It is never an extra fetch: the first streamed
 * song of the process would make it anyway, so it is made only when that song is coming.
 */
object Warmup {
    /** How long after the service starts. */
    const val AFTER_MS = 3_000L

    /**
     * Whether to fetch the player script now: a network that reaches the internet, Data Saver
     * off for the app, and either a network that costs nothing or a current song that is to be
     * streamed (not a local file, a download or a song cached whole). Once a process.
     */
    fun newPipe(
        validated: Boolean,
        dataSaver: Boolean,
        unmetered: Boolean,
        currentStreamed: Boolean,
        alreadyWarmed: Boolean,
    ): Boolean = !alreadyWarmed && validated && !dataSaver && (unmetered || currentStreamed)
}

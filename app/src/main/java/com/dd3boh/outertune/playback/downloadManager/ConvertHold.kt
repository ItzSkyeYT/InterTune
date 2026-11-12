/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

/**
 * Keeps the app up for as long as it converts songs (Unreleased.DOWNLOAD_FORMATS): from the first
 * conversion of a run to a moment after its last, [start] has been called once and [stop] once.
 *
 * A conversion begins when the last download has landed, which is when the download service stops,
 * and it takes from seconds to minutes a song. With nothing of the app's left in the foreground,
 * Android stops the work of an app that is not on screen: a song converted to MP3 stood still for
 * as long as the person was in another app, and went on when they came back (a tester on 12 Nov
 * 2026: downloads "sometimes only finishing after you start downloading another song"; on the
 * emulator the same day Android froze the process ten seconds after the app was left, for the 68 s
 * it was away). [start] makes the app a foreground service (ConvertHoldService).
 *
 * Between two songs of a run nothing is converted for a moment (one file is written to the folder,
 * the next is read): the hold stays for [lingerMs] after a conversion ends, and a conversion that
 * begins in that time keeps it. Let go and taken again, it would be asked for from the background,
 * where Android may refuse it.
 *
 * [start] says whether the app is held. When it is not (Android refused), the next conversion asks
 * again. Plain functions in, so it is tested without Android: [later] runs a block after a delay.
 */
internal class ConvertHold(
    private val start: (label: String) -> Boolean,
    private val stop: () -> Unit,
    private val later: (delayMs: Long, block: () -> Unit) -> Unit,
    private val lingerMs: Long = LINGER_MS,
) {
    /** Conversions under way. */
    private var converting = 0

    /** [start] went through and [stop] has not been called since. */
    private var held = false

    /** Counts the conversions begun: a letting go asked for before the last of them is dropped. */
    private var begun = 0L

    /** A conversion begins: to [label], the format as a person says it (ConvertNotice.label). */
    @Synchronized
    fun begin(label: String) {
        converting++
        begun++
        if (!held) held = start(label)
    }

    /** A conversion is over, however it ended. */
    @Synchronized
    fun end() {
        if (converting > 0) converting--
        if (converting > 0 || !held) return
        val asked = begun
        later(lingerMs) { letGo(asked) }
    }

    @Synchronized
    private fun letGo(asked: Long) {
        if (converting > 0 || !held || begun != asked) return
        held = false
        stop()
    }

    companion object {
        /** How long the hold stays after a conversion: longer than the write of a song's file and the read of the next. */
        const val LINGER_MS = 6_000L
    }
}

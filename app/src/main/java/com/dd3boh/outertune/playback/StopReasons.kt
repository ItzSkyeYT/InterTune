/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import androidx.media3.common.Player
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why the music stopped when nobody asked it to, in words for Report a problem.
 *
 * A song that stops by itself with no error left nothing to read: issue #26, "playback stops
 * exactly 1 minute after the screen turns off", where the report could only say that nothing
 * looked like a fault. Each such stop now writes a line to the log, and the last few are also kept
 * in a small file, because the stop that matters most, Android ending the service, can take the
 * process and its log lines with it.
 */
object StopReasons {
    /** What paused the player, or null when it was asked for: a tap, a button, the app itself. */
    fun paused(reason: Int): String? = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> null
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "another app took the sound"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "the headphones or the speaker went away"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "a change from elsewhere"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "the end of the song, as set"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "the sound was held back too long"
        else -> "the player's reason $reason"
    }

    /** The line for a pause nobody asked for, or null when it was asked for. */
    fun pausedLine(reason: Int, screenOn: Boolean?, inForeground: Boolean?): String? =
        paused(reason)?.let { "STOPPED: paused by $it${state(screenOn, inForeground)}" }

    /**
     * The line for the service ending while music plays: Android stopping it, most likely. Without
     * whether it was in the foreground: by the time it hears it is ending, Android has already
     * taken it out (seen on the emulator, 17 Oct 2026). [SCREEN_OFF_OUT_OF_FOREGROUND] is that reading.
     */
    fun endedLine(screenOn: Boolean?): String =
        "STOPPED: the player's service ended while playing${state(screenOn, null)}"

    /**
     * The line for the screen going off while music plays with the service out of the foreground,
     * which is what lets Android stop it a minute later (issue #26). Read when the screen goes off,
     * while the service is alive to say.
     */
    const val SCREEN_OFF_OUT_OF_FOREGROUND = "STOPPED SOON: the screen went off while playing and the player's service is NOT in the foreground, " +
        "so Android can stop it a minute later"

    /**
     * The line for media3 failing to bring the service into the foreground. Android 12 and later
     * refuse that from the background, media3 carries on out of the foreground, and Android then
     * stops the service about a minute after the app has left the screen.
     */
    const val NOT_IN_FOREGROUND = "STOPPED SOON: Android would not let the player into the foreground from the background, " +
        "and out of it the service can be stopped a minute after the app leaves the screen"

    private fun state(screenOn: Boolean?, inForeground: Boolean?): String = listOfNotNull(
        screenOn?.let { if (it) "screen on" else "screen off" },
        inForeground?.let { if (it) "in the foreground" else "NOT in the foreground" },
    ).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " (", postfix = ")").orEmpty()

    /** How many lines the file keeps, the latest last: a screen going off and the stop after it are two. */
    const val KEPT = 8

    private const val FILE = "stops.txt"

    /** [lines] with [line] added at the end, dated, and only the last [KEPT] of them. */
    fun added(lines: List<String>, line: String, at: Date): List<String> =
        (lines + "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(at)} $line").takeLast(KEPT)

    /**
     * Keeps [line] among the last stops. Small and synchronous on purpose: it is also written as the
     * service ends, when anything left for later may never run.
     */
    fun keep(context: Context, line: String) {
        runCatching {
            val file = File(context.filesDir, FILE)
            file.writeText(added(read(context), line, Date()).joinToString("\n", postfix = "\n"))
        }
    }

    /** The last stops, oldest first. Empty when there are none or the file cannot be read. */
    fun read(context: Context): List<String> = runCatching {
        File(context.filesDir, FILE).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
    }.getOrNull().orEmpty()
}

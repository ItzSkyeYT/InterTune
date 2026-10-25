/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.BatteryMode
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.SYNC_CD
import com.dd3boh.outertune.constants.SpatialAudioMode
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.extensions.toEnum
import kotlin.math.min

/**
 * What each choice of Battery use (Settings > Player and audio) turns down, as plain rules: Full,
 * the app as it always was; Balanced, everyone's default; and Saver. Asked for on 23 Oct 2026, his
 * answers on 24 Oct; the costs behind each line are in experiments/features/battery-1023.
 *
 * THE RULE FOR SETTINGS SET BY HAND. A mode never writes a setting. It is applied where a setting
 * is used: the person's own value goes in, and the value to use while the mode is on comes out.
 * So nothing anybody chose is written over, Full and Balanced give every setting back exactly as
 * it was set, and leaving Saver brings back each effect, quality and style the moment it is left.
 * And it is never silent: every row of Settings that Saver holds says so under it, and is greyed
 * where Saver holds it entirely. Two lines go further and leave a choice made by hand alone even
 * in Saver: a song cache size, which Saver only picks where none was ever picked, and Download on
 * Wi-Fi only, which no mode turns off. The developer's Enable offload stays as well, and keeps
 * meaning offload in every mode.
 */
object BatteryUse {

    /** Everyone's, existing installs included: a key never written reads as this. */
    val DEFAULT = BatteryMode.BALANCED

    /** The mode in force: as stored, the default when nothing is, and Full in a build that does not offer the choice. */
    fun modeOf(stored: String?, offered: Boolean = Unreleased.BATTERY_MODES): BatteryMode =
        if (!offered) BatteryMode.FULL else stored.toEnum(DEFAULT)

    /** [chosen] as the row shows it, in force only where this build offers the choice. */
    fun inForce(chosen: BatteryMode, offered: Boolean = Unreleased.BATTERY_MODES): BatteryMode =
        if (!offered) BatteryMode.FULL else chosen

    // The library sync, which walks the whole library when the app opens.

    /** How long an automatic library sync waits after the last one. Sync now is never held. */
    fun syncEverySeconds(mode: BatteryMode): Long = when (mode) {
        BatteryMode.FULL -> SYNC_CD.toLong()
        BatteryMode.BALANCED -> 2 * HOUR_S
        BatteryMode.SAVER -> 6 * HOUR_S
    }

    /** Whether an automatic sync may run on the network in use: Saver waits for one that is not metered. */
    fun syncAllowed(mode: BatteryMode, metered: Boolean): Boolean = !(mode == BatteryMode.SAVER && metered)

    // The adaptive queue's taste, a read of the whole listening history.

    /**
     * How long the radios keep the taste they read before reading it again. Within one run (one
     * play of one queue) the mode says, since the run's own songs and skips count at once whatever
     * the taste's age. A new run never takes a taste older than Full keeps it, so a radio started
     * now leaves out what was just heard in another queue as freshly as it always did.
     */
    fun tasteKeptMs(mode: BatteryMode, sameRun: Boolean = true): Long = when (if (sameRun) mode else BatteryMode.FULL) {
        BatteryMode.FULL -> 5 * MINUTE_MS
        BatteryMode.BALANCED -> 30 * MINUTE_MS
        BatteryMode.SAVER -> 60 * MINUTE_MS
    }

    // The bars on the playing song, each of whose steps is a frame drawn for the whole window.

    enum class Bars {
        /** As smooth as the screen draws: 90 frames a second on a Pixel 5. */
        SCREEN_RATE,

        /** A step every [BARS_STEP_MS]. */
        STEPPED,

        /** Standing still. */
        STILL,
    }

    /** Thirty steps a second. */
    const val BARS_STEP_MS = 33L

    fun bars(mode: BatteryMode): Bars = when (mode) {
        BatteryMode.FULL -> Bars.SCREEN_RATE
        BatteryMode.BALANCED -> Bars.STEPPED
        BatteryMode.SAVER -> Bars.STILL
    }

    // What is streamed.

    /** The quality to stream at: Saver takes Low on a metered network, whatever is chosen. */
    fun quality(chosen: AudioQuality, metered: Boolean, mode: BatteryMode): AudioQuality =
        if (mode == BatteryMode.SAVER && metered) AudioQuality.LOW else chosen

    /**
     * Whether Saver streams something else than [chosen] would on mobile data, for the line under
     * Audio quality. Auto already takes the smallest stream there, so only High and Highest change.
     */
    fun lowersQuality(chosen: AudioQuality, mode: BatteryMode): Boolean =
        mode == BatteryMode.SAVER && (chosen == AudioQuality.HIGH || chosen == AudioQuality.MAX)

    // The sound effects, which Saver turns off (his answer of 24 Oct 2026). With none of them on,
    // the song can go to the phone's low-power audio path.

    fun holdsEffects(mode: BatteryMode): Boolean = mode == BatteryMode.SAVER

    fun spatialAudio(chosen: SpatialAudioMode, mode: BatteryMode): SpatialAudioMode =
        if (holdsEffects(mode)) SpatialAudioMode.OFF else chosen

    fun equalizer(chosen: Boolean, mode: BatteryMode): Boolean = chosen && !holdsEffects(mode)

    fun fade(chosen: Boolean, mode: BatteryMode): Boolean = chosen && !holdsEffects(mode)

    /** The player's volume: up to 100% in Saver, where the boost past it would need the app's path. */
    fun volume(chosen: Float, mode: BatteryMode): Float = if (holdsEffects(mode)) min(chosen, 1f) else chosen

    /** Quieter as you walk away: off in Saver, since its Bluetooth scan runs for as long as music plays. */
    fun proximity(chosen: Boolean, mode: BatteryMode): Boolean = chosen && mode != BatteryMode.SAVER

    /**
     * Whether the song goes to the phone's low-power audio path (offload): in Saver, or wherever
     * the developer's switch asks for it, and only while nothing needs the app's own path. Skip
     * silence, another speed or pitch, a sleep timer counting down and every sound effect do.
     */
    fun offload(mode: BatteryMode, developerSwitch: Boolean, needsAppPath: Boolean): Boolean =
        !needsAppPath && (developerSwitch || mode == BatteryMode.SAVER)

    // Downloads, the player's background and the song cache.

    /** Download on Wi-Fi only as it is set, and always in Saver. */
    fun downloadsOnWifiOnly(chosen: Boolean, mode: BatteryMode): Boolean = chosen || mode == BatteryMode.SAVER

    /**
     * The player's background: [blur] in place of Living blur in Saver, which costs about half a
     * core more than Blur while the player is open. [blur] is the app's default background, Blur
     * where the phone can draw it and a gradient where it cannot.
     */
    fun playerBackground(chosen: PlayerBackgroundStyle, mode: BatteryMode, blur: PlayerBackgroundStyle): PlayerBackgroundStyle =
        if (mode == BatteryMode.SAVER && chosen == PlayerBackgroundStyle.LIVING) blur else chosen

    /** What Saver keeps of songs played, where no size was ever picked: one of the sizes the setting offers. */
    const val SAVER_SONG_CACHE_MB = 512

    /**
     * The song cache's size in megabytes, -1 for no limit and 0 for none: a size picked by hand,
     * and otherwise none, or [SAVER_SONG_CACHE_MB] in Saver. Read when the app starts.
     */
    fun songCacheMb(stored: Int?, mode: BatteryMode): Int =
        stored ?: if (mode == BatteryMode.SAVER) SAVER_SONG_CACHE_MB else 0

    private const val HOUR_S = 60 * 60L
    private const val MINUTE_MS = 60 * 1000L
}

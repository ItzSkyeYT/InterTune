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

    /** Saver while the phone's battery saver is on, unless the switch under Battery use says no: on for everyone. */
    const val FOLLOW_PHONE_DEFAULT = true

    /**
     * The mode in force: Saver while the phone's own battery saver is on and [follow] lets it in
     * (his ask of 25 Oct 2026), otherwise the one picked. Nothing is written either way, so the
     * phone's saver going off gives the picked mode back at once.
     */
    fun effective(picked: BatteryMode, follow: Boolean, phoneSaver: Boolean): BatteryMode =
        if (follow && phoneSaver) BatteryMode.SAVER else picked

    /**
     * The mode in force from what is stored: the picked mode (the default when none), the switch
     * (on when never set) and the phone's battery saver; Full in a build that does not offer the choice.
     */
    fun modeOf(stored: String?, follow: Boolean?, phoneSaver: Boolean, offered: Boolean = Unreleased.BATTERY_MODES): BatteryMode =
        if (!offered) BatteryMode.FULL else effective(stored.toEnum(DEFAULT), follow ?: FOLLOW_PHONE_DEFAULT, phoneSaver)

    /** [chosen] as the row shows it, with the phone's saver, in force only where this build offers the choice. */
    fun inForce(chosen: BatteryMode, follow: Boolean, phoneSaver: Boolean, offered: Boolean = Unreleased.BATTERY_MODES): BatteryMode =
        if (!offered) BatteryMode.FULL else effective(chosen, follow, phoneSaver)

    /** Whether the row under Battery use says that Saver is on because of the phone. */
    fun saverFromPhone(picked: BatteryMode, follow: Boolean, phoneSaver: Boolean): Boolean =
        follow && phoneSaver && picked != BatteryMode.SAVER

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

    // The screen: what is drawn and how often (round two, his word of 25 Oct 2026).

    /**
     * The dark theme, as Android's own battery saver has it: on in Saver even where light was
     * picked, since a black screen is what saves on an OLED panel. [chosenDark] is the theme as the
     * person set it, the system's included.
     */
    fun darkTheme(chosenDark: Boolean, mode: BatteryMode): Boolean = chosenDark || mode == BatteryMode.SAVER

    /** Pure black for the dark theme's surfaces: on in Saver, otherwise as set. */
    fun pureBlack(chosen: Boolean, mode: BatteryMode): Boolean = chosen || mode == BatteryMode.SAVER

    /**
     * Liquid glass without its live blur: in Saver the panels keep their shapes and places and are
     * drawn as still frosted panes, so nothing behind them is recorded and blurred at every frame.
     */
    fun glassStill(mode: BatteryMode): Boolean = mode == BatteryMode.SAVER

    /**
     * Whether the app's window is held to a slower frame rate: in Saver, the slowest the display
     * offers at the size in use that is still at least [SAVER_FRAME_RATE] ([saverScreenMode]);
     * otherwise the screen's own rate. 30 where a panel has it, 60 on most phones.
     */
    fun lowFrameRate(mode: BatteryMode): Boolean = mode == BatteryMode.SAVER

    const val SAVER_FRAME_RATE = 30f

    /** One of the display's modes, as Display.Mode gives it. */
    data class ScreenMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /**
     * The display mode Saver holds the window to: the lowest refresh rate at the size in use that
     * is still at least [SAVER_FRAME_RATE], or null where the display has no faster one at that
     * size to hold back (then the window only hints the rate). Not compared with the mode in use,
     * which moves with what the system picks: at 60 for a still screen, a phone that also has 90
     * would go back up on the next scroll. A mode at another size would change the picture, so it
     * is never taken.
     */
    fun saverScreenMode(modes: List<ScreenMode>, current: ScreenMode): ScreenMode? {
        val sameSize = modes.filter { it.width == current.width && it.height == current.height }
        val slowest = sameSize.filter { it.refreshRate >= SAVER_FRAME_RATE - 0.5f }.minByOrNull { it.refreshRate } ?: return null
        return slowest.takeIf { sameSize.any { it.refreshRate > slowest.refreshRate + 0.5f } }
    }

    // Home's engine rows (Quick picks by the engine and Discover), 7.5 s and 3.7 s of processor a
    // build in Preview on 23 Oct 2026 (MEASURE.md section 4).

    /** Listens since a build after which Balanced builds Home's rows again, once three hours have gone by. */
    const val ROWS_REBUILD_LISTENS = 10

    /** A row this old is built again on Balanced however little was played since. */
    const val ROWS_OLD_MS = 24 * 60 * 60 * 1000L

    private const val ROWS_FRESH_MS = 3 * 60 * 60 * 1000L

    /**
     * Whether a built row stands when Home appears. A pull to refresh always builds, and so does a
     * change of what the row is built under (the lean, the chip, Familiarity): the caller decides
     * those before asking. Full, as before: until a new listening session, a new part of the day
     * or three hours. Balanced: at least three hours, and then only once [ROWS_REBUILD_LISTENS]
     * listens have started since the build, or a day has gone by. Saver: until a pull to refresh.
     */
    fun rowStands(mode: BatteryMode, ageMs: Long, sameSession: Boolean, sameDayPart: Boolean, listensSince: Int): Boolean = when (mode) {
        BatteryMode.FULL -> ageMs < ROWS_FRESH_MS && sameSession && sameDayPart
        BatteryMode.BALANCED -> ageMs < ROWS_FRESH_MS || (listensSince < ROWS_REBUILD_LISTENS && ageMs < ROWS_OLD_MS)
        BatteryMode.SAVER -> true
    }

    /** Whether Home builds rows nobody sees (the shadow row How it's doing compares against). Not in Saver. */
    fun buildsUnseenRows(mode: BatteryMode): Boolean = mode != BatteryMode.SAVER

    /** Whether Playing now follows the session by itself (a rebuild after enough listens). In Saver only a pull builds. */
    fun rowFollowsSession(mode: BatteryMode): Boolean = mode != BatteryMode.SAVER

    private const val HOUR_S = 60 * 60L
    private const val MINUTE_MS = 60 * 1000L
}

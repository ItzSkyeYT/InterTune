package com.dd3boh.outertune.constants

/**
 * Default values, bounds and presets for the sleep timer.
 *
 * Durations are stored in minutes (timer length) and seconds (fade length).
 */
object SleepTimerDefaults {
    const val FADE_ENABLED = true

    /** Fade length in seconds. */
    const val FADE_DURATION_SECONDS = 30
    val FADE_DURATION_RANGE = 5..60

    /** Whether a timer whose time is up waits for the end of the song (SleepTimerSongFinishKey). */
    const val LET_SONG_FINISH = false

    /** Timer length in minutes. */
    const val DEFAULT_MINUTES = 60
    val MINUTES_RANGE = 1..120

    /**
     * Fixed-minute presets. Not the sheet's chips (see [IN_MINUTES]): the schedule's lengths are
     * made from these (ScheduledSleep.LENGTHS), so they stay as they were.
     */
    val PRESET_MINUTES = listOf(15, 30, 45, 60, 75, 90)

    /** The "In" row of the sheet that sets a timer: the lengths Spotify and YouTube Music offer, and 90. */
    val IN_MINUTES = listOf(10, 15, 30, 45, 60, 90)

    /** The chips of a running timer, and the notification's two (the first two). */
    val EXTEND_MINUTES = listOf(5, 15, 30)
}

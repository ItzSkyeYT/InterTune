/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.SleepTimerDefaults
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The sleep timer that starts by itself, under the sleep timer's fade in Player and audio.
 *
 * Each day from the chosen time, music that plays gets the sleep timer, once: at the chosen time
 * when music is already on, or when it is put on in the three hours after. It is the service's own
 * sleep timer that starts, the one the player's button starts, so the fade and the stop are that
 * timer's and nothing else needs to know where it came from.
 *
 * A timer that is already running when the schedule looks is left alone, and it counts as that
 * day's: set by hand at 22:50 or at 23:10, the schedule has nothing more to do that night. Once the
 * day's timer has stopped the music, music put on again plays on, because whoever put it on is
 * plainly awake. Nothing here runs while the music is paused: the service looks only while it
 * plays, so an evening with nothing on costs nothing.
 *
 * Days are named by the date their window opened, so a window that opens at 23:00 and runs past
 * midnight is still the night of the day it opened on.
 */
object ScheduledSleep {
    /** 23:00, in minutes after midnight. */
    const val DEFAULT_START = 23 * 60

    /** In minutes. */
    const val DEFAULT_LENGTH = 30

    /** How long after the chosen time music put on still gets the timer. */
    const val WINDOW_MINUTES = 3 * 60

    /** The lengths offered: the sleep timer's own presets, and its longest. */
    val LENGTHS: List<Int> = (SleepTimerDefaults.PRESET_MINUTES + SleepTimerDefaults.MINUTES_RANGE.last).distinct()

    /**
     * The longest wait between two looks while music plays. The wait for the window is worked out
     * from the clock, and the phone's clock or time zone can change in the meantime; looking again
     * every quarter of an hour, which costs nothing beside the music, sees the change.
     */
    val LONGEST_WAIT: Duration = Duration.ofMinutes(15)

    /** The shortest, so that a wait that ends a moment early does not spin. */
    private val SHORTEST_WAIT: Duration = Duration.ofSeconds(1)

    /** The three settings as the service reads them, already kept to what they can be. */
    data class Settings(val on: Boolean, val start: Int, val length: Int)

    /** What the schedule does when it looks while music plays. */
    data class Look(
        /** The day this serves: written down, so that it starts only once on it. */
        val night: LocalDate,
        /** False when a timer is already running: it is left alone and counts as the day's. */
        val startTimer: Boolean,
    )

    /** The start time as stored, kept to a time of day. */
    fun startOf(stored: Int?): Int = Math.floorMod(stored ?: DEFAULT_START, 24 * 60)

    /**
     * The length as stored, kept to what the sleep timer takes. A 0 there stops the music at once
     * and a -1 means the end of the song, so neither may come from a damaged or edited setting.
     */
    fun lengthOf(stored: Int?): Int =
        (stored ?: DEFAULT_LENGTH).coerceIn(SleepTimerDefaults.MINUTES_RANGE.first, SleepTimerDefaults.MINUTES_RANGE.last)

    private fun opening(day: LocalDate, startMinute: Int): LocalDateTime =
        day.atTime(LocalTime.of(startMinute / 60 % 24, startMinute % 60))

    /**
     * The day [now] falls in, named by the date its window opened, or null outside every window.
     * A window that opens at 23:00 runs past midnight into the next day.
     */
    fun nightOf(now: LocalDateTime, startMinute: Int): LocalDate? {
        val today = now.toLocalDate()
        for (day in listOf(today, today.minusDays(1))) {
            val opens = opening(day, startMinute)
            if (!now.isBefore(opens) && now.isBefore(opens.plusMinutes(WINDOW_MINUTES.toLong()))) return day
        }
        return null
    }

    /**
     * What to do at [now] for music that is playing: null outside the window or once the day has
     * been served, and otherwise the day to write down, with the timer to start unless one is
     * already running.
     */
    fun look(now: LocalDateTime, startMinute: Int, served: LocalDate?, timerRunning: Boolean): Look? {
        val night = nightOf(now, startMinute) ?: return null
        if (night == served) return null
        return Look(night, startTimer = !timerRunning)
    }

    /**
     * How long to wait from [now] before looking again while music plays: until the next window
     * opens, and never longer than [LONGEST_WAIT]. Worked out on the zoned clock, so that a night
     * the clocks change on is waited out in real time and not in the hours the clock face shows.
     */
    fun untilNextLook(now: ZonedDateTime, startMinute: Int): Duration {
        val today = opening(now.toLocalDate(), startMinute).atZone(now.zone)
        val next = if (now.isBefore(today)) today else today.plusDays(1)
        val wait = Duration.between(now, next)
        return when {
            wait > LONGEST_WAIT -> LONGEST_WAIT
            wait < SHORTEST_WAIT -> SHORTEST_WAIT
            else -> wait
        }
    }
}

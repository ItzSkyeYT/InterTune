/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import com.dd3boh.outertune.constants.SleepTimerDefaults

/**
 * The sleep timer's fade as one row in Settings (Ti-Die's review, his answer of 19 Nov 2026): a length of 0
 * is no fade, any other length turns it on at that length, held within what the timer can fade over. Off
 * keeps the length there was, so the switch in the timer's sheet turns it back on at that length.
 */
object SleepFadeChoice {
    /** The length to store for [seconds] picked, or null to leave the stored one as it is (0, no fade). */
    fun of(seconds: Int): Int? = if (seconds <= 0) null else seconds.coerceIn(SleepTimerDefaults.FADE_DURATION_RANGE)
}

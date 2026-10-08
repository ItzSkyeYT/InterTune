/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the system is asked for access to the music on the device.
 *
 * It used to come from the automatic scan at start, so it turned up at the first start after
 * setup, or when the phone was turned a minute after it, over whatever was on screen. With the
 * short setup it is asked for where somebody has gone that needs it. With the flag off every
 * answer here is today's.
 */
class MediaPermissionAskTest {

    private val either = listOf(false, true)

    @Test
    fun `with the short setup the scan at start never asks`() {
        for (asked in either) assertFalse(MediaPermissionAsk.atStart(askedBefore = asked, whereNeeded = true))
    }

    @Test
    fun `opening Folders or the local scanner asks once, and only when it would help`() {
        fun opening(on: Boolean = true, granted: Boolean = false, asked: Boolean = false, tour: Boolean = false) =
            MediaPermissionAsk.onOpening(on, granted, asked, tour, whereNeeded = true)

        assertTrue(opening())
        // Asked once. After that the banner over the list and the Scan button ask when tapped.
        assertFalse(opening(asked = true))
        assertFalse(opening(granted = true))
        assertFalse(opening(on = false))
        // The tour of the settings opens Local media by itself: nobody went there.
        assertFalse(opening(tour = true))
    }

    @Test
    fun `turning local media on asks whenever access is missing`() {
        assertTrue(MediaPermissionAsk.onTurningOn(granted = false, whereNeeded = true))
        assertFalse(MediaPermissionAsk.onTurningOn(granted = true, whereNeeded = true))
    }

    @Test
    fun `with the flag off it is asked as it is today, once, by the scan at start`() {
        assertTrue(MediaPermissionAsk.atStart(askedBefore = false, whereNeeded = false))
        assertFalse(MediaPermissionAsk.atStart(askedBefore = true, whereNeeded = false))
        for (on in either) for (granted in either) for (asked in either) for (tour in either) {
            assertFalse(MediaPermissionAsk.onOpening(on, granted, asked, tour, whereNeeded = false))
        }
        for (granted in either) assertFalse(MediaPermissionAsk.onTurningOn(granted, whereNeeded = false))
    }

    @Test
    fun `nothing asks twice in a row for the same reason`() {
        // Whoever was asked by the old scan at start, before an update, is not asked again by
        // opening a screen: the same mark is read by both.
        val askedByTheOldScan = true
        assertEquals(false, MediaPermissionAsk.onOpening(true, false, askedByTheOldScan, false, whereNeeded = true))
    }
}

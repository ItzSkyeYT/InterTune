/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * freshLaunch is true only for an activity started for the intent it holds. It is false for one
 * recreated with saved state and for a task relaunched from recents, since both hand back the
 * task's original intent with its extras.
 */
class FreshLaunchTest {

    @Test
    fun `a fresh launch is fresh`() {
        assertTrue(freshLaunch(hadSavedInstanceState = false, flags = 0))
    }

    @Test
    fun `recreated with saved state, after a rotation or process death, it is not fresh`() {
        assertFalse(freshLaunch(hadSavedInstanceState = true, flags = 0))
    }

    @Test
    fun `reopened from recents, it is not fresh`() {
        assertFalse(freshLaunch(hadSavedInstanceState = false, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }
}

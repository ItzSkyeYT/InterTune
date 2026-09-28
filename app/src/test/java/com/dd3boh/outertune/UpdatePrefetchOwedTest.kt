/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a found update should be pre-fetched: auto-install has to be on, the install cannot be
 * from F-Droid, and the installer has to be free to take it.
 */
class UpdatePrefetchOwedTest {

    @Test
    fun `auto-install on, not F-Droid, installer free, owed`() {
        assertTrue(
            updatePrefetchOwed(autoInstall = true, fromFdroid = false, installerBusy = false, installerIdle = true)
        )
    }

    @Test
    fun `never on F-Droid, even with an old auto-install preference still on`() {
        assertFalse(
            updatePrefetchOwed(autoInstall = true, fromFdroid = true, installerBusy = false, installerIdle = true)
        )
    }

    @Test
    fun `not owed when auto-install is off`() {
        assertFalse(
            updatePrefetchOwed(autoInstall = false, fromFdroid = false, installerBusy = false, installerIdle = true)
        )
    }

    @Test
    fun `not owed while the installer is already busy`() {
        assertFalse(
            updatePrefetchOwed(autoInstall = true, fromFdroid = false, installerBusy = true, installerIdle = true)
        )
    }

    @Test
    fun `not owed once the installer has left Idle`() {
        assertFalse(
            updatePrefetchOwed(autoInstall = true, fromFdroid = false, installerBusy = false, installerIdle = false)
        )
    }
}

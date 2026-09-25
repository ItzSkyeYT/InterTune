/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewInstallDefaultsTest {

    @Test
    fun `a new install starts on Best recommendations`() {
        val prefs = mutablePreferencesOf()
        applyNewInstallDefaults(prefs)
        assertEquals(QuickPicksSource.ENGINE.name, prefs[QuickPicksSourceKey])
    }

    @Test
    fun `an install that went through setup before keeps its default`() {
        val prefs = mutablePreferencesOf(OobeStatusKey to OOBE_VERSION)
        applyNewInstallDefaults(prefs)
        // Nothing written, so it still reads as YouTube Music, as it did before the update.
        assertNull(prefs[QuickPicksSourceKey])
    }

    @Test
    fun `a source chosen during setup is never overridden`() {
        val prefs = mutablePreferencesOf(QuickPicksSourceKey to QuickPicksSource.LIBRARY.name)
        applyNewInstallDefaults(prefs)
        assertEquals(QuickPicksSource.LIBRARY.name, prefs[QuickPicksSourceKey])
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewInstallDefaultsTest {

    // What SetupWizard does as it opens and as it finishes. In between, OobeStatusKey is the page
    // it is on: Next writes the page number, so Done is reached with the key on the last page.
    private fun open(prefs: MutablePreferences) = markFirstSetup(prefs)

    private fun finish(prefs: MutablePreferences) {
        applyNewInstallDefaults(prefs)
        prefs[OobeStatusKey] = OOBE_VERSION
    }

    @Test
    fun `a new install that skips setup on the welcome page starts on Best recommendations`() {
        val prefs = mutablePreferencesOf()
        open(prefs)
        finish(prefs)
        assertEquals(QuickPicksSource.ENGINE.name, prefs[QuickPicksSourceKey])
    }

    @Test
    fun `a new install that taps through setup to Done starts on Best recommendations`() {
        val prefs = mutablePreferencesOf()
        open(prefs)
        prefs[OobeStatusKey] = OOBE_VERSION - 1
        finish(prefs)
        assertEquals(QuickPicksSource.ENGINE.name, prefs[QuickPicksSourceKey])
    }

    @Test
    fun `setup opening again part way through is still a new install`() {
        val prefs = mutablePreferencesOf()
        open(prefs)
        // Rotated, or the app was closed, on the third page.
        prefs[OobeStatusKey] = 2
        open(prefs)
        prefs[OobeStatusKey] = OOBE_VERSION - 1
        finish(prefs)
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
    fun `an update that opens setup on its last page keeps its default`() {
        // Finished under a setup one page shorter, so it opens on this one's last page.
        val prefs = mutablePreferencesOf(OobeStatusKey to OOBE_VERSION - 1)
        open(prefs)
        finish(prefs)
        assertNull(prefs[QuickPicksSourceKey])
    }

    @Test
    fun `setup run again from Developer writes nothing, skipped or tapped through`() {
        for (lastPage in listOf(0, OOBE_VERSION - 1)) {
            val prefs = mutablePreferencesOf(OobeStatusKey to OOBE_VERSION)
            // Developer > Enter configurator.
            prefs[OobeStatusKey] = 0
            open(prefs)
            prefs[OobeStatusKey] = lastPage
            finish(prefs)
            assertNull("finished on page $lastPage", prefs[QuickPicksSourceKey])
        }
    }

    @Test
    fun `a source chosen during setup is never overridden`() {
        val prefs = mutablePreferencesOf()
        open(prefs)
        prefs[QuickPicksSourceKey] = QuickPicksSource.LIBRARY.name
        finish(prefs)
        assertEquals(QuickPicksSource.LIBRARY.name, prefs[QuickPicksSourceKey])
    }

    @Test
    fun `finishing removes the mark, so setup run again later is not a new install`() {
        val prefs = mutablePreferencesOf()
        open(prefs)
        finish(prefs)
        assertNull(prefs[FirstSetupKey])
    }
}

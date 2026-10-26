/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's connection and battery in words for a report, and nothing that names a network or a person. */
class PhoneStateTest {

    @Test
    fun `a slow mobile line says so, in kbps`() {
        val line = PhoneState(network = "mobile data", metered = true, downKbps = 120, upKbps = 40, batterySaver = true,
            backgroundRestricted = false, optimisationExempt = false).describe()
        assertEquals("mobile data, metered, about 120 kbps down and 40 kbps up, no VPN, Data Saver off; battery saver on, " +
            "not restricted in the background, battery optimisation on for InterTune", line)
    }

    @Test
    fun `a fast Wi-Fi in Mbps, a VPN and Data Saver named, what Android cannot say left out`() {
        val line = PhoneState(network = "Wi-Fi", vpn = true, downKbps = 45_400, dataSaver = true).describe()
        assertEquals("Wi-Fi, not metered, about 45 Mbps down, a VPN on, Data Saver on; battery saver off", line)
    }

    @Test
    fun `InterTune's own Battery use comes beside the phone's saver`() {
        val line = PhoneState(network = "Wi-Fi", batterySaver = true, batteryUse = "Saver", backgroundRestricted = false).describe()
        assertTrue(line, line.endsWith("; battery saver on, Battery use Saver, not restricted in the background"))
    }

    @Test
    fun `no network, and a network with no internet behind it`() {
        assertTrue(PhoneState(network = null).describe().startsWith("no network, Data Saver off"))
        assertTrue("no internet behind it" in PhoneState(network = "Wi-Fi", validated = false).describe())
    }

    @Test
    fun `the report says what the phone is like now, under where the app came from`() {
        val report = ProblemLog.report(ProblemLog.Excerpt(emptyList(), null), build = "0.12.5", device = "a phone", installedFrom = "GitHub",
            phoneNow = "Wi-Fi, not metered")
        val lines = report.lines()
        assertEquals("Phone now: Wi-Fi, not metered", lines[lines.indexOf("Installed from: GitHub") + 1])
        assertFalse("Phone now" in ProblemLog.report(ProblemLog.Excerpt(emptyList(), null), build = "0.12.5", device = "a phone", installedFrom = "GitHub"))
    }
}

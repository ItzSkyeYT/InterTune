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

/**
 * The lines that say how a song was found and kept playing. They go to the app's own log, which
 * Report a problem shows and people paste into issues, so none of them may carry an address.
 */
class StepLogTest {
    private val lines = listOf(
        StepLog.resolved("TESTVIDEO01", 1_320, listOf(StepLog.asked("VISIONOS", "OK", 210), StepLog.deciphered(40), StepLog.checked(200, 310))),
        StepLog.unresolved("TESTVIDEO01", 20_000, listOf(StepLog.asked("VISIONOS", null, 5_000))),
        StepLog.freshTry("TESTVIDEO01", "VISIONOS", "/player", 1_500),
        StepLog.freshAnswered("TESTVIDEO01", "VISIONOS", "/player", 180),
        StepLog.alongside("TESTVIDEO01", "VISIONOS", "VISIONOS_104", 3_000),
        StepLog.passedOver("TESTVIDEO01", "VISIONOS", 3_000, "VISIONOS_104"),
        StepLog.playedUnchecked("TESTVIDEO01", "VISIONOS", 3_000),
        StepLog.lateRefusal("TESTVIDEO01", "VISIONOS", 403, "30 min"),
        StepLog.lateAnswer("TESTVIDEO01", "VISIONOS", 200),
        StepLog.slowNetwork("TESTVIDEO01", 10_000),
        StepLog.timedOut("TESTVIDEO01", "VISIONOS", "/player", 5_000),
        StepLog.stallCut("TESTVIDEO01", 6_000, 83_000, 1, 3),
        StepLog.stallRoute("TESTVIDEO01", 12_000, 83_000, "VISIONOS", 2, 3),
        StepLog.loadFailed("TESTVIDEO01", "load failed twice in 30 s", "VISIONOS"),
        StepLog.quietRetry("TESTVIDEO01", "Timeout", 1_000, 1, 3),
        StepLog.gaveUp("TESTVIDEO01", 3, "no answer from YouTube's servers"),
        StepLog.rests("VISIONOS", "18:20", 2, "bot check"),
        StepLog.remoteApplied("2026-10-21-visionos-closed", listOf("VISIONOS_104", "VISIONOS"), listOf("VISIONOS")),
        StepLog.remoteIgnored("it disabled every full-stream client"),
    )

    @Test
    fun `no line carries an address, a host or a scheme`() {
        val ip = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
        for (line in lines) {
            assertFalse(line, "googlevideo" in line)
            assertFalse(line, "http" in line)
            assertFalse(line, ip.containsMatchIn(line))
        }
    }

    @Test
    fun `times print to the hundredth, limits to the tenth, places in a song as a player shows them`() {
        assertEquals("0.21 s", StepLog.seconds(210))
        assertEquals("1.32 s", StepLog.seconds(1_320))
        assertEquals("1.5 s", StepLog.limit(1_500))
        assertEquals("1:23", StepLog.at(83_000))
        assertEquals("0:05", StepLog.at(5_400))
    }

    @Test
    fun `one line a song says each step and its time`() {
        assertEquals(
            "[TESTVIDEO01] resolved in 1.32 s: VISIONOS OK 0.21 s, n 0.04 s, HEAD 200 0.31 s",
            lines.first(),
        )
        assertEquals("[TESTVIDEO01] no stream after 20.00 s: VISIONOS no answer 5.00 s", lines[1])
    }

    @Test
    fun `the recovery lines say what was done and how many tries are left`() {
        assertEquals("PLAYING: [TESTVIDEO01] no byte for 6.0 s at 1:23, connection cut (1 of 3)", StepLog.stallCut("TESTVIDEO01", 6_000, 83_000, 1, 3))
        assertEquals("PLAYING: [TESTVIDEO01] Timeout, tried again quietly in 1 s (1 of 3)", StepLog.quietRetry("TESTVIDEO01", "Timeout", 1_000, 1, 3))
        assertTrue(StepLog.gaveUp("TESTVIDEO01", 1, "x").contains("after 1 try:"))
    }
}

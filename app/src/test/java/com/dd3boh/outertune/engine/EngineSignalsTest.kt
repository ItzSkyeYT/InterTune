/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSignalsTest {
    private fun listen(
        playedMs: Long, durationMs: Long = 200_000, endReason: Int = EndReason.ENDED, origin: Int = PlayOrigin.SEARCH.code,
        depth: Int = 0, startedAt: Long = 1_000_000, learn: Boolean = true,
    ) = ListenRow("s", startedAt, startedAt + playedMs, playedMs, durationMs, endReason, origin, depth, sessionId = 1, tzOffsetMin = 0, learn = learn)

    @Test
    fun `engagement ramps from ten to eighty percent and needs thirty seconds`() {
        assertEquals(0.0, Signals.engagement(listen(20_000), null), 0.0)
        assertEquals(0.0, Signals.engagement(listen(40_000, 400_000), null), 0.0)        // 10%: the bottom of the ramp
        assertEquals(0.5, Signals.engagement(listen(90_000, 200_000), null), 1e-9)       // 45%
        assertEquals(1.0, Signals.engagement(listen(160_000, 200_000), null), 1e-9)      // 80%
        assertEquals(1.0, Signals.engagement(listen(200_000, 200_000), null), 1e-9)
    }

    @Test
    fun `an unknown length is credited slowly and capped`() {
        assertEquals(0.0, Signals.engagement(listen(30_000, -1), null), 0.0)
        assertEquals(0.5, Signals.engagement(listen(120_000, -1), null), 1e-9)
        assertEquals(0.7, Signals.engagement(listen(600_000, -1), null), 1e-9)
    }

    @Test
    fun `a like during or just after the listen is a full positive`() {
        val l = listen(40_000, 400_000, startedAt = 1_000_000)
        assertEquals(1.0, Signals.engagement(l, likedAt = 1_020_000), 0.0)
        assertEquals(1.0, Signals.engagement(l, likedAt = l.endedAt + 9 * 60_000), 0.0)
        assertEquals(0.0, Signals.engagement(l, likedAt = l.endedAt + 11 * 60_000), 0.0)
        assertEquals(0.0, Signals.engagement(l, likedAt = 500_000), 0.0)
    }

    @Test
    fun `a skip counts most near the middle and not at all in the fade-out or before the floor`() {
        assertEquals(0.0, Signals.skip(listen(20_000, endReason = EndReason.SKIPPED)), 0.0)
        assertEquals(0.0, Signals.skip(listen(190_000, endReason = EndReason.SKIPPED)), 0.0)
        assertEquals(0.0, Signals.skip(listen(100_000, endReason = EndReason.ENDED)), 0.0)
        val mid = Signals.skip(listen(100_000, endReason = EndReason.SKIPPED))      // r = 0.5: full ramp, weight 1 - r
        assertEquals(0.5, mid, 1e-9)
        val early = Signals.skip(listen(40_000, endReason = EndReason.SKIPPED))     // r = 0.2: partway up the ramp
        assertTrue(early in 0.0..mid)
        assertEquals(0.0, Signals.skip(listen(100_000, -1, endReason = EndReason.SKIPPED)), 0.0)
    }

    @Test
    fun `intent follows how the play started and decays down a radio`() {
        assertEquals(1.0, Signals.intent(listen(1, origin = PlayOrigin.SEARCH.code)), 0.0)
        assertEquals(0.8, Signals.intent(listen(1, origin = PlayOrigin.PLAYLIST.code)), 0.0)
        assertEquals(0.7, Signals.intent(listen(1, origin = PlayOrigin.QUICK_PICKS.code)), 0.0)
        assertEquals(0.8, Signals.intent(listen(1, origin = PlayOrigin.PLAYLIST.code, depth = 3)), 0.0)
        assertEquals(0.6, Signals.intent(listen(1, origin = PlayOrigin.RADIO.code, depth = 1)), 1e-9)
        assertEquals(0.6 * 0.85 * 0.85, Signals.intent(listen(1, origin = PlayOrigin.QUICK_PICKS.code, depth = 3)), 1e-9)
        assertEquals(0.2, Signals.intent(listen(1, origin = PlayOrigin.RADIO.code, depth = 30)), 1e-9)
    }

    @Test
    fun `value combines the three and is nothing when the listener opted out`() {
        val l = listen(100_000, endReason = EndReason.SKIPPED, origin = PlayOrigin.SEARCH.code)   // g 0.571, k 0.5, m 1
        assertEquals((0.5 - 0.10) / 0.70 - 0.25, Signals.value(l, null), 1e-9)
        assertEquals(0.0, Signals.value(l.copy(learn = false), null), 0.0)
    }

    @Test
    fun `day parts are local and know the weekend`() {
        // 2026-09-11 is a Friday. 14:00 UTC with a +120 offset is 16:00 local: weekday afternoon = 2.
        val fridayNoonUtc = 1_789_135_200_000L
        assertEquals(2, dayPartBucket(fridayNoonUtc, 120))
        // The same instant a day later is Saturday: weekend afternoon = 6.
        assertEquals(6, dayPartBucket(fridayNoonUtc + 86_400_000L, 120))
        // 23:30 local on Friday rolls into Saturday night with a +600 offset.
        assertEquals(4, dayPartBucket(fridayNoonUtc, 600))
    }

    @Test
    fun `recency never sees a negative age`() {
        assertEquals(1.0, Signals.recency(nowMs = 0, thenMs = 10_000), 0.0)
        assertEquals(Math.pow(2.0, -0.5), Signals.recency(nowMs = 3_600_000, thenMs = 0), 1e-12)
    }
}

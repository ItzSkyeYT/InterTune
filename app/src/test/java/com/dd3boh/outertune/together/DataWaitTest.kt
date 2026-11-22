/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest behind the room past what has come in waited for it for as long as that took (Follow.jumpNow), and a stream that
 * comes in no faster than it plays never got there: "Catching up, N ms apart" for good (dvd in #testers, 22 Nov 2026, on
 * a slow connection: "i can catch up but its not catching up"). DataWait bounds the wait, jumps, and says when even that
 * can't work.
 */
class DataWaitTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()

    /**
     * The looks of a guest playing on, every quarter of a second from [fromMs] to [toMs], the room [ahead] of it and the
     * song come in [comeIn] ahead of it at each moment: when it first jumps, or null.
     */
    private fun firstJump(wait: DataWait, fromMs: Long, toMs: Long, ahead: Long, comeIn: (Long) -> Long): Long? {
        var t = fromMs
        while (t <= toMs) {
            val local = 60_000 + t
            if (wait.jumps(targetMs = local + ahead, localMs = local, bufferedMs = local + comeIn(t), nowMs = t)) return t
            t += 250
        }
        return null
    }

    @Test
    fun `read no further than 15 s ahead, a guest 20 s behind waited for good, and now jumps after the limit`() {
        // As it was: what has come in never reaches where the room is, and no jump is ever asked for.
        for (t in 0..120_000L step 250) assertFalse(Follow.jumpNow(80_000 + t, 60_000 + t, 75_000 + t))
        assertEquals(DataWait.LIMIT_MS, firstJump(DataWait(), 0, 120_000, ahead = 20_000) { 15_000 })
        // The limit is a few seconds.
        assertTrue(DataWait.LIMIT_MS in 3_000L..10_000L)
    }

    @Test
    fun `what comes in fast enough to get there soon is waited for, with no rebuffer`() {
        // Twice the song's pace, 8 s to make up: there 8.5 s on, by what has come in, past the limit.
        val wait = DataWait()
        assertEquals(8_500L, firstJump(wait, 0, 30_000, ahead = 8_000) { t -> t })
        assertTrue(Follow.jumpNow(60_000 + 8_500 + 8_000, 60_000 + 8_500, 60_000 + 8_500 + 8_500))
        assertFalse(wait.tooSlow)
        // A fifth faster than the song: 37 s more to get there, so the jump at the limit is sooner.
        assertEquals(DataWait.LIMIT_MS, firstJump(DataWait(), 0, 60_000, ahead = 8_000) { t -> t / 5 })
    }

    @Test
    fun `a line at the song's own pace gets one jump, then the plain line, until the song has come in that far`() {
        val wait = DataWait()
        // 2 s behind, 1 s come in and never more: the wait is given up on at the limit, and the guest jumps.
        assertEquals(DataWait.LIMIT_MS, firstJump(wait, 0, 20_000, ahead = 2_000) { 1_000 })
        assertFalse(wait.tooSlow)
        // Behind again after the rebuffer; the next wait (after SyncLock's hold on a second jump) is given up on too: the
        // jump did not help, so it is not made again, and the line says so.
        assertNull(firstJump(wait, 12_000, 60_000, ahead = 1_800) { 1_000 })
        assertTrue(wait.tooSlow)
        // The song comes in that far after all: into it, and the line goes.
        assertTrue(wait.jumps(targetMs = 100_000, localMs = 98_000, bufferedMs = 100_000 + Follow.LANDING_MS, nowMs = 61_000))
        assertFalse(wait.tooSlow)
    }

    @Test
    fun `another song or Catch up starts afresh, and a wait broken off starts again`() {
        val wait = DataWait()
        assertFalse(wait.songIs("a"))
        firstJump(wait, 0, 20_000, ahead = 2_000) { 1_000 }
        firstJump(wait, 12_000, 60_000, ahead = 2_000) { 1_000 }
        assertTrue(wait.tooSlow)
        assertFalse("the same song", wait.songIs("a"))
        assertTrue("another song ends it", wait.songIs("b"))
        assertFalse(wait.tooSlow)
        // After a gap in the looks (in step, paused, on its own), the wait counts from its new start.
        val broken = DataWait()
        assertNull(firstJump(broken, 0, 4_000, ahead = 2_000) { 1_000 })
        assertEquals(10_000L + DataWait.LIMIT_MS, firstJump(broken, 10_000, 30_000, ahead = 2_000) { 1_000 })
        // Catch up, too.
        val caught = DataWait()
        firstJump(caught, 0, 20_000, ahead = 2_000) { 1_000 }
        firstJump(caught, 12_000, 60_000, ahead = 2_000) { 1_000 }
        caught.reset()
        assertFalse(caught.tooSlow)
        assertEquals(70_000L + DataWait.LIMIT_MS, firstJump(caught, 70_000, 90_000, ahead = 2_000) { 1_000 })
    }

    @Test
    fun `the session bounds every wait for data and says when the line can't keep up`() {
        val follow = session.substringAfter("private fun follow()").substringBefore("\n    }\n")
        assertTrue("is Follow.Seek -> if (dataWait.jumps(decision.positionMs, local, player.bufferedPosition, now))" in follow)
        assertTrue("dataWait.songIs(state.songId)" in follow)
        assertTrue("tooSlow = dataWait.tooSlow" in follow)
        val catchUp = session.substringAfter("fun catchUp() {").substringBefore("\n    }\n")
        assertTrue("dataWait.reset()" in catchUp)
        assertTrue("tooSlow = false" in catchUp)
        // The sheet: the plain line in place of the meter, after the lines that say why it is not following.
        val sheet = File(src, "ui/dialog/TogetherDialog.kt").readText()
        val lines = sheet.substringAfter("status.waitingForHost -> Waiting()").substringBefore("else -> SyncMeter(")
        assertTrue(lines.indexOf("status.tooSlow -> RoomTooSlow()") > lines.indexOf("status.songUnavailable -> SongUnavailable()"))
        for (values in listOf("values", "values-fr")) {
            val strings = File("src/main/res/$values/strings-ot.xml").readText()
            assertTrue(values, "<string name=\"together_too_slow\">" in strings)
        }
    }
}

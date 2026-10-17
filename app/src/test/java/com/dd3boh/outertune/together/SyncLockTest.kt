/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The follower's lock (his word of 17 Oct 2026: the adjuster was "way too sensitive"), the
 * room's settings, and the follower's queue being the leader's.
 */
class SyncLockTest {

    private fun looks(lock: SyncLock, gaps: List<Long>) = gaps.map { lock.decide(10_000 + it, 10_000, 1.0) }

    private fun inStep(lock: SyncLock) = looks(lock, List(SyncLock.LOCK_LOOKS) { 5L })

    @Test
    fun `in step for two seconds, it locks`() {
        val lock = SyncLock()
        looks(lock, List(SyncLock.LOCK_LOOKS - 1) { 5L })
        assertFalse(lock.locked)
        looks(lock, listOf(5L))
        assertTrue(lock.locked)
    }

    @Test
    fun `locked, readings within the tolerance leave the speed alone`() {
        val lock = SyncLock(toleranceMs = 60)
        inStep(lock)
        // The wobble of a real room: before the lock each of these was a nudge.
        val decisions = looks(lock, listOf(40, -35, 55, -50, 30, 58, -20))
        assertTrue(decisions.all { it == Follow.Steady(1.0) })
        assertTrue(lock.locked)
        // Unlocked, the same reading nudges, as it always did.
        assertTrue(SyncLock(toleranceMs = 60).decide(10_040, 10_000, 1.0) is Follow.Nudge)
    }

    @Test
    fun `one reading past the tolerance is not drifting, a second of them is`() {
        val lock = SyncLock(toleranceMs = 60)
        inStep(lock)
        assertEquals(Follow.Steady(1.0), looks(lock, listOf(90)).single())
        looks(lock, listOf(10))                                   // back inside: the count starts again
        val held = looks(lock, List(SyncLock.UNLOCK_LOOKS - 1) { 90L })
        assertTrue(held.all { it == Follow.Steady(1.0) })
        assertTrue(lock.locked)
        val drifting = looks(lock, listOf(90)).single()
        assertFalse(lock.locked)
        assertTrue(drifting is Follow.Nudge && drifting.speed > 1.0) // behind: a touch faster
    }

    @Test
    fun `corrected back into step, it locks again`() {
        val lock = SyncLock(toleranceMs = 60)
        inStep(lock)
        looks(lock, List(SyncLock.UNLOCK_LOOKS) { 90L })
        assertFalse(lock.locked)
        inStep(lock)
        assertTrue(lock.locked)
    }

    @Test
    fun `a jump is a jump at once, locked or not, and the lock is earned again`() {
        val lock = SyncLock()
        inStep(lock)
        assertEquals(Follow.Seek(10_900), lock.decide(10_900, 10_000, 1.0))
        assertFalse(lock.locked)
    }

    @Test
    fun `the catch-up speed sets how hard it nudges`() {
        val gentle = SyncLock(maxNudge = 0.01).decide(10_200, 10_000, 1.0) as Follow.Nudge
        val quick = SyncLock(maxNudge = 0.05).decide(10_200, 10_000, 1.0) as Follow.Nudge
        assertEquals(1.01, gentle.speed, 1e-9)
        assertEquals(1.05, quick.speed, 1e-9)
    }

    @Test
    fun `settings are held to their ranges, and unset ones are the defaults`() {
        assertEquals(TogetherTuning(SyncLock.DEFAULT_TOLERANCE_MS, 0.05, 0), TogetherTuning.of(null, null, null))
        assertEquals(TogetherTuning(SyncLock.MIN_TOLERANCE_MS, 0.01, 0), TogetherTuning.of(5, 0, -40))
        assertEquals(TogetherTuning(SyncLock.MAX_TOLERANCE_MS, 0.05, TogetherTuning.MAX_DELAY_MS), TogetherTuning.of(9_000, 50, 9_000))
        // Above in step, or it would lock and unlock on the same readings.
        assertTrue(SyncLock.MIN_TOLERANCE_MS > Follow.IN_STEP_MS)
        assertTrue(SyncLock.MAX_TOLERANCE_MS < Follow.DRIFT_MS)
    }

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `a follower's queue is the leader's, never a radio of its own`() {
        // The radio played other songs next than the leader did, more of them (his find, 17 Oct).
        assertFalse(session.contains("YouTubeQueue"))
        assertTrue(session.contains("matchQueue()"))
        // Nor do its own top-ups and drops change the leader's queue on it.
        assertTrue(service.contains("together.value?.following != true &&\n            mediaItem != null"))
        val replan = service.substringAfter("private fun replanQueueTail(").substringBefore("\n    }\n")
        assertTrue(replan.contains("if (together.value?.following == true) return"))
    }

    @Test
    fun `a follower's pause is its own, and play takes it back to the leader`() {
        val listener = session.substringAfter("override fun onPlayWhenReadyChanged").substringBefore("override fun")
        assertTrue(listener.contains("listenerPlayed(playWhenReady)"))
        assertFalse(listener.contains("listenerChose"))
        assertTrue(session.substringAfter("private fun follow()").contains("if (ownWay || pausedHere) return"))
    }

    @Test
    fun `the room shows the song playing, not its queue, and the player has a way back to it`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertFalse(sheet.contains("together_up_next"))
        assertFalse(sheet.contains("QueueRow"))
        val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
        assertEquals(2, Regex("""\bRoomButton\(""").findAll(player).count())
    }
}

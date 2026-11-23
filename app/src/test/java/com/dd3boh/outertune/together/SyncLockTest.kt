/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlin.math.abs
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

    /** A look every 250 ms from [from], the host [gap] ms ahead of the guest each time; what it decides at each. */
    private fun looks(lock: SyncLock, gaps: List<Long>, from: Long = 0L) =
        gaps.mapIndexed { i, gap -> lock.decide(10_000 + gap, 10_000, 1.0, from + i * 250L) }

    @Test
    fun `in step, readings inside the tolerance leave the speed alone however they wobble`() {
        val lock = SyncLock(toleranceMs = 60)
        // The wobble of a real room: before, each of these past 25 ms was a change of speed, a click.
        val decisions = looks(lock, List(40) { listOf(40L, -35L, 55L, -50L, 30L, 58L, -20L, 10L)[it % 8] })
        assertTrue(decisions.all { it == Follow.Steady(1.0) })
        assertTrue(lock.locked)
    }

    @Test
    fun `one odd reading is not drifting, nor are a few`() {
        val lock = SyncLock(toleranceMs = 60)
        val gaps = List(40) { if (it % 7 == 3) 300L else 5L }
        assertTrue(looks(lock, gaps).all { it == Follow.Steady(1.0) })
    }

    @Test
    fun `a real gap is corrected once, with one speed held for as long as it takes, then back`() {
        val lock = SyncLock(toleranceMs = 60)
        // 150 ms behind and staying: after the first looks one nudge, no other change till it ends.
        val first = looks(lock, List(8) { 150L })
        val nudge = first.last() as Follow.Nudge
        assertTrue(nudge.speed > 1.0)
        assertEquals(1.05, nudge.speed, 1e-9)                       // 150 / 3 s is 5%, the cap
        // 150 ms at 5% takes 3 s, from the sixth look at 1.25 s to 4.25 s: held whatever the looks read
        val held = looks(lock, List(9) { 20L }, from = 8 * 250L)
        assertTrue(held.all { it == nudge })
        // and then the host's speed again, and the readings start again, quietly
        val after = looks(lock, List(30) { 0L }, from = 4_250L)
        assertEquals(Follow.Steady(1.0), after.first())
        assertTrue(after.all { it == Follow.Steady(1.0) })
    }

    @Test
    fun `a gap past the seek line is a jump on the second look, not the first`() {
        val lock = SyncLock()
        assertEquals(Follow.Steady(1.0), lock.decide(10_900, 10_000, 1.0, 0))
        assertEquals(Follow.Seek(10_900), lock.decide(10_900, 10_000, 1.0, 250))
        assertFalse(lock.locked)
    }

    @Test
    fun `after a jump it does not jump again at once, unless the host is miles away`() {
        val lock = SyncLock()
        lock.sought(0)
        lock.decide(10_900, 10_000, 1.0, 1_000)
        assertTrue("no second jump within five seconds", lock.decide(10_900, 10_000, 1.0, 1_250) !is Follow.Seek)
        assertTrue(lock.decide(20_000, 10_000, 1.0, 1_500) is Follow.Seek)
        assertTrue(lock.decide(10_900, 10_000, 1.0, 5_500) is Follow.Seek)
    }

    @Test
    fun `the catch-up speed sets how hard it nudges`() {
        val gentle = looks(SyncLock(maxNudge = 0.01), List(8) { 200L }).last() as Follow.Nudge
        val quick = looks(SyncLock(maxNudge = 0.05), List(8) { 200L }).last() as Follow.Nudge
        assertEquals(1.01, gentle.speed, 1e-9)
        assertEquals(1.05, quick.speed, 1e-9)
    }

    @Test
    fun `the host's last words give a line that one odd word does not move`() {
        val line = HostLine()
        fun state(at: Long, pos: Long) = TogetherMessage.State("s", "", "", pos, true, 1.0, at)
        var newest = state(0, 1_000)
        for (i in 0 until 6) { newest = state(i * 2_000L, 1_000 + i * 2_000L + listOf(10, -8, 12, -11, 6, -5)[i]); line.hear(newest) }
        val before = line.target(newest, 12_000)
        newest = state(12_000, 13_000 + 120)                      // one word 120 ms off
        line.hear(newest)
        assertTrue("moved ${line.target(newest, 14_000) - 15_000}", abs(line.target(newest, 14_000) - 15_000) < 40)
        assertTrue(abs(before - 13_000) < 15)
        // a late word, older than the newest, is ignored
        assertFalse(line.hear(state(8_000, 9_000)))
        // a seek of the host starts the line again
        newest = state(14_000, 40_000)
        assertTrue(line.hear(newest))
        assertEquals(40_000L, line.target(newest, 14_000))
    }

    @Test
    fun `one lopsided pong moves the clock gap little`() {
        val gap = ClockGap()
        for (i in 0 until 5) gap.heard(askedAtMs = i * 1_000L, hostMs = i * 1_000L + 5_000 + 350, nowMs = i * 1_000L + 700)
        assertEquals(5_000L, gap.offsetMs)
        gap.heard(askedAtMs = 9_000, hostMs = 9_000 + 5_000 + 480, nowMs = 9_000 + 720)  // 130 ms off: answered fast, came back slow
        assertTrue(abs(gap.offsetMs - 5_000) < 20)
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
        // The guard sits among the auto load more conditions, beside the end of an album's (EndlessQueue).
        val autoLoad = service.substringAfter("if (autoLoadMore &&").substringBefore(") {")
        assertTrue(autoLoad.contains("together.value?.following != true &&"))
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
        // His word of 17 Oct 2026: the room's button is in the player's row and the menu stays last; since 19 Nov 2026
        // Connected's normal player has it by the title instead, and the row keeps it for a small player. Where in the
        // row is RoomButtonStaysTest's: right before the menu in a release, first with ROOM_AT_FREE_END.
        val classic = player.substringAfter("val classicButtons").substringBefore("\n            }\n")
        assertTrue(classic.indexOf("roomCircle()") in 0 until classic.indexOf("menuCircle()"))
        val connected = player.substringAfter("val quickActions").substringBefore("val controlsContent")
        assertTrue(connected.indexOf("R.string.together_in_room") in 0 until connected.indexOf("R.string.options"))
        assertFalse("no pill over the cover any more", player.contains("RoomButton("))
    }

    @Test
    fun `a pause while the room plays is the listener's, one while the room is paused is the room's`() {
        // Decided by the room's own state, not by a window of time after the room's commands: a
        // window long enough for a song to load swallowed a pause pressed in it, and a play.
        assertEquals(true, PauseHere.after(pausedHere = false, playWhenReady = false, roomPlaying = true))
        assertEquals(null, PauseHere.after(pausedHere = false, playWhenReady = false, roomPlaying = false))
        // Play while paused here goes back to the room, whether the room plays or not.
        assertEquals(false, PauseHere.after(pausedHere = true, playWhenReady = true, roomPlaying = true))
        assertEquals(false, PauseHere.after(pausedHere = true, playWhenReady = true, roomPlaying = false))
        // Nothing changes otherwise: the room's own play, or a pause on top of a pause.
        assertEquals(null, PauseHere.after(pausedHere = false, playWhenReady = true, roomPlaying = true))
        assertEquals(null, PauseHere.after(pausedHere = true, playWhenReady = false, roomPlaying = true))
    }

    @Test
    fun `the listener's play and pause are not held up by the room's own commands`() {
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        val played = session.substringAfter("private fun listenerPlayed(").substringBefore("\n    }\n")
        assertTrue(played.contains("PauseHere.after("))
        assertFalse("no window of time decides it", played.contains("ownCommandUntil"))
        // And a follower found playing while marked paused here is back with the room.
        assertTrue(session.contains("if (pausedHere && player.playWhenReady)"))
    }
}


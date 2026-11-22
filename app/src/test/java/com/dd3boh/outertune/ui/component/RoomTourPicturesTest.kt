/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pictures of How rooms work move the way the (i) pictures do: nothing jumps, a playhead never seen to leap from
 * the end of its line back to its start, and every loop ends where the next begins. And each shows what its page
 * says, in that order: control arrives before the song chosen, a pause holds still, Catch up ends in step.
 */
class RoomTourPicturesTest {

    /** One frame of a screen that draws 60 a second, the slowest there is. */
    private val frame = 1f / 60f

    /** Every 240th of a second over [seconds], seams and all. */
    private fun moments(seconds: Float) = generateSequence(0f) { it + 1f / 240f }.takeWhile { it < seconds }.toList()

    /** The most a shown value may change in one frame: a fade of 0.2 s, the quickest here, eased. */
    private val most = 0.13f

    private fun smooth(name: String, seconds: Float, value: (Float) -> Float) {
        for (at in moments(seconds)) {
            val step = abs(value(at + frame) - value(at))
            assertTrue("$name jumps by $step at $at", step <= most)
        }
    }

    /** A playhead may jump only where it does not show: at a change of song, faded out on both sides of it. */
    private fun headHidesItsJumps(name: String, seconds: Float, head: (Float) -> Float, shown: (Float) -> Float) {
        smooth("$name's showing", seconds, shown)
        for (at in moments(seconds)) {
            val step = abs(head(at + frame) - head(at))
            if (step > 0.05f) assertTrue("$name jumps by $step at $at while it shows", shown(at) < 0.02f && shown(at + frame) < 0.02f)
        }
    }

    @Test
    fun `the ease has no corner and stays between its ends`() {
        assertEquals(0f, tourEase(-1f), 0f)
        assertEquals(0f, tourEase(0f), 0f)
        assertEquals(1f, tourEase(1f), 0f)
        assertEquals(1f, tourEase(2f), 0f)
        // Flat at both ends: the first and last hundredth move by almost nothing.
        assertTrue(tourEase(0.01f) < 0.001f)
        assertTrue(1f - tourEase(0.99f) < 0.001f)
    }

    @Test
    fun `the first picture fills the room once, and the three phones then change song together`() {
        for (guest in 0..1) {
            assertEquals(0f, stepLit(guest, 0f), 0f)
            assertEquals(1f, stepLit(guest, STEP_SONG - 0.5f), 0f)
            // In step with the host by the time the song changes, and from then on.
            for (at in moments(4 * STEP_SONG).filter { it > STEP_SONG - 0.5f }) {
                assertEquals("guest $guest at $at", stepHead(at), stepGuestHead(guest, at), 0f)
            }
            smooth("guest $guest's screen", 4 * STEP_SONG) { stepLit(guest, it) }
        }
        // A phone shows the song only once the invite has reached it.
        for (guest in 0..1) {
            assertEquals(0f, stepLit(guest, STEP_INVITE[guest] + STEP_FLIGHT), 0f)
            assertEquals(null, stepInvite(guest, STEP_INVITE[guest] + STEP_FLIGHT + 0.01f))
        }
        assertEquals(null, stepInvite(0, STEP_INVITE[0] - 0.01f))
        assertTrue(stepInvite(1, STEP_INVITE[1] + STEP_FLIGHT / 2) != null)
        headHidesItsJumps("the host's playhead", 4 * STEP_SONG, ::stepHead) { if (it < STEP_SONG) 1f - tourStep(it, STEP_SONG - DIP_OUT, DIP_OUT) else songShown(it.mod(STEP_SONG), STEP_SONG) }
        // Two songs take turns, so the picture goes on for ever with no seam.
        assertEquals(stepSong(0.5f), stepSong(0.5f + 2 * STEP_SONG))
        assertTrue(stepSong(0.5f) != stepSong(0.5f + STEP_SONG))
    }

    @Test
    fun `control goes to the guest and comes back, and each song comes after its chooser has control`() {
        assertEquals(0f, controlRing(0f), 0f)
        assertEquals(1f, controlRing(CONTROL_GUEST_SONG - CONTROL_PICK), 0f)
        assertEquals(1f, controlRing(CONTROL_GUEST_SONG), 0f)
        assertEquals(0f, controlRing(CONTROL_HOST_SONG - CONTROL_PICK), 0f)
        assertEquals(0f, controlRing(CONTROL_LOOP - 0.01f), 0f)
        assertEquals(1, controlSong(CONTROL_GUEST_SONG + 0.1f).first)
        assertEquals(0, controlSong(CONTROL_HOST_SONG + 0.1f).first)
        assertEquals(0, controlSong(0f).first)
        smooth("the ring", 3 * CONTROL_LOOP, ::controlRing)
        headHidesItsJumps("the playhead", 3 * CONTROL_LOOP, { controlSong(it).second / CONTROL_SONG_LENGTH }, ::controlShown)
        // A song is chosen by whoever has control: the guest's rises while the ring is round the guest.
        controlCard(CONTROL_GUEST_SONG - CONTROL_PICK / 2)!!.let { (who, _) -> assertEquals(1, who) }
        controlCard(CONTROL_HOST_SONG - CONTROL_PICK / 2)!!.let { (who, _) -> assertEquals(0, who) }
        assertEquals(null, controlCard(0.1f))
        // The loop closes: the same at its start and a loop later.
        for (at in moments(CONTROL_LOOP)) {
            assertEquals(controlRing(at), controlRing(at + CONTROL_LOOP), 1e-4f)
            assertEquals(controlSong(at).first, controlSong(at + CONTROL_LOOP).first)
        }
    }

    @Test
    fun `a song added goes in after the one playing, and plays next`() {
        assertEquals(0f, addRoom(0f), 0f)
        // Room is made before the song lands, and it lands before the song playing ends.
        assertEquals(1f, addRoom(ADD_LEAVE + ADD_FLIGHT), 0f)
        assertTrue(ADD_LEAVE + ADD_FLIGHT < ADD_NEXT)
        assertTrue(addFlight(ADD_LEAVE + ADD_FLIGHT) >= 1f)
        assertEquals(0f, addScroll(ADD_NEXT), 0f)
        assertEquals(1f, addScroll(ADD_NEXT + ADD_SCROLL), 0f)
        // Each loop begins with the song the loop before added, so it ends where the next begins.
        for (loop in 0..3) {
            val start = loop * ADD_LOOP + 0.01f
            assertEquals(addAdded(start), addPlaying(start + ADD_LOOP))
        }
        // The song added lands right after the one playing, and is the one playing once the queue has moved.
        val landed = addRows(ADD_LEAVE + ADD_FLIGHT + 0.5f).single { it.id == ADDED_ID }
        assertEquals(1f, landed.slot, 0f)
        assertEquals(0f, addRows(ADD_NEXT + ADD_SCROLL).single { it.id == ADDED_ID }.slot, 0f)
        smooth("the light on a song added", 3 * ADD_LOOP, ::addGlow)
        assertEquals(0f, addGlow(ADD_NEXT), 0f)
    }

    @Test
    fun `no song in the queue ever jumps, at the loop's seam least of all`() {
        for (at in moments(3 * ADD_LOOP)) {
            val now = addRows(at).associateBy { it.id }
            val next = addRows(at + frame).associateBy { it.id }
            for (id in now.keys + next.keys) {
                val a = now[id]
                val b = next[id]
                // The song added takes its row in the frame its card lands there, the card the same size in the same place.
                if (a == null && b != null && addFlight(at) < 1f && addFlight(at + frame) >= 1f) {
                    assertEquals(1f, b.slot, 0f)
                    continue
                }
                val alpha = abs((b?.alpha ?: 0f) - (a?.alpha ?: 0f))
                assertTrue("song $id fades by $alpha at $at", alpha <= most)
                if (a != null && b != null && a.alpha > 0.02f && b.alpha > 0.02f) {
                    assertTrue("song $id moves ${b.slot - a.slot} rows at $at", abs(b.slot - a.slot) <= 0.05f)
                    assertTrue("song $id's names jump at $at", abs(b.words - a.words) <= most)
                    assertTrue("song $id's playhead shows by fits at $at", abs(b.shown - a.shown) <= most)
                    val headA = a.head
                    val headB = b.head
                    if (headA != null && headB != null && abs(headB - headA) > 0.05f) {
                        assertTrue("song $id's playhead jumps at $at while it shows", a.shown < 0.02f && b.shown < 0.02f)
                    }
                    assertEquals("song $id changes cover at $at", a.song, b.song)
                }
            }
        }
    }

    @Test
    fun `your pause holds still while the room goes on, play and Catch up bring you back in step`() {
        val paused = ownHead(OWN_PAUSE + OWN_STOP)
        assertEquals(paused, ownHead(OWN_PLAY - 0.01f), 1e-6f)
        assertTrue(ownRoomHead(OWN_PLAY - 0.01f) > paused + 0.1f)
        assertEquals(ownRoomHead(OWN_PLAY + OWN_GLIDE), ownHead(OWN_PLAY + OWN_GLIDE), 1e-5f)
        assertEquals(1f, ownSongMix(OWN_CATCH - 0.01f), 0f)
        assertEquals(ownRoomHead(OWN_CATCH + OWN_CATCH_GLIDE), ownHead(OWN_CATCH + OWN_CATCH_GLIDE), 1e-5f)
        assertEquals(0f, ownSongMix(OWN_LOOP - 0.01f), 0f)
        assertEquals(0f, ownAway(OWN_LOOP - 0.01f), 0f)
        assertEquals(1f, ownLinked(0.01f), 1e-5f)
        assertEquals(1f, ownLinked(OWN_LOOP - 0.01f), 1e-5f)
        for (f in listOf(::ownSongMix, ::ownLinked, ::ownAway, ::ownPauseSign, { t: Float -> ownCatchSign(t).first })) {
            smooth("a part of the fourth picture", 3 * OWN_LOOP, f)
            for (at in moments(OWN_LOOP)) assertTrue(f(at) in 0f..1f)
        }
        headHidesItsJumps("your playhead", 3 * OWN_LOOP, ::ownHead, ::ownHeadShown)
        // The room's song changes at the loop's end, and the room's and yours together.
        assertTrue(ownRoomSong(0.5f) != ownRoomSong(0.5f + OWN_LOOP))
        assertEquals(ownRoomSong(0.5f), ownRoomSong(0.5f + 2 * OWN_LOOP))
    }

    @Test
    fun `with animations off each picture holds a moment that says what its page says`() {
        val inStep = stillMoment(RoomTourScene.IN_STEP)
        assertEquals(1f, stepLit(0, inStep), 0f)
        assertEquals(1f, stepLit(1, inStep), 0f)
        assertEquals(0f, controlRing(stillMoment(RoomTourScene.CONTROL)), 0f)
        val added = stillMoment(RoomTourScene.ADD)
        assertTrue(addFlight(added) >= 1f && addScroll(added) == 0f)
        assertEquals(1f, ownSongMix(stillMoment(RoomTourScene.OWN)), 0f)
    }
}

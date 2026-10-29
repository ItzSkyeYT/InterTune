/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import com.dd3boh.outertune.recap.RecapSoundPlan.Step.PauseTheirs
import com.dd3boh.outertune.recap.RecapSoundPlan.Step.ResumeTheirs
import com.dd3boh.outertune.recap.RecapSoundPlan.Step.SlideIn
import com.dd3boh.outertune.recap.RecapSoundPlan.Step.Stop
import com.dd3boh.outertune.recap.RecapSoundPlan.Step.TakeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Recap's sound as decided on 25 Oct 2026 (RecapSound): the listener's music pauses at Open
 * Recap and carries on at the close, each page's song takes over from the one before and keeps
 * playing, and only the last page's song goes on in their queue. Made-up songs; pages as a full
 * year's story has them (RecapPlanTest): since, minutes, hour, streak, artists, only here, the
 * countdown's four, number one, the card, thank you.
 */
class RecapSoundPlanTest {
    private val songs = listOf("first", "long", "evening", "streaky", "one", "found", "five", "four", "three", "two", "one", "one", "first")
    private val last = songs.lastIndex

    private fun opened(theirs: TheirMusic = TheirMusic.PLAYING) = RecapSoundPlan().also { it.open(theirs, songs.size) }

    /** The story played through to [page], one page at a time. */
    private fun RecapSoundPlan.through(page: Int) = (0..page).flatMap { show(it, songs[it]) }

    @Test
    fun `the listener's music pauses when the story is opened, and only if it was playing`() {
        assertEquals(listOf(PauseTheirs), RecapSoundPlan().open(TheirMusic.PLAYING, songs.size))
        assertEquals(emptyList<RecapSoundPlan.Step>(), RecapSoundPlan().open(TheirMusic.PAUSED, songs.size))
        assertEquals(emptyList<RecapSoundPlan.Step>(), RecapSoundPlan().open(TheirMusic.NONE, songs.size))
        // Once: the window drawn again opens nothing more.
        val plan = opened()
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.open(TheirMusic.PAUSED, songs.size))
        assertEquals(TheirMusic.PLAYING, plan.theirs)
    }

    @Test
    fun `nothing plays before the story is opened, nor after it is closed`() {
        val plan = RecapSoundPlan()
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.show(0, "first"))
        // Closed from the start screen: nothing was touched, nothing is.
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.close(null))
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.open(TheirMusic.PLAYING, songs.size))
        val after = opened()
        after.close(null)
        assertFalse(after.opened)
        assertEquals(emptyList<RecapSoundPlan.Step>(), after.show(0, "first"))
        assertEquals(emptyList<RecapSoundPlan.Step>(), after.close(null))
    }

    @Test
    fun `each page's song takes over from the one before, from its start point`() {
        val plan = opened()
        assertEquals(listOf(TakeOver(0, "first")), plan.show(0, "first"))
        assertEquals(listOf(TakeOver(1, "long")), plan.show(1, "long"))
        assertEquals(listOf(TakeOver(2, "evening")), plan.show(2, "evening"))
        assertEquals("evening", plan.playing)
        // Every step of the countdown is a song of its own.
        val countdown = (3..9).flatMap { plan.show(it, songs[it]) }
        assertEquals(listOf("streaky", "one", "found", "five", "four", "three", "two"), countdown.map { (it as TakeOver).songId })
    }

    @Test
    fun `a song that is the next page's too keeps playing, on or back, and on the same page drawn again`() {
        val plan = opened()
        plan.through(10)
        assertEquals("one", plan.playing)
        // Number one, then the year's card: the same song plays on.
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.show(11, "one"))
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.show(10, "one"))
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.show(10, "one"))
        // Back to number two: its song again, from its start.
        assertEquals(listOf(TakeOver(9, "two")), plan.show(9, "two"))
    }

    @Test
    fun `watch again starts the first song from its start, though the last page played it`() {
        val plan = opened()
        plan.through(last)
        assertEquals("first", plan.playing)
        assertEquals(listOf(TakeOver(0, "first")), plan.show(0, "first"))
    }

    @Test
    fun `a page without a song leaves the one playing as it is`() {
        val plan = opened()
        plan.show(0, "first")
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.show(1, null))
        assertEquals("first", plan.playing)
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.show(2, "first"))
    }

    @Test
    fun `a song that played to its end starts again when its page comes up again`() {
        val plan = opened()
        plan.through(10)
        plan.ended("one")
        assertEquals(listOf(TakeOver(11, "one")), plan.show(11, "one"))
        // Another song's end changes nothing.
        plan.ended("two")
        assertEquals("one", plan.playing)
    }

    @Test
    fun `closed before the last page, the listener's music carries on, and nothing goes into their queue`() {
        val plan = opened(TheirMusic.PLAYING)
        plan.through(5)
        assertEquals(listOf(Stop, ResumeTheirs), plan.close(Sounding("found", 31_000)))
        // On the card, one page before the end: still early.
        val card = opened(TheirMusic.PLAYING).also { it.through(last - 1) }
        assertEquals(listOf(Stop, ResumeTheirs), card.close(Sounding("one", 70_000)))
    }

    @Test
    fun `music that was paused stays paused, and nothing playing stays so`() {
        val paused = opened(TheirMusic.PAUSED).also { it.through(3) }
        assertEquals(listOf(Stop), paused.close(Sounding("streaky", 12_000)))
        val none = opened(TheirMusic.NONE).also { it.through(3) }
        assertEquals(listOf(Stop), none.close(Sounding("streaky", 12_000)))
    }

    @Test
    fun `at the end the last song slides into the listener's queue as the song playing now, from where it is`() {
        for (theirs in TheirMusic.entries) {
            val plan = opened(theirs).also { it.through(last) }
            // Their queue carries on after it (MusicService.slideIn): it is not resumed now.
            assertEquals(listOf(Stop, SlideIn("first", 93_400)), plan.close(Sounding("first", 93_400)))
        }
    }

    @Test
    fun `at the end with nothing sounding, or muted, it is closing early`() {
        // Paused with its button, held, failed or run out: no sound to play on.
        val quiet = opened(TheirMusic.PLAYING).also { it.through(last) }
        assertEquals(listOf(Stop, ResumeTheirs), quiet.close(null))
        val muted = opened(TheirMusic.PLAYING).also { it.through(last); it.mute(true) }
        assertEquals(listOf(Stop, ResumeTheirs), muted.close(Sounding("first", 93_400)))
        // Muted, then sound on again: it plays on.
        val back = opened(TheirMusic.PAUSED).also { it.through(last); it.mute(true); it.mute(false) }
        assertEquals(listOf(Stop, SlideIn("first", 93_400)), back.close(Sounding("first", 93_400)))
        // Run out on the last page, then its song is not the one sounding.
        val ran = opened(TheirMusic.PAUSED).also { it.through(last); it.ended("first") }
        assertEquals(listOf(Stop), ran.close(Sounding("first", 200_000)))
    }

    @Test
    fun `a local file and a song with no heat map go through the story like any other`() {
        // Where they start is RecapHeat's (RecapHeatTest); to the plan they are songs, and a local
        // file last goes on in the queue as a streamed song would.
        val plan = RecapSoundPlan().also { it.open(TheirMusic.PLAYING, 3) }
        assertEquals(listOf(TakeOver(0, "no-heat-map")), plan.show(0, "no-heat-map"))
        assertEquals(listOf(TakeOver(1, "LA-local-file")), plan.show(1, "LA-local-file"))
        assertEquals(emptyList<RecapSoundPlan.Step>(), plan.show(2, "LA-local-file"))
        assertTrue(plan.opened)
        assertEquals(listOf(Stop, SlideIn("LA-local-file", 5_000)), plan.close(Sounding("LA-local-file", 5_000)))
    }
}

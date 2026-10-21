/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.pill

import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.Specs
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the line between two songs says (DESIGN.md 2.5): the move, the listener's own edit, or why
 * the change stays plain, and which of those wins when more than one holds.
 */
class PillsTest {

    private val a = PillSong(Specs.A_ID, albumId = "album-a", durationMs = 308_000)
    private val b = PillSong(Specs.B_ID, albumId = "album-b", durationMs = 361_000)
    private val pair = SongPair(a.id, b.id)

    private fun planner(auto: AutoChoice? = null, fits: Boolean = true, parts: Boolean = false) = object : PairPlanner {
        override fun auto(pair: SongPair) = auto
        override fun stillFits(spec: TransitionSpec) = fits
        override fun partsReady(pair: SongPair) = parts
    }

    @Test
    fun `a pair nothing is known about says Auto`() {
        assertEquals(Pill.Auto, Pills.of(a, b, saved = null, blocker = null, planner = PairPlanner.NOT_YET))
    }

    @Test
    fun `Auto's choice is named with its length`() {
        val pill = Pills.of(a, b, null, null, planner(AutoChoice.Mixed(Preset.FADE, PillLength.bars(8))))
        assertEquals(Pill.Move(Preset.FADE, PillLength.bars(8)), pill)
        val blend = Pills.of(a, b, null, null, planner(AutoChoice.Mixed(Preset.BLEND, PillLength.seconds(6.4))))
        assertEquals(Pill.Move(Preset.BLEND, PillLength.seconds(6.0)), blend)
    }

    @Test
    fun `a saved edit wears its preset, and its parts fall back to bands when they are not ready`() {
        assertEquals(Pill.Edited(Preset.FADE, PillLength.bars(8), bandsThisTime = false), Pills.of(a, b, Specs.fadeOnBands(), null, planner()))
        val parts = Specs.vocalsOverCustom()
        assertEquals(Pill.Edited(Preset.CUSTOM, PillLength.bars(8), bandsThisTime = true), Pills.of(a, b, parts, null, planner(parts = false)))
        assertEquals(Pill.Edited(Preset.CUSTOM, PillLength.bars(8), bandsThisTime = false), Pills.of(a, b, parts, null, planner(parts = true)))
    }

    @Test
    fun `a saved edit whose songs changed since needs a look`() {
        assertEquals(Pill.NeedsLook, Pills.of(a, b, Specs.fadeOnBands(), null, planner(fits = false)))
    }

    @Test
    fun `a plain change asked for says it was the listener's choice`() {
        val plain = Pills.plainSpec(pair, saved = null)
        assertEquals(Pill.Plain(PlainReason.YOUR_CHOICE), Pills.of(a, b, plain, null, planner()))
        assertTrue(Pill.Plain(PlainReason.YOUR_CHOICE).edited)
    }

    @Test
    fun `a setting that blocks every change wins over an edit, and the pair's own reasons win over a setting`() {
        assertEquals(Pill.Plain(PlainReason.SKIP_SILENCE), Pills.of(a, b, Specs.fadeOnBands(), PlainReason.SKIP_SILENCE, planner()))
        val short = b.copy(durationMs = 52_000)
        assertEquals(Pill.Plain(PlainReason.SHORT_SONG), Pills.of(a, short, Specs.fadeOnBands(), PlainReason.SKIP_SILENCE, planner()))
    }

    @Test
    fun `the pair's own reasons, as the guards and the planner refuse them`() {
        assertEquals(PlainReason.SAME_SONG, Pills.pairReason(a, a))
        assertEquals(PlainReason.SHORT_SONG, Pills.pairReason(a.copy(durationMs = 59_999), b))
        assertEquals(PlainReason.SHORT_SONG, Pills.pairReason(a, b.copy(durationMs = 30_000)))
        assertEquals(PlainReason.RUN_TOGETHER, Pills.pairReason(a, b.copy(albumId = "album-a")))
        // A length not known yet says nothing; the player knows it once it plays.
        assertNull(Pills.pairReason(a.copy(durationMs = -1), b))
        assertNull(Pills.pairReason(a.copy(albumId = ""), b.copy(albumId = "")))
        assertNull(Pills.pairReason(a, b))
    }

    @Test
    fun `shuffled, a saved edit is not used and the pill says what Auto does`() {
        val auto = AutoChoice.Mixed(Preset.RISE, PillLength.seconds(10.0))
        assertEquals(Pill.Move(Preset.RISE, PillLength.seconds(10.0)), Pills.of(a, b, Specs.fadeOnBands(), null, planner(auto), shuffled = true))
        assertEquals(Pill.Auto, Pills.of(a, b, Pills.plainSpec(pair, null), null, planner(), shuffled = true))
    }

    @Test
    fun `the player's state, in the guards' order`() {
        assertNull(Pills.blocker(PlayerFacts()))
        assertEquals(PlainReason.CASTING, Pills.blocker(PlayerFacts(casting = true, skipSilence = true)))
        assertEquals(PlainReason.REPEAT_ONE, Pills.blocker(PlayerFacts(repeatOne = true, speedChanged = true)))
        assertEquals(PlainReason.SPEED, Pills.blocker(PlayerFacts(speedChanged = true, skipSilence = true)))
        assertEquals(PlainReason.SKIP_SILENCE, Pills.blocker(PlayerFacts(skipSilence = true, spatial = true)))
        assertEquals(PlainReason.SPATIAL, Pills.blocker(PlayerFacts(spatial = true, decoder = true)))
        assertEquals(PlainReason.DECODER, Pills.blocker(PlayerFacts(decoder = true, sleepTimer = true)))
        assertEquals(PlainReason.SLEEP_TIMER, Pills.blocker(PlayerFacts(sleepTimer = true)))
        assertEquals(PlainReason.TOGETHER, Pills.blocker(PlayerFacts(together = true)))
        for (reason in PlainReason.entries) {
            val facts = PlayerFacts(
                casting = reason == PlainReason.CASTING, together = reason == PlainReason.TOGETHER,
                repeatOne = reason == PlainReason.REPEAT_ONE, speedChanged = reason == PlainReason.SPEED,
                skipSilence = reason == PlainReason.SKIP_SILENCE, spatial = reason == PlainReason.SPATIAL,
                decoder = reason == PlainReason.DECODER, sleepTimer = reason == PlainReason.SLEEP_TIMER,
            )
            assertEquals(reason.name, reason.everyChange, Pills.blocker(facts) == reason)
        }
    }

    @Test
    fun `the prototype's styles are named as the presets they stand for`() {
        assertEquals(AutoChoice.Mixed(Preset.FADE, PillLength.bars(8)), AutoChoice.of(Style.BEAT, bars = 8, seconds = 17.6))
        assertEquals(AutoChoice.Mixed(Preset.RISE, PillLength.seconds(10.0)), AutoChoice.of(Style.SMOOTH, bars = 0, seconds = 10.2))
        assertEquals(AutoChoice.Mixed(Preset.BLEND, PillLength.seconds(6.0)), AutoChoice.of(Style.BLEND, bars = 0, seconds = 6.0))
        assertEquals(AutoChoice.Mixed(Preset.CUT, PillLength.NONE), AutoChoice.of(Style.CUT, bars = 0, seconds = 0.5))
        assertEquals(AutoChoice.Plain(PlainReason.NO_FIT), AutoChoice.of(Style.GAPLESS, bars = 0, seconds = 0.0))
    }

    @Test
    fun `the planner's refusals, read as reasons`() {
        // Each of Planner.guard's lines, so a line reworded there is caught here.
        assertEquals(PlainReason.NOT_READY, Pills.reasonOfPlanner("the next song's opening was not read in time"))
        assertEquals(PlainReason.SHORT_SONG, Pills.reasonOfPlanner("this song is under a minute"))
        assertEquals(PlainReason.SHORT_SONG, Pills.reasonOfPlanner("the next song is under a minute"))
        assertEquals(PlainReason.SAME_SONG, Pills.reasonOfPlanner("the same song again"))
        assertEquals(PlainReason.RUN_TOGETHER, Pills.reasonOfPlanner("two songs of one album"))
        assertEquals(PlainReason.FORMATS, Pills.reasonOfPlanner("the songs' formats differ"))
        assertEquals(PlainReason.NO_FIT, Pills.reasonOfPlanner("the end of this song is silent"))
        assertEquals(PlainReason.NO_FIT, Pills.reasonOfPlanner("too little of this song is left to blend over; tempos 7.6% apart"))
    }

    @Test
    fun `Plain change for these two keeps an edit's moves, and is a spec that may be saved`() {
        val fresh = Pills.plainSpec(pair, saved = null)
        assertTrue(fresh.problems().joinToString(), fresh.isValid)
        assertTrue(fresh.plain)
        assertEquals(pair, fresh.pair)
        assertEquals(LaneSet.BANDS, fresh.lanes)
        assertEquals(TransitionSpec.PLAIN, fresh.label)

        val edit = Specs.vocalsOverCustom()
        val kept = Pills.plainSpec(edit.pair, saved = edit)
        assertEquals(edit.copy(plain = true), kept)
    }

    @Test
    fun `a queue started from a playlist keeps its link, and links of queues gone are dropped`() {
        var links = QueueLinks.with(emptySet(), queueId = 11, playlistId = "LP1", alive = setOf(11))
        assertEquals(mapOf(11L to "LP1"), QueueLinks.decode(links))
        links = QueueLinks.with(links, queueId = 12, playlistId = "LP=2", alive = setOf(11, 12))
        assertEquals(mapOf(11L to "LP1", 12L to "LP=2"), QueueLinks.decode(links))
        // The same queue started from another playlist, then from no playlist at all.
        links = QueueLinks.with(links, queueId = 11, playlistId = "LP3", alive = setOf(11, 12))
        assertEquals("LP3", QueueLinks.decode(links)[11])
        links = QueueLinks.with(links, queueId = 11, playlistId = null, alive = setOf(11, 12))
        assertFalse(11L in QueueLinks.decode(links))
        // A queue deleted since.
        links = QueueLinks.with(links, queueId = 13, playlistId = "LP4", alive = setOf(13))
        assertEquals(mapOf(13L to "LP4"), QueueLinks.decode(links))
        // Anything else in the set is not a link and is left out.
        assertEquals(emptyMap<Long, String>(), QueueLinks.decode(setOf("x", "=LP", "12=")))
    }

    @Test
    fun `the Mix row's line`() {
        assertEquals(MixRowLine.Off, MixRowLine.of(on = false, shuffled = false, blocker = PlainReason.SKIP_SILENCE))
        assertEquals(MixRowLine.On, MixRowLine.of(on = true, shuffled = false, blocker = null))
        assertEquals(MixRowLine.Shuffled, MixRowLine.of(on = true, shuffled = true, blocker = null))
        assertEquals(MixRowLine.Blocked(PlainReason.SKIP_SILENCE), MixRowLine.of(on = true, shuffled = true, blocker = PlainReason.SKIP_SILENCE))
    }
}

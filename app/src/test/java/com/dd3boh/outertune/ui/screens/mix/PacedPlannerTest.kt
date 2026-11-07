/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.pill.PairPlanner
import com.dd3boh.outertune.mix.pill.Pill
import com.dd3boh.outertune.mix.pill.PillSong
import com.dd3boh.outertune.mix.pill.Pills
import com.dd3boh.outertune.mix.pill.PlainReason
import com.dd3boh.outertune.playback.automix.AutomixPace
import com.dd3boh.outertune.playback.automix.AutomixSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The playlist's pills and the queue's line on a saved change this phone cannot hold in time: said
 * as playback decides it, "Plain · too far for this phone", from the same lead and the pace
 * playback keeps, read off the main thread.
 */
class PacedPlannerTest {

    private val dir = File("src/main/java/com/dd3boh/outertune")

    /**
     * An 8-bar fade at 128 BPM, bar 1 at 208 s of a 301.7 s song: its first frame about 95 s before
     * the end, past what the Pixel 5 holds in time (about 85 s), within what a fast phone does.
     */
    private val far = TransitionSpec(
        a = Anchor("a", 208_000.0, 128.0), b = Anchor("b", 1_000.0, 128.0), lanes = LaneSet.BANDS,
        bars = 8, beatMatch = true, preset = Preset.CUSTOM, moves = Presets.moves(Preset.FADE, LaneSet.BANDS, 8.0, 0.25)!!,
    )
    private val duration = 301_700L

    @Test
    fun `a saved change past this phone's limit is said too far, exactly as playback decides it`() {
        val pace = AutomixPace()
        val planner = PacedPlanner(PairPlanner.NOT_YET, pace)
        val lead = AutomixSchedule.savedLead(far, duration.toDouble(), pace)!!
        assertFalse(lead.fits)
        assertEquals(lead.fits, planner.heldInTime(far, duration))
        val a = PillSong("a", null, duration)
        val b = PillSong("b", null, 240_000)
        assertEquals(Pill.Plain(PlainReason.TOO_FAR), Pills.of(a, b, far, null, planner))
        // Moved near the end, it is held in time and wears its preset again.
        val near = far.copy(a = far.a.copy(atMs = 280_000.0))
        assertTrue(AutomixSchedule.savedLead(near, duration.toDouble(), pace)!!.fits)
        assertTrue(planner.heldInTime(near, duration))
        assertEquals(Pill.Edited(Preset.CUSTOM, com.dd3boh.outertune.mix.pill.PillLength.bars(8), bandsThisTime = false), Pills.of(a, b, near, null, planner))
        // A faster phone holds the far one.
        val fast = PacedPlanner(PairPlanner.NOT_YET, AutomixPace(holdSpeed = 80.0, skipSpeed = 80.0, analyseMsPerS = 5.0, renderMsPerS = 60.0))
        assertTrue(fast.heldInTime(far, duration))
        // A length not known yet says nothing, as the pair's other rules.
        assertTrue(planner.heldInTime(far, -1))
        // Everything else is the planner it wraps.
        assertEquals(PairPlanner.NOT_YET.auto(far.pair), planner.auto(far.pair))
    }

    @Test
    fun `the song's length as analysed, to the ms as playback has it, wins over the playlist's whole seconds`() {
        val pace = AutomixPace()
        val bands = Presets.onBands(far)
        val off = com.dd3boh.outertune.mix.engine.SpecPlan.firstChangeMs(bands) - far.a.atMs
        val lead = AutomixSchedule.savedLead(far, duration.toDouble(), pace)!!
        val reach = pace.reachMs(lead.spanMs, lead.entrySeconds)
        // The song as analysed, and a change placed 0.1 s inside this phone's limit, as the editor can.
        val exact = 301_412.0
        val edge = far.copy(a = far.a.copy(atMs = exact - (reach - 100) - off))
        assertTrue(AutomixSchedule.savedLead(edge, exact, pace)!!.fits)
        // The playlist knows the song to the second, 0.6 s longer here: on that alone it would be too far.
        val listed = 302_000L
        assertFalse(AutomixSchedule.savedLead(edge, listed.toDouble(), pace)!!.fits)
        assertFalse(PacedPlanner(PairPlanner.NOT_YET, pace).heldInTime(edge, listed))
        assertTrue(PacedPlanner(PairPlanner.NOT_YET, pace) { id -> if (id == "a") exact else null }.heldInTime(edge, listed))
        // Not analysed: the playlist's length.
        assertFalse(PacedPlanner(PairPlanner.NOT_YET, pace) { null }.heldInTime(edge, listed))
    }

    @Test
    fun `the pills read this phone's pace off the main thread, playback says the same reason, and a tap opens the editor`() {
        val playlist = File(dir, "ui/screens/mix/MixPlaylist.kt").readText()
        val start = playlist.indexOf("fun rememberMixPlanner")
        val io = playlist.indexOf("withContext(Dispatchers.IO)", start)
        val read = playlist.indexOf("AutomixPace.read(context.filesDir)", start)
        assertTrue("$start $io $read", start > 0 && io > start && read > io && read < playlist.indexOf("return planner", start))
        assertTrue("PacedPlanner(" in playlist)
        // The song's length as analysed, where its end was: what playback and the editor go by.
        assertTrue("tailEnds[id]?.songMs" in playlist)
        val controller = File(dir, "playback/automix/AutomixController.kt").readText()
        assertTrue("decide(t, AutoChoice.Plain(PlainReason.TOO_FAR))" in controller)
        // Where the band and Move closer are.
        assertTrue("onClick = { navController.openMixEditor(pair) }," in playlist)
        assertTrue("navController.openMixEditor(pair)" in File(dir, "ui/screens/mix/MixQueue.kt").readText())
    }
}

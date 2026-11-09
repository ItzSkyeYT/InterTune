/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.Copy
import com.dd3boh.outertune.playback.automix.MixLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * His ask of 9 Nov 2026: "the time for the change made to whatever track to propagate and be
 * played by the player is way too long, so as soon as a change is made it should be hearable by
 * the player and playhead". The preview takes an edit's stream over at the bar being heard as
 * soon as its frames there are worked out, never before and never with a gap, and the playhead
 * stays on its bar; playback is handed each edit as it is made, without waiting for the save.
 */
class EditHeardAtOnceTest {

    private val spec = TransitionSpec(
        a = Anchor("a", 108_000.0, 120.0), b = Anchor("b", 0.0, 120.0), lanes = LaneSet.BANDS,
        bars = 8, preset = Preset.CUSTOM, plain = true, moves = emptyMap(),
    )

    private val step = 1000.0 / 48_000 / spec.b.barMs

    /** A window of one level from bar [from] to [to]; streamed when [streamed], none of its chunks there yet. */
    private fun window(level: Short, streamed: Boolean, from: Double = -2.0, to: Double = 10.0): PreviewHeard {
        val n = ((to - from) / step).roundToInt()
        val progress = if (streamed) PreviewProgress((n + EnginePreview.CHUNK - 1) / EnginePreview.CHUNK) else null
        return PreviewHeard(PreviewSetup(spec, from, to, null), ShortArray(2 * n) { level }, n, { from + it * step }, { u -> ((u - from) / step).roundToInt().coerceIn(0, n) }, progress)
    }

    private fun playhead(): PreviewPlayhead {
        val copy = Copy(ShortArray(2 * 48_000 * 40) { 10_000 }, 48_000, 2, startMs = 90_000.0)
        return PreviewPlayhead(48_000, 2, PreviewSound(copy, copy, null))
    }

    private val block = ShortArray(EnginePreview.BLOCK * 2)
    private var written = 0L

    private fun PreviewPlayhead.play(h: PreviewHeard, seek: Double? = null): Boolean {
        var s = seek
        val ok = fill(block, h, { s.also { s = null } }, null, true, written) { _, _, _ -> }
        if (ok) written += EnginePreview.BLOCK
        return ok
    }

    @Test
    fun `an edit's stream is taken over at the bar heard once its frames there are ready, not before`() {
        val p = playhead()
        val before = window(10_000, streamed = false)
        repeat(8) { assertTrue(p.play(before, if (it == 0) 2.0 else null)) }
        val bar = p.lastU
        val edit = window(5_000, streamed = true)
        // Nothing of it there yet: the change as it was goes on, with no gap.
        assertTrue(p.play(edit))
        assertEquals(10_000, block[block.size - 2].toInt())
        assertTrue(p.current === before)
        // Its chunk at the bar heard, and the one after, worked out: taken over there.
        val at = edit.indexAt(p.lastU)
        for (c in at / EnginePreview.CHUNK..(at + 3 * EnginePreview.BLOCK) / EnginePreview.CHUNK) edit.progress!!.markDone(c)
        assertTrue(p.play(edit))
        assertTrue(p.current === edit)
        assertEquals(5_000, block[block.size - 2].toInt())
        // The playhead stayed where it was: one block on from the bar it was at.
        assertEquals(bar + 2 * EnginePreview.BLOCK * step, p.lastU, step)
    }

    @Test
    fun `a stream that falls behind gives way to the change before at the same bar, and asks for those frames`() {
        val p = playhead()
        val before = window(10_000, streamed = false)
        repeat(4) { p.play(before, if (it == 0) 2.0 else null) }
        val edit = window(5_000, streamed = true)
        val at = edit.indexAt(p.lastU)
        edit.progress!!.markDone(at / EnginePreview.CHUNK)
        assertTrue(p.play(edit))
        assertTrue(p.current === edit)
        // Played to the end of what is there.
        while (edit.readyAt(edit.indexAt(p.lastU), EnginePreview.BLOCK)) assertTrue(p.play(edit))
        val bar = p.lastU
        assertTrue(p.play(edit))
        assertTrue("back on the change before", p.current === before)
        assertEquals(bar + EnginePreview.BLOCK * step, p.lastU, 2 * step)
        assertEquals("asked for", edit.indexAt(bar) / EnginePreview.CHUNK, edit.progress!!.want)
    }

    @Test
    fun `with nothing before it, a stream starts where it is asked to once its frames are there`() {
        val p = playhead()
        val edit = window(5_000, streamed = true)
        assertFalse(p.play(edit, 3.0))
        val at = edit.indexAt(3.0)
        assertEquals(at / EnginePreview.CHUNK, edit.progress!!.want)
        edit.progress!!.markDone(at / EnginePreview.CHUNK)
        edit.progress!!.markDone(at / EnginePreview.CHUNK + 1)
        assertTrue(p.play(edit))
        assertEquals(3.0 + EnginePreview.BLOCK * step, p.lastU, 2 * step)
    }

    @Test
    fun `playback is handed each edit as it is made, Auto's as none, and the store again once the editor is gone`() {
        val pair = SongPair("a-${System.nanoTime()}", "b")
        val heard = ArrayList<SongPair>()
        val l: (SongPair) -> Unit = { heard += it }
        MixLive.listen(l)
        try {
            assertNull(MixLive[pair])
            val edited = spec.copy(plain = false, bars = 4)
            MixLive.edited(pair, edited)
            assertEquals(listOf(pair), heard)
            assertEquals(edited, MixLive[pair]!!.spec)
            // The same again is not a new edit.
            MixLive.edited(pair, edited)
            assertEquals(1, heard.size)
            MixLive.edited(pair, null)
            assertEquals(2, heard.size)
            assertNull(MixLive[pair]!!.spec)
            MixLive.closed(pair)
            assertNull(MixLive[pair])
        } finally {
            MixLive.stopListening(l)
        }
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the editor hands each edit over without waiting for the save, and playback plays it in place of the store`() {
        val model = code("ui/screens/mix/MixEditorViewModel.kt")
        val publish = model.substringAfter("fun publish() {").substringBefore("scheduleAutosave()")
        assertTrue("MixLive.edited(SongPair(fromId, toId), (e.saveAction(0) as? SaveAction.Keep)?.spec)" in publish)
        val controller = code("playback/automix/AutomixController.kt")
        val onBands = controller.substringAfter("private fun onBands(").substringBefore("latestSaved?.let")
        assertTrue(onBands.indexOf("live != null -> Result.success(live.spec)") in 0 until onBands.indexOf("savedChange(t.a.id, b.id)"))
        // Held and planned already: planned again from the edit and handed over in place of the plan before.
        val replan = controller.substringAfter("private fun replan(t: Transition) {").substringBefore("\n    }\n")
        assertTrue("onBands(t, r.held, r.ring, r.frames, tail, r.head, earliest" in replan)
        assertTrue("stage.offer(held, heldPlan, render.outgoing, render.incoming, replaces = true, ring = ring)" in controller)
        assertTrue("MixLive.listen(liveListener)" in controller)
        assertTrue("MixLive.stopListening(liveListener)" in controller.substringAfter("fun release() {"))
    }

    @Test
    fun `the preview streams each edit from the bar heard, the whole render only where an echo rings`() {
        val preview = code("ui/screens/mix/EnginePreview.kt")
        assertTrue("Preview.stream(alone, a, b, aAnalysis, levelA, levelB)" in preview)
        assertTrue("s.indexAt(if (running) position() else lastU) + leadFrames" in preview)
        assertTrue("return if (s.echoes) Streamed.WHOLE_TO_FOLLOW else Streamed.DONE" in preview)
    }
}

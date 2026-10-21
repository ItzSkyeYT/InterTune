/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The stored form of a change: what is saved reads back the same, and anything this build cannot
 * play (another version, a lane missing, a move a side cannot make) is not read at all, so the
 * pair plays Auto. And the clock every screen and the engine share.
 */
class TransitionSpecTest {

    private fun id(key: String) = LaneId.of(key)!!

    @Test
    fun `a spec on bands reads back the same`() {
        val spec = Specs.fadeOnBands()
        assertEquals(emptyList<String>(), spec.problems())
        assertEquals(spec, transitionSpecOf(spec.toJson()))
    }

    @Test
    fun `a custom spec on parts reads back the same, curves, mutes and fixes with it`() {
        val spec = Specs.vocalsOverCustom()
        assertEquals(emptyList<String>(), spec.problems())
        assertEquals(spec, transitionSpecOf(spec.toJson()))
    }

    @Test
    fun `a spec timed in seconds and a plain one read back the same`() {
        val seconds = Specs.fadeOnBands().copy(bars = 0, seconds = 6.0, beatMatch = false)
        assertEquals(seconds, transitionSpecOf(seconds.toJson()))
        val plain = Specs.fadeOnBands().copy(plain = true)
        assertEquals(plain, transitionSpecOf(plain.toJson()))
        assertEquals("plain", plain.label)
        assertEquals("fade", Specs.fadeOnBands().label)
    }

    @Test
    fun `the same spec is saved as the same text`() {
        val spec = Specs.vocalsOverCustom()
        val shuffled = spec.copy(moves = spec.moves.entries.reversed().associate { it.key to it.value })
        assertEquals(spec.toJson(), shuffled.toJson())
        assertEquals(spec.toJson(), transitionSpecOf(spec.toJson())!!.toJson())
    }

    @Test
    fun `another version is not read, so the pair plays Auto`() {
        val text = Specs.fadeOnBands().toJson()
        assertTrue(text.startsWith("{\"v\":1,"))
        assertNull(transitionSpecOf(text.replaceFirst("{\"v\":1,", "{\"v\":2,")))
        assertNull(transitionSpecOf(text.replaceFirst("{\"v\":1,", "{")))
    }

    @Test
    fun `what is not a spec is not read`() {
        for (text in listOf("", "null", "[]", "{}", "not json", "{\"v\":1}")) assertNull(text, transitionSpecOf(text))
        // A number written as a string is not a number.
        assertNull(transitionSpecOf(Specs.fadeOnBands().toJson().replace("\"bars\":8", "\"bars\":\"8\"")))
    }

    @Test
    fun `a lane nobody wrote is an error, never full`() {
        val spec = Specs.fadeOnBands()
        val missing = spec.copy(moves = spec.moves - id("B.lows"))
        assertTrue(missing.problems().any { "B.lows" in it })
        assertNull(transitionSpecOf(missing.toJson()))
        val otherSet = spec.copy(moves = spec.moves + (id("A.vocals") to Move(0.0, 1.0, How.FADE)))
        assertTrue(otherSet.problems().any { "A.vocals" in it })
        assertNull(transitionSpecOf(otherSet.toJson()))
    }

    @Test
    fun `a side only makes the moves it can`() {
        val spec = Specs.fadeOnBands()
        val echoIn = spec.copy(moves = spec.moves + (id("B.mids") to Move(0.0, 1.0, How.ECHO)))
        val fromStartOut = spec.copy(moves = spec.moves + (id("A.mids") to Move(0.0, 1.0, How.FROM_START)))
        assertNull(transitionSpecOf(echoIn.toJson()))
        assertNull(transitionSpecOf(fromStartOut.toJson()))
        assertNull(transitionSpecOf(spec.toJson().replace("\"how\":\"thin\"", "\"how\":\"swirl\"")))
    }

    @Test
    fun `the other rules`() {
        val spec = Specs.fadeOnBands()
        assertFalse(spec.copy(b = spec.b.copy(song = Specs.A_ID)).isValid)
        assertFalse(spec.copy(bars = 32).isValid)
        assertFalse(spec.copy(bars = 8, seconds = 6.0).isValid)
        assertFalse(spec.copy(bars = 0, seconds = 0.0).isValid)
        assertFalse(spec.copy(introTwice = 3).isValid)
        assertFalse(spec.copy(a = spec.a.copy(bpm = 0.0)).isValid)
        assertFalse(spec.copy(moves = spec.moves + (id("A.mids") to Move(4.0, -1.0, How.FADE))).isValid)
        assertFalse(spec.copy(curves = mapOf(id("A.mids") to LaneCurve(listOf(Key(2.0, 1.0), Key(1.0, 0.0))))).isValid)
        assertFalse(spec.copy(curves = mapOf(id("A.mids") to LaneCurve(listOf(Key(1.0, 1.5))))).isValid)
        assertFalse(spec.copy(muted = setOf(id("A.drums"))).isValid)
        for (bars in TransitionSpec.LENGTHS_BARS) assertTrue(spec.copy(bars = bars).isValid)
        for (seconds in TransitionSpec.LENGTHS_SECONDS) assertTrue(spec.copy(bars = 0, seconds = seconds).isValid)
    }

    @Test
    fun `fields this build does not know are passed over`() {
        val text = Specs.fadeOnBands().toJson().replaceFirst("{\"v\":1,", "{\"v\":1,\"later\":{\"x\":[1,2]},")
        assertEquals(Specs.fadeOnBands(), transitionSpecOf(text))
    }

    @Test
    fun `the design's own example reads, once it names its songs`() {
        // DESIGN.md section 5, as written there, with each side's song id added and none of the
        // fields that only later builds will add.
        val text = """
            { "v": 1, "preset": "custom", "from": "vocals_over", "bars": 8, "beatMatch": true, "lanes": "parts",
              "a": { "song": "wake-me-up", "at": 281340.0, "bpm": 111.0, "beats": 4, "source": "251:4820311:312" },
              "b": { "song": "after-hours", "at": 1210.0, "bpm": 109.0, "beats": 4, "source": "251:5791102:312" },
              "moves": {
                "A.vocals": { "u": 7.0, "len": 1.0, "how": "echo" },  "B.vocals": { "u": 8.0, "len": 0.0, "how": "cut" },
                "A.drums":  { "u": 2.0, "len": 0.0, "how": "cut" },   "B.drums":  { "u": 0.0, "len": 2.0, "how": "thin" },
                "A.bass":   { "u": 3.75, "len": 0.25, "how": "fade" }, "B.bass":  { "u": 3.75, "len": 0.25, "how": "fade" },
                "A.melody": { "u": 4.0, "len": 2.0, "how": "muffle" }, "B.melody": { "u": 5.0, "len": 1.0, "how": "fade" } },
              "curves": {}, "introTwice": 0, "startBar": 0 }
        """.trimIndent()
        val read = transitionSpecOf(text)
        assertNotNull(read)
        val spec = read!!
        assertEquals(SongPair("wake-me-up", "after-hours"), spec.pair)
        assertEquals(Preset.CUSTOM, spec.preset)
        assertEquals(Preset.VOCALS_OVER, spec.from)
        assertEquals(Move(7.0, 1.0, How.ECHO), spec.moves[id("A.vocals")])
        assertEquals(BeatFix.NONE, spec.a.fix)
    }

    @Test
    fun `lengths at 109 BPM are the design's`() {
        val spec = Specs.fadeOnBands()
        for ((bars, seconds) in listOf(2 to 4.4, 4 to 8.8, 8 to 17.6, 16 to 35.2)) {
            assertEquals("$bars bars", seconds, spec.copy(bars = bars).lengthMs / 1000, 0.05)
        }
        val timed = spec.copy(bars = 0, seconds = 6.0)
        assertEquals(6.0 / (240.0 / 109), timed.lengthBars, 1e-9)
        assertEquals(6000.0, timed.lengthMs, 1e-6)
    }

    @Test
    fun `beat matching plays A's own bars at B's tempo, within the planner's limits`() {
        val spec = Specs.fadeOnBands()
        // 109 over 111: Wake Me Up 1.8% slower, inside 4% at 8 bars.
        assertEquals(109.0 / 111.0, spec.tempoRatio(), 1e-12)
        assertTrue(spec.beatMatchAllowed())
        assertTrue(spec.beatMatched)
        // Eight bars of B take eight of A's own bars of A's music.
        assertEquals(8 * 240_000.0 / 111, spec.aMsAt(8.0) - spec.aMsAt(0.0), 1e-6)
        assertEquals(8 * 240_000.0 / 109, spec.bMsAt(8.0) - spec.bMsAt(0.0), 1e-6)
        assertEquals(spec.a.atMs, spec.aMsAt(0.0), 0.0)
        assertEquals(spec.b.atMs, spec.bMsAt(0.0), 0.0)

        // Off, A keeps its own speed: B's bar of time passes in A too, and A's bar lines drift.
        val off = spec.copy(beatMatch = false)
        assertFalse(off.beatMatched)
        assertEquals(8 * 240_000.0 / 109, off.aMsAt(8.0) - off.aMsAt(0.0), 1e-6)

        // At 118 BPM it would be 7.6%: refused, whatever the switch says.
        val far = spec.copy(a = spec.a.copy(bpm = 118.0))
        assertFalse(far.beatMatchAllowed())
        assertFalse(far.beatMatched)
        // 6% is allowed up to 4 bars, 4% beyond.
        val five = spec.copy(a = spec.a.copy(bpm = 109.0 * 1.05))
        assertTrue(five.copy(bars = 4).beatMatchAllowed())
        assertFalse(five.copy(bars = 8).beatMatchAllowed())
        assertFalse(spec.copy(bars = 0, seconds = 8.0).beatMatchAllowed())
    }

    @Test
    fun `a tempo twice or half the other is folded`() {
        val spec = Specs.fadeOnBands()
        assertEquals(1.0, spec.copy(a = spec.a.copy(bpm = 140.0), b = spec.b.copy(bpm = 70.0)).tempoRatio(), 1e-12)
        assertEquals(1.0, spec.copy(a = spec.a.copy(bpm = 70.0), b = spec.b.copy(bpm = 140.0)).tempoRatio(), 1e-12)
        val folded = spec.copy(a = spec.a.copy(bpm = 218.0))
        assertTrue(abs(1 - folded.tempoRatio()) < 1e-12)
        // At 218 into 109, A plays two of its own bars for each of B's, which take the same time.
        assertEquals(8 * 2 * 240_000.0 / 218, folded.aMsAt(8.0) - folded.aMsAt(0.0), 1e-6)
    }

    @Test
    fun `lanes and keys`() {
        assertEquals(listOf("A.lows", "A.mids", "A.highs", "B.lows", "B.mids", "B.highs"), LaneSet.BANDS.ids.map { it.key })
        assertEquals(8, LaneSet.PARTS.ids.size)
        assertNull(LaneId.of("C.lows"))
        assertNull(LaneId.of("A.cowbell"))
        assertNull(LaneId.of("lows"))
        assertEquals(LaneId(Side.B, Lane.VOCALS), LaneId.of("B.vocals"))
        assertEquals(
            listOf("custom", "auto", "vocals_over", "beat_under", "drum_swap", "riff_rise", "fade", "rise", "blend", "echo_out", "cut"),
            Preset.entries.map { it.key },
        )
    }
}

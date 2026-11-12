/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import com.dd3boh.outertune.mix.auto.AutoMoves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The Simple level's rows against the spec: every choice of rows writes a valid change and reads
 * back as itself, every preset reads as its own rows, a row changed on a preset leaves it, and a
 * change the rows cannot write says "Set in Advanced" in the rows it differs in.
 */
class SimpleRowsTest {

    private val rules = Preset.entries.filter { it != Preset.AUTO && it != Preset.CUSTOM }

    /** A spec on [lanes] whose change is [l] bars long with [beatsPerBar] beats to the bar: in bars, or timed in seconds when [l] is not a length in bars. */
    private fun base(lanes: LaneSet, l: Double, beatsPerBar: Int = 4): TransitionSpec {
        val b = Specs.b.copy(bpm = TransitionSpec.NOMINAL_BPM, beatsPerBar = beatsPerBar)
        val a = Specs.a.copy(bpm = TransitionSpec.NOMINAL_BPM, beatsPerBar = beatsPerBar)
        val whole = l == Math.rint(l)
        return TransitionSpec(
            a = a, b = b, lanes = lanes,
            bars = if (whole) l.toInt() else 0,
            seconds = if (whole) 0.0 else l * b.barMs / 1000,
            preset = Preset.CUSTOM,
            moves = emptyMap(),
        )
    }

    /** Every place the rows are tried: both lane sets, the lengths offered in bars, one timed in seconds, and three beats to the bar. */
    private val cases: List<TransitionSpec> = buildList {
        for (lanes in LaneSet.entries) {
            for (l in listOf(2.0, 4.0, 8.0, 16.0, 2.5)) add(base(lanes, l))
            add(base(lanes, 8.0, beatsPerBar = 3))
        }
    }

    private fun where(s: TransitionSpec) = "${s.lanes.key}, ${s.lengthBars} bars of ${s.b.beatsPerBar}"

    @Test
    fun `every choice of rows writes a valid change with a move on every lane`() {
        for (s in cases) {
            val all = SimpleRows.all(s.lanes)
            assertEquals(if (s.lanes == LaneSet.PARTS) 3 * 3 * 4 * 2 + 3 else 3 * 3 * 4 * 2, all.size)
            for (rows in all) {
                val written = rows.write(s)
                assertNotNull("$rows on ${where(s)}", written)
                assertEquals("$rows on ${where(s)}: ${written!!.problems()}", emptyList<String>(), written.problems())
                assertEquals(s.lanes.ids.toSet(), written.moves.keys)
            }
        }
    }

    @Test
    fun `every choice of rows reads back as itself, and no two write the same change`() {
        for (s in cases) {
            val seen = HashMap<String, SimpleRows>()
            for (rows in SimpleRows.all(s.lanes)) {
                val written = rows.write(s)!!
                assertEquals("$rows on ${where(s)}", SimpleRead.of(rows), SimpleRows.read(written))
                val key = written.moves.entries.sortedBy { it.key.key }.joinToString { "${it.key}=${it.value}" }
                seen.put(key, rows)?.let { other -> throw AssertionError("$rows and $other write the same change on ${where(s)}") }
            }
        }
    }

    @Test
    fun `every preset reads back as its own rows, and its rows write that preset exactly`() {
        for (s in cases) for (p in rules) {
            if (p.needsParts && s.lanes != LaneSet.PARTS) {
                assertNull(SimpleRows.of(p, s.lanes))
                continue
            }
            val own = SimpleRows.of(p, s.lanes)
            assertNotNull("$p on ${where(s)}", own)
            val preset = Presets.apply(s, p)
            assertEquals("$p on ${where(s)}", SimpleRead.of(own!!), SimpleRows.read(preset))
            assertEquals("$p on ${where(s)}", preset.moves, own.write(s)!!.moves)
            assertEquals("$p on ${where(s)}", p, Presets.match(own.write(s)!!))
            assertEquals(p, own.preset(s.lanes))
        }
        // No two presets share their rows.
        for (lanes in LaneSet.entries) {
            val own = rules.mapNotNull { p -> SimpleRows.of(p, lanes)?.let { p to it } }
            assertEquals(own.size, own.map { it.second }.toSet().size)
        }
    }

    @Test
    fun `Fade, Rise, Blend and Cut are the rows' own rules, not hand-made`() {
        for (s in cases) for (p in listOf(Preset.FADE, Preset.RISE, Preset.BLEND, Preset.CUT)) {
            val rows = SimpleRows.of(p, s.lanes)!!
            assertEquals("$p on ${where(s)}", Presets.moves(p, s.lanes, s.lengthBars, s.beat), SimpleRows.composed(rows, s.lanes, s.lengthBars, s.beat))
        }
    }

    @Test
    fun `a row changed on a preset lights Custom, or the preset whose rows they now are, never the one it left`() {
        for (s in cases) for (p in rules) {
            val own = SimpleRows.of(p, s.lanes) ?: continue
            val changes = VolumeRow.entries.map { own.withVolume(it) } + EqRow.entries.map { own.withEq(it) } +
                FilterRow.entries.map { own.withFilter(it) } + EffectsRow.entries.map { own.withEffects(it) } +
                PartsRow.entries.mapNotNull { own.withParts(it, s.lanes) }
            for (changed in changes) {
                if (changed == own) continue
                val lit = Presets.match(changed.write(s)!!)
                assertNotEquals("$p, then $changed on ${where(s)}", p, lit)
                assertEquals("$p, then $changed on ${where(s)}", changed.preset(s.lanes) ?: Preset.CUSTOM, lit)
            }
        }
    }

    @Test
    fun `a part move sets the other rows, and setting any other row turns it off`() {
        val s = base(LaneSet.PARTS, 8.0)
        for (part in PartsRow.entries.filter { it != PartsRow.OFF }) {
            val rows = SimpleRows.of(Preset.FADE, LaneSet.PARTS)!!.withParts(part, LaneSet.PARTS)!!
            assertEquals(SimpleRows.of(part.preset!!, LaneSet.PARTS), rows)
            assertEquals(part.preset, Presets.match(rows.write(s)!!))
            assertEquals(PartsRow.OFF, rows.withFilter(FilterRow.LOW_PASS_IN).parts)
            assertEquals(PartsRow.OFF, rows.withVolume(VolumeRow.CROSSFADE).parts)
            // Turned off, the four rows stay as the part move had them.
            val off = rows.withParts(PartsRow.OFF, LaneSet.PARTS)!!
            assertEquals(rows.copy(parts = PartsRow.OFF), off)
        }
        // On bands there is no part move to set.
        assertNull(SimpleRows.of(Preset.FADE, LaneSet.BANDS)!!.withParts(PartsRow.VOCALS_OVER, LaneSet.BANDS))
        assertNull(SimpleRows.of(Preset.VOCALS_OVER, LaneSet.PARTS)!!.write(base(LaneSet.BANDS, 8.0)))
    }

    @Test
    fun `a handle moved in Advanced reads Set in Advanced in the row it belongs to, and only there`() {
        val fade = Presets.apply(base(LaneSet.BANDS, 8.0), Preset.FADE)
        val aHighs = LaneId(Side.A, Lane.HIGHS)
        val later = fade.copy(moves = fade.moves + (aHighs to fade.moves.getValue(aHighs).let { it.copy(u = it.u + 0.25) }))
        assertEquals(SimpleRead(null, EqRow.BASS_SWAP, FilterRow.NONE, EffectsRow.NONE, PartsRow.OFF), SimpleRows.read(later))
        // A how changed in the how bar: the filter row.
        val bMids = LaneId(Side.B, Lane.MIDS)
        val thin = fade.copy(moves = fade.moves + (bMids to fade.moves.getValue(bMids).copy(how = How.THIN)))
        assertEquals(SimpleRead(VolumeRow.OVERLAP, EqRow.BASS_SWAP, null, EffectsRow.NONE, PartsRow.OFF), SimpleRows.read(thin))
        // The lows: a swap moved a bar later.
        val lows = listOf(LaneId(Side.A, Lane.LOWS), LaneId(Side.B, Lane.LOWS))
        val swap = fade.copy(moves = fade.moves + lows.associateWith { fade.moves.getValue(it).let { m -> m.copy(u = m.u + 1) } })
        assertEquals(SimpleRead(VolumeRow.OVERLAP, null, FilterRow.NONE, EffectsRow.NONE, PartsRow.OFF), SimpleRows.read(swap))
        // A curve or a mute on a lane says nothing a row can.
        val curved = fade.copy(curves = mapOf(lows[0] to LaneCurve(listOf(Key(0.0, 1.0), Key(2.0, 0.0)))))
        assertNull(SimpleRows.read(curved).eq)
        assertEquals(VolumeRow.OVERLAP, SimpleRows.read(curved).volume)
        assertNull(SimpleRows.read(fade.copy(muted = setOf(LaneId(Side.A, Lane.MIDS)))).rows)
    }

    @Test
    fun `a change the rows cannot write never reads as a whole choice`() {
        val random = Random(21)
        val hows = How.entries
        var partly = 0
        var known = 0
        for (s in cases) {
            val all = SimpleRows.all(s.lanes).map { it.write(s)!! }
            repeat(150) {
                val start = all[random.nextInt(all.size)]
                val id = s.lanes.ids[random.nextInt(s.lanes.ids.size)]
                val how = hows.filter { it.fits(id.side) }.let { it[random.nextInt(it.size)] }
                val m = start.moves.getValue(id)
                val changed = start.copy(moves = start.moves + (id to Move(m.u + s.beat * random.nextInt(-4, 5), if (how == How.CUT) 0.0 else s.beat * random.nextInt(1, 9), how)))
                val read = SimpleRows.read(changed)
                val whole = read.rows
                if (whole != null) {
                    // Read whole only where the rows write exactly this.
                    assertTrue("$changed read as $whole", SimpleRows.sameMoves(whole.write(s)!!.moves, changed.moves))
                } else {
                    partly++
                    known += listOf(read.volume, read.eq, read.filter, read.effects).count { it != null }
                }
            }
        }
        // Most of these say "Set in Advanced" somewhere, and keep the other rows where they do.
        assertTrue("$partly", partly > 1000)
        assertTrue("$known of ${partly * 4}", known > partly)
    }

    @Test
    fun `Auto's own echo for far tempos reads as Echo out, the rest as not the rows'`() {
        val s = base(LaneSet.BANDS, 4.0).copy(moves = AutoMoves.echoOn(0.25))
        val read = SimpleRows.read(s)
        assertEquals(EffectsRow.ECHO_OUT, read.effects)
        assertNull(read.volume)
        assertNull(read.rows)
    }

    @Test
    fun `Set in Advanced rows are filled from a fallback before a row is written`() {
        val read = SimpleRead(null, EqRow.BASS_SWAP, null, EffectsRow.ECHO_OUT, PartsRow.OFF)
        val fallback = SimpleRows.of(Preset.CUT, LaneSet.BANDS)!!
        assertEquals(SimpleRows(VolumeRow.CUT, EqRow.BASS_SWAP, FilterRow.NONE, EffectsRow.ECHO_OUT), read.filled(fallback))
    }
}

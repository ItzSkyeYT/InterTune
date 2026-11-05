/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** The Volume row: how the two songs' levels pass each other. */
enum class VolumeRow {
    /** Both play in full, then A drops out: B comes in over the first half, A leaves over the second. */
    OVERLAP,

    /** One fades out as the other fades in, over the whole change. */
    CROSSFADE,

    /** Straight from one to the other, on the bar line halfway. */
    CUT,
}

/** The EQ row: what the two basslines do. */
enum class EqRow {
    /** The lows go with the rest of each song, so both basslines can play at once. */
    NONE,

    /** The basses trade places on one beat: halfway, or a bar before the end while B still opens up from muffled. */
    BASS_SWAP,

    /** A's bass leaves on the change's first beat, before the rest of A; B's comes in with the rest of B. */
    LOWS_OUT_FIRST,
}

/** The Filter row: a sweep on the way in, on the way out, or both. */
enum class FilterRow {
    NONE,

    /** B opens up from muffled: a low-pass on everything of B but its lows, open by the change's end. */
    LOW_PASS_IN,

    /** A thins out as it leaves: a high-pass on everything of A but its lows. */
    HIGH_PASS_OUT,

    BOTH,
}

/** The Effects row. */
enum class EffectsRow {
    NONE,

    /** A's voice (its mids, on bands) echoes away as A starts to leave, or over the beat before a cut. */
    ECHO_OUT,
}

/** The Parts row, with the parts download: off, or one of the three part moves, each a preset. */
enum class PartsRow(val preset: Preset?) {
    OFF(null),
    VOCALS_OVER(Preset.VOCALS_OVER),
    BEAT_UNDER(Preset.BEAT_UNDER),
    DRUM_SWAP(Preset.DRUM_SWAP),
}

/**
 * One choice in each row of the editor's Simple level (DESIGN.md, "Two levels"), written as the
 * moves of a change.
 *
 * A preset's own rows ([of]) are written by that preset's rule (Presets), so a preset set row by
 * row is the very change its chip writes, and lights it. Any other choice is written by the rows'
 * own rules ([composed]), which give Fade, Rise, Blend and Cut exactly; Echo out, Riff rise and
 * the three part moves are hand-made takes on the rows they are given here. A part move sets the
 * four other rows as it has them, and setting any of those turns it off.
 */
data class SimpleRows(
    val volume: VolumeRow,
    val eq: EqRow,
    val filter: FilterRow,
    val effects: EffectsRow,
    val parts: PartsRow = PartsRow.OFF,
) {
    fun withVolume(v: VolumeRow) = copy(volume = v, parts = PartsRow.OFF)
    fun withEq(e: EqRow) = copy(eq = e, parts = PartsRow.OFF)
    fun withFilter(f: FilterRow) = copy(filter = f, parts = PartsRow.OFF)
    fun withEffects(x: EffectsRow) = copy(effects = x, parts = PartsRow.OFF)

    /** A part move with the rows it sets, or these rows without one; null for a part move on bands. */
    fun withParts(p: PartsRow, lanes: LaneSet): SimpleRows? = when (val preset = p.preset) {
        null -> copy(parts = PartsRow.OFF)
        else -> of(preset, lanes)
    }

    /** Whether these rows can be written on [lanes]: a part move needs the parts. */
    fun fits(lanes: LaneSet): Boolean = parts == PartsRow.OFF || lanes == LaneSet.PARTS

    /** The preset these rows are on [lanes], if any: its own rows, or a part move. */
    fun preset(lanes: LaneSet): Preset? = when {
        !fits(lanes) -> null
        parts != PartsRow.OFF -> parts.preset
        else -> RULES.firstOrNull { of(it, lanes) == this }
    }

    /** Every lane's move for these rows on a change [lengthBars] long, [beat] being one beat in bars; null for a part move on bands. */
    fun moves(lanes: LaneSet, lengthBars: Double, beat: Double, facts: Presets.Facts = Presets.Facts()): Map<LaneId, Move>? {
        if (!fits(lanes)) return null
        preset(lanes)?.let { return Presets.moves(it, lanes, lengthBars, beat, facts) }
        return composed(this, lanes, lengthBars, beat)
    }

    /** [spec] with these rows' moves at its own length, its curves and mutes cleared, played mixed; null for a part move on bands. */
    fun write(spec: TransitionSpec, facts: Presets.Facts = Presets.Facts()): TransitionSpec? {
        val moves = moves(spec.lanes, spec.lengthBars, spec.beat, facts) ?: return null
        return spec.copy(moves = moves, curves = emptyMap(), muted = emptySet(), plain = false, preset = preset(spec.lanes) ?: Preset.CUSTOM)
    }

    companion object {
        private val RULES = Preset.entries.filter { it != Preset.AUTO && it != Preset.CUSTOM }

        // A band and its parts are named together, as Presets names them: the bass is on the
        // lows, the hats on the highs, the voice on the mids.
        private val LOW = arrayOf(Lane.LOWS, Lane.BASS)
        private val VOICE = arrayOf(Lane.MIDS, Lane.VOCALS)
        private val HATS = arrayOf(Lane.HIGHS, Lane.DRUMS)
        private val UPPER = arrayOf(Lane.MIDS, Lane.HIGHS, Lane.VOCALS, Lane.DRUMS, Lane.MELODY)

        /** A preset's own rows on [lanes]: null for Auto and Custom, and for a part move on bands. */
        fun of(preset: Preset, lanes: LaneSet): SimpleRows? = when (preset) {
            Preset.AUTO, Preset.CUSTOM -> null
            Preset.FADE -> SimpleRows(VolumeRow.OVERLAP, EqRow.BASS_SWAP, FilterRow.NONE, EffectsRow.NONE)
            Preset.RISE -> SimpleRows(VolumeRow.OVERLAP, EqRow.BASS_SWAP, FilterRow.BOTH, EffectsRow.NONE)
            Preset.BLEND -> SimpleRows(VolumeRow.CROSSFADE, EqRow.BASS_SWAP, FilterRow.NONE, EffectsRow.NONE)
            Preset.ECHO_OUT -> SimpleRows(VolumeRow.OVERLAP, EqRow.BASS_SWAP, FilterRow.NONE, EffectsRow.ECHO_OUT)
            Preset.CUT -> SimpleRows(VolumeRow.CUT, EqRow.NONE, FilterRow.NONE, EffectsRow.NONE)
            // The beat changes over on a bar line; before it B's riff rises muffled, after it the
            // basses take turns. On parts A's last line also echoes.
            Preset.RIFF_RISE -> SimpleRows(
                VolumeRow.CUT, EqRow.BASS_SWAP, FilterRow.LOW_PASS_IN,
                if (lanes == LaneSet.PARTS) EffectsRow.ECHO_OUT else EffectsRow.NONE,
            )
            Preset.VOCALS_OVER -> SimpleRows(VolumeRow.OVERLAP, EqRow.BASS_SWAP, FilterRow.NONE, EffectsRow.ECHO_OUT, PartsRow.VOCALS_OVER).takeIf { lanes == LaneSet.PARTS }
            Preset.BEAT_UNDER -> SimpleRows(VolumeRow.OVERLAP, EqRow.LOWS_OUT_FIRST, FilterRow.HIGH_PASS_OUT, EffectsRow.ECHO_OUT, PartsRow.BEAT_UNDER).takeIf { lanes == LaneSet.PARTS }
            Preset.DRUM_SWAP -> SimpleRows(VolumeRow.CUT, EqRow.LOWS_OUT_FIRST, FilterRow.NONE, EffectsRow.NONE, PartsRow.DRUM_SWAP).takeIf { lanes == LaneSet.PARTS }
        }

        /** Every choice of rows that can be made on [lanes]: the four rows freely, and on parts each part move with the rows it sets. */
        fun all(lanes: LaneSet): List<SimpleRows> = buildList {
            for (v in VolumeRow.entries) for (e in EqRow.entries) for (f in FilterRow.entries) for (x in EffectsRow.entries) {
                add(SimpleRows(v, e, f, x))
            }
            if (lanes == LaneSet.PARTS) for (p in PartsRow.entries) p.preset?.let { add(of(it, lanes)!!) }
        }

        /**
         * The rows' own rules, for any choice that is not a preset's. Positions are in bars from
         * the start of bar 1, as Presets has them; [l] is the change's length, [beat] one beat.
         */
        internal fun composed(r: SimpleRows, lanes: LaneSet, l: Double, beat: Double): Map<LaneId, Move> {
            val w = Presets.Writer(lanes, beat)
            val muffled = r.filter == FilterRow.LOW_PASS_IN || r.filter == FilterRow.BOTH
            val thin = r.filter == FilterRow.HIGH_PASS_OUT || r.filter == FilterRow.BOTH
            // The volume's shape for everything but the lows, before a filter or an echo.
            val (aOut, bIn) = when (r.volume) {
                VolumeRow.OVERLAP -> Move(l / 2, l / 2, How.FADE) to Move(0.0, l / 2, How.FADE)
                VolumeRow.CROSSFADE -> Move(0.0, l, How.FADE) to Move(0.0, l, How.FADE)
                VolumeRow.CUT -> Move(l / 2, 0.0, How.CUT) to Move(l / 2, 0.0, How.CUT)
            }
            // The echo first, so the rest of A leaves the voice to it: from where A starts to
            // leave over a quarter of the change at most, or over the beat before a cut.
            if (r.effects == EffectsRow.ECHO_OUT) {
                if (r.volume == VolumeRow.CUT) w.out(*VOICE, u = l / 2 - beat, len = beat, how = How.ECHO)
                else w.out(*VOICE, u = aOut.u, len = min(aOut.len, l / 4), how = How.ECHO)
            }
            when {
                !thin -> w.out(*UPPER, u = aOut.u, len = aOut.len, how = aOut.how)
                // A cut has no time to thin in: A thins out over the first half, up to the cut.
                r.volume == VolumeRow.CUT -> w.out(*UPPER, u = 0.0, len = l / 2, how = How.THIN)
                else -> w.out(*UPPER, u = aOut.u, len = aOut.len, how = How.THIN)
            }
            if (muffled) {
                // Muffled from where B comes in until the change's end, so it has opened by then.
                w.inn(*UPPER, u = bIn.u, len = l - bIn.u, how = How.MUFFLED)
            } else {
                // In an overlap B's hats come in first, thin, as Fade has them.
                if (r.volume == VolumeRow.OVERLAP) w.inn(*HATS, u = 0.0, len = l / 4, how = How.THIN)
                w.inn(*UPPER, u = bIn.u, len = bIn.len, how = bIn.how)
            }
            when (r.eq) {
                EqRow.NONE -> {
                    w.out(*LOW, u = aOut.u, len = aOut.len, how = aOut.how)
                    w.inn(*LOW, u = bIn.u, len = bIn.len, how = bIn.how)
                }
                // B's bass waits for B to open up, as Rise has it.
                EqRow.BASS_SWAP -> w.swap(*LOW, at = if (muffled && r.volume != VolumeRow.CUT) l - 1 else l / 2)
                EqRow.LOWS_OUT_FIRST -> {
                    w.out(*LOW, u = 0.0, len = beat, how = How.FADE)
                    w.inn(*LOW, u = bIn.u, len = bIn.len, how = bIn.how)
                }
            }
            return w.moves
        }

        /**
         * [spec] read as rows. A change some choice of rows writes reads as that choice. Any other
         * reads row by row: a row keeps a choice where its share of the moves is exactly what that
         * choice writes, together with the other rows kept, and is null ("Set in Advanced") where
         * not. So a change reads with no null row only when the rows write it exactly.
         */
        fun read(spec: TransitionSpec, facts: Presets.Facts = Presets.Facts()): SimpleRead {
            val lanes = spec.lanes
            val written = all(lanes).map { rows -> rows to rows.moves(lanes, spec.lengthBars, spec.beat, facts)!! }
            if (spec.curves.isEmpty() && spec.muted.isEmpty()) {
                written.firstOrNull { (_, moves) -> sameMoves(moves, spec.moves) }?.let { return SimpleRead.of(it.first) }
            }
            val adjusted = spec.curves.keys + spec.muted
            val mine = Row.entries.associateWith { it.signature(lanes, spec.moves, adjusted) }
            val theirs = written.map { (rows, moves) -> rows to Row.entries.associateWith { it.signature(lanes, moves, emptySet()) } }
            // A part move changed in Advanced is no longer the part move; anything else made on parts is off.
            val parts = when {
                lanes != LaneSet.PARTS -> PartsRow.OFF
                spec.from.needsParts -> null
                else -> PartsRow.OFF
            }
            val candidates = theirs.filter { (rows, _) -> parts == null || rows.parts == parts }
            val known = LinkedHashMap<Row, Any>()
            for (row in Row.entries) {
                val values = candidates.filter { (_, sig) -> sig[row] == mine[row] }.map { (rows, _) -> row.of(rows) }.toSet()
                if (values.size == 1) known[row] = values.single()
            }
            // Each row on its own may match a different choice: keep what one choice can say
            // together, giving up first the rows that hang on the others.
            while (known.isNotEmpty()) {
                val together = candidates.any { (rows, sig) -> known.all { (row, value) -> row.of(rows) == value && sig[row] == mine[row] } }
                if (together) break
                known.remove(GIVE_UP.first { it in known })
            }
            return SimpleRead(
                known[Row.VOLUME] as VolumeRow?,
                known[Row.EQ] as EqRow?,
                known[Row.FILTER] as FilterRow?,
                known[Row.EFFECTS] as EffectsRow?,
                parts,
            )
        }

        /** The rows that may be read on their own, each with its share of the moves. */
        private enum class Row {
            VOLUME, EQ, FILTER, EFFECTS;

            fun of(r: SimpleRows): Any = when (this) {
                VOLUME -> r.volume
                EQ -> r.eq
                FILTER -> r.filter
                EFFECTS -> r.effects
            }

            /**
             * This row's share of [moves]: the lows' whole moves for EQ; for Filter the how and for
             * Volume the timing of every other lane; for Effects which of A's lanes echo. A lane
             * with a curve or a mute ([adjusted]) says nothing any choice writes.
             */
            fun signature(lanes: LaneSet, moves: Map<LaneId, Move>, adjusted: Set<LaneId>): String = lanes.ids.mapNotNull { id ->
                val m = moves[id]
                val low = id.lane in LOW
                val mark = when {
                    this == EFFECTS -> if (id.side == Side.B) return@mapNotNull null else if (id !in adjusted && m?.how == How.ECHO) "echo" else "-"
                    (this == EQ) != low -> return@mapNotNull null
                    id in adjusted || m == null -> "adjusted"
                    this == EQ -> key(m)
                    this == FILTER -> m.how.key
                    else -> timing(m)
                }
                "$id:$mark"
            }.joinToString(" ")
        }

        /** Given up in this order when the rows read one by one cannot all be said at once: the rows that hang on the others first. */
        private val GIVE_UP = listOf(Row.EQ, Row.VOLUME, Row.FILTER, Row.EFFECTS)

        private fun q(x: Double): Long = (x * 1e6).roundToLong()

        /** Where a move is, as the editor compares them: a cut has no length, Keep and From the start no place. */
        private fun timing(m: Move): String = when (m.how) {
            How.KEEP, How.FROM_START -> m.how.key
            How.CUT -> "@${q(m.u)}"
            else -> "@${q(m.u)}+${q(m.len)}"
        }

        private fun key(m: Move): String = m.how.key + timing(m)

        /** Whether two sets of moves say the same thing, by the rule EditorPresets.sameMoves has. */
        internal fun sameMoves(a: Map<LaneId, Move>, b: Map<LaneId, Move>): Boolean {
            if (a.keys != b.keys) return false
            for ((id, y) in b) {
                val x = a[id] ?: return false
                if (x.how != y.how) return false
                if (y.how == How.FROM_START || y.how == How.KEEP) continue
                if (abs(x.u - y.u) > 1e-6) return false
                if (y.how != How.CUT && abs(x.len - y.len) > 1e-6) return false
            }
            return true
        }
    }
}

/**
 * A change read as the Simple level's rows: each row's choice, or null where the moves say more
 * than that row can, which the page shows as "Set in Advanced". [rows] is the whole choice when
 * no row is null.
 */
data class SimpleRead(
    val volume: VolumeRow?,
    val eq: EqRow?,
    val filter: FilterRow?,
    val effects: EffectsRow?,
    val parts: PartsRow?,
) {
    val rows: SimpleRows?
        get() = if (volume != null && eq != null && filter != null && effects != null && parts != null) SimpleRows(volume, eq, filter, effects, parts) else null

    /** These rows with [fallback]'s choice wherever this one has none. */
    fun filled(fallback: SimpleRows) = SimpleRows(
        volume ?: fallback.volume,
        eq ?: fallback.eq,
        filter ?: fallback.filter,
        effects ?: fallback.effects,
        parts ?: fallback.parts,
    )

    companion object {
        fun of(r: SimpleRows) = SimpleRead(r.volume, r.eq, r.filter, r.effects, r.parts)
    }
}

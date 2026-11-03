/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import com.dd3boh.outertune.mix.Planner
import kotlin.math.abs

/**
 * The two songs of a change, in play order: [from] goes out, [to] comes in. A saved change belongs
 * to the two recordings, so one pair is shared by every playlist where they meet. The ids are the
 * songs' own, never resolved through SongVersionMap: a cover or a remix has other timings, and
 * every anchor would be wrong on it.
 */
data class SongPair(val from: String, val to: String)

/** A is the song going out, B the one coming in. */
enum class Side { A, B }

/**
 * One layer of a song in a change. Bands are cut from the song on every phone: lows under
 * [Bands.LOWS_BELOW_HZ], highs over [Bands.HIGHS_ABOVE_HZ], mids between. Parts come from the
 * separation model once it is downloaded; melody is everything that is not voice, drums or bass.
 */
enum class Lane(val key: String) {
    LOWS("lows"),
    MIDS("mids"),
    HIGHS("highs"),
    VOCALS("vocals"),
    DRUMS("drums"),
    BASS("bass"),
    MELODY("melody"),
    ;

    companion object {
        fun of(key: String): Lane? = entries.firstOrNull { it.key == key }
    }
}

/** The lanes a change is made on. A change keeps the set it was made on. */
enum class LaneSet(val key: String, val lanes: List<Lane>) {
    BANDS("bands", listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)),
    PARTS("parts", listOf(Lane.VOCALS, Lane.DRUMS, Lane.BASS, Lane.MELODY)),
    ;

    /** Both songs' lanes, A's first, in the order the editor lists them. */
    val ids: List<LaneId> get() = Side.entries.flatMap { side -> lanes.map { LaneId(side, it) } }

    companion object {
        fun of(key: String): LaneSet? = entries.firstOrNull { it.key == key }
    }
}

/** Where the bands split, so the editor's colours and the engine's cut agree. */
object Bands {
    const val LOWS_BELOW_HZ = 120.0
    const val HIGHS_ABOVE_HZ = 6_000.0
}

/** One song's lane: "A.lows", "B.vocals" in the stored form. */
data class LaneId(val side: Side, val lane: Lane) {
    val key: String get() = "${side.name}.${lane.key}"

    override fun toString(): String = key

    companion object {
        fun of(key: String): LaneId? {
            val side = when (key.substringBefore('.', "")) {
                "A" -> Side.A
                "B" -> Side.B
                else -> return null
            }
            return LaneId(side, Lane.of(key.substringAfter('.')) ?: return null)
        }
    }
}

/**
 * How a lane leaves (A) or comes in (B) over its move. What each one sounds like is in [Shapes]
 * and [laneAt], so the editor draws and the engine plays the same thing.
 */
enum class How(val key: String, val forExit: Boolean, val forEntry: Boolean) {
    /** Equal power: cosine out, sine in. */
    FADE("fade", forExit = true, forEntry = true),

    /** Out: a low-pass closing from 20 kHz to 600 Hz over the move, the lane fading away over its second half. */
    MUFFLE("muffle", forExit = true, forEntry = false),

    /** In: a low-pass opening from 300 Hz to 20 kHz over the move, the lane coming up over its first quarter. */
    MUFFLED("muffled", forExit = false, forEntry = true),

    /** Out: a high-pass rising from 20 to 600 Hz, the lane fading away over its second half. In: held high-passed at 300 Hz, opening over the move's last beat. */
    THIN("thin", forExit = true, forEntry = true),

    /** Out: the lane goes into an echo for the move, its dry sound stops at the end and the echo rings out. */
    ECHO("echo", forExit = true, forEntry = false),

    /** On the beat, with only a few ms of fade. */
    CUT("cut", forExit = true, forEntry = true),

    /** Out: plays the whole change and is gone at its end. */
    KEEP("keep", forExit = true, forEntry = false),

    /** In: at full from B's first frame, as if there were no change. */
    FROM_START("start", forExit = false, forEntry = true),
    ;

    fun fits(side: Side): Boolean = if (side == Side.A) forExit else forEntry

    companion object {
        fun of(key: String): How? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One lane's exit (on A) or entry (on B): it starts at [u] and takes [len], both in bars of B
 * from the start of bar 1 of the change (negative before it), as stems/tools/render.py and the
 * mock count them. A [len] of 0 is a cut on [u].
 */
data class Move(val u: Double, val len: Double, val how: How) {
    val end: Double get() = u + len
}

/** A point of a curve: [value] at [u] bars. Between two points the value eases on a raised cosine. */
data class Key(val u: Double, val value: Double)

/**
 * A lane's curves from Adjust, which win over its move: its gain from 0 to 1, and a low-pass or
 * a high-pass cutoff in Hz where it has one (eased in log frequency). An empty filter list is no
 * filter.
 */
data class LaneCurve(
    val gain: List<Key>,
    val lowPassHz: List<Key> = emptyList(),
    val highPassHz: List<Key> = emptyList(),
)

/**
 * The listener's fix to a song's beat (Fix the beat). It belongs to the song, so Auto uses it too
 * (BeatFixStore), and each anchor keeps the fix it was set with, so a fix changed later shows the
 * anchor needs a look instead of moving it in silence.
 */
data class BeatFix(
    /** The tempo set by hand (tapped, halved or doubled), null while the analysis's own stands. */
    val bpm: Double? = null,
    /** Where the 1 is moved, in beats, later when positive. */
    val downbeatShift: Int = 0,
    /** The whole grid moved by this many ms, later when positive. */
    val nudgeMs: Int = 0,
    /** The copy the fix was made on, as in [Anchor.source]: a fix made on another encode is checked again. */
    val source: String = "",
    /**
     * A bar 1 the listener heard and set, in ms of the song's own clock, where the change was made:
     * the 1 the bars are counted from wherever the song's grid is laid, the phone's own analysis of
     * another stretch of the song included, so Auto puts the 1 where the listener did. [downbeatShift]
     * then counts from it. Null where the bars are the analysis's.
     */
    val oneMs: Double? = null,
) {
    val isNone: Boolean get() = bpm == null && downbeatShift == 0 && nudgeMs == 0 && oneMs == null

    companion object {
        val NONE = BeatFix()
    }
}

/**
 * Where bar 1 of the change sits in one song, in that song's own clock, and the grid it was
 * snapped to. Before a saved change is played, the anchor is held against the song's analysis of
 * the day: the same [source], [analysis] and [fix] play as saved; otherwise it snaps to the nearest
 * downbeat within half a beat, and with none, or a tempo moved by more than 1%, Auto plays and the
 * pill says the change needs a look.
 */
data class Anchor(
    val song: String,
    /** Bar 1's downbeat in this song, in ms from its first frame. */
    val atMs: Double,
    /** The song's tempo there, its fix applied; [TransitionSpec.NOMINAL_BPM] where no grid was trusted. */
    val bpm: Double,
    val beatsPerBar: Int = 4,
    /** The copy the grid was read from: "itag:bytes:encoderDelay" as the app writes it, "" when not known. */
    val source: String = "",
    /** The :mix analysis version the grid came from, 0 when not known. */
    val analysis: Int = 0,
    val fix: BeatFix = BeatFix.NONE,
) {
    /** One bar of this song at its own tempo, in ms. */
    val barMs: Double get() = beatsPerBar * 60_000.0 / bpm
}

/**
 * The presets, in the order the editor shows them, which never changes so no chip ever moves.
 * Riff rise works on bands too, on the mids.
 */
enum class Preset(val key: String, val needsParts: Boolean) {
    CUSTOM("custom", false),
    AUTO("auto", false),
    VOCALS_OVER("vocals_over", true),
    BEAT_UNDER("beat_under", true),
    DRUM_SWAP("drum_swap", true),
    RIFF_RISE("riff_rise", false),
    FADE("fade", false),
    RISE("rise", false),
    BLEND("blend", false),
    ECHO_OUT("echo_out", false),
    CUT("cut", false),
    ;

    companion object {
        fun of(key: String): Preset? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A loop of the next song's own bars, played in front of it: [bars] bars of B from its downbeat at
 * [fromMs] (B's own clock), [times] over, cut on B's bar lines with a few ms crossfaded at each
 * join so it neither clicks nor drifts off B's grid. A bar with no voice in it and no clash of key
 * with A (or drums alone) is what Auto picks; the editor offers 1, 2 or 4 bars as DJ apps do.
 */
data class IntroLoop(val bars: Int, val fromMs: Double, val times: Int = 1) {
    companion object {
        /** The loop lengths offered, in bars. */
        val BARS = listOf(1, 2, 4)

        /** How many times a loop may play in front of the song. */
        const val MAX_TIMES = 16
    }
}

/**
 * One change from a song into the next, as the editor makes it and as it is saved: everything
 * else (the engine's lane plan, the canvas, the preview, the pill) is worked out from this.
 *
 * Positions are in bars of B from the start of bar 1, the change's first downbeat, where A's
 * anchor meets B's. The change runs from 0 to [lengthBars]. With beat matching A is stretched to
 * B's tempo, its pitch kept, and B is never stretched, so B's seekbar and lyrics stay true.
 *
 * Every lane of [lanes] has a move on both sides: a lane nobody wrote is an error, never full.
 * A spec that breaks a rule here ([problems]) is not read at all, and the pair plays Auto.
 */
data class TransitionSpec(
    val a: Anchor,
    val b: Anchor,
    val lanes: LaneSet,
    /** The change's length in B's bars (the editor offers [LENGTHS_BARS]); 0 when it is timed in seconds. */
    val bars: Int,
    /** The change's length in seconds when [bars] is 0 (the editor offers [LENGTHS_SECONDS]); 0 otherwise. */
    val seconds: Double = 0.0,
    /** Asked for by the listener; it only happens where [beatMatchAllowed], see [beatMatched]. */
    val beatMatch: Boolean = true,
    /** The preset whose rule writes exactly these moves, CUSTOM when none does. */
    val preset: Preset,
    /** The preset the moves were first written by: [preset] itself, or what a custom edit began from. */
    val from: Preset = preset,
    /** "Plain change for these two": today's change, the moves kept for when it is turned back. */
    val plain: Boolean = false,
    val moves: Map<LaneId, Move>,
    /** Adjust's curves, which win over the lane's move. */
    val curves: Map<LaneId, LaneCurve> = emptyMap(),
    /** Lanes muted in this change: A's gone from bar 1, B's in only after the change. */
    val muted: Set<LaneId> = emptySet(),
    /** Play the intro twice: B's first 2 or 4 bars under A from its copy, then again live. 0 is off. */
    val introTwice: Int = 0,
    /**
     * A loop of B's own bars played in front of it, as a DJ app's loop: [IntroLoop.bars] bars of B
     * from [IntroLoop.fromMs], [IntroLoop.times] over, from bar 1 of the change under A, and then B
     * from its own bar 1. Null for none.
     */
    val introLoop: IntroLoop? = null,
    /** Where B starts playing, in ms of its own clock: 0, its first frame, unless a later start is allowed. */
    val bStartMs: Double = 0.0,
    /** When it was saved, in ms since the epoch; 0 for a spec not saved. */
    val savedAt: Long = 0,
    val version: Int = VERSION,
) {
    val pair: SongPair get() = SongPair(a.song, b.song)

    /** What the pill says without reading the moves: "plain", or the preset's key. */
    val label: String get() = if (plain) PLAIN else preset.key

    /** The change's length in B's bars, also when it is timed in seconds. */
    val lengthBars: Double get() = if (bars > 0) bars.toDouble() else seconds * 1000.0 / b.barMs

    /** The change's length in ms. */
    val lengthMs: Double get() = lengthBars * b.barMs

    /** One beat of B, in bars. */
    val beat: Double get() = 1.0 / b.beatsPerBar

    /**
     * B's tempo over A's, folded by two the way that brings it nearest 1, as a DJ would play a 140
     * into a 70: above 1, A has to play faster to meet B.
     */
    fun tempoRatio(): Double {
        var best = b.bpm / a.bpm
        for (m in doubleArrayOf(2.0, 0.5)) {
            val r = b.bpm / (a.bpm * m)
            if (abs(1 - r) < abs(1 - best)) best = r
        }
        return best
    }

    /** How far A's tempo may be moved at this length: 6% up to 4 bars, 4% beyond, as the planner allows. */
    fun beatMatchLimit(): Double = if (lengthBars <= 4.0) Planner.BEAT_MAX_SPEED else Planner.BEAT_EIGHT_BAR_MAX_SPEED

    /** Whether the two tempos are near enough to lock A to B at this length. Never on a change timed in seconds. */
    fun beatMatchAllowed(): Boolean = bars > 0 && abs(1 - tempoRatio()) <= beatMatchLimit() + 1e-9

    /** Whether A is stretched to B's beat in this change. */
    val beatMatched: Boolean get() = beatMatch && beatMatchAllowed()

    /** How many bars of the change B's loop in front of it takes: its bars, times over; 0 without one. */
    val loopBars: Double get() = introLoop?.let { (it.bars * it.times).toDouble() } ?: introTwice.toDouble()

    /**
     * Where bar position [u] of the change falls in B's own clock, in ms: inside B's loop, the bar
     * of the loop playing there; after it, B from its own bar 1.
     */
    fun bMsAt(u: Double): Double {
        val loop = loopBars
        if (loop <= 0.0) return b.atMs + u * b.barMs
        if (u >= 0.0 && u < loop) {
            val bars = introLoop?.bars ?: introTwice
            val from = introLoop?.fromMs ?: b.atMs
            return from + (u % bars) * b.barMs
        }
        return b.atMs + (u - loop) * b.barMs
    }

    /**
     * Where bar position [u] falls in A's own clock, in ms. Beat matched, A plays one of its own
     * bars (folded) for each bar of B; otherwise it plays at its own speed and its bars drift.
     */
    fun aMsAt(u: Double): Double = a.atMs + u * (if (beatMatched) b.barMs * tempoRatio() else b.barMs)

    /** Whatever breaks the rules of a spec, in a line each; empty for a spec that may be saved and played. */
    fun problems(): List<String> = buildList {
        if (version != VERSION) add("version $version, this build reads $VERSION")
        for ((name, anchor) in listOf("a" to a, "b" to b)) {
            if (anchor.song.isBlank()) add("$name has no song")
            if (!anchor.atMs.isFinite() || anchor.atMs < 0) add("$name's anchor is at ${anchor.atMs} ms")
            if (!anchor.bpm.isFinite() || anchor.bpm !in 20.0..400.0) add("$name's tempo is ${anchor.bpm}")
            if (anchor.beatsPerBar !in 2..12) add("$name has ${anchor.beatsPerBar} beats to the bar")
            anchor.fix.bpm?.let { if (!it.isFinite() || it !in 20.0..400.0) add("$name's fixed tempo is $it") }
        }
        if (a.song == b.song) add("a song into itself")
        when {
            bars > 0 && seconds != 0.0 -> add("timed in bars and in seconds at once")
            bars > 0 && bars > MAX_BARS -> add("$bars bars")
            bars == 0 && !(seconds.isFinite() && seconds > 0.0 && seconds <= MAX_SECONDS) -> add("$seconds s")
            bars < 0 -> add("$bars bars")
        }
        for (id in lanes.ids) {
            val move = moves[id]
            if (move == null) {
                add("no move for $id")
                continue
            }
            if (!move.how.fits(id.side)) add("$id cannot ${move.how.key}")
            if (!move.u.isFinite() || !move.len.isFinite() || move.len < 0) add("$id moves at ${move.u} over ${move.len}")
        }
        for (id in moves.keys - lanes.ids.toSet()) add("$id is not a lane of ${lanes.key}")
        for ((id, curve) in curves) {
            if (id !in lanes.ids) add("$id is not a lane of ${lanes.key}")
            if (curve.gain.isEmpty()) add("$id's curve has no points")
            if (!curve.gain.all { it.u.isFinite() && it.value in 0.0..1.0 }) add("$id's curve leaves 0 to 1")
            for ((name, keys) in listOf("gain" to curve.gain, "low-pass" to curve.lowPassHz, "high-pass" to curve.highPassHz)) {
                if (keys.zipWithNext().any { (p, q) -> q.u < p.u }) add("$id's $name points are out of order")
            }
            if (!(curve.lowPassHz + curve.highPassHz).all { it.u.isFinite() && it.value in 20.0..20_000.0 }) add("$id's filter leaves 20 Hz to 20 kHz")
        }
        for (id in muted) if (id !in lanes.ids) add("$id is not a lane of ${lanes.key}")
        if (introTwice !in INTRO_TWICE_BARS) add("intro twice over $introTwice bars")
        introLoop?.let { l ->
            if (l.bars !in IntroLoop.BARS) add("a loop of ${l.bars} bars")
            if (l.times !in 1..IntroLoop.MAX_TIMES) add("a loop played ${l.times} times")
            if (!l.fromMs.isFinite() || l.fromMs < 0) add("a loop from ${l.fromMs} ms")
            if (introTwice > 0) add("a loop and the intro twice at once")
        }
        if (!bStartMs.isFinite() || bStartMs < 0) add("B starts at $bStartMs ms")
    }

    val isValid: Boolean get() = problems().isEmpty()

    companion object {
        /** The stored form's version. A spec of another version is not read, and the pair plays Auto. */
        const val VERSION = 1

        /** What [label] says for "Plain change for these two". */
        const val PLAIN = "plain"

        /** The lengths the editor offers in bars, and in seconds without a trusted grid. 32 bars is not offered. */
        val LENGTHS_BARS = listOf(2, 4, 8, 16)
        val LENGTHS_SECONDS = listOf(4.0, 6.0, 8.0, 12.0)

        /** The longest a stored change may be: Auto may write other lengths than the editor offers. */
        const val MAX_BARS = 16
        const val MAX_SECONDS = 60.0

        /** Intro twice: off, or over 2 or 4 bars. */
        val INTRO_TWICE_BARS = listOf(0, 2, 4)

        /** The tempo an anchor carries where no grid was trusted: a 2 s bar, so handles snap to half seconds on quarter bars. */
        const val NOMINAL_BPM = 120.0
    }
}

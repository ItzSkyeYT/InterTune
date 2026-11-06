/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.pill

import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.roundToInt

/**
 * Why a change plays plain, as today's change, in the words its pill gives (DESIGN.md 2.5). One
 * type for the playlist's pills, the queue's line, the editor and AutoMix's guards, so every place
 * gives the same reason for the same change.
 */
enum class PlainReason {
    CASTING,
    TOGETHER,
    REPEAT_ONE,
    SAME_SONG,
    RUN_TOGETHER,
    SHORT_SONG,
    SPEED,
    SKIP_SILENCE,

    /** Head tracking, the surround upmix or Ambience: the held end would pass them long before it is heard. */
    SPATIAL,
    SLEEP_TIMER,

    /** The FFmpeg decoder, which the next song's opening is not read with. */
    DECODER,
    FORMATS,
    NOT_READY,
    NO_TIME,
    FROM_NEXT,

    /** The planner found no way to mix these two: every style was refused for its own reason. */
    NO_FIT,
    YOUR_CHOICE,
    ;

    /** Whether it holds for every change while it lasts, a setting or the player's state, rather than for one pair. */
    val everyChange: Boolean
        get() = when (this) {
            CASTING, TOGETHER, REPEAT_ONE, SPEED, SKIP_SILENCE, SPATIAL, SLEEP_TIMER, DECODER -> true
            else -> false
        }
}

/** How long a change is, as a pill says it: in bars, in whole seconds, or not at all for a cut. */
data class PillLength(val bars: Int, val seconds: Int) {
    companion object {
        val NONE = PillLength(0, 0)
        fun bars(bars: Int) = PillLength(bars, 0)

        /** Whole seconds: "Blend · 6 s", never "6.4 s". */
        fun seconds(seconds: Double) = PillLength(0, seconds.roundToInt())

        fun of(spec: TransitionSpec): PillLength = when {
            spec.preset == Preset.CUT -> NONE
            spec.bars > 0 -> bars(spec.bars)
            else -> seconds(spec.seconds)
        }
    }
}

/** One side of a pill's pair, as the playlist or the queue knows it. */
data class PillSong(
    val id: String,
    val albumId: String?,
    /** -1 while not known. */
    val durationMs: Long,
)

/** What the line between two songs says. The app turns each into words. */
sealed interface Pill {
    /** Whether it shows the pencil: the listener made this change. */
    val edited: Boolean get() = false

    /** Neither song is analysed yet, so nobody knows what Auto will do. */
    data object Auto : Pill

    /** Auto's choice, named. */
    data class Move(val preset: Preset, val length: PillLength) : Pill

    /** The listener's saved change; [bandsThisTime] when it was made on parts and they are not ready. */
    data class Edited(val preset: Preset, val length: PillLength, val bandsThisTime: Boolean) : Pill {
        override val edited: Boolean get() = true
    }

    /** A saved change whose songs have changed since: Auto plays until it is looked at. */
    data object NeedsLook : Pill {
        override val edited: Boolean get() = true
    }

    /** Today's change, and why. */
    data class Plain(val reason: PlainReason) : Pill {
        override val edited: Boolean get() = reason == PlainReason.YOUR_CHOICE
    }
}

/** What Auto would do between two songs, by the planner. */
sealed interface AutoChoice {
    data class Mixed(val preset: Preset, val length: PillLength) : AutoChoice
    data class Plain(val reason: PlainReason) : AutoChoice

    companion object {
        /**
         * The prototype's styles named as the presets they stand for until the band presets plan
         * on their own (DESIGN.md 3.7): Beat is Fade, Smooth is Rise, Blend is Blend, Cut is Cut,
         * and Gapless is plain.
         */
        fun of(style: Style, bars: Int, seconds: Double, why: PlainReason = PlainReason.NO_FIT): AutoChoice = when (style) {
            Style.BEAT -> Mixed(Preset.FADE, if (bars > 0) PillLength.bars(bars) else PillLength.seconds(seconds))
            Style.SMOOTH -> Mixed(Preset.RISE, PillLength.seconds(seconds))
            Style.BLEND -> Mixed(Preset.BLEND, PillLength.seconds(seconds))
            Style.CUT -> Mixed(Preset.CUT, PillLength.NONE)
            Style.GAPLESS -> Plain(why)
        }
    }
}

/**
 * Auto for a pair, behind an interface so the pills can be drawn before the analysis cache and the
 * band presets exist: until then [NOT_YET] knows nothing and every pair says Auto, as DESIGN.md 2.4
 * has a pair not analysed yet say.
 */
interface PairPlanner {
    /** Auto's choice for [pair] from what is known of both songs now, or null while either is not analysed. */
    fun auto(pair: SongPair): AutoChoice?

    /** Whether a saved change still fits its songs as they are analysed now; true while nothing says otherwise. */
    fun stillFits(spec: TransitionSpec): Boolean = true

    /** Whether a change made on parts has its parts this time; without them it plays on bands. */
    fun partsReady(pair: SongPair): Boolean = false

    companion object {
        val NOT_YET: PairPlanner = object : PairPlanner {
            override fun auto(pair: SongPair): AutoChoice? = null
        }
    }
}

/** What the player and the settings say about every change at once. */
data class PlayerFacts(
    val casting: Boolean = false,
    val together: Boolean = false,
    val repeatOne: Boolean = false,
    val speedChanged: Boolean = false,
    val skipSilence: Boolean = false,
    val spatial: Boolean = false,
    val decoder: Boolean = false,
    val sleepTimer: Boolean = false,
)

/** The grey line under the Mix switch on a playlist. */
sealed interface MixRowLine {
    data object Off : MixRowLine
    data object On : MixRowLine
    data object Shuffled : MixRowLine
    data class Blocked(val reason: PlainReason) : MixRowLine

    companion object {
        fun of(on: Boolean, shuffled: Boolean, blocker: PlainReason?): MixRowLine = when {
            !on -> Off
            blocker != null -> Blocked(blocker)
            shuffled -> Shuffled
            else -> On
        }
    }
}

object Pills {

    /**
     * The pill between [a] and [b]. First what holds for the pair whatever is saved (the same song,
     * a short song, an album running on), then what holds for every change ([blocker]), then the
     * listener's saved change, then Auto. A pill must name what would really play: a saved change
     * a setting keeps plain says that setting, not the edit. Shuffled, saved changes are not used,
     * as DESIGN.md 2.1 has it, so the pill says what Auto does.
     */
    fun of(
        a: PillSong,
        b: PillSong,
        saved: TransitionSpec?,
        blocker: PlainReason?,
        planner: PairPlanner,
        shuffled: Boolean = false,
    ): Pill {
        pairReason(a, b)?.let { return Pill.Plain(it) }
        blocker?.let { return Pill.Plain(it) }
        val pair = SongPair(a.id, b.id)
        val spec = saved?.takeIf { !shuffled && it.pair == pair }
        if (spec != null) {
            if (spec.plain) return Pill.Plain(PlainReason.YOUR_CHOICE)
            if (!planner.stillFits(spec)) return Pill.NeedsLook
            val bands = spec.lanes == LaneSet.PARTS && !planner.partsReady(pair)
            return Pill.Edited(spec.preset, PillLength.of(spec), bandsThisTime = bands)
        }
        return when (val auto = planner.auto(pair)) {
            null -> Pill.Auto
            is AutoChoice.Mixed -> Pill.Move(auto.preset, auto.length)
            is AutoChoice.Plain -> Pill.Plain(auto.reason)
        }
    }

    /**
     * What keeps this pair plain whatever is saved, as AutomixGuards and Planner.guard refuse it.
     * Two songs of one album are taken as running into each other, as the guards take them today.
     * A length not known yet says nothing: the player knows it once the song plays.
     */
    fun pairReason(a: PillSong, b: PillSong): PlainReason? = when {
        a.id == b.id -> PlainReason.SAME_SONG
        a.durationMs in 0 until Planner.MIN_SONG_MS || b.durationMs in 0 until Planner.MIN_SONG_MS -> PlainReason.SHORT_SONG
        a.albumId?.takeIf { it.isNotBlank() }?.let { it == b.albumId } == true -> PlainReason.RUN_TOGETHER
        else -> null
    }

    /** What keeps every change plain now, in the order AutomixGuards asks. */
    fun blocker(f: PlayerFacts): PlainReason? = when {
        f.casting -> PlainReason.CASTING
        f.together -> PlainReason.TOGETHER
        f.repeatOne -> PlainReason.REPEAT_ONE
        f.speedChanged -> PlainReason.SPEED
        f.skipSilence -> PlainReason.SKIP_SILENCE
        f.spatial -> PlainReason.SPATIAL
        f.decoder -> PlainReason.DECODER
        f.sleepTimer -> PlainReason.SLEEP_TIMER
        else -> null
    }

    /**
     * A reason for a gapless plan, from the planner's own line (Planner.guard and the refusals
     * after it). The lines are for the log, so this reads only the few that name a reason a pill
     * has; PillsTest holds each of them, so rewording one there is caught.
     */
    fun reasonOfPlanner(line: String): PlainReason = when {
        "not read in time" in line -> PlainReason.NOT_READY
        "under a minute" in line -> PlainReason.SHORT_SONG
        "same song" in line -> PlainReason.SAME_SONG
        "of one album" in line -> PlainReason.RUN_TOGETHER
        "formats differ" in line -> PlainReason.FORMATS
        else -> PlainReason.NO_FIT
    }

    /**
     * "Plain change for these two". A saved change keeps its moves, so turning it back gives what
     * the listener made. With nothing saved it is a Blend on bands, 8 bars at a nominal tempo from
     * each song's start, a spec that passes every rule but is never played as a mix: the pill and
     * the engine read only [TransitionSpec.plain] until the editor turns it back.
     */
    fun plainSpec(pair: SongPair, saved: TransitionSpec?): TransitionSpec {
        saved?.takeIf { it.pair == pair }?.let { return it.copy(plain = true) }
        val bars = 8
        val l = bars.toDouble()
        val beat = 1.0 / 4
        val moves = buildMap {
            for (side in Side.entries) {
                for (lane in LaneSet.BANDS.lanes) {
                    // Blend: both songs over the whole change, the lows swapped on the middle bar.
                    put(LaneId(side, lane), if (lane == Lane.LOWS) Move(l / 2 - beat, beat, How.FADE) else Move(0.0, l, How.FADE))
                }
            }
        }
        return TransitionSpec(
            a = Anchor(song = pair.from, atMs = 0.0, bpm = TransitionSpec.NOMINAL_BPM),
            b = Anchor(song = pair.to, atMs = 0.0, bpm = TransitionSpec.NOMINAL_BPM),
            lanes = LaneSet.BANDS,
            bars = bars,
            beatMatch = false,
            preset = Preset.BLEND,
            plain = true,
            moves = moves,
        )
    }
}

/**
 * Which playlist each queue was started from, kept as "queueId=playlistId" strings in the app's
 * settings, so the Mix switch of that playlist still holds after the app is closed and the queue
 * is restored. The queue itself is in the database, and a column there would need a migration.
 */
object QueueLinks {

    fun decode(links: Set<String>): Map<Long, String> = buildMap {
        for (link in links) {
            val queue = link.substringBefore('=', "").toLongOrNull() ?: continue
            val playlist = link.substringAfter('=', "").takeIf { it.isNotEmpty() } ?: continue
            put(queue, playlist)
        }
    }

    /**
     * [links] with [queueId] linked to [playlistId], or unlinked when that is null, and only the
     * queues still [alive] kept: a queue's id is made from its title and comes back for another
     * queue of the same name, so a link must not outlive its queue.
     */
    fun with(links: Set<String>, queueId: Long, playlistId: String?, alive: Set<Long>): Set<String> {
        val kept = decode(links).filterKeys { it != queueId && it in alive }.toMutableMap()
        if (playlistId != null) kept[queueId] = playlistId
        return kept.map { (queue, playlist) -> "$queue=$playlist" }.toSet()
    }
}

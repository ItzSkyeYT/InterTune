/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.pill

import com.dd3boh.outertune.mix.auto.AutoInput
import com.dd3boh.outertune.mix.auto.AutoPick
import com.dd3boh.outertune.mix.auto.AutoPlain
import com.dd3boh.outertune.mix.auto.AutoPlanner
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.abs
import kotlin.math.round

/**
 * Auto for the pairs of a playlist, as far as it is known: what was heard the last time a change
 * played ([heard]), which is what really happened, and otherwise Auto's own pick from what the
 * analysis cache holds of the first song's end and the second song's opening ([choices]). A pick
 * made here has not been rendered and judged as one is when it plays, so the pill may name a
 * move the proof then turns down; the pill says what was heard from then on.
 */
class AnalysedPlanner(
    private val heard: PairPlanner,
    private val choices: Map<SongPair, AutoChoice>,
    /** The outgoing songs' ends as analysed, for whether a saved change still fits them. */
    private val tails: Map<String, SongEnd> = emptyMap(),
) : PairPlanner {

    override fun auto(pair: SongPair): AutoChoice? = heard.auto(pair) ?: choices[pair]

    override fun stillFits(spec: TransitionSpec): Boolean = fits(spec, tails[spec.a.song])

    companion object {

        /**
         * Auto's pick for each of [pairs] whose two ends are known: [tail] of the first song,
         * [head] of the second, each song's beat fix, and its album for the rule on songs that run
         * together. Pure, and quick without the audio: a playlist of fifty songs takes a moment.
         */
        fun plan(
            pairs: List<SongPair>,
            tail: (String) -> SongEnd?,
            head: (String) -> SongEnd?,
            fix: (String) -> BeatFix? = { null },
            album: (String) -> String? = { null },
        ): Map<SongPair, AutoChoice> = buildMap {
            for (pair in pairs) {
                if (pair in this) continue
                val a = tail(pair.from) ?: continue
                val b = head(pair.to) ?: continue
                val pick = runCatching {
                    AutoPlanner.pick(AutoInput(a, b, pair.from, pair.to, album(pair.from), album(pair.to), fix(pair.from), fix(pair.to)))
                }.getOrNull() ?: continue
                put(pair, choiceOf(pick))
            }
        }

        /** A pick in a pill's terms: the move and its length, or plain with the reason a pill has. */
        fun choiceOf(pick: AutoPick): AutoChoice = when (val plain = pick.plain) {
            null -> AutoChoice.Mixed(
                pick.move,
                when {
                    pick.move == Preset.CUT -> PillLength.NONE
                    pick.bars > 0 -> PillLength.bars(pick.bars)
                    else -> PillLength.seconds(pick.seconds)
                },
            )
            else -> AutoChoice.Plain(reasonOf(plain))
        }

        fun reasonOf(plain: AutoPlain): PlainReason = when (plain) {
            AutoPlain.SAME_SONG -> PlainReason.SAME_SONG
            AutoPlain.SHORT_SONG -> PlainReason.SHORT_SONG
            AutoPlain.RUN_TOGETHER -> PlainReason.RUN_TOGETHER
            AutoPlain.FORMATS -> PlainReason.FORMATS
            AutoPlain.NOT_READ -> PlainReason.NOT_READY
            AutoPlain.NOTHING_TO_MIX, AutoPlain.NO_FIT, AutoPlain.NO_CLEAN_MIX -> PlainReason.NO_FIT
        }

        /**
         * Whether a saved change still sits on its outgoing song as analysed now, by the rule
         * playback follows: a change timed in bars needs a beat of the song within half a beat of
         * its anchor, at a tempo within 1% of the one it was made at (halved or doubled allowed).
         * True wherever nothing is known to say otherwise: no analysis yet, a change timed in
         * seconds, or an anchor outside what was analysed.
         */
        fun fits(spec: TransitionSpec, tail: SongEnd?): Boolean {
            if (spec.bars == 0 || spec.plain) return true
            val end = tail?.with(spec.a.fix) ?: return true
            if (spec.a.atMs < end.fromMs || spec.a.atMs > end.toMs) return true
            val line = end.line ?: return false
            if (!line.beatsTrusted) return false
            val beatMs = 60_000.0 / spec.a.bpm
            val k = round((spec.a.atMs - line.t0Ms) / line.beatMs)
            if (abs(line.t0Ms + k * line.beatMs - spec.a.atMs) > beatMs / 2) return false
            val r = line.bpm / spec.a.bpm
            return listOf(0.5, 1.0, 2.0).any { abs(r / it - 1) <= TEMPO_MOVED }
        }

        /** How far the tempo found may move from the one a change was made at before it needs a look. */
        const val TEMPO_MOVED = 0.01
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.content.res.Resources
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.TransitionSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Every sentence the editor builds from the spec, in the page's language. */
class EditorWords(private val res: Resources, val aTitle: String, val bTitle: String) {

    private val locale: Locale get() = res.configuration.locales[0]

    /** "After Hours'", "Wake Me Up's" in English; the title as it is where the language says "de". */
    fun owner(title: String): String =
        res.getString(if (title.endsWith("s") || title.endsWith("S")) R.string.mix_edit_owner_s else R.string.mix_edit_owner, title)

    fun laneName(lane: Lane): String = res.getString(
        when (lane) {
            Lane.LOWS -> R.string.mix_edit_lows
            Lane.MIDS -> R.string.mix_edit_mids
            Lane.HIGHS -> R.string.mix_edit_highs
            Lane.VOCALS -> R.string.mix_edit_vocals
            Lane.DRUMS -> R.string.mix_edit_drums
            Lane.BASS -> R.string.mix_edit_bass
            Lane.MELODY -> R.string.mix_edit_melody
        }
    )

    private fun laneWord(lane: Lane) = laneName(lane).lowercase(locale)

    private fun plural(lane: Lane): Boolean = res.getBoolean(
        when (lane) {
            Lane.LOWS -> R.bool.mix_edit_plural_lows
            Lane.MIDS -> R.bool.mix_edit_plural_mids
            Lane.HIGHS -> R.bool.mix_edit_plural_highs
            Lane.VOCALS -> R.bool.mix_edit_plural_vocals
            Lane.DRUMS -> R.bool.mix_edit_plural_drums
            Lane.BASS -> R.bool.mix_edit_plural_bass
            Lane.MELODY -> R.bool.mix_edit_plural_melody
        }
    )

    private fun pick(lane: Lane, many: Int, one: Int) = if (plural(lane)) many else one

    fun bars(n: Int): String = res.getQuantityString(R.plurals.mix_edit_bars, n, n)

    fun beats(n: Int): String = res.getQuantityString(R.plurals.mix_edit_beats, n, n)

    fun seconds(s: Double): String = res.getString(R.string.mix_edit_seconds, number(s))

    /** A number with at most one decimal, in the page's way of writing it. */
    fun number(x: Double): String =
        if (abs(x - x.roundToInt()) < 0.05) x.roundToInt().toString() else String.format(locale, "%.1f", x)

    fun bpm(bpm: Double): String = res.getString(R.string.mix_edit_bpm, number(bpm))

    /** "4:41", from ms. */
    fun clock(ms: Double): String {
        val s = floor(maxOf(0.0, ms) / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** "8 bars" or "6 s". */
    fun length(s: TransitionSpec): String = if (s.bars > 0) bars(s.bars) else seconds(s.seconds)

    /** A bar's name on the ruler: "-2", "-1", "1" to the length, then "+1", "+2". */
    fun barName(u: Double, length: Double): String {
        val k = floor(u + 1e-9).toInt()
        return when {
            k < 0 -> k.toString()
            k >= length - 1e-9 -> "+" + (k - ceil(length - 1e-9).toInt() + 1)
            else -> (k + 1).toString()
        }
    }

    /** "bar 3", "bar 3, beat 2", or "4.5 s" in a change timed in seconds. */
    fun position(u: Double, s: TransitionSpec): String {
        if (s.bars == 0) return seconds(u * s.b.barMs / 1000)
        val beat = floor((u - floor(u + 1e-9)) * s.b.beatsPerBar + 1e-6).toInt() + 1
        val name = barName(u, s.lengthBars)
        return if (beat > 1) res.getString(R.string.mix_pos_beat, name, beat) else res.getString(R.string.mix_pos_bar, name)
    }

    /** A ramp's length: "1 beat", "2 bars", "whole change". */
    fun ramp(len: Double, s: TransitionSpec): String {
        if (s.bars == 0) return seconds(len * s.b.barMs / 1000)
        if (abs(len - s.lengthBars) < 1e-9 && len > 4) return res.getString(R.string.mix_how_whole)
        if (len < 1 - 1e-9 || abs(len - len.roundToInt()) > 1e-9) return beats((len * s.b.beatsPerBar).roundToInt())
        return bars(len.roundToInt())
    }

    fun limit(l: Limit): String = when (l) {
        is Limit.AReach -> res.getString(R.string.mix_edit_limit_a_reach, clock(l.reachMs), aTitle)
        is Limit.AEnds -> res.getString(R.string.mix_edit_limit_a_ends, aTitle, clock(l.durationMs))
        Limit.BFirstSecond -> res.getString(R.string.mix_edit_limit_b_first, bTitle)
        is Limit.BReach -> res.getString(R.string.mix_edit_limit_b_reach, owner(bTitle), clock(l.reachMs))
        is Limit.ANeeds -> res.getString(R.string.mix_edit_limit_a_needs, clock(l.neededMs), owner(aTitle), clock(l.reachMs))
        is Limit.APlays -> res.getString(R.string.mix_edit_limit_a_plays, clock(l.reachMs), aTitle)
    }

    fun howName(h: How): String = res.getString(
        when (h) {
            How.FADE -> R.string.mix_how_fade
            How.MUFFLE -> R.string.mix_how_muffle
            How.MUFFLED -> R.string.mix_how_muffled
            How.THIN -> R.string.mix_how_thin
            How.ECHO -> R.string.mix_how_echo
            How.CUT -> R.string.mix_how_cut
            How.KEEP -> R.string.mix_how_keep
            How.FROM_START -> R.string.mix_how_start
        }
    )

    fun presetName(p: Preset): String = res.getString(
        when (p) {
            Preset.CUSTOM -> R.string.mix_preset_custom
            Preset.AUTO -> R.string.mix_preset_auto
            Preset.VOCALS_OVER -> R.string.mix_preset_vocals_over
            Preset.BEAT_UNDER -> R.string.mix_preset_beat_under
            Preset.DRUM_SWAP -> R.string.mix_preset_drum_swap
            Preset.RIFF_RISE -> R.string.mix_preset_riff_rise
            Preset.FADE -> R.string.mix_preset_fade
            Preset.RISE -> R.string.mix_preset_rise
            Preset.BLEND -> R.string.mix_preset_blend
            Preset.ECHO_OUT -> R.string.mix_preset_echo_out
            Preset.CUT -> R.string.mix_preset_cut
        }
    )

    /** The lit preset's line, under the chips. */
    fun presetLine(p: Preset?, s: TransitionSpec, autoPlain: Boolean): String = when (p) {
        null -> res.getString(R.string.mix_line_plain, aTitle, bTitle)
        Preset.CUSTOM -> res.getString(R.string.mix_line_custom)
        Preset.AUTO -> res.getString(if (autoPlain && s.plain) R.string.mix_line_auto_plain else R.string.mix_line_auto)
        Preset.VOCALS_OVER -> res.getString(R.string.mix_line_vocals_over, aTitle, owner(bTitle))
        Preset.BEAT_UNDER -> res.getString(R.string.mix_line_beat_under, owner(aTitle), owner(bTitle), bTitle)
        Preset.DRUM_SWAP -> res.getString(R.string.mix_line_drum_swap, owner(aTitle), bTitle)
        Preset.RIFF_RISE -> res.getString(R.string.mix_line_riff_rise, owner(bTitle), aTitle)
        Preset.FADE -> res.getString(R.string.mix_line_fade)
        Preset.RISE -> res.getString(R.string.mix_line_rise, bTitle, aTitle)
        Preset.BLEND -> res.getString(R.string.mix_line_blend)
        Preset.ECHO_OUT -> res.getString(R.string.mix_line_echo_out, owner(aTitle), bTitle)
        Preset.CUT -> res.getString(TimedLines.cutPreset(s.bars == 0))
    }

    /** What happens at a moment: "After Hours' hats come in", "Lows swap". */
    fun moment(m: Moment): String {
        val lane = m.lane
        return when (m.kind) {
            Moment.Kind.SWAP -> res.getString(pick(lane!!, R.string.mix_edit_m_swap, R.string.mix_edit_m_swap_one), laneName(lane), laneWord(lane))
            Moment.Kind.ENTRY -> when {
                m.how == How.THIN && (lane == Lane.HIGHS || lane == Lane.DRUMS) -> res.getString(R.string.mix_edit_m_hats, owner(bTitle))
                m.how == How.MUFFLED -> res.getString(pick(lane!!, R.string.mix_edit_m_rise, R.string.mix_edit_m_rise_one), owner(bTitle), laneWord(lane))
                else -> res.getString(pick(lane!!, R.string.mix_edit_m_in, R.string.mix_edit_m_in_one), owner(bTitle), laneWord(lane))
            }
            Moment.Kind.EXIT -> {
                val one = !plural(lane!!)
                val verb = res.getString(
                    when (m.how) {
                        How.CUT -> if (one) R.string.mix_edit_v_cut_one else R.string.mix_edit_v_cut
                        How.MUFFLE -> if (one) R.string.mix_edit_v_muffle_one else R.string.mix_edit_v_muffle
                        How.THIN -> if (one) R.string.mix_edit_v_thin_one else R.string.mix_edit_v_thin
                        How.ECHO -> if (one) R.string.mix_edit_v_echo_one else R.string.mix_edit_v_echo
                        else -> if (one) R.string.mix_edit_v_fade_one else R.string.mix_edit_v_fade
                    }
                )
                res.getString(if (one) R.string.mix_edit_m_out_one else R.string.mix_edit_m_out, owner(aTitle), laneWord(lane), verb)
            }
            Moment.Kind.MUTED_A -> res.getString(pick(lane!!, R.string.mix_edit_m_muted, R.string.mix_edit_m_muted_one), owner(aTitle), laneWord(lane))
            Moment.Kind.MUTED_B -> res.getString(pick(lane!!, R.string.mix_edit_m_waits, R.string.mix_edit_m_waits_one), owner(bTitle), laneWord(lane))
            Moment.Kind.CURVE_A -> res.getString(pick(lane!!, R.string.mix_edit_m_curve, R.string.mix_edit_m_curve_one), owner(aTitle), laneWord(lane))
            Moment.Kind.CURVE_B -> res.getString(pick(lane!!, R.string.mix_edit_m_curve, R.string.mix_edit_m_curve_one), owner(bTitle), laneWord(lane))
            Moment.Kind.STARTS_UNDER -> res.getString(R.string.mix_edit_m_under, bTitle, aTitle)
        }
    }

    /** The now line while it plays: "Bar 5 of 8 · Bass swap". */
    fun nowPlaying(e: MixEditor, u: Double): String {
        val s = e.spec
        val l = s.lengthBars
        val pos = when {
            u < 0 -> if (s.bars == 0) res.getString(R.string.mix_edit_now_before_timed) else ceil(-u - 1e-9).toInt().let { res.getQuantityString(R.plurals.mix_edit_now_before, it, it) }
            u >= l -> res.getString(R.string.mix_edit_now_after)
            s.bars == 0 -> res.getString(R.string.mix_edit_now_second, floor(u * s.b.barMs / 1000).toInt() + 1, s.seconds.roundToInt())
            else -> res.getString(R.string.mix_edit_now_bar, floor(u).toInt() + 1, s.bars)
        }
        if (s.plain) return "$pos · ${res.getString(R.string.mix_edit_now_plain)}"
        val m = e.momentAt(u) ?: return pos
        return "$pos · ${moment(m)}"
    }

    /** The now line when stopped: "8 bars, 17.6 s · Wake Me Up loses its last 0:09". */
    fun nowStopped(e: MixEditor): String {
        val s = e.spec
        if (s.plain) return res.getString(R.string.mix_edit_plain_yours)
        val len = if (s.bars > 0) "${bars(s.bars)}, ${seconds(s.lengthMs / 1000)}" else seconds(s.seconds)
        val lost = e.unheardMs()
        return if (lost >= 1000) "$len · ${res.getString(R.string.mix_edit_loses, aTitle, clock(lost))}" else len
    }

    fun readingA() = res.getString(R.string.mix_edit_reading_a, aTitle)
    fun readingB() = res.getString(R.string.mix_edit_reading_b, bTitle)
    fun analysing() = res.getString(R.string.mix_edit_analysing)

    /** "4:41.3", for where bar 1 sits while a song is dragged. */
    fun clockTenths(ms: Double): String {
        val tenths = (maxOf(0.0, ms) / 100).roundToInt()
        return String.format(locale, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
    }

    fun byBeats(text: String) = res.getString(R.string.mix_canvas_by_beats, text)

    // ---- on the canvas ----

    fun leaves(where: String) = res.getString(R.string.mix_canvas_leaves, where)
    fun comes(where: String) = res.getString(R.string.mix_canvas_comes, where)
    fun howFrom(h: How, where: String) = res.getString(R.string.mix_canvas_how_from, howName(h).lowercase(locale), where)
    fun howInFrom(h: How, where: String) = res.getString(R.string.mix_canvas_how_in_from, howName(h).lowercase(locale), where)
    fun cutAt(where: String) = res.getString(R.string.mix_canvas_cut_at, where)
    fun fromItsStart() = res.getString(R.string.mix_canvas_from_start)
    fun echoWord() = res.getString(R.string.mix_canvas_echo)
    fun notHeard(ms: Double) = res.getString(R.string.mix_canvas_not_heard, clock(ms))
    fun introTwiceWord() = res.getString(R.string.mix_canvas_intro_twice)
    fun mutedLabel(name: String) = res.getString(R.string.mix_lane_muted, name)
    fun soloLabel(name: String) = res.getString(R.string.mix_lane_soloed, name)
    fun plainOnCanvas() = res.getString(R.string.mix_canvas_plain, bTitle, aTitle)

    /** "Highs, Wake Me Up", for Adjust's two curves. */
    fun laneOf(lane: Lane, side: com.dd3boh.outertune.mix.edit.Side) =
        "${laneName(lane)}, ${if (side == com.dd3boh.outertune.mix.edit.Side.A) aTitle else bTitle}"

    /** What TalkBack hears for the whole canvas. */
    fun summary(e: MixEditor): String {
        val s = e.spec
        val lit = e.lit()
        val what = lit?.let(::presetName) ?: res.getString(R.string.mix_edit_now_plain)
        val head = res.getString(R.string.mix_canvas_summary, aTitle, bTitle, length(s), what)
        if (s.plain) return head
        return head + " " + e.moments().joinToString(". ") { "${position(it.at, s)}: ${moment(it)}" } + "."
    }

    fun beatMatchLine(e: MixEditor): String {
        val s = e.spec
        if (s.bars == 0 && !e.facts.barsMode) return res.getString(R.string.mix_edit_bm_no_bars)
        if (e.facts.a.grid == null) return res.getString(R.string.mix_edit_bm_no_beat_a, aTitle)
        val ratio = s.tempoRatio()
        val pct = String.format(locale, "%.1f", abs(1 - ratio) * 100)
        return when {
            !s.beatMatchAllowed() && s.bars > 0 -> res.getString(R.string.mix_edit_bm_refused, pct)
            s.beatMatched && abs(1 - ratio) < 0.0005 -> res.getString(R.string.mix_edit_bm_same, aTitle, owner(bTitle))
            s.beatMatched -> res.getString(if (ratio < 1) R.string.mix_edit_bm_slower else R.string.mix_edit_bm_faster, aTitle, pct, owner(bTitle))
            else -> res.getString(R.string.mix_edit_bm_off, String.format(locale, "%.2f", s.b.barMs / 1000 * abs(1 - ratio) * s.lengthBars), length(s))
        }
    }
}

/**
 * The lines that speak of bars and beats, said without them for a change timed in seconds
 * ([TransitionSpec.bars] 0): without a steady beat in both songs there is no bar to cut on, and
 * the basses swap at a moment, not on a beat.
 */
internal object TimedLines {
    fun cutPreset(timed: Boolean) = if (timed) R.string.mix_line_cut_timed else R.string.mix_line_cut
    fun cut(timed: Boolean) = if (timed) R.string.mix_simple_cut_line_timed else R.string.mix_simple_cut_line
    fun bassSwap(timed: Boolean) = if (timed) R.string.mix_simple_bass_swap_line_timed else R.string.mix_simple_bass_swap_line
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.pill.MixRowLine
import com.dd3boh.outertune.mix.pill.Pill
import com.dd3boh.outertune.mix.pill.PillLength
import com.dd3boh.outertune.mix.pill.PlainReason

/**
 * Mix's words on screen, from the states in com.dd3boh.outertune.mix.pill. One place, so the
 * playlist's pills, the queue's line and the player's menu always say a change the same way.
 */
object MixWords {

    @StringRes
    fun move(preset: Preset): Int = when (preset) {
        Preset.CUSTOM -> R.string.mix_move_custom
        Preset.AUTO -> R.string.mix_move_auto
        Preset.VOCALS_OVER -> R.string.mix_move_vocals_over
        Preset.BEAT_UNDER -> R.string.mix_move_beat_under
        Preset.DRUM_SWAP -> R.string.mix_move_drum_swap
        Preset.RIFF_RISE -> R.string.mix_move_riff_rise
        Preset.FADE -> R.string.mix_move_fade
        Preset.RISE -> R.string.mix_move_rise
        Preset.BLEND -> R.string.mix_move_blend
        Preset.ECHO_OUT -> R.string.mix_move_echo_out
        Preset.CUT -> R.string.mix_move_cut
    }

    @StringRes
    fun reason(reason: PlainReason): Int = when (reason) {
        PlainReason.CASTING -> R.string.mix_plain_casting
        PlainReason.TOGETHER -> R.string.mix_plain_together
        PlainReason.REPEAT_ONE -> R.string.mix_plain_repeat_one
        PlainReason.SAME_SONG -> R.string.mix_plain_same_song
        PlainReason.RUN_TOGETHER -> R.string.mix_plain_run_together
        PlainReason.SHORT_SONG -> R.string.mix_plain_short_song
        PlainReason.SPEED -> R.string.mix_plain_speed
        PlainReason.SKIP_SILENCE -> R.string.mix_plain_skip_silence
        PlainReason.SPATIAL -> R.string.mix_plain_spatial
        PlainReason.SLEEP_TIMER -> R.string.mix_plain_sleep_timer
        PlainReason.DECODER -> R.string.mix_plain_decoder
        PlainReason.FORMATS -> R.string.mix_plain_formats
        PlainReason.NOT_READY -> R.string.mix_plain_not_ready
        PlainReason.NO_TIME -> R.string.mix_plain_no_time
        PlainReason.FROM_NEXT -> R.string.mix_plain_from_next
        PlainReason.NO_FIT -> R.string.mix_plain_no_fit
        PlainReason.YOUR_CHOICE -> R.string.mix_plain_your_choice
    }

    /** The Mix row's line when a setting keeps every change plain; null for a reason that is never one. */
    @StringRes
    fun blocked(reason: PlainReason): Int? = when (reason) {
        PlainReason.CASTING -> R.string.mix_blocked_casting
        PlainReason.TOGETHER -> R.string.mix_blocked_together
        PlainReason.REPEAT_ONE -> R.string.mix_blocked_repeat_one
        PlainReason.SPEED -> R.string.mix_blocked_speed
        PlainReason.SKIP_SILENCE -> R.string.mix_blocked_skip_silence
        PlainReason.SPATIAL -> R.string.mix_blocked_spatial
        PlainReason.DECODER -> R.string.mix_blocked_decoder
        PlainReason.SLEEP_TIMER -> R.string.mix_blocked_sleep_timer
        else -> null
    }

    /** Where the setting behind a blocking reason is changed, for the button beside the line; null where no page has it. */
    fun blockedSetting(reason: PlainReason): String? = when (reason) {
        PlainReason.SKIP_SILENCE, PlainReason.SPATIAL, PlainReason.DECODER -> "settings/player"
        else -> null
    }

    @StringRes
    fun rowLine(line: MixRowLine): Int = when (line) {
        MixRowLine.Off -> R.string.mix_off
        MixRowLine.On -> R.string.mix_on
        MixRowLine.Shuffled -> R.string.mix_shuffled
        is MixRowLine.Blocked -> blocked(line.reason) ?: R.string.mix_on
    }

    /** A move with its length, as a pill and the queue's line say it: "Fade · 8 bars", "Blend · 6 s", "Cut". */
    @Composable
    fun named(preset: Preset, length: PillLength): String {
        val name = stringResource(move(preset))
        return when {
            length.bars > 0 -> pluralStringResource(R.plurals.mix_pill_bars, length.bars, name, length.bars)
            length.seconds > 0 -> stringResource(R.string.mix_pill_seconds, name, length.seconds)
            else -> name
        }
    }

    /** What a pill says, without its pencil: the pencil is drawn, and said by [described]. */
    @Composable
    fun text(pill: Pill): String = when (pill) {
        Pill.Auto -> stringResource(R.string.mix_pill_auto)
        is Pill.Move -> named(pill.preset, pill.length)
        is Pill.Edited -> if (pill.bandsThisTime) stringResource(R.string.mix_pill_bands, stringResource(move(pill.preset)))
            else named(pill.preset, pill.length)
        Pill.NeedsLook -> stringResource(R.string.mix_pill_needs_look)
        is Pill.Plain -> stringResource(R.string.mix_pill_plain, stringResource(reason(pill.reason)))
    }

    /** What TalkBack says of a pill: both songs, the move, and whether it is the listener's own. */
    @Composable
    fun described(pill: Pill, fromTitle: String, toTitle: String): String {
        val words = text(pill).let { if (pill.edited) stringResource(R.string.mix_pill_yours, it) else it }
        return stringResource(R.string.mix_pill_described, fromTitle, toTitle, words)
    }
}

/** Mix's pages in the app's navigation; the editor's route is MIX_EDITOR_ROUTE. */
object MixRoutes {
    /** Settings, Player and audio, Mixing. */
    const val SETTINGS = "settings/player/mixing"
}

/** Opens the editor on the change from [pair]'s first song into its second. */
fun NavController.openMixEditor(pair: SongPair) = openMixEditor(pair.from, pair.to)

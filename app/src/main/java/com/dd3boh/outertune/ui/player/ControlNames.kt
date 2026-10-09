/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_ONE
import com.dd3boh.outertune.R

/**
 * What a screen reader says for the player's own buttons, which are pictures with nothing
 * written on them. The mini player's three were named in 0.11.1; the full player's shuffle,
 * previous, play, next, repeat and heart, and the same row in the queue, had no name at all.
 * The words are the ones the notification's buttons already carry, so they are translated.
 */
internal fun Modifier.named(name: String): Modifier = semantics {
    contentDescription = name
    role = Role.Button
}

/** Shuffle is said as it stands, like the picture: on or off. */
@Composable
internal fun shuffleName(on: Boolean): String =
    stringResource(if (on) R.string.action_shuffle_on else R.string.action_shuffle_off)

/** Repeat as it stands: off, the queue, or the one song. */
@Composable
internal fun repeatName(mode: Int): String = stringResource(
    when (mode) {
        REPEAT_MODE_ALL -> R.string.repeat_mode_all
        REPEAT_MODE_ONE -> R.string.repeat_mode_one
        else -> R.string.repeat_mode_off
    }
)

/** The heart says what a tap on it does. */
@Composable
internal fun likeName(liked: Boolean): String =
    stringResource(if (liked) R.string.action_remove_like else R.string.action_like)

/** Play says what a tap on it does. Ended, a tap plays again. */
@Composable
internal fun playPauseName(playing: Boolean): String =
    stringResource(if (playing) R.string.widget_pause else R.string.widget_play)

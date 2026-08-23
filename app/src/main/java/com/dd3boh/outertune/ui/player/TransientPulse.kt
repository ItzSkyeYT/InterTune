/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/**
 * A stand-in beat source for [ChromaticShockEffect].
 *
 * [ChromaticShockEffect] wants a transient magnitude in 0..1, which properly comes from analysing
 * the audio. That needs an AudioProcessor spliced into the media3 sink, which is a much bigger
 * change than the effect itself. This gives the effect something real to fire on in the meantime: a
 * single decaying pulse each time [trigger] changes, for example on a track change.
 *
 * It is deliberately not a fake beat clock. Inventing a tempo and pulsing to it would be
 * synchronised to nothing and read worse than pulsing on a real event.
 *
 * Returns a [FloatState] rather than a Float on purpose. Reading the value during composition would
 * recompose the caller on every frame of the decay; callers should pass `{ pulse.floatValue }` into
 * [ChromaticShockEffect], which samples it from a coroutine instead.
 */
@Composable
fun rememberTransientPulse(
    trigger: Any?,
    peak: Float = 0.9f,
    decaySeconds: Float = 0.25f,
): FloatState {
    val state = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(trigger) {
        // Nothing to pulse for before the first real trigger, so opening the player stays quiet.
        if (trigger == null) return@LaunchedEffect
        state.floatValue = peak

        var lastFrameNanos = 0L
        var elapsed = 0f
        while (elapsed < decaySeconds) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    elapsed += (nanos - lastFrameNanos) / 1_000_000_000f
                    state.floatValue = (peak * (1f - elapsed / decaySeconds)).coerceAtLeast(0f)
                }
                lastFrameNanos = nanos
            }
        }
        state.floatValue = 0f
    }

    return state
}

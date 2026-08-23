/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * The AGSL shader below is adapted from notK50BML/OuterTune's LiquidChromaticShock, distributed
 * under GPL-3.0. The Kotlin host has been rewritten; see the note on idle cost at [ChromaticShockEffect].
 */

package com.dd3boh.outertune.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

private const val TAG = "ChromaticShock"

/** Below this, a transient is not treated as a kick. */
private const val KICK_TRIGGER_THRESHOLD = 0.45f

/** Minimum gap between kicks, so a sustained loud passage cannot relaunch the ring every frame. */
private const val MIN_KICK_INTERVAL_SECONDS = 0.35f

/**
 * How long a ring lives. Must stay in step with the early-out in the shader, which is the only place
 * the ring actually stops being drawn.
 */
private const val SHOCK_LIFETIME_SECONDS = 1.4f

/**
 * An expanding ring of refraction, launched by a beat.
 *
 * Pixels near the ring front are resampled along the outward direction with a different offset per
 * colour channel, which is what produces the rainbow fringing along any edge the ring passes over.
 * The ring is transient: it expands, fades, and after [SHOCK_LIFETIME_SECONDS] it is gone. Catching
 * it mid-expansion is why the fringing appears as a band across part of the screen rather than on
 * every edge at once.
 */
private const val CHROMATIC_SHOCK_SHADER_SRC = """
    uniform shader composable;
    uniform float2 resolution;
    uniform float shockAge;
    uniform float shockStrength;

    half4 main(float2 fragCoord) {
        if (shockAge > 1.4 || shockStrength <= 0.001) {
            return composable.eval(fragCoord);
        }

        float2 center = resolution * float2(0.5, 0.42);
        float2 delta = fragCoord - center;
        float dist = length(delta);
        float2 dir = dist > 0.5 ? delta / dist : float2(0.0, 0.0);

        float minDim = min(resolution.x, resolution.y);
        float speed = minDim * 1.15;
        float front = shockAge * speed;
        float bandWidth = max(minDim * 0.16 - shockAge * minDim * 0.11, minDim * 0.012);
        float diff = dist - front;
        float band = exp(-(diff * diff) / (bandWidth * bandWidth));

        float life = clamp(1.0 - shockAge / 1.4, 0.0, 1.0);
        float amount = band * shockStrength * life;

        float push = amount * minDim * 0.045;
        half4 rSample = composable.eval(fragCoord + dir * (push * 1.35));
        half4 gSample = composable.eval(fragCoord + dir * push);
        half4 bSample = composable.eval(fragCoord + dir * (push * 0.65));

        half4 col = half4(rSample.r, gSample.g, bSample.b, gSample.a);
        col.rgb += half3(amount * 0.55);
        return col;
    }
"""

/**
 * Wraps [content] with the chromatic shock ripple.
 *
 * Passes [content] straight through, with no wrapper layer at all, when the effect is off, when the
 * device is below API 33 (RuntimeShader does not exist there), or when this device's Skia rejects
 * the AGSL source. A hand-written shader failing to compile must not take the player down with it.
 *
 * IDLE COST. The version this is adapted from ran `while (true) { withFrameNanos { } }` for as long
 * as the player was open and attached the RenderEffect unconditionally, so every frame paid for a
 * full-screen offscreen render plus a fragment-shader pass, plus two allocations, purely to have the
 * shader hand back its input unchanged. Here the frame loop suspends between rings and the layer is
 * not applied at all unless a ring is in flight, so an idle player costs nothing.
 *
 * @param transient reads the current beat magnitude, 0..1. A lambda rather than a value so the
 *   caller is not recomposed as it changes; it is sampled from a coroutine. The source is up to the
 *   caller and need not be audio analysis, see [rememberTransientPulse].
 */
@Composable
fun ChromaticShockEffect(
    enabled: Boolean,
    isActive: Boolean,
    transient: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        content()
        return
    }
    ChromaticShockEffectImpl(isActive, transient, modifier, content)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ChromaticShockEffectImpl(
    isActive: Boolean,
    transient: () -> Float,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val shader = remember {
        runCatching { RuntimeShader(CHROMATIC_SHOCK_SHADER_SRC) }
            .onFailure { Log.e(TAG, "AGSL shader failed to compile, disabling chromatic shock", it) }
            .getOrNull()
    }
    if (shader == null) {
        content()
        return
    }

    // Age of the ring in flight, parked at the expired value when idle. Deliberately not a running
    // wall clock: nothing accumulates across a session, so there is no float drift to worry about.
    var shockAge by remember { mutableFloatStateOf(SHOCK_LIFETIME_SECONDS) }
    var shockStrength by remember { mutableFloatStateOf(0f) }
    var pendingKick by remember { mutableStateOf<Float?>(null) }
    val currentTransient by rememberUpdatedState(transient)


    // Kick detection runs off transient changing, not off the frame clock, so nothing wakes up
    // between beats. The interval guard reads shockAge, which is the same clock the ring animates
    // on, so MIN_KICK_INTERVAL_SECONDS still means what it says.
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        snapshotFlow { currentTransient() }.collect { value ->
            if (value > KICK_TRIGGER_THRESHOLD && shockAge >= MIN_KICK_INTERVAL_SECONDS) {
                pendingKick = value.coerceIn(0f, 1f)
            }
        }
    }

    // One long-lived loop: suspend until a kick arrives, animate the ring for its lifetime, suspend
    // again. Keyed only on isActive, so it is never restarted by the state it writes.
    LaunchedEffect(isActive) {
        if (!isActive) {
            shockAge = SHOCK_LIFETIME_SECONDS
            shockStrength = 0f
            pendingKick = null
            return@LaunchedEffect
        }
        while (true) {
            val kick = snapshotFlow { pendingKick }.filterNotNull().first()
            pendingKick = null
            shockStrength = kick
            shockAge = 0f

            var lastFrameNanos = 0L
            while (shockAge < SHOCK_LIFETIME_SECONDS) {
                withFrameNanos { nanos ->
                    if (lastFrameNanos != 0L) {
                        shockAge += (nanos - lastFrameNanos) / 1_000_000_000f
                    }
                    lastFrameNanos = nanos
                }
            }
            shockAge = SHOCK_LIFETIME_SECONDS
            shockStrength = 0f
        }
    }

    if (shockAge >= SHOCK_LIFETIME_SECONDS) {
        // No layer, no offscreen buffer, no shader pass. This is the common case.
        content()
        return
    }

    Box(
        modifier = modifier.graphicsLayer {
            // Reading shockAge here, inside the layer block, is what re-invalidates the layer on
            // each frame while the ring is alive.
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("shockAge", shockAge)
            shader.setFloatUniform("shockStrength", shockStrength)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "composable")
                .asComposeRenderEffect()
        }
    ) {
        content()
    }
}

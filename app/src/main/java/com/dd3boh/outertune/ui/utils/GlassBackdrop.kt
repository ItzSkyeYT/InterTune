/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.dd3boh.outertune.constants.PlayerGlassIntensityKey
import com.dd3boh.outertune.utils.rememberPreference
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * The app content stack, published as a backdrop for glass panels to refract.
 *
 * Null when liquid glass is off, unsupported on this device, or when the nav rail is in use, so a
 * consumer just checks for null. Deliberately the only backdrop in the app: a lens over a flat fill
 * costs a full offscreen pass and renders nothing, so panels share one source rather than each
 * publishing another.
 *
 * A layer must never contain one of its own readers. Doing so makes RenderNode::prepareTreeImpl
 * recurse until the native stack overflows, which kills the process with no Java exception.
 */
val LocalAppBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Resolved glass parameters, so every surface derives its numbers the same way instead of each
 * hard-coding its own.
 *
 * Both derived from the single existing intensity slider. A separate frost control was tried and
 * dropped: the two move together in practice, since a panel that bends the backdrop harder also
 * needs to obscure more of it to stay readable, and two sliders for one visual idea is worse than
 * one that does the obvious thing.
 *
 *  - [refraction] is how hard the lens bends the backdrop at the panel rim.
 *  - [frost] is how much the panel obscures what is behind it: tint opacity plus blur radius.
 */
@Immutable
data class GlassSpec(
    val backdrop: LayerBackdrop,
    val refraction: Float,
) {
    /**
     * Frosting tracks refraction, but not linearly: it ramps in faster at the bottom of the range,
     * because the readability cost of a clear panel shows up long before the lens looks strong.
     */
    val frost: Float get() = kotlin.math.sqrt(refraction.coerceIn(0f, 1f))

    /** Blur behind the panel. Scales hard with frost, which is what "frosted" actually means. */
    val blur: Dp get() = lerp(6.dp, 32.dp, frost)

    /**
     * lens() early-returns when either argument is 0, which drops the rounded SDF and leaves an
     * unshaped blurred rectangle. Floored so the panel always keeps its shape.
     */
    val lensT: Float get() = refraction.coerceIn(0.15f, 1f)

    /**
     * Tint opacity. The full range is exposed deliberately: at 0 the panel is nearly clear glass
     * with only a refracting rim, at 1 it is the solid surface the app uses without glass, still
     * with the rim. Anything readable lives above roughly 0.6, but where exactly is taste, which is
     * why it is on a slider rather than pinned to a constant.
     */
    fun tintAlpha(min: Float = 0.30f, max: Float = 1f): Float = lerp(min, max, frost)

    @Composable
    fun tint(elevation: Dp = 6.dp, min: Float = 0.30f, max: Float = 1f): Color =
        MaterialTheme.colorScheme.surfaceColorAtElevation(elevation).copy(alpha = tintAlpha(min, max))
}

/**
 * The glass spec for this composition, or null when glass is off or unavailable.
 *
 * Reading this is all a surface needs; the gating, the backdrop and the numbers all arrive together.
 */
@Composable
fun rememberGlassSpec(): GlassSpec? {
    val backdrop = LocalAppBackdrop.current ?: return null
    val refraction by rememberPreference(PlayerGlassIntensityKey, defaultValue = 1f)
    return GlassSpec(backdrop, refraction.coerceIn(0f, 1f))
}

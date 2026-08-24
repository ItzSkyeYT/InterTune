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
 * Both derived from the single intensity slider, which means "how much glass", so turning it up
 * makes the panel MORE glassy: more transparent and more strongly refracted. That direction matters.
 * It used to run the other way, where 100% produced a solid opaque bar, which is the least glassy
 * thing the panel can be. Two separate users read that as the effect being broken and turned the
 * slider down to 0 looking for more glass, getting panels so clear that text behind them collided
 * with the text on them.
 *
 *  - [refraction] is how hard the lens bends the backdrop at the panel rim. Rises with intensity.
 *  - [frost] is how much the panel obscures what is behind it. FALLS as intensity rises.
 */
@Immutable
data class GlassSpec(
    val backdrop: LayerBackdrop,
    /** The user's "glass intensity", 0..1. 0 is almost the solid bar, 1 is as glassy as it gets. */
    val intensity: Float,
) {
    /** Lens strength. More intensity, more bend. */
    val refraction: Float get() = intensity.coerceIn(0f, 1f)

    /**
     * How opaque the panel is. Inverse of intensity, eased so it falls off gently at first: the
     * readability cost of a clear panel arrives well before the lens starts looking strong.
     */
    val frost: Float get() = 1f - kotlin.math.sqrt(intensity.coerceIn(0f, 1f))

    /** Blur behind the panel. More frost, more blur, which is what "frosted" actually means. */
    val blur: Dp get() = lerp(6.dp, 32.dp, frost)

    /**
     * lens() early-returns when either argument is 0, which drops the rounded SDF and leaves an
     * unshaped blurred rectangle. Floored so the panel always keeps its shape even at intensity 0.
     */
    val lensT: Float get() = refraction.coerceIn(0.15f, 1f)

    /**
     * Tint opacity.
     *
     * [min] is the floor at full intensity and is deliberately not near zero. Below roughly 0.5 a
     * section heading behind the panel starts fighting the text on it, and no amount of blur
     * rescues that. The slider controls how glassy it looks, not whether it stays readable.
     */
    fun tintAlpha(min: Float = 0.52f, max: Float = 0.97f): Float = lerp(min, max, frost)

    @Composable
    fun tint(elevation: Dp = 6.dp, min: Float = 0.52f, max: Float = 0.97f): Color =
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
    val intensity by rememberPreference(PlayerGlassIntensityKey, defaultValue = 1f)
    return GlassSpec(backdrop, intensity.coerceIn(0f, 1f))
}

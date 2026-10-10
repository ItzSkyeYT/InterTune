/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.theme

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.LocalRippleConfiguration
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.dd3boh.outertune.constants.Unreleased
import com.google.material.color.dynamiccolor.DynamicScheme
import com.google.material.color.hct.Hct
import com.google.material.color.scheme.SchemeTonalSpot
import com.google.material.color.score.Score

// TODO: support for custom accent
val DefaultThemeColor = Color(0xFFED5564)

@Composable
fun OuterTuneTheme(
    context: Context,
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    highContrastCompat: Boolean,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(darkTheme, pureBlack, themeColor) {
       if (themeColor == DefaultThemeColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val systemTheme = if (darkTheme) {
                dynamicDarkColorScheme(context).pureBlack(pureBlack)
            } else {
                dynamicLightColorScheme(context)
            }


            // when high contrast mode Android collapses all accent colours into (more or less) one shade. We use
            // secondaryContainer and onSecondaryContainer weirdly in several places in terms of theming so just replace
            // those with shades that make sense
            if (highContrastCompat) {
                systemTheme.copy(
                    secondaryContainer = systemTheme.surfaceContainerHigh,
                    onSecondaryContainer = systemTheme.secondary,
                )
            } else {
                systemTheme
            }
        } else {
            val source = Hct.fromInt(themeColor.toArgb())
            (if (Unreleased.COVER_ACCENT) CoverAccent.scheme(source, darkTheme) else SchemeTonalSpot(source, darkTheme, 0.0))
                .toColorScheme()
                .pureBlack(darkTheme && pureBlack)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
    ) {
        // Material's focus highlight is a 10% wash, which on a small keypad phone's screen is
        // hard to find. Only focus is made stronger: a press looks as it always has, and touch
        // never gives these controls focus in the first place.
        CompositionLocalProvider(
            LocalRippleConfiguration provides RippleConfiguration(rippleAlpha = KeyFocusRippleAlpha),
            content = content,
        )
    }
}

/** Material's own ripple alphas, with the focused state raised from 0.1 to stand out under a D-pad. */
private val KeyFocusRippleAlpha = RippleAlpha(
    draggedAlpha = 0.16f,
    focusedAlpha = 0.3f,
    hoveredAlpha = 0.08f,
    pressedAlpha = 0.1f,
)

fun Bitmap.extractThemeColor(): Color {
    val rankedColors = Score.score(mainColours())
    return Color(rankedColors.first())
}

/**
 * The colour the app's theme is built from: [extractThemeColor]'s, or with [CoverAccent] the same
 * hue with a chroma that says how much colour the cover has of it.
 */
fun Bitmap.extractThemeSource(): Color =
    if (Unreleased.COVER_ACCENT) Color(CoverAccent.source(mainColours())) else extractThemeColor()

/**
 * The colour to paint in what is to be the cover's colour, the widget's background for one:
 * [extractThemeColor]'s, or with [CoverAccent] the cover's commonest colour when it has none to take.
 */
fun Bitmap.extractCoverColor(): Color =
    if (Unreleased.COVER_ACCENT) Color(CoverAccent.paint(mainColours(), mainColours(everything = true))) else extractThemeColor()

/** A cover's main colours, and how many of its pixels each stands for. */
private fun Bitmap.mainColours(everything: Boolean = false): Map<Int, Int> =
    Palette.from(this)
        .maximumColorCount(8)
        // Palette leaves out what is nearly black or nearly white unless it is told not to.
        .apply { if (everything) clearFilters() }
        .generate()
        .swatches
        .associate { it.rgb to it.population }

/**
 * Two colours taken from the artwork, for the player background gradient.
 *
 * [Score.score] is asked first because it picks colours that work as a theme. It is filtered, so it
 * rejects anything it judges unusable, and on plenty of real covers it returns fewer than two.
 * Measured on The Eminem Show, a strongly red cover: it came back short and the background rendered
 * a flat neutral grey, which is the one outcome this function exists to avoid.
 *
 * Three tiers now. Scored colours, then the palette's own swatches, which are the colour clusters
 * actually present in the image and are therefore always album-coloured, and only then grey, which
 * now means "no usable palette at all" rather than "the scorer was fussy".
 */
fun Bitmap.extractGradientColors(): List<Color> {
    val palette = Palette.from(this)
        .maximumColorCount(16)
        .generate()

    val extractedColors = palette.swatches.associate { it.rgb to it.population }

    val orderedColors = Score.score(extractedColors, 2, 0xff4285f4.toInt(), true)
        .sortedByDescending { Color(it).luminance() }

    if (orderedColors.size >= 2) {
        return listOf(Color(orderedColors[0]), Color(orderedColors[1]))
    }

    // Straight from the image. Ordered by how much of the cover each one occupies, so the result is
    // what the artwork actually reads as, then lightest first for a top-down gradient.
    val fromSwatches = palette.swatches
        .sortedByDescending { it.population }
        .take(2)
        .map { Color(it.rgb) }
        .sortedByDescending { it.luminance() }

    if (fromSwatches.size >= 2) return fromSwatches

    // One swatch still beats none: pair it with a much darker version of itself.
    fromSwatches.firstOrNull()?.let { only ->
        return listOf(only, Color(only.red * 0.2f, only.green * 0.2f, only.blue * 0.2f))
    }

    return listOf(Color(0xFF595959), Color(0xFF0D0D0D))
}

fun DynamicScheme.toColorScheme() = ColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(primary),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    surfaceBright = Color(surfaceBright),
    surfaceDim = Color(surfaceDim),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    primaryFixed = Color(primaryFixed),
    primaryFixedDim = Color(primaryFixedDim),
    onPrimaryFixed = Color(onPrimaryFixed),
    onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
    secondaryFixed = Color(secondaryFixed),
    secondaryFixedDim = Color(secondaryFixedDim),
    onSecondaryFixed = Color(onSecondaryFixed),
    onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
    tertiaryFixed = Color(tertiaryFixed),
    tertiaryFixedDim = Color(tertiaryFixedDim),
    onTertiaryFixed = Color(onTertiaryFixed),
    onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
)

fun ColorScheme.pureBlack(apply: Boolean) =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

val ColorSaver = object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)
    override fun SaverScope.save(value: Color): Int = value.toArgb()
}

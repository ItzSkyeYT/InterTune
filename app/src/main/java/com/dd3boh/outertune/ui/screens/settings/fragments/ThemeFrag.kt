/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.DynamicThemeKey
import com.dd3boh.outertune.constants.HighContrastKey
import com.dd3boh.outertune.constants.PlayerLiquidGlassKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.LivingSmoothingKey
import com.dd3boh.outertune.constants.LivingStrengthKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.ui.player.LivingField
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget
import com.dd3boh.outertune.constants.BackAnimationsKey
import com.dd3boh.outertune.constants.PureBlackKey
import com.dd3boh.outertune.constants.PlayerGlassIntensityKey
import com.dd3boh.outertune.constants.GroupedPlayerControlsKey
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.floatingGlass
import com.dd3boh.outertune.ui.component.topBarSurfaceColor
import com.dd3boh.outertune.ui.utils.GlassSpec
import com.dd3boh.outertune.ui.utils.LocalAppBackdrop
import com.dd3boh.outertune.ui.utils.LocalAppBackdropAvailable
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

@Composable
fun ColumnScope.ThemeAppFrag() {
    val (darkMode, onDarkModeChange) = rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val (dynamicTheme, onDynamicThemeChange) = rememberPreference(DynamicThemeKey, defaultValue = true)
    val (highContrastCompat, onHccChange) = rememberPreference(HighContrastKey, defaultValue = false)

    val (pureBlack, onPureBlackChange) = rememberPreference(PureBlackKey, defaultValue = false)
    val (backAnimations, onBackAnimationsChange) = rememberPreference(BackAnimationsKey, defaultValue = true)

    SwitchPreference(
        title = { Text(stringResource(R.string.back_animations)) },
        description = stringResource(R.string.back_animations_description),
        icon = { Icon(Icons.Rounded.SwipeLeft, null) },
        checked = backAnimations,
        onCheckedChange = onBackAnimationsChange
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.enable_dynamic_theme)) },
        description = stringResource(R.string.enable_dynamic_theme_description),
        icon = { Icon(Icons.Rounded.Palette, null) },
        checked = dynamicTheme,
        onCheckedChange = onDynamicThemeChange
    )
    AnimatedVisibility(!dynamicTheme) {
        SwitchPreference(
            title = { Text(stringResource(R.string.high_contrast)) },
            description = stringResource(R.string.high_contrast_description),
            icon = { Icon(Icons.Rounded.Contrast, null) },
            checked = highContrastCompat,
            onCheckedChange = onHccChange
        )
    }
    EnumListPreference(
        title = { Text(stringResource(R.string.dark_theme)) },
        icon = { Icon(Icons.Rounded.DarkMode, null) },
        selectedValue = darkMode,
        onValueSelected = onDarkModeChange,
        valueText = {
            when (it) {
                DarkMode.ON -> stringResource(R.string.dark_theme_on)
                DarkMode.OFF -> stringResource(R.string.dark_theme_off)
                DarkMode.AUTO -> stringResource(R.string.dark_theme_follow_system)
            }
        }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.pure_black)) },
        description = stringResource(R.string.pure_black_description),
        icon = { Icon(Icons.Rounded.Contrast, null) },
        checked = pureBlack,
        onCheckedChange = onPureBlackChange
    )
}


@Composable
fun ColumnScope.ThemePlayerFrag() {
    val (playerBackground, onPlayerBackgroundChange) = rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = DEFAULT_PLAYER_BACKGROUND
    )
    val availableBackgroundStyles = PlayerBackgroundStyle.entries.filter {
        when (it) {
            PlayerBackgroundStyle.BLUR -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            PlayerBackgroundStyle.LIVING -> Unreleased.LIVING_BACKGROUND
            else -> true
        }
    }
    val (glassIntensity, onGlassIntensityChange) = rememberPreference(
        PlayerGlassIntensityKey,
        defaultValue = 1f
    )
    val (liquidGlass, onChromaticShockChange) = rememberPreference(
        PlayerLiquidGlassKey,
        defaultValue = false
    )
    val (groupedControls, onGroupedControlsChange) = rememberPreference(
        GroupedPlayerControlsKey,
        defaultValue = true
    )

    EnumListPreference(
        modifier = Modifier.tourTarget(Tour.SETTING_PLAYER_BACKGROUND),
        title = { Text(stringResource(R.string.player_background_style)) },
        icon = { Icon(Icons.Rounded.BlurOn, null) },
        selectedValue = playerBackground,
        onValueSelected = onPlayerBackgroundChange,
        valueText = {
            when (it) {
                PlayerBackgroundStyle.FOLLOW_THEME -> stringResource(R.string.player_background_default)
                PlayerBackgroundStyle.GRADIENT -> stringResource(R.string.player_background_gradient)
                PlayerBackgroundStyle.BLUR -> stringResource(R.string.player_background_blur)
                PlayerBackgroundStyle.LIVING -> stringResource(R.string.player_background_living)
            }
        },
        values = availableBackgroundStyles
    )

    // Only there with the style it belongs to, and right under it. Shown through the same reveal
    // the glass slider further down uses.
    val (livingStrength, onLivingStrengthChange) = rememberPreference(
        LivingStrengthKey,
        defaultValue = LivingField.DEFAULT_STRENGTH
    )
    val (livingSmoothing, onLivingSmoothingChange) = rememberPreference(
        LivingSmoothingKey,
        defaultValue = LivingField.DEFAULT_SMOOTHING
    )
    AnimatedVisibility(playerBackground == PlayerBackgroundStyle.LIVING && Unreleased.LIVING_BACKGROUND) {
        Column {
            LivingBlurSample(
                strength = livingStrength,
                smoothing = livingSmoothing,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp)
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_living_strength)) },
                description = stringResource(R.string.player_living_strength_description),
                icon = { Icon(Icons.Rounded.GraphicEq, null) },
                // A label for the slider underneath, not a button.
                onClick = null
            )
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(
                        R.string.player_glass_intensity_value,
                        (livingStrength * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Slider(
                    value = livingStrength,
                    onValueChange = onLivingStrengthChange,
                    valueRange = 0f..1f
                )
            }
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_living_smoothing)) },
                description = stringResource(R.string.player_living_smoothing_description),
                icon = { Icon(Icons.Rounded.Waves, null) },
                onClick = null
            )
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(
                        R.string.player_glass_intensity_value,
                        (livingSmoothing * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Slider(
                    value = livingSmoothing,
                    onValueChange = onLivingSmoothingChange,
                    valueRange = 0f..1f
                )
            }
        }
    }

    // Glass has nothing to act on when the background follows the theme: there is no artwork blur
    // and no gradient to make translucent.
    val glassApplies = playerBackground != PlayerBackgroundStyle.FOLLOW_THEME

    // One switch, not two. There used to be a separate "glass player background" that only
    // recoloured the now playing screen, and having two settings both called glass sharing one
    // intensity slider is what made people turn the slider to 0 looking for more glass.
    //
    // The refraction half needs API 33 for RuntimeShader; the background half does not, so below
    // 33 this still does something and the description says which half you get.
    SwitchPreference(
        title = { Text(stringResource(R.string.player_liquid_glass)) },
        description = stringResource(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                R.string.player_liquid_glass_description
            else
                R.string.player_liquid_glass_description_legacy
        ),
        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
        checked = liquidGlass,
        onCheckedChange = onChromaticShockChange
    )

    // Always drawn, dimmed when glass is off, rather than hidden. It only does anything with glass
    // on (Player.kt gates on `liquidGlass && groupedControls`), but hiding it outright meant the
    // 0.10.2 notes could point people at a setting that was not on their screen, and it read as
    // missing. Its description already says it needs Liquid glass, which is the whole explanation.
    SwitchPreference(
        title = { Text(stringResource(R.string.grouped_player_controls)) },
        description = stringResource(R.string.grouped_player_controls_description),
        icon = { Icon(Icons.Rounded.ViewAgenda, null) },
        checked = groupedControls,
        onCheckedChange = onGroupedControlsChange,
        isEnabled = liquidGlass
    )

    // Below Android 13, and beside the side navigation, the app draws no floating glass at all, so a
    // sample there would stay flat whatever the switch and the slider said. There is none, and the
    // slider, which still changes the now playing screen, says why.
    val noSampleBecause = when {
        LocalAppBackdropAvailable.current -> null
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> R.string.player_glass_preview_needs_13
        else -> R.string.player_glass_preview_side_navigation
    }

    // Shown rather than described, right above the slider that changes it. Always drawn where the
    // app can draw glass, so turning Liquid glass off shows the flat look that comes back.
    if (noSampleBecause == null) {
        GlassSample(
            intensity = glassIntensity,
            grouped = liquidGlass && groupedControls,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp)
        )
    }

    // The intensity slider stays hidden rather than dimmed: a greyed out slider still looks
    // draggable, and this one already has history of people misreading what it belongs to.
    // One reveal rather than two consecutive `if` blocks, which popped content into existence in a
    // single frame directly under the switch that was just tapped. ThemeAppFrag above already
    // animates its equivalent, so the file disagreed with itself. ColumnScope defaults, to match.
    AnimatedVisibility(liquidGlass) {
        // Required: AnimatedVisibility takes one child, and this holds two.
        Column {
            // Below BOTH toggles, not nested under the first. It drives both, and while it sat
            // under the vivid-background switch people read it as belonging to that alone and
            // turned it down looking for more glass, which does the opposite.
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_glass_intensity)) },
                description = stringResource(R.string.player_glass_intensity_description),
                content = noSampleBecause?.let { reason ->
                    @Composable {
                        Text(
                            text = stringResource(reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                },
                icon = { Icon(Icons.Rounded.Tune, null) },
                // A label for the slider underneath, not a button.
                onClick = null
            )
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(
                        R.string.player_glass_intensity_value,
                        (glassIntensity * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Slider(
                    value = glassIntensity,
                    onValueChange = onGlassIntensityChange,
                    valueRange = 0f..1f
                )
            }
        }
    }
}

/**
 * A strip of made-up artwork with the player's five buttons floating on it, drawn as the app draws
 * them right now: glass at the current intensity when glass is on, the flat surfaces when it is
 * off, and in one bar or each on its own as "Group the player buttons" says. The slider means "how much glass", and people have read it
 * backwards before, so it is worth letting them see which way it goes.
 *
 * For looking at only. Nothing in it takes a touch, so a drag that starts on it still scrolls the
 * page, and accessibility services skip it rather than announce a Play button that does nothing.
 *
 * It cannot use the app's or the screen's backdrop, since it sits inside both (see TopBarGlass.kt),
 * so it records one of its own from just this strip. The controls are beside that recording, not
 * in it, so nothing reads a layer it is part of. Kept small because blur costs by area: one short
 * strip recorded once, and three small shapes blurring only what is under them.
 */
@Composable
private fun GlassSample(intensity: Float, grouped: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // The app's own test for glass, so the sample is flat wherever the floating controls are.
    val glassOn = LocalAppBackdrop.current != null
    val backdrop = rememberLayerBackdrop()
    val glass = if (glassOn) GlassSpec(backdrop, intensity.coerceIn(0f, 1f)) else null
    val flat = topBarSurfaceColor()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clearAndSetSemantics { }
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .then(if (glass != null) Modifier.layerBackdrop(backdrop) else Modifier)
        ) {
            // The theme's own colours, so it matches whatever the app is wearing.
            drawRect(
                Brush.linearGradient(
                    listOf(colors.primary, colors.tertiary, colors.secondary),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
            // Hard edges and stripes, because that is where glass shows: a smooth gradient looks
            // the same through any amount of blur or bend. The stripes are just wide enough to
            // survive the lightest blur, so they show through at high intensity and melt away at
            // low, which is the difference the slider makes.
            val h = size.height
            drawCircle(colors.tertiaryContainer, radius = h * 0.62f, center = Offset(size.width * 0.12f, h * 0.1f))
            drawCircle(colors.primaryContainer, radius = h * 0.5f, center = Offset(size.width * 0.52f, h * 1.02f))
            drawCircle(colors.secondaryContainer, radius = h * 0.4f, center = Offset(size.width * 0.84f, h * 0.18f))
            val stripe = colors.onPrimary.copy(alpha = 0.3f)
            val gap = 18.dp.toPx()
            var x = -h
            while (x < size.width) {
                drawLine(stripe, Offset(x, h), Offset(x + h, 0f), strokeWidth = 3.dp.toPx())
                x += gap
            }
        }

        // The player's own five buttons, as "Group the player buttons" has them: inside one glass
        // bar, or each on its own. The switch is right above, and what it does could not be seen
        // without going to the player and back.
        val buttons = listOf(Icons.Rounded.Shuffle, Icons.Rounded.SkipPrevious, Icons.Rounded.PlayArrow, Icons.Rounded.SkipNext, Icons.Rounded.Repeat)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(46.dp)
                .then(
                    if (grouped && glass != null) Modifier.floatingGlass(glass, CircleShape)
                    else Modifier
                )
                .padding(horizontal = 6.dp)
        ) {
            buttons.forEach { icon ->
                val play = icon == Icons.Rounded.PlayArrow
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(if (play) 38.dp else 36.dp)
                        .then(
                            when {
                                // Play is the filled one in the player too, grouped or not.
                                play && glass != null && !grouped -> Modifier.floatingGlass(glass, CircleShape, glass.buttonTint())
                                play -> Modifier.background(colors.primaryContainer, CircleShape)
                                grouped && glass != null -> Modifier
                                glass != null -> Modifier.floatingGlass(glass, CircleShape)
                                else -> Modifier.background(flat, CircleShape)
                            }
                        )
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (play) colors.onPrimaryContainer else colors.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}


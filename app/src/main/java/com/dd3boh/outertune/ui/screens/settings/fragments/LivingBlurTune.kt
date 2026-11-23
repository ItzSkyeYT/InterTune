/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LivingColours
import com.dd3boh.outertune.constants.LivingColoursKey
import com.dd3boh.outertune.constants.LivingSmoothingKey
import com.dd3boh.outertune.constants.LivingStrengthKey
import com.dd3boh.outertune.constants.PlayerGlassIntensityKey
import com.dd3boh.outertune.constants.PlayerLiquidGlassKey
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.ui.player.LivingBackground
import com.dd3boh.outertune.ui.player.LivingField
import com.dd3boh.outertune.ui.utils.GlassSpec
import com.dd3boh.outertune.ui.utils.LocalGlassStill
import com.dd3boh.outertune.ui.utils.stillGlass
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Living blur tuned on the whole screen (his ask of 20 Nov 2026, a mockup first): the player's own
 * picture behind everything, the cover where the player has it, and the three settings that shape
 * the picture in one bar where the player's controls are, glass with Liquid glass on. Every change is the setting itself,
 * so the player has it at once. The eye hides the bar, to see the picture whole.
 *
 * In the debug app, the Preview and the Tester app (Unreleased.LIVING_TUNE), his pick of 20 Nov 2026.
 */
@Composable
fun LivingBlurTune(onClose: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val song by playerConnection.mediaMetadata.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val (strength, onStrength) = rememberPreference(LivingStrengthKey, LivingField.DEFAULT_STRENGTH)
    val (smoothing, onSmoothing) = rememberPreference(LivingSmoothingKey, LivingField.DEFAULT_SMOOTHING)
    val (colours, onColours) = rememberEnumPreference(LivingColoursKey, LivingColours.MAIN)
    val glassIntensity by rememberPreference(PlayerGlassIntensityKey, 1f)
    // Glass only where the player has it (Ti-Die, #testers 21 Nov 2026: it was glass with Liquid glass off).
    val liquidGlass by rememberPreference(PlayerLiquidGlassKey, defaultValue = false)
    val still = LocalGlassStill.current
    var barShown by rememberSaveable { mutableStateOf(true) }
    val queueTitle = stringResource(R.string.player_living_sample_queue)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Edge to edge, the bars' icons are this screen's to set.
        val view = LocalView.current
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        DisposableEffect(view, dark) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            onDispose { }
        }
        val backdrop = rememberLayerBackdrop()
        // The player's wash over the picture, as the sample in Settings has it.
        val scrim = (if (dark) Color.Black else Color.White).copy(alpha = if (dark) 0.3f else 0.45f)
        val spec = GlassSpec(backdrop, glassIntensity.coerceIn(0f, 1f), still = still)
        val pane = if (liquidGlass) Pane(spec, if (still) spec.stillFill() else spec.tint())
        // Without glass a plain back panel, nothing blurred and no rim, so what is on it stays readable.
        else Pane(spec, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), glass = false)
        val reset = {
            onStrength(LivingField.DEFAULT_STRENGTH)
            onSmoothing(LivingField.DEFAULT_SMOOTHING)
            onColours(LivingColours.MAIN)
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxSize().then(if (still || !liquidGlass) Modifier else Modifier.layerBackdrop(backdrop))) {
                LivingBackground(
                    cover = song?.getThumbnailModel(100, 100),
                    tap = playerConnection.service.levelTap,
                    playing = isPlaying,
                    onScreen = true,
                    strength = strength,
                    smoothing = smoothing,
                    colours = colours,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(scrim))
            }

            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    TuneRoundButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), pane, onClose)
                    Spacer(Modifier.weight(1f))
                    if (pane.glass) {
                        TextButton(onClick = reset, modifier = Modifier.height(48.dp).pane(pane, RoundedCornerShape(24.dp))) {
                            Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        FilledTonalButton(onClick = reset, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.reset)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    TuneRoundButton(
                        if (barShown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        stringResource(if (barShown) R.string.living_tune_hide else R.string.living_tune_show),
                        pane,
                    ) { barShown = !barShown }
                }

                // The cover and the song, where and as the player has them.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = PlayerHorizontalPadding),
                ) {
                    val artPx = with(LocalDensity.current) { 480.dp.roundToPx() }
                    AsyncImage(
                        model = song?.getThumbnailModel(artPx, artPx),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = PlayerHorizontalPadding, vertical = 12.dp)) {
                    Text(
                        text = song?.title ?: stringResource(R.string.player_living_sample_silent),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    song?.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() }?.let { artists ->
                        Text(
                            text = artists,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // The bar keeps its room while it is hidden, so the cover never moves.
                var barHeight by remember { mutableIntStateOf(0) }
                Box(Modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { barHeight.toDp() })) {
                    // The plain one: the Column's own cannot be called from inside this Box.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = barShown,
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut() + slideOutVertically { it / 4 },
                    ) {
                        Column(
                            modifier = Modifier
                                .onSizeChanged { barHeight = it.height }
                                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                                .fillMaxWidth()
                                .pane(pane, RoundedCornerShape(28.dp))
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        ) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = colours == LivingColours.MAIN,
                                    onClick = { onColours(LivingColours.MAIN) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                ) { Text(stringResource(R.string.living_tune_three_colours), maxLines = 1) }
                                SegmentedButton(
                                    selected = colours == LivingColours.COVER,
                                    onClick = { onColours(LivingColours.COVER) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                ) { Text(stringResource(R.string.living_tune_cover), maxLines = 1) }
                            }
                            Spacer(Modifier.height(12.dp))
                            TuneSlider(R.string.living_tune_strength, strength, onStrength)
                            TuneSlider(R.string.living_tune_smoothing, smoothing, onSmoothing)
                            Spacer(Modifier.height(4.dp))
                            if (song != null) {
                                // The player's own three, in its order.
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    IconButton(enabled = canSkipPrevious, onClick = { playerConnection.loadQueueIfEmpty().seekToPrevious() }) {
                                        Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.widget_previous))
                                    }
                                    FilledIconButton(onClick = { playerConnection.togglePlayPause() }, modifier = Modifier.size(56.dp)) {
                                        Icon(
                                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            stringResource(if (isPlaying) R.string.widget_pause else R.string.widget_play),
                                        )
                                    }
                                    IconButton(enabled = canSkipNext, onClick = { playerConnection.loadQueueIfEmpty().seekToNext() }) {
                                        Icon(Icons.Rounded.SkipNext, stringResource(R.string.widget_next))
                                    }
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = { scope.launch { playSongToTuneBy(context, database, playerConnection, queueTitle) } },
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                ) { Text(stringResource(R.string.player_living_sample_play)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TuneSlider(@StringRes label: Int, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.player_glass_intensity_value, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
}

/** A round button over the picture: on a pane of glass, or Material's own tonal one where the player has no glass. */
@Composable
private fun TuneRoundButton(icon: ImageVector, description: String, pane: Pane, onClick: () -> Unit) {
    if (!pane.glass) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, description) }
        return
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).pane(pane, CircleShape)) {
        IconButton(onClick = onClick) { Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface) }
    }
}

/**
 * What every pane on this screen is made of: the dock's glass over the picture, or its still frost on Saver, and the wash
 * over it; with Liquid glass off ([glass] false) only the wash, a plain panel.
 */
private class Pane(val spec: GlassSpec, val tint: Color, val glass: Boolean = true)

/** One pane of glass, the queue bar's recipe (Queue.kt glassPane), with a wash that keeps what is on it readable. */
private fun Modifier.pane(pane: Pane, shape: Shape): Modifier {
    val spec = pane.spec
    if (!pane.glass) return this.background(pane.tint, shape)
    if (spec.still) return this.stillGlass { shape }.background(pane.tint, shape)
    return this
        .drawBackdrop(
            backdrop = spec.backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(spec.blur.toPx())
                lens(
                    refractionHeight = 20f.dp.toPx() * spec.lensT,
                    refractionAmount = 28f.dp.toPx() * spec.lensT,
                    depthEffect = true,
                )
            },
        )
        .background(pane.tint, shape)
}

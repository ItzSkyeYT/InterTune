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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.ui.utils.SameTextSize
import com.dd3boh.outertune.ui.utils.shrinkToFit
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Living blur tuned on the whole screen (his ask of 20 Nov 2026, a mockup first): the player's own
 * picture behind everything, the cover where the player has it, and the three settings that shape
 * the picture in one bar where the player's controls are, glass with Liquid glass on. Every change is the setting itself,
 * so the player has it at once. The eye hides the bar, to see the picture whole.
 *
 * For everyone in 0.13 (Unreleased.LIVING_TUNE), his pick of 20 Nov 2026, and since 27 Nov 2026 the one place the
 * three are set: their rows in Look and feel went, and the button under the sample there opens this.
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

            // Wider than tall, a phone on its side: the cover beside the bar, and the bar's two sliders side by side.
            val sideBySide = with(LocalLandscape.current) { TunePlan.sideBySide(windowWidth, windowHeight) }
            // Clear of the camera's cut too, which a phone on its side has at one end.
            TuneScreen(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), sideBySide) {
                // The buttons' outer edges on the bar's, 12 dp in from the sides.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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

                // The cover and the song, where and as the player has them, in the room the top row and the bar leave: the
                // cover as big as it can be there, never under the buttons or on the song's lines (his report of 26 Nov 2026).
                CoverAndSong(
                    modifier = Modifier.padding(start = PlayerHorizontalPadding, top = 8.dp, end = PlayerHorizontalPadding, bottom = 16.dp),
                    song = {
                        Column {
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
                    },
                ) {
                    val artPx = with(LocalDensity.current) { 480.dp.roundToPx() }
                    AsyncImage(
                        model = song?.getThumbnailModel(artPx, artPx),
                        contentDescription = null,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius * 2))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    )
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
                        // The settings, and the play row under them that is never scrolled away nor cut (TuneBar).
                        TuneBar(pane, onHeight = { barHeight = it }, settings = {
                            // The two names one size, kept beside the tick (SegmentLabel).
                            val segments = remember { SameTextSize() }
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = colours == LivingColours.MAIN,
                                    onClick = { onColours(LivingColours.MAIN) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                ) { SegmentLabel(stringResource(R.string.living_tune_three_colours), segments) }
                                SegmentedButton(
                                    selected = colours == LivingColours.COVER,
                                    onClick = { onColours(LivingColours.COVER) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                ) { SegmentLabel(stringResource(R.string.living_tune_cover), segments) }
                            }
                            Spacer(Modifier.height(12.dp))
                            TuneSliders(sideBySide, strength, onStrength, smoothing, onSmoothing)
                        }) {
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

/**
 * The bar: [settings] over [play] on one pane, the play row with as much room under it as at the sides and 16 dp over it,
 * clear of the handle above. Where the screen is too short for all of it (a small window, large text, a phone on its side
 * with his own colours' row) the settings scroll and the play row stays whole under them: it was the one cut, half out of
 * the bar, on his S25U on its side (his report of 27 Nov 2026).
 */
@Composable
private fun TuneBar(
    pane: Pane,
    onHeight: (Int) -> Unit,
    settings: @Composable ColumnScope.() -> Unit,
    play: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .onSizeChanged { onHeight(it.height) }
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .fillMaxWidth()
            .pane(pane, shape)
            .clip(shape),
    ) {
        Column(
            // What the play row leaves, and no more than the settings need.
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 16.dp, end = 20.dp),
            content = settings,
        )
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 20.dp), content = play)
    }
}

/**
 * A name in the segmented button, kept beside its tick: Material gives the label the whole segment, the tick's room too,
 * and in French at text size 1.3 "Trois couleurs" ran into the divider with the tick on the outline. Where even that room
 * is too short the two names get smaller together ([names]).
 */
@Composable
private fun SegmentLabel(text: String, names: SameTextSize) {
    // The button's own style, which gives its size in sp, as shrinkToFit needs.
    val style = MaterialTheme.typography.labelLarge.merge(LocalTextStyle.current)
    val autoSize = style.shrinkToFit(smallest = 11.sp, text = text, row = names)
    val tick = SegmentedButtonDefaults.IconSize + SEGMENT_ICON_GAP
    // Made again for each new autoSize: a Text does not see that one changed (see shrinkToFit).
    key(autoSize) {
        Text(
            text,
            style = style,
            autoSize = autoSize,
            maxLines = 1,
            modifier = Modifier.layout { measurable, constraints ->
                val room = if (constraints.hasBoundedWidth) {
                    constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - tick.roundToPx()).coerceAtLeast(0))
                } else constraints
                val placeable = measurable.measure(room)
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            },
        )
    }
}

/** Between the segmented button's tick and its name (Material's own, not public). */
private val SEGMENT_ICON_GAP = 8.dp

/**
 * Strength and Smoothing, one over the other; on a phone on its side ([sideBySide]) side by side, each over half the bar.
 * On its side the bar has only the screen's height, and one over the other they left the play row no room: on his S25U
 * with his own colours' row the play button was cut in half (his report of 27 Nov 2026). Their names one size.
 */
@Composable
private fun TuneSliders(sideBySide: Boolean, strength: Float, onStrength: (Float) -> Unit, smoothing: Float, onSmoothing: (Float) -> Unit) {
    val names = remember { SameTextSize() }
    if (sideBySide) {
        Row(horizontalArrangement = Arrangement.spacedBy(SLIDERS_GAP)) {
            Column(Modifier.weight(1f)) { TuneSlider(R.string.living_tune_strength, strength, onStrength, names) }
            Column(Modifier.weight(1f)) { TuneSlider(R.string.living_tune_smoothing, smoothing, onSmoothing, names) }
        }
    } else {
        TuneSlider(R.string.living_tune_strength, strength, onStrength, names)
        // A slider's name nearer its own slider than the one above.
        Spacer(Modifier.height(8.dp))
        TuneSlider(R.string.living_tune_smoothing, smoothing, onSmoothing, names)
    }
}

/** Between the two sliders side by side: the bar's own margin. */
private val SLIDERS_GAP = 20.dp

/** A slider with its name and value over it. The name smaller where half a narrow bar cannot hold it whole ([names]). */
@Composable
private fun TuneSlider(@StringRes label: Int, value: Float, onChange: (Float) -> Unit, names: SameTextSize) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val name = stringResource(label)
        val style = MaterialTheme.typography.titleSmall
        val autoSize = style.shrinkToFit(smallest = 11.sp, text = name, row = names)
        // Made again for each new autoSize: a Text does not see that one changed (see shrinkToFit).
        key(autoSize) {
            Text(
                name,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                autoSize = autoSize,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.player_glass_intensity_value, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
}

/**
 * The tuner's three parts, in this order: the top row, the cover with the song, the bar. Upright they stand one over the
 * other, the bar at the bottom with the room it needs and the cover in what is left between. Wider than tall, a phone on
 * its side, the cover is beside the bar, as the player has it: the bar has the end half, top to bottom, and the top row
 * and the cover share the other. [sideBySide] is the window's (TunePlan.sideBySide), which the bar's sliders go by too.
 */
@Composable
private fun TuneScreen(modifier: Modifier, sideBySide: Boolean, content: @Composable () -> Unit) {
    Layout(content, modifier) { parts, constraints ->
        val (topRow, coverAndSong, bar) = parts
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val barWidth = TunePlan.barWidth(width, sideBySide)
        val startWidth = if (sideBySide) width - barWidth else width
        val top = topRow.measure(Constraints(maxWidth = startWidth, maxHeight = height))
        val below = (height - top.height).coerceAtLeast(0)
        // Side by side its top stops where the buttons' tops are, 8 dp under the status bar.
        val barRoom = if (sideBySide) (height - BAR_TOP.roundToPx()).coerceAtLeast(0) else below
        val barPart = bar.measure(Constraints(maxWidth = barWidth, maxHeight = barRoom))
        val room = coverAndSong.measure(
            Constraints.fixed(
                width = startWidth,
                height = if (sideBySide) below else (below - barPart.height).coerceAtLeast(0),
            )
        )
        layout(width, height) {
            top.placeRelative(0, 0)
            room.placeRelative(0, top.height)
            barPart.placeRelative(width - barPart.width, height - barPart.height)
        }
    }
}

/**
 * The [cover], square, as big as the room lets it be with the [song]'s lines under it, the two together in the middle of
 * the room. The lines start where the cover starts and may run on to the room's end, past a cover smaller than the
 * room is wide. Nothing goes past the room: the lines are left out where even they do not fit.
 */
@Composable
private fun CoverAndSong(modifier: Modifier, song: @Composable () -> Unit, cover: @Composable () -> Unit) {
    Layout(contents = listOf(cover, song), modifier = modifier) { (coverParts, songParts), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val lines = songParts.single()
        val gap = COVER_SONG_GAP.roundToPx()
        val side = TunePlan.coverSide(width, height, lines.maxIntrinsicHeight(width), gap)
        val x = if (side > 0) (width - side) / 2 else 0
        val coverPart = coverParts.single().measure(Constraints.fixed(side, side))
        val linesPart = lines.measure(Constraints(minWidth = width - x, maxWidth = width - x))
        val under = if (side > 0) side + gap else 0
        val y = ((height - under - linesPart.height) / 2).coerceAtLeast(0)
        layout(width, height) {
            coverPart.placeRelative(x, y)
            if (y + under + linesPart.height <= height) linesPart.placeRelative(x, y + under)
        }
    }
}

/** Between the cover and the song's title. */
private val COVER_SONG_GAP = 12.dp

/** Above the bar where it has the screen's whole height: the top row's own margin over its buttons. */
private val BAR_TOP = 8.dp

/** The tuner's arithmetic, apart so that LivingBlurTuneTest can hold it to a screen's numbers. */
internal object TunePlan {
    /** A window wider than tall, a phone on its side: the cover beside the bar, and the bar's sliders side by side. */
    fun sideBySide(width: Dp, height: Dp): Boolean = width > height

    /** The bar's width: the end half side by side, else the whole width. */
    fun barWidth(width: Int, sideBySide: Boolean): Int = if (sideBySide) width / 2 else width

    /** The cover's side in a room [width] by [height], with [songHeight] of lines [gap] under it: as big as both let it be. */
    fun coverSide(width: Int, height: Int, songHeight: Int, gap: Int): Int = min(width, height - songHeight - gap).coerceAtLeast(0)
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

/**
 * Whether the whole-screen tuner is open. The activity keeps it and draws the tuner over every screen ([LivingTuneHost]);
 * Look and feel's button opens it. Look and feel kept it before, and turning the phone, which moves Look and feel between a
 * page of its own and Settings' two panes, made it anew and closed the tuner (his report of 27 Nov 2026). Saved with the
 * activity, so a change of language or text size, which makes the activity anew, leaves it open too.
 */
@Stable
class LivingTuneDoor {
    var open by mutableStateOf(false)

    companion object {
        val Saver: Saver<LivingTuneDoor, Boolean> = Saver(save = { it.open }, restore = { LivingTuneDoor().apply { open = it } })
    }
}

/** The activity's [LivingTuneDoor]. Where no activity gives one (a preview) a door of its own, which nothing draws. */
val LocalLivingTune = staticCompositionLocalOf { LivingTuneDoor() }

/** The tuner while [door] is open, over whatever is on screen. Its own scope, so opening it recomposes nothing else. */
@Composable
fun LivingTuneHost(door: LivingTuneDoor) {
    if (door.open) LivingBlurTune(onClose = { door.open = false })
}

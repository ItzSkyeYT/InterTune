/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.mix.KeyEstimate
import com.dd3boh.outertune.mix.edit.EffectsRow
import com.dd3boh.outertune.mix.edit.EqRow
import com.dd3boh.outertune.mix.edit.FilterRow
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.PartsRow
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.SimpleRead
import com.dd3boh.outertune.mix.edit.SimpleRows
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.VolumeRow
import com.dd3boh.outertune.mix.edit.goneAt
import com.dd3boh.outertune.mix.edit.laneAt
import com.dd3boh.outertune.ui.utils.LocalLandscape
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The lengths the Simple sheet's pill offers, in bars; 32 is shown too, never possible (DESIGN.md 3.8). */
private val SIMPLE_BARS = listOf(4, 8, 16)

/** How many lines the line under the waveforms may take before it is cut. */
internal const val WHERE_LINES = 3

/**
 * The sheet's height in px out of [maxPx]: 92% upright and 94% on its side, the player showing
 * above it, and never so tall that its top reaches into the last [clearOfBarPx] (the status bar
 * and a little room), where the clock and the battery were drawn over its corners on its side.
 */
internal fun sheetHeight(maxPx: Int, landscape: Boolean, clearOfBarPx: Int): Int =
    minOf((maxPx * if (landscape) 0.94f else 0.92f).toInt(), maxPx - clearOfBarPx).coerceAtLeast(0)

/**
 * Edit transition, Simple (DESIGN.md, "Two levels"): a sheet over the player with both songs'
 * waveforms and the change as a window on them, dragged to move it a bar at a time; the length,
 * Beat matching, the presets, and one row per choice. Advanced is the full page on the same spec,
 * one tap away, so going back and forth loses nothing.
 *
 * The page around it ([MixEditorScreen]) owns the preview, Save, the question before changes are
 * lost and the line's messages; this draws them and hands the taps back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixSimpleSheet(
    vm: MixEditorViewModel,
    words: EditorWords,
    colors: MixColors,
    guard: String?,
    playing: Boolean,
    playU: () -> Double,
    said: String?,
    onSay: (String) -> Unit,
    onPlay: () -> Unit,
    onPreset: (Preset, SnackbarHostState) -> Unit,
    onAdvanced: () -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    val phase by vm.phase.collectAsState()
    val ui by vm.ui.collectAsState()
    val songA by vm.songA.collectAsState()
    val songB by vm.songB.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val landscape = LocalLandscape.current.active
    val host = remember { SnackbarHostState() }
    val scheme = MaterialTheme.colorScheme
    val look = remember(scheme, colors) { SimpleLook.of(scheme, colors) }

    // Swiped away, Back, or a tap above it: with changes, the page asks first and the sheet stays.
    val dirty by rememberUpdatedState(ui?.dirty == true)
    val close by rememberUpdatedState(onClose)
    var switching by remember { mutableStateOf(false) }
    val confirm = remember {
        { value: SheetValue ->
            if (value == SheetValue.Hidden && dirty && !switching) {
                close()
                false
            } else {
                true
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirm)
    fun toAdvanced() {
        switching = true
        scope.launch { sheetState.hide() }.invokeOnCompletion { onAdvanced() }
    }
    // X slides the sheet away when there is nothing to lose, and asks first when there is.
    fun dismiss() {
        if (dirty) onClose() else scope.launch { sheetState.hide() }.invokeOnCompletion { onClose() }
    }
    fun setRow(change: (SimpleRows) -> SimpleRows?) {
        when (vm.edit { setRows(change) }) {
            RowEdit.REPLACED_ADVANCED -> scope.launch {
                val r = host.showSnackbar(resources.getString(R.string.mix_simple_replaced), resources.getString(R.string.mix_edit_undo), duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) vm.edit { undo() }
            }
            RowEdit.NEEDS_PARTS -> onSay(resources.getString(R.string.mix_edit_needs_parts))
            else -> {}
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        sheetMaxWidth = if (landscape) 840.dp else BottomSheetDefaults.SheetMaxWidth,
        dragHandle = { Grip() },
        // Below the status bar on its own: the sheet stops short of the top, the player showing above it.
        contentWindowInsets = {
            if (landscape) WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) else WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        },
    ) {
        val editor = vm.editor
        val spec = ui?.spec
        val ready = phase is MixEditorViewModel.Phase.Ready && editor != null && spec != null
        // Its top stays clear of the status bar: on its side 94% of the height ran under the clock.
        val clearOfBar = with(LocalDensity.current) { (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp).roundToPx() }
        Box(
            Modifier.layout { measurable, constraints ->
                val h = if (constraints.hasBoundedHeight) sheetHeight(constraints.maxHeight, landscape, clearOfBar).coerceAtLeast(constraints.minHeight) else constraints.minHeight
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        ) {
            Column {
                Header(
                    landscape = landscape,
                    canSave = ui?.dirty == true && ready,
                    onClose = ::dismiss,
                    onSave = onSave,
                    onAdvanced = ::toAdvanced,
                )
                // A guard that will refuse at playback: editing and saving still work.
                if (guard != null) {
                    Text(
                        guard,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
                @Composable
                fun SongsPart(waveHeight: Dp) {
                    SongLine(songA, words.aTitle, spec?.a?.bpm, editor?.facts?.a?.grid != null, vm.pair?.a?.key, words, look.dark)
                    when (val p = phase) {
                        is MixEditorViewModel.Phase.Failed -> FailedState(p, words.aTitle, words.bTitle, if (p.side == Side.A) songA else songB, vm)
                        else -> if (ready) {
                            Waves(vm, editor!!, spec!!, vm.pair!!, words, look, waveHeight, playing, playU) { onSay(words.limit(it)) }
                        } else {
                            Shimmer(look, waveHeight)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                    ) {
                        if (ready) LengthPill(vm, editor!!, spec!!, words) else Spacer(Modifier.width(1.dp))
                        WhereLine(editor, spec, words, phase, playing, playU, said, Modifier.weight(1f))
                        PlayButton(playing, ready, onPlay)
                    }
                    SongLine(songB, words.bTitle, spec?.b?.bpm, editor?.facts?.b?.grid != null, vm.pair?.b?.key, words, look.dark)
                }
                @Composable
                fun ColumnScope.ChoicesPart() {
                    if (ready) Choices(vm, editor!!, spec!!, ui!!, words, look, onPreset = { onPreset(it, host) }, onRow = ::setRow)
                    Spacer(Modifier.height(24.dp))
                }
                if (landscape) {
                    // On its side: two upright widths, the songs on the left, the choices on the right.
                    Row(Modifier.weight(1f)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { SongsPart(132.dp) }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { ChoicesPart() }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        LevelSwitch(simple = true, onSimple = {}, onAdvanced = ::toAdvanced, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                        SongsPart(176.dp)
                        ChoicesPart()
                    }
                }
            }
            SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** The colours of the Simple sheet: each song's from the theme, each row's as Advanced has them where they mean the same thing. */
@Stable
internal class SimpleLook(
    val dark: Boolean,
    val a: Color,
    val b: Color,
    val ink: Color,
    val faint: Color,
    val canvas: Color,
    val primary: Color,
    val volume: Color,
    val eq: Color,
    val filter: Color,
    val effects: Color,
    val parts: Color,
) {
    companion object {
        fun of(scheme: androidx.compose.material3.ColorScheme, colors: MixColors) = SimpleLook(
            dark = scheme.surface.luminance() < 0.5f,
            a = scheme.secondary,
            b = scheme.tertiary,
            ink = scheme.onSurface,
            faint = scheme.onSurfaceVariant,
            canvas = scheme.surfaceContainer,
            primary = scheme.primary,
            volume = scheme.onSurface,
            // Blue is the lows, purple a filter, everywhere in the editor (DESIGN.md 3.4).
            eq = colors.bassLows,
            filter = colors.filter,
            effects = colors.vocals,
            parts = colors.melody,
        )
    }
}

/** The sheet's handle: smaller than Material's, as the mock has it, so the songs start higher. */
@Composable
private fun Grip() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .size(width = 32.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
    )
}

/** X, the title and Save; on its side the Simple | Advanced switch sits here too. */
@Composable
private fun Header(landscape: Boolean, canSave: Boolean, onClose: () -> Unit, onSave: () -> Unit, onAdvanced: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp),
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.mix_edit_close)) }
        Text(
            stringResource(R.string.mix_edit_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
        )
        if (landscape) LevelSwitch(simple = true, onSimple = {}, onAdvanced = onAdvanced, modifier = Modifier.padding(end = 12.dp).width(220.dp))
        FilledTonalButton(onClick = onSave, enabled = canSave) { Text(stringResource(R.string.mix_edit_save), maxLines = 1) }
    }
}

/** Simple | Advanced: the two levels of the editor, on the one spec. */
@Composable
internal fun LevelSwitch(simple: Boolean, onSimple: () -> Unit, onAdvanced: () -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.height(40.dp)) {
        SegmentedButton(selected = simple, onClick = onSimple, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
            Text(stringResource(R.string.mix_simple_level_simple), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SegmentedButton(selected = !simple, onClick = onAdvanced, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
            Text(stringResource(R.string.mix_simple_level_advanced), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A song: cover, title and artists, its tempo, and its key where the key finder is sure of it. */
@Composable
private fun SongLine(song: Song?, title: String, bpm: Double?, sure: Boolean, key: KeyEstimate?, words: EditorWords, dark: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = song?.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val artists = song?.artists?.joinToString(", ") { it.name }.orEmpty()
            if (artists.isNotEmpty()) {
                Text(artists, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            if (bpm != null && sure) words.bpm(bpm) else stringResource(R.string.mix_edit_bpm_unknown),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        key?.takeIf { it.sure }?.let { KeyPill(it, dark) }
    }
}

/** A key as its Camelot code, tinted by its place on the wheel, so keys that mix well look alike. */
@Composable
private fun KeyPill(estimate: KeyEstimate, dark: Boolean) {
    val key = estimate.key
    val hue = (key.camelotNumber - 1) * 30f
    val back = if (dark) Color.hsl(hue, 0.32f, 0.24f) else Color.hsl(hue, 0.55f, 0.88f)
    val ink = if (dark) Color.hsl(hue, 0.55f, 0.82f) else Color.hsl(hue, 0.6f, 0.24f)
    val described = stringResource(R.string.mix_simple_key, key.camelot)
    Text(
        key.camelot,
        style = MaterialTheme.typography.labelMedium,
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .background(back, RoundedCornerShape(10.dp))
            .semantics { contentDescription = described }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The canvas while the two songs are read. */
@Composable
private fun Shimmer(look: SimpleLook, height: Dp) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(look.canvas),
    )
}

/** Where the change starts in A and what of A's end it leaves out; while it plays, what is happening; a limit hit, in red for 3 s. */
@Composable
private fun WhereLine(
    editor: MixEditor?,
    spec: TransitionSpec?,
    words: EditorWords,
    phase: MixEditorViewModel.Phase,
    playing: Boolean,
    playU: () -> Double,
    said: String?,
    modifier: Modifier,
) {
    val res = LocalResources.current
    val text by remember(editor, spec, words, phase, playing, said) {
        derivedStateOf {
            when {
                said != null -> said
                phase is MixEditorViewModel.Phase.Loading -> when (phase.stage) {
                    PairLoader.Stage.READING_A -> words.readingA()
                    PairLoader.Stage.READING_B -> words.readingB()
                    PairLoader.Stage.ANALYSING -> words.analysing()
                }
                editor == null || spec == null -> ""
                playing -> words.nowPlaying(editor, playU())
                else -> {
                    val lost = editor.unheardMs(spec)
                    if (lost >= 1000) res.getString(R.string.mix_simple_starts_lost, words.clock(spec.a.atMs), words.aTitle, words.clock(lost))
                    else res.getString(R.string.mix_simple_starts, words.clock(spec.a.atMs), words.aTitle)
                }
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (said != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        // Three lines: a long title in French at a large font cut "ne s'entendent pas" at two.
        maxLines = WHERE_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, onPlay: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onPlay),
    ) {
        Icon(
            if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            stringResource(if (playing) R.string.mix_edit_stop else R.string.mix_simple_hear),
            tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The length pill: 4, 8 and 16 bars, each with why it cannot be had where it cannot, and 32 never; seconds without a steady beat. */
@Composable
private fun LengthPill(vm: MixEditorViewModel, editor: MixEditor, spec: TransitionSpec, words: EditorWords) {
    var open by remember { mutableStateOf(false) }
    val described = stringResource(R.string.mix_edit_length) + ", " + words.length(spec)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(role = Role.DropdownList) { open = true }
                .semantics { contentDescription = described }
                .padding(start = 14.dp, end = 8.dp),
        ) {
            Text(words.length(spec), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = editor.lengthOptions()
            val shown = if (editor.facts.barsMode) {
                options.filter { o -> o.bars in SIMPLE_BARS || (o.lit && o.bars !in SIMPLE_BARS) }.sortedBy { it.ms }
            } else {
                options.filter { it.bars == 0 }
            }
            for (o in shown) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(if (o.bars > 0) words.bars(o.bars) else words.seconds(o.seconds))
                            o.refused?.let { Text(words.limit(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    trailingIcon = if (o.lit) { { Icon(Icons.Rounded.Check, null) } } else null,
                    enabled = o.refused == null,
                    onClick = {
                        open = false
                        if (!o.lit) vm.edit { setLength(o.bars, o.seconds) }
                    },
                )
            }
            if (editor.facts.barsMode) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(words.bars(32))
                            Text(stringResource(R.string.mix_simple_length_no_32, words.aTitle), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

/** Beat matching, the presets with the lit one's line, and the rows. */
@Composable
private fun ColumnScope.Choices(
    vm: MixEditorViewModel,
    editor: MixEditor,
    spec: TransitionSpec,
    ui: MixEditorViewModel.Ui,
    words: EditorWords,
    look: SimpleLook,
    onPreset: (Preset) -> Unit,
    onRow: ((SimpleRows) -> SimpleRows?) -> Unit,
) {
    val res = LocalResources.current
    val allowed = spec.beatMatchAllowed() && editor.beatMatchPossible
    val checked = spec.beatMatched && editor.beatMatchPossible
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = allowed, role = Role.Switch) { vm.edit { setBeatMatch(!checked) } }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.mix_edit_beat_matching), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (allowed) 1f else 0.5f))
            Text(words.beatMatchLine(editor), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = allowed)
    }
    Text(
        stringResource(R.string.mix_edit_presets),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    )
    val chips: @Composable () -> Unit = {
        for (p in EditorPresets.ORDER) {
            val lit = ui.lit == p
            val enabled = (!p.needsParts || spec.lanes == LaneSet.PARTS) && !(p == Preset.CUSTOM && !lit && editor.lastCustom == null)
            FilterChip(
                selected = lit,
                enabled = enabled,
                onClick = { onPreset(p) },
                label = { Text(words.presetName(p), maxLines = 1) },
                leadingIcon = when {
                    p == Preset.AUTO -> { { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(FilterChipDefaults.IconSize)) } }
                    lit -> { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } }
                    else -> null
                },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { chips() }
    // Auto names what it picked when that is a preset, as the mock has it.
    val autoPick = remember(editor.auto) {
        EditorPresets.ORDER.firstOrNull { p ->
            p != Preset.AUTO && p != Preset.CUSTOM &&
                EditorPresets.moves(p, editor.auto.lengthBars, editor.auto.lanes, editor.auto.beat)?.let { EditorPresets.sameMoves(editor.auto.moves, it) } == true
        }
    }
    val line = when {
        ui.lit == Preset.CUSTOM -> stringResource(R.string.mix_simple_line_custom)
        ui.lit == Preset.AUTO && !spec.plain && autoPick != null ->
            res.getString(R.string.mix_simple_auto_picked, words.presetName(autoPick), words.presetLine(autoPick, spec, false))
        else -> words.presetLine(ui.lit, spec, editor.auto.plain)
    }
    Text(
        line,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
    )
    Rows(editor, spec, ui, words, look, onRow)
}

/** One option of a row: its name and, where it helps, what it does. */
private class Option(val name: String, val line: String?)

/** The rows as cards, each opened to its choices; a row the moves say more than shows who set it. */
@Composable
private fun Rows(
    editor: MixEditor,
    spec: TransitionSpec,
    ui: MixEditorViewModel.Ui,
    words: EditorWords,
    look: SimpleLook,
    onRow: ((SimpleRows) -> SimpleRows?) -> Unit,
) {
    val res = LocalResources.current
    val read: SimpleRead = remember(spec) { editor.rows(spec) }
    val byAuto = ui.lit == Preset.AUTO
    val unknown = stringResource(if (byAuto) R.string.mix_simple_set_by_auto else R.string.mix_simple_set_in_advanced)
    val note = stringResource(if (byAuto) R.string.mix_simple_auto_note else R.string.mix_simple_advanced_note)
    var open by rememberSaveable { mutableStateOf("") }
    val a = words.aTitle
    val b = words.bTitle
    val none = res.getString(R.string.mix_simple_none)
    // Timed in seconds: no bar line or beat to name.
    val timed = spec.bars == 0

    @Composable
    fun Card(tag: String, name: Int, accent: Color, options: List<Option>, selected: Int?, onPick: (Int) -> Unit) {
        RowCard(
            name = stringResource(name),
            accent = accent,
            value = selected?.let { options[it].name } ?: unknown,
            open = tag in open,
            onToggle = { open = if (tag in open) open.replace(tag, "") else open + tag },
            options = options,
            selected = selected,
            note = if (selected == null) note else null,
            onPick = onPick,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
        Card(
            "V", R.string.mix_simple_volume, look.volume,
            listOf(
                Option(res.getString(R.string.mix_simple_overlap), res.getString(R.string.mix_simple_overlap_line, a)),
                Option(res.getString(R.string.mix_simple_crossfade), res.getString(R.string.mix_simple_crossfade_line)),
                Option(res.getString(R.string.mix_simple_cut), res.getString(TimedLines.cut(timed))),
            ),
            read.volume?.ordinal,
        ) { i -> onRow { it.withVolume(VolumeRow.entries[i]) } }
        Card(
            "E", R.string.mix_simple_eq, look.eq,
            listOf(
                Option(none, null),
                Option(res.getString(R.string.mix_simple_bass_swap), res.getString(TimedLines.bassSwap(timed))),
                Option(res.getString(R.string.mix_simple_lows_first), res.getString(R.string.mix_simple_lows_first_line, words.owner(a))),
            ),
            read.eq?.ordinal,
        ) { i -> onRow { it.withEq(EqRow.entries[i]) } }
        Card(
            "F", R.string.mix_simple_filter, look.filter,
            listOf(
                Option(none, null),
                Option(res.getString(R.string.mix_simple_low_pass_in), res.getString(R.string.mix_simple_low_pass_in_line, b)),
                Option(res.getString(R.string.mix_simple_high_pass_out), res.getString(R.string.mix_simple_high_pass_out_line, a)),
                Option(res.getString(R.string.mix_simple_both), res.getString(R.string.mix_simple_both_line, b, a)),
            ),
            read.filter?.ordinal,
        ) { i -> onRow { it.withFilter(FilterRow.entries[i]) } }
        Card(
            "X", R.string.mix_simple_effects, look.effects,
            listOf(
                Option(none, null),
                Option(res.getString(R.string.mix_simple_echo_out), res.getString(R.string.mix_simple_echo_out_line, words.owner(a))),
            ),
            read.effects?.ordinal,
        ) { i -> onRow { it.withEffects(EffectsRow.entries[i]) } }
        // Parts only once the parts download is on the phone: a change on parts.
        if (spec.lanes == LaneSet.PARTS) {
            Card(
                "P", R.string.mix_simple_parts, look.parts,
                listOf(
                    Option(res.getString(R.string.mix_simple_parts_off), res.getString(R.string.mix_simple_parts_off_line)),
                    Option(words.presetName(Preset.VOCALS_OVER), res.getString(R.string.mix_simple_vocals_over_line, a, words.owner(b))),
                    Option(words.presetName(Preset.BEAT_UNDER), res.getString(R.string.mix_simple_beat_under_line, words.owner(a), words.owner(b))),
                    Option(words.presetName(Preset.DRUM_SWAP), res.getString(R.string.mix_simple_drum_swap_line)),
                ),
                read.parts?.ordinal,
            ) { i -> onRow { it.withParts(PartsRow.entries[i], spec.lanes) } }
        }
    }
}

/** One row as a card: its colour, its name and choice, opened to the choices as radio buttons. */
@Composable
private fun RowCard(
    name: String,
    accent: Color,
    value: String,
    open: Boolean,
    onToggle: () -> Unit,
    options: List<Option>,
    selected: Int?,
    note: String?,
    onPick: (Int) -> Unit,
) {
    val state = stringResource(if (open) R.string.mix_simple_open else R.string.mix_simple_closed)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = state }
                    .padding(start = 14.dp, end = 10.dp),
            ) {
                Box(Modifier.size(width = 3.dp, height = 20.dp).background(accent, RoundedCornerShape(2.dp)))
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.padding(start = 12.dp, end = 12.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.padding(start = 6.dp).size(22.dp).rotate(if (open) 180f else 0f))
            }
            AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.selectableGroup().padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
                    note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    options.forEachIndexed { i, o ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(selected = i == selected, role = Role.RadioButton) { onPick(i) }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            RadioButton(selected = i == selected, onClick = null)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(o.name, style = MaterialTheme.typography.bodyMedium)
                                o.line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- the waveforms ----

/**
 * The waveforms laid out for one frame: A on its own clock, fixed, from the earliest of it a
 * change may use to past its end, and B under it as it falls against A in the change. Every
 * length in px.
 */
private class WaveFrame(val editor: MixEditor, val spec: TransitionSpec, val width: Float, val height: Float, val dp: Float) {
    val pad = 8 * dp
    val perA = editor.perA(spec)
    val startMs = editor.facts.aReachStartMs
    val endMs = editor.facts.a.durationMs + (spec.lengthBars / 2 + 1) * perA
    val msPerPx = (endMs - startMs) / (width - 2 * pad)
    val laneH = (height - 36 * dp) / 2
    val aTop = 12 * dp
    val aBottom = aTop + laneH
    val bTop = aBottom + 12 * dp
    val bBottom = bTop + laneH

    fun x(aMs: Double): Float = (pad + (aMs - startMs) / msPerPx).toFloat()
    fun aMs(x: Float): Double = startMs + (x - pad) * msPerPx
    fun xOfU(u: Double): Float = x(spec.aMsAt(u))
    fun uOfX(x: Float): Double = (aMs(x) - spec.a.atMs) / perA
}

/**
 * Both songs' waveforms with the change as a window over them: dragged sideways, it moves where
 * the change falls in A, a bar at a time on A's downbeats, as A's lane moves in Advanced. Over it,
 * each row's mark: the songs' levels (Volume), where the basslines go (EQ), the filters' washes and
 * the echo. A drag is one undo step; the playhead runs over it while the preview plays.
 */
@Composable
private fun Waves(
    vm: MixEditorViewModel,
    editor: MixEditor,
    spec: TransitionSpec,
    pair: LoadedPair,
    words: EditorWords,
    look: SimpleLook,
    height: Dp,
    playing: Boolean,
    playU: () -> Double,
    onLimit: (Limit) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val read = remember(spec) { editor.rows(spec) }
    var dragging by remember { mutableStateOf(false) }
    val earlier = stringResource(R.string.mix_a11y_bar_earlier)
    val later = stringResource(R.string.mix_a11y_bar_later)
    val described = stringResource(R.string.mix_simple_window, words.clock(spec.a.atMs), words.aTitle) + " " + words.summary(editor)
    BoxWithConstraints(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(look.canvas),
    ) {
        val frame = WaveFrame(editor, spec, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density.density)
        val now = rememberUpdatedState(frame)
        fun moveBy(bars: Int) {
            vm.edit {
                val (next, why) = anchorMoved(this.spec, Side.A, -bars * perA(this.spec), byBeats = false)
                if (why != null) onLimit(why)
                apply(next)
            }
        }
        Canvas(
            Modifier
                .matchParentSize()
                // The system's back gesture takes the screen's edges; here a drag moves the change.
                .systemGestureExclusion()
                .semantics {
                    contentDescription = described
                    customActions = listOf(
                        CustomAccessibilityAction(earlier) { moveBy(-1); true },
                        CustomAccessibilityAction(later) { moveBy(1); true },
                    )
                }
                .pointerInput(editor, pair) {
                    var before: TransitionSpec? = null
                    var dx = 0f
                    var last = 0.0
                    var limit: Limit? = null
                    detectHorizontalDragGestures(
                        onDragStart = {
                            before = editor.spec
                            dx = 0f
                            last = editor.spec.a.atMs
                            limit = null
                            dragging = true
                        },
                        onDragEnd = {
                            before?.let { editor.commit(it) }
                            vm.publish()
                            dragging = false
                            limit?.let {
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                onLimit(it)
                            }
                        },
                        onDragCancel = {
                            before?.let { editor.live(it) }
                            vm.publish()
                            dragging = false
                        },
                        onHorizontalDrag = { change, amount ->
                            val from = before ?: return@detectHorizontalDragGestures
                            change.consume()
                            dx += amount
                            // Right brings a later moment of A to bar 1: the window moves with the finger.
                            val (next, why) = editor.anchorMoved(from, Side.A, -dx * now.value.msPerPx, byBeats = false)
                            limit = why
                            if (next.a.atMs != last) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                last = next.a.atMs
                            }
                            editor.live(next)
                            vm.publish()
                        },
                    )
                },
        ) {
            drawWaves(frame, pair, look, read, dragging)
        }
        // The playhead, on its own so playing redraws only this.
        if (playing) {
            Canvas(Modifier.matchParentSize()) {
                val x = frame.xOfU(playU())
                if (x in 0f..size.width) drawRect(look.primary, Offset(x - frame.dp, frame.aTop - 6 * frame.dp), Size(2 * frame.dp, frame.bBottom - frame.aTop + 12 * frame.dp))
            }
        }
    }
}

private fun DrawScope.drawWaves(f: WaveFrame, pair: LoadedPair, look: SimpleLook, read: SimpleRead, dragging: Boolean) {
    val spec = f.spec
    val dp = f.dp
    val l = spec.lengthBars
    val x0 = f.xOfU(0.0)
    val x1 = f.xOfU(l)
    // Each song's bar lines over its lane: A's own downbeats, which the window snaps to, and B's bars of the change.
    val tick = look.faint.copy(alpha = 0.5f)
    f.editor.gridA(spec)?.let { g ->
        var k = g.downbeatNear(f.startMs)
        while (g.beatMs(k) < f.startMs) k += g.beatsPerBar
        var guard = 0
        while (g.beatMs(k) <= f.endMs && guard++ < 400) {
            val x = f.x(g.beatMs(k))
            drawRect(tick, Offset(x - 0.5f * dp, f.aTop - 8 * dp), Size(dp, 5 * dp))
            k += g.beatsPerBar
        }
    }
    if (spec.bars > 0) {
        val first = ceil(f.uOfX(f.pad)).toInt()
        val last = floor(f.uOfX(size.width - f.pad)).toInt()
        for (k in first..min(last, first + 400)) {
            val x = f.xOfU(k.toDouble())
            if (f.editor.bStartU(spec) <= k) drawRect(tick, Offset(x - 0.5f * dp, f.bTop - 8 * dp), Size(dp, 5 * dp))
        }
    }

    // The waveforms: what each song has there, faint, and what is heard of it over that.
    val aBands = pair.a.bands
    val bBands = pair.b.bands
    val aOrigin = pair.a.pcm.info.startMs
    val bOrigin = pair.b.pcm.info.startMs
    val step = 3 * dp
    val bar = 2 * dp
    val lanes = spec.lanes.lanes
    // On parts the voice and the melody share the mids: each counts half.
    fun share(bands: BandEnvelopes, lane: Lane) = 1f / lanes.count { bands.band(it) === bands.band(lane) }
    fun levels(bands: BandEnvelopes, side: Side, ms0: Double, ms1: Double, u: Double): Pair<Float, Float> {
        var full = 0f
        var heard = 0f
        for (lane in lanes) {
            val p = bands.peak(lane, ms0, ms1) * share(bands, lane)
            full += p
            val st = spec.laneAt(LaneId(side, lane), u)
            heard += p * (st.gain.toFloat() * passed(lane, st))
        }
        val ref = bands.reference
        return full / ref to heard / ref
    }
    fun column(x: Float, centre: Float, half: Float, v: Float, color: Color) {
        val h = min(1f, v).pow(0.8f) * half
        if (h < 0.5f) return
        drawRoundRect(color, Offset(x - bar / 2, centre - h), Size(bar, 2 * h), CornerRadius(bar / 2))
    }
    val aCentre = (f.aTop + f.aBottom) / 2
    val bCentre = (f.bTop + f.bBottom) / 2
    val half = f.laneH / 2 - 2 * dp
    val aEnd = f.editor.facts.a.durationMs
    val bEnd = f.editor.facts.b.windowEndMs
    var x = f.pad + bar / 2
    while (x < size.width - f.pad) {
        val u = f.uOfX(x)
        val inside = x in x0..x1
        val ms0 = f.aMs(x - step / 2)
        val ms1 = f.aMs(x + step / 2)
        if (ms1 > pair.a.pcm.info.startMs && ms0 < aEnd) {
            val (full, heard) = levels(aBands, Side.A, ms0 - aOrigin, ms1 - aOrigin, u)
            column(x, aCentre, half, full, look.a.copy(alpha = 0.18f))
            column(x, aCentre, half, heard, look.a.copy(alpha = if (inside) 0.95f else 0.6f))
        }
        val b0 = spec.bMsAt(f.uOfX(x - step / 2))
        val b1 = spec.bMsAt(f.uOfX(x + step / 2))
        if (b1 > 0 && b0 < bEnd) {
            val (full, heard) = levels(bBands, Side.B, max(0.0, b0) - bOrigin, b1 - bOrigin, u)
            column(x, bCentre, half, full, look.b.copy(alpha = 0.18f))
            column(x, bCentre, half, heard, look.b.copy(alpha = if (inside) 0.95f else 0.6f))
        }
        x += step
    }

    if (!spec.plain) {
        // Filter: a low-pass as a wash from the top of the lane, a high-pass from the bottom.
        var cx = max(f.pad, x0 - 2 * f.perA.toFloat() / f.msPerPx.toFloat())
        val cEnd = min(size.width - f.pad, x1 + 2 * f.perA.toFloat() / f.msPerPx.toFloat())
        while (cx < cEnd) {
            val u = f.uOfX(cx)
            for ((side, top, bottom) in listOf(Triple(Side.A, f.aTop, f.aBottom), Triple(Side.B, f.bTop, f.bBottom))) {
                var lp = 0f
                var hp = 0f
                for (lane in lanes) {
                    val s = spec.laneAt(LaneId(side, lane), u)
                    if (s.gain <= 0.01) continue
                    s.lowPassHz?.let { lp = max(lp, 1 - logPlace(it)) }
                    s.highPassHz?.let { hp = max(hp, logPlace(it)) }
                }
                val h = bottom - top
                if (lp > 0) drawRect(look.filter.copy(alpha = 0.24f), Offset(cx, top), Size(step, lp * h * 0.9f))
                if (hp > 0) drawRect(look.filter.copy(alpha = 0.24f), Offset(cx, bottom - hp * h * 0.9f), Size(step, hp * h * 0.9f))
            }
            cx += step
        }

        // Volume: each song's level through the change, A falling, B rising.
        val points = max(2, ((x1 - x0) / (2 * dp)).toInt())
        for ((side, top, bottom) in listOf(Triple(Side.A, f.aTop, f.aBottom), Triple(Side.B, f.bTop, f.bBottom))) {
            val path = Path()
            for (i in 0..points) {
                val u = l * i / points
                val g = lanes.sumOf { spec.laneAt(LaneId(side, it), u).gain } / lanes.size
                val y = (top + 4 * dp + (1 - g.toFloat()) * (bottom - top - 8 * dp))
                val px = f.xOfU(u)
                if (i == 0) path.moveTo(px, y) else path.lineTo(px, y)
            }
            drawPath(path, look.volume.copy(alpha = 0.9f), style = Stroke(width = 2.5f * dp, cap = StrokeCap.Round))
        }

        // EQ: where A's bassline is gone and B's is in, dashed; one line across both for a swap.
        if (read.eq != EqRow.NONE) {
            val low = lanes.firstOrNull { it == Lane.LOWS || it == Lane.BASS }
            if (low != null) {
                val aId = LaneId(Side.A, low)
                val bMove = spec.moves[LaneId(Side.B, low)]
                val aX = spec.goneAt(aId).takeIf { it.isFinite() }?.let { f.xOfU(it) }
                val bX = bMove?.takeIf { it.how != How.FROM_START && LaneId(Side.B, low) !in spec.curves }?.let { f.xOfU(if (it.how == How.CUT) it.u else it.end) }
                val dash = PathEffect.dashPathEffect(floatArrayOf(4 * dp, 4 * dp))
                fun dashed(xx: Float, from: Float, to: Float) {
                    if (xx < f.pad || xx > size.width - f.pad) return
                    drawLine(look.eq, Offset(xx, from), Offset(xx, to), strokeWidth = 2 * dp, pathEffect = dash)
                }
                if (aX != null && bX != null && abs(aX - bX) < 1.5f * dp) {
                    dashed(aX, f.aTop, f.bBottom)
                } else {
                    aX?.let { dashed(it, f.aTop, f.aBottom) }
                    bX?.let { dashed(it, f.bTop, f.bBottom) }
                }
            }
        }

        // Effects: an echo rings on after its lane is gone, dying away.
        for (lane in lanes) {
            val id = LaneId(Side.A, lane)
            val m = spec.moves[id] ?: continue
            if (m.how != How.ECHO || id in spec.curves || id in spec.muted) continue
            val ex = f.xOfU(m.end)
            for (i in 1..4) {
                val dx = ex + i * 7 * dp
                if (dx > size.width - f.pad) break
                drawCircle(look.effects.copy(alpha = 1f - i * 0.2f), (3f - i * 0.5f) * dp, Offset(dx, aCentre))
            }
        }
    }

    // The change: a window over both songs, with a grip at each edge.
    val top = f.aTop - 4 * dp
    val bottom = f.bBottom + 4 * dp
    val edge = if (dragging) look.primary else look.ink
    drawRoundRect(look.ink.copy(alpha = 0.06f), Offset(x0, top), Size(x1 - x0, bottom - top), CornerRadius(10 * dp))
    drawRoundRect(edge.copy(alpha = 0.9f), Offset(x0, top), Size(x1 - x0, bottom - top), CornerRadius(10 * dp), style = Stroke(2 * dp))
    val mid = (top + bottom) / 2
    for (gx in listOf(x0, x1)) drawRoundRect(edge, Offset(gx - 3 * dp, mid - 14 * dp), Size(6 * dp, 28 * dp), CornerRadius(3 * dp))
}

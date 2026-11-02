/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlin.math.abs
import kotlin.math.roundToInt

/** The floating bar over a handle or a bracket: how it goes, how long, and the rest under "...". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HowBar(
    vm: MixEditorViewModel,
    editor: MixEditor,
    spec: TransitionSpec,
    selection: Selection,
    layout: CanvasLayout,
    words: EditorWords,
    onCustomize: (Lane) -> Unit,
) {
    val side = when (selection) { is Selection.Handle -> selection.id.side; is Selection.Bracket -> selection.side }
    val ids: List<LaneId> = when (selection) {
        is Selection.Handle -> listOf(selection.id)
        is Selection.Bracket -> editor.bracketIds(spec, selection.side)
    }
    if (ids.isEmpty()) return
    val moves = ids.mapNotNull { spec.moves[it] }
    if (moves.isEmpty()) return
    val how = moves.first().how.takeIf { h -> moves.all { it.how == h } }
    val len = moves.first().len.takeIf { l -> moves.all { abs(it.len - l) < 1e-9 } }
    val options = if (side == Side.A) listOf(How.FADE, How.MUFFLE, How.THIN, How.ECHO, How.CUT) else listOf(How.FADE, How.MUFFLED, How.THIN, How.CUT)
    val fixedLen = how == null || how in MixEditor.FIXED
    val anchor = when (selection) {
        is Selection.Handle -> layout.grips.firstOrNull { it.id == selection.id }?.at
        is Selection.Bracket -> layout.brackets.firstOrNull { it.side == selection.side }?.let { androidx.compose.ui.geometry.Offset(it.labelX, it.y) }
    } ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var more by remember { mutableStateOf(false) }
    val dp = layout.dp
    val below = anchor.y - 14 * dp - size.height < 0
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        shadowElevation = 6.dp,
        modifier = Modifier
            .widthIn(max = with(androidx.compose.ui.platform.LocalDensity.current) { (layout.width - 8 * dp).toDp() })
            .onSizeChanged { size = it }
            .offset {
                val x = (anchor.x - size.width / 2f).coerceIn(4 * dp, layout.width - size.width - 4 * dp)
                val y = if (below) anchor.y + 16 * dp else anchor.y - 14 * dp - size.height
                IntOffset(x.roundToInt(), y.roundToInt())
            },
    ) {
        FlowRow(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            for (o in options) {
                val on = how == o
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(36.dp)
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(18.dp))
                        .clickable { vm.edit { setHow(ids, o) } }
                        .semantics { role = Role.RadioButton }
                        .padding(horizontal = 8.dp),
                ) {
                    Text(words.howName(o), style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                }
            }
            VerticalDivider(Modifier.height(22.dp).padding(horizontal = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.edit { stepLength(ids, longer = false) } }, enabled = !fixedLen, modifier = Modifier.size(32.dp, 36.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.mix_how_shorter))
                }
                val lenText = when {
                    how == How.KEEP -> stringResource(R.string.mix_how_kept)
                    how == How.FROM_START -> stringResource(R.string.mix_how_from_start)
                    how == How.CUT -> stringResource(R.string.mix_how_on_beat)
                    how == null || len == null -> stringResource(R.string.mix_how_mixed)
                    else -> words.ramp(len, spec)
                }
                Text(lenText, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.widthIn(min = 46.dp))
                IconButton(onClick = { vm.edit { stepLength(ids, longer = true) } }, enabled = !fixedLen, modifier = Modifier.size(32.dp, 36.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.mix_how_longer))
                }
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.mix_how_more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    val keep = if (side == Side.A) How.KEEP else How.FROM_START
                    DropdownMenuItem(text = { Text(words.howName(keep)) }, onClick = { more = false; vm.edit { setHow(ids, keep) } })
                    if (selection is Selection.Handle) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.mix_how_customize)) }, onClick = { more = false; onCustomize(selection.id.lane) })
                    }
                }
            }
        }
    }
}

/** The length sheet: each length with its seconds and its cost, and why one cannot be had. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LengthSheet(vm: MixEditorViewModel, editor: MixEditor, words: EditorWords, onDone: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = state) {
        val spec = editor.spec
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.mix_edit_length_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
            val note = if (editor.facts.barsMode) stringResource(R.string.mix_edit_length_note, words.number(spec.b.bpm)) else stringResource(R.string.mix_edit_length_note_timed)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val options = editor.lengthOptions()
            for (o in options.filter { it.bars > 0 }) {
                val ok = o.refused == null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(enabled = ok) { vm.edit { setLength(o.bars) }; onDone() }
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                ) {
                    val colour = when {
                        !ok -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        o.lit -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(words.bars(o.bars), style = MaterialTheme.typography.bodyLarge, color = colour, modifier = Modifier.widthIn(min = 72.dp))
                    Column(Modifier.weight(1f)) {
                        val cost = buildString {
                            append(words.seconds(o.ms / 1000))
                            if (ok) append(" · ").append(stringResource(R.string.mix_edit_length_at, words.clock(o.aAtMs)))
                            if (ok && o.unheardMs >= 1000) append(" · ").append(stringResource(R.string.mix_edit_loses, words.aTitle, words.clock(o.unheardMs)))
                        }
                        Text(cost, style = MaterialTheme.typography.bodyMedium, color = if (o.lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        o.refused?.let { Text(words.limit(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (o.lit) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            val timed = options.filter { it.bars == 0 }
            if (timed.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    if (editor.facts.barsMode) Text(stringResource(R.string.mix_edit_length_timed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (o in timed) {
                        FilterChip(
                            selected = o.lit,
                            enabled = o.refused == null,
                            onClick = { vm.edit { setLength(0, o.seconds) }; onDone() },
                            label = { Text(words.seconds(o.seconds)) },
                            leadingIcon = if (o.lit) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                        )
                    }
                }
            }
            if (editor.facts.barsMode) {
                Text(stringResource(R.string.mix_edit_length_no_32, words.aTitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
    }
}

/**
 * One tap of the nudge: 5 ms, half the 10 ms it was, since beats 10 ms apart already sound tight
 * and a song a few ms off its grid needs a finer step to be put right (mix-better, 2 Nov 2026).
 */
internal const val NUDGE_STEP_MS = 5

/** The tempos a typed one may be. */
private val TYPED_BPM = 40.0..250.0

/** Taps the tempo is worked out over, the newest, and how many it takes before it is used. */
private const val TAPS_KEPT = 16
private const val TAPS_TAKEN = 4

/** The tempo of [taps] (ms), from the median of their gaps, to a tenth of a BPM; null under two taps. */
internal fun tappedBpm(taps: List<Long>): Double? {
    if (taps.size < 2) return null
    val gaps = taps.zipWithNext { a, b -> b - a }.sorted()
    val median = gaps[gaps.size / 2].coerceAtLeast(1)
    return (600_000.0 / median).roundToInt() / 10.0
}

/** Fix the beat of one song: half, double, tapped, where the 1 is, a nudge; kept for the song. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FixSheet(
    vm: MixEditorViewModel,
    editor: MixEditor,
    spec: TransitionSpec,
    side: Side,
    words: EditorWords,
    clicking: Boolean,
    onClick: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current
    val anchor = if (side == Side.A) spec.a else spec.b
    val facts = if (side == Side.A) editor.facts.a else editor.facts.b
    val fix = anchor.fix
    // The beats as the page has them: the analysis's, or the ones laid at a tempo set here.
    val current = if (side == Side.A) editor.gridA(spec) else editor.gridB(spec)
    val base = facts.grid ?: current
    val taps = remember { mutableStateListOf<Long>() }
    ModalBottomSheet(onDismissRequest = {
        // Closed while tapping: the tempo tapped is taken, as closing the pad takes it.
        tappedBpm(taps)?.takeIf { taps.size >= TAPS_TAKEN }?.let { b -> vm.edit { setFix(side, fix.copy(bpm = b)) } }
        onClick(false)
        onDone()
    }, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.mix_fix_title, facts.title), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
            fun set(next: BeatFix) = vm.edit { setFix(side, next) }
            var tapping by remember { mutableStateOf(base == null) }
            fun takeTaps() {
                tappedBpm(taps)?.takeIf { taps.size >= TAPS_TAKEN }?.let { set(fix.copy(bpm = it)) }
                taps.clear()
                tapping = false
            }
            // Nothing found and nothing set yet: the tempo is the listener's to give, by tapping or typing it.
            val bpm = current?.bpm
            Text(
                when {
                    bpm == null -> stringResource(R.string.mix_beat_note_none)
                    !fix.isNone -> stringResource(R.string.mix_fix_changed, words.number(com.dd3boh.outertune.mix.Tempo.felt(bpm).takeIf { fix.bpm == null } ?: bpm))
                    facts.barsSure -> stringResource(R.string.mix_fix_sure, words.number(com.dd3boh.outertune.mix.Tempo.felt(bpm)))
                    else -> stringResource(R.string.mix_fix_bars_guessed, words.number(com.dd3boh.outertune.mix.Tempo.felt(bpm)))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
                if (base != null) {
                    val found = facts.grid?.bpm ?: base.bpm
                    val half = fix.bpm != null && abs(fix.bpm!! - found / 2) < 0.05
                    val double = fix.bpm != null && abs(fix.bpm!! - found * 2) < 0.05
                    FilterChip(selected = half, onClick = { set(fix.copy(bpm = if (half) null else found / 2)) }, label = { Text(stringResource(R.string.mix_fix_half)) })
                    FilterChip(selected = double, onClick = { set(fix.copy(bpm = if (double) null else found * 2)) }, label = { Text(stringResource(R.string.mix_fix_double)) })
                }
                FilterChip(selected = tapping, onClick = { if (tapping) takeTaps() else tapping = true }, label = { Text(stringResource(R.string.mix_fix_tap)) })
                // Typed: the tempo as a number, for a song whose tempo is known.
                var typed by remember(bpm) { mutableStateOf(bpm?.let { words.number(com.dd3boh.outertune.mix.Tempo.felt(it).takeIf { fix.bpm == null } ?: it) } ?: "") }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { v -> typed = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mix_fix_type)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        typed.replace(',', '.').toDoubleOrNull()?.takeIf { it in TYPED_BPM }?.let { set(fix.copy(bpm = it)) }
                    }),
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
            if (tapping) {
                // A pad hit in time with the music: the tempo follows every tap, from the second one,
                // over the last taps' gaps, and the pad stays until it is closed, which takes it.
                var hit by remember { mutableIntStateOf(0) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, top = 6.dp)
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(if (hit % 2 == 1) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                val now = System.currentTimeMillis()
                                val last = taps.lastOrNull()
                                // Faster than 330 BPM is a bounce, not a beat; a long pause starts over.
                                if (last != null && now - last < 180) return@awaitEachGesture
                                if (last != null && now - last > 2000) taps.clear()
                                taps += now
                                while (taps.size > TAPS_KEPT) taps.removeAt(0)
                                hit++
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        },
                ) {
                    val live = tappedBpm(taps)
                    Text(
                        if (live != null) stringResource(R.string.mix_fix_tapped, taps.size, words.number(live)) else stringResource(R.string.mix_fix_tap),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { takeTaps() }) { Text(stringResource(R.string.mix_fix_tap_done)) }
                }
            }
            // The 1 and the nudge once there are beats to move.
            if (current == null) return@Column
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.mix_fix_the_one), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 56.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = false, onClick = { set(fix.copy(downbeatShift = fix.downbeatShift - 1)) }, label = { Text(stringResource(R.string.mix_fix_earlier)) })
                    FilterChip(selected = false, onClick = { set(fix.copy(downbeatShift = fix.downbeatShift + 1)) }, label = { Text(stringResource(R.string.mix_fix_later)) })
                    if (fix.downbeatShift != 0) Text(if (fix.downbeatShift > 0) "+${words.beats(fix.downbeatShift)}" else "-${words.beats(-fix.downbeatShift)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.mix_fix_nudge), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 56.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = false, onClick = { set(fix.copy(nudgeMs = fix.nudgeMs - NUDGE_STEP_MS)) }, label = { Text(stringResource(R.string.mix_fix_minus)) })
                    FilterChip(selected = false, onClick = { set(fix.copy(nudgeMs = fix.nudgeMs + NUDGE_STEP_MS)) }, label = { Text(stringResource(R.string.mix_fix_plus)) })
                    Text(stringResource(R.string.mix_fix_ms, (if (fix.nudgeMs > 0) "+" else "") + fix.nudgeMs), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                FilterChip(selected = clicking, onClick = { onClick(!clicking) }, label = { Text(stringResource(if (clicking) R.string.mix_fix_click_stop else R.string.mix_fix_click)) })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { set(BeatFix.NONE) }, enabled = !fix.isNone) { Text(stringResource(R.string.mix_fix_reset)) }
            }
            Text(stringResource(R.string.mix_fix_kept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
}

/** The change as a numbered list of what happens where; a tap plays from there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsSheet(editor: MixEditor, words: EditorWords, onPlay: (Double) -> Unit, onDone: () -> Unit) {
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDone, sheetState = state) {
        val spec = editor.spec
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.mix_moments_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                stringResource(if (spec.plain) R.string.mix_moments_plain else R.string.mix_moments_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (!spec.plain) editor.moments().forEachIndexed { i, m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(m.at - 0.5); onDone() }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("${i + 1} · ${words.position(m.at, spec)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 96.dp).padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(words.moment(m).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.mix_moments_times, words.aTitle, words.clock(spec.aMsAt(m.at)), words.bTitle, words.clock(maxOf(0.0, spec.bMsAt(m.at)))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

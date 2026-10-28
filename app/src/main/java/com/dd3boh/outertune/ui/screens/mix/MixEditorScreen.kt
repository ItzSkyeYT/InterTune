/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.ScreenBackHandler
import com.dd3boh.outertune.ui.component.TopBarActions
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Edit transition (DESIGN.md section 3), on lows, mids and highs: the two songs' ends on one
 * canvas, the presets, the length, Beat matching, Fix the beat, undo, and a preview that plays
 * what is on the page. Opened for one ordered pair of songs; saving keeps the change for that pair
 * wherever the two meet.
 */
@Composable
fun MixEditorScreen(navController: NavController) {
    val vm: MixEditorViewModel = hiltViewModel()
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val snackbar = LocalSnackbarHostState.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(playerConnection) {
        val service = playerConnection?.service ?: return@LaunchedEffect
        vm.start(service.songSource(), levelOf = { id -> service.mixLevelOf(id) }, volume = { service.playerVolume.value })
    }

    val phase by vm.phase.collectAsState()
    val ui by vm.ui.collectAsState()
    val songA by vm.songA.collectAsState()
    val songB by vm.songB.collectAsState()
    val aTitle = songA?.title ?: vm.pair?.a?.title ?: ""
    val bTitle = songB?.title ?: vm.pair?.b?.title ?: ""
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val words = remember(resources, configuration, aTitle, bTitle) { EditorWords(resources, aTitle, bTitle) }
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        MixColors(
            dark = scheme.surface.luminance() < 0.5f, primary = scheme.primary, onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariant, surface = scheme.surface, surfaceHigh = scheme.surfaceContainerHigh,
            inverseSurface = scheme.inverseSurface, inverseOnSurface = scheme.inverseOnSurface, error = scheme.error,
        )
    }
    val canvas = remember { CanvasState() }
    val wordsNow by androidx.compose.runtime.rememberUpdatedState(words)
    var lanesView by rememberSaveable { mutableStateOf(false) }
    var lengthOpen by remember { mutableStateOf(false) }
    var fixOpen by remember { mutableStateOf<Side?>(null) }
    var momentsOpen by remember { mutableStateOf(false) }
    var discardOpen by remember { mutableStateOf(false) }
    var clicking by remember { mutableStateOf(false) }
    var loop by rememberSaveable { mutableStateOf(true) }
    var said by remember { mutableStateOf<String?>(null) }
    val skipSilence by rememberPreference(SkipSilenceKey, defaultValue = false)

    // The preview: one thing plays at a time. It pauses the player and gives way when the player
    // starts again; on leaving, the player plays on if the preview paused it and nothing else
    // has started it since.
    val preview = vm.preview
    val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val playing by (preview?.playing ?: idle).collectAsState()
    var playU by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(playing, preview) {
        while (playing && preview != null) {
            withFrameNanos { }
            playU = preview.position()
        }
    }
    fun play(from: Double) {
        val p = vm.preview ?: return
        val e = vm.editor ?: return
        val player = playerConnection?.player
        if (player != null && player.isPlaying) {
            player.pause()
            vm.pausedPlayer = player
        }
        p.loop = loop
        playU = from.coerceIn(e.fitStart(), e.fitEnd())
        p.play(playU)
    }
    fun stop() {
        vm.preview?.stop()
        clicking = false
        vm.preview?.click(null)
    }
    DisposableEffect(playerConnection) {
        val player = playerConnection?.player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    vm.preview?.stop()
                    vm.pausedPlayer = null
                }
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.preview?.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Headphones unplugged or gone: the preview stops, as the music would, instead of carrying on
    // from the loudspeaker. Its own track has no noisy handling of its own, and the player it
    // paused hears nothing.
    DisposableEffect(context) {
        val noisy = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                if (intent.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) vm.preview?.stop()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, noisy, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(noisy) } }
    }
    // The preview starts on opening, from 2 bars before the change.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase is MixEditorViewModel.Phase.Ready && !started) {
            started = true
            vm.editor?.let { play(it.fitStart()) }
        }
    }
    // Songs to Bands: the shape separates into its lanes over 300 ms, and folds back.
    LaunchedEffect(lanesView) {
        val to = if (lanesView) 1f else 0f
        if (canvas.view == to) return@LaunchedEffect
        canvas.selection = null
        Animatable(canvas.view).animateTo(to, tween(300)) { canvas.view = value }
    }
    LaunchedEffect(said) {
        if (said != null) {
            delay(3_000)
            said = null
        }
    }

    val editor = vm.editor
    val spec = ui?.spec

    fun close() {
        if (ui?.dirty == true) discardOpen = true else navController.navigateUp()
    }
    // Back leaves Adjust, then asks before changes are lost; with the player open over the page it is the player's.
    ScreenBackHandler(enabled = canvas.adjust != null || ui?.dirty == true) {
        if (canvas.adjust != null) leaveAdjust(vm, canvas) else discardOpen = true
    }

    val actions = remember {
        object : CanvasActions {
            override fun playFrom(u: Double) = play(u)
            override fun scrub(u: Double) {
                vm.preview?.stop()
                playU = u
            }
            override fun scrubDone() = play(playU)
            override fun say(limit: Limit) {
                said = wordsNow.limit(limit)
            }
            override fun note(text: Int) {
                said = resources.getString(text)
            }
            override fun laneMenu(id: com.dd3boh.outertune.mix.edit.LaneId, action: LaneAction) {
                when (action) {
                    LaneAction.MUTE -> vm.edit { toggleMute(id) }
                    LaneAction.ALONE -> {
                        canvas.solo = if (canvas.solo == id) null else id
                        vm.preview?.solo(canvas.solo)
                        if (canvas.solo != null && vm.preview?.playing?.value != true) vm.editor?.let { play(it.fitStart()) }
                    }
                    LaneAction.KEEP -> vm.edit { keepWhole(id) }
                    LaneAction.ADJUST -> enterAdjust(id.lane)
                }
            }
            override fun enterAdjust(lane: com.dd3boh.outertune.mix.edit.Lane) {
                val e = vm.editor ?: return
                canvas.selection = null
                vm.adjustBefore = e.spec
                vm.adjustEdited = false
                var next = e.spec
                for (side in Side.entries) {
                    val id = com.dd3boh.outertune.mix.edit.LaneId(side, lane)
                    next = e.curveSet(next, id, e.curveFromMove(id, next).gain)
                }
                e.live(next)
                vm.publish()
                canvas.adjust = lane
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tall = maxHeight >= 700.dp
        val landscape = LocalLandscape.current.active
        val insets = LocalPlayerAwareWindowInsets.current
        val guard = when {
            vm.fromId == vm.toId -> stringResource(R.string.mix_edit_guard_same_song)
            skipSilence -> stringResource(R.string.mix_edit_guard_skip_silence)
            vm.pair?.let { it.a.pcm.info.sampleRate != it.b.pcm.info.sampleRate || it.a.pcm.info.channels != it.b.pcm.info.channels } == true ->
                stringResource(R.string.mix_edit_guard_formats)
            else -> null
        }

        @Composable
        fun Upper(small: Boolean) {
            if (guard != null) {
                Text(
                    guard,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
            SongRow(songA, aTitle, spec?.let { words.clock(it.a.atMs) }, spec?.a?.bpm, vm.editor?.facts?.a?.grid != null, spec?.a?.fix?.isNone == false, words, landscape) { fixOpen = Side.A }
            when (val p = phase) {
                is MixEditorViewModel.Phase.Failed -> FailedState(p, aTitle, bTitle, if (p.side == Side.A) songA else songB, vm)
                else -> {
                    val e = editor
                    val s = spec ?: e?.spec
                    if (e != null && s != null) {
                        MixCanvasView(vm, e, s, vm.pair, canvas, words, colors, small, { playU }, actions, 0f)
                    } else {
                        LoadingCanvas(colors)
                    }
                }
            }
            SongRow(songB, bTitle, spec?.let { words.clock(it.b.atMs) }, spec?.b?.bpm, vm.editor?.facts?.b?.grid != null, spec?.b?.fix?.isNone == false, words, landscape) { fixOpen = Side.B }
            BarRow(spec, words, lanesView, canvas.adjust, { lengthOpen = true }, { lanesView = it }) { leaveAdjust(vm, canvas) }
            NowLine(vm, editor, spec, words, phase, playing, { playU }, said) { if (phase is MixEditorViewModel.Phase.Ready) momentsOpen = true }
        }

        @Composable
        fun ColumnScope.Lower() {
            val e = editor
            val s = spec
            if (e == null || s == null) {
                Spacer(Modifier.height(24.dp))
                return
            }
            // Adjust holds its own undo step until Done: everything below waits for it.
            Lowers(vm, e, s, ui!!, words, phase is MixEditorViewModel.Phase.Ready && canvas.adjust == null, landscape, onPreset = { p ->
                when (vm.edit { tapPreset(p) }) {
                    PresetTap.REPLACED_CUSTOM -> scope.launch {
                        val r = snackbar.showSnackbar(resources.getString(R.string.mix_edit_replaced, wordsNow.presetName(p)), resources.getString(R.string.mix_edit_undo), duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) vm.edit { undo() }
                    }
                    PresetTap.NEEDS_PARTS -> said = resources.getString(R.string.mix_edit_needs_parts)
                    else -> {}
                }
                canvas.flash = 1f
                scope.launch { Animatable(1f).animateTo(0f, tween(200)) { canvas.flash = value } }
            })
        }

        if (landscape) {
            // On its side: two upright widths side by side, the canvas left, the rest right.
            Row(Modifier.fillMaxSize().windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal))) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars))
                    Upper(small = true)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Spacer(Modifier.windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars))
                    SideBar(ui, ::close, vm, onSave = { save(vm, context, haptic, navController) })
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Lower()
                        Spacer(Modifier.height(96.dp))
                    }
                }
            }
        } else {
            val page = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal))
                    .then(if (!tall) Modifier.verticalScroll(page) else Modifier),
            ) {
                Spacer(Modifier.windowInsetsTopHeight(insets))
                Upper(small = !tall)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                if (tall) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Lower()
                        Spacer(Modifier.windowInsetsTopHeight(insets.only(WindowInsetsSides.Bottom)))
                        Spacer(Modifier.height(104.dp))
                    }
                } else {
                    Lower()
                    Spacer(Modifier.height(104.dp))
                    Spacer(Modifier.windowInsetsTopHeight(insets.only(WindowInsetsSides.Bottom)))
                }
            }
        }

        // The transport: one floating pane as wide as its content.
        val bottom = insets.only(WindowInsetsSides.Bottom)
        Transport(
            playing = playing,
            enabled = phase is MixEditorViewModel.Phase.Ready,
            // Read in the transport itself, so the page is not composed again on every frame it plays.
            time = {
                val e = vm.editor
                val s = vm.ui.value?.spec
                if (e == null || s == null) "0:00 / 0:00" else {
                    val span = (e.fitEnd() - e.fitStart()) * s.b.barMs
                    val at = ((playU - e.fitStart()) * s.b.barMs).coerceIn(0.0, span)
                    "${wordsNow.clock(at)} / ${wordsNow.clock(span)}"
                }
            },
            loop = loop,
            onPlay = { if (playing) stop() else vm.editor?.let { e -> play(if (playU >= e.fitEnd() - 1e-6 || playU < e.fitStart()) e.fitStart() else playU) } },
            onLoop = { loop = !loop; vm.preview?.loop = loop },
            modifier = Modifier
                .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .windowInsetsPadding(bottom)
                .padding(bottom = 16.dp, end = if (landscape) 24.dp else 0.dp),
        )

        if (!landscape) {
            FloatingTopBar(
                title = stringResource(R.string.mix_edit_title),
                navController = navController,
                onBack = ::close,
                actions = {
                    TopBarActions {
                        IconButton(onClick = { vm.edit { undo() } }, enabled = ui?.canUndo == true && canvas.adjust == null) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.mix_edit_undo))
                        }
                        IconButton(onClick = { vm.edit { redo() } }, enabled = ui?.canRedo == true && canvas.adjust == null) {
                            Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.mix_edit_redo))
                        }
                    }
                    FilledTonalButton(
                        onClick = { save(vm, context, haptic, navController) },
                        enabled = ui?.dirty == true && canvas.adjust == null && phase is MixEditorViewModel.Phase.Ready,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.mix_edit_save), maxLines = 1) }
                },
            )
        }
    }

    // Sheets and the dialog.
    val e = editor
    if (lengthOpen && e != null) LengthSheet(vm, e, words) { lengthOpen = false }
    fixOpen?.let { side ->
        if (e != null && spec != null) FixSheet(vm, e, spec, side, words, clicking, onClick = { on ->
            clicking = on
            vm.preview?.click(if (on) side else null)
            if (on) play(e.fitStart())
        }) { fixOpen = null; if (clicking) stop() }
    }
    if (momentsOpen && e != null) MomentsSheet(e, words, onPlay = { play(it) }) { momentsOpen = false }
    if (discardOpen) {
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            title = { Text(stringResource(R.string.mix_edit_discard_title)) },
            confirmButton = { TextButton(onClick = { discardOpen = false; navController.navigateUp() }) { Text(stringResource(R.string.mix_edit_discard)) } },
            dismissButton = { TextButton(onClick = { discardOpen = false }) { Text(stringResource(R.string.mix_edit_keep_editing)) } },
        )
    }
}

private fun leaveAdjust(vm: MixEditorViewModel, canvas: CanvasState) {
    val e = vm.editor ?: return
    val before = vm.adjustBefore
    canvas.adjust = null
    if (before != null) {
        if (vm.adjustEdited) e.commit(before) else e.live(before)
    }
    vm.adjustBefore = null
    vm.adjustEdited = false
    vm.publish()
}

private fun save(vm: MixEditorViewModel, context: android.content.Context, haptic: androidx.compose.ui.hapticfeedback.HapticFeedback, navController: NavController) {
    vm.preview?.stop()
    vm.save { ok ->
        if (ok) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            Toast.makeText(context, R.string.mix_edit_saved, Toast.LENGTH_SHORT).show()
            navController.navigateUp()
        } else {
            Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_LONG).show()
        }
    }
}

/** A song's row: cover, title and artists, its tempo (a tap fixes it), and where bar 1 sits in it. */
@Composable
private fun SongRow(
    song: Song?,
    title: String,
    at: String?,
    bpm: Double?,
    sure: Boolean,
    fixed: Boolean,
    words: EditorWords,
    oneLine: Boolean,
    onBpm: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().height(if (oneLine) 40.dp else 48.dp).padding(horizontal = 12.dp),
    ) {
        AsyncImage(
            model = song?.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(if (oneLine) 30.dp else 36.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!oneLine) {
                val artists = song?.artists?.joinToString(", ") { it.name }.orEmpty()
                if (artists.isNotEmpty()) Text(artists, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val pillColor = if (fixed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        val label = if (bpm != null && sure) words.bpm(bpm) else stringResource(R.string.mix_edit_bpm_unknown)
        val fixOf = stringResource(R.string.mix_edit_fix_beat_of, title)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, pillColor, RoundedCornerShape(12.dp))
                .clickable(onClick = onBpm)
                .semantics { contentDescription = fixOf; role = Role.Button }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = if (fixed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (at != null) {
            val desc = stringResource(R.string.mix_edit_bar_one_at, at)
            Text(
                at,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 30.dp).semantics { contentDescription = desc },
            )
        }
    }
}

/** The length pill and the Songs | Bands switch, or Adjust's title and Done. */
@Composable
private fun BarRow(
    spec: TransitionSpec?,
    words: EditorWords,
    lanesView: Boolean,
    adjust: com.dd3boh.outertune.mix.edit.Lane?,
    onLength: () -> Unit,
    onView: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp),
    ) {
        if (adjust != null) {
            Text(stringResource(R.string.mix_adjust_title, words.laneName(adjust)), style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(onClick = onDone, modifier = Modifier.height(32.dp)) { Text(stringResource(R.string.mix_adjust_done)) }
            return@Row
        }
        val lengthDesc = stringResource(R.string.mix_edit_length)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable(enabled = spec != null, onClick = onLength)
                .semantics { contentDescription = lengthDesc + ", " + (spec?.let(words::length) ?: "") }
                .padding(start = 12.dp, end = 6.dp),
        ) {
            Text(spec?.let(words::length) ?: "", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        val second = if (spec?.lanes == LaneSet.PARTS) R.string.mix_edit_view_parts else R.string.mix_edit_view_bands
        SingleChoiceSegmentedButtonRow(Modifier.height(32.dp)) {
            SegmentedButton(selected = !lanesView, onClick = { onView(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.mix_edit_view_songs), maxLines = 1)
            }
            SegmentedButton(selected = lanesView, onClick = { onView(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(second), maxLines = 1)
            }
        }
    }
}

/** The now line: what is happening as it plays, the length and its cost when stopped. A tap opens the moments. */
@Composable
private fun NowLine(
    vm: MixEditorViewModel,
    editor: MixEditor?,
    spec: TransitionSpec?,
    words: EditorWords,
    phase: MixEditorViewModel.Phase,
    playing: Boolean,
    playU: () -> Double,
    said: String?,
    onTap: () -> Unit,
) {
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
                else -> words.nowStopped(editor)
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (said != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clickable(enabled = phase is MixEditorViewModel.Phase.Ready, onClick = onTap)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Everything under the fixed part: presets, Beat matching, Intro twice, the two ways back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lowers(
    vm: MixEditorViewModel,
    editor: MixEditor,
    spec: TransitionSpec,
    ui: MixEditorViewModel.Ui,
    words: EditorWords,
    ready: Boolean,
    wrap: Boolean,
    onPreset: (Preset) -> Unit,
) {
    Text(stringResource(R.string.mix_edit_presets), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp))
    val chips: @Composable () -> Unit = {
        for (p in EditorPresets.ORDER) {
            val lit = ui.lit == p
            val allowed = !p.needsParts || spec.lanes == LaneSet.PARTS
            val enabled = ready && allowed && !(p == Preset.CUSTOM && !lit && editor.lastCustom == null)
            FilterChip(
                selected = lit,
                enabled = enabled,
                onClick = { onPreset(p) },
                label = { Text(words.presetName(p), maxLines = 1) },
                leadingIcon = if (lit) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
            )
        }
    }
    if (wrap) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) { chips() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) { chips() }
    }
    Text(
        words.presetLine(ui.lit, spec, editor.auto.plain),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    val bass = editor.twoBasslines()
    if (bass >= 0.5) {
        Text(
            stringResource(R.string.mix_edit_two_basslines, words.ramp(bass, spec)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    HorizontalDivider(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    val allowed = spec.beatMatchAllowed() && editor.beatMatchPossible
    SwitchRow(
        title = stringResource(R.string.mix_edit_beat_matching),
        line = words.beatMatchLine(editor),
        checked = spec.beatMatched && editor.beatMatchPossible,
        enabled = ready && allowed,
    ) { on -> vm.edit { setBeatMatch(on) } }
    // Offered once playback plays it (M10): until then a change saved with it is refused at the
    // change and Auto plays. A change that already has it keeps the switch, to turn it off.
    if (INTRO_TWICE_PLAYS || spec.introTwice > 0) SwitchRow(
        title = stringResource(R.string.mix_edit_intro_twice),
        line = if (spec.introTwice > 0) stringResource(R.string.mix_edit_intro_twice_line, words.owner(words.bTitle), words.bars(spec.introTwice), words.aTitle) else null,
        checked = spec.introTwice > 0,
        enabled = ready && spec.bars > 0,
    ) { on -> vm.edit { setIntroTwice(if (on) 2 else 0) } }
    if (spec.introTwice > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
            for (n in listOf(2, 4)) FilterChip(
                selected = spec.introTwice == n,
                onClick = { vm.edit { setIntroTwice(n) } },
                label = { Text(words.bars(n)) },
                leadingIcon = if (spec.introTwice == n) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    FlowRow(modifier = Modifier.padding(horizontal = 4.dp)) {
        TextButton(onClick = { vm.edit { backToAuto() } }, enabled = ready && ui.lit != Preset.AUTO) { Text(stringResource(R.string.mix_edit_back_to_auto)) }
        TextButton(onClick = { vm.edit { plainChange() } }, enabled = ready && !spec.plain) { Text(stringResource(R.string.mix_edit_plain)) }
    }
    Text(
        stringResource(R.string.mix_edit_parts_need, PARTS_MB),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/** What the mixer download is, until the parts arrive (DESIGN.md 2.9). */
private const val PARTS_MB = 13

@Composable
private fun SwitchRow(title: String, line: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.7f))
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Play or Stop, the time in the change's own clock, and Loop: one pane, as wide as it needs. */
@Composable
private fun Transport(
    playing: Boolean,
    enabled: Boolean,
    time: () -> String,
    loop: Boolean,
    onPlay: () -> Unit,
    onLoop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(64.dp).padding(horizontal = 8.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onPlay),
            ) {
                Icon(
                    if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.mix_edit_stop else R.string.mix_edit_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            val text by remember { derivedStateOf(time) }
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1, modifier = Modifier.widthIn(min = 84.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (loop) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Switch, onClick = onLoop),
            ) {
                Icon(Icons.Rounded.Repeat, stringResource(R.string.mix_edit_loop), tint = if (loop) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** On its side, the top bar's controls sit over the right half: X, Undo, Redo and Save, no title. */
@Composable
private fun SideBar(ui: MixEditorViewModel.Ui?, onClose: () -> Unit, vm: MixEditorViewModel, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.mix_edit_close)) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.edit { undo() } }, enabled = ui?.canUndo == true) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.mix_edit_undo)) }
        IconButton(onClick = { vm.edit { redo() } }, enabled = ui?.canRedo == true) { Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.mix_edit_redo)) }
        FilledTonalButton(onClick = onSave, enabled = ui?.dirty == true, modifier = Modifier.padding(start = 4.dp)) { Text(stringResource(R.string.mix_edit_save), maxLines = 1) }
    }
}

/** The canvas while the two songs are read: its lanes shimmer, and only then does anything move. */
@Composable
private fun LoadingCanvas(colors: MixColors) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerState = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "shimmer")
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(236.dp)) {
        val shimmer = shimmerState.value
        val pad = 12.dp.toPx()
        for ((top, h) in listOf(0f to 88.dp.toPx(), 148.dp.toPx() to 88.dp.toPx())) {
            val r = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
            val w = size.width - 2 * pad
            drawRoundRect(colors.surfaceHigh.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(pad, top + 6.dp.toPx()), androidx.compose.ui.geometry.Size(w, h - 12.dp.toPx()), r)
            val gx = pad + (w + 160.dp.toPx()) * shimmer - 80.dp.toPx()
            drawRoundRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0f), 0.5f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f), 1f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0f),
                    startX = gx - 80.dp.toPx(), endX = gx + 80.dp.toPx(),
                ),
                topLeft = androidx.compose.ui.geometry.Offset(pad, top + 6.dp.toPx()), size = androidx.compose.ui.geometry.Size(w, h - 12.dp.toPx()), cornerRadius = r,
            )
        }
    }
}

/** Where the canvas would be, when a song could not be read: why, and a way on. */
@Composable
private fun FailedState(p: MixEditorViewModel.Phase.Failed, aTitle: String, bTitle: String, song: Song?, vm: MixEditorViewModel) {
    val downloads = LocalDownloadUtil.current
    Column(Modifier.fillMaxWidth().heightIn(min = 236.dp).padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.Center) {
        val text = when {
            p.offline -> stringResource(R.string.mix_edit_offline)
            p.side == Side.A -> stringResource(R.string.mix_edit_unreadable, EditorWords(LocalResources.current, aTitle, bTitle).owner(aTitle))
            else -> stringResource(R.string.mix_edit_unreadable_b, EditorWords(LocalResources.current, aTitle, bTitle).owner(bTitle))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { vm.retry() }) { Text(stringResource(R.string.mix_edit_try_again)) }
            // A downloaded song is read from its file, at any time and anywhere in it.
            if (!p.offline && song != null && song.song.dateDownload == null && !song.song.isLocal) {
                TextButton(onClick = { downloads.download(song.song) }) { Text(stringResource(R.string.mix_edit_download)) }
            }
        }
    }
}

/** Whether playback plays a change that plays the next song's intro twice; SpecPlan refuses one until M10. */
private const val INTRO_TWICE_PLAYS = false

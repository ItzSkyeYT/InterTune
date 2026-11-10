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
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.dd3boh.outertune.mix.edit.IntroLoop
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.LocalPlayerSheet
import com.dd3boh.outertune.ui.component.ScreenBackHandler
import com.dd3boh.outertune.ui.component.TopBarActions
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Edit transition (DESIGN.md section 3), on lows, mids and highs: the two songs' ends on one
 * canvas, the presets, the length, Beat matching, Fix the beat, undo, and a preview that plays
 * what is on the page. Opened for one ordered pair of songs; saving keeps the change for that pair
 * wherever the two meet.
 *
 * It opens on Simple ([MixSimpleSheet]), a sheet over the player with a row per choice; this page
 * is Advanced, under it. Both edit the one spec of the view model, so either way nothing is lost.
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
    val simple by vm.simple.collectAsState()
    val playerSheet = LocalPlayerSheet.current

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
    // The four parts are the page where they are at hand: open on them.
    val partsReady = vm.pair?.partsReady == true
    LaunchedEffect(partsReady) { if (partsReady) lanesView = true }
    var lengthOpen by remember { mutableStateOf(false) }
    var fixOpen by remember { mutableStateOf<Side?>(null) }
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
    // The preview starts on opening Advanced, from 2 bars before the change. Simple is a sheet
    // over what is playing: it waits for its play button.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase is MixEditorViewModel.Phase.Ready && !started) {
            started = true
            if (!vm.simple.value) vm.editor?.let { play(it.fitStart()) }
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

    // The change saves itself (autosave): leaving keeps it, with nothing to ask.
    fun close() = navController.navigateUp()
    // A preset chip, on either level: what it replaced is said where the level shows its messages.
    fun presetTapped(p: Preset, host: SnackbarHostState) {
        when (vm.edit { tapPreset(p) }) {
            PresetTap.REPLACED_CUSTOM -> scope.launch {
                val r = host.showSnackbar(resources.getString(R.string.mix_edit_replaced, wordsNow.presetName(p)), resources.getString(R.string.mix_edit_undo), duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) vm.edit { undo() }
            }
            PresetTap.NEEDS_PARTS -> said = resources.getString(R.string.mix_edit_needs_parts)
            else -> {}
        }
        canvas.flash = 1f
        scope.launch { Animatable(1f).animateTo(0f, tween(200)) { canvas.flash = value } }
    }
    // Back leaves Adjust first; with the player open over the page it is the player's.
    ScreenBackHandler(enabled = canvas.adjust != null) { leaveAdjust(vm, canvas) }

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

    // A guard that will refuse at playback, said on both levels, and a setting of the player that
    // keeps every change plain right now (casting, Repeat one, a speed, Surround upmix...), as the
    // playlist's lines say it: a change saved here would not play until it changes. With the page
    // that changes it, where there is one.
    val blocker = rememberMixBlocker()
    val formats = vm.pair?.let { it.a.pcm.info.sampleRate != it.b.pcm.info.sampleRate || it.a.pcm.info.channels != it.b.pcm.info.channels } == true
    // A change past what this phone holds in time (one saved before, or before the phone was
    // measured this slow) plays plain: said, with the fix that brings it closer to the end.
    val phoneGuard = spec?.let { s -> editor?.phoneGuard(s) }
    val guard = when {
        vm.fromId == vm.toId -> stringResource(R.string.mix_edit_guard_same_song)
        skipSilence -> stringResource(R.string.mix_edit_guard_skip_silence)
        formats -> stringResource(R.string.mix_edit_guard_formats)
        phoneGuard != null -> words.phoneGuard(phoneGuard)
        else -> blocker?.let { MixWords.blocked(it) }?.let { stringResource(it) }
    }
    val phoneFix = if (guard != null && phoneGuard != null && vm.fromId != vm.toId && !skipSilence && !formats) {
        GuardFix(stringResource(R.string.mix_edit_guard_phone_fix)) { vm.edit { fitToPhone() } }
    } else null
    val guardSetting = if (guard != null && vm.fromId != vm.toId && !formats && phoneFix == null) guardSetting(skipSilence, blocker) else null

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tall = maxHeight >= 700.dp
        val landscape = LocalLandscape.current.active
        val insets = LocalPlayerAwareWindowInsets.current

        @Composable
        fun Upper(small: Boolean) {
            // Simple | Advanced, upright under the top bar; on its side over the right half.
            if (!landscape) {
                LevelSwitch(simple = false, onSimple = { vm.showSimple(true) }, onAdvanced = {}, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            }
            if (guard != null) GuardBand(guard, guardSetting, phoneFix)
            SongRow(songA, aTitle, spec?.let { words.clock(it.a.atMs) }, spec?.let { vm.editor?.shownBpm(Side.A, it) }, spec?.a?.fix?.isNone == false, words, landscape, spec?.let { vm.editor?.beatNote(Side.A, it) }) { fixOpen = Side.A }
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
            SongRow(songB, bTitle, spec?.let { words.clock(it.b.atMs) }, spec?.let { vm.editor?.shownBpm(Side.B, it) }, spec?.b?.fix?.isNone == false, words, landscape, spec?.let { vm.editor?.beatNote(Side.B, it) }) { fixOpen = Side.B }
            // Songs and Bands only for a pair loaded without parts: never while it loads, when it is not known yet.
            BarRow(spec, words, lanesView, canvas.adjust, { lengthOpen = true }, { lanesView = it }, partsReady = vm.pair?.partsReady != false) { leaveAdjust(vm, canvas) }
            // Under the length row, the canvas's overview and its zoom buttons at its end, as an
            // editor keeps them at the end of its overview (REAPER, Vegas, Audition).
            val ready = phase is MixEditorViewModel.Phase.Ready
            val overview: (@Composable () -> Unit)? = if (ready && editor != null && spec != null) {
                { CanvasOverview(editor, spec, vm.pair, canvas, colors, { playU }, Modifier.fillMaxSize()) }
            } else null
            val zoom: (@Composable () -> Unit)? = if (ready && editor != null && spec != null) {
                { ZoomButtons(editor, spec, canvas, { playU }) }
            } else null
            NowLine(vm, editor, spec, words, phase, playing, { playU }, said, overview = overview, zoom = zoom)
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
            Lowers(
                vm, e, s, ui!!, words, phase is MixEditorViewModel.Phase.Ready && canvas.adjust == null, landscape, onPreset = { p -> presetTapped(p, snackbar) },
                onUserPreset = {
                    canvas.flash = 1f
                    scope.launch { Animatable(1f).animateTo(0f, tween(200)) { canvas.flash = value } }
                },
                onLimit = { actions.say(it) },
                onPresetDeleted = { p, i ->
                    scope.launch {
                        val r = snackbar.showSnackbar(resources.getString(R.string.mix_preset_deleted), resources.getString(R.string.mix_edit_undo), duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) vm.restorePreset(p, i)
                    }
                },
            )
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
                    SideBar(ui, ::close, onStep = { back -> step(vm, canvas, back) }, onSave = { if (canvas.adjust != null) leaveAdjust(vm, canvas); save(vm, context, haptic, navController) })
                    LevelSwitch(simple = false, onSimple = { vm.showSimple(true) }, onAdvanced = {}, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Lower()
                        Spacer(Modifier.height(96.dp))
                    }
                }
            }
        } else {
            val page = rememberScrollState()
            // The lower part keeps a scroll of its own under the canvas only where it has room above the Play pill,
            // which floats over it above the player and the bars (lowerPinned). Where it has not (a phone: the
            // pill covered the presets row, and no scroll could take a row out from under it), the page scrolls
            // as one, and the pill, which does not move, floats over it as over any list.
            var upperPx by remember { mutableIntStateOf(0) }
            val bars = insets.asPaddingValues().calculateBottomPadding()
            val upper = with(LocalDensity.current) { upperPx.toDp() }
            val pinned = tall && upperPx > 0 && lowerPinned(roomDp = (maxHeight - upper).value, barsDp = bars.value)
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal))
                    .then(if (!pinned) Modifier.verticalScroll(page) else Modifier),
            ) {
                Column(Modifier.onSizeChanged { upperPx = it.height }) {
                    Spacer(Modifier.windowInsetsTopHeight(insets))
                    Upper(small = !tall)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                }
                if (pinned) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Lower()
                        PillRoom(insets)
                    }
                } else {
                    Lower()
                    PillRoom(insets)
                }
            }
        }

        // The transport: one floating pane as wide as its content.
        val bottom = insets.only(WindowInsetsSides.Bottom)
        Transport(
            playing = playing,
            enabled = phase is MixEditorViewModel.Phase.Ready,
            // Read in the transport itself, so the page is not composed again on every frame it plays.
            // The playhead and the page's state first, whatever else is missing: they are what makes the clock move on.
            time = {
                val u = playU
                val s = ui?.spec
                val e = vm.editor
                if (e == null || s == null) "0:00 / 0:00" else {
                    // A bar as it is heard: B's, or A's where B is the song adjusted.
                    val span = (e.fitEnd() - e.fitStart()) * s.heardBarMs
                    val at = ((u - e.fitStart()) * s.heardBarMs).coerceIn(0.0, span)
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
                        IconButton(onClick = { step(vm, canvas, back = true) }, enabled = ui?.canUndo == true) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.mix_edit_undo))
                        }
                        IconButton(onClick = { step(vm, canvas, back = false) }, enabled = ui?.canRedo == true) {
                            Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.mix_edit_redo))
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            // Save keeps what Adjust shows: it closes Adjust first.
                            if (canvas.adjust != null) leaveAdjust(vm, canvas)
                            save(vm, context, haptic, navController)
                        },
                        enabled = ui?.dirty == true && phase is MixEditorViewModel.Phase.Ready,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(if (ui?.dirty == true) R.string.mix_edit_save else R.string.mix_edit_saved_state), maxLines = 1) }
                },
            )
        }
    }

    // Simple: the sheet over the player (or over this page, once Advanced has been opened).
    if (simple) {
        MixSimpleSheet(
            vm = vm,
            words = words,
            colors = colors,
            guard = guard,
            guardSetting = guardSetting,
            guardFix = phoneFix,
            playing = playing,
            playU = { playU },
            said = said,
            onSay = { said = it },
            onPlay = { if (playing) stop() else vm.editor?.let { e -> play(if (playU >= e.fitEnd() - 1e-6 || playU < e.fitStart()) e.fitStart() else playU) } },
            onPreset = ::presetTapped,
            onAdvanced = {
                vm.showSimple(false)
                // Advanced is this page: the player opened over it gives way.
                playerSheet?.collapseSoft()
            },
            onClose = ::close,
            onSave = { if (canvas.adjust != null) leaveAdjust(vm, canvas); save(vm, context, haptic, navController) },
            onFix = { fixOpen = it },
        )
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
}

/** The Play pill's height and the 16 dp under it: what it takes of the room above the player and the bars. */
internal const val PILL_ZONE_DP = 80f

/** What a row needs above the pill to be tapped: the presets' title and their chips, and a little more. */
internal const val MIN_ABOVE_PILL_DP = 140f

/**
 * Whether the page's lower part (presets, Beat matching, loops) scrolls on its own under the canvas.
 * [roomDp] is the height left under the canvas, [barsDp] what the player and the navigation bar take
 * at the bottom, over the page; the Play pill floats above them. On a Pixel 5 that left 10 dp above
 * the pill (258 - 168 - 80): the pill sat over the presets row, which had nowhere to go.
 */
internal fun lowerPinned(roomDp: Float, barsDp: Float): Boolean = roomDp - barsDp - PILL_ZONE_DP >= MIN_ABOVE_PILL_DP

/**
 * Room at the end of the page for the pill and the bars under it, as a list leaves room under a
 * floating bar: the last row scrolls up clear of both. The bars' part was a spacer of the *top*
 * inset's height, which is none: the last rows stopped under the player.
 */
@Composable
private fun PillRoom(insets: androidx.compose.foundation.layout.WindowInsets) {
    Spacer(Modifier.windowInsetsBottomHeight(insets))
    Spacer(Modifier.height(104.dp))
}

private fun leaveAdjust(vm: MixEditorViewModel, canvas: CanvasState) {
    val e = vm.editor ?: return
    val before = vm.adjustBefore
    canvas.adjust = null
    // Each edit made in Adjust is an undo step already (adjustStep); Adjust opened and left with
    // none takes back the curve it drew for the lane.
    if (before != null && !vm.adjustEdited) e.live(before)
    vm.adjustBefore = null
    vm.adjustEdited = false
    vm.publish()
}

/** Undo or redo, in Adjust too: Adjust closes where the step taken back leaves the lane with no curve to adjust. */
private fun step(vm: MixEditorViewModel, canvas: CanvasState, back: Boolean) {
    vm.edit { if (back) undo() else redo() }
    val lane = canvas.adjust ?: return
    val spec = vm.editor?.spec ?: return
    if (Side.entries.none { com.dd3boh.outertune.mix.edit.LaneId(it, lane) in spec.curves }) {
        canvas.adjust = null
        vm.adjustBefore = null
        vm.adjustEdited = false
        vm.publish()
    }
}

private fun save(vm: MixEditorViewModel, context: android.content.Context, haptic: androidx.compose.ui.hapticfeedback.HapticFeedback, navController: NavController) {
    vm.preview?.stop()
    vm.save { ok ->
        if (ok) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            Toaster.show(context, R.string.mix_edit_saved, Toast.LENGTH_SHORT)
            navController.navigateUp()
        } else {
            Toaster.show(context, R.string.error_unknown, Toast.LENGTH_LONG)
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
    fixed: Boolean,
    words: EditorWords,
    oneLine: Boolean,
    note: MixEditor.BeatNote?,
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
        // Where the beat is not sure, two words beside the tempo say so; the pill fixes it.
        if (note != null) Text(words.beatNote(note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        val pillColor = if (fixed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        val label = if (bpm != null) words.bpm(bpm) else stringResource(R.string.mix_edit_bpm_unknown)
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
    /** Both songs' parts are at hand: the page shows them, and there is no other view to switch to. */
    partsReady: Boolean = false,
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
        // With the parts at hand the page is the four parts, his choice of 7 Nov 2026: Songs and
        // Bands stay only for a pair whose parts are not made yet.
        if (!partsReady) SingleChoiceSegmentedButtonRow(Modifier.height(32.dp)) {
            SegmentedButton(selected = !lanesView, onClick = { onView(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.mix_edit_view_songs), maxLines = 1)
            }
            SegmentedButton(selected = lanesView, onClick = { onView(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(second), maxLines = 1)
            }
        }
    }
}

/**
 * The line under the bars: a refusal, or the loading stage. No narration of the change (his rule: no
 * comments in the mixer). With nothing to say, the [overview] of the canvas in its place, at the
 * same height, so nothing moves when a refusal comes and goes; the [zoom] buttons at its end.
 */
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
    overview: (@Composable () -> Unit)? = null,
    zoom: (@Composable () -> Unit)? = null,
) {
    val text by remember(editor, spec, words, phase, playing, said) {
        derivedStateOf {
            when {
                said != null -> said
                phase is MixEditorViewModel.Phase.Loading -> when (phase.stage) {
                    PairLoader.Stage.READING_A -> words.readingA()
                    PairLoader.Stage.READING_B -> words.readingB()
                    PairLoader.Stage.SPLITTING -> words.splitting(phase.progress)
                    PairLoader.Stage.ANALYSING -> words.analysing()
                }
                else -> ""
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (said != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (text.isEmpty() && overview != null) Box(Modifier.matchParentSize()) { overview() }
        }
        zoom?.invoke()
    }
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
    onUserPreset: () -> Unit = {},
    onLimit: (Limit) -> Unit = {},
    onPresetDeleted: (com.dd3boh.outertune.mix.edit.MixPreset, Int) -> Unit = { _, _ -> },
) {
    Text(stringResource(R.string.mix_edit_presets), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp))
    // The listener's own presets after the app's; one of them lit, Custom is not.
    val userPresets by vm.presets.collectAsState()
    val userLit = remember(spec, userPresets) { editor.litPreset(userPresets, spec) }
    // The lit chip is kept in view, as on Simple (PresetRow): the listener's own when one is lit, else the app's.
    val presetRow = rememberPresetRow(userLit?.id ?: ui.lit, remember(userPresets) { userPresets.map { it.id } }, 12.dp)
    val chips: @Composable () -> Unit = {
        DefaultChip(lit = spec.plain, enabled = ready) { vm.edit { plainChange() } }
        for (p in EditorPresets.ORDER) {
            val lit = ui.lit == p && !(p == Preset.CUSTOM && userLit != null)
            val allowed = !p.needsParts || spec.lanes == LaneSet.PARTS
            val enabled = ready && allowed && !(p == Preset.CUSTOM && !lit && editor.lastCustom == null)
            FilterChip(
                selected = lit,
                enabled = enabled,
                onClick = { onPreset(p) },
                label = { Text(words.presetName(p), maxLines = 1) },
                leadingIcon = if (lit) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                modifier = presetRow.chip(p),
            )
        }
        UserPresetChips(vm, editor, userPresets, userLit, canSave = !spec.plain, enabled = ready, onApplied = onUserPreset, onLimit = onLimit, onDeleted = onPresetDeleted, row = presetRow)
    }
    if (wrap) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) { chips() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(presetRow.scroll).padding(horizontal = 12.dp)) { chips() }
    }
    // No sentence under the chips: the lit chip names the preset, the canvas shows it (his rule of
    // 2 Nov 2026, "a mixer does not need comments").
    Spacer(Modifier.height(6.dp))
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
    // The song the beat matcher adjusts, as two decks' pitch faders: the lit one moves to the other's tempo.
    if (spec.beatMatched && editor.beatMatchPossible) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.mix_edit_bm_adjust), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 64.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                for (side in listOf(Side.A, Side.B)) FilterChip(
                    selected = spec.matchSide == side,
                    onClick = { vm.edit { setMatchSide(side) } },
                    label = { Text(if (side == Side.A) words.aTitle else words.bTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) },
                    enabled = ready && (side == Side.A || editor.bAdjustPossible || spec.matchSide == Side.B),
                )
            }
        }
        // Why the next song cannot be the one adjusted here: its way back would read past what was read.
        editor.bAdjustReadLimit?.let { readTo ->
            Text(
                stringResource(R.string.mix_edit_bm_read_to, words.bTitle, words.clock(readTo)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 88.dp, end = 12.dp),
            )
        }
    }
    // A loop on either song, as a DJ app has one on each deck: 1, 2 or 4 bars, 1, 2 or 4 times.
    // On the next song it plays in front of it from bar 1, under the outgoing one, which then
    // comes in on its own bar 1; on the outgoing song its bars play again before it goes on, so
    // the change can run past its end. On the grid, so only where both songs' beats were found.
    if (spec.bars > 0 && editor.facts.b.grid != null && editor.facts.a.grid != null) {
        var deck by remember { mutableStateOf(if (spec.outroLoop != null && spec.introLoop == null) Side.A else Side.B) }
        val bars = if (deck == Side.A) spec.outroLoop?.bars else spec.introLoop?.bars
        val times = if (deck == Side.A) spec.outroLoop?.times else spec.introLoop?.times
        fun set(n: Int?, k: Int) = vm.edit { if (deck == Side.A) setOutroLoop(n, k) else setIntroLoop(n, k) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.mix_edit_loop), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 64.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                for (side in listOf(Side.A, Side.B)) FilterChip(
                    selected = deck == side,
                    onClick = { deck = side },
                    label = { Text(if (side == Side.A) words.aTitle else words.bTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) },
                    enabled = ready,
                )
                for (n in IntroLoop.BARS) FilterChip(
                    selected = bars == n,
                    onClick = { set(if (bars == n) null else n, times ?: 1) },
                    label = { Text(words.bars(n)) },
                    enabled = ready,
                )
                if (bars != null) for (k in LOOP_TIMES) FilterChip(
                    selected = times == k,
                    onClick = { set(bars, k) },
                    label = { Text(stringResource(R.string.mix_edit_loop_times, k)) },
                    enabled = ready,
                )
            }
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
private fun SideBar(ui: MixEditorViewModel.Ui?, onClose: () -> Unit, onStep: (back: Boolean) -> Unit, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.mix_edit_close)) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onStep(true) }, enabled = ui?.canUndo == true) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.mix_edit_undo)) }
        IconButton(onClick = { onStep(false) }, enabled = ui?.canRedo == true) { Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.mix_edit_redo)) }
        FilledTonalButton(onClick = onSave, enabled = ui?.dirty == true, modifier = Modifier.padding(start = 4.dp)) { Text(stringResource(if (ui?.dirty == true) R.string.mix_edit_save else R.string.mix_edit_saved_state), maxLines = 1) }
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
internal fun FailedState(p: MixEditorViewModel.Phase.Failed, aTitle: String, bTitle: String, song: Song?, vm: MixEditorViewModel) {
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

/** How many times over a loop can be played, from the editor. */
private val LOOP_TIMES = listOf(1, 2, 4)

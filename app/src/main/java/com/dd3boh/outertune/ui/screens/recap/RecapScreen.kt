/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapReader
import com.dd3boh.outertune.recap.RecapShown
import com.dd3boh.outertune.recap.RecapSongShown
import com.dd3boh.outertune.recap.RecapSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Behind the Recap while it loads: the start screen's own red paper. */
internal val RecapNight = RecapInk.Red

/**
 * The year as a story, full screen, in the calm shapes look (experiments/features/recap/v5-shapes-calm):
 * the start screen, then the slides one after another (planSlides), each a loud sheet of paper
 * growing over the last, eight cut-paper shapes that stay on screen and change from slide to slide,
 * covers stuck on like stickers. A tap on the right goes on and on the left goes back, a press held
 * keeps everything where it is, and each slide moves on by itself when its time is up; the bars at
 * the top say how far the story has got. The pill at the foot names the slide's song, which plays
 * under it and on until the next slide's takes over ([sound], RecapSound). The thank-you page stays
 * until it is left.
 *
 * What a slide holds is in RecapSlides; how things move, in RecapMotion; the shapes and the sheets
 * in RecapStage. [onOpened] is told once the story is opened from the start screen: that is the
 * Recap looked at, which is what is counted (RecapViewModel.opened).
 */
@Composable
internal fun RecapStory(
    shown: RecapShown,
    onOpened: () -> Unit,
    onClose: () -> Unit,
    sound: RecapSound = RecapSound.Silent,
) {
    val context = LocalContext.current
    val recap = shown.recap
    val slides = remember(shown) { planSlides(shown) }
    val pages = remember(slides) { storyPages(slides) }
    val words = remember(recap.year, context) { RecapWords(context, recap.year) }
    val covers = remember { RecapCovers(context.applicationContext) }
    val still = remember { RecapMotion.still() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val opened by rememberUpdatedState(onOpened)
    val close by rememberUpdatedState(onClose)

    // Where the story is, kept across a turn of the phone: 0 is the start screen, then 1 to
    // slides.size; the step is the place in a slide of several, the countdown's.
    var savedAt by rememberSaveable { mutableIntStateOf(0) }
    var savedStep by rememberSaveable { mutableIntStateOf(0) }
    val stage = remember(slides) { RecapStage(slides, still, savedAt, savedStep) }
    stage.onPage = { a, s -> savedAt = a; savedStep = s }

    // The pieces of a slide are placed under the status bar and round the bars, as the design's are (RecapStage.pieceY).
    val density = LocalDensity.current
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val chromePx = with(density) { STORY_BARS_DP.dp.toPx() }
    SideEffect {
        stage.top = topInset.toFloat()
        stage.chrome = chromePx
    }

    var held by remember { mutableStateOf(false) }
    // The pause button: the story stays where it is, everything on the slide included, until it is
    // tapped again or the story is tapped on or back. A finger held down pauses only while it is down.
    var stopped by rememberSaveable { mutableStateOf(false) }
    var away by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(false) }
    val page = stage.current

    fun next() {
        val p = stage.current
        if (p.at == 0 || stage.opening) return
        val slide = slides[p.at - 1]
        if (p.step < slide.steps - 1) stage.go(p.at, p.step + 1, How.ON)
        else if (p.at < slides.size) stage.go(p.at + 1, 0, How.ON)
    }

    fun previous() {
        val p = stage.current
        if (p.at <= 1 || stage.opening) return
        if (p.step > 0) stage.go(p.at, p.step - 1, How.BACK)
        else stage.go(p.at - 1, slides[p.at - 2].steps - 1, How.BACK)
    }

    fun openStory() {
        if (stage.current.at != 0 || stage.opening) return
        opened()
        // The listener's music pauses, and the first song is made ready while the story opens.
        sound.open(pages)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        stage.open()
    }

    // Away from the app, the share sheet over it included: the story waits.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watcher = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> away = true
                Lifecycle.Event.ON_RESUME -> away = false
                else -> Unit
            }
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    // The story's clock, once a frame: by frames and not by an animation, so that with
    // animations switched off the story still keeps its time. A held story holds everything.
    val paused by rememberUpdatedState(held || stopped || away || sharing)
    LaunchedEffect(stage) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Real time down to ten frames a second; a longer gap (the screen off, a stall) is not motion.
            stage.frame(((now - last) / 1e6).coerceIn(0.0, 100.0), paused)
            last = now
            val p = stage.current
            if (p.at > 0 && !paused && !stage.opening) {
                val length = stage.lengthOf(p)
                if (length > 0 && stage.clock.now - p.start >= length) next()
            }
        }
    }

    // Each page as it comes up: a tick under the finger, a firmer one for number one, and its song.
    LaunchedEffect(page.visit) {
        if (page.at == 0) return@LaunchedEffect
        val slide = slides[page.at - 1]
        haptic.performHapticFeedback(if (slide.kind == SlideKind.NUMBER_ONE) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        // Opened already, unless the window was drawn again past its start screen.
        sound.open(pages)
        sound.show(pageOf(slides, page.at, page.step))
    }
    // Nothing plays while the story waits, nor away from the app. The speaker button mutes it.
    LaunchedEffect(held, stopped, away) { if (held || stopped || away) sound.pause() else sound.resume() }
    LaunchedEffect(muted) { sound.mute(muted) }
    // Headphones out, or another app's sound for good: the story pauses with its sound.
    DisposableEffect(sound) {
        sound.onInterrupted = { stopped = true }
        onDispose { sound.onInterrupted = null }
    }

    // The bars of the phone follow the paper: dark icons on a light one, light on a dark one.
    val view = LocalView.current
    val lightPaper = !page.paper.dark
    LaunchedEffect(lightPaper) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightPaper
                isAppearanceLightNavigationBars = lightPaper
            }
        }
    }

    // The year on one card, drawn once: the slide shows the very picture that is shared.
    var yearCard by remember(shown) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(shown) {
        yearCard = runCatching { drawYearCard(context, shown, words, covers) }.getOrNull()
    }

    fun share(top: Boolean) {
        if (sharing) return
        sharing = true
        scope.launch {
            try {
                val card = if (top) drawTopCard(context, shown, words, covers) else yearCard ?: drawYearCard(context, shown, words, covers)
                RecapCards.share(context, card, if (top) "intertune-recap-${recap.year}-number-one" else "intertune-recap-${recap.year}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                sharing = false
            }
        }
    }

    @Composable
    fun Content(s: SlideScope) {
        val p = s.page
        if (p.at == 0) {
            RecapStart(s, onOpen = { openStory() }, onClose = { close() })
            return
        }
        when (slides[p.at - 1].kind) {
            SlideKind.SINCE -> SinceSlide(s)
            SlideKind.MINUTES -> MinutesSlide(s)
            SlideKind.HOUR -> HourSlide(s)
            SlideKind.STREAK -> StreakSlide(s)
            SlideKind.ARTISTS -> ArtistsSlide(s)
            SlideKind.ONLY_HERE -> OnlySlide(s)
            SlideKind.COUNTDOWN -> CountdownSlide(s, p.step)
            SlideKind.NUMBER_ONE -> NumberOneSlide(s, sharing) { share(top = true) }
            SlideKind.CARD -> CardSlide(s, yearCard, sharing) { share(top = false) }
            SlideKind.THANKS -> ThanksSlide(s, onAgain = { stopped = false; stage.go(1, 0, How.ON) }, onDone = { close() })
        }
    }

    val nextWords = stringResource(R.string.recap_next)
    val previousWords = stringResource(R.string.recap_previous)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { stage.anchors.origin = it.positionInRoot() }
            .onSizeChanged { stage.resize(it.width.toFloat(), it.height.toFloat()) }
            .semantics {
                // As a tap on or back: a paused story starts again.
                customActions = listOf(
                    CustomAccessibilityAction(nextWords) { stopped = false; next(); true },
                    CustomAccessibilityAction(previousWords) { stopped = false; previous(); true },
                )
            }
            .pointerInput(slides) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (stage.current.at == 0 || stage.opening) return@awaitEachGesture
                    val back = down.position.x < size.width * RecapMotion.BACK_SHARE
                    var timedOut = true
                    val up = withTimeoutOrNull(RecapMotion.HOLD_MS) { waitForUpOrCancellation().also { timedOut = false } }
                    if (timedOut) {
                        // Held only while the finger is down: whatever the pause button says holds again after.
                        held = true
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            held = false
                        }
                    } else if (up != null) {
                        up.consume()
                        stopped = false
                        if (back) previous() else next()
                    }
                }
            }
    ) {
        Papers(stage)
        Shapes(stage)
        Grain()
        Confetti(stage)
        stage.leaving?.let { gone ->
            key(gone.page.visit) { PageView(gone.page, stage, shown, words, covers) { Content(it) } }
        }
        key(page.visit) { PageView(page, stage, shown, words, covers) { Content(it) } }

        val chrome by animateFloatAsState(if (page.at > 0 && !stage.opening) 1f else 0f, tween(300), label = "chrome")
        val ink by animateColorAsState(page.paper.ink, tween(RecapMotion.CHROME_COLOUR_MS), label = "ink")
        if (page.at > 0) {
            val current = slides[page.at - 1]
            StoryBars(
                count = slides.size,
                at = page.at,
                fraction = {
                    val length = stage.lengthOf(page)
                    val p = if (length <= 0L) 1f else ((stage.clock.now - page.start).toFloat() / length).coerceIn(0f, 1f)
                    (page.step + p) / current.steps
                },
                year = recap.year,
                ink = ink,
                stopped = stopped,
                onPause = { stopped = !stopped },
                onClose = { close() },
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = chrome },
            )
            SongPill(
                song = current.song(page.step),
                night = page.paper.dark,
                still = still,
                muted = muted,
                onMute = { muted = !muted },
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chrome },
            )
        }
    }
}

/** The papers: the slide's own, and while one changes to the next the new sheet growing over the old. */
@Composable
private fun Papers(stage: RecapStage) {
    val sheet = remember { Path() }
    val rim = remember { Path() }
    Canvas(Modifier.fillMaxSize()) {
        val w = stage.wipe
        val now = if (w == null) 0L else stage.clock.now
        val u = stage.unit
        if (w == null || w.done(now)) {
            drawRect(stage.current.paper.paper)
        } else {
            drawRect(w.under)
            when (w.kind) {
                WipeKind.FADE -> drawRect(w.over, alpha = RecapMotion.Ease.transform(w.progress(now)))
                WipeKind.GROW -> {
                    for (i in w.rims.indices.reversed()) {
                        w.rim(i, now, rim)
                        paperShadow(rim, u, 1f, 1.3f)
                        drawPath(rim, w.rims[i])
                    }
                    w.inside(now, size, sheet)
                    paperShadow(sheet, u, 1f, 1.3f)
                    drawPath(sheet, w.over)
                }
                WipeKind.STRIPS -> {
                    w.inside(now, size, sheet)
                    paperShadow(sheet, u, 1f, 1.3f)
                    drawPath(sheet, w.over)
                }
            }
        }
    }
}

/**
 * The grain of the paper, once over the papers and the shapes alike and under the words and the
 * stickers: one pass for the whole screen rather than one for each piece of paper, which a
 * mid-range phone feels at every frame.
 */
@Composable
private fun Grain() {
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    Canvas(Modifier.fillMaxSize()) { drawRect(grain) }
}

/** The eight paper shapes, between the papers and the words. */
@Composable
private fun Shapes(stage: RecapStage) {
    val paths = remember { ActorPaths() }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = stage.shapesAlpha(stage.clock.now) }
    ) {
        drawActors(stage.actors, stage.clock.now, rest = !stage.still, unit = stage.unit, paths = paths)
    }
}

/**
 * Confetti flies between the paper shapes and the words, and behind the words: on the lines of
 * words a slide marks (Modifier.overConfetti, number one's), once they begin to arrive, it is not
 * drawn at all, so they read clearly while it falls rather than seeming to be under it.
 */
@Composable
private fun Confetti(stage: RecapStage) {
    val clear = remember { Path() }
    Canvas(Modifier.fillMaxSize()) {
        if (!stage.confetti.active) return@Canvas
        val now = stage.clock.now
        val page = stage.current
        val lines = stage.anchors.behind(page.visit, now - page.start)
        if (lines.isEmpty()) {
            with(stage.confetti) { draw(now) }
            return@Canvas
        }
        val margin = CONFETTI_MARGIN_DP.dp.toPx()
        clear.rewind()
        for (line in lines) clear.addRoundRect(RoundRect(line.inflate(margin), CornerRadius(margin)))
        clipPath(clear, ClipOp.Difference) { with(stage.confetti) { draw(now) } }
    }
}

/** How far round a line of words confetti is kept clear of it. */
private const val CONFETTI_MARGIN_DP = 4

/**
 * One page's words and stickers: the new one inside the sheet growing in, the old one outside it
 * until it is covered, or fading where no sheet comes (into the first slide, or with Remove
 * animations on, when the new one fades in too).
 */
@Composable
private fun PageView(
    page: PageState,
    stage: RecapStage,
    shown: RecapShown,
    words: RecapWords,
    covers: RecapCovers,
    content: @Composable (SlideScope) -> Unit,
) {
    val s = remember(page.visit) { SlideScope(shown, words, covers, page.paper, stage, page) }
    val clip = remember { Path() }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                val w = stage.wipe
                val gone = stage.leaving?.takeIf { it.page.visit == page.visit }
                when {
                    w == null -> if (gone == null || !gone.covered) drawContent()
                    else -> {
                        val now = stage.clock.now
                        when {
                            // Covered: nothing of it shows once the sheet is all the way over it.
                            gone != null && gone.covered && w.done(now) -> Unit
                            w.kind == WipeKind.FADE || w.done(now) -> drawContent()
                            else -> {
                                w.inside(now, size, clip)
                                clipPath(clip, if (gone == null) ClipOp.Intersect else ClipOp.Difference) { this@drawWithContent.drawContent() }
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                val w = stage.wipe
                val gone = stage.leaving?.takeIf { it.page.visit == page.visit }
                val going = s.leftFor()
                when {
                    gone == null && going <= 0f && w != null && w.kind == WipeKind.FADE && !w.done(stage.clock.now) -> alpha = RecapMotion.Ease.transform(w.progress(stage.clock.now))
                    going > 0f && (gone == null || !gone.covered) -> {
                        if (stage.still) {
                            alpha = 1f - (going / RecapMotion.CALM_LEAVE_MS).coerceIn(0f, 1f)
                        } else {
                            val e = RecapMotion.LeaveEasing.transform((going / RecapMotion.LEAVE_MS).coerceIn(0f, 1f))
                            alpha = 1f - e
                            scaleX = 1f + 0.05f * e
                            scaleY = scaleX
                        }
                    }
                }
            }
    ) { content(s) }
}

/** How tall the bars at the top are under the status bar: 9 dp, the bars' 3, 5 more and the row of 40 (StoryBars). */
private const val STORY_BARS_DP = 57

/**
 * The bars at the top, one for each slide, filling as it plays; the mark, the year, the pause
 * button and a way out, in the slide's ink.
 */
@Composable
private fun StoryBars(count: Int, at: Int, fraction: () -> Float, year: Int, ink: Color, stopped: Boolean, onPause: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = 9.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(3.dp)) {
            val gap = 4.dp.toPx()
            val width = (size.width - gap * (count - 1)) / count
            val corner = CornerRadius(size.height / 2)
            for (i in 0 until count) {
                val left = i * (width + gap)
                drawRoundRect(ink.copy(alpha = 0.3f), Offset(left, 0f), Size(width, size.height), corner)
                val filled = when {
                    i < at - 1 -> 1f
                    i == at - 1 -> fraction().coerceIn(0f, 1f)
                    else -> 0f
                }
                if (filled > 0f) drawRoundRect(ink, Offset(left, 0f), Size(width * filled, size.height), corner)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp, start = 14.dp, end = 6.dp)
                .height(40.dp),
        ) {
            RecapMark(20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recap_short), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
            Text(" $year", fontSize = 14.sp, color = ink)
            Spacer(Modifier.weight(1f))
            PauseButton(stopped, onPause, ink)
            CloseButton(onClose, tint = ink)
        }
    }
}

/** Two bars while the story runs, a play triangle while it is paused. */
@Composable
private fun PauseButton(stopped: Boolean, onClick: () -> Unit, tint: Color) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (stopped) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            stringResource(if (stopped) R.string.recap_play else R.string.recap_pause),
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The way out, in the ink of whatever is behind it. */
@Composable
internal fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier, tint: Color = RecapInk.Ink) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, stringResource(R.string.recap_close), tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Where the song plays: one floating pill of ink, as wide as what it holds. The song that was
 * playing slides up and out while the next one comes up from below, and the sound bars rise once
 * with it, one after another, and stand still. With Remove animations on, the two cross-fade.
 */
@Composable
private fun SongPill(song: RecapSongShown?, night: Boolean, still: Boolean, muted: Boolean, onMute: () -> Unit, modifier: Modifier = Modifier) {
    if (song == null) return
    val shape = RoundedCornerShape(26.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(bottom = 12.dp)
            .widthIn(max = 366.dp)
            .height(52.dp)
            .shadow(10.dp, shape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .clip(shape)
            .background(if (night) RecapInk.NightPill else RecapInk.Ink)
            .border(1.5.dp, RecapInk.Cream.copy(alpha = if (night) 0.22f else 0.1f), shape)
            .animateContentSize(tween(RecapMotion.PILL_WIDTH_MS, easing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f)))
            .padding(start = 8.dp, end = 4.dp),
    ) {
        AnimatedContent(
            targetState = song,
            transitionSpec = {
                if (still) fadeIn(tween(420)) togetherWith fadeOut(tween(420))
                else (slideInVertically(tween(RecapMotion.PILL_IN_MS, RecapMotion.PILL_IN_DELAY_MS, RecapMotion.Snap)) { it / 2 } +
                    fadeIn(tween(RecapMotion.PILL_IN_MS, RecapMotion.PILL_IN_DELAY_MS))) togetherWith
                    (slideOutVertically(tween(RecapMotion.PILL_OUT_MS, easing = CubicBezierEasing(0.5f, 0f, 0.75f, 0f))) { -it / 2 } +
                        fadeOut(tween(RecapMotion.PILL_OUT_MS)))
            },
            label = "pill",
            modifier = Modifier.weight(1f, fill = false),
        ) { shownSong ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = shownSong.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(RecapInk.Cream.copy(alpha = 0.1f)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.widthIn(max = 230.dp)) {
                    Text(shownSong.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = RecapInk.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shownSong.artists, fontSize = 12.sp, color = RecapInk.Cream.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        SoundBars(song, muted, still)
        // Mutes the story's sound and lets it play on unheard (RecapSound.mute).
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onMute), contentAlignment = Alignment.Center) {
            Icon(
                if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                stringResource(if (muted) R.string.recap_sound_off else R.string.recap_sound_on),
                tint = RecapInk.Cream,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Four sound bars in lemon that stand still; with each new song they rise once, one after another. */
@Composable
private fun SoundBars(song: RecapSongShown, muted: Boolean, still: Boolean) {
    val heights = floatArrayOf(0.5f, 0.95f, 0.68f, 0.38f)
    val rise = remember { List(4) { Animatable(1f) } }
    LaunchedEffect(song.id) {
        if (still) return@LaunchedEffect
        rise.forEachIndexed { i, bar ->
            launch {
                bar.snapTo(0f)
                delay(220L + i * 60L)
                bar.animateTo(1f, tween(RecapMotion.EQ_MS, easing = RecapMotion.Bouncy))
            }
        }
    }
    Canvas(Modifier.padding(start = 2.dp).size(19.dp, 16.dp)) {
        val bar = 2.75.dp.toPx()
        val gap = (size.width - 4 * bar) / 3
        for (i in 0 until 4) {
            val h = size.height * if (muted) 0.25f else (0.12f + (heights[i] - 0.12f) * rise[i].value)
            drawRoundRect(RecapInk.Lemon, Offset(i * (bar + gap), size.height - h), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

/** "My number one song", drawn at story size from the largest cover there is. */
private suspend fun drawTopCard(context: android.content.Context, shown: RecapShown, words: RecapWords, covers: RecapCovers): Bitmap {
    val model = shown.song(shown.recap.topSongs.first().songId)?.cover
    val cover = covers.bitmap(model, RecapReader.COVER_PX)
    return withContext(Dispatchers.Default) { RecapCards.top(context, shown, words, cover) }
}

/** The year on one card: the five covers fanned out as stickers. */
private suspend fun drawYearCard(context: android.content.Context, shown: RecapShown, words: RecapWords, covers: RecapCovers): Bitmap = coroutineScope {
    val models = shown.recap.topSongs.map { shown.song(it.songId)?.cover }
    val bitmaps = models.map { async { covers.bitmap(it, 600) } }.awaitAll()
    withContext(Dispatchers.Default) { RecapCards.year(context, shown, words, bitmaps) }
}

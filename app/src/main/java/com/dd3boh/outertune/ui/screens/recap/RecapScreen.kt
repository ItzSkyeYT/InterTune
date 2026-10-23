/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapPiece
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

/** The Recap's own dark, under the start screen and behind every slide while it loads. */
internal val RecapNight = Color(0xFF0E0A0A)

/**
 * The year as a story, full screen, as the approved mock tells it: the start screen, then the
 * slides one after another (planSlides). A tap on the right goes on and on the left goes back, a
 * press held keeps the slide where it is, and each slide moves on by itself when its time is up;
 * the bars at the top say how far the story has got. The pill at the foot names the slide's song
 * and the piece of it that plays (RecapSound: nothing plays in phase 1). The thank-you page
 * stays until it is left.
 *
 * What it draws, slide by slide, is in RecapSlides; how things move, in RecapMotion. [onOpened]
 * is told once the story is opened from the start screen: that is the Recap looked at, which is
 * what is counted (RecapViewModel.opened).
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
    val words = remember(recap.year, context) { RecapWords(context, recap.year) }
    val covers = remember { RecapCovers(context.applicationContext) }
    val still = remember { RecapMotion.still() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val opened by rememberUpdatedState(onOpened)
    val close by rememberUpdatedState(onClose)

    // Where the story is: 0 is the start screen, then 1 to slides.size. [step] is the place in a
    // slide of several, the countdown's.
    var at by rememberSaveable { mutableIntStateOf(0) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    // One more each time a slide comes up, so that coming back to one plays its entrances again.
    var visit by remember { mutableIntStateOf(0) }
    // Each slide's last step and visit, so that the one fading out stays as it was.
    val stepOf = remember { mutableStateMapOf<Int, Int>() }
    val visitOf = remember { mutableStateMapOf<Int, Int>() }
    var opening by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var away by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(false) }
    // The first slide spreading out of the Open button, and the start screen opening under it.
    val reveal = remember { Animatable(1f) }
    val openness = remember { Animatable(0f) }
    var revealFrom by remember { mutableStateOf(Offset.Zero) }

    fun go(slide: Int, toStep: Int) {
        at = slide
        step = toStep
        visit++
        stepOf[slide] = toStep
        visitOf[slide] = visit
    }

    fun next() {
        if (at == 0) return
        val slide = slides[at - 1]
        if (step < slide.steps - 1) {
            step++
            stepOf[at] = step
        } else if (at < slides.size) {
            go(at + 1, 0)
        }
    }

    fun previous() {
        if (at <= 1) return
        if (step > 0) {
            step--
            stepOf[at] = step
        } else {
            go(at - 1, slides[at - 2].steps - 1)
        }
    }

    fun openStory(from: Offset) {
        if (at != 0 || opening) return
        opened()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (still) {
            go(1, 0)
            return
        }
        revealFrom = from
        opening = true
        scope.launch {
            openness.snapTo(0f)
            launch { openness.animateTo(1f, tween(RecapMotion.OPEN_MS.toInt() + RecapMotion.REVEAL_MS, easing = RecapMotion.OpenEasing)) }
            delay(RecapMotion.OPEN_MS)
            reveal.snapTo(0f)
            go(1, 0)
            reveal.animateTo(1f, tween(RecapMotion.REVEAL_MS, easing = RecapMotion.RevealEasing))
            opening = false
        }
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

    // How far the slide on screen has got, 0 to 1, and the clock that moves it on: by frames and
    // not by an animation, so that with animations switched off the story still keeps its time.
    val progress = remember(at, step, visit) { mutableFloatStateOf(0f) }
    val paused = held || away || sharing || opening
    LaunchedEffect(progress, paused) {
        if (at == 0) return@LaunchedEffect
        val ms = slides[at - 1].ms
        if (ms <= 0L) {
            progress.floatValue = 1f
            return@LaunchedEffect
        }
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (progress.floatValue < 1f) {
            val now = withFrameNanos { it }
            progress.floatValue = (progress.floatValue + (now - last) / 1e6f / ms).coerceAtMost(1f)
            last = now
        }
        next()
    }

    // Each slide as it comes up: a tick under the finger, a firmer one for number one, and its song.
    LaunchedEffect(at, step, visit) {
        if (at == 0) return@LaunchedEffect
        val slide = slides[at - 1]
        haptic.performHapticFeedback(if (slide.kind == SlideKind.NUMBER_ONE) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        val song = slide.song(step)
        val piece = slide.piece(step)
        if (song != null && piece != null) sound.play(song.id, piece)
    }
    LaunchedEffect(held, away) { if (held || away) sound.pause() else sound.resume() }
    DisposableEffect(sound) { onDispose { sound.stop() } }

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

    val nextWords = stringResource(R.string.recap_next)
    val previousWords = stringResource(R.string.recap_previous)
    Box(
        Modifier
            .fillMaxSize()
            .background(RecapNight)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(nextWords) { next(); true },
                    CustomAccessibilityAction(previousWords) { previous(); true },
                )
            }
            .pointerInput(slides) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (at == 0 || opening) return@awaitEachGesture
                    val back = down.position.x < size.width * RecapMotion.BACK_SHARE
                    var timedOut = true
                    val up = withTimeoutOrNull(RecapMotion.HOLD_MS) { waitForUpOrCancellation().also { timedOut = false } }
                    if (timedOut) {
                        held = true
                        waitForUpOrCancellation()
                        held = false
                    } else if (up != null) {
                        up.consume()
                        if (back) previous() else next()
                    }
                }
            }
    ) {
        // Under the slides: as it opens, the first slide spreads over it from the button.
        if (at == 0 || opening) {
            RecapStart(
                shown = shown,
                words = words,
                covers = covers,
                still = still,
                openness = { openness.value },
                onOpen = ::openStory,
                onClose = { close() },
            )
        }

        if (at > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val r = reveal.value
                        if (r < 1f) {
                            clip = true
                            shape = CircleReveal(revealFrom, r)
                        }
                    }
            ) {
                AnimatedContent(targetState = at, transitionSpec = { RecapMotion.slideChange(this) }, label = "slide") { index ->
                    val slide = slides[index - 1]
                    val stepHere = stepOf[index] ?: 0
                    val colours = slideColours(covers, slide.cover(stepHere), brand = slide.kind == SlideKind.THANKS)
                    val s = SlideScope(shown, words, covers, colours, still, key = visitOf[index] ?: 0)
                    Box(Modifier.fillMaxSize()) {
                        if (slide.kind == SlideKind.THANKS) BrandBackdrop() else CoverBackdrop(covers, slide.cover(stepHere), colours, still)
                        when (slide.kind) {
                            SlideKind.SINCE -> SinceSlide(s)
                            SlideKind.MINUTES -> MinutesSlide(s)
                            SlideKind.HOUR -> HourSlide(s)
                            SlideKind.STREAK -> StreakSlide(s)
                            SlideKind.ARTISTS -> ArtistsSlide(s)
                            SlideKind.ONLY_HERE -> OnlyHereSlide(s)
                            SlideKind.COUNTDOWN -> CountdownSlide(s, stepHere)
                            SlideKind.NUMBER_ONE -> NumberOneSlide(s, sharing) { share(top = true) }
                            SlideKind.CARD -> CardSlide(s, yearCard, sharing) { share(top = false) }
                            SlideKind.THANKS -> ThanksSlide(s, onAgain = { go(1, 0) }, onDone = { close() })
                        }
                    }
                }
            }
        }

        val chrome by animateFloatAsState(if (at > 0 && !opening) 1f else 0f, tween(300), label = "chrome")
        if (at > 0) {
            val current = slides[at - 1]
            StoryBars(
                count = slides.size,
                at = at,
                fraction = { (step + progress.floatValue) / current.steps },
                year = recap.year,
                onClose = { close() },
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = chrome },
            )
            SongPill(
                song = current.song(step),
                piece = current.piece(step),
                progress = { progress.floatValue },
                muted = muted,
                onMute = { muted = !muted },
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chrome },
            )
        }
    }
}

/** The bars at the top, one for each slide, filling as it plays; the mark, the year and a way out. */
@Composable
private fun StoryBars(count: Int, at: Int, fraction: () -> Float, year: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = 8.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(3.dp)) {
            val gap = 4.dp.toPx()
            val width = (size.width - gap * (count - 1)) / count
            val corner = CornerRadius(size.height / 2)
            for (i in 0 until count) {
                val left = i * (width + gap)
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(left, 0f), Size(width, size.height), corner)
                val filled = when {
                    i < at - 1 -> 1f
                    i == at - 1 -> fraction().coerceIn(0f, 1f)
                    else -> 0f
                }
                if (filled > 0f) drawRoundRect(Color.White, Offset(left, 0f), Size(width * filled, size.height), corner)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp, start = 16.dp, end = 8.dp)
                .height(36.dp),
        ) {
            RecapMark(22.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recap_short), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.92f))
            Text(" $year", fontSize = 14.sp, color = Color.White.copy(alpha = 0.62f))
            Spacer(Modifier.weight(1f))
            CloseButton(onClose)
        }
    }
}

/** The way out, white on whatever is behind it. */
@Composable
internal fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, stringResource(R.string.recap_close), tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * Where the song plays: one floating pill, as wide as what it holds. The faint line is the whole
 * song and the brighter piece of it what plays under this slide, filling as the slide plays.
 */
@Composable
private fun SongPill(
    song: RecapSongShown?,
    piece: RecapPiece?,
    progress: () -> Float,
    muted: Boolean,
    onMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (song == null) return
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(bottom = 18.dp)
            .widthIn(min = 220.dp, max = 356.dp)
            .height(56.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.13f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight().padding(start = 10.dp, end = 6.dp)) {
            AsyncImage(
                model = song.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(song.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists, fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            // Phase 1 is silent (RecapSound): the button only shows the choice there will be.
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onMute), contentAlignment = Alignment.Center) {
                Icon(
                    if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    stringResource(if (muted) R.string.recap_sound_off else R.string.recap_sound_on),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        val duration = (song.durationS.takeIf { it > 0 } ?: 180) * 1000f
        // Over the pill and no part of its size, which stays that of what it holds.
        Canvas(Modifier.matchParentSize()) {
            val side = 22.dp.toPx()
            val span = size.width - 2 * side
            val thick = 4.dp.toPx()
            val top = size.height - 5.dp.toPx() - thick
            val line = 2.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(side, top + (thick - line) / 2), Size(span, line), CornerRadius(line / 2))
            if (piece != null && span > 0f) {
                val from = side + piece.fromMs / duration * span
                val width = maxOf(10.dp.toPx(), piece.lengthMs / duration * span).coerceAtMost(side + span - from)
                val corner = CornerRadius(thick / 2)
                drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(from, top), Size(width, thick), corner)
                drawRoundRect(Color.White, Offset(from, top), Size(width * progress().coerceIn(0f, 1f), thick), corner)
            }
        }
    }
}

/** "My number one song", drawn at story size from the largest cover there is. */
private suspend fun drawTopCard(context: android.content.Context, shown: RecapShown, words: RecapWords, covers: RecapCovers): Bitmap {
    val model = shown.song(shown.recap.topSongs.first().songId)?.cover
    val cover = covers.bitmap(model, RecapReader.COVER_PX)
    val colours = covers.colours(model)
    return withContext(Dispatchers.Default) { RecapCards.top(context, shown, words, cover, colours) }
}

/** The year on one card: the five covers fanned out, in the number one's colours. */
private suspend fun drawYearCard(context: android.content.Context, shown: RecapShown, words: RecapWords, covers: RecapCovers): Bitmap = coroutineScope {
    val models = shown.recap.topSongs.map { shown.song(it.songId)?.cover }
    val bitmaps = models.map { async { covers.bitmap(it, 600) } }.awaitAll()
    val colours = covers.colours(models.firstOrNull())
    withContext(Dispatchers.Default) { RecapCards.year(context, shown, words, bitmaps, colours) }
}

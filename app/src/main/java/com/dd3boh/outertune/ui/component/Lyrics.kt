/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LyricClickable
import com.dd3boh.outertune.constants.LyricAutoScrollKey
import com.dd3boh.outertune.constants.LyricFontSizeKey
import com.dd3boh.outertune.constants.LyricKaraokeEnable
import com.dd3boh.outertune.constants.LyricLatinKey
import com.dd3boh.outertune.constants.LyricUpdateSpeed
import com.dd3boh.outertune.constants.LyricsPosition
import com.dd3boh.outertune.constants.LyricsTextPositionKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.Speed
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import com.dd3boh.outertune.extensions.isPowerSaver
import com.dd3boh.outertune.lyrics.LyricsShare
import com.dd3boh.outertune.lyrics.LyricsLatin
import com.dd3boh.outertune.lyrics.latinTools
import com.dd3boh.outertune.lyrics.LyricsVideoPlan
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.shimmer.ShimmerHost
import com.dd3boh.outertune.ui.component.shimmer.TextPlaceholder
import com.dd3boh.outertune.ui.menu.LyricsMenu
import com.dd3boh.outertune.ui.utils.fadingEdge
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import java.io.File
import kotlin.time.Duration.Companion.seconds

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun Lyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    /** The small player's lyrics view, where the fades are kept short; see [lyricsFadeLength]. */
    smallWindow: Boolean = false,
    /**
     * Invoked when the user taps the "lyrics not found" state. There is nothing to read and nothing
     * to seek to, so the tap that got them here should also get them back to the artwork rather
     * than leaving them on a dead screen that only the menu can escape.
     */
    onNoLyricsClick: (() -> Unit)? = null,
    /**
     * Extra room left clear below the close/more button row, on top of its own padding. Used by
     * the landscape two-pane player, where this view's bottom corner would otherwise sit under
     * the collapsed queue sheet's expand arrow.
     */
    bottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val density = LocalDensity.current
    var (showLyrics, onShowLyricsChange) = rememberPreference(ShowLyricsKey, false)
    val landscapeOffset = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val lyricsTextPosition by rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.CENTER)
    val lyricsFontSize by rememberPreference(LyricFontSizeKey, 20)

    val lyricsClickable by rememberPreference(LyricClickable, true)
    val lyricsAutoScroll by rememberPreference(LyricAutoScrollKey, true)
    val lyricsFancy by rememberPreference(LyricKaraokeEnable, false)
    val lyricsUpdateSpeed by rememberEnumPreference(LyricUpdateSpeed, Speed.MEDIUM)
    var lyricRefreshRate = lyricsUpdateSpeed.toLrcRefreshMillis()
    val showLatin by rememberPreference(LyricLatinKey, false)

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    // NOTE: lyricsModel is the current display lyrics that is updated by playerLyrics AND/OR manually
    val playerLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
    var lyricsModel by remember { mutableStateOf(playerLyrics) }

    val lines: SnapshotStateList<LyricLine> = remember { mutableStateListOf<LyricLine>() }

    val isSynced = remember(lyricsModel) {
        lyricsModel is SemanticLyrics.SyncedLyrics
    }

    LaunchedEffect(playerLyrics) {
        lyricsModel = playerLyrics
    }

    // Lines picked to share as a picture, or with synced lyrics a video, by their place in
    // [lines]. Picking starts with a long press on a line, or from the menu, and ends with Share,
    // the cross, Back, or other lyrics.
    // Kept outside this view (LyricsPicking): the player makes it anew when it turns between its
    // upright and its sideways layout, and a turn of the phone lost the picks and the dialog.
    val keep: LyricsPicking = viewModel()
    var picking by keep::picking
    val picked = keep.picked
    // The picked lines, while their picture is shown before it is sent, their video, and the song
    // they are from. That song, and not whichever plays: a video can take longer to make than
    // what is left of the song, and the next one's lyrics coming up does not close the dialog.
    var cardLines by keep::cardLines
    var cardVideo by keep::cardVideo
    var cardSong by keep::cardSong
    val mostLines = pluralStringResource(R.plurals.lyrics_pick_most, LyricsShare.MOST_LINES, LyricsShare.MOST_LINES)
    val mostForVideo = pluralStringResource(R.plurals.lyrics_pick_most_video, LyricsShare.MOST_LINES, LyricsShare.MOST_LINES)

    /** The lines with their times, for a video of them; none for lyrics that are not synced. */
    fun timedLines(): List<LyricsVideoPlan.Timed>? = if (!isSynced) null else lines.map {
        LyricsVideoPlan.Timed(it.text, it.start.toLong(), it.end.toLong(), it.isTranslated)
    }

    /** The video of the lines at [chosen], or null where there can be none. */
    fun videoOf(chosen: Collection<Int>): LyricsVideoPlan.Clip? =
        timedLines()?.let { LyricsVideoPlan.clip(it, chosen, playerConnection.player.duration.takeIf { ms -> ms > 0 }) }

    // How long the picked lines' video is, for the picking bar: worked out when the picks change,
    // not on each of the view's ticks.
    val pickedVideoMs by remember(isSynced) { derivedStateOf { if (picked.isEmpty()) null else videoOf(picked)?.durationMs } }

    fun stopPicking() {
        picking = false
        picked.clear()
    }

    /**
     * A line tapped or long pressed while picking: see [LyricsShare.toggle]. As many lines as a
     * picture takes, or with synced lyrics as many as a video does, whichever is more.
     */
    fun pick(index: Int) {
        val fits: (List<Int>) -> Boolean = { chosen -> chosen.size <= LyricsShare.MOST_LINES || videoOf(chosen) != null }
        when (val result = LyricsShare.toggle(picked.toList(), index, lines.map { it.text }, fits)) {
            is LyricsShare.Pick.Picked -> {
                picked.clear()
                picked.addAll(result.lines)
            }

            LyricsShare.Pick.Full -> Toast.makeText(context, if (isSynced) mostForVideo else mostLines, Toast.LENGTH_SHORT).show()

            LyricsShare.Pick.Empty -> Unit
        }
    }

    LaunchedEffect(lyricsModel) {
        lines.clear()
        // Picks belong to the lyrics they were made in, and other lyrics end the picking. The same
        // ones coming back, as they do when the view is made anew, keep it; none yet is no change.
        if (lyricsModel != null && keep.pickedIn != lyricsModel) {
            picking = false
            picked.clear()
            keep.pickedIn = lyricsModel
        }
        lyricsModel?.let { model ->
            if (isSynced) {
                val lyrics = lyricsModel as SemanticLyrics.SyncedLyrics
                lines.addAll(lyrics.text)

                if (lyricsFancy && lyrics.text.fastAny { it.words != null }) {
                    lyricRefreshRate = lyricsUpdateSpeed.toLrcRefreshMillis()
                } else {
                    lyricRefreshRate = Speed.SLOW.toLrcRefreshMillis()
                }
            } else {
                // A line each, as synced lyrics have, so that one can be picked to share. They
                // stand with no room between them below, and read as the one block they were.
                model.unsyncedText.forEach { (text, _) ->
                    lines.add(LyricLine(text, 0L.toULong(), 0L.toULong(), null, null, false))
                }
                lyricRefreshRate = Speed.SLOW.toLrcRefreshMillis()
            }
        }
    }

    // Show in Latin letters: each line's Latin version (LyricsLatin), worked out once for the lyrics
    // on screen and off the main thread, so that scrolling only looks it up. It is kept with the
    // lyrics it was made from, so that none is shown under the next song's lines while theirs are
    // worked out. A translation in the file gets none: it is not what is sung.
    var latin by remember { mutableStateOf<Pair<SemanticLyrics, Map<String, String>>?>(null) }
    LaunchedEffect(lyricsModel, showLatin) {
        val model = lyricsModel
        val tools = latinTools()
        if (!showLatin || tools == null || model == null || model == uninitializedLyric) {
            latin = null
            return@LaunchedEffect
        }
        val sung = if (model is SemanticLyrics.SyncedLyrics) {
            model.text.filterNot { it.isTranslated }.map { it.text }
        } else {
            model.unsyncedText.map { it.first }
        }
        latin = model to withContext(Dispatchers.Default) { LyricsLatin.of(sung, tools) }
    }
    val latinLines = latin?.takeIf { it.first === lyricsModel }?.second

    val textColor = MaterialTheme.colorScheme.secondary
    val prevTextColor = MaterialTheme.colorScheme.primary
    val pickedColor = textColor.copy(alpha = 0.16f)
    val lineIndication = LocalIndication.current

    var currentLineIndex by remember {
        mutableIntStateOf(-1)
    }
    // Because LaunchedEffect has delay, which leads to inconsistent with current line color and scroll animation,
    // we use deferredCurrentLineIndex when user is scrolling
    var deferredCurrentLineIndex by rememberSaveable {
        mutableIntStateOf(0)
    }

    var lastPreviewTime by rememberSaveable {
        mutableLongStateOf(0L)
    }
    var isSeeking by remember {
        mutableStateOf(false)
    }
    var currentPos by remember { mutableLongStateOf(0L) }

    // Every 125 ms, or with word-by-word karaoke every 33 ms by default and 16 ms at the fastest
    // setting, so this more than anything has to know when to stop. Composition is not disposed
    // when the screen goes off, and the player is often left open on the lyrics when the phone
    // goes into a pocket, so a bare LaunchedEffect here kept reading the player for as long as the
    // music played: 28,800 wakeups an hour at the slowest, over 100,000 at the default karaoke
    // speed, to highlight lines nobody could see. It is gated the same way as the mini player's
    // position poll, STARTED rather than RESUMED because the lyrics are still on screen behind a
    // dialog and should keep moving there.
    //
    // Paused, nothing moves, but the old loop still woke on every tick only to find that out and
    // go round again. It now waits on isPlaying instead, which costs nothing until playback
    // resumes. The .value check keeps that wait off the hot path, since first() subscribes to the
    // flow every time it is called. The tap-to-seek handler below writes currentLineIndex and
    // currentPos itself, so a tap while paused still moves the highlight at once.
    //
    // The read now comes before the delay rather than after it, so coming back from either of
    // those catches up at once: turning the screen back on shows the line being sung, not the one
    // that was playing when it went off. Reading straight away is safe because the effect above
    // that fills lines is keyed on the same lyricsModel, is launched first, and never suspends,
    // so lines is already full by the time this runs. That ordering matters: findCurrentLineIndex
    // throws on an empty list, and the old loop never had to care because it slept first.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lyricsModel, lifecycleOwner) {
        if (lyricsModel == null || !isSynced || (lyricsModel as SemanticLyrics.SyncedLyrics).text.isEmpty()) {
            currentLineIndex = -1
            return@LaunchedEffect
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (!playerConnection.isPlaying.value) playerConnection.isPlaying.first { it }
                val sliderPosition = sliderPositionProvider()
                isSeeking = sliderPosition != null
                currentLineIndex = findCurrentLineIndex(lines, sliderPosition ?: playerConnection.player.currentPosition)
                currentPos = sliderPosition ?: playerConnection.player.currentPosition
                delay(lyricRefreshRate)
            }
        }
    }

    LaunchedEffect(isSeeking, lastPreviewTime) {
        if (isSeeking) {
            lastPreviewTime = 0L
        } else if (lastPreviewTime != 0L) {
            delay(LyricsPreviewTime)
            lastPreviewTime = 0L
        }
    }

    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /**
     * Brings line [index] up from under the picking bar, where a long press near the foot of a
     * short view leaves it (a phone on its side, large text), so that the line just picked is seen.
     */
    fun keepClearOfBar(index: Int) {
        val layout = lazyListState.layoutInfo
        val line = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val lineFoot = line.offset + line.size - layout.viewportStartOffset
        val barTop = layout.viewportEndOffset - layout.viewportStartOffset -
            with(density) { (PickBarRoom + bottomPadding).toPx() }
        if (lineFoot > barTop) scope.launch { lazyListState.animateScrollBy(lineFoot - barTop) }
    }

    // A finger resting on the lines: the view does not start scrolling to the song's line under
    // it, which would take the line being pressed away from under the finger. Its scrolling is
    // also a glide that leaves touches alone (glideToItem), so that in a quick part of a song (a
    // line a second) a long press on a line starts picking, where one in four was lost.
    var fingerDown by remember { mutableStateOf(false) }
    // With "Follow the song" off, the lines are placed on the song's line once, when they come up,
    // and from then on stay where the listener leaves them.
    var placedOnce by remember(lines) { mutableStateOf(false) }

    LaunchedEffect(currentLineIndex, lastPreviewTime, picking, fingerDown, lyricsAutoScroll) {
        /**
         * Count number of new lines in a lyric
         */
        fun countNewLine(str: String) = str.count { it == '\n' }

        /**
         * Calculate the lyric offset Based on how many lines (\n chars)
         */
        fun calculateOffset() = with(density) {
            if (landscapeOffset) {
                16.dp.toPx().toInt() * countNewLine(lines[currentLineIndex].text) // landscape sits higher by default
            } else {
                20.dp.toPx().toInt() * countNewLine(lines[currentLineIndex].text)
            }
        }

        // Nothing moves under a finger, or while lines are picked. Once the finger is lifted, or
        // the picking ends, the song's line is scrolled to again.
        if (!isSynced || picking || fingerDown) return@LaunchedEffect
        if (!lyricsAutoScroll && placedOnce) return@LaunchedEffect
        if (currentLineIndex != -1) {
            placedOnce = true
            deferredCurrentLineIndex = currentLineIndex
            if (lastPreviewTime == 0L) {
                if (isSeeking) {
                    lazyListState.scrollToItem(
                        currentLineIndex,
                        with(density) { 36.dp.toPx().toInt() } + calculateOffset())
                } else {
                    lazyListState.glideToItem(
                        currentLineIndex,
                        with(density) { 36.dp.toPx().toInt() } + calculateOffset())
                }
            }
        } else if (lastPreviewTime == 0L) {
            // Before the first line, as after a seek back into the song's opening: the view goes
            // back to where the first line will be sung, with no line lit, and follows the song
            // from there. It used to stay on the line it had last shown (reported 18 Oct 2026).
            deferredCurrentLineIndex = -1
            if (isSeeking) {
                lazyListState.scrollToItem(0, with(density) { 36.dp.toPx().toInt() })
            } else {
                lazyListState.glideToItem(0, with(density) { 36.dp.toPx().toInt() })
            }
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp)
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = WindowInsets.systemBars
                .only(WindowInsetsSides.Top)
                .add(WindowInsets(top = maxHeight / 2, bottom = maxHeight / 2))
                .asPaddingValues(),
            modifier = Modifier
                .fadingEdge(vertical = lyricsFadeLength(maxHeight, smallWindow))
                // Only watched, on the way in, and left for the lines and the list to act on.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        fingerDown = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            fingerDown = false
                        }
                    }
                }
                .nestedScroll(remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            lastPreviewTime = System.currentTimeMillis()
                            return super.onPostScroll(consumed, available, source)
                        }

                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                            lastPreviewTime = System.currentTimeMillis()
                            return super.onPostFling(consumed, available)
                        }
                    }
                })
        ) {
            val displayedCurrentLineIndex = if (isSeeking) deferredCurrentLineIndex else currentLineIndex

            if (lyricsModel == null) {
                item {
                    ShimmerHost {
                        repeat(10) {
                            Box(
                                contentAlignment = when (lyricsTextPosition) {
                                    LyricsPosition.LEFT -> Alignment.CenterStart
                                    LyricsPosition.CENTER -> Alignment.Center
                                    LyricsPosition.RIGHT -> Alignment.CenterEnd
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 4.dp)
                            ) {
                                TextPlaceholder()
                            }
                        }
                    }
                }
            } else if (lyricsModel != uninitializedLyric) {
                val maxW = maxWidth - 48.dp
                itemsIndexed(
                    items = lines
                ) { index, item ->
                    var lyricFontSizeAdjusted = lyricsFontSize
                    if (item.speaker?.isBackground == true) {
                        lyricFontSizeAdjusted = (lyricFontSizeAdjusted * 0.75).toInt()
                    }
                    if (item.isTranslated) {
                        lyricFontSizeAdjusted = (lyricFontSizeAdjusted * 0.75).toInt()
                    }
                    // Its Latin version goes under it at the size of a translation's line.
                    val itemLatin = remember(item.text, item.isTranslated, latinLines) {
                        if (item.isTranslated) null else latinLines?.let { LyricsLatin.under(item.text, it) }
                    }
                    val latinSize = (lyricFontSizeAdjusted * 0.75).toInt()

                    val isPicked = picking && index in picked
                    // Unsynced lines touch, so picked ones side by side are lit as one block.
                    val joinsAbove = isPicked && !isSynced && (index - 1) in picked
                    val joinsBelow = isPicked && !isSynced && (index + 1) in picked
                    val seeks = isSynced && lyricsClickable
                    Column(
                        horizontalAlignment = when (lyricsTextPosition) {
                            LyricsPosition.LEFT -> Alignment.Start
                            LyricsPosition.CENTER -> Alignment.CenterHorizontally
                            LyricsPosition.RIGHT -> Alignment.End
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 24.dp,
                                // Unsynced lines touch, with the room above and below them that the
                                // one block they were had.
                                top = when {
                                    !isSynced -> if (index == 0) 8.dp else 0.dp
                                    item.isTranslated -> 0.dp
                                    else -> 8.dp
                                },
                                end = 24.dp,
                                bottom = when {
                                    !isSynced -> if (index == lines.lastIndex) 8.dp else 0.dp
                                    item.isTranslated -> 16.dp
                                    else -> 8.dp
                                },
                            )
                            // A picked line is lit from behind, out past its edges, so that
                            // nothing moves when it is picked.
                            .drawBehind {
                                if (isPicked) {
                                    val sideways = 12.dp.toPx()
                                    val spill = (if (isSynced) 4.dp else 2.dp).toPx()
                                    val round = CornerRadius(12.dp.toPx())
                                    val top = if (joinsAbove) CornerRadius.Zero else round
                                    val bottom = if (joinsBelow) CornerRadius.Zero else round
                                    drawPath(
                                        Path().apply {
                                            addRoundRect(
                                                RoundRect(
                                                    left = -sideways,
                                                    top = if (joinsAbove) 0f else -spill,
                                                    right = size.width + sideways,
                                                    bottom = size.height + if (joinsBelow) 0f else spill,
                                                    topLeftCornerRadius = top,
                                                    topRightCornerRadius = top,
                                                    bottomRightCornerRadius = bottom,
                                                    bottomLeftCornerRadius = bottom,
                                                )
                                            )
                                        },
                                        color = pickedColor,
                                    )
                                }
                            }
                            // we allow clicking on blank lyrics, ignore item.isClickable
                            // A long press picks the line to share, on lyrics of any kind; while
                            // picking a tap does too, and does not seek.
                            .combinedClickable(
                                interactionSource = null,
                                indication = if (picking || seeks) lineIndication else null,
                                onLongClick = {
                                    if (!picking) {
                                        picked.clear()
                                        picking = true
                                    }
                                    pick(index)
                                    keepClearOfBar(index)
                                },
                                onClick = {
                                    if (picking) {
                                        pick(index)
                                    } else if (seeks) {
                                        playerConnection.player.seekTo(item.start.toLong())
                                        currentLineIndex = index
                                        currentPos = item.start.toLong()
                                        lastPreviewTime = 0L
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                },
                            )
                    ) {
                        if (currentPos.toULong() in item.start..item.end + 100.toULong() && lyricsFancy
                            && item.words != null && !context.isPowerSaver() && !picking
                        ) { // word by word
                            // now do eye bleach to make lyric line babies
                            val style = LocalTextStyle.current.copy(
                                fontSize = lyricsFontSize.sp,
                                color = textColor,
                                fontWeight = FontWeight.Bold
                            )
                            val rawSplitLines = splitTextToLines(item.text, style, maxW)
                            val lyricLines = ArrayList<LyricLine>()
                            if (rawSplitLines.size > 1) {
                                var from = 0
                                for (i in rawSplitLines) {
                                    // Two throws lived here, and the coerce on the old subList
                                    // call only looked like it covered them.
                                    //
                                    // `from = to` took the uncoerced value, so once the wrapped
                                    // lines held more words than the timing data does, from walked
                                    // past words.size and the next subList threw fromIndex > size.
                                    // Word counts disagree easily: splitTextToLines wraps on
                                    // rendered width, and one timed word can carry punctuation or
                                    // an elision that the split counts as two.
                                    //
                                    // And when the coerce did bite, it produced an empty slice,
                                    // whose first() and last() then threw NoSuchElementException.
                                    // A line with no timing left is still a line worth showing, so
                                    // it keeps the parent's range rather than being dropped.
                                    val to = (from + i.split(' ').size).coerceAtMost(item.words.size)
                                    val words: MutableList<SemanticLyrics.Word> =
                                        if (from < to) item.words.subList(from, to)
                                        else mutableListOf()
                                    lyricLines.add(
                                        if (words.isEmpty()) {
                                            item.copy(text = i, words = mutableListOf())
                                        } else {
                                            item.copy(
                                                text = i,
                                                start = words.first().timeRange.start,
                                                end = words.last().timeRange.endInclusive,
                                                words = words
                                            )
                                        }
                                    )
                                    from = to
                                }
                            } else {
                                lyricLines.add(item)
                            }


                            lyricLines.forEach {
                                HorizontalReveal(
                                    progress = calculateLineProgress(it, currentPos),
                                    modifier = Modifier
                                ) {
                                    Text(
                                        text = it.text,
                                        fontSize = lyricFontSizeAdjusted.sp,
                                        color = textColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            // The words are timed on the line itself; its Latin version is shown whole while it is sung.
                            itemLatin?.let { pairs ->
                                Text(
                                    text = pairs.mapNotNull { it.second }.joinToString("\n"),
                                    fontSize = latinSize.sp,
                                    color = textColor,
                                    textAlign = when (lyricsTextPosition) {
                                        LyricsPosition.LEFT -> TextAlign.Left
                                        LyricsPosition.CENTER -> TextAlign.Center
                                        LyricsPosition.RIGHT -> TextAlign.Right
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }


                        } else { // regular
                            val isConsumed = currentPos.toULong() > (item.end + 100.toULong())
                            val isHighlighted =
                                ((index == displayedCurrentLineIndex || (index == displayedCurrentLineIndex + 1 && item.isTranslated)))
                            Text(
                                text = remember(item.text, itemLatin, latinSize) { withLatin(item.text, itemLatin, latinSize) },
                                fontSize = lyricFontSizeAdjusted.sp,
                                color = if (isConsumed && !isHighlighted) prevTextColor else textColor,
                                textAlign = when (lyricsTextPosition) {
                                    LyricsPosition.LEFT -> TextAlign.Left
                                    LyricsPosition.CENTER -> TextAlign.Center
                                    LyricsPosition.RIGHT -> TextAlign.Right
                                },
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.alpha(
                                    if (picking) {
                                        // The picked lines stand out from the rest, whichever is being sung.
                                        if (isPicked) 1f else 0.45f
                                    } else if (!isSynced || isHighlighted) {
                                        1f
                                    } else if (isConsumed) {
                                        0.6f
                                    } else {
                                        0.5f
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }

        if (lyricsModel == uninitializedLyric) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (onNoLyricsClick != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onNoLyricsClick
                            )
                        } else Modifier
                    )
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    fontSize = lyricsFontSize.sp,
                    color = textColor,
                    textAlign = when (lyricsTextPosition) {
                        LyricsPosition.LEFT -> TextAlign.Left
                        LyricsPosition.CENTER -> TextAlign.Center
                        LyricsPosition.RIGHT -> TextAlign.Right
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }

        mediaMetadata?.let { mediaMetadata ->
            // While lines are picked the view's own two buttons give way to the bar that shares
            // them: neither closing the lyrics nor their menu is what anybody picking lines is after.
            if (picking) LyricsPickBar(
                picked = picked.size,
                videoMs = pickedVideoMs,
                onCancel = ::stopPicking,
                onShare = {
                    cardSong = mediaMetadata
                    cardVideo = videoOf(picked)
                    cardLines = LyricsShare.pickedLines(lines.map { it.text }, picked)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = bottomPadding)
            ) else Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = bottomPadding)
            ) {
                IconButton(
                    onClick = { onShowLyricsChange(false) }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.toggle_lyrics),
                        tint = textColor
                    )
                }
                IconButton(
                    onClick = {
                        menuState.show {
                            LyricsMenu(
                                lyricsProvider = {
                                    withContext(Dispatchers.IO) {
                                        var dbLyric = playerConnection.service.database.lyrics(mediaMetadata.id).first()

                                        // eye bleach to try to load local file for editor
                                        if (dbLyric == null && mediaMetadata.localPath != null) {
                                            LrcUtils.loadLyricsFile(File(mediaMetadata.localPath))?.let {
                                                dbLyric = LyricsEntity(mediaMetadata.id, it)
                                            }
                                        }

                                        dbLyric
                                    }
                                },
                                mediaMetadataProvider = { mediaMetadata },
                                onRefreshRequest = { lyricsModel = it },
                                onDismiss = menuState::dismiss,
                                shownLines = {
                                    lines.map { it.text }.takeIf { lyricsModel != null && lyricsModel != uninitializedLyric }
                                },
                                onPickLines = {
                                    picked.clear()
                                    picking = true
                                },
                                picksForVideo = isSynced,
                            )
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.options),
                        tint = textColor
                    )
                }
            }

            cardLines?.let { chosen ->
                LyricsCardDialog(
                    lines = chosen,
                    song = cardSong ?: mediaMetadata,
                    video = cardVideo,
                    onDismiss = {
                        cardLines = null
                        cardVideo = null
                    },
                    onShared = {
                        cardLines = null
                        cardVideo = null
                        stopPicking()
                    },
                )
            }
        }
    }

    // Back stops the picking before it closes anything. Composed only while picking, which makes it
    // the newest, so it comes before the player sheet's own.
    if (picking) BackHandler(onBack = ::stopPicking)
}

@Composable
fun HorizontalReveal(
    progress: Float,
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 0.5f,
    rtl: Boolean = false,
    content: @Composable () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 100, easing = LinearEasing)
    )

    Box(modifier = modifier.padding(start = 1.dp)) {
        Box(modifier = Modifier.alpha(backgroundAlpha)) {
            content()
        }

        Box(
            modifier = Modifier
                .graphicsLayer {
                    clip = true
                    shape = RectangleShape
                }
                .drawWithContent {
                    val clipWidth = size.width * animatedProgress
                    val left = if (!rtl) 0f else size.width - clipWidth
                    val right = if (!rtl) clipWidth else size.width
                    clipRect(left, 0f, right, size.height) {
                        this@drawWithContent.drawContent()
                    }
                }
        ) {
            content()
        }
    }
}

@Composable
fun splitTextToLines(
    text: String,
    style: TextStyle,
    maxWidth: Dp
): List<String> {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var currentLine = ""

    for (word in words) {
        val tentativeLine = if (currentLine.isEmpty()) word else "$currentLine $word"
        val tentativeWidth = with(density) {
            textMeasurer.measure(
                tentativeLine, style,
            ).size.width.toDp()
        }

        if (tentativeWidth < maxWidth) {
            currentLine = tentativeLine
        } else {
            lines.add(currentLine)
            currentLine = word
        }
    }

    if (currentLine.isNotEmpty()) {
        lines.add(currentLine)
    }

    return lines
}

/**
 * [text] as the lyrics view shows it: as it is, or with each of its lines' Latin version under it
 * in [latin] (LyricsLatin.under), smaller, as a translation's line is.
 */
private fun withLatin(text: String, latin: List<Pair<String, String?>>?, latinSize: Int): AnnotatedString {
    if (latin == null) return AnnotatedString(text)
    return buildAnnotatedString {
        latin.forEachIndexed { i, (line, inLatin) ->
            if (i > 0) append('\n')
            append(line)
            if (inLatin != null) {
                append('\n')
                withStyle(SpanStyle(fontSize = latinSize.sp)) { append(inLatin) }
            }
        }
    }
}

/**
 * Get current position in lyric line list
 */
fun findCurrentLineIndex(lines: List<LyricLine>, position: Long): Int {
    for (index in lines.indices) {
        if (lines[index].start > (position).toUInt()) {
            return if (index > 0 && lines[index - 1].isTranslated) index - 2 else index - 1
        }
    }
    return if (lines[lines.lastIndex].isTranslated) lines.lastIndex - 1 else lines.lastIndex
}

/**
 * Get current position in lyric line. Used for word by word lyrics
 */
fun calculateLineProgress(line: LyricLine, currentPositionMs: Long): Float {
    val words = line.words
    val startMs = line.start.toLong()
    val endMs = line.end.toLong()

    // by line if no words are available
    if (words.isNullOrEmpty()) {
        return when {
            currentPositionMs < startMs -> 0f
            currentPositionMs > endMs -> 1f // add buffer so lyric line animation completes
            else -> (currentPositionMs - startMs).toFloat() / (endMs - startMs).toFloat()
        }
    }

    // progress based on words
    val currentMs = currentPositionMs.toULong()
    var completedWords = 0
    var partialProgress = 0f

    return when {
        currentPositionMs < startMs -> 0f
        currentPositionMs > endMs -> 1f // add buffer so lyric line animation completes
        else -> {
            for (i in words.indices) {
                val word = words[i]
                val start = word.timeRange.first
                val end = word.timeRange.last

                if (currentMs < start) {
                    break // we're before this word
                } else if (currentMs in word.timeRange) {
                    val wordDuration = (end - start).coerceAtLeast(1u).toFloat()
                    partialProgress = (currentMs - start).toFloat() / wordDuration
                    completedWords = i
                    break
                } else {
                    completedWords++
                }
            }

            val totalWords = words.size.toFloat()
            var progress = (completedWords + partialProgress) / totalWords
            progress.coerceIn(0f, 1f)
        }
    }
}

const val animateScrollDuration = 300L

/** What the picking bar takes at the foot of the lyrics view: its own height and a little room over it. */
private val PickBarRoom = 64.dp
val LyricsPreviewTime = 7.seconds

/**
 * The lines being picked to share, and the picture or video of them waiting to be sent, with the
 * lyrics they were picked in: kept outside the lyrics view, which the player makes anew when it
 * turns between its upright and its sideways layout, so that a turn of the phone keeps them.
 */
class LyricsPicking : ViewModel() {
    var picking by mutableStateOf(false)
    val picked = mutableStateListOf<Int>()
    var pickedIn: SemanticLyrics? = null
    var cardLines by mutableStateOf<List<String>?>(null)
    var cardVideo by mutableStateOf<LyricsVideoPlan.Clip?>(null)
    var cardSong by mutableStateOf<MediaMetadata?>(null)
}

/**
 * Scrolls to item [index], [offset] pixels past its top, on the spring animateScrollToItem uses,
 * but by handing the list each step itself. A list that scrolls under its own power takes the
 * next touch on it for a drag that stops the scroll, and the line under the finger never hears of
 * it; one moved this way is never scrolling, so a touch is a touch on what it lands on. An item
 * that is not laid out is jumped to.
 */
private suspend fun LazyListState.glideToItem(index: Int, offset: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index, offset)
        return
    }
    var moved = 0f
    animate(0f, (item.offset + offset).toFloat(), animationSpec = spring()) { value, _ ->
        moved += dispatchRawDelta(value - moved)
    }
}

/**
 * How far the lyrics fade out at the top and bottom of a view [height] tall.
 *
 * 64dp. In the small player ([smallWindow]: a keypad phone or a floating window) never more than
 * a quarter of the height: in the 100dp or so it has for lyrics, two full fades met in the middle
 * and left every line faded out. Everywhere else it stays 64dp whatever the height, since an
 * ordinary phone's lyrics view can be as short as 200dp with some player settings.
 */
internal fun lyricsFadeLength(height: Dp, smallWindow: Boolean): Dp =
    if (smallWindow) minOf(64.dp, height / 4) else 64.dp

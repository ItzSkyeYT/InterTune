/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapNote

/**
 * The note to next year's you as the story holds it (NOTE_WRITE, his add of 22 Nov 2026): kept by
 * the story rather than by its page, so a slide back and on again keeps what was typed, and [text]
 * a turn of the phone too. [sealedDay]: this year's note is sealed, written on that day.
 */
@Stable
internal class NoteDraft(text: MutableState<String>) {
    var text by text
    var focused by mutableStateOf(false)
    var sealedDay by mutableStateOf<Long?>(null)

    /** Writing it again, to take the place of the one sealed. */
    var again by mutableStateOf(false)
    var sealing by mutableStateOf(false)

    /** The last seal did not take: the slide says why, and Seal it is the way to try again. */
    var failed by mutableStateOf(false)

    /** When it was sealed, on the story's clock: its seal is pressed on then. -1 for one found sealed. */
    var sealedAt by mutableLongStateOf(-1L)

    /** The paper shows the note sealed rather than a field to write in. */
    val shut: Boolean get() = sealedDay != null && !again

    /** The story waits while the note is written: the field has the keyboard or holds words, or they are being sealed. */
    val writing: Boolean get() = sealing || (!shut && (focused || text.isNotEmpty()))

    fun sealed(day: Long, at: Long) {
        sealedDay = day
        sealedAt = at
        again = false
        failed = false
        text = ""
    }
}

/** How long the seal shows, on the story's clock, before the thank-you page comes. */
internal const val NOTE_SEALED_MS = 1_800L

/**
 * The note's hand: the phone's own face in italic, on lines [size] × 30/19 apart as the design's
 * 19 on 30, each line's words centred on it so the paper's rules can be drawn under them.
 */
internal fun noteHand(size: TextUnit, colour: Color = RecapInk.Ink) = TextStyle(
    fontSize = size,
    lineHeight = size * (30f / 19f),
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Medium,
    color = colour,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** A long note is written a little smaller, so that all of it fits on its paper: the design's 19 sp up to 180 letters, 15 at 500. */
internal fun noteSize(length: Int): Float = if (length <= 180) 19f else (19f - (length - 180) / 320f * 4f).coerceAtLeast(15f)

/** What is written under the note on its paper, quieter, on the next of its lines. */
private fun noteSmall(line: TextUnit) = TextStyle(
    fontSize = 13.sp,
    lineHeight = line,
    fontWeight = FontWeight.Medium,
    color = RecapInk.Ink.copy(alpha = 0.64f),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** The space above the first words on the paper. */
private val PAPER_TOP = 26.dp

/**
 * A sheet of lined paper, taped on and a little crooked, as the design's note has it: white with
 * its grain, faint blue rules under the words' lines and on to its foot, a strip of tape across
 * its top. It flies in at step [at] from ([fy]) away, turned [fr] more, and rests turned [turn].
 * [baseline] is where the first line of words stands, from the top of what the paper holds.
 */
@Composable
internal fun NotePaper(
    s: SlideScope,
    at: Float,
    turn: Float,
    fy: Float,
    fr: Float,
    line: TextUnit,
    baseline: () -> Float?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current.density
    val grain = remember(density) { PaperGrain.brush(density) }
    val shape = RoundedCornerShape(4.dp)
    val rule = RecapInk.Blue.copy(alpha = 0.22f)
    Column(
        modifier
            .padding(top = 18.dp, start = 4.dp, end = 4.dp)
            .enter(s, Entrance.FLY, at, turn, fy = fy, fr = fr)
            .drawWithContent {
                drawContent()
                // The tape across its top edge, its own shadow under it.
                rotate(-4f, Offset(size.width / 2, 1.dp.toPx())) {
                    val corner = Offset(size.width / 2 - 46.dp.toPx(), -12.dp.toPx())
                    val tape = Size(92.dp.toPx(), 26.dp.toPx())
                    drawRect(Color.Black.copy(alpha = 0.12f), corner + Offset(0f, 1.dp.toPx()), tape)
                    drawRect(RecapInk.Cream.copy(alpha = 0.78f), corner, tape)
                }
            }
            .shadow(10.dp, shape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
            .background(RecapInk.White, shape)
            .drawBehind {
                drawRect(grain)
                val step = line.toPx()
                val thick = 1.dp.toPx()
                var y = PAPER_TOP.toPx() + (baseline() ?: (step * 0.75f)) + 3.dp.toPx()
                while (y < size.height - 6.dp.toPx()) {
                    drawRect(rule, Offset(0f, y), Size(size.width, thick))
                    y += step
                }
            }
            .padding(start = 22.dp, end = 22.dp, top = PAPER_TOP, bottom = 20.dp),
        content = content,
    )
}

/** Where the first line of words stands ([baseline] in pixels, below 0 while not laid out yet), in dp, for words written on the next line of the same paper. */
@Composable
private fun baselineOf(baseline: Float, line: TextUnit): Dp = with(LocalDensity.current) {
    (if (baseline >= 0f) baseline else line.toPx() * 0.75f).toDp()
}

// ---- last year's note, right after the first slide

/**
 * "A note from your past self": the note sealed at the end of last year's Recap (RecapNote), on
 * its paper popping in from above, with the day it was written. It stays as long as its words
 * take to read (noteReadMs), counted from when its paper has landed.
 */
@Composable
internal fun NoteReadSlide(s: SlideScope) {
    val note = s.shown.pastNote ?: return
    val c = s.paper
    val year = s.shown.recap.year
    val hand = remember(note.text) { noteHand(noteSize(note.text.length).sp) }
    // Where its first line stands: a number rather than the layout, so that laying it out again changes nothing.
    var first by remember { mutableFloatStateOf(-1f) }
    s.arrives(2.4f, RecapMotion.FLY_MS + noteReadMs(note.text).toInt())
    SlideColumn { room ->
        Kick(stringResource(R.string.recap_note_read_kick, (year - 1).toString()), s)
        val (before, after) = around(stringResource(R.string.recap_note_read_title, MARK))
        Words(before, s, at = 1f, style = RecapType.h2, colour = c.ink, strip = stringResource(R.string.recap_note_read_strip), after = after, stripAt = 1.8f, stripTurn = -3f)
        NotePaper(s, at = 2.4f, turn = 1.4f, fy = -320f, fr = -10f, line = hand.lineHeight, baseline = { first.takeIf { it >= 0f } }) {
            Text(note.text, style = hand, onTextLayout = { first = it.getLineBaseline(0) })
            Text(
                stringResource(R.string.recap_note_written_on, s.words.day(note.writtenDay)), style = noteSmall(hand.lineHeight),
                modifier = Modifier.paddingFromBaseline(top = baselineOf(first, hand.lineHeight)),
            )
        }
        Grow(room)
        Text(stringResource(R.string.recap_note_read_line), style = RecapType.quiet, color = c.ink2, modifier = Modifier.enter(s, Entrance.UP, 3.6f))
    }
}

// ---- the note to next year's you, just before the thank-you page

/**
 * "Write a note for next year's you": a field on lined paper, Seal it and Not now under it. While
 * the field has the keyboard or holds words the story waits (RecapStage.writing), and Back, or a
 * tap beside the paper, closes the keyboard first. A note sealed already shows sealed, with Write
 * it again. Sealing is the story's to do ([onSeal]): off the main thread, then the seal is pressed
 * on and the thank-you page comes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteWriteSlide(s: SlideScope, note: NoteDraft, onSeal: () -> Unit, onNotNow: () -> Unit) {
    val c = s.paper
    val year = s.shown.recap.year
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focusedHere by remember { mutableStateOf(false) }
    var first by remember { mutableFloatStateOf(-1f) }
    val hand = remember { noteHand(19.sp) }

    // The keyboard closed (Back closes it first): the field is let go of too, so the next Back leaves the Recap as ever.
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard) { if (!keyboard && note.focused) focusManager.clearFocus() }
    BackHandler(enabled = note.focused) { focusManager.clearFocus() }
    // A page that goes while its field has the keyboard leaves no field behind it thought to have it.
    DisposableEffect(Unit) { onDispose { if (focusedHere) note.focused = false } }
    LaunchedEffect(note.again) { if (note.again) runCatching { focus.requestFocus() } }

    SlideColumn { room ->
        Kick(stringResource(R.string.recap_note_write_kick), s)
        val (before, after) = around(stringResource(R.string.recap_note_write_title, MARK))
        Words(before, s, at = 1f, style = RecapType.h2, colour = c.ink, strip = stringResource(R.string.recap_note_write_strip), after = after, stripAt = 1.8f, stripTurn = -3f)
        NotePaper(
            s, at = 2.4f, turn = -1.6f, fy = 300f, fr = 8f, line = hand.lineHeight, baseline = { first.takeIf { it >= 0f } },
            // A tap anywhere on the paper is a tap on the field, never on the story behind it.
            modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { if (!note.shut) runCatching { focus.requestFocus() } },
        ) {
            if (note.shut) {
                Sealed(s, note, hand, onBaseline = { first = it })
            } else {
                Box {
                    BasicTextField(
                        value = note.text,
                        onValueChange = {
                            note.text = it.take(RecapNote.MAX_CHARS)
                            note.failed = false
                        },
                        textStyle = hand,
                        cursorBrush = SolidColor(RecapInk.Blue),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 4,
                        onTextLayout = { first = it.getLineBaseline(0) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged {
                                focusedHere = it.isFocused
                                note.focused = it.isFocused
                            },
                        decorationBox = { field ->
                            Box {
                                if (note.text.isEmpty()) Text(stringResource(R.string.recap_note_placeholder, (year + 1).toString()), style = noteHand(19.sp, RecapInk.Ink.copy(alpha = 0.42f)))
                                field()
                            }
                        },
                    )
                    // A quiet count near the end of what a note holds.
                    val left = RecapNote.MAX_CHARS - note.text.length
                    if (left <= 100) {
                        Text(
                            pluralStringResource(R.plurals.recap_note_left, left, s.words.number(left)), style = RecapType.quiet.copy(fontSize = 12.sp),
                            color = RecapInk.Ink.copy(alpha = 0.64f), modifier = Modifier.align(Alignment.BottomEnd).offset(y = 16.dp),
                        )
                    }
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp).enter(s, Entrance.UP, 3.4f),
        ) {
            if (note.shut) {
                NoteButton(stringResource(R.string.recap_note_again), filled = false) { note.again = true }
            } else {
                NoteButton(stringResource(R.string.recap_note_seal), filled = true, lock = true, dim = note.text.isBlank() || note.sealing) {
                    if (note.text.isBlank()) runCatching { focus.requestFocus() } else onSeal()
                }
                NoteButton(stringResource(R.string.recap_note_not_now), filled = false, onClick = onNotNow)
            }
        }
        val day = note.sealedDay
        Text(
            when {
                note.failed -> stringResource(R.string.recap_note_failed)
                note.shut -> stringResource(R.string.recap_note_sealed_line)
                note.again && day != null -> stringResource(R.string.recap_note_again_line, s.words.day(day))
                else -> stringResource(R.string.recap_note_write_line)
            },
            style = RecapType.quiet, color = if (note.failed) c.ink else c.ink2, modifier = Modifier.enter(s, Entrance.UP, 3.8f),
        )
        Grow(room)
    }
}

/**
 * The note sealed: in its place on the paper, "Sealed until your next Recap" and the day it was
 * written, and a red seal with a lock pressed on beside them as it is sealed.
 */
@Composable
private fun Sealed(s: SlideScope, note: NoteDraft, hand: TextStyle, onBaseline: (Float) -> Unit) {
    var first by remember { mutableFloatStateOf(-1f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.recap_note_sealed), style = hand,
                onTextLayout = {
                    first = it.getLineBaseline(0)
                    onBaseline(first)
                },
            )
            note.sealedDay?.let { day ->
                Text(
                    stringResource(R.string.recap_note_written_on, s.words.day(day)), style = noteSmall(hand.lineHeight),
                    modifier = Modifier.paddingFromBaseline(top = baselineOf(first, hand.lineHeight)),
                )
            }
            Spacer(Modifier.height(with(LocalDensity.current) { hand.lineHeight.toDp() }))
        }
        Spacer(Modifier.width(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    rotationZ = -10f
                    val since = if (note.sealedAt < 0) Float.MAX_VALUE else (s.stage.clock.now - note.sealedAt).toFloat()
                    if (s.still) {
                        alpha = RecapMotion.Ease.transform((since / RecapMotion.CALM_FADE_MS).coerceIn(0f, 1f))
                    } else {
                        // Pressed on: from large and faint to its size, a touch past it and back.
                        val e = RecapMotion.Settle.transform((since / RecapMotion.POP_MS).coerceIn(0f, 1f))
                        alpha = (since / 160f).coerceIn(0f, 1f)
                        scaleX = 1.8f - 0.8f * e
                        scaleY = scaleX
                        rotationZ = -10f - 30f * (1f - e)
                    }
                }
                .size(58.dp)
                .shadow(6.dp, CircleShape, ambientColor = RecapInk.Shade, spotColor = RecapInk.Shade)
                .background(RecapRed, CircleShape)
                .border(3.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(Icons.Rounded.Lock, null, tint = RecapInk.Cream, modifier = Modifier.size(26.dp))
        }
    }
}

/** One of the note's two buttons: ink with cream words, or the ink's outline on the paper. */
@Composable
private fun NoteButton(text: String, filled: Boolean, lock: Boolean = false, dim: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(23.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .height(46.dp)
            .graphicsLayer { alpha = if (dim) 0.55f else 1f }
            .clip(shape)
            .then(if (filled) Modifier.background(RecapInk.Ink) else Modifier.background(RecapInk.Ink.copy(alpha = 0.14f)).border(2.dp, RecapInk.Ink, shape))
            .recapClick(onClick)
            .padding(start = if (lock) 18.dp else 22.dp, end = 22.dp),
    ) {
        if (lock) Icon(Icons.Rounded.Lock, null, tint = RecapInk.Cream, modifier = Modifier.size(20.dp))
        Text(text, style = RecapType.button, color = if (filled) RecapInk.Cream else RecapInk.Ink, maxLines = 1, softWrap = false)
    }
}

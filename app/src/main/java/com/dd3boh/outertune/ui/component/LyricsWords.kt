/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.node.Ref
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import com.dd3boh.outertune.playback.MusicLevels
import org.akanework.gramophone.logic.utils.SemanticLyrics
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Word by word lyrics: the line being sung is lit where each word stands, from the text as it was
 * laid out (the same layout as before the line was sung, so nothing moves as it starts), each word
 * from its first letter to its last over the time it is sung.
 *
 * The app's own way, with no look picked (every build but the debug app, and the debug app until a
 * look is picked), does that with the hard edge it always had. Until #165 (13 Nov 2026) it lit each
 * row from its start up to a share of its width counted in words, the same share for every word
 * whatever its length, and handed the words to the rows by counting spaces: paused between two
 * words, the lit part stood inside a word not sung yet or short of the end of one sung, and a word
 * timed as two syllables shifted the rest of the line and lost its last word.
 *
 * Better word by word lyrics, as looks to choose from (his line on the Roadmap for 0.13; #94, #145),
 * the debug app's (Unreleased.LYRICS_WORDS_LOOKS): the same words in the same places, with a soft
 * edge, and:
 *
 * - [A], on the word: that, and nothing moves.
 * - [B], lifted: each word rises a little as it is sung and stays up until the line is over; a word
 *   held long swells and glows while it is held.
 * - [C], carried: the line being sung at full size and the others a little smaller, the lines under
 *   it following one after the other when the view moves on, and each word nodding as it is sung,
 *   by as much as the music is strong at that moment (the levels the Living blur reads). Nothing
 *   beats by itself: a word moves once, when it is sung.
 */
enum class LyricsWordsLook(val label: String) {
    A("A: on the word"),
    B("B: lifted"),
    C("C: carried");

    /** What a trial asks for; null where it says nothing of it. [look] as it is stored, "" for the app's own. */
    data class Trial(val look: String?, val wordByWord: Boolean?)

    companion object {
        /** The look stored under LyricsWordsLookKey, or null for the app's own way. */
        fun of(stored: String?): LyricsWordsLook? = entries.firstOrNull { it.name == stored }

        /**
         * The extra a command line sets a look with, for pictures and recordings of each with the
         * lyrics left open: `am start -n <debug app>/com.dd3boh.outertune.MainActivity --es lyricswords b,words`.
         */
        const val TRIAL = "lyricswords"

        /**
         * The trial in [words], apart by commas, in any order and case: today, a, b or c; words or
         * lines, for the word by word switch of the lyrics settings. A word it does not know is
         * passed over. Null where no trial was asked for.
         */
        fun trial(words: String?): Trial? {
            if (words == null) return null
            var look: String? = null
            var wordByWord: Boolean? = null
            for (word in words.split(',').map { it.trim().uppercase() }) {
                when {
                    word == "TODAY" -> look = ""
                    entries.any { it.name == word } -> look = word
                    word == "WORDS" -> wordByWord = true
                    word == "LINES" -> wordByWord = false
                }
            }
            return Trial(look, wordByWord)
        }
    }
}

/** The looks' numbers and what they work out, with no Android in them. */
internal object LyricsWords {
    /** How much ink the part of the line not sung yet has, as the app's own way gives it. */
    const val UNSUNG = 0.5f

    /** How wide the soft edge of the lit part is, as a share of the lyrics' font size. */
    const val SOFT_EM = 0.5f

    /** [LyricsWordsLook.B] and [LyricsWordsLook.C]: how far a word rises, as a share of the font size. */
    const val LIFT_EM = 0.12f

    /** B: a word is up this long after it starts being sung. */
    const val RISE_MS = 240L

    /** B: the words come back down in this long once the line is over, the time the view keeps a line lit after its end. */
    const val SETTLE_MS = 100L

    /** B: a word sung for at least this long is held: it swells and glows while it is. */
    const val HELD_MS = 1100L

    /** B: how much larger a held word is at its fullest, where it has the room. */
    const val SWELL = 0.07f

    /**
     * B: the share of the room between a held word and the word beside it that its swell may
     * take. Seen on the emulator on 12 Nov 2026: a long word swollen by all of [SWELL] closed the
     * space before it, and read as one word with its neighbour.
     */
    const val SWELL_ROOM = 0.3f

    /** B: a held word's glow, its reach as a share of the font size and its ink at its fullest. */
    const val GLOW_EM = 0.4f
    const val GLOW = 0.55f

    /** C: a word's nod, up in this long and back down in that. */
    const val NOD_UP_MS = 110L
    const val NOD_DOWN_MS = 420L

    /** C: how far a word nods when the music is at its quietest, as a share of the nod at its loudest. */
    const val NOD_LEAST = 0.3f

    /** C: the size of the lines that are not being sung, and how long a line takes to change size. */
    const val AROUND = 0.94f
    const val AROUND_MS = 320

    /** C: each line under the one being sung follows this much later than the one above it, for this many lines. */
    const val STEP_MS = 45L
    const val STEPS = 5

    /**
     * A word of the line, or a syllable where the lyrics time those: its letters [from] up to [to]
     * in the line's text, and when it is sung.
     */
    data class Piece(val from: Int, val to: Int, val startMs: Long, val endMs: Long, val rtl: Boolean)

    /**
     * The words of a line with their times, in the order they stand in [text], each within it.
     * One the text does not hold, or that starts inside the one before, is left out; none when the
     * line has no word times.
     */
    fun pieces(text: String, words: List<SemanticLyrics.Word>?): List<Piece> {
        if (words.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Piece>(words.size)
        var taken = 0
        for (word in words.sortedBy { it.charRange.first }) {
            val from = word.charRange.first
            val to = (word.charRange.last + 1).coerceAtMost(text.length)
            if (from < taken || to <= from) continue
            val start = word.timeRange.first.toLong()
            out += Piece(from, to, start, word.timeRange.last.toLong().coerceAtLeast(start), word.isRtl)
            taken = to
        }
        return out
    }

    /** How much of [piece] is sung at [atMs], from none to all of it, evenly over its time. */
    fun sung(piece: Piece, atMs: Long): Float = when {
        atMs <= piece.startMs -> 0f
        atMs >= piece.endMs -> 1f
        else -> (atMs - piece.startMs).toFloat() / (piece.endMs - piece.startMs).toFloat()
    }

    /**
     * B: how far up [piece] is at [atMs], 0 to 1: up within [RISE_MS] of its start, slowing as it
     * arrives, there until the line ends at [lineEndMs], and down in the [SETTLE_MS] after.
     */
    fun rise(piece: Piece, atMs: Long, lineEndMs: Long): Float {
        if (atMs <= piece.startMs) return 0f
        val up = easeOut(((atMs - piece.startMs).toFloat() / RISE_MS).coerceIn(0f, 1f))
        val down = ((lineEndMs + SETTLE_MS - atMs).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
        return up * down
    }

    /** C: the nod of [piece] at [atMs], 0 to 1 and back: up in [NOD_UP_MS] from its start, down in [NOD_DOWN_MS]. */
    fun nod(piece: Piece, atMs: Long): Float {
        val since = atMs - piece.startMs
        return when {
            since <= 0 || since >= NOD_UP_MS + NOD_DOWN_MS -> 0f
            since < NOD_UP_MS -> easeOut(since.toFloat() / NOD_UP_MS)
            else -> 1f - easeInOut((since - NOD_UP_MS).toFloat() / NOD_DOWN_MS)
        }
    }

    /** Whether [piece] is held: sung for [HELD_MS] or more. */
    fun held(piece: Piece): Boolean = piece.endMs - piece.startMs >= HELD_MS

    /** B: how full a held word's swell and glow are at [atMs], 0 to 1 and back to 0 over the time it is held. */
    fun swell(piece: Piece, atMs: Long): Float {
        if (!held(piece) || atMs <= piece.startMs || atMs >= piece.endMs) return 0f
        return sin(PI * (atMs - piece.startMs) / (piece.endMs - piece.startMs)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * B: the size a held word is drawn at, 1 for as it stands, when its swell is [swell] full: up
     * to [SWELL] larger, grown from its middle, and never by more on a side than [SWELL_ROOM] of
     * the space to the word there ([spaceBefore], [spaceAfter], in the pixels [width] is in).
     */
    fun swollen(swell: Float, width: Float, spaceBefore: Float, spaceAfter: Float): Float {
        if (swell <= 0f || width <= 0f) return 1f
        val room = 2f * SWELL_ROOM * min(spaceBefore, spaceAfter).coerceAtLeast(0f) / width
        return 1f + min(SWELL, room) * swell.coerceAtMost(1f)
    }

    /**
     * Where the lit part ends in a word that stands from [left] to [right] and is [sung] that far:
     * the far end of a soft edge [soft] wide, which starts on the word's first letter with nothing
     * lit and has left its last letter when all of it is. From the right for a word read that way.
     */
    fun edge(left: Float, right: Float, sung: Float, soft: Float, rtl: Boolean): Float {
        val reach = (right - left + soft) * sung.coerceIn(0f, 1f)
        return if (rtl) right - reach else left + reach
    }

    /**
     * The app's own way: what of [box] is lit when [sung] of its word is sung, from where to where
     * across, or null for none of it. All of it once the word is sung; while it is, from the side
     * it is read from up to a hard edge that crosses its letters evenly over its time. Between two
     * words nothing of the next one is lit until it starts.
     */
    fun lit(box: WordBox, sung: Float, rtl: Boolean): ClosedFloatingPointRange<Float>? {
        val p = box.share(sung)
        if (p <= 0f) return null
        if (p >= 1f) return box.clipLeft..box.clipRight
        val edge = edge(box.left, box.right, p, 0f, rtl)
        return if (rtl) edge..box.clipRight else box.clipLeft..edge
    }

    /** A stretch of a row the app's own way lights in one go: [from] to [to] across, [top] to [bottom] down. */
    data class Stretch(val row: Int, val from: Float, val to: Float, val top: Float, val bottom: Float)

    /**
     * The app's own way: what of the line is lit at once, each word's lit part ([lit]) with those that touch on a
     * row joined, so that a row is drawn lit in one go up to the word being sung. [boxes] in the order [place]
     * gives them, [sung] how much of each of [pieces] is sung.
     */
    fun litStretches(boxes: List<WordBox>, pieces: List<Piece>, sung: FloatArray): List<Stretch> {
        val out = ArrayList<Stretch>(2)
        for (box in boxes) {
            val part = lit(box, sung[box.piece], pieces[box.piece].rtl) ?: continue
            val last = out.lastOrNull()
            if (last != null && last.row == box.row && part.start <= last.to) {
                out[out.lastIndex] = Stretch(
                    box.row, min(last.from, part.start), max(last.to, part.endInclusive), min(last.top, box.clipTop), max(last.bottom, box.clipBottom),
                )
            } else {
                out += Stretch(box.row, part.start, part.endInclusive, box.clipTop, box.clipBottom)
            }
        }
        return out
    }

    /**
     * Where each of [pieces] stands in the line laid out in [rows]: a box for each row a word is on
     * (a word too long for a row goes on over two), in the order of the rows and across each, drawn
     * out to half way to the words beside it and [past] beyond the row's own ends. The rows are read
     * from where the letters were laid out, never from the words counted, so a word timed as two
     * syllables is two boxes side by side, and the line's last word is on the row it stands on. Text
     * with no time of its own goes with the word nearest it, and a row with none is one box.
     */
    fun place(pieces: List<Piece>, rows: WordRows, past: Float): List<WordBox> {
        val length = rows.length
        val out = ArrayList<WordBox>(pieces.size + 2)
        val covered = BooleanArray(rows.count)
        for ((index, piece) in pieces.withIndex()) {
            if (piece.from >= length) continue
            val to = piece.to.coerceAtMost(length)
            val firstRow = rows.rowOf(piece.from)
            val lastRow = rows.rowOf((to - 1).coerceAtLeast(piece.from))
            val mine = ArrayList<WordBox>(1)
            for (row in firstRow..lastRow) {
                val a = max(piece.from, rows.start(row))
                val b = min(to, rows.visibleEnd(row))
                if (b <= a) continue
                // Where its own letters stand, whichever way they are read: never worked out from the way the row is
                // read, which can be the other one (19 Nov 2026: Compose lays a line out the app's way, so in an
                // English app an Arabic line is a row read from the left whose letters run from the right).
                val across = rows.across(a, b)
                if (across.endInclusive <= across.start) continue
                mine += WordBox(index, row, across.start, across.endInclusive, rows.top(row), rows.bottom(row))
                covered[row] = true
            }
            val width = mine.sumOf { (it.right - it.left).toDouble() }.toFloat().coerceAtLeast(1f)
            var before = 0f
            for (box in mine) {
                box.before = before
                box.part = (box.right - box.left) / width
                before += box.part
            }
            out += mine
        }
        // A row no word stands on (text the lyrics give no time for) is drawn whole and lit whole: a row before the
        // first word as that word starts, any other once the word before it is sung. The words are in the order they
        // are read, so the word before a row is the last of them on a row above it; the rows added here are not
        // words, and are never taken for one.
        val words = out.toList()
        val firstRow = words.minOfOrNull { it.row }
        for (row in covered.indices) {
            if (covered[row] || firstRow == null) continue
            val early = row < firstRow
            val with = if (early) words.first() else words.last { it.row < row }
            out += WordBox(with.piece, row, rows.left(row), rows.right(row), rows.top(row), rows.bottom(row)).apply {
                // All of it at once: a share of the word too small to be seen being sung.
                before = if (early) 0f else 0.999f
                part = 0.001f
            }
        }
        out.sortWith(compareBy<WordBox> { it.row }.thenBy { it.left })
        for ((i, box) in out.withIndex()) {
            val leftOf = out.getOrNull(i - 1)?.takeIf { it.row == box.row }
            val rightOf = out.getOrNull(i + 1)?.takeIf { it.row == box.row }
            // Out to half way to the word beside it, and at a row's ends out past the row's own end: text there with
            // no time of its own is drawn and lit with the word nearest it, never cut off.
            box.clipLeft = if (leftOf == null) min(box.left, rows.left(box.row)) - past else (leftOf.right + box.left) / 2f
            box.clipRight = if (rightOf == null) max(box.right, rows.right(box.row)) + past else (box.right + rightOf.left) / 2f
            // Above the first row and under the last nothing else is drawn: tall letters and tails keep their ends.
            if (box.row == 0) box.clipTop = box.top - past
            if (box.row == rows.count - 1) box.clipBottom = box.bottom + past
        }
        return out
    }

    /**
     * C: how far a word nods for the music's [levels] as it starts (MusicLevels' values), as a
     * share of the full nod: the stronger of the bass and the middle, as much as the passage
     * counts in its song, never under [NOD_LEAST].
     */
    fun weight(levels: FloatArray): Float {
        val loud = max(levels[MusicLevels.BASS], levels[MusicLevels.MID]) * levels[MusicLevels.PRESENCE]
        return NOD_LEAST + (1f - NOD_LEAST) * loud.coerceIn(0f, 1f)
    }

    /**
     * C: the nod's weight for each word that has just started and has none yet, written into
     * [weights] (under 0: none yet). [level] gives the music's levels now, or null when there are
     * none to go by (nothing measured, audio that cannot be read): the word then does not nod.
     */
    fun weigh(pieces: List<Piece>, weights: FloatArray, atMs: Long, level: () -> FloatArray?) {
        for (i in pieces.indices) {
            if (i >= weights.size || weights[i] >= 0f) continue
            val since = atMs - pieces[i].startMs
            if (since < 0) continue
            // Come upon late, after a seek into the line: it is not made to nod for a moment that is past.
            weights[i] = if (since > NOD_UP_MS + NOD_DOWN_MS) 0f else level()?.let(::weight) ?: 0f
        }
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)
}

/**
 * C: how far the view has moved on, kept for a moment, so that each line under the one being sung
 * can follow a little later than the one above it ([LyricsWords.STEP_MS] each): a line is held back
 * by what the view moved in that time.
 */
internal class LyricsCarry {
    private val at = LongArray(SIZE)
    private val px = FloatArray(SIZE)
    private var count = 0
    private var next = 0
    private var total = 0f

    /** The frame the lines are placed for. Read where they are placed, so that a move places them again. */
    var frame by mutableLongStateOf(0L)
        private set

    /** The view moved [by] pixels further at [now]; 0 to place the lines again with nothing moved. */
    fun moved(now: Long, by: Float) {
        // The first move after a rest was made in the frame before it, not over the whole rest:
        // where the view stood until then is kept first.
        if (count == 0 || now - at[(next - 1 + SIZE) % SIZE] > REST_NANOS) keep(now - FRAME_NANOS)
        total += by
        keep(now)
        frame = now
    }

    private fun keep(time: Long) {
        at[next] = time
        px[next] = total
        next = (next + 1) % SIZE
        if (count < SIZE) count++
    }

    /** How far a line [steps] under the one being sung is held back at [now], in pixels, never upwards. */
    fun lag(steps: Int, now: Long): Float {
        if (steps <= 0 || count == 0) return 0f
        val then = now - steps.coerceAtMost(LyricsWords.STEPS) * LyricsWords.STEP_MS * 1_000_000L
        return (total - movedBy(then)).coerceAtLeast(0f)
    }

    /** Whether a line is still held back at [now]: the view moved within the longest delay. */
    fun busy(now: Long): Boolean = count > 0 && lag(LyricsWords.STEPS, now) > 0.5f

    /** How far the view had moved by [time]: between the two readings around it, or the oldest kept. */
    private fun movedBy(time: Long): Float {
        var later = -1
        for (back in 1..count) {
            val i = (next - back + SIZE) % SIZE
            if (at[i] <= time) {
                if (later < 0) return px[i]
                val span = (at[later] - at[i]).coerceAtLeast(1L)
                return px[i] + (px[later] - px[i]) * ((time - at[i]).toFloat() / span)
            }
            later = i
        }
        return px[later]
    }

    private companion object {
        const val SIZE = 96
        const val FRAME_NANOS = 16_000_000L
        const val REST_NANOS = 50_000_000L
    }
}

/** Where a word stands in the line as laid out: one of these for each row it is on. */
internal class WordBox(
    val piece: Int,
    val row: Int,
    /** From its first letter to its last. */
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
) {
    /** What is drawn as this word: out to half way to its neighbours, and past the line's ends. */
    var clipLeft = left
    var clipRight = right
    var clipTop = top
    var clipBottom = bottom

    /** The share of the word that stands before this box, and the share that is in it. */
    var before = 0f
    var part = 1f

    /**
     * How much of this box is sung when [sung] of its word is, from none to all of it: none before the word
     * starts and all once it is sung, whatever the rounding of [before] and [part].
     */
    fun share(sung: Float): Float = when {
        sung <= 0f -> 0f
        sung >= 1f -> 1f
        else -> ((sung - before) / part).coerceIn(0f, 1f)
    }
}

/**
 * What the placing of the words (LyricsWords.place) reads of the line as it was laid out, row by
 * row: the layout's own answers on a screen (LaidOutRows), made-up ones in a test.
 */
internal interface WordRows {
    /** How many letters the text has, and how many rows it was laid out on. */
    val length: Int
    val count: Int

    fun rowOf(offset: Int): Int
    fun start(row: Int): Int

    /** Where [row]'s letters end, the spaces at its end left out. */
    fun visibleEnd(row: Int): Int
    fun left(row: Int): Float
    fun right(row: Int): Float
    fun top(row: Int): Float
    fun bottom(row: Int): Float

    /**
     * Where the letters [from] up to [to], all on one row, stand across: from the left of the
     * leftmost to the right of the rightmost, whichever way they and the row are read.
     */
    fun across(from: Int, to: Int): ClosedFloatingPointRange<Float>
}

/** The rows of a line as Compose laid it out. */
private class LaidOutRows(private val layout: TextLayoutResult) : WordRows {
    override val length = layout.layoutInput.text.length
    override val count = layout.lineCount
    override fun rowOf(offset: Int) = layout.getLineForOffset(offset)
    override fun start(row: Int) = layout.getLineStart(row)
    override fun visibleEnd(row: Int) = layout.getLineEnd(row, visibleEnd = true)
    override fun left(row: Int) = layout.getLineLeft(row)
    override fun right(row: Int) = layout.getLineRight(row)
    override fun top(row: Int) = layout.getLineTop(row)
    override fun bottom(row: Int) = layout.getLineBottom(row)

    // Each letter's own box: Compose places it by the letter's way and the row's (Layout.isRtlCharAt), and a letter at a
    // row's end by that row, never the next.
    override fun across(from: Int, to: Int): ClosedFloatingPointRange<Float> {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (offset in from until to) {
            val box = layout.getBoundingBox(offset)
            left = min(left, box.left)
            right = max(right, box.right)
        }
        return if (left <= right) left..right else 0f..0f
    }
}

/**
 * The line being sung, drawn the app's own way or as a [LyricsWordsLook] says: what is worked out
 * once for the line as it was laid out (where each word stands), and the drawing of one frame.
 */
private class WordsDrawing(private val pieces: List<LyricsWords.Piece>, private val measurer: TextMeasurer) {
    private var laidOut: TextLayoutResult? = null
    private var boxes: List<WordBox> = emptyList()
    private val alone = HashMap<Int, TextLayoutResult?>()
    private val sung = FloatArray(pieces.size)
    private val up = FloatArray(pieces.size)
    private val full = FloatArray(pieces.size)

    /** Half the ink: the app's own way draws the part not sung yet through it, the whole line as one layer. */
    private val halfInk = Paint().apply { alpha = LyricsWords.UNSUNG }

    private fun boxesOf(layout: TextLayoutResult): List<WordBox> {
        if (layout === laidOut) return boxes
        laidOut = layout
        alone.clear()
        boxes = LyricsWords.place(pieces, LaidOutRows(layout), emOf(layout))
        return boxes
    }

    /** The lyrics' font size in pixels, as the line was laid out; a line's height where it has none of its own. */
    private fun emOf(layout: TextLayoutResult): Float {
        val size = layout.layoutInput.style.fontSize
        return if (size.isSp) with(layout.layoutInput.density) { size.toPx() } else layout.getLineBottom(0) - layout.getLineTop(0)
    }

    /** The word [piece] by itself, laid out as the line is, for its glow; null for one on two rows. */
    private fun aloneOf(piece: Int, layout: TextLayoutResult): TextLayoutResult? = alone.getOrPut(piece) {
        val word = pieces[piece]
        val text = layout.layoutInput.text
        if (word.from >= text.length || boxes.count { it.piece == piece } != 1) return@getOrPut null
        runCatching {
            measurer.measure(
                text = text.subSequence(word.from, word.to.coerceAtMost(text.length)),
                style = layout.layoutInput.style.copy(textAlign = TextAlign.Start),
                softWrap = false,
                maxLines = 1,
            )
        }.getOrNull()
    }

    /** One frame of the line at [atMs] in the lyrics: the app's own way with no [look], else as it says. */
    fun DrawScope.drawWords(layout: TextLayoutResult, look: LyricsWordsLook?, color: Color, atMs: Long, lineEndMs: Long, weights: FloatArray?) {
        val all = boxesOf(layout)
        if (all.isEmpty()) {
            drawText(textLayoutResult = layout, color = color, shadow = Shadow.None)
            return
        }
        val em = emOf(layout)
        val soft = em * LyricsWords.SOFT_EM
        for (i in pieces.indices) {
            val piece = pieces[i]
            sung[i] = LyricsWords.sung(piece, atMs)
            up[i] = when (look) {
                null, LyricsWordsLook.A -> 0f
                LyricsWordsLook.B -> LyricsWords.rise(piece, atMs, lineEndMs)
                LyricsWordsLook.C -> LyricsWords.nod(piece, atMs) * (weights?.getOrNull(i)?.coerceAtLeast(0f) ?: 0f)
            }
            full[i] = if (look == LyricsWordsLook.B) LyricsWords.swell(piece, atMs) else 0f
        }
        if (look == null) {
            // The app's own way, drawn as the line always was (HorizontalReveal): all of it at half its ink through
            // one layer, so that letters which overlap, as in scripts whose letters join, are no darker where they
            // do; then what is sung over it at full ink, up to a hard edge, a stretch of a row at a time.
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(-em, -em, size.width + em, size.height + em), halfInk)
                drawText(textLayoutResult = layout, color = color, shadow = Shadow.None)
                canvas.restore()
            }
            for (lit in LyricsWords.litStretches(all, pieces, sung)) {
                clipRect(lit.from, lit.top, lit.to, lit.bottom) {
                    drawText(textLayoutResult = layout, color = color, shadow = Shadow.None)
                }
            }
            return
        }
        // The looks being compared: each word drawn on its own, as it moves on its own.
        val unsung = color.copy(alpha = color.alpha * LyricsWords.UNSUNG)
        var i = 0
        while (i < all.size) {
            val box = all[i]
            val p = box.share(sung[box.piece])
            val dy = -up[box.piece] * LyricsWords.LIFT_EM * em
            val swell = full[box.piece]
            // Words side by side that look the same, all sung or none of it and nothing of their
            // own moving, are drawn as one.
            var last = i
            if ((p <= 0f || p >= 1f) && swell == 0f) {
                while (last + 1 < all.size) {
                    val beside = all[last + 1]
                    if (beside.row != box.row || full[beside.piece] != 0f || up[beside.piece] != up[box.piece]) break
                    if (beside.share(sung[beside.piece]) != p) break
                    last++
                }
            }
            val left = min(box.clipLeft, all[last].clipLeft)
            val right = max(box.clipRight, all[last].clipRight)
            val centre = Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
            // The space to the words beside it: the clip's edges stand half way to them.
            val size = LyricsWords.swollen(swell, box.right - box.left, 2f * (box.left - box.clipLeft), 2f * (box.clipRight - box.right))
            withTransform({
                if (dy != 0f) translate(0f, dy)
                if (size != 1f) scale(size, size, centre)
            }) {
                if (swell > 0f) aloneOf(box.piece, layout)?.let { word ->
                    // Its glow: the word's own shadow, the word itself left as good as clear (as LyricsBlur draws).
                    drawText(
                        textLayoutResult = word,
                        color = color.copy(alpha = LyricsBlur.TEXT_INK),
                        topLeft = Offset(box.left, layout.getLineBaseline(box.row) - word.firstBaseline),
                        shadow = Shadow(color.copy(alpha = (color.alpha * LyricsWords.GLOW * swell).coerceAtMost(LyricsBlur.MOST_INK)), Offset.Zero, LyricsWords.GLOW_EM * em),
                    )
                }
                clipRect(left, box.clipTop, right, box.clipBottom) {
                    if (p >= 1f) {
                        drawText(textLayoutResult = layout, color = color, shadow = Shadow.None)
                    } else {
                        drawText(textLayoutResult = layout, color = unsung, shadow = Shadow.None)
                        if (p > 0f) {
                            val rtl = pieces[box.piece].rtl
                            val edge = LyricsWords.edge(box.left, box.right, p, soft, rtl)
                            // To the same colour with no ink: towards clear black the edge would darken on its way.
                            val clear = color.copy(alpha = 0f)
                            val lit = if (rtl) {
                                Brush.horizontalGradient(listOf(clear, color), startX = edge, endX = edge + soft)
                            } else {
                                Brush.horizontalGradient(listOf(color, clear), startX = edge - soft, endX = edge)
                            }
                            drawText(textLayoutResult = layout, brush = lit, alpha = 1f, shadow = Shadow.None)
                        }
                    }
                }
            }
            i = last + 1
        }
    }
}

/**
 * The line being sung, lit word by word the app's own way, or as [look] says when one is picked.
 * The same text, size and wrapping as the line had before it was sung, so that it stays where it
 * was; [at] gives where the song is in the lyrics at the frame being drawn, and is read only as
 * the line draws. [weights] is C's: how far each word nods (LyricsWords.weigh).
 */
@Composable
internal fun LyricsWordsLine(
    look: LyricsWordsLook?,
    text: String,
    pieces: List<LyricsWords.Piece>,
    lineEndMs: Long,
    fontSize: TextUnit,
    color: Color,
    textAlign: TextAlign,
    at: () -> Long,
    weights: FloatArray?,
    modifier: Modifier = Modifier,
) {
    val laidOut = remember { Ref<TextLayoutResult>() }
    val measurer = rememberTextMeasurer()
    val drawing = remember(pieces, measurer) { WordsDrawing(pieces, measurer) }
    Text(
        text = text,
        fontSize = fontSize,
        color = color,
        textAlign = textAlign,
        fontWeight = FontWeight.Bold,
        onTextLayout = { result -> laidOut.value = result },
        modifier = modifier.drawWithContent {
            val layout = laidOut.value
            if (layout == null) drawContent() else with(drawing) { drawWords(layout, look, color, at(), lineEndMs, weights) }
        },
    )
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package org.akanework.gramophone.logic.utils

import org.akanework.gramophone.logic.utils.SemanticLyrics.SyncedLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word's end time is normally the next word's own mark minus 1ms. When that next mark is
 * exactly zero, the subtraction underflowed ULong instead of going negative, handing back a
 * range of about 18 quintillion milliseconds instead of an end before the start. The size check
 * right after (end must come after the start) let that huge range through, since it is, in fact,
 * after the start.
 *
 * That range then went into a word-duration estimate for the next word, by way of
 * ULongRange.count(). Iterable<T>.count() has no arithmetic fast path for this or any other
 * range, so it stepped through the range one millisecond at a time. A couple of billion steps
 * later the Int it was counting into overflowed, which is the only reason this ever threw rather
 * than hanging outright: kotlin.collections.count() checks for exactly that and throws
 * ArithmeticException once the counter goes negative.
 *
 * A mark of zero is not a typing accident: an [offset:] large enough coerces several word marks
 * down to zero together (offset moves marks earlier, and marks cannot go below zero), which is
 * exactly the second case below. The same line's own closing mark can end up at zero the same
 * way, which is what the third case checks.
 */
class SemanticLyricsWordTimingTest {

    @Test(timeout = 2000)
    fun `a word whose next mark is exactly zero does not underflow or hang`() {
        val lyrics = parseLrc(
            "[00:01.00]<00:01.00>a <00:00.00>b c",
            trimEnabled = false,
            multiLineEnabled = false
        )

        assertTrue(lyrics is SyncedLyrics)
        val words = (lyrics as SyncedLyrics).text.single().words!!
        assertTrue("expected both words, got $words", words.size == 2)
        words.forEach {
            val spanMs = (it.timeRange.last - it.timeRange.first).toLong()
            assertTrue("word spanned an implausible $spanMs ms: $it", spanMs in 0..10_000)
        }
    }

    @Test(timeout = 2000)
    fun `marks coerced to zero by a large offset do not underflow or hang`() {
        val lyrics = parseLrc(
            "[offset:3000]\n[00:01.00]<00:01.00>a <00:02.00>b",
            trimEnabled = false,
            multiLineEnabled = false
        )

        assertTrue(lyrics is SyncedLyrics)
        val line = (lyrics as SyncedLyrics).text.single()
        val words = line.words!!
        assertTrue("expected both words, got $words", words.size == 2)
        words.forEach {
            val spanMs = (it.timeRange.last - it.timeRange.first).toLong()
            assertTrue("word spanned an implausible $spanMs ms: $it", spanMs in 0..10_000)
        }
        // The line's own closing mark (lastWordSyncPoint) is coerced to zero here too, same as
        // the words above, so it cannot be used as end - 1ms either (zero start, zero mark:
        // straight underflow, wrapping to ULong.MAX_VALUE; Lyrics.kt then adds a small buffer to
        // that, which itself wraps back around to a small number). It is left at the 0uL sentinel
        // instead and backfilled from the last word's own end, same as a line with no closing
        // mark at all would be.
        assertEquals(words.last().timeRange.last, line.end)
        assertTrue("line end an implausible ${line.end}", line.end < 10_000uL)
    }

    @Test
    fun `a next mark that is genuinely later is still used as this word's end`() {
        val lyrics = parseLrc(
            "[00:01.00]<00:01.00>a <00:02.00>b",
            trimEnabled = false,
            multiLineEnabled = false
        ) as SyncedLyrics

        val words = lyrics.text.single().words!!
        assertEquals(2, words.size)
        // "a" ends 1ms before "b" starts: exactly the next-mark-minus-1ms rule, unaffected by
        // the added guard since 2000 is genuinely later than 1000.
        assertEquals(1999uL, words[0].timeRange.last)
    }

    @Test
    fun `a word tied with the next one skips ahead to the first genuinely later mark`() {
        // "sky" and the word after it share a mark (990ms), so the next-mark rule cannot use
        // that neighbour: it would hand back an end before, or level with, sky's own start,
        // which used to mean sky was silently dropped rather than shown. It is not the line's
        // last word either, so the line's own closing mark is not a candidate for it. The fix
        // is to keep looking past tied entries for the first one that is genuinely later, here
        // the "x" at 1760ms, same as the tied word ("日本") right after it also does.
        val lyrics = parseLrc(
            "[00:01.312]<00:00.82>you <00:00.99>sky <00:00.99>日本 <00:01.76>x <00:01.915>night <00:02:28>",
            trimEnabled = false,
            multiLineEnabled = false
        ) as SyncedLyrics

        val words = lyrics.text.single().words!!
        assertEquals(5, words.size)
        val (you, sky, nihon, x, night) = words

        assertEquals(989uL, you.timeRange.last)
        assertEquals(990uL, sky.timeRange.first)
        assertEquals(1759uL, sky.timeRange.last)
        assertEquals(990uL, nihon.timeRange.first)
        assertEquals(1759uL, nihon.timeRange.last)
        assertEquals(1914uL, x.timeRange.last)
        // "night" is the line's actual last word, so it still ends on the line's own closing
        // mark (<00:02:28>, 2280ms; a colon rather than a period before the fraction is this
        // format's alternate syntax for it, see parseTime) minus 1ms, unaffected by this fix.
        assertEquals(2279uL, night.timeRange.last)
    }

    @Test
    fun `a line whose only word mark is its own start ends where its word does`() {
        // One word, marked at the line's own start, and nothing after it to close it. The line's
        // closing mark is then the same as its start, so start minus 1ms would end the line
        // before it began, as it did before the guard, and Lyrics.kt showed that as a line sung
        // for 100ms. The line is left to the backfill instead, which takes its word's own end.
        val lyrics = parseLrc(
            "[00:01.00]<00:01.00>hello",
            trimEnabled = false,
            multiLineEnabled = false
        ) as SyncedLyrics

        val line = lyrics.text.single()
        val word = line.words!!.single()
        assertEquals(1000uL, line.start)
        assertEquals(word.timeRange.last, line.end)
        assertTrue("line end ${line.end} should come after its start", line.end > line.start)
    }
}

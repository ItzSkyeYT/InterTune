/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Issue #34, the two ends of the track. A finger dragging up into a playlist's header, slowing down as
 * it nears the top or flicking and then nearly still, must never send the list down. The bottom of the
 * track must meet the end of the list, so the last pixel does not jump and a finger resting there does
 * not shake the list. All of it on a smaller phone and in landscape too, where only a few rows fit,
 * with the Albums grid's uneven lines (a title on two lines makes its line taller), an album with Other
 * versions at the end, and a last row taller than the screen. The fakes are in ScrollbarFakes.kt.
 */
class ScrollbarEndsTest {

    /** The list's height and its paddings: status bar and app bar above, player and navigation bar below. */
    private class Screen(val name: String, val topDp: Float, val bottomDp: Float, val viewportDp: Float)

    private val pixel5 = Screen("Pixel 5", 94f, 168f, 851f)
    private val small = Screen("640 dp phone", 94f, 168f, 640f)
    private val landscape = Screen("Pixel 5 in landscape", 88f, 80f, 393f)

    private fun list(sizesDp: List<Float>, on: Screen = pixel5) = FakeList(sizesDp, on.topDp, on.bottomDp, on.viewportDp)
    private fun playlist(songs: Int, headerDp: Float = 260f, on: Screen = pixel5) = list(listOf(headerDp, 48f) + List(songs) { 64f }, on)
    private fun librarySongs(songs: Int, on: Screen = pixel5) = list(listOf(56f, 48f) + List(songs) { 64f }, on)
    private fun album(songs: Int, on: Screen = pixel5) = list(listOf(330f, 48f) + List(songs) { 64f }, on)

    /** An album with Other versions at the end: their 48 dp title, then a carousel of covers. */
    private fun albumWithOtherVersions(songs: Int, on: Screen = pixel5) = list(listOf(330f, 48f) + List(songs) { 64f } + listOf(48f, 180f), on)

    /** Home: the chips, then a title and a shelf, over and over, the shelves of all sizes. */
    private fun home(on: Screen = pixel5) = Random(5).let { r ->
        list(listOf(56f) + List(40) { if (it % 2 == 0) 48f else listOf(250f, 200f, 320f, 256f)[r.nextInt(4)] }, on)
    }

    /** History: a day's 48 dp sticky heading over 1 to 25 songs, and no top padding in the list. */
    private fun history(days: Int): FakeList {
        val r = Random(days)
        val sizes = ArrayList<Float>()
        val headings = ArrayList<Int>()
        repeat(days) {
            headings += sizes.size
            sizes += 48f
            repeat(1 + r.nextInt(25)) { sizes += 64f }
        }
        return FakeList(sizes, topPaddingDp = 0f, viewportDp = 851f - 94f, headers = headings)
    }

    /**
     * Library > Albums: the filter and sort lines, then lines of three albums. Uneven, a line is 173 to
     * 209 dp tall, as a long title takes two lines and the line is as tall as its tallest; even, 230 dp.
     */
    private fun albumsGrid(lines: Int, on: Screen = pixel5, even: Boolean = false): FakeGrid {
        val r = Random(lines)
        val heights = List(lines) { if (even) 230f else listOf(173f, 189f, 193f, 209f)[r.nextInt(4)] }
        return FakeGrid(3, listOf(56f, 48f), lines * 3, { heights[it] }, on.topDp, on.bottomDp, on.viewportDp)
    }

    private fun dp(px: Double) = "%.0f".format(px / DENSITY)

    /** Every case that went wrong in a test, so one run shows all of them, not only the first. */
    private val wrong = ArrayList<String>()
    private var cases = 0

    private fun check(what: String, ok: Boolean) {
        if (!ok) wrong += what
    }

    private fun assertAllRight() {
        val failed = wrong.map { it.substringBefore(": ") }.distinct().size
        assertTrue("$failed of $cases cases went wrong:\n" + wrong.take(40).joinToString("\n"), wrong.isEmpty())
    }

    private class Run(val against: List<Double>, val apartPx: Float, val events: Int)

    /**
     * Grabs the thumb where the list shows it and moves the finger by what [finger] says for the event
     * and the thumb's top, until it says null or the thumb is against the end. A step against the finger
     * is the list going the other way by more than half a pixel. After each event the list is laid out
     * again, as on screen.
     */
    private fun drag(list: FakeScrollable, finger: (Int, Float) -> Float?): Run {
        val press = Press(list)
        val against = ArrayList<Double>()
        var apart = 0f
        var events = 0
        while (true) {
            val px = finger(events, press.top) ?: break
            val before = list.pos
            if (!press.finger(px)) break
            events++
            val step = list.pos - before
            if ((px > 0f && step < -0.5) || (px < 0f && step > 0.5)) against += step
            apart = max(apart, abs(restingTop(list) - press.top) * list.contentPx)
        }
        return Run(against, apart, events)
    }

    private fun assertFollows(what: String, run: Run) {
        cases++
        check("$what: the list never moved", run.events > 0)
        check(
            "$what: ${run.against.size} of ${run.events} steps went against the finger, the worst ${dp(run.against.maxOfOrNull { abs(it) } ?: 0.0)} dp",
            run.against.isEmpty()
        )
        check(
            "$what: the thumb drawn from the list got ${"%.1f".format(run.apartPx / DENSITY)} dp from the finger",
            run.apartPx <= 3f * DENSITY
        )
    }

    /** A finger going up 3 to 6 px an event, slowing down to a sixth of a pixel as the thumb nears the top. */
    private fun slowingUp(seed: Int): (Int, Float) -> Float? {
        val r = Random(seed)
        var speed = 3f + r.nextFloat() * 3f
        return { k, top ->
            if (top < 0.01f) speed = max(0.15f, speed * (0.6f + r.nextFloat() * 0.3f))
            if (k < 20_000) -speed else null
        }
    }

    /** A flick of 40 px, then the finger nearly still at 0.2 px an event, then a pixel, over and over. */
    private fun flicks(direction: Float): (Int, Float) -> Float? {
        val pattern = listOf(40f, 0.2f, 0.2f, 0.2f, 1f)
        return { k, _ -> if (k < 20_000) direction * pattern[k % pattern.size] else null }
    }

    @Test
    fun `dragging up to the top while slowing down never sends the list down`() {
        val lists = mapOf<String, () -> FakeScrollable>(
            "playlist of 1000 under a 220 dp header" to { playlist(1000, 220f) },
            "playlist of 1000 under a 260 dp header" to { playlist(1000, 260f) },
            "playlist of 3000 under a 220 dp header" to { playlist(3000, 220f) },
            "playlist of 3000 under a 260 dp header" to { playlist(3000, 260f) },
            "album of 300" to { album(300) },
            "Library Songs of 2000" to { librarySongs(2000) },
            "Albums grid of 150 lines" to { albumsGrid(150, even = true) },
            "Albums grid of 150 uneven lines" to { albumsGrid(150) },
        )
        for ((what, make) in lists) for (start in listOf(5, 20, 100)) for (seed in 0 until 20) {
            val list = make().also { it.jumpTo(start, 0) }
            assertFollows("$what, up from row $start, finger $seed", drag(list, slowingUp(seed)))
        }
        assertAllRight()
    }

    @Test
    fun `a flick and then a finger nearly still never send the list back`() {
        // Up into a playlist's header, from the end.
        for (songs in listOf(300, 1000, 3000)) for (header in listOf(220f, 260f)) {
            val list = playlist(songs, header).also { it.pos = it.maxScroll }
            assertFollows("playlist of $songs under a $header dp header, up from the end", drag(list, flicks(-1f)))
        }
        // Down lists whose rows are not all alike, from the top and from half way.
        val down = mapOf<String, () -> FakeScrollable>(
            "playlist of 1000" to { playlist(1000) },
            "playlist of 3000 under a 220 dp header" to { playlist(3000, 220f) },
            "Library Songs of 2000" to { librarySongs(2000) },
            "History" to { history(60) },
            "Albums grid of 150 uneven lines" to { albumsGrid(150) },
            "Albums grid of 150 uneven lines on a 640 dp phone" to { albumsGrid(150, small) },
            "Albums grid of 150 uneven lines in landscape" to { albumsGrid(150, landscape) },
        )
        for ((what, make) in down) for (start in listOf(0.0, 0.5)) {
            val list = make().also { it.scrollBy((start * it.maxScroll).toFloat()) }
            assertFollows("$what, down from ${start * 100}%", drag(list, flicks(1f)))
        }
        assertAllRight()
    }

    /**
     * Drags the thumb down a pixel at a time to the bottom of the track, then holds it there with a
     * tremor of half a pixel each way. The list must be at its end, the last pixel must move it about
     * as far as the ones before it, and the tremor no further than the thumb's own half pixel accounts for.
     */
    private fun atTheBottom(what: String, list: FakeScrollable): Press {
        cases++
        val press = Press(list)
        val trail = arrayListOf(list.pos)
        while (!press.atBottom && trail.size < 1_000_000) {
            press.finger(1f)
            trail += list.pos
        }
        check("$what: the thumb never reached the bottom", press.atBottom)
        check("$what: the list is ${dp(list.maxScroll - list.pos)} dp from its end with the thumb at the bottom", abs(list.maxScroll - list.pos) <= 0.5)
        val n = trail.size
        assertTrue("$what: too short a drag to tell", n >= 12)
        // How far the list goes for a pixel of thumb just before the bottom: the most of the three
        // pixels before the last. The place is counted in rows, so a tall row goes by faster.
        val rate = (n - 4 until n - 1).maxOf { trail[it] - trail[it - 1] }
        val last = trail[n - 1] - trail[n - 2]
        check("$what: the last pixel moved the list ${dp(last)} dp, the ones before ${dp(rate)} dp", abs(last) <= 2 * rate + 2)
        var lo = list.pos
        var hi = list.pos
        repeat(200) {
            press.finger(if (it % 2 == 0) -0.5f else 0.5f)
            lo = min(lo, list.pos)
            hi = max(hi, list.pos)
        }
        check(
            "$what: a resting finger swung the list ${dp(hi - lo)} dp, where its half pixel accounts for ${dp(rate / 2)} dp",
            hi - lo <= rate + 2
        )
        return press
    }

    @Test
    fun `the bottom of the track is the end of the list, and a finger resting there keeps it still`() {
        val lists = mapOf<String, () -> FakeScrollable>(
            "Albums grid of 150 uneven lines" to { albumsGrid(150) },
            "Albums grid of 40 uneven lines" to { albumsGrid(40) },
            "Albums grid on a 640 dp phone" to { albumsGrid(150, small) },
            "Albums grid in landscape" to { albumsGrid(150, landscape) },
            "album of 15 with Other versions" to { albumWithOtherVersions(15) },
            "album of 40 with Other versions" to { albumWithOtherVersions(40) },
            "album of 15 with Other versions on a 640 dp phone" to { albumWithOtherVersions(15, small) },
            "album of 15 on a 640 dp phone" to { album(15, small) },
            "album of 300 on a 640 dp phone" to { album(300, small) },
            "Home" to { home() },
            "Home on a 640 dp phone" to { home(small) },
            "playlist of 1000" to { playlist(1000) },
            "Library Songs of 2000" to { librarySongs(2000) },
            "Library Songs in landscape" to { librarySongs(400, landscape) },
            "History" to { history(60) },
        )
        for ((what, make) in lists) for (start in listOf(0.0, 0.5)) {
            val list = make().also { it.scrollBy((start * it.maxScroll).toFloat()) }
            atTheBottom("$what, grabbed ${if (start == 0.0) "at the top" else "half way"}", list)
        }
        assertAllRight()
    }

    @Test
    fun `a last row taller than the screen is seen to its end`() {
        // Measured at the grab, so even the thumb measured afresh from the list lands under the finger.
        val measured = mapOf(
            "two 600 dp rows" to list(listOf(600f, 600f)),
            "one 1200 dp row" to list(listOf(1200f)),
            "Albums grid in landscape, its lines taller than the screen" to albumsGrid(60, landscape, even = true),
        )
        for ((what, list) in measured) {
            val press = atTheBottom(what, list)
            val apart = abs(restingTop(list) - press.top) * list.contentPx
            check("$what: let go at the bottom, the thumb jumps ${"%.1f".format(apart / DENSITY)} dp", apart <= 3f * DENSITY)
        }
        // Not laid out at the grab: the thumb was as long as for a song at the end, and measured
        // afresh it is shorter. It stays as the finger left it until the list moves.
        val unseen = list(List(40) { 64f } + listOf(700f))
        val press = atTheBottom("40 songs, then a 700 dp row", unseen)
        val letGo = ScrollbarLetGo(press.drag, unseen.snapshot())
        check("40 songs, then a 700 dp row: let go, the thumb does not stay", letGo.stillAt(unseen.snapshot(), unseen.contentPx.toFloat()) === press.drag)
        assertAllRight()
    }

    @Test
    fun `let go, the thumb stays as the finger left it until the list moves`() {
        val list = albumWithOtherVersions(15)
        val press = Press(list)
        repeat(300) { press.finger(1f) }
        val track = list.contentPx.toFloat()
        val letGo = ScrollbarLetGo(press.drag, list.snapshot())
        assertTrue("the thumb does not stay as the finger left it", letGo.stillAt(list.snapshot(), track) === press.drag)
        assertTrue("a resized track keeps the old thumb", ScrollbarLetGo(press.drag, list.snapshot()).stillAt(list.snapshot(), track + 1f) == null)
        list.scrollBy(-1f)
        assertTrue("a list that moved keeps the old thumb", letGo.stillAt(list.snapshot(), track) == null)
        list.scrollBy(1f)
        assertTrue("a list that came back to the same place brings the old thumb back", letGo.stillAt(list.snapshot(), track) == null)
    }

    @Test
    fun `on a small screen the usual row is a song or a line of albums, not a header`() {
        fun usual(what: String, list: FakeScrollable, dp: Float) {
            cases++
            val rowPx = Press(list).geometry.rowPx
            check("$what: the usual row is ${"%.0f".format(rowPx / DENSITY)} dp, not $dp", abs(rowPx - dp * DENSITY) <= DENSITY)
        }
        for (on in listOf(small, landscape)) {
            usual("Albums grid, ${on.name}", albumsGrid(150, on, even = true), 230f)
            usual("Library Songs, ${on.name}", librarySongs(400, on), 64f)
        }
        usual("album, ${small.name}", album(40, small), 64f)
        assertAllRight()
    }
}

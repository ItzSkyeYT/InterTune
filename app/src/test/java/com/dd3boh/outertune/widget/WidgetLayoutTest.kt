/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import com.dd3boh.outertune.widget.WidgetLayout.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shape of the widget at every size and in every style, decided here rather than by dragging
 * one around. The first tests are the approved mockups (experiments/features/widget-rework,
 * 25 Oct 2026) at the sizes they were drawn at; the sweep after them is every size a launcher can
 * hand out, in every style, with every setting.
 */
class WidgetLayoutTest {

    private fun plan(w: Int, h: Int, settings: WidgetSettings = WidgetSettings(), available: Int = 6, fontScale: Float = 1f, upNext: Int = 0) =
        WidgetLayout.plan(w, h, settings, available, fontScale, upNext)

    private val cover = WidgetSettings(style = WidgetStyle.COVER)
    private val full = WidgetSettings(style = WidgetStyle.FULL_COVER)
    private val shelf = WidgetSettings(style = WidgetStyle.SHELF)
    private val styles = listOf(cover, full, shelf)

    @Test
    fun `a new widget is a Cover on the song's own colours`() {
        assertEquals(WidgetStyle.COVER, WidgetSettings().style)
        assertNull("never picked", WidgetSettings().background)
        assertEquals(WidgetBackground.ARTWORK, WidgetSettings().backgroundShown)
        assertEquals(WidgetBackground.SYSTEM, shelf.backgroundShown)
        // A background picked is the one drawn, in either style that has one.
        assertEquals(WidgetBackground.DARK, shelf.copy(background = WidgetBackground.DARK).backgroundShown)
    }

    @Test
    fun `one by one is the cover with play on its corner, or in glass on Full cover`() {
        for (s in listOf(cover, shelf)) {
            val tile = plan(70, 70, s)
            assertEquals(WidgetShape.TILE, tile.shape)
            assertEquals(58, tile.coverW)
            assertTrue(tile.play)
            assertFalse(tile.below)
            assertFalse(tile.skips || tile.like)
        }
        val glass = plan(70, 70, full)
        assertEquals(WidgetShape.TILE, glass.shape)
        assertEquals(70, glass.coverW)
        assertEquals(GlassPane(18, 18, 33, 33, circle = true), glass.glass)
        // Tall and narrow, the cover keeps its shape and play goes under it.
        assertTrue(plan(70, 150).below)
        // Too small to spare a button: the tile opens the app.
        assertFalse(plan(50, 50).play)
    }

    @Test
    fun `one line keeps its cover first, then the words, then previous and next`() {
        // Two by one: the cover and play, no words.
        val two = plan(150, 50)
        assertEquals(WidgetShape.LINE, two.shape)
        assertTrue(two.coverW > 0)
        assertEquals(0, two.titleLines)
        assertFalse(two.skips)
        // Three by one: the cover, the words and play.
        val three = plan(250, 50)
        assertTrue(three.coverW > 0 && three.titleLines == 1)
        assertFalse(three.skips)
        // Four by one: the cover, the words, the heart, previous, play and next.
        val four = plan(407, 50)
        assertTrue(four.coverW > 0 && four.titleLines == 1 && four.skips && four.like)
        // Shelf is the same line on its own background: a line has no room for a shelf.
        assertEquals(four.copy(style = WidgetStyle.SHELF), plan(407, 50, shelf))
        // Full cover: the cover under it all, play in glass, and the bar on a four by one.
        assertEquals(0, plan(150, 50, full).titleLines)
        assertTrue(plan(150, 50, full).glass!!.circle)
        assertTrue(plan(250, 50, full).glass!!.circle)
        val glass = plan(407, 50, full)
        assertTrue(glass.titleLines == 1 && glass.skips && glass.like && !glass.glass!!.circle)
    }

    @Test
    fun `two by two is the cover over its title in Cover, the cover with a glass bar in Full cover, and three covers in Shelf`() {
        val a = plan(150, 150, cover)
        assertEquals(WidgetShape.TOP, a.shape)
        assertEquals(130, a.coverW)
        assertFalse("the mockup's two by two has the title alone", a.showArtist)
        assertTrue(a.skips)
        assertEquals(3, a.slots)
        assertFalse(a.like || a.shuffle)

        val b = plan(150, 150, full)
        assertEquals(WidgetShape.POSTER, b.shape)
        assertFalse(b.glass!!.circle)
        assertTrue(b.skips)
        assertFalse(b.like)
        assertEquals("centred", (150 - b.glass!!.w) / 2, b.glass!!.x)

        val c = plan(150, 150, shelf)
        assertEquals(WidgetShape.SHELF, c.shape)
        assertFalse(c.buttonsInline)
        assertEquals(3, c.columns)
        assertEquals(1, c.shelfRows)
        assertFalse(c.captions || c.heading)
    }

    @Test
    fun `two by three is a square cover over the song, a poster, and two rows of two covers with their names`() {
        val a = plan(170, 260, cover)
        assertEquals(WidgetShape.TOP, a.shape)
        assertEquals(a.coverW, a.coverH)
        assertTrue(a.showArtist)
        assertEquals(WidgetShape.POSTER, plan(170, 260, full).shape)
        val c = plan(170, 260, shelf)
        assertEquals(2, c.columns)
        assertEquals(2, c.shelfRows)
        assertTrue(c.captions)
        assertEquals(4, c.shown)
    }

    @Test
    fun `four by two is the cover beside the song, a banner with its bar at the start, and a row of four covers`() {
        val a = plan(400, 188, cover)
        assertEquals(WidgetShape.SIDE, a.shape)
        assertEquals(164, a.coverW)
        assertTrue(a.showArtist && a.skips && a.like)
        assertFalse(a.shuffle)

        val b = plan(400, 188, full)
        assertEquals(WidgetShape.BANNER, b.shape)
        assertEquals(b.padH, b.glass!!.x)
        assertTrue(b.like && b.showArtist)

        val c = plan(400, 188, shelf)
        assertEquals(WidgetShape.SHELF, c.shape)
        assertTrue(c.buttonsInline && c.like)
        assertEquals(4, c.columns)
        assertEquals(4, c.shown)
        assertFalse("no room for the heading at four by two", c.heading)
        // The Pixel's flat four by two is a line in Cover, as it was: beside, the artist would not fit.
        assertEquals(WidgetShape.LINE, plan(407, 107, cover).shape)
        assertEquals(WidgetShape.LINE, plan(407, 107, full).shape)
    }

    @Test
    fun `four by three keeps the cover beside the song, a poster, and four covers under the heading`() {
        assertEquals(WidgetShape.SIDE, plan(400, 250, cover).shape)
        assertTrue(plan(400, 250, cover).like)
        assertEquals(WidgetShape.POSTER, plan(400, 250, full).shape)
        val c = plan(400, 250, shelf)
        assertTrue(c.buttonsInline && c.heading && c.captions)
        assertEquals(4, c.columns)
    }

    @Test
    fun `four by four has shuffle in Cover and Shelf, the buttons in their own line, and three big covers on the shelf`() {
        for (h in listOf(340, 400)) {
            val a = plan(400, h, cover)
            assertEquals(WidgetShape.TOP, a.shape)
            assertEquals(5, a.slots)
            assertTrue(a.like && a.shuffle)
            val b = plan(400, h, full)
            assertEquals(WidgetShape.POSTER, b.shape)
            assertTrue(b.like)
            assertFalse("Full cover's bar has the heart, previous, play and next", b.shuffle)
            val c = plan(400, h, shelf)
            assertFalse(c.buttonsInline)
            assertTrue(c.like && c.shuffle && c.heading && c.captions)
            assertEquals(3, c.columns)
        }
    }

    @Test
    fun `a tablet puts Up next beside Cover and Full cover, and keeps Shelf's four by three shape with more covers`() {
        for ((w, h) in listOf(640 to 300, 800 to 400)) {
            val a = plan(w, h, cover, upNext = 4)
            assertEquals(WidgetShape.PANES, a.shape)
            assertEquals(4, a.upNext)
            assertTrue(a.like)
            val b = plan(w, h, full, upNext = 4)
            assertEquals(WidgetShape.PANES, b.shape)
            assertEquals(4, b.upNext)
            assertEquals(b.padH, b.glass!!.x)
            val c = plan(w, h, shelf, upNext = 4)
            assertEquals(WidgetShape.SHELF, c.shape)
            assertTrue(c.buttonsInline)
            assertEquals(6, c.columns)
        }
        assertTrue("shuffle where the cover keeps its height with it", plan(800, 400, cover, upNext = 4).shuffle)
        // Fewer in the queue, fewer rows; nothing after this song, or nothing playing, no pane.
        assertEquals(2, plan(640, 300, cover, upNext = 2).upNext)
        assertEquals(WidgetShape.SIDE, plan(640, 300, cover, upNext = 0).shape)
        // A phone's four by four is never a tablet's.
        assertEquals(WidgetShape.TOP, plan(400, 400, cover, upNext = 4).shape)
    }

    @Test
    fun `a Shelf holding a list alone is its heading and covers`() {
        val list = shelf.copy(content = WidgetContent.LIST)
        val grid = plan(400, 188, list)
        assertEquals(WidgetShape.GRID, grid.shape)
        assertTrue(grid.heading)
        assertEquals(0, plan(400, 188, list, available = 0).shown)
        assertEquals(WidgetShape.NONE, plan(400, 188, list, available = 0).shape)
        // Cover and Full cover are what is playing, whatever a Shelf was set to hold.
        assertEquals(WidgetShape.SIDE, plan(400, 188, list.copy(style = WidgetStyle.COVER)).shape)
    }

    @Test
    fun `Cover without its cover is a plain player, and the others always have theirs`() {
        val plain = plan(400, 188, cover.copy(showArtwork = false))
        assertEquals(WidgetShape.SIDE, plain.shape)
        assertEquals(0, plain.coverW)
        assertEquals(plan(400, 188, full), plan(400, 188, full.copy(showArtwork = false)))
        assertEquals(plan(400, 188, shelf), plan(400, 188, shelf.copy(showArtwork = false)))
    }

    @Test
    fun `the shelf shows full rows of covers, never more than the list holds or the ceiling allows`() {
        for (available in 0..WidgetLayout.MAX_PICKS) for (maxRows in 0..WidgetLayout.MAX_PICKS) {
            val p = plan(170, 500, shelf.copy(maxRows = maxRows), available = available)
            if (p.shape != WidgetShape.SHELF) continue
            assertTrue(p.shown <= available)
            if (maxRows > 0) assertTrue(p.shelfRows <= maxRows)
            if (p.shelfRows > 1) assertTrue("rows are full", p.shelfRows * p.columns <= available)
        }
    }

    /**
     * The one that matters: at every size a launcher can hand out, in every style and with every
     * setting that changes the arithmetic, what is planned fits, words included. A widget clips
     * silently, so this is the only place a mistake would ever be seen.
     */
    @Test
    fun `nothing is ever cut off, at any size, in any style`() {
        val variants = buildList {
            for (style in WidgetStyle.entries) for (buttons in WidgetButtons.entries) for (size in WidgetTextSize.entries) {
                add(WidgetSettings(style = style, buttons = buttons, textSize = size))
            }
            for (style in WidgetStyle.entries) {
                add(WidgetSettings(style = style, showArtist = false, showHeading = false))
                add(WidgetSettings(style = style, showLike = false))
            }
            add(WidgetSettings(style = WidgetStyle.COVER, showArtwork = false))
            add(WidgetSettings(style = WidgetStyle.COVER, showArtwork = false, buttons = WidgetButtons.PLAY_ONLY))
            for (size in WidgetTextSize.entries) add(WidgetSettings(style = WidgetStyle.SHELF, content = WidgetContent.LIST, textSize = size))
            add(WidgetSettings(style = WidgetStyle.SHELF, content = WidgetContent.LIST, maxRows = 1))
            add(WidgetSettings(style = WidgetStyle.SHELF, maxRows = 2))
        }
        val sizes = buildList {
            for (w in 40..600 step 6) for (h in 40..600 step 6) add(w to h)
            for (w in 606..960 step 18) for (h in 40..600 step 14) add(w to h)
        }
        var checked = 0
        var liked = 0
        val shapes = HashMap<Pair<WidgetStyle, WidgetShape>, Int>()
        for (settings in variants) for (fontScale in listOf(1f, 1.3f)) for (available in listOf(0, 2, 6)) {
            // Up next only counts for Cover and Full cover, and the list only for Shelf.
            val queues = if (settings.style == WidgetStyle.SHELF) listOf(0) else listOf(0, 1, 4)
            if (settings.style != WidgetStyle.SHELF && available != 6) continue
            for (upNext in queues) for ((w, h) in sizes) {
                val p = plan(w, h, settings, available, fontScale, upNext)
                checked++
                shapes.merge(p.style to p.shape, 1, Int::plus)
                // Built only on a failure: millions of these otherwise.
                fun ok(fits: Boolean, what: String) {
                    if (!fits) fail("$what: $settings, font $fontScale, $available songs, $upNext next, at ${w}x$h: $p")
                }
                ok(p.style == settings.style, "the style")
                // The heart is only ever added: the same widget without it is the same in every
                // other respect, so nothing that was there moves, shrinks or goes for it.
                val bare = plan(w, h, settings.copy(showLike = false), available, fontScale, upNext)
                val unliked = p.copy(like = false, glass = if (p.like) bare.glass else p.glass)
                ok(bare == unliked, "the heart changed something besides itself")
                if (p.like) {
                    liked++
                    ok(p.skips, "a heart without previous and next")
                    ok(settings.showLike && settings.buttons == WidgetButtons.ALL, "a heart not asked for")
                    // Full cover's glass grows by the heart and its gap, and by nothing else.
                    if (p.glass != null) {
                        val was = bare.glass
                        ok(was != null && p.glass.w == was.w + p.button + p.gap && p.glass.y == was.y && p.glass.h == was.h, "the glass grew by more than the heart")
                    }
                }
                checkFits(p, w, h, settings, fontScale, available, ::ok)
                ok(WidgetArt.bytes(p, 2.75f) <= WidgetArt.BUDGET_BYTES, "too many bitmap bytes for the launcher")
            }
        }
        assertTrue(checked > 1_000_000)
        assertTrue("the heart was never drawn", liked > 100_000)
        // Every style reaches every shape it has.
        val expected = mapOf(
            WidgetStyle.COVER to setOf(WidgetShape.TILE, WidgetShape.LINE, WidgetShape.SIDE, WidgetShape.TOP, WidgetShape.PANES),
            WidgetStyle.FULL_COVER to setOf(WidgetShape.TILE, WidgetShape.LINE, WidgetShape.POSTER, WidgetShape.BANNER, WidgetShape.PANES),
            WidgetStyle.SHELF to setOf(WidgetShape.TILE, WidgetShape.LINE, WidgetShape.SHELF, WidgetShape.GRID, WidgetShape.NONE),
        )
        for ((style, wanted) in expected) {
            val seen = shapes.keys.filter { it.first == style }.map { it.second }.toSet()
            assertEquals("$style's shapes", wanted, seen)
        }
    }

    /** What each shape draws fits the widget: the same sums the drawing makes, done the other way round. */
    private fun checkFits(p: WidgetPlan, w: Int, h: Int, s: WidgetSettings, fontScale: Float, available: Int, ok: (Boolean, String) -> Unit) {
        val scale = s.textSize.scale * fontScale
        val t = line(p.titleSp, scale)
        val a = line(p.artistSp, scale)
        val words = (if (p.titleLines > 0) p.titleLines * t else 0) + (if (p.showArtist) a else 0)
        val controlsH = if (p.play) maxOf(p.button, p.playSize) else 0
        ok(p.padH >= 0 && p.padV >= 0, "a negative padding")
        ok(!p.shuffle || p.skips, "shuffle without previous and next")
        p.glass?.let { g ->
            ok(g.x >= 0 && g.y >= 0 && g.x + g.w <= w && g.y + g.h <= h, "the glass out of the widget")
            ok(s.style == WidgetStyle.FULL_COVER, "glass outside Full cover")
            if (!g.circle) ok(WidgetLayout.rowWidth(p) + 2 * WidgetLayout.glassPadH(p) <= g.w, "the buttons wider than their glass")
            ok(g.h >= p.button, "the buttons taller than their glass")
            val (pw, ph) = WidgetGlass.pixels(g.w, g.h, 2.75f)
            ok(pw * ph <= WidgetGlass.MAX_PX, "the glass's bitmap too big")
        }
        if (s.style == WidgetStyle.FULL_COVER && p.play && p.shape != WidgetShape.NONE) ok(p.glass != null, "Full cover's buttons out of their glass")
        when (p.shape) {
            WidgetShape.NONE -> ok(p.shown == 0, "a heading with covers")
            WidgetShape.TILE -> {
                if (s.style == WidgetStyle.FULL_COVER) return
                if (p.below) ok(2 * p.padV.coerceAtLeast(0) + p.coverH + (if (p.coverH > 0) 6 else 0) + p.playSize <= h, "tile, play under")
                ok(p.coverW + 2 * p.padH <= w && p.coverH + 2 * p.padV <= h, "tile's cover")
                if (p.play) ok(p.playSize <= minOf(w, h) - 2 * WidgetLayout.TILE_PAD_DP, "tile's play")
            }
            WidgetShape.LINE -> {
                ok(words <= h - 2 * p.padV, "a line's words")
                ok(controlsH <= h - 2 * p.padV, "a line's buttons")
                // Full cover's cover is the whole widget; the others' sits in the line.
                if (s.style == WidgetStyle.FULL_COVER) ok(p.coverW == w && p.coverH == h, "Full cover is the whole widget")
                else ok(p.coverH <= h - 2 * p.padV, "a line's cover")
                if (s.style == WidgetStyle.FULL_COVER) {
                    if (p.titleLines > 0) ok(WidgetLayout.lineWords(p, w) >= WidgetLayout.WORDS_MIN_DP, "a line's words squeezed")
                } else if (p.titleLines > 0) {
                    ok(WidgetLayout.lineWords(p, w) >= WidgetLayout.WORDS_MIN_DP, "a line's words squeezed")
                } else {
                    ok(2 * p.padH + (if (p.coverW > 0) p.coverW + 6 else 0) + WidgetLayout.rowWidth(p) <= w, "a line's cover and play")
                }
            }
            WidgetShape.SIDE -> {
                val column = WidgetLayout.columnWidth(p, w)
                val colH = if (p.coverW > 0) p.coverH else h - 2 * p.padV
                ok(column >= WidgetLayout.rowWidth(p), "the buttons wider than the column")
                ok(column >= WidgetLayout.COLUMN_MIN_DP, "the column too narrow")
                ok(words + (if (p.play) 8 + controlsH else 0) <= colH, "the column's words and buttons")
                ok(colH + 2 * p.padV <= h, "side too tall")
                ok(p.coverW == p.coverH, "a cover that is not square")
            }
            WidgetShape.TOP -> {
                ok(p.coverW + 2 * p.padH <= w, "top's cover too wide")
                ok(p.coverH <= p.coverW, "top's cover taller than wide")
                ok(2 * p.padV + p.coverH + 8 + words + (if (p.play) 6 + controlsH else 0) <= h, "top too tall")
                ok(WidgetLayout.spreadMin(p) <= w - 2 * p.padH, "top's buttons too wide")
                ok(p.gap >= (if (p.slots > 1) WidgetLayout.SPREAD_MIN_DP else 0), "top's buttons too close")
            }
            WidgetShape.POSTER, WidgetShape.BANNER -> {
                ok(p.coverW == w && p.coverH == h, "Full cover is the whole widget")
                // The room between the words and the glass is only there with words.
                val bar = p.glass?.h?.plus(if (p.titleLines > 0) 8 else 0) ?: 0
                ok(2 * p.padV + words + bar <= h, "the words and the glass too tall")
            }
            WidgetShape.SHELF, WidgetShape.GRID -> {
                val caption = WidgetLayout.captionHeight(scale)
                val grid = p.shelfRows * (p.tile + if (p.captions) caption else 0) + (p.shelfRows - 1) * p.shelfGap
                val gridW = p.columns * p.tile + (p.columns - 1) * p.shelfGap
                ok(gridW + 2 * p.padH <= w, "the shelf too wide")
                ok(p.tile >= WidgetLayout.SHELF_MIN_DP, "covers too small")
                ok(p.shown in 1..available && p.shown <= p.columns * p.shelfRows, "covers the list does not have")
                ok(p.shelfRows <= WidgetLayout.MAX_SHELF_ROWS, "too many rows")
                if (p.shape == WidgetShape.SHELF) {
                    val top = p.headerHeight + (if (p.buttonsInline) 10 else (if (p.play) 6 + controlsH else 0) + 8)
                    ok(2 * p.padV + top + p.headingHeight + grid <= h, "the shelf too tall")
                    ok(t + (if (p.showArtist) a else 0) <= p.headerHeight, "the shelf's line of song")
                    ok(p.headerCover <= p.headerHeight, "the shelf's cover")
                    if (p.buttonsInline) {
                        ok(WidgetLayout.headerWords(p, w) >= WidgetLayout.WORDS_MIN_DP, "the shelf's words squeezed")
                        ok(controlsH <= p.headerHeight, "the shelf's buttons")
                    } else {
                        ok(WidgetLayout.spreadMin(p) <= w - 2 * p.padH, "the shelf's buttons too wide")
                    }
                } else {
                    ok(2 * p.padV + p.headingHeight + grid <= h, "the list too tall")
                }
            }
            WidgetShape.PANES -> {
                ok(s.style != WidgetStyle.SHELF, "a Shelf in two panes")
                ok(p.upNext in 1..WidgetLayout.MAX_UP_NEXT, "Up next's rows")
                ok(WidgetLayout.upNextHeading(scale) + p.upNext * p.upNextRow <= h - 2 * p.padV, "Up next too tall")
                ok(p.upNextRow >= WidgetLayout.UP_NEXT_COVER_DP && p.upNextRow >= line(13, scale) + line(12, scale) + 4, "an Up next row")
                if (s.style == WidgetStyle.COVER) {
                    val column = WidgetLayout.columnWidth(p, w)
                    ok(column >= WidgetLayout.rowWidth(p) && column >= WidgetLayout.COLUMN_MIN_DP, "the left pane's buttons")
                    val colH = if (p.coverW > 0) p.coverH else h - 2 * p.padH
                    ok(words + (if (p.play) 8 + controlsH else 0) <= colH, "the left pane's words and buttons")
                    ok(p.coverH <= h - 2 * p.padH, "the left pane's cover")
                } else {
                    val bar = p.glass?.h?.plus(8) ?: 0
                    ok(2 * p.padV + words + bar <= h, "the words and the glass too tall")
                    p.glass?.let { ok(it.x + it.w <= w - p.padH - WidgetLayout.PANE_GAP_DP - p.paneW, "the glass under Up next") }
                }
            }
        }
    }

    @Test
    fun `a choice that was never made, or one from a later version, falls back rather than failing`() {
        assertEquals(WidgetContent.BOTH, WidgetContent.of(null))
        assertEquals(WidgetContent.BOTH, WidgetContent.of("SOMETHING_ELSE"))
        assertEquals(WidgetContent.LIST, WidgetContent.of("LIST"))
        assertEquals(WidgetList.QUICK_PICKS, WidgetList.of(null))
        assertEquals(WidgetList.KEEP_LISTENING, WidgetList.of("KEEP_LISTENING"))
        assertNull(WidgetBackground.of("ANYTHING"))
        assertEquals(WidgetBackground.ARTWORK, WidgetBackground.of("ARTWORK"))
        assertEquals(WidgetButtons.ALL, WidgetButtons.of(null))
        assertEquals(WidgetTextSize.NORMAL, WidgetTextSize.of(null))
        assertNull(WidgetStyle.of("A_LATER_STYLE"))
        assertEquals(WidgetStyle.FULL_COVER, WidgetStyle.of("FULL_COVER"))
        assertEquals(1f, WidgetTextSize.NORMAL.scale, 0f)
    }

    @Test
    fun `settings survive a trip through a widget's own state, the style and an unpicked background included`() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        val wanted = WidgetSettings(
            style = WidgetStyle.SHELF,
            content = WidgetContent.LIST,
            list = WidgetList.RECENT,
            maxRows = 2,
            background = WidgetBackground.LIGHT,
            opacity = 60,
            showArtwork = false,
            showArtist = false,
            showHeading = false,
            buttons = WidgetButtons.PLAY_ONLY,
            textSize = WidgetTextSize.LARGE,
            rounded = false,
            showLike = false,
        )
        WidgetKeys.write(prefs, wanted)
        assertEquals(wanted, WidgetKeys.read(prefs))
        // Back to never picked: the key goes, and the style's own is read back.
        WidgetKeys.write(prefs, wanted.copy(background = null))
        assertNull(prefs[WidgetKeys.BACKGROUND])
        assertEquals(wanted.copy(background = null), WidgetKeys.read(prefs))
        for (style in WidgetStyle.entries) {
            WidgetKeys.write(prefs, WidgetSettings(style = style))
            assertEquals(style, WidgetKeys.read(prefs).style)
        }
        assertEquals(WidgetSettings(), WidgetKeys.read(androidx.datastore.preferences.core.emptyPreferences()))
    }

    /**
     * A widget set up before there were styles: everything it was written with is there but the
     * style. It is a Cover, or a Shelf of its list if it was a list; and the background every widget
     * was written with by default, "Follow the system" at full strength, is read as never picked.
     */
    @Test
    fun `a widget from before the styles is a Cover, a list stays a list, and only a background that was picked stays`() {
        fun old(content: WidgetContent, background: WidgetBackground, opacity: Int = 100) =
            androidx.datastore.preferences.core.mutablePreferencesOf().also {
                WidgetKeys.write(it, WidgetSettings(content = content, background = background, opacity = opacity))
                it.remove(WidgetKeys.STYLE)
            }
        val both = WidgetKeys.read(old(WidgetContent.BOTH, WidgetBackground.SYSTEM))
        assertEquals(WidgetStyle.COVER, both.style)
        assertNull(both.background)
        assertEquals(WidgetBackground.ARTWORK, both.backgroundShown)
        assertEquals(WidgetStyle.COVER, WidgetKeys.read(old(WidgetContent.NOW_PLAYING, WidgetBackground.SYSTEM)).style)
        val list = WidgetKeys.read(old(WidgetContent.LIST, WidgetBackground.SYSTEM))
        assertEquals(WidgetStyle.SHELF, list.style)
        assertEquals(WidgetContent.LIST, list.contentShown)
        assertEquals(WidgetBackground.SYSTEM, list.backgroundShown)
        assertEquals(WidgetBackground.DARK, WidgetKeys.read(old(WidgetContent.BOTH, WidgetBackground.DARK)).background)
        assertEquals(WidgetBackground.SYSTEM, WidgetKeys.read(old(WidgetContent.BOTH, WidgetBackground.SYSTEM, opacity = 60)).background)
        // Once saved with a style, Follow the system is a choice like any other.
        val saved = androidx.datastore.preferences.core.mutablePreferencesOf().also {
            WidgetKeys.write(it, WidgetSettings(background = WidgetBackground.SYSTEM))
        }
        assertEquals(WidgetBackground.SYSTEM, WidgetKeys.read(saved).background)
    }

    @Test
    fun `a nonsense opacity or row count from an older file is brought back into range`() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        prefs[WidgetKeys.OPACITY] = 400
        prefs[WidgetKeys.MAX_ROWS] = 99
        val read = WidgetKeys.read(prefs)
        assertEquals(100, read.opacity)
        assertEquals(WidgetLayout.MAX_PICKS, read.maxRows)
    }
}

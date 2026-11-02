/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import com.dd3boh.outertune.widget.WidgetLayout.line
import com.dd3boh.outertune.widget.WidgetLayout.rowWidth
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The Shelf style's three looks (1 Nov 2026, drawn from real covers in experiments/features/shelf-redo
 * and picked in the widget's settings). Each holds what is playing and the list, or the list alone.
 * Big cover is the default, and a Shelf set up before there were looks is a Big cover.
 */
enum class ShelfLook {
    /** The playing cover large and square, its words over its top and its buttons in glass over its foot, the list as a mosaic of covers beside it. */
    BIG_COVER,

    /** What is playing in a line on top, and the list's covers leaning in a record crate under it. */
    CRATE,

    /** The playing cover with its record sliding out of the sleeve, and the list as a row of records. */
    RECORDS;

    companion object {
        fun of(name: String?): ShelfLook = entries.firstOrNull { it.name == name } ?: BIG_COVER
    }
}

/** A box of a look, in dp from the widget's top start. */
data class Spot(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    fun overlaps(o: Spot): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom

    fun contains(o: Spot): Boolean = o.x >= x && o.y >= y && o.right <= right && o.bottom <= bottom

    fun grown(by: Int): Spot = Spot(x - by, y - by, w + 2 * by, h + 2 * by)

    fun at(dx: Int, dy: Int): Spot = Spot(x + dx, y + dy, w, h)
}

/**
 * Where one of the looks puts what it draws, in dp from the widget's top start, decided with the
 * rest of the plan before a single view exists. The words' sizes and the buttons are the plan's own
 * fields, and the pill of buttons is the plan's glass.
 */
data class LookPlan(
    /** The cover of what is playing (Big cover's big one, the crate's line's, the record's sleeve), or Big cover's first song of a list alone. None in a crate or records of a list alone. */
    val hero: Spot? = null,
    val heroRadius: Int = 0,
    /** Records: how far the record comes out past the sleeve's end. */
    val discOut: Int = 0,
    /** The words: the title's lines and the artist under them, and in Big cover with a list alone the heading over them. */
    val words: Spot? = null,
    /** Big cover with a list alone: the heading's height, at the top of [words]. */
    val headingInWords: Int = 0,
    /** Big cover: the band under the words, solid down to this far from the cover's top, then fading out. */
    val band: Int = 0,
    /** The list's heading, where it stands on its own over the crate or the records. */
    val heading: Spot? = null,
    /** Where the pill of buttons may reach with the heart, and whether it keeps to its end (it then grows towards its start) or to its start. */
    val pillRoom: Spot? = null,
    val pillAtEnd: Boolean = false,
    /** The list's covers, each tapped to play its song: Big cover's mosaic, the crate's covers as they stand before they lean, the records. */
    val picks: List<Spot> = emptyList(),
    val pickRadius: Int = 0,
    /** Crate and Records: how many of [picks] stand in each row, in order. */
    val rows: List<Int> = emptyList(),
    /** The pictures drawn ahead of time (WidgetPictures), each with the room its shadows and leaning covers take: the hero's, and one for each row. */
    val heroPicture: Spot? = null,
    val rowPictures: List<Spot> = emptyList(),
) {
    /**
     * How wide a line of words [line] may be drawn beside the pill [glass]: where the pill keeps to
     * the end of a line the words share, they stop [WidgetLooks.WORDS_PILL_GAP_DP] short of it,
     * the heart's room included when it is there; anywhere else, as wide as the line.
     */
    fun lineRoom(line: Spot, glass: GlassPane?): Int {
        if (!pillAtEnd || glass == null) return line.w
        val shares = glass.y < line.bottom && glass.y + glass.h > line.y
        return if (shares) minOf(line.w, glass.x - WidgetLooks.WORDS_PILL_GAP_DP - line.x) else line.w
    }

    /** Where each pick can be tapped: its cover, or in a row where the next one lies over it, the part of it that shows. */
    fun taps(): List<Spot> {
        if (rows.isEmpty()) return picks
        val out = ArrayList<Spot>(picks.size)
        var at = 0
        for (count in rows) {
            for (i in 0 until count) {
                val p = picks[at + i]
                val end = if (i < count - 1) picks[at + i + 1].x else p.right
                out += Spot(p.x, p.y, end - p.x, p.h)
            }
            at += count
        }
        return out
    }
}

/**
 * The looks' arithmetic: what [WidgetLayout.plan] gives a Shelf. Every size in dp comes from here,
 * as for every other shape, and WidgetLayoutTest walks every size a launcher can hand out in each
 * look to prove nothing is cut off, nothing lies outside the widget and every button can be tapped.
 */
object WidgetLooks {
    /** Round Big cover and between its covers: less on a small widget, more on a tablet's. */
    const val PAD_DP = 12
    const val SMALL_PAD_DP = 8
    const val GAP_DP = 6
    const val BIG_GAP_DP = 10

    /** Big cover's mosaic: covers no smaller than this, where a cover is still a cover, nor larger than the files they are drawn from allow. */
    const val MIN_CELL_DP = 48
    const val MAX_CELL_DP = 120
    const val BIG_MAX_CELL_DP = 160

    /** The big cover is drawn from a 320 pixel file: past this it would only be blurred. */
    const val MAX_HERO_DP = 360

    /** At most this many columns and rows in Big cover's mosaic, its big cover counted, which spans at most three of them (the mockup's). */
    const val MAX_COLUMNS = 8
    const val MAX_ROWS = 6
    const val MAX_SPAN = 3

    /**
     * What a song of the list is worth against the share of the widget a look fills, when the
     * planner chooses between arrangements. In Big cover's mosaic six songs make up for about two
     * fifths of the room, so the approved four by three (a big cover and two by three covers beside
     * it) wins over a bigger cover with two songs, and a square widget still fills itself with five.
     * With a list alone, where the list is all there is, a song is worth more; in the crate and the
     * records, less against covers big enough to see.
     */
    const val MOSAIC_PICK_WEIGHT = 0.07f
    const val LIST_PICK_WEIGHT = 0.12f
    const val PICK_WEIGHT = 0.04f

    /** The pill of buttons: every button 40 dp to tap, play a filled disc as big, and a little glass round them. */
    const val PILL_BUTTON_DP = 40
    const val PILL_PLAY_DP = 40
    const val PILL_GAP_DP = 2
    const val PILL_PAD_H_DP = 5
    const val PILL_PAD_V_DP = 4

    /** On a big cover or a tablet's widget, the pill a size up. */
    const val BIG_PILL_BUTTON_DP = 44
    const val BIG_PILL_PLAY_DP = 48
    const val BIG_PILL_GAP_DP = 4
    const val BIG_PILL_PAD_H_DP = 8

    /** Between the pill and the big cover's edges, and the big cover from which the pill is a size up. */
    const val PILL_MARGIN_DP = 6
    const val BIG_PILL_FROM_DP = 280

    /** How far a cover's shadow reaches past it. */
    const val SHADOW_DP = 8

    /** Words never get less than this across: under it a title is a few letters and an ellipsis. */
    const val WORDS_MIN_DP = 96

    /** Between words and the pill at the end of their line. */
    const val WORDS_PILL_GAP_DP = 10

    /** What a title is given, at the text's own size, before the buttons may share its line: else they go under it. */
    const val TITLE_ROOM_DP = 150

    /** Between what is playing and the list under it, between rows, and between a cover and its words. */
    const val SECTION_GAP_DP = 12
    const val ROW_GAP_DP = 4
    const val WORDS_GAP_DP = 12

    // The crate.

    /** Round the crate and the records: their covers lean and cast shadows, and both stay inside the widget. */
    const val ROWS_PAD_DP = 14
    const val BIG_ROWS_PAD_DP = 18

    /** A crate's covers: no smaller than this, nor larger than this (phone, tablet). */
    const val TILE_MIN_DP = 56
    const val TILE_MAX_DP = 120
    const val BIG_TILE_MAX_DP = 180

    /** The part of a cover or a record that shows before the next one lies over it: it is what is tapped, so never under 40 dp. */
    const val MIN_STEP_DP = 40

    /** The most of a cover that shows past the next: a crate's covers always lie over each other, a little less on a tablet's widget. */
    const val STEP_SHARE = 0.62f
    const val BIG_STEP_SHARE = 0.72f

    /** Every other cover stands this much lower, as records in a crate never stand level. */
    const val JITTER_DP = 6

    /** How each cover leans, in degrees, clockwise: the mockup's, and on past its seventh. */
    val ANGLES = intArrayOf(-7, 4, -3, 6, -5, 3, -6, 5, -4, 7, -2, 4)

    /** The line's cover in the crate: at least, on a tablet, and at most where it grows beside its words. */
    const val LINE_COVER_DP = 56
    const val BIG_LINE_COVER_DP = 72
    const val LINE_COVER_MAX_DP = 120
    const val BIG_LINE_COVER_MAX_DP = 200

    /** How far a cover leaning at most seven degrees reaches past the square it stands in, each way. */
    fun lean(tile: Int): Int = ceil(tile * 0.058f).toInt()

    // The records.

    /** The sleeve of what is playing (the least it is on a narrow widget's line, and beside its words), and the records of the list. */
    const val SMALL_SLEEVE_DP = 56
    const val SLEEVE_MIN_DP = 72
    const val SLEEVE_MAX_DP = 150
    const val BIG_SLEEVE_MAX_DP = 260
    const val DISC_MIN_DP = 52
    const val DISC_MAX_DP = 96
    const val BIG_DISC_MAX_DP = 180

    /** The record out of its sleeve, as a share of the sleeve; the records of a row, how far apart. */
    const val OUT_SHARE = 0.35f
    const val DISC_STEP_SHARE = 0.8f

    /** Between the record out of its sleeve and the words beside it. */
    const val COLUMN_GAP_DP = 16

    /** The most of their room the crate's or the records' rows leave empty one way, where they can help it. */
    const val ROOM_LEFT_SHARE = 0.45f

    /** Rows of the crate or of records, at most, and what a row holds at most: its places to tap are a row of their own, and Glance keeps ten children at most. */
    const val MAX_LOOK_ROWS = 3
    const val MAX_PER_ROW = 8

    /** The list's heading: a line of 12 sp and the room under it. */
    fun headingHeight(scale: Float): Int = line(12, scale) + 6

    /** What a look draws at this size, or null when it has no room for it: the planner then draws a line, or with a list alone its heading. */
    fun plan(w: Int, h: Int, s: WidgetSettings, available: Int, scale: Float): WidgetPlan? {
        val list = s.contentShown == WidgetContent.LIST
        if (available <= 0 && (list || s.look != ShelfLook.BIG_COVER)) return null
        return when (s.look) {
            ShelfLook.BIG_COVER -> bigCover(w, h, s, available, scale, list)
            ShelfLook.CRATE, ShelfLook.RECORDS -> rows(w, h, s, available, scale, list)
        }
    }

    /** The pill of buttons: previous, play and next, or play alone in a circle; the heart is added by [withLike]. */
    private class Pill(val skips: Boolean, val button: Int, val play: Int, val gap: Int) {
        val h: Int get() = maxOf(button, play) + 2 * PILL_PAD_V_DP
        val padH: Int get() = if (button > PILL_BUTTON_DP) BIG_PILL_PAD_H_DP else PILL_PAD_H_DP
        val w: Int get() = if (skips) rowWidth(false, true, true, false, button, play, gap) + 2 * padH else h
    }

    private fun pill(s: WidgetSettings, skips: Boolean, big: Boolean): Pill? = when {
        s.buttons == WidgetButtons.NONE -> null
        big -> Pill(skips, BIG_PILL_BUTTON_DP, BIG_PILL_PLAY_DP, BIG_PILL_GAP_DP)
        else -> Pill(skips, PILL_BUTTON_DP, PILL_PLAY_DP, PILL_GAP_DP)
    }

    /** Previous and next where they were asked for, then play alone: a look gives up its skips before it gives up its shape. */
    private fun skipsTries(s: WidgetSettings, list: Boolean): List<Boolean> =
        if (list) listOf(false) else listOf(s.buttons == WidgetButtons.ALL, false).distinct()

    private fun WidgetPlan.withPill(pill: Pill?, at: Spot?): WidgetPlan =
        if (pill == null || at == null) copy(play = false, skips = false)
        else copy(
            play = true, skips = pill.skips,
            button = pill.button, playSize = pill.play, gap = if (pill.skips) pill.gap else 0,
            glass = GlassPane(at.x, at.y, at.w, at.h, circle = !pill.skips),
        )

    // Big cover.

    /** The words over a big cover and the pill under them. */
    private class Face(
        val titleSp: Int, val artistSp: Int, val artist: Boolean, val lines: Int,
        val inset: Int, val heading: Int, val words: Int, val pill: Pill?,
    ) {
        val band: Int get() = inset + words + 8
    }

    /** The words and the pill on a big cover [hero] dp across, or null when they do not fit on it. */
    private fun face(hero: Int, s: WidgetSettings, scale: Float, list: Boolean, skips: Boolean, big: Boolean): Face? {
        val titleSp = when {
            hero < 150 -> 14
            hero < 200 -> 16
            hero < 280 -> 18
            else -> 20
        }
        val artistSp = when {
            hero < 150 -> 12
            hero < 280 -> 13
            else -> 14
        }
        val inset = if (hero < 150) 10 else 14
        val t = line(titleSp, scale)
        val a = line(artistSp, scale)
        val heading = if (list && s.showHeading) line(12, scale) + 2 else 0
        val pill = if (list) null else pill(s, skips, big || hero >= BIG_PILL_FROM_DP)
        if (pill != null && pill.w + 2 * PILL_MARGIN_DP > hero) return null
        if (hero - 2 * inset < WORDS_MIN_DP) return null
        // The words, the room under them, the pill and its margin: the band never reaches the pill.
        val room = hero - inset - 8 - (pill?.let { it.h + PILL_MARGIN_DP + 6 } ?: 0)
        if (heading + t > room) return null
        val artist = s.showArtist && heading + t + a <= room
        val lines = if (hero >= 260 && heading + 2 * t + (if (artist) a else 0) <= room) 2 else 1
        return Face(titleSp, artistSp, artist, lines, inset, heading, heading + lines * t + (if (artist) a else 0), pill)
    }

    private class Mosaic(val cols: Int, val rows: Int, val n: Int, val cell: Int, val face: Face, val score: Float)

    /**
     * Big cover (the default): the cover large and square at the start, its words over
     * its top on a band dark enough that no cover's own lettering can fight them, the pill over its
     * foot, and the list's covers as a mosaic round it. It is a grid of equal covers with the big one
     * taking [Mosaic.n] by n of them in its top start corner: two by three beside it at a phone's
     * four by three (the mockup), one column beside it on a flat widget, round it on a square one, a
     * row under it on a tall one, and on a tablet as many as twelve. Of all the grids whose covers
     * stay big enough and whose big cover holds its words and buttons, the one that fills the widget
     * best, each song of the list counting for [MOSAIC_PICK_WEIGHT]. With a list alone the big cover is the
     * list's first song, its name over it and the heading over that.
     */
    private fun bigCover(w: Int, h: Int, s: WidgetSettings, available: Int, scale: Float, list: Boolean): WidgetPlan? {
        val big = WidgetLayout.isBig(w, h)
        val pad = if (minOf(w, h) < 160) SMALL_PAD_DP else PAD_DP
        val gap = if (big) BIG_GAP_DP else GAP_DP
        val iw = w - 2 * pad
        val ih = h - 2 * pad
        if (iw <= 0 || ih <= 0) return null
        val songs = (if (list) available - 1 else available).coerceAtLeast(0)
        val maxCell = if (big) BIG_MAX_CELL_DP else MAX_CELL_DP
        var best: Mosaic? = null
        for (skips in skipsTries(s, list)) {
            for (cols in 1..MAX_COLUMNS) {
                val acrossCell = (iw - (cols - 1) * gap) / cols
                if (acrossCell <= 0) break
                for (rows in 1..MAX_ROWS) {
                    val downCell = (ih - (rows - 1) * gap) / rows
                    if (downCell <= 0) break
                    for (n in 1..minOf(cols, rows, MAX_SPAN)) {
                        val k = cols * rows - n * n
                        if (k > songs) continue
                        // The big cover beside the others, or the big cover alone.
                        if (if (k == 0) cols != 1 || rows != 1 else n < 2) continue
                        val cell = minOf(acrossCell, downCell, (MAX_HERO_DP - (n - 1) * gap) / n, if (k > 0) maxCell else Int.MAX_VALUE)
                        if (k > 0 && cell < MIN_CELL_DP) continue
                        val hero = n * cell + (n - 1) * gap
                        val face = face(hero, s, scale, list, skips, big) ?: continue
                        val gw = cols * cell + (cols - 1) * gap
                        val gh = rows * cell + (rows - 1) * gap
                        // A grid that leaves a quarter of the room or more empty one way loses to one
                        // that does not, and between two that fill as much, the one that is more even.
                        val even = minOf(gw.toFloat() / iw, gh.toFloat() / ih)
                        val lopsided = 4f * maxOf(0f, 0.75f - even)
                        val score = gw.toFloat() * gh / (iw.toFloat() * ih) + (if (list) LIST_PICK_WEIGHT else MOSAIC_PICK_WEIGHT) * k + 0.01f * even - lopsided
                        if (best == null || score > best.score) best = Mosaic(cols, rows, n, cell, face, score)
                    }
                }
            }
            if (best != null) break
        }
        val m = best ?: return null
        val gw = m.cols * m.cell + (m.cols - 1) * gap
        val gh = m.rows * m.cell + (m.rows - 1) * gap
        val ox = pad + (iw - gw) / 2
        val oy = pad + (ih - gh) / 2
        val side = m.n * m.cell + (m.n - 1) * gap
        val hero = Spot(ox, oy, side, side)
        val f = m.face
        val picks = buildList {
            for (r in 0 until m.rows) for (c in 0 until m.cols) {
                if (r < m.n && c < m.n) continue
                add(Spot(ox + c * (m.cell + gap), oy + r * (m.cell + gap), m.cell, m.cell))
            }
        }
        val pillSpot = f.pill?.let { p -> Spot(hero.x + (hero.w - p.w) / 2, hero.bottom - PILL_MARGIN_DP - p.h, p.w, p.h) }
        val look = LookPlan(
            hero = hero,
            heroRadius = (side * 0.11f).roundToInt().coerceIn(14, 28),
            words = Spot(hero.x + f.inset, hero.y + f.inset, hero.w - 2 * f.inset, f.words),
            headingInWords = f.heading,
            band = f.band,
            pillRoom = pillSpot?.let { Spot(hero.x + PILL_MARGIN_DP, it.y, hero.w - 2 * PILL_MARGIN_DP, it.h) },
            picks = picks,
            pickRadius = when {
                m.cell < 56 -> 10
                m.cell < 120 -> 12
                else -> 14
            },
            heroPicture = hero.grown(SHADOW_DP),
        )
        return WidgetPlan(
            WidgetStyle.SHELF, WidgetShape.BIG_COVER,
            padH = ox, padV = oy,
            coverW = side, coverH = side,
            titleLines = f.lines, titleSp = f.titleSp, artistSp = f.artistSp, showArtist = f.artist,
            shown = picks.size + (if (list) 1 else 0),
            look = look,
        ).withPill(f.pill, pillSpot)
    }

    // The line of what is playing, over the crate or the records.

    private enum class Mode {
        /** The cover, the words and the pill at the end, in one line: a tablet's. */
        INLINE,

        /** The title across the line, and the artist under it with the pill at its end: the title gets its room first. */
        SPLIT,

        /** The words, and the pill under them, beside a cover or a sleeve as tall as both. */
        BESIDE,

        /** The cover or the sleeve and the words on a line, and the pill on a line of its own under them. */
        UNDER,
    }

    /** What is playing over the crate or the records, laid out from the top start of its room. */
    private class Header(
        val mode: Mode,
        val heroSide: Int,
        /** What the hero takes across: the cover, or the sleeve with its record out. */
        val block: Int,
        val height: Int,
        val heroY: Int,
        val words: Spot,
        val pill: Pill?,
        val pillSpot: Spot?,
        val pillRoom: Spot?,
        val pillAtEnd: Boolean,
        val titleSp: Int,
        val artistSp: Int,
        val artist: Boolean,
    )

    /**
     * The line of what is playing in [mode], [iw] across, or null when it has no room for it: the
     * crate's has a cover, and Records' (when [records]) its sleeve with the record out. The pill
     * sits at the end of the line only where the title keeps [TITLE_ROOM_DP], at the end of the
     * artist's line only where the artist keeps [WORDS_MIN_DP], and with [heart] these are judged
     * with the heart's width too. Whether the heart is wanted is never asked, so turning it on or off
     * never changes the line; the words are drawn up to the pill there is ([LookPlan.lineRoom]).
     * [want] is the side the cover or the sleeve beside its words is wanted at, which it takes as far
     * as its largest and the width allow.
     */
    private fun header(mode: Mode, iw: Int, s: WidgetSettings, scale: Float, big: Boolean, skips: Boolean, heart: Boolean, records: Boolean, want: Int): Header? {
        val titleSp = if (big) 20 else 16
        val artistSp = if (big) 15 else 13
        val t = line(titleSp, scale)
        val a = line(artistSp, scale)
        val pill = pill(s, skips, big)
        val ph = pill?.h ?: 0
        val pw = pill?.let { if (it.skips && heart) it.w + it.button + it.gap else it.w } ?: 0
        val titleRoom = (TITLE_ROOM_DP * scale).roundToInt()
        val gap = if (records) COLUMN_GAP_DP else WORDS_GAP_DP
        fun block(side: Int) = if (records) side + (side * OUT_SHARE).roundToInt() else side
        val minSide = when {
            records -> SLEEVE_MIN_DP
            big -> BIG_LINE_COVER_DP
            else -> LINE_COVER_DP
        }
        val maxSide = when {
            records -> if (big) BIG_SLEEVE_MAX_DP else SLEEVE_MAX_DP
            else -> if (big) BIG_LINE_COVER_MAX_DP else LINE_COVER_MAX_DP
        }
        fun words(artist: Boolean) = t + (if (artist) a else 0)
        when (mode) {
            Mode.INLINE -> {
                // The cover, the words, the pill at the end.
                pill ?: return null
                val side = minSide
                val x = block(side) + gap
                val wordsW = iw - x - WORDS_PILL_GAP_DP - pill.w
                if (iw - x - WORDS_PILL_GAP_DP - pw < titleRoom) return null
                val artist = s.showArtist && t + a <= maxOf(side, ph)
                val height = maxOf(side, ph, words(artist))
                val py = (height - ph) / 2
                val from = x + titleRoom + WORDS_PILL_GAP_DP
                return Header(
                    Mode.INLINE, side, block(side), height, (height - side) / 2,
                    Spot(x, (height - words(artist)) / 2, wordsW, words(artist)),
                    pill, Spot(iw - pill.w, py, pill.w, ph), Spot(from, py, iw - from, ph), true,
                    titleSp, artistSp, artist,
                )
            }
            Mode.SPLIT -> {
                // The title across, the artist under it with the pill at its end.
                pill ?: return null
                if (!s.showArtist) return null
                val height = t + ph
                val side = maxOf(minSide, height)
                val x = block(side) + gap
                val titleW = iw - x
                if (side > maxSide || titleW < titleRoom || titleW - pw - WORDS_PILL_GAP_DP < WORDS_MIN_DP) return null
                val top = (side - height) / 2
                val from = x + WORDS_MIN_DP + WORDS_PILL_GAP_DP
                return Header(
                    Mode.SPLIT, side, block(side), side, 0,
                    Spot(x, top, titleW, t + a),
                    pill, Spot(iw - pill.w, top + t, pill.w, ph), Spot(from, top + t, iw - from, ph), true,
                    titleSp, artistSp, true,
                )
            }
            Mode.BESIDE -> {
                // The words, and the pill under them, in a column beside the cover or the sleeve.
                val artist = s.showArtist
                val column = words(artist) + (if (pill != null) 8 + ph else 0)
                val need = maxOf(pw, WORDS_MIN_DP)
                val least = maxOf(minSide, column)
                var side = minOf(maxOf(least, want), maxOf(maxSide, least))
                while (side >= least && iw - block(side) - gap < need) side--
                if (side < least) return null
                val top = (side - column) / 2
                val x = block(side) + gap
                val colW = iw - x
                val py = top + words(artist) + 8
                return Header(
                    Mode.BESIDE, side, block(side), side, 0,
                    Spot(x, top, colW, words(artist)),
                    pill, pill?.let { Spot(x, py, it.w, ph) }, pill?.let { Spot(x, py, colW, ph) }, false,
                    titleSp, artistSp, artist,
                )
            }
            Mode.UNDER -> {
                // The cover or the sleeve and the words on a line, the pill on a line of its own.
                val side = if (records) SMALL_SLEEVE_DP else minSide
                val artist = s.showArtist && t + a <= side
                val wordsW = iw - block(side) - gap
                if (wordsW < WORDS_MIN_DP || t > side || pw > iw) return null
                val py = side + 8
                return Header(
                    Mode.UNDER, side, block(side), side + (if (pill != null) 8 + ph else 0), 0,
                    Spot(block(side) + gap, (side - words(artist)) / 2, wordsW, words(artist)),
                    pill, pill?.let { Spot(0, py, it.w, ph) }, pill?.let { Spot(0, py, iw, ph) }, false,
                    titleSp, artistSp, artist,
                )
            }
        }
    }

    // The rows: the crate's and the records'.

    /** How a row of the crate or of records stands: its things' size, and how much room a row of them takes. */
    private class RowKind(
        val crate: Boolean,
        val min: Int,
        val max: Int,
        val big: Boolean,
    ) {
        /** Past the things themselves: a crate's covers lean both ways. */
        fun reach(size: Int): Int = if (crate) lean(size) else 0

        fun height(size: Int): Int = if (crate) size + JITTER_DP + 2 * lean(size) else size

        /** The most of each thing that shows past the next. */
        fun step(size: Int): Int = maxOf(MIN_STEP_DP, ((if (!crate) DISC_STEP_SHARE else if (big) BIG_STEP_SHARE else STEP_SHARE) * size).toInt())

        /** How many fit [room] across with at least [MIN_STEP_DP] of each showing. */
        fun count(size: Int, room: Int): Int {
            val span = room - 2 * reach(size) - size
            return if (span < 0) 0 else 1 + span / MIN_STEP_DP
        }

        /** How wide a row of [n] is in [room]. */
        fun width(size: Int, n: Int, room: Int): Int {
            val span = room - 2 * reach(size)
            val step = if (n <= 1) 0 else minOf((span - size) / (n - 1), step(size))
            return (n - 1) * step + size + 2 * reach(size)
        }

        /** A row of [n] in [room] across from [x], each over the one before, in the middle of the room. */
        fun row(x: Int, y: Int, room: Int, size: Int, n: Int): List<Spot> {
            val span = room - 2 * reach(size)
            val step = if (n <= 1) 0 else minOf((span - size) / (n - 1), step(size))
            val used = (n - 1) * step + size
            val x0 = x + reach(size) + (span - used) / 2
            return List(n) { i -> Spot(x0 + i * step, y + reach(size) + (if (crate && i % 2 == 1) JITTER_DP else 0), size, size) }
        }
    }

    private class Rows(val rows: Int, val size: Int, val shown: Int, val score: Float, val lopsided: Float)

    /**
     * The rows that fill [room] by [height] best with up to [songs] things. First, rows that leave
     * less of the room empty one way (no more than [ROOM_LEFT_SHARE] of it where they can). Then the
     * crate keeps to one row, its look, and takes a second or a third only where the first leaves the
     * room that empty; two short rows of three looked like two clumps where one row of six fills the
     * width (seen on the emulator, 1 Nov 2026). Records take the rows that show the most, the
     * biggest. Last, what the rows fill, more for filling it evenly, and [PICK_WEIGHT] a song.
     */
    private fun fitRows(kind: RowKind, room: Int, height: Int, songs: Int, maxRows: Int, max: Int): Rows? {
        var best: Rows? = null
        fun better(a: Rows, b: Rows): Boolean = when {
            a.lopsided != b.lopsided -> a.lopsided < b.lopsided
            kind.crate && a.rows != b.rows -> a.rows < b.rows
            !kind.crate && a.shown != b.shown -> a.shown > b.shown
            !kind.crate && a.size != b.size -> a.size > b.size
            else -> a.score > b.score
        }
        val ceiling = if (maxRows > 0) minOf(maxRows, MAX_LOOK_ROWS) else MAX_LOOK_ROWS
        for (r in 1..ceiling) {
            val per = (height - (r - 1) * ROW_GAP_DP) / r
            var size = minOf(max, (room * 0.6f).toInt(), per)
            while (size >= kind.min && kind.height(size) > per) size--
            if (size < kind.min) continue
            val fits = minOf(kind.count(size, room), MAX_PER_ROW)
            if (fits <= 0) continue
            val shown = minOf(songs, fits * r)
            // No row left empty: fewer songs than rows is fewer rows.
            if (shown < r) continue
            val tall = r * kind.height(size) + (r - 1) * ROW_GAP_DP
            val wide = kind.width(size, (shown + r - 1) / r, room)
            // What the rows fill, more for filling it evenly both ways, the songs, and bigger covers; and
            // rows that leave most of the room empty one way lose to any that leave less of it.
            val down = tall.toFloat() / height
            val across = wide.toFloat() / room
            val lopsided = maxOf(0f, (1f - ROOM_LEFT_SHARE) - minOf(down, across))
            val score = down * across + 0.5f * minOf(down, across) + PICK_WEIGHT * shown + size / 2000f
            val rows = Rows(r, size, shown, score, lopsided)
            if (best == null || better(rows, best)) best = rows
        }
        return best
    }

    /** [shown] songs over [rows] rows, the first rows a song fuller where they do not share out evenly. */
    private fun perRow(shown: Int, rows: Int): List<Int> = List(rows) { r -> shown / rows + (if (r < shown % rows) 1 else 0) }

    /**
     * Crate and Records. Crate: what is playing in a line on top (its cover, its words and the pill
     * of buttons), and the list's covers under it leaning in a record crate, each over the one before,
     * flicked through from the start. Records: the sleeve of what is playing with its record sliding
     * out of it, its words and the pill beside it, and the list as records in a row, each one's cover
     * the label at its middle. The line's title has its room first: the buttons sit at the end of the
     * line only where the title keeps [TITLE_ROOM_DP], else under the title, under the words, or
     * under the line. Under it the list's heading and a row, or two or three on a tall widget. With a
     * list alone, the heading and the rows.
     */
    private fun rows(w: Int, h: Int, s: WidgetSettings, available: Int, scale: Float, list: Boolean): WidgetPlan? {
        val big = WidgetLayout.isBig(w, h)
        val crate = s.look == ShelfLook.CRATE
        val pad = if (big) BIG_ROWS_PAD_DP else ROWS_PAD_DP
        val iw = w - 2 * pad
        val ih = h - 2 * pad
        if (iw <= 0 || ih <= 0) return null
        val kind = if (crate) RowKind(true, TILE_MIN_DP, if (big) BIG_TILE_MAX_DP else TILE_MAX_DP, big)
        else RowKind(false, DISC_MIN_DP, if (big) BIG_DISC_MAX_DP else DISC_MAX_DP, big)
        val headingH = headingHeight(scale)
        // The crate's covers are its look: its line is as short as it can be, and its cover takes what
        // the rows leave. The sleeve with its record out is Records' look: it is beside its words where
        // it can be, wanted at about half the height, and gives way to the records only as they need.
        // Each line is tried with room for the heart first, then without, then with play alone: the
        // crate keeps the heart before the line it would like, Records its sleeve beside its words.
        val order = if (crate) listOf(Mode.INLINE, Mode.SPLIT, Mode.BESIDE, Mode.UNDER) else listOf(Mode.BESIDE, Mode.SPLIT, Mode.INLINE, Mode.UNDER)
        data class Try(val mode: Mode?, val skips: Boolean, val heart: Boolean)
        val tries = buildList {
            if (list) add(Try(null, false, false))
            else for (skips in skipsTries(s, false)) {
                val hearts = if (skips) listOf(true, false) else listOf(false)
                if (crate) for (heart in hearts) for (mode in order) add(Try(mode, skips, heart))
                else for (mode in order) for (heart in hearts) add(Try(mode, skips, heart))
            }
        }
        for ((mode, skips, heart) in tries) {
            val wants = if (!crate && mode == Mode.BESIDE) listOf((ih * 0.55f).toInt(), (ih * 0.42f).toInt(), 0) else listOf(0)
            for (want in wants) {
                val head = mode?.let { header(it, iw, s, scale, big, skips, heart, records = !crate, want = want) ?: continue }
                val top = head?.let { it.height + SECTION_GAP_DP } ?: 0
                // Records are never bigger than the sleeve over them.
                val max = head?.takeIf { !crate }?.let { minOf(kind.max, maxOf(kind.min, it.heroSide)) } ?: kind.max
                val bare = fitRows(kind, iw, ih - top, available, s.maxRows, max) ?: continue
                // The list's heading where it costs no song and shrinks none, as on the shelf before.
                val titled = if (s.showHeading) fitRows(kind, iw, ih - top - headingH, available, s.maxRows, max) else null
                val withHeading = titled != null && titled.shown >= bare.shown && titled.size >= bare.size && titled.rows == bare.rows
                val fit = if (withHeading) titled!! else bare
                val heading = if (withHeading) headingH else 0
                val rowH = kind.height(fit.size)
                val rowsH = fit.rows * rowH + (fit.rows - 1) * ROW_GAP_DP
                // The cover or the sleeve beside its words takes what the rows leave, up to its largest.
                val spare = ih - top - heading - rowsH
                val grown = if (head != null && head.mode == Mode.BESIDE && spare > 0) {
                    header(Mode.BESIDE, iw, s, scale, big, skips, heart, records = !crate, want = head.heroSide + spare)
                } else null
                val line = grown ?: head
                val used = (line?.let { it.height + SECTION_GAP_DP } ?: 0) + heading + rowsH
                val oy = pad + (ih - used) / 2
                return place(if (crate) WidgetShape.CRATE else WidgetShape.RECORDS, kind, pad, oy, iw, line, heading, perRow(fit.shown, fit.rows), fit.size)
            }
        }
        return null
    }

    /** The plan of a crate or of records: [line] from [oy] down, the heading, and the rows of [counts] things of [size]. */
    private fun place(
        shape: WidgetShape, kind: RowKind, pad: Int, oy: Int, iw: Int,
        line: Header?, heading: Int, counts: List<Int>, size: Int,
    ): WidgetPlan {
        var y = oy
        var plan = WidgetPlan(WidgetStyle.SHELF, shape, padH = pad, padV = oy, titleLines = 0)
        var hero: Spot? = null
        var heroPicture: Spot? = null
        if (line != null) {
            val h = Spot(pad, y + line.heroY, line.heroSide, line.heroSide)
            hero = h
            heroPicture = Spot(h.x, h.y, line.block, h.h).grown(SHADOW_DP)
            plan = plan.copy(
                coverW = line.heroSide, coverH = line.heroSide,
                titleLines = 1, titleSp = line.titleSp, artistSp = line.artistSp, showArtist = line.artist,
            ).withPill(line.pill, line.pillSpot?.at(pad, y))
            y += line.height + SECTION_GAP_DP
        }
        val headingSpot = if (heading > 0) Spot(pad, y, iw, heading).also { y += heading } else null
        val picks = ArrayList<Spot>()
        val pictures = ArrayList<Spot>()
        val rowH = kind.height(size)
        for ((r, n) in counts.withIndex()) {
            if (r > 0) y += ROW_GAP_DP
            val spots = kind.row(pad, y, iw, size, n)
            picks += spots
            val left = spots.minOf { it.x } - kind.reach(size)
            val right = spots.maxOf { it.right } + kind.reach(size)
            pictures += Spot(left, y, right - left, rowH).grown(SHADOW_DP)
            y += rowH
        }
        return plan.copy(
            shown = picks.size,
            look = LookPlan(
                hero = hero,
                heroRadius = hero?.let {
                    if (shape == WidgetShape.RECORDS) (it.w * 0.075f).roundToInt().coerceIn(6, 16) else (it.w * 0.2f).roundToInt().coerceIn(10, 22)
                } ?: 0,
                discOut = line?.let { it.block - it.heroSide } ?: 0,
                words = line?.words?.at(pad, oy),
                heading = headingSpot,
                pillRoom = line?.pillRoom?.at(pad, oy),
                pillAtEnd = line?.pillAtEnd ?: false,
                picks = picks,
                pickRadius = if (size < 80) 8 else 10,
                rows = counts,
                heroPicture = heroPicture,
                rowPictures = pictures,
            ),
        )
    }

    /** Where the record out of a sleeve [side] dp across lies, from the sleeve's top start: its left, its top, its size. */
    fun disc(side: Int): Triple<Int, Int, Int> {
        val d = (side * 0.955f).roundToInt()
        val out = (side * OUT_SHARE).roundToInt()
        return Triple(side + out - d, (side - d) / 2, d)
    }

    /**
     * [plan] with the heart before previous where the room the plan keeps for the pill holds it: on
     * Big cover the pill grows from its middle within the cover's margins; at the end of a line it
     * grows towards its start, into the room the words keep clear; under words it grows towards its end.
     */
    internal fun withLike(plan: WidgetPlan): WidgetPlan {
        val g = plan.glass ?: return plan
        val look = plan.look ?: return plan
        val room = look.pillRoom ?: return plan
        if (g.circle) return plan
        val grown = g.w + plan.button + plan.gap
        if (grown > room.w) return plan
        val x = when {
            plan.shape == WidgetShape.BIG_COVER -> room.x + (room.w - grown) / 2
            look.pillAtEnd -> g.x + g.w - grown
            else -> g.x
        }
        return plan.copy(like = true, glass = g.copy(x = x, w = grown))
    }
}

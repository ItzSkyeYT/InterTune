/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What a widget holds, and what shape it takes at the size the launcher gave it.
 *
 * Two people want different things from the same space. One wants a remote control and nothing
 * else; one wants somewhere to start a song from without opening anything; one wants both. So the
 * choice is theirs, per widget, made when it is dropped on the home screen and changeable after.
 * Everything here is plain arithmetic over dp, which means the shape of the widget at every size
 * is decided by a test rather than by dragging one around a home screen.
 */
enum class WidgetContent {
    /** What is playing, and a list under it when there is room. */
    BOTH,

    /** What is playing and nothing else. */
    NOW_PLAYING,

    /** A list and nothing else: somewhere to start from, with the player left to the app. */
    LIST;

    companion object {
        fun of(name: String?): WidgetContent = entries.firstOrNull { it.name == name } ?: BOTH
    }
}

/** Which row the list shows. Each is a row the app already keeps, so none of them is a new idea. */
enum class WidgetList {
    QUICK_PICKS,
    FORGOTTEN_FAVOURITES,
    KEEP_LISTENING,
    RECENT;

    companion object {
        fun of(name: String?): WidgetList = entries.firstOrNull { it.name == name } ?: QUICK_PICKS
    }
}

/** What the widget sits on. */
enum class WidgetBackground {
    /** The launcher's own widget background, which follows the phone's theme. */
    SYSTEM,
    DARK,
    LIGHT,

    /** The cover's own colour, deep enough for white words: see WidgetColours.deep. */
    ARTWORK,

    /** None: the words sit straight on the wallpaper. */
    NONE;

    companion object {
        fun of(name: String?): WidgetBackground? = entries.firstOrNull { it.name == name }
    }
}

/** How much of the transport the widget carries. */
enum class WidgetButtons {
    /** Previous, play or pause, next, as the width allows. */
    ALL,

    /** Play and pause only, however wide it is. */
    PLAY_ONLY,

    /** None: a widget to look at and to tap, not to drive. */
    NONE;

    companion object {
        fun of(name: String?): WidgetButtons = entries.firstOrNull { it.name == name } ?: ALL
    }
}

/** Words larger or smaller than the app's own, for eyes and for small widgets. */
enum class WidgetTextSize(val scale: Float) {
    SMALL(0.85f),
    NORMAL(1f),
    LARGE(1.2f);

    companion object {
        fun of(name: String?): WidgetTextSize = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * How the widget looks, picked in its own settings (25 Oct 2026, from the three looks he approved
 * in experiments/features/widget-rework). Every size draws in the style: a style is a way of
 * drawing the widget, not a size it is drawn at.
 */
enum class WidgetStyle {
    /** The cover as the hero, on the song's own colours. The default: closest to what the widget was, and it reads on any wallpaper. */
    COVER,

    /** The cover fills the widget, the words over it and the buttons in one pane of glass. */
    FULL_COVER,

    /** What is playing on top, and the list as covers to tap under it. */
    SHELF;

    /** What this style is drawn on when no background was picked: the song's colours, or for Shelf the launcher's. */
    val ownBackground: WidgetBackground get() = if (this == SHELF) WidgetBackground.SYSTEM else WidgetBackground.ARTWORK

    companion object {
        fun of(name: String?): WidgetStyle? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Everything one widget was told to be. Read from that widget's own state, so two widgets side by
 * side can be a Cover in the song's colours and a Shelf of forgotten favourites, at different
 * sizes. Anything never chosen falls back to the default, which is also what a widget dropped on
 * a home screen and never configured gets.
 */
data class WidgetSettings(
    val style: WidgetStyle = WidgetStyle.COVER,
    /** What a Shelf holds: what is playing and the list, or the list alone. The other styles are what is playing. */
    val content: WidgetContent = WidgetContent.BOTH,
    val list: WidgetList = WidgetList.QUICK_PICKS,
    /** 0 means as many rows of covers as fit; anything else is a ceiling the listener set. */
    val maxRows: Int = 0,
    /** Null until one is picked, and then the style's own: see [backgroundShown]. */
    val background: WidgetBackground? = null,
    /** How solid the background is, in percent. */
    val opacity: Int = 100,
    /** Cover without its cover is a plain player. The other two are made of covers and always show them. */
    val showArtwork: Boolean = true,
    val showArtist: Boolean = true,
    val showHeading: Boolean = true,
    val buttons: WidgetButtons = WidgetButtons.ALL,
    val textSize: WidgetTextSize = WidgetTextSize.NORMAL,
    val rounded: Boolean = true,
    /** A heart before previous, where the widget has room for one: see [WidgetLayout.plan]. */
    val showLike: Boolean = true,
) {
    /** The background drawn: the one picked, or the style's own. Full cover is drawn on its cover. */
    val backgroundShown: WidgetBackground get() = background ?: style.ownBackground

    /** What the widget holds in its style. */
    val contentShown: WidgetContent
        get() = when (style) {
            WidgetStyle.SHELF -> if (content == WidgetContent.LIST) WidgetContent.LIST else WidgetContent.BOTH
            else -> WidgetContent.NOW_PLAYING
        }

    /** Whether the cover is drawn: only Cover can do without it. */
    val artworkShown: Boolean get() = style != WidgetStyle.COVER || showArtwork
}

/** What one widget draws at one size. */
enum class WidgetShape {
    /** Only the list's heading, which opens the app: a list too short for a row of its covers. */
    NONE,

    /** Narrower than a title: the cover with play on its corner, or under it when the widget is tall. Full cover: a glass circle on it. */
    TILE,

    /** One line: the cover, the song and the buttons side by side. Full cover: the line over its cover, the buttons in glass at the end. */
    LINE,

    /** Cover, wide: the cover at the start, the song beside it and the buttons under the song. */
    SIDE,

    /** Cover, square or tall: the cover across the top, the song and the buttons under it. */
    TOP,

    /** Full cover: the cover is the widget, the song and the glass bar along its bottom. */
    POSTER,

    /** Full cover, wide and not tall: the song at the top, the glass bar at the bottom. */
    BANNER,

    /** Shelf: what is playing on top, the list as covers under it. */
    SHELF,

    /** Shelf holding a list alone: its heading and its covers. */
    GRID,

    /** A tablet's width (Cover and Full cover): what is playing, and Up next beside it. */
    PANES,
}

/** Full cover's one pane of glass, in dp from the widget's top left: the bar of buttons, or the circle round play. */
data class GlassPane(val x: Int, val y: Int, val w: Int, val h: Int, val circle: Boolean = false)

/**
 * Everything one widget draws at one size, in dp, decided before a single view exists.
 *
 * Glance cannot measure: it lays out what it is given and clips whatever does not fit. So the sizes
 * are worked out here from the size and the settings alone, and [MusicWidget] draws exactly these
 * numbers. That is what lets a test walk every size a launcher can hand out, in every style, and
 * prove nothing is ever cut off. Each shape uses the fields it needs and leaves the rest alone.
 */
data class WidgetPlan(
    val style: WidgetStyle,
    val shape: WidgetShape,
    val padH: Int = 0,
    val padV: Int = 0,
    /** The cover beside or over the song, 0 for none. In Full cover it is the whole widget. */
    val coverW: Int = 0,
    val coverH: Int = 0,
    /** Lines of title, 0 for no words at all: a narrow line is its cover and play. */
    val titleLines: Int = 1,
    val titleSp: Int = 15,
    val artistSp: Int = 13,
    val showArtist: Boolean = false,
    val play: Boolean = false,
    val skips: Boolean = false,
    /** The heart before previous: added where it fits by [WidgetLayout.plan] to a plan that is otherwise final. */
    val like: Boolean = false,
    /** Shuffle after next, where a row of buttons has room for five. */
    val shuffle: Boolean = false,
    /** The side of every button but play. */
    val button: Int = WidgetLayout.BUTTON_DP,
    /** The side of play: a filled circle in Cover and Shelf, a larger glyph in Full cover's glass. */
    val playSize: Int = WidgetLayout.BUTTON_DP,
    /** Between two buttons. In a spread row, the room the row has shared out. */
    val gap: Int = WidgetLayout.BUTTON_SPACING_DP,
    /**
     * A spread row: across the width, play in its middle, in [slots] places. With five, the first is
     * the heart's (a blank without it) and the last shuffle's (a blank without it), so previous, play
     * and next are in the same places whether the heart is there or not.
     */
    val slots: Int = 0,
    /** TILE: play under the cover rather than on its corner. */
    val below: Boolean = false,
    /** Full cover's glass. */
    val glass: GlassPane? = null,
    /** Shelf: the line of what is playing on top, and whether the buttons are at its end or on a line under it. */
    val headerHeight: Int = 0,
    val headerCover: Int = 0,
    val buttonsInline: Boolean = false,
    /** The list's heading, 0 for none. */
    val headingHeight: Int = 0,
    /** Shelf: covers [tile] dp square, [columns] across, [shelfRows] down, with their names under them or not. */
    val tile: Int = 0,
    val columns: Int = 0,
    val shelfRows: Int = 0,
    val captions: Boolean = false,
    val shelfGap: Int = 0,
    /** How many covers the shelf shows: never more than the list holds. */
    val shown: Int = 0,
    /** PANES: Up next's pane, as wide as this, its rows and their height. */
    val paneW: Int = 0,
    val upNext: Int = 0,
    val upNextRow: Int = 0,
) {
    val heading: Boolean get() = headingHeight > 0
}

object WidgetLayout {
    /** Narrower than this and there is no room for words beside a picture. */
    const val TINY_WIDTH_DP = 110

    /** Shorter than this and the widget is one line: the song and its buttons side by side. */
    const val LINE_HEIGHT_DP = 100

    /** Shorter than this, a line has small buttons: a single cell on most launchers. */
    const val SLIM_HEIGHT_DP = 64

    /** From this width and height a widget is a tablet's (or a foldable's inside): Up next goes beside what is playing. */
    const val PANES_WIDTH_DP = 500
    const val PANES_HEIGHT_DP = 150

    const val BUTTON_DP = 44
    const val SLIM_BUTTON_DP = 36

    /** Between two buttons laid one after the other. */
    const val BUTTON_SPACING_DP = 4

    /** Between a cover and the words beside it, and between the words and the buttons. */
    const val COVER_GAP_DP = 10
    const val SMALL_GAP_DP = 6

    /** Between Cover's cover and its column of words and buttons. */
    const val SIDE_GAP_DP = 14

    /**
     * The words of a line never get less than this. Under it a title is a few letters and an
     * ellipsis, and the line is better as its cover and play: the skips go first, then the words.
     */
    const val WORDS_MIN_DP = 96

    /** What the words of a line keep beside the heart. */
    const val LIKE_WORDS_MIN_DP = 96

    /** Cover's column of words and buttons is never narrower than this: the cover gives way first. */
    const val COLUMN_MIN_DP = 110

    /** Under this, Cover's cover is a thumbnail, and the widget is better as a line. */
    const val SIDE_COVER_MIN_DP = 72
    const val TOP_COVER_MIN_DP = 48

    /** Around a single cell's cover. */
    const val TILE_PAD_DP = 6

    /** Never more than this many songs in a list, however big the widget is. */
    const val MAX_PICKS = 6

    /** The most Up next shows: the next four of the queue. */
    const val MAX_UP_NEXT = 4

    /** The size a shelf's covers are made close to, the most they grow to, and the least they shrink to. */
    const val SHELF_PREF_DP = 80
    const val SHELF_MAX_DP = 120
    const val SHELF_MIN_DP = 36

    /** A shelf's covers have the song's name under them from this size. */
    const val CAPTION_MIN_DP = 48

    /** At most this many rows of covers: past that it is a list, not a shelf. */
    const val MAX_SHELF_ROWS = 3

    /** A row of Up next, and its cover. */
    const val UP_NEXT_ROW_DP = 52
    const val UP_NEXT_COVER_DP = 40

    /** Between the two panes. */
    const val PANE_GAP_DP = 16

    /** A spread row's buttons are at least this far apart, at most the second; shuffle needs the third. */
    const val SPREAD_MIN_DP = 8
    const val SPREAD_MAX_DP = 48
    const val SHUFFLE_SPREAD_DP = 16

    /** Inside a glass bar, at its ends and above and below the buttons. */
    const val GLASS_PAD_H_DP = 14
    const val GLASS_PAD_V_DP = 3

    /**
     * The height of a line of text, in dp, at a size in sp under a text scale. Roboto's line is about
     * 1.33 of its size; the rounding is up, so a guess errs towards room to spare.
     */
    fun line(sp: Int, scale: Float): Int = ceil(sp * scale * 1.34f).toInt()

    /**
     * What to draw at this size. [available] is how many songs the chosen list holds, [upNext] how
     * many songs the queue has after the one playing, and [fontScale] is the phone's own text size,
     * which Glance applies to every sp on top of the widget's setting.
     */
    fun plan(
        widthDp: Int,
        heightDp: Int,
        settings: WidgetSettings,
        available: Int,
        fontScale: Float = 1f,
        upNext: Int = 0,
    ): WidgetPlan {
        val w = widthDp.coerceAtLeast(1)
        val h = heightDp.coerceAtLeast(1)
        val scale = settings.textSize.scale * fontScale.coerceIn(0.5f, 3f)
        val shaped = shape(w, h, settings, available.coerceAtLeast(0), scale, upNext.coerceIn(0, MAX_UP_NEXT))
        return withLike(shaped, w, settings)
    }

    /** Every shape but the heart, which [withLike] adds where it fits. */
    private fun shape(w: Int, h: Int, s: WidgetSettings, available: Int, scale: Float, upNext: Int): WidgetPlan {
        if (s.contentShown == WidgetContent.LIST) {
            return grid(w, h, s, available, scale) ?: WidgetPlan(s.style, WidgetShape.NONE, padH = 12, padV = 10)
        }
        if (w < TINY_WIDTH_DP) return tile(w, h, s)
        if (h < LINE_HEIGHT_DP) return line(w, h, s, scale)
        if (s.style == WidgetStyle.SHELF) return shelf(w, h, s, available, scale) ?: line(w, h, s, scale)
        if (w >= PANES_WIDTH_DP && h >= PANES_HEIGHT_DP && upNext > 0) panes(w, h, s, scale, upNext)?.let { return it }
        if (s.style == WidgetStyle.FULL_COVER) {
            // Wide: the song at the top and the bar at the bottom, darkened from the start; otherwise
            // the song and the bar along the bottom, darkened from below.
            return if (w * 10 >= h * 17) banner(w, h, s, scale) ?: line(w, h, s, scale)
            else poster(w, h, s, scale) ?: line(w, h, s, scale)
        }
        // Cover: beside where the cover can be as tall as the widget, on top where it can be at
        // least half as tall as it is wide, and otherwise whichever of the two fits.
        val side = side(w, h, s, scale)
        val top = top(w, h, s, scale)
        val fills = side != null && (side.coverW == 0 || side.coverH * 20 >= (h - 2 * side.padH) * 17)
        return when {
            side != null && fills && w * 4 >= h * 5 -> side
            top != null && top.coverH * 11 >= top.coverW * 5 -> top
            else -> side ?: top
        } ?: line(w, h, s, scale)
    }

    /** How wide a row of buttons laid one after the other is. */
    fun rowWidth(like: Boolean, skips: Boolean, play: Boolean, shuffle: Boolean, button: Int, playSize: Int, gap: Int): Int {
        val others = (if (like) 1 else 0) + (if (skips) 2 else 0) + (if (shuffle) 1 else 0)
        val count = others + if (play) 1 else 0
        if (count == 0) return 0
        return others * button + (if (play) playSize else 0) + (count - 1) * gap
    }

    /** The row of buttons a plan lays one after the other, heart and shuffle included. */
    fun rowWidth(p: WidgetPlan): Int = rowWidth(p.like, p.skips, p.play, p.shuffle, p.button, p.playSize, p.gap)

    /** A spread row's least width: its slots at [SPREAD_MIN_DP] apart. */
    private fun spreadWidth(slots: Int, button: Int, playSize: Int): Int =
        if (slots <= 0) 0 else (slots - 1) * button + playSize + (slots - 1) * SPREAD_MIN_DP

    private data class Spread(val skips: Boolean, val slots: Int, val shuffle: Boolean, val gap: Int)

    /**
     * Previous, play and next across [room], play in the middle: five places where there is room for
     * them (the heart's, and shuffle's when [roomy] and there is room for that too), else three, else
     * play alone.
     */
    private fun spread(room: Int, s: WidgetSettings, button: Int, playSize: Int, roomy: Boolean): Spread {
        if (s.buttons == WidgetButtons.NONE) return Spread(false, 0, false, 0)
        if (s.buttons == WidgetButtons.PLAY_ONLY) return Spread(false, 1, false, 0)
        val five = 4 * button + playSize
        val three = 2 * button + playSize
        return when {
            room >= five + 4 * SPREAD_MIN_DP ->
                Spread(true, 5, roomy && room >= five + 4 * SHUFFLE_SPREAD_DP, minOf((room - five) / 4, SPREAD_MAX_DP))
            room >= three + 2 * SPREAD_MIN_DP -> Spread(true, 3, false, minOf((room - three) / 2, SPREAD_MAX_DP))
            else -> Spread(false, 1, false, 0)
        }
    }

    /** Narrower than a title: the cover and play. */
    private fun tile(w: Int, h: Int, s: WidgetSettings): WidgetPlan {
        val play = s.buttons != WidgetButtons.NONE
        val small = minOf(w, h)
        if (s.style == WidgetStyle.FULL_COVER) {
            // The cover is the tile, and play a circle of glass in the middle of it.
            val d = (small * 0.47f).roundToInt().coerceIn(30, 52)
            val withPlay = play && small >= 56
            return WidgetPlan(
                s.style, WidgetShape.TILE,
                coverW = w, coverH = h, titleLines = 0,
                play = withPlay, button = d, playSize = d,
                glass = if (withPlay) GlassPane((w - d) / 2, (h - d) / 2, d, d, circle = true) else null,
            )
        }
        val pad = TILE_PAD_DP
        val side = w - 2 * pad
        val under = minOf(40, side)
        // Narrow and tall: the cover keeps its shape and play goes under it, rather than the cover
        // being stretched into a strip.
        if (play && side >= 40 && h >= 2 * pad + side + SMALL_GAP_DP + under) {
            val cover = if (s.artworkShown) side else 0
            val used = (if (cover > 0) cover + SMALL_GAP_DP else 0) + under
            return WidgetPlan(
                s.style, WidgetShape.TILE,
                padH = pad, padV = (h - used) / 2,
                coverW = cover, coverH = cover, titleLines = 0,
                play = true, button = under, playSize = under, below = true,
            )
        }
        // Play on the cover's corner, and only if the cover can spare the room: on a single small
        // cell the whole tile opens the app instead.
        val p = (small * 0.36f).roundToInt().coerceIn(24, 36)
        return WidgetPlan(
            s.style, WidgetShape.TILE,
            padH = pad, padV = pad,
            coverW = if (s.artworkShown) w - 2 * pad else 0,
            coverH = if (s.artworkShown) h - 2 * pad else 0,
            titleLines = 0,
            play = play && small >= 56, button = p, playSize = p,
        )
    }

    /**
     * One line. The cover comes first, then the words, then previous and next: a line too short for
     * all three keeps its cover and play (the mockup's two by one), and the skips go before the words
     * do. Full cover's line is its cover, the words over its start and the buttons in glass at its end.
     */
    private fun line(w: Int, h: Int, s: WidgetSettings, scale: Float): WidgetPlan {
        val slim = h < SLIM_HEIGHT_DP
        val padV = if (slim) 4 else 6
        val inner = (h - 2 * padV).coerceAtLeast(1)
        val titleSp = if (slim) 14 else 15
        val artistSp = if (slim) 12 else 13
        val t = line(titleSp, scale)
        val a = line(artistSp, scale)
        val play = s.buttons != WidgetButtons.NONE
        val skipsWanted = s.buttons == WidgetButtons.ALL
        val artist = s.showArtist && t + a <= inner
        if (s.style == WidgetStyle.FULL_COVER) {
            val padH = 12
            val button = minOf(if (slim) SLIM_BUTTON_DP else 40, inner - 2 * GLASS_PAD_V_DP).coerceAtLeast(24)
            val circle = minOf(44, inner)
            fun bar(skips: Boolean) = rowWidth(false, skips, true, false, button, button, BUTTON_SPACING_DP) + 2 * GLASS_PAD_H_DP
            val words = { glassW: Int -> w - 2 * padH - glassW - (if (glassW > 0) SMALL_GAP_DP else 0) }
            data class GlassTry(val words: Boolean, val skips: Boolean, val circle: Boolean)
            val tries = buildList {
                if (skipsWanted) add(GlassTry(true, true, false))
                if (play) add(GlassTry(true, false, true)) else add(GlassTry(true, false, false))
                if (play) add(GlassTry(false, false, true))
            }
            for (tr in tries) {
                val gw = when {
                    tr.skips -> bar(true)
                    tr.circle -> circle
                    else -> 0
                }
                val gh = if (tr.skips) button + 2 * GLASS_PAD_V_DP else circle
                if (tr.words && words(gw) < WORDS_MIN_DP) continue
                if (!tr.words && 2 * padH + gw > w) continue
                return WidgetPlan(
                    s.style, WidgetShape.LINE,
                    padH = padH, padV = padV,
                    coverW = w, coverH = h,
                    titleLines = if (tr.words) 1 else 0, titleSp = titleSp, artistSp = artistSp,
                    showArtist = tr.words && artist,
                    play = play, skips = tr.skips,
                    button = if (tr.skips) button else circle, playSize = if (tr.skips) button else circle,
                    glass = if (gw > 0) GlassPane(w - padH - gw, (h - gh) / 2, gw, gh, circle = !tr.skips) else null,
                )
            }
            return WidgetPlan(s.style, WidgetShape.LINE, padH = padH, padV = padV, coverW = w, coverH = h, titleLines = 0)
        }
        val padH = 10
        val button = minOf(if (slim) SLIM_BUTTON_DP else BUTTON_DP, inner)
        val playSize = minOf(if (slim) SLIM_BUTTON_DP else 40, inner)
        val coverWanted = if (s.artworkShown) minOf(inner, 72).takeIf { it >= 32 } ?: 0 else 0
        data class LineTry(val cover: Int, val words: Boolean, val skips: Boolean)
        val tries = buildList {
            if (coverWanted > 0) {
                if (skipsWanted) add(LineTry(coverWanted, true, true))
                add(LineTry(coverWanted, true, false))
                // The cover and play, or with no buttons the cover alone: never an empty line.
                add(LineTry(coverWanted, false, false))
            }
            if (skipsWanted) add(LineTry(0, true, true))
            add(LineTry(0, true, false))
            if (play) add(LineTry(0, false, false))
        }
        for (tr in tries) {
            val controls = rowWidth(false, tr.skips, play, false, button, playSize, BUTTON_SPACING_DP)
            val coverPart = if (tr.cover > 0) tr.cover + COVER_GAP_DP else 0
            if (tr.words) {
                val words = w - 2 * padH - coverPart - controls - (if (play) SMALL_GAP_DP else 0)
                if (words < WORDS_MIN_DP) continue
            } else if (2 * padH + (if (tr.cover > 0) tr.cover + SMALL_GAP_DP else 0) + controls > w) {
                continue
            }
            return WidgetPlan(
                s.style, WidgetShape.LINE,
                padH = padH, padV = padV,
                coverW = tr.cover, coverH = tr.cover,
                titleLines = if (tr.words) 1 else 0, titleSp = titleSp, artistSp = artistSp,
                showArtist = tr.words && artist,
                play = play, skips = tr.skips, button = button, playSize = playSize,
            )
        }
        return WidgetPlan(s.style, WidgetShape.LINE, padH = padH, padV = padV, titleLines = 0)
    }

    /**
     * Cover, wide: the cover as tall as the widget at its start, the song at the top of a column
     * beside it and the buttons at the bottom of that column (the mockup's four by two and four by
     * three). The cover gives way to the column, never the other way round. With no cover wanted,
     * the column is the widget.
     */
    private fun side(w: Int, h: Int, s: WidgetSettings, scale: Float): WidgetPlan? {
        val pad = if (minOf(w, h) >= 300) 14 else 12
        val ih = h - 2 * pad
        val play = s.buttons != WidgetButtons.NONE
        val skipsWanted = s.buttons == WidgetButtons.ALL
        val button = 40
        val playSize = 48
        val controlsH = if (play) maxOf(button, playSize) else 0
        for (skips in listOf(skipsWanted, false).distinct()) {
            // The heart's room is kept in the column whether or not it is wanted, so turning it off
            // changes nothing else: see withLike.
            val controls = rowWidth(skips, skips, play, false, button, playSize, BUTTON_SPACING_DP)
            val column = maxOf(controls, COLUMN_MIN_DP)
            val cover = if (s.artworkShown) minOf(ih, w - 2 * pad - SIDE_GAP_DP - column) else 0
            if (s.artworkShown && cover < SIDE_COVER_MIN_DP) continue
            if (!s.artworkShown && w - 2 * pad < column) continue
            val colH = if (cover > 0) cover else ih
            val titleSp = if (colH >= 150) 17 else 15
            val t = line(titleSp, scale)
            val a = line(13, scale)
            val under = if (play) 8 + controlsH else 0
            if (t + under > colH) continue
            // A column with no room for the artist is a flat widget, better as a line, which has.
            if (s.showArtist && t + a + under > colH) return null
            val lines = if (colH >= 190 && 2 * t + (if (s.showArtist) a else 0) + under <= colH) 2 else 1
            return WidgetPlan(
                s.style, WidgetShape.SIDE,
                padH = pad, padV = (h - colH) / 2,
                coverW = cover, coverH = cover,
                titleLines = lines, titleSp = titleSp, artistSp = 13, showArtist = s.showArtist,
                play = play, skips = skips, button = button, playSize = playSize,
            )
        }
        return null
    }

    /**
     * Cover, square or tall: the cover across the top, as tall as the room under it leaves and never
     * taller than wide, the song under it and the buttons spread under that (the mockup's two by two,
     * two by three and four by four). Shuffle comes in on a four by four, where five buttons have room.
     */
    private fun top(w: Int, h: Int, s: WidgetSettings, scale: Float): WidgetPlan? {
        if (!s.artworkShown) return null
        val small = w < 200
        val big = minOf(w, h) >= 300
        val pad = if (big) 14 else 10
        val iw = w - 2 * pad
        val ih = h - 2 * pad
        val titleSp = when {
            small -> 13
            big -> 17
            else -> 15
        }
        val artistSp = if (small) 11 else 13
        val button = when {
            small -> 36
            big -> BUTTON_DP
            else -> 40
        }
        val playSize = when {
            small -> 40
            big -> 56
            else -> 48
        }
        val play = s.buttons != WidgetButtons.NONE
        val row = spread(iw, s, button, playSize, roomy = big)
        val controlsH = if (play) maxOf(button, playSize) else 0
        val t = line(titleSp, scale)
        val a = line(artistSp, scale)
        fun cover(artist: Boolean) = ih - 8 - t - (if (artist) a else 0) - (if (play) SMALL_GAP_DP + controlsH else 0)
        val artist = s.showArtist && cover(true) >= TOP_COVER_MIN_DP
        val coverH = minOf(cover(artist), iw)
        if (coverH < TOP_COVER_MIN_DP) return null
        val used = coverH + 8 + t + (if (artist) a else 0) + (if (play) SMALL_GAP_DP + controlsH else 0)
        return WidgetPlan(
            s.style, WidgetShape.TOP,
            padH = pad, padV = (h - used) / 2,
            coverW = iw, coverH = coverH,
            titleLines = 1, titleSp = titleSp, artistSp = artistSp, showArtist = artist,
            play = play, skips = row.skips, shuffle = row.shuffle,
            button = button, playSize = playSize, gap = row.gap, slots = row.slots,
        )
    }

    /**
     * Full cover's glass: a bar of previous, play and next as wide as its buttons, or the circle round
     * play where only play was asked for or the bar has no room in [room]. Null with no buttons.
     */
    private fun glassFor(room: Int, s: WidgetSettings, button: Int, gap: Int, padH: Int = GLASS_PAD_H_DP): Triple<Int, Int, Boolean>? {
        if (s.buttons == WidgetButtons.NONE) return null
        val bar = rowWidth(false, true, true, false, button, button, gap) + 2 * padH
        if (s.buttons == WidgetButtons.ALL && bar <= room) return Triple(bar, button + 2 * GLASS_PAD_V_DP, true)
        val circle = button + 2 * GLASS_PAD_V_DP
        return if (circle <= room) Triple(circle, circle, false) else null
    }

    /** Inside a glass bar at its ends: less on a small widget, where every dp of the bar counts. */
    fun glassPadH(p: WidgetPlan): Int = if (p.shape == WidgetShape.POSTER && p.coverW < 200) 8 else GLASS_PAD_H_DP

    /** Full cover: the cover is the widget, darkened towards the bottom, the song over it and the glass bar under the song. */
    private fun poster(w: Int, h: Int, s: WidgetSettings, scale: Float): WidgetPlan? {
        val small = w < 200
        val big = minOf(w, h) >= 300
        val pad = when {
            small -> 8
            big -> 16
            else -> 12
        }
        val button = when {
            small -> 36
            big -> 44
            else -> 40
        }
        val gap = when {
            small -> 2
            big -> 12
            else -> 8
        }
        val titleSp = when {
            small -> 13
            big -> 19
            else -> 16
        }
        val artistSp = when {
            small -> 11
            big -> 13
            else -> 12
        }
        val glass = glassFor(w - 2 * pad, s, button, gap, padH = if (small) 8 else GLASS_PAD_H_DP)
        if (s.buttons != WidgetButtons.NONE && glass == null) return null
        val gh = glass?.second ?: 0
        if (gh > h - 2 * pad) return null
        val t = line(titleSp, scale)
        val a = line(artistSp, scale)
        val room = h - 2 * pad - (if (gh > 0) gh + 8 else 0)
        val words = t <= room
        // A small poster is its title, as the mockup's two by two: the artist would be a smudge.
        val artist = words && s.showArtist && !small && t + a <= room
        return WidgetPlan(
            s.style, WidgetShape.POSTER,
            padH = pad, padV = pad,
            coverW = w, coverH = h,
            titleLines = if (words) 1 else 0, titleSp = titleSp, artistSp = artistSp, showArtist = artist,
            play = glass != null, skips = glass?.third == true,
            button = button, playSize = button, gap = if (glass?.third == true) gap else 0,
            glass = glass?.let { (gw, gh2, isBar) -> GlassPane((w - gw) / 2, h - pad - gh2, gw, gh2, circle = !isBar) },
        )
    }

    /** Full cover, wide: darkened towards its start, the song at the top and the glass bar at the bottom (the mockup's four by two). */
    private fun banner(w: Int, h: Int, s: WidgetSettings, scale: Float): WidgetPlan? {
        val pad = 14
        val button = 40
        val gap = 8
        val titleSp = 16
        val artistSp = 12
        val glass = glassFor(w - 2 * pad, s, button, gap)
        if (s.buttons != WidgetButtons.NONE && glass == null) return null
        val gh = glass?.second ?: 0
        val t = line(titleSp, scale)
        val a = line(artistSp, scale)
        val room = h - 2 * pad - (if (gh > 0) gh + 8 else 0)
        // With no room for the artist it is a flat widget, better as a line, which has.
        if (t + (if (s.showArtist) a else 0) > room) return null
        val artist = s.showArtist
        return WidgetPlan(
            s.style, WidgetShape.BANNER,
            padH = pad, padV = pad,
            coverW = w, coverH = h,
            titleLines = 1, titleSp = titleSp, artistSp = artistSp, showArtist = artist,
            play = glass != null, skips = glass?.third == true,
            button = button, playSize = button, gap = if (glass?.third == true) gap else 0,
            glass = glass?.let { (gw, gh2, isBar) -> GlassPane(pad, h - pad - gh2, gw, gh2, circle = !isBar) },
        )
    }

    /** A shelf of covers: how many across and down, and how big. */
    internal data class ShelfGrid(val columns: Int, val rows: Int, val tile: Int, val captions: Boolean, val gap: Int) {
        fun height(caption: Int): Int = rows * (tile + if (captions) caption else 0) + (rows - 1) * gap
        val width: Int get() = columns * tile + (columns - 1) * gap
    }

    /** The height of a cover's name under it in a shelf. */
    fun captionHeight(scale: Float): Int = line(12, scale) + 3

    /**
     * Covers to fill [room] by [height], the way the approved mockups lay them out:
     *
     * - About [SHELF_PREF_DP] across, as many as the width holds (four on a phone's four cells,
     *   six on a tablet), in full rows only: six songs over four columns are one row of four, never
     *   four and two.
     * - The song's name under each cover of a grid of rows, the covers a little smaller for them,
     *   and under a single row where they fit as it is (the four by three; not the four by two).
     * - A narrow widget with room for a second row under the first has two rows of smaller covers
     *   (the two by three); a wide one has one row of three bigger covers instead of four (the four
     *   by four), and two rows of them where the height holds both, so a One UI five by four shows
     *   all six songs.
     * - A short strip has covers as tall as it, and more of them across (the two by two).
     *
     * Null when not even a row of small covers fits.
     */
    internal fun shelfGrid(room: Int, height: Int, available: Int, maxRows: Int, caption: Int, gap: Int): ShelfGrid? {
        if (available <= 0 || height < SHELF_MIN_DP || room < SHELF_MIN_DP) return null
        val n0 = ((room + gap).toFloat() / (SHELF_PREF_DP + gap)).roundToInt().coerceIn(2, 6)
        val ceiling = if (maxRows > 0) minOf(maxRows, MAX_SHELF_ROWS) else MAX_SHELF_ROWS
        fun rowsFor(n: Int) = minOf(ceiling, maxOf(1, available / n))
        fun across(n: Int) = (room - (n - 1) * gap) / n
        // A short strip: covers as tall as it, and as many as fill the width.
        if (height < minOf(across(n0), SHELF_MAX_DP)) {
            val n = ((room + gap).toFloat() / (height + gap)).roundToInt().coerceIn(n0, 6)
            val tile = minOf(height, across(n))
            return if (tile >= SHELF_MIN_DP) ShelfGrid(n, 1, tile, false, gap) else null
        }
        // [n] columns of covers up to [side]: as many full rows as fit, and one more where the
        // covers lose no more than a tenth for it.
        fun fill(n: Int, side: Int, captions: Boolean): ShelfGrid? {
            val cap = if (captions) caption else 0
            var tile = minOf(side, across(n), SHELF_MAX_DP)
            var rows = minOf((height + gap) / (tile + cap + gap), rowsFor(n))
            if (rows == 0) {
                tile = minOf(tile, height - cap)
                rows = 1
            }
            if (rows < rowsFor(n)) {
                val more = (height - rows * gap) / (rows + 1) - cap
                if (more * 10 >= tile * 9) {
                    rows++
                    tile = more
                }
            }
            return if (tile >= (if (captions) CAPTION_MIN_DP else SHELF_MIN_DP)) ShelfGrid(n, rows, tile, captions, gap) else null
        }
        // The names: under every cover of a grid of rows, the covers a little smaller for them; under
        // a single row only where they fit as they are, which a short widget has no room for.
        val bare = fill(n0, across(n0), captions = false) ?: return null
        val first = when {
            bare.rows >= 2 -> {
                val tile = minOf(across(n0), SHELF_MAX_DP, (height - (bare.rows - 1) * gap) / bare.rows - caption)
                if (tile >= CAPTION_MIN_DP) bare.copy(tile = tile, captions = true) else bare
            }
            height - bare.tile >= caption && bare.tile >= CAPTION_MIN_DP -> bare.copy(captions = true)
            else -> bare
        }
        val cap = if (first.captions) caption else 0
        val roomy = first.rows == 1 && (height - first.tile - cap) * 5 >= (first.tile + cap + gap) * 3
        if (!roomy) return first
        if (rowsFor(n0) >= 2) {
            // Narrow: two rows of smaller covers fill the room better than one row of big ones.
            val two = (height - gap - 2 * cap) / 2
            return if (two * 4 >= first.tile * 3 && two >= (if (first.captions) CAPTION_MIN_DP else SHELF_MIN_DP)) first.copy(rows = 2, tile = two) else first
        }
        if (n0 in 3..4) {
            // Wide, and six songs make no second full row of four: bigger covers, three across.
            val bigger = fill(n0 - 1, SHELF_MAX_DP, captions = first.captions)
            if (bigger != null && bigger.tile > first.tile) return bigger
        }
        return first
    }

    /** The list's heading over a shelf, with the room under it. */
    fun headingHeight(scale: Float): Int = line(12, scale) + 6

    /**
     * A shelf of [room] by [height], with the list's heading over it where the shelf leaves room
     * for it: the heading never costs a cover or shrinks one.
     */
    private fun shelfUnder(room: Int, height: Int, s: WidgetSettings, available: Int, scale: Float, gap: Int): Pair<ShelfGrid, Int>? {
        val caption = captionHeight(scale)
        val grid = shelfGrid(room, height, available, s.maxRows, caption, gap) ?: return null
        val heading = headingHeight(scale)
        return grid to if (s.showHeading && grid.height(caption) + heading <= height) heading else 0
    }

    /**
     * Shelf: what is playing in a line on top, its buttons at the end of that line on a wide widget
     * and on a line of their own under it on a narrow or a tall one (the mockup's four by four), and
     * the list as covers under that, each one tapped to play it. Null when not a row of covers fits:
     * the widget is then the line of what is playing on its own.
     */
    private fun shelf(w: Int, h: Int, s: WidgetSettings, available: Int, scale: Float): WidgetPlan? {
        val small = w < 200
        val narrow = w < 300
        val big = minOf(w, h) >= 300
        val pad = when {
            small -> 10
            big -> 14
            else -> 12
        }
        val iw = w - 2 * pad
        val ih = h - 2 * pad
        val ownRow = narrow || (h >= 300 && h * 5 >= w * 4)
        val headerCover = when {
            small -> 32
            narrow -> 40
            ownRow -> 56
            else -> 48
        }
        val titleSp = when {
            small -> 12
            narrow -> 13
            else -> 15
        }
        val artistSp = if (narrow) 11 else 12
        val button = if (small) 36 else 40
        val playSize = when {
            small -> 32
            ownRow && !narrow -> 48
            else -> 36
        }
        val t = line(titleSp, scale)
        val a = line(artistSp, scale)
        val play = s.buttons != WidgetButtons.NONE
        val gap = if (small) 6 else 8
        val plan: WidgetPlan
        val top: Int
        if (!ownRow) {
            val controls = rowWidth(false, s.buttons == WidgetButtons.ALL, play, false, button, playSize, BUTTON_SPACING_DP)
            val skips = s.buttons == WidgetButtons.ALL &&
                iw - headerCover - COVER_GAP_DP - controls - SMALL_GAP_DP >= WORDS_MIN_DP
            val controlsH = if (play) maxOf(button, playSize) else 0
            val header = maxOf(headerCover, controlsH)
            val artist = s.showArtist && t + a <= header
            if (t > header) return null
            top = header + 10
            plan = WidgetPlan(
                s.style, WidgetShape.SHELF,
                padH = pad, titleSp = titleSp, artistSp = artistSp, showArtist = artist,
                play = play, skips = skips, button = button, playSize = playSize,
                headerHeight = header, headerCover = headerCover, buttonsInline = true,
            )
        } else {
            val header = maxOf(headerCover, t)
            val artist = s.showArtist && t + a <= header
            val row = spread(iw, s, button, playSize, roomy = !narrow)
            val controlsH = if (play) maxOf(button, playSize) else 0
            top = header + (if (play) SMALL_GAP_DP + controlsH else 0) + 8
            plan = WidgetPlan(
                s.style, WidgetShape.SHELF,
                padH = pad, titleSp = titleSp, artistSp = artistSp, showArtist = artist,
                play = play, skips = row.skips, shuffle = row.shuffle, button = button, playSize = playSize,
                gap = row.gap, slots = row.slots, headerHeight = header, headerCover = headerCover,
            )
        }
        val (grid, heading) = shelfUnder(iw, ih - top, s, available, scale, gap) ?: return null
        val used = top + heading + grid.height(captionHeight(scale))
        return plan.copy(
            padV = (h - used) / 2,
            headingHeight = heading,
            tile = grid.tile, columns = grid.columns, shelfRows = grid.rows, captions = grid.captions,
            shelfGap = grid.gap, shown = minOf(available, grid.columns * grid.rows),
        )
    }

    /** Shelf with a list alone: the list's heading and its covers. */
    private fun grid(w: Int, h: Int, s: WidgetSettings, available: Int, scale: Float): WidgetPlan? {
        val pad = if (w < 200) 10 else 12
        val gap = if (w < 200) 6 else 8
        val (grid, heading) = shelfUnder(w - 2 * pad, h - 2 * pad, s, available, scale, gap) ?: return null
        val used = heading + grid.height(captionHeight(scale))
        return WidgetPlan(
            s.style, WidgetShape.GRID,
            padH = pad, padV = (h - used) / 2, titleLines = 0,
            headingHeight = heading,
            tile = grid.tile, columns = grid.columns, shelfRows = grid.rows, captions = grid.captions,
            shelfGap = grid.gap, shown = minOf(available, grid.columns * grid.rows),
        )
    }

    /** The height of a row of Up next: its cover, or its two lines of words with room round them. */
    fun upNextRowHeight(scale: Float): Int = maxOf(UP_NEXT_ROW_DP, line(13, scale) + line(12, scale) + 8)

    /** Up next's heading. */
    fun upNextHeading(scale: Float): Int = line(12, scale) + 8

    /**
     * A tablet: what is playing on the left, in Cover's wide shape or over Full cover's cover with
     * the glass bar at the bottom, and Up next on the right, the next songs of the queue, each one
     * tapped to play the queue on from it (the mockup approved for tablets). Shelf is not drawn this
     * way: its tablet shape is not decided, and it stays its four by three, wider.
     */
    private fun panes(w: Int, h: Int, s: WidgetSettings, scale: Float, upNext: Int): WidgetPlan? {
        val pad = 14
        val ih = h - 2 * pad
        val paneW = (w * 0.28f).roundToInt().coerceIn(180, 280)
        val rowH = upNextRowHeight(scale)
        val rows = minOf(upNext, MAX_UP_NEXT, (ih - upNextHeading(scale)) / rowH)
        if (rows <= 0) return null
        val left = w - 2 * pad - PANE_GAP_DP - paneW
        val play = s.buttons != WidgetButtons.NONE
        val t = line(18, scale)
        val a = line(13, scale)
        val base = WidgetPlan(
            s.style, WidgetShape.PANES,
            padH = pad, padV = pad, paneW = paneW, upNext = rows, upNextRow = rowH, headingHeight = upNextHeading(scale),
        )
        if (s.style == WidgetStyle.FULL_COVER) {
            val button = 44
            val gap = 12
            val glass = glassFor(left, s, button, gap)
            if (s.buttons != WidgetButtons.NONE && glass == null) return null
            val gh = glass?.second ?: 0
            val room = ih - (if (gh > 0) gh + 8 else 0)
            if (t > room) return null
            return base.copy(
                coverW = w, coverH = h,
                titleLines = 1, titleSp = 18, artistSp = 13, showArtist = s.showArtist && t + a <= room,
                play = glass != null, skips = glass?.third == true,
                button = button, playSize = button, gap = if (glass?.third == true) gap else 0,
                glass = glass?.let { (gw, gh2, isBar) -> GlassPane(pad, h - pad - gh2, gw, gh2, circle = !isBar) },
            )
        }
        val button = 40
        val playSize = 52
        val gap = BUTTON_SPACING_DP
        val controlsH = if (play) maxOf(button, playSize) else 0
        val skipsWanted = s.buttons == WidgetButtons.ALL
        for (skips in listOf(skipsWanted, false).distinct()) {
            // The heart's room is kept as in SIDE. Shuffle gets its room only where the cover keeps
            // three quarters of the height with it: the cover is the point of the style.
            val withShuffle = maxOf(rowWidth(skips, skips, play, skips, button, playSize, gap), COLUMN_MIN_DP)
            val column = maxOf(rowWidth(skips, skips, play, false, button, playSize, gap), COLUMN_MIN_DP)
            val roomy = skips && s.artworkShown && minOf(ih, left - SIDE_GAP_DP - withShuffle) * 4 >= ih * 3
            val cover = if (s.artworkShown) minOf(ih, left - SIDE_GAP_DP - if (roomy) withShuffle else column) else 0
            if (s.artworkShown && cover < SIDE_COVER_MIN_DP) continue
            if (!s.artworkShown && left < column) continue
            val colH = if (cover > 0) cover else ih
            val col = left - (if (cover > 0) cover + SIDE_GAP_DP else 0)
            val under = if (play) 8 + controlsH else 0
            if (t + under > colH) continue
            val shuffle = skips && rowWidth(true, true, true, true, button, playSize, gap) <= col
            return base.copy(
                padV = (h - maxOf(colH, upNextHeading(scale) + rows * rowH)) / 2,
                coverW = cover, coverH = cover,
                titleLines = if (colH >= 190 && 2 * t + a + under <= colH) 2 else 1,
                titleSp = 18, artistSp = 13, showArtist = s.showArtist && t + a + under <= colH,
                play = play, skips = skips, shuffle = shuffle, button = button, playSize = playSize, gap = gap,
            )
        }
        return null
    }

    /** How wide the words of a line of song are left. */
    fun lineWords(p: WidgetPlan, w: Int): Int = when {
        p.style == WidgetStyle.FULL_COVER -> w - 2 * p.padH - (p.glass?.let { it.w + SMALL_GAP_DP } ?: 0)
        else -> w - 2 * p.padH - (if (p.coverW > 0) p.coverW + COVER_GAP_DP else 0) - rowWidth(p) - (if (p.play) SMALL_GAP_DP else 0)
    }

    /** Cover's column beside its cover, in SIDE and PANES. */
    fun columnWidth(p: WidgetPlan, w: Int): Int {
        val content = if (p.shape == WidgetShape.PANES) w - 2 * p.padH - PANE_GAP_DP - p.paneW else w - 2 * p.padH
        return content - (if (p.coverW > 0) p.coverW + SIDE_GAP_DP else 0)
    }

    /** Shelf's words in the line on top, beside the buttons when they are there. */
    fun headerWords(p: WidgetPlan, w: Int): Int =
        w - 2 * p.padH - p.headerCover - COVER_GAP_DP - (if (p.buttonsInline) rowWidth(p) + SMALL_GAP_DP else 0)

    /** Full cover's glass, a button wider for the heart: centred on a poster, from the start elsewhere, from the end on a line. */
    private fun glassWithHeart(p: WidgetPlan, w: Int): GlassPane? = p.glass?.let { g ->
        val grown = g.w + p.button + p.gap
        when (p.shape) {
            WidgetShape.POSTER -> g.copy(x = (w - grown) / 2, w = grown)
            WidgetShape.LINE -> g.copy(x = w - p.padH - grown, w = grown)
            else -> g.copy(w = grown)
        }
    }

    /**
     * [plan] with the heart before previous, where it fits without moving or shrinking anything that
     * is there: in a line the words make room for it and keep [LIKE_WORDS_MIN_DP]; in Cover's
     * column it needs the column's room; in a spread row it has its own place (see [WidgetPlan.slots]);
     * in glass the bar grows by a button and must still fit. Never where previous and next are not,
     * so never where play alone or no buttons were asked for.
     */
    private fun withLike(plan: WidgetPlan, w: Int, settings: WidgetSettings): WidgetPlan {
        if (!settings.showLike || !plan.skips) return plan
        val liked = plan.copy(like = true)
        val full = plan.style == WidgetStyle.FULL_COVER
        return when (plan.shape) {
            WidgetShape.LINE ->
                if (full) {
                    val grown = liked.copy(glass = glassWithHeart(plan, w))
                    if (lineWords(grown, w) >= LIKE_WORDS_MIN_DP) grown else plan
                } else if (lineWords(liked, w) >= LIKE_WORDS_MIN_DP) liked else plan
            WidgetShape.SIDE -> if (rowWidth(liked) <= columnWidth(liked, w)) liked else plan
            WidgetShape.TOP -> if (plan.slots == 5) liked else plan
            WidgetShape.SHELF ->
                if (plan.buttonsInline) (if (headerWords(liked, w) >= LIKE_WORDS_MIN_DP) liked else plan)
                else if (plan.slots == 5) liked else plan
            WidgetShape.POSTER, WidgetShape.BANNER -> {
                val grown = glassWithHeart(plan, w) ?: return plan
                if (grown.x >= plan.padH && grown.x + grown.w <= w - plan.padH) liked.copy(glass = grown) else plan
            }
            WidgetShape.PANES ->
                if (full) {
                    val grown = glassWithHeart(plan, w) ?: return plan
                    if (grown.w <= w - 2 * plan.padH - PANE_GAP_DP - plan.paneW) liked.copy(glass = grown) else plan
                } else if (rowWidth(liked) <= columnWidth(liked, w)) liked else plan
            WidgetShape.TILE, WidgetShape.GRID, WidgetShape.NONE -> plan
        }
    }

    /** A spread row's width at its tightest. */
    fun spreadMin(p: WidgetPlan): Int = spreadWidth(p.slots, p.button, p.playSize)
}

/**
 * Where a widget's settings live: its own Glance state, one key each.
 *
 * Strings and ints rather than a serialised blob, so a settings file can be read with a text editor
 * and a key added later costs nothing. Every read falls back, so a widget configured by an older
 * version is never broken by a newer one.
 */
object WidgetKeys {
    val STYLE = stringPreferencesKey("widget_style")
    val CONTENT = stringPreferencesKey("widget_content")
    val LIST = stringPreferencesKey("widget_list")
    val MAX_ROWS = intPreferencesKey("widget_max_rows")
    val BACKGROUND = stringPreferencesKey("widget_background")
    val OPACITY = intPreferencesKey("widget_opacity")
    val SHOW_ARTWORK = booleanPreferencesKey("widget_show_artwork")
    val SHOW_ARTIST = booleanPreferencesKey("widget_show_artist")
    val SHOW_HEADING = booleanPreferencesKey("widget_show_heading")
    val BUTTONS = stringPreferencesKey("widget_buttons")
    val TEXT_SIZE = stringPreferencesKey("widget_text_size")
    val ROUNDED = booleanPreferencesKey("widget_rounded")
    val SHOW_LIKE = booleanPreferencesKey("widget_show_like")

    /**
     * A widget set up before there were styles has no style: it is a Cover, unless it was a list,
     * which stays a list as a Shelf of it. Its background was written whether or not it was ever
     * picked, and "Follow the system" at full strength was the default: that is read as never
     * picked, so it takes the style's own colours like a new widget does. Any other background was
     * a choice, and stays.
     */
    fun read(prefs: Preferences): WidgetSettings {
        val fallback = WidgetSettings()
        val content = WidgetContent.of(prefs[CONTENT])
        val stored = WidgetStyle.of(prefs[STYLE])
        val opacity = (prefs[OPACITY] ?: fallback.opacity).coerceIn(0, 100)
        val background = WidgetBackground.of(prefs[BACKGROUND])
        return WidgetSettings(
            style = stored ?: if (content == WidgetContent.LIST) WidgetStyle.SHELF else WidgetStyle.COVER,
            content = content,
            list = WidgetList.of(prefs[LIST]),
            maxRows = (prefs[MAX_ROWS] ?: fallback.maxRows).coerceIn(0, WidgetLayout.MAX_PICKS),
            background = if (stored == null && background == WidgetBackground.SYSTEM && opacity == 100) null else background,
            opacity = opacity,
            showArtwork = prefs[SHOW_ARTWORK] ?: fallback.showArtwork,
            showArtist = prefs[SHOW_ARTIST] ?: fallback.showArtist,
            showHeading = prefs[SHOW_HEADING] ?: fallback.showHeading,
            buttons = WidgetButtons.of(prefs[BUTTONS]),
            textSize = WidgetTextSize.of(prefs[TEXT_SIZE]),
            rounded = prefs[ROUNDED] ?: fallback.rounded,
            showLike = prefs[SHOW_LIKE] ?: fallback.showLike,
        )
    }

    fun write(prefs: MutablePreferences, settings: WidgetSettings) {
        prefs[STYLE] = settings.style.name
        prefs[CONTENT] = settings.content.name
        prefs[LIST] = settings.list.name
        prefs[MAX_ROWS] = settings.maxRows
        // Never picked is no key at all, so the style's own is what is read back.
        settings.background?.let { prefs[BACKGROUND] = it.name } ?: prefs.remove(BACKGROUND)
        prefs[OPACITY] = settings.opacity
        prefs[SHOW_ARTWORK] = settings.showArtwork
        prefs[SHOW_ARTIST] = settings.showArtist
        prefs[SHOW_HEADING] = settings.showHeading
        prefs[BUTTONS] = settings.buttons.name
        prefs[TEXT_SIZE] = settings.textSize.name
        prefs[ROUNDED] = settings.rounded
        prefs[SHOW_LIKE] = settings.showLike
    }
}

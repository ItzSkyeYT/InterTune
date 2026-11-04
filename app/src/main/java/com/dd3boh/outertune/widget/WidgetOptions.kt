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

    /** What is playing and the list's covers to tap, or the list alone, in one of three looks: see [ShelfLook]. */
    SHELF;

    /**
     * What this style is drawn on when no background was picked: the song's colours. For a Shelf
     * that is its cover itself, blurred and darkened, as all three of its looks were drawn.
     */
    val ownBackground: WidgetBackground get() = WidgetBackground.ARTWORK

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
    /** How a Shelf looks: Big cover unless another was picked, which is also what a Shelf from before the looks is. */
    val look: ShelfLook = ShelfLook.BIG_COVER,
    val list: WidgetList = WidgetList.QUICK_PICKS,
    /** 0 means as many rows of covers as fit; anything else is a ceiling the listener set (the crate's rows and the records'). */
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
    /** Only the list's heading, which opens the app: a list alone with no room for its look. */
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

    /** Shelf, Big cover: the cover of what is playing big, the list as a mosaic of covers beside it; with a list alone, its first song big. */
    BIG_COVER,

    /** Shelf, Crate: what is playing in a line on top, the list's covers leaning in a crate under it; with a list alone, its heading and the crate. */
    CRATE,

    /** Shelf, Records: the sleeve with its record sliding out, the list as records under it; with a list alone, its heading and the records. */
    RECORDS,

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
    /** Full cover's glass, and the pill of buttons of a Shelf's look. */
    val glass: GlassPane? = null,
    /** Up next's heading, 0 for none. */
    val headingHeight: Int = 0,
    /** How many of the list's songs a Shelf shows: never more than the list holds. */
    val shown: Int = 0,
    /** PANES: Up next's pane, as wide as this, its rows and their height. */
    val paneW: Int = 0,
    val upNext: Int = 0,
    val upNextRow: Int = 0,
    /** A Shelf's look: where it puts its covers, its words and its pill. */
    val look: LookPlan? = null,
)

object WidgetLayout {
    /** Narrower than this and there is no room for words beside a picture. */
    const val TINY_WIDTH_DP = 110

    /** Shorter than this and the widget is one line: the song and its buttons side by side. */
    const val LINE_HEIGHT_DP = 100

    /** A Shelf narrower than this and at least as tall as wide, whose look has no room, is a tile rather than a line. */
    const val LOOK_TILE_BELOW_DP = 220

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

    /**
     * Never more than this many songs in a list, however big the widget is: what a tablet's widget
     * has to fill itself with (see [isBig]).
     */
    const val MAX_PICKS = 12

    /** What a widget below a tablet's size draws of a list at most: the six its shapes were made for. */
    const val PHONE_PICKS = 6

    /**
     * A tablet's size (or a foldable's inside): one side longer than [BIG_LONG_DP] and the other at
     * least [BIG_SHORT_DP]. A phone's home screen has no widget that big, and a widget that is
     * keeps to its shape no longer, since what fills a phone's four by four leaves most of this
     * empty: a Shelf's look grows its covers and their number, and Up next gives way to a cover
     * that can fill the height.
     */
    const val BIG_LONG_DP = 650
    const val BIG_SHORT_DP = 420

    /** On a tablet Cover's column of words and buttons beside its cover is never wider than this. */
    const val BIG_COLUMN_MAX_DP = 400

    /** Under this share of the height a tablet's Cover is too small for Up next to go beside it, and it is drawn without. */
    const val PANES_COVER_MIN_PERCENT = 60

    fun isBig(widthDp: Int, heightDp: Int): Boolean =
        maxOf(widthDp, heightDp) > BIG_LONG_DP && minOf(widthDp, heightDp) >= BIG_SHORT_DP

    /** The most Up next shows: the next four of the queue. */
    const val MAX_UP_NEXT = 4

    /** At most this many rows in a Shelf's crate or of its records: the ceiling the settings offer. */
    const val MAX_SHELF_ROWS = WidgetLooks.MAX_LOOK_ROWS

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
        // A list holds more songs than a phone's shapes were made for: under a tablet's size they
        // draw the first six, as they always did.
        val songs = available.coerceAtLeast(0).let { if (isBig(w, h)) it else minOf(it, PHONE_PICKS) }
        val shaped = shape(w, h, settings, songs, scale, upNext.coerceIn(0, MAX_UP_NEXT))
        return withLike(shaped, w, settings)
    }

    /** Every shape but the heart, which [withLike] adds where it fits. */
    private fun shape(w: Int, h: Int, s: WidgetSettings, available: Int, scale: Float, upNext: Int): WidgetPlan {
        if (s.contentShown == WidgetContent.LIST) {
            return WidgetLooks.plan(w, h, s, available, scale) ?: WidgetPlan(s.style, WidgetShape.NONE, padH = 12, padV = 10)
        }
        if (w < TINY_WIDTH_DP) return tile(w, h, s)
        if (h < LINE_HEIGHT_DP) return line(w, h, s, scale)
        if (s.style == WidgetStyle.SHELF) {
            // A look with no room: a narrow, tall widget is better as its cover with play than as a
            // line with most of its height empty; anything else is the line.
            return WidgetLooks.plan(w, h, s, available, scale)
                ?: if (w < LOOK_TILE_BELOW_DP && h >= w) tile(w, h, s) else line(w, h, s, scale)
        }
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
            // On a tablet the column is no wider than a column of words should be, and the cover and
            // the column are in the middle of the widget rather than against its start.
            val sideH = if (cover > 0 && isBig(w, h)) {
                maxOf(pad, (w - cover - SIDE_GAP_DP - minOf(w - 2 * pad - cover - SIDE_GAP_DP, BIG_COLUMN_MAX_DP)) / 2)
            } else {
                pad
            }
            return WidgetPlan(
                s.style, WidgetShape.SIDE,
                padH = sideH, padV = (h - colH) / 2,
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

    /** Inside a glass bar at its ends: less on a small widget, where every dp of the bar counts, and the pill's own in a Shelf's look. */
    fun glassPadH(p: WidgetPlan): Int = when {
        p.look != null -> if (p.button > WidgetLooks.PILL_BUTTON_DP) WidgetLooks.BIG_PILL_PAD_H_DP else WidgetLooks.PILL_PAD_H_DP
        p.shape == WidgetShape.POSTER && p.coverW < 200 -> 8
        else -> GLASS_PAD_H_DP
    }

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

    /** The height of a row of Up next: its cover, or its two lines of words with room round them. */
    fun upNextRowHeight(scale: Float): Int = maxOf(UP_NEXT_ROW_DP, line(13, scale) + line(12, scale) + 8)

    /** Up next's heading. */
    fun upNextHeading(scale: Float): Int = line(12, scale) + 8

    /**
     * A tablet: what is playing on the left, in Cover's wide shape or over Full cover's cover with
     * the glass bar at the bottom, and Up next on the right, the next songs of the queue, each one
     * tapped to play the queue on from it (the mockup approved for tablets). A Shelf is never drawn
     * this way: its looks fill a tablet's widget with their own covers.
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
            // On a tablet a cover that is only a small part of the height is not worth Up next beside it:
            // the widget is better as a cover that fills it, which is what it is drawn as without Up next.
            if (isBig(w, h) && cover > 0 && cover * 100 < ih * PANES_COVER_MIN_PERCENT) continue
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
     * in glass the bar grows by a button and must still fit, and a Shelf's pill grows into the room
     * its look keeps for it (see [WidgetLooks.withLike]). Never where previous and next are not,
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
            WidgetShape.BIG_COVER, WidgetShape.CRATE, WidgetShape.RECORDS -> WidgetLooks.withLike(plan)
            WidgetShape.POSTER, WidgetShape.BANNER -> {
                val grown = glassWithHeart(plan, w) ?: return plan
                if (grown.x >= plan.padH && grown.x + grown.w <= w - plan.padH) liked.copy(glass = grown) else plan
            }
            WidgetShape.PANES ->
                if (full) {
                    val grown = glassWithHeart(plan, w) ?: return plan
                    if (grown.w <= w - 2 * plan.padH - PANE_GAP_DP - plan.paneW) liked.copy(glass = grown) else plan
                } else if (rowWidth(liked) <= columnWidth(liked, w)) liked else plan
            WidgetShape.TILE, WidgetShape.NONE -> plan
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
    val LOOK = stringPreferencesKey("widget_look")
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
     * which stays a list as a Shelf of it. A Shelf set up before there were looks has no look, and is
     * a Big cover. Its background was written whether or not it was ever
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
            look = ShelfLook.of(prefs[LOOK]),
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
        prefs[LOOK] = settings.look.name
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

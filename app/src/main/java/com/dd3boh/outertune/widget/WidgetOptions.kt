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
    /** What is playing, and a list under it when there is room. The one most people want. */
    BOTH,

    /** What is playing and nothing else. Given the whole widget, the artwork grows to fill it. */
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

    /** Taken from the artwork of what is playing, the way the player tints itself. */
    ARTWORK,

    /** None: the words sit straight on the wallpaper. */
    NONE;

    companion object {
        fun of(name: String?): WidgetBackground = entries.firstOrNull { it.name == name } ?: SYSTEM
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
 * Everything one widget was told to be. Read from that widget's own state, so two widgets side by
 * side can be a player and a list of forgotten favourites, in different colours, at different
 * sizes. Anything never chosen falls back to the default, which is also what a widget dropped on
 * a home screen and never configured gets.
 */
data class WidgetSettings(
    val content: WidgetContent = WidgetContent.BOTH,
    val list: WidgetList = WidgetList.QUICK_PICKS,
    /** 0 means as many rows as fit; anything else is a ceiling the listener set. */
    val maxRows: Int = 0,
    val background: WidgetBackground = WidgetBackground.SYSTEM,
    /** How solid the background is, in percent. */
    val opacity: Int = 100,
    val showArtwork: Boolean = true,
    val showArtist: Boolean = true,
    val showHeading: Boolean = true,
    val buttons: WidgetButtons = WidgetButtons.ALL,
    val textSize: WidgetTextSize = WidgetTextSize.NORMAL,
    val rounded: Boolean = true,
)

/** How the now playing part is drawn, which depends on the room it has. */
enum class NowShape {
    /** Not drawn at all: this widget is a list. */
    NONE,

    /** Narrower than a title: the cover with play over it, or play under it when the widget is tall. */
    TINY,

    /** A single cell tall: a small cover, the song on one or two short lines, small buttons. */
    SLIM,

    /** Cover, title and artist, buttons, in a line. */
    ROW,

    /** Narrow and tall: the cover above the song, the buttons under it. */
    STACKED,

    /** Wide and tall: a big cover beside the song, the buttons under the song. */
    CARD,

    /** Roughly square: the cover fills the widget, with the song and its buttons over it. */
    POSTER,
}

/**
 * Everything one widget draws at one size, in dp, decided before a single view exists.
 *
 * Glance cannot measure: it lays out what it is given and clips whatever does not fit. So the sizes
 * are worked out here from the size and the settings alone, and [MusicWidget] draws exactly these
 * numbers. That is what lets a test walk every size a launcher can hand out and prove nothing is
 * ever cut off. The rules before this guessed, and at common sizes lost: a play button clipped at
 * two by two, a title squeezed to nothing beside a cover, the last row of a list cut in half, and a
 * band of empty background under nearly everything else.
 */
data class WidgetPlan(
    val shape: NowShape,
    /** The side of the cover in the now playing part, 0 for none. */
    val cover: Int = 0,
    /** Height of the now playing part. Shapes that are the whole widget leave it at 0. */
    val nowHeight: Int = 0,
    val showArtist: Boolean = false,
    /** Lines the title may take: two only where the height was there for them. */
    val titleLines: Int = 1,
    val play: Boolean = false,
    val skips: Boolean = false,
    /** The side of each transport button. */
    val button: Int = WidgetLayout.BUTTON_DP,
    /** TINY and POSTER: the buttons sit on their own line under the song rather than beside it. */
    val buttonsBelow: Boolean = false,
    /** Between the now playing part and the list. */
    val gap: Int = 0,
    val headingHeight: Int = 0,
    val rows: Int = 0,
    val rowHeight: Int = 0,
    val rowCover: Boolean = true,
    val rowShowsArtist: Boolean = true,
    /**
     * Whether what is drawn sits in the middle of the height. A list that ran out of songs before it
     * ran out of room is drawn from the top instead, the way any list is.
     */
    val centred: Boolean = true,
) {
    val heading: Boolean get() = headingHeight > 0
}

object WidgetLayout {
    /** Narrower than this and there is no room for words beside a picture. */
    const val TINY_WIDTH_DP = 110

    /** Shorter than this and the song is one line with small buttons: a single cell on most launchers. */
    const val SLIM_HEIGHT_DP = 64

    /** Narrower than this and a narrow widget with a list stacks its song above its buttons. */
    const val WIDE_ENOUGH_DP = 180

    /** From this height, what is playing on its own is a card or a poster rather than a line. */
    const val TALL_DP = 110

    /** A wide widget becomes a card from this height; under it, a line shows the artist and a cover nearly as big. */
    const val CARD_HEIGHT_DP = 130

    const val PAD_H_DP = 12
    const val PAD_V_DP = 10

    /** Between the cover and the words beside it. */
    const val COVER_GAP_DP = 10

    /** Between the words and the buttons, and between the now playing part and a list. */
    const val SMALL_GAP_DP = 6

    const val BUTTON_DP = 44
    const val SLIM_BUTTON_DP = 36

    /** Between two transport buttons, so the filled play button does not touch its neighbours. */
    const val BUTTON_SPACING_DP = 4

    /** Words beside a cover never get less than this: the cover, or the skip buttons, go first. */
    const val WORDS_MIN_DP = 64

    /** The cover in a line of song with a list under it, and the most it grows to on its own. */
    const val ROW_COVER_DP = 56
    const val ROW_COVER_MAX_DP = 72

    /** The cover of a narrow widget stacked above its song, with a list under it. */
    const val STACKED_COVER_DP = 64

    /** Below this a card's cover is a thumbnail, and the widget is better as a line. */
    const val CARD_COVER_MIN_DP = 72

    /** A row of a list: never shorter than the first, and grown to at most the second when there is room. */
    const val LIST_ROW_MIN_DP = 50
    const val LIST_ROW_DP = 56
    const val LIST_ROW_MAX_DP = 64
    const val LIST_COVER_DP = 40

    /** Never more than this many rows, however tall the widget is: past that it is a list, not a widget. */
    const val MAX_PICKS = 6

    /**
     * The height of a line of text, in dp, at a size in sp under a text scale. Roboto's line is about
     * 1.33 of its size; the rounding is up, so a guess errs towards room to spare.
     */
    fun line(sp: Int, scale: Float): Int = ceil(sp * scale * 1.34f).toInt()

    /**
     * What to draw at this size. [available] is how many songs the chosen list holds, and [fontScale]
     * is the phone's own text size, which Glance applies to every sp on top of the widget's setting.
     */
    fun plan(widthDp: Int, heightDp: Int, settings: WidgetSettings, available: Int, fontScale: Float = 1f): WidgetPlan {
        val w = widthDp.coerceAtLeast(1)
        val h = heightDp.coerceAtLeast(1)
        val scale = settings.textSize.scale * fontScale.coerceIn(0.5f, 3f)
        if (settings.content == WidgetContent.LIST) return listOnly(w, h, settings, available, scale)
        if (w < TINY_WIDTH_DP) return tiny(w, h, settings)
        if (h < SLIM_HEIGHT_DP) return slim(w, h, settings, scale)
        if (settings.content == WidgetContent.BOTH) {
            withList(w, h, settings, available, scale)?.let { return it }
        }
        // What is playing on its own: the proportions choose the shape.
        if (h < TALL_DP || !settings.showArtwork) return songLine(w, h, settings, scale)
        val aspect = w.toFloat() / h
        return when {
            aspect >= 1.6f -> (if (h >= CARD_HEIGHT_DP) card(w, h, settings, scale) else null) ?: songLine(w, h, settings, scale)
            aspect >= 0.62f -> poster(w, h, settings, scale)
            else -> stacked(w, h, settings, scale)
        }
    }

    private fun controlsWidth(play: Boolean, skips: Boolean, button: Int): Int = when {
        skips -> 3 * button + 2 * BUTTON_SPACING_DP
        play -> button
        else -> 0
    }

    /**
     * A cover and skip buttons for a line this wide, in order of preference, keeping the first that
     * leaves the words their minimum. The skip buttons were asked for and the cover was only
     * offered, so the cover goes first; it comes back if dropping the skips makes room for it.
     */
    private fun fitLine(w: Int, padH: Int, coverWanted: Int, settings: WidgetSettings, button: Int): Triple<Int, Boolean, Boolean> {
        val play = settings.buttons != WidgetButtons.NONE
        val skipsWanted = settings.buttons == WidgetButtons.ALL
        val tries = listOf(coverWanted to skipsWanted, 0 to skipsWanted, coverWanted to false, 0 to false).distinct()
        for ((cover, skips) in tries) {
            val words = w - 2 * padH - (if (cover > 0) cover + COVER_GAP_DP else 0) -
                controlsWidth(play, skips, button) - (if (play) SMALL_GAP_DP else 0)
            if (words >= WORDS_MIN_DP) return Triple(cover, play, skips)
        }
        return Triple(0, play, false)
    }

    /** Narrower than a title: the cover and play, over it or under it. */
    private fun tiny(w: Int, h: Int, settings: WidgetSettings): WidgetPlan {
        val play = settings.buttons != WidgetButtons.NONE
        val side = w - 16
        return if (play && w >= BUTTON_DP + 8 && h >= side + 8 + BUTTON_DP + 16) {
            WidgetPlan(NowShape.TINY, cover = if (settings.showArtwork) side else 0, play = true, buttonsBelow = true)
        } else {
            // Over the cover, and only if the cover can spare the room: on a single small cell the
            // whole tile opens the app instead.
            val overlay = play && minOf(w, h) >= 56
            WidgetPlan(
                NowShape.TINY,
                cover = if (settings.showArtwork) minOf(w, h) else 0,
                play = overlay,
                button = minOf(40, minOf(w, h) - 16).coerceAtLeast(24),
            )
        }
    }

    /** One cell tall: a line of song with small buttons. */
    private fun slim(w: Int, h: Int, settings: WidgetSettings, scale: Float): WidgetPlan {
        val inner = h - 8
        val coverWanted = if (settings.showArtwork && h - 12 >= 32) h - 12 else 0
        val button = minOf(SLIM_BUTTON_DP, inner)
        val (cover, play, skips) = fitLine(w, PAD_H_DP, coverWanted, settings, button)
        return WidgetPlan(
            NowShape.SLIM,
            cover = cover,
            nowHeight = inner,
            showArtist = settings.showArtist && line(14, scale) + line(12, scale) <= inner,
            play = play,
            skips = skips,
            button = button,
        )
    }

    /** A line of song given the whole widget: the cover grows with the height, up to a point. */
    private fun songLine(w: Int, h: Int, settings: WidgetSettings, scale: Float): WidgetPlan {
        val inner = h - 2 * PAD_V_DP
        val coverWanted = if (settings.showArtwork) minOf(inner, ROW_COVER_MAX_DP).takeIf { it >= 40 } ?: 0 else 0
        val button = minOf(BUTTON_DP, inner)
        val (cover, play, skips) = fitLine(w, PAD_H_DP, coverWanted, settings, button)
        return WidgetPlan(
            NowShape.ROW,
            cover = cover,
            nowHeight = inner,
            showArtist = settings.showArtist && line(15, scale) + line(13, scale) <= inner,
            play = play,
            skips = skips,
            button = button,
        )
    }

    /**
     * The song and a list under it, or null when not a single row fits: then the song has the
     * widget to itself, which beats a heading over nothing.
     */
    private fun withList(w: Int, h: Int, settings: WidgetSettings, available: Int, scale: Float): WidgetPlan? {
        val play = settings.buttons != WidgetButtons.NONE
        val now = if (w < WIDE_ENOUGH_DP) {
            val words = line(14, scale) + if (settings.showArtist) line(12, scale) else 0
            val cover = if (settings.showArtwork) STACKED_COVER_DP else 0
            val skips = settings.buttons == WidgetButtons.ALL && controlsWidth(true, true, BUTTON_DP) <= w - 2 * PAD_H_DP
            WidgetPlan(
                NowShape.STACKED,
                cover = cover,
                nowHeight = (if (cover > 0) cover + SMALL_GAP_DP else 0) + words + (if (play) 2 + BUTTON_DP else 0),
                showArtist = settings.showArtist,
                play = play,
                skips = skips,
            )
        } else {
            val (cover, _, skips) = fitLine(w, PAD_H_DP, if (settings.showArtwork) ROW_COVER_DP else 0, settings, BUTTON_DP)
            val words = line(15, scale) + if (settings.showArtist) line(13, scale) else 0
            WidgetPlan(
                NowShape.ROW,
                cover = cover,
                nowHeight = maxOf(cover, if (play) BUTTON_DP else 0, words),
                showArtist = settings.showArtist,
                play = play,
                skips = skips,
            )
        }
        val room = h - 2 * PAD_V_DP - now.nowHeight - SMALL_GAP_DP
        val list = listRows(w, room, settings, available, scale) ?: return null
        return list.copy(
            shape = now.shape,
            cover = now.cover,
            nowHeight = now.nowHeight,
            showArtist = now.showArtist,
            play = now.play,
            skips = now.skips,
            gap = SMALL_GAP_DP,
        )
    }

    /** A list and nothing else. Too short for a single row, it is its heading, which opens the app. */
    private fun listOnly(w: Int, h: Int, settings: WidgetSettings, available: Int, scale: Float): WidgetPlan =
        listRows(w, h - 2 * PAD_V_DP, settings, available, scale) ?: WidgetPlan(NowShape.NONE)

    /**
     * As many rows as fit in [room] under their heading, never more than the list holds or the
     * listener allowed. When the height is what limits them, the rows share out what is left, up to
     * a comfortable height, and the rest is split above and below; when the songs run out first,
     * the list starts at the top.
     */
    private fun listRows(w: Int, room: Int, settings: WidgetSettings, available: Int, scale: Float): WidgetPlan? {
        val heading = if (settings.showHeading) line(12, scale) + 4 else 0
        val space = room - heading
        val words = line(14, scale) + line(12, scale)
        val rowCover = settings.showArtwork && w - 2 * PAD_H_DP - LIST_COVER_DP - COVER_GAP_DP >= WORDS_MIN_DP
        val rowMin = maxOf(LIST_ROW_MIN_DP, words + 8, (if (rowCover) LIST_COVER_DP + 8 else 0))
        val allowed = minOf(available, if (settings.maxRows > 0) settings.maxRows else MAX_PICKS, MAX_PICKS)
        if (allowed <= 0 || space < rowMin) return null
        val fit = space / rowMin
        val rows = minOf(allowed, fit)
        val heightLimited = fit <= allowed
        val rowHeight = if (heightLimited) minOf(space / rows, maxOf(LIST_ROW_MAX_DP, rowMin)) else maxOf(LIST_ROW_DP, rowMin)
        return WidgetPlan(
            NowShape.NONE,
            headingHeight = heading,
            rows = rows,
            rowHeight = rowHeight,
            rowCover = rowCover,
            rowShowsArtist = settings.showArtist,
            centred = heightLimited,
        )
    }

    /** Wide and tall: the cover as tall as the widget, the song and the buttons beside it. */
    private fun card(w: Int, h: Int, settings: WidgetSettings, scale: Float): WidgetPlan? {
        val play = settings.buttons != WidgetButtons.NONE
        for (skips in listOf(settings.buttons == WidgetButtons.ALL, false).distinct()) {
            val beside = maxOf(96, controlsWidth(play, skips, BUTTON_DP))
            val cover = minOf(h - 2 * PAD_H_DP, w - 2 * PAD_H_DP - 14 - beside)
            if (cover < CARD_COVER_MIN_DP) continue
            val title = line(16, scale)
            val artist = line(13, scale)
            val controls = if (play) SMALL_GAP_DP + BUTTON_DP else 0
            val showArtist = settings.showArtist && title + artist + controls <= cover
            val lines = if (2 * title + (if (showArtist) artist else 0) + controls <= cover) 2 else 1
            if (title + controls > cover) continue
            return WidgetPlan(
                NowShape.CARD,
                cover = cover,
                showArtist = showArtist,
                titleLines = lines,
                play = play,
                skips = skips,
            )
        }
        return null
    }

    /** Roughly square: the cover is the widget, the song and its buttons along the bottom of it. */
    private fun poster(w: Int, h: Int, settings: WidgetSettings, scale: Float): WidgetPlan {
        val play = settings.buttons != WidgetButtons.NONE
        val title = line(16, scale)
        val artist = line(13, scale)
        // The buttons get a line of their own only where the widget is big enough not to be buried
        // under them: otherwise play sits at the end of the song's line.
        val below = settings.buttons == WidgetButtons.ALL && w >= WIDE_ENOUGH_DP &&
            2 * PAD_H_DP + title + artist + 4 + BUTTON_DP <= h * 0.6f
        val wordsWidth = w - 2 * PAD_H_DP - if (!below && play) BUTTON_DP + 8 else 0
        val words = wordsWidth >= 56
        return WidgetPlan(
            NowShape.POSTER,
            cover = maxOf(w, h),
            showArtist = words && settings.showArtist && 2 * PAD_H_DP + title + artist + (if (below) 4 + BUTTON_DP else 0) <= h,
            titleLines = if (words) 1 else 0,
            play = play,
            skips = below,
            buttonsBelow = below,
        )
    }

    /** Narrow and tall: the cover as wide as the widget, the song under it, the buttons under that. */
    private fun stacked(w: Int, h: Int, settings: WidgetSettings, scale: Float): WidgetPlan {
        val play = settings.buttons != WidgetButtons.NONE
        val skips = settings.buttons == WidgetButtons.ALL && controlsWidth(true, true, BUTTON_DP) <= w - 2 * PAD_H_DP
        val words = line(14, scale) + if (settings.showArtist) line(12, scale) else 0
        val controls = if (play) 2 + BUTTON_DP else 0
        val cover = minOf(w - 2 * PAD_H_DP, h - 2 * PAD_V_DP - SMALL_GAP_DP - words - controls).takeIf { it >= 48 } ?: 0
        return WidgetPlan(
            NowShape.STACKED,
            cover = cover,
            nowHeight = (if (cover > 0) cover + SMALL_GAP_DP else 0) + words + controls,
            showArtist = settings.showArtist,
            play = play,
            skips = skips,
        )
    }

    /**
     * The narrowest a plan can be drawn without cutting anything off, counting the words beside a
     * cover at nothing: [fitLine] is what keeps them their minimum wherever the width allows it.
     */
    fun widthOf(plan: WidgetPlan): Int {
        val controls = controlsWidth(plan.play, plan.skips, plan.button)
        val now = when (plan.shape) {
            NowShape.NONE -> 0
            NowShape.TINY -> maxOf(plan.cover, if (plan.play) plan.button else 0)
            NowShape.POSTER -> 2 * PAD_H_DP + controls
            NowShape.CARD -> 2 * PAD_H_DP + plan.cover + 14 + controls
            NowShape.STACKED -> 2 * PAD_H_DP + maxOf(plan.cover, controls)
            NowShape.ROW, NowShape.SLIM -> 2 * PAD_H_DP + (if (plan.cover > 0) plan.cover + COVER_GAP_DP else 0) +
                controls + (if (plan.play) SMALL_GAP_DP else 0)
        }
        val list = if (plan.rows > 0) 2 * PAD_H_DP + (if (plan.rowCover) LIST_COVER_DP + COVER_GAP_DP else 0) else 0
        return maxOf(now, list)
    }

    /** The height a plan takes, for the test that nothing is ever cut off. */
    fun heightOf(plan: WidgetPlan, heightDp: Int): Int = when (plan.shape) {
        NowShape.TINY, NowShape.POSTER, NowShape.CARD -> heightDp
        NowShape.SLIM -> 8 + plan.nowHeight
        else -> 2 * PAD_V_DP + plan.nowHeight + (if (plan.rows > 0) plan.gap + plan.headingHeight + plan.rows * plan.rowHeight else 0)
    }
}

/**
 * Where a widget's settings live: its own Glance state, one key each.
 *
 * Strings and ints rather than a serialised blob, so a settings file can be read with a text editor
 * and a key added later costs nothing. Every read falls back, so a widget configured by an older
 * version is never broken by a newer one.
 */
object WidgetKeys {
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

    fun read(prefs: Preferences): WidgetSettings {
        val fallback = WidgetSettings()
        return WidgetSettings(
            content = WidgetContent.of(prefs[CONTENT]),
            list = WidgetList.of(prefs[LIST]),
            maxRows = (prefs[MAX_ROWS] ?: fallback.maxRows).coerceIn(0, WidgetLayout.MAX_PICKS),
            background = WidgetBackground.of(prefs[BACKGROUND]),
            opacity = (prefs[OPACITY] ?: fallback.opacity).coerceIn(0, 100),
            showArtwork = prefs[SHOW_ARTWORK] ?: fallback.showArtwork,
            showArtist = prefs[SHOW_ARTIST] ?: fallback.showArtist,
            showHeading = prefs[SHOW_HEADING] ?: fallback.showHeading,
            buttons = WidgetButtons.of(prefs[BUTTONS]),
            textSize = WidgetTextSize.of(prefs[TEXT_SIZE]),
            rounded = prefs[ROUNDED] ?: fallback.rounded,
        )
    }

    fun write(prefs: MutablePreferences, settings: WidgetSettings) {
        prefs[CONTENT] = settings.content.name
        prefs[LIST] = settings.list.name
        prefs[MAX_ROWS] = settings.maxRows
        prefs[BACKGROUND] = settings.background.name
        prefs[OPACITY] = settings.opacity
        prefs[SHOW_ARTWORK] = settings.showArtwork
        prefs[SHOW_ARTIST] = settings.showArtist
        prefs[SHOW_HEADING] = settings.showHeading
        prefs[BUTTONS] = settings.buttons.name
        prefs[TEXT_SIZE] = settings.textSize.name
        prefs[ROUNDED] = settings.rounded
    }
}

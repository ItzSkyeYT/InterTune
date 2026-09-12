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
) {
    /** Rows to draw at this size, under this ceiling. */
    fun rowsAt(widthDp: Int, heightDp: Int, available: Int): Int {
        val fits = WidgetLayout.listCount(widthDp, heightDp, content, available)
        return if (maxRows <= 0) fits else minOf(fits, maxRows)
    }
}

/** How the now playing part is drawn, which depends on the room it has. */
enum class NowShape {
    /** Not drawn at all: this widget is a list. */
    NONE,

    /** Narrower than a title: the artwork alone, which opens the app, with play over it if there is height. */
    TINY,

    /** Artwork, title and artist in a line, with the buttons at the end. */
    ROW,

    /** Too narrow for a line: artwork above, title under it, buttons under that. */
    STACKED,

    /** Given the whole widget: artwork as large as it will go, the song under it, then the buttons. */
    ART,
}

object WidgetLayout {
    /** Narrower than this and there is no room for words beside a picture. */
    const val TINY_WIDTH_DP = 110

    /** Narrower than this and the skip buttons go, leaving play and pause. */
    const val WIDE_ENOUGH_DP = 180

    /** Below this height a line of song and its buttons is the whole widget. */
    const val COMPACT_HEIGHT_DP = 110

    /** A row of a list, including its padding. */
    const val PICK_ROW_DP = 56

    /** The heading over a list. */
    const val LIST_HEADER_DP = 24

    /** What the now playing line takes before any list. */
    const val ROW_BLOCK_DP = 70

    /** What the stacked form takes: artwork, title, buttons. */
    const val STACKED_BLOCK_DP = 150

    /** Below either of these the artwork cannot be given the widget, whatever the setting says. */
    const val ART_MIN_WIDTH_DP = 180
    const val ART_MIN_HEIGHT_DP = 220

    /** Never more than this many rows, however tall the widget is: past that it is a list, not a widget. */
    const val MAX_PICKS = 6

    /** What the song and the buttons take under a cover that has been given the widget. */
    const val ART_TEXT_AND_BUTTONS_DP = 100

    fun showsSkipButtons(widthDp: Int): Boolean = widthDp >= WIDE_ENOUGH_DP

    /** How tall the cover may be when the widget is given over to it. */
    fun artHeightDp(heightDp: Int): Int = (heightDp - ART_TEXT_AND_BUTTONS_DP).coerceAtLeast(80)

    fun showsArtwork(heightDp: Int): Boolean = heightDp >= 72

    /** The shape of the now playing part at this size, under this choice of content. */
    fun nowShape(widthDp: Int, heightDp: Int, content: WidgetContent): NowShape = when {
        content == WidgetContent.LIST -> NowShape.NONE
        widthDp < TINY_WIDTH_DP -> NowShape.TINY
        content == WidgetContent.NOW_PLAYING && widthDp >= ART_MIN_WIDTH_DP && heightDp >= ART_MIN_HEIGHT_DP -> NowShape.ART
        widthDp < WIDE_ENOUGH_DP && heightDp >= STACKED_BLOCK_DP -> NowShape.STACKED
        else -> NowShape.ROW
    }

    /** What the now playing part takes from the height before the list gets any. */
    fun nowBlockDp(shape: NowShape, heightDp: Int): Int = when (shape) {
        NowShape.NONE -> 0
        NowShape.TINY -> heightDp
        NowShape.ROW -> ROW_BLOCK_DP
        NowShape.STACKED -> STACKED_BLOCK_DP
        NowShape.ART -> heightDp
    }

    /** How many rows of the list fit under the now playing part, at most [available]. */
    fun listCount(widthDp: Int, heightDp: Int, content: WidgetContent, available: Int): Int {
        if (available <= 0) return 0
        if (content == WidgetContent.NOW_PLAYING) return 0
        val shape = nowShape(widthDp, heightDp, content)
        if (shape == NowShape.TINY || shape == NowShape.ART) return 0
        val room = heightDp - nowBlockDp(shape, heightDp) - LIST_HEADER_DP
        if (room < PICK_ROW_DP) return 0
        return (room / PICK_ROW_DP).coerceIn(0, minOf(MAX_PICKS, available))
    }

    /** A list-only widget shows its heading; a list under a song does too, to say which row it is. */
    fun showsListHeader(content: WidgetContent, count: Int): Boolean = count > 0
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

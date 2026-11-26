/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The colour the theme was last made from, kept with the id of the song it came from.
 *
 * A new process (an update, the app swiped away, the system ending it) has nothing playing until Play,
 * and the cover has to be loaded to know its colour, so the app opened in Material's default for as
 * long as that took, the mini player's line included. The last colour is read at once at start, before
 * any cover, and the song shown settles it: the same song keeps it, another song makes its own. Not with
 * Persistent queue off, where a new start shows no song before Play.
 */
class ThemeSeed(private val store: Store) {

    /** Where the seed lives; the app's preferences, or a map in a test. */
    interface Store {
        fun getInt(key: String): Int?
        fun getString(key: String): String?
        fun put(key: String, int: Int?, string: String?)
    }

    /** The colour to start with, before any song is known; null when none is kept. */
    fun atStart(): Color? = store.getInt(KEY_COLOUR)?.let { Color(it) }

    /** The colour kept for [songId], only when it was made from that very song. */
    fun forSong(songId: String): Color? =
        if (store.getString(KEY_SONG) == songId) atStart() else null

    /** A new song's colour, once it has been worked out from its cover. */
    fun write(songId: String, colour: Color) {
        val argb = colour.toArgb()
        if (store.getString(KEY_SONG) == songId && store.getInt(KEY_COLOUR) == argb) return
        store.put(KEY_COLOUR, argb, songId)
    }

    /**
     * Nothing is shown any more, the song shown gives no colour (its cover failed to load), or the theme does not
     * follow the cover: the next start has no colour.
     */
    fun clear() {
        if (store.getInt(KEY_COLOUR) == null && store.getString(KEY_SONG) == null) return
        store.put(KEY_COLOUR, null, null)
    }

    companion object {
        private const val PREFS = "theme_seed"
        private const val KEY_COLOUR = "colour"
        private const val KEY_SONG = "song"

        fun of(context: Context): ThemeSeed {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ThemeSeed(object : Store {
                override fun getInt(key: String) = if (prefs.contains(key)) prefs.getInt(key, 0) else null
                override fun getString(key: String) = prefs.getString(key, null)
                override fun put(key: String, int: Int?, string: String?) {
                    prefs.edit().apply {
                        if (int == null) remove(KEY_COLOUR) else putInt(KEY_COLOUR, int)
                        if (string == null) remove(KEY_SONG) else putString(KEY_SONG, string)
                    }.apply()
                }
            })
        }
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.playback.TransitionFadeEnvelope

/**
 * Crossfade (Unreleased.CROSSFADE): a song that ends by itself fades out while the next one fades
 * in, on AutoMix's stage and under its guards, side by side with it. Pure, so tested;
 * AutomixController asks it about every change of song.
 *
 * A queue that plays as a mix gets no crossfade at all (his rule of 5 Nov 2026): a crossfade
 * never stands between two changes someone mixed.
 */
object Crossfade {

    /** The lengths offered in the settings, in seconds: the same as Fade between tracks. */
    val SECONDS_CHOICES: List<Int> = TransitionFadeEnvelope.SECONDS_CHOICES

    const val DEFAULT_SECONDS = 6

    /** The stored length, kept inside the range the settings offer. */
    fun seconds(stored: Int?): Int = (stored ?: DEFAULT_SECONDS).coerceIn(SECONDS_CHOICES.first(), SECONDS_CHOICES.last())

    /** How one change of song is made, from the two settings and the queue playing. */
    enum class Way(val auto: Boolean) {
        /** Auto's change, as AutoMix makes it, gapless where Auto declines. */
        AUTO(auto = true),

        /** Auto's change first, and a crossfade where Auto would leave it gapless. */
        AUTO_THEN_CROSSFADE(auto = true),

        /** A crossfade, Auto aside. */
        CROSSFADE(auto = false),

        /** Today's change: neither applies to this queue. */
        NONE(auto = false),
    }

    /**
     * How the changes of the queue playing are made: AutoMix's setting for this queue first, and
     * Crossfade's where it is on and [crossfadeOff] does not keep it out of this queue.
     */
    fun way(automix: Boolean, crossfade: Boolean, crossfadeOff: Boolean): Way {
        val crossfades = crossfade && !crossfadeOff
        return when {
            automix && crossfades -> Way.AUTO_THEN_CROSSFADE
            automix -> Way.AUTO
            crossfades -> Way.CROSSFADE
            else -> Way.NONE
        }
    }

    /**
     * Why the queue playing gets no crossfade at all, or null when it may have one: it comes from
     * a playlist whose Mix switch is on ([mixPlaylist]), Auto has already mixed two of its songs
     * since it started ([autoMixed]), or two songs side by side in it have a change saved for
     * them ([savedPair], see [savedSideBySide]).
     */
    fun whyOff(mixPlaylist: Boolean, autoMixed: Boolean, savedPair: SongPair?): String? = when {
        mixPlaylist -> "it comes from a playlist that plays as a mix"
        autoMixed -> "AutoMix has mixed two of its songs"
        savedPair != null -> "${savedPair.from} and ${savedPair.to} follow each other in it with a change saved for them"
        else -> null
    }

    /**
     * The first two songs side by side in [songs], in that order, with a change saved from the
     * first into the second ([saved]), or null. A saved change belongs to its two songs in that
     * order only, so the other way round does not count, nor two songs further apart.
     */
    fun savedSideBySide(songs: List<String>, saved: Set<SongPair>): SongPair? {
        if (saved.isEmpty()) return null
        for (i in 0 until songs.size - 1) {
            val pair = SongPair(songs[i], songs[i + 1])
            if (pair in saved) return pair
        }
        return null
    }
}

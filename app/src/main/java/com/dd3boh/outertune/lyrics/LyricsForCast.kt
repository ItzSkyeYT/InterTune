/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.cast.CastLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics

/**
 * A song's lyrics as InterTune's own Chromecast receiver shows them on the TV (asked for on
 * 3 Nov 2026: lyrics on the screen when they are open on the phone).
 */
object LyricsForCast {
    /**
     * The synced lines of [lyrics], or null when there are none with times: unsynced lyrics have
     * nothing to follow the song with on a screen across the room. Translated lines are left out
     * (the TV shows the song's own words), and each line is trimmed; a blank one stays, as the
     * pause in the singing it marks.
     */
    fun of(mediaId: String, lyrics: SemanticLyrics?, shiftMs: Long): CastLyrics? {
        val synced = lyrics as? SemanticLyrics.SyncedLyrics ?: return null
        val lines = synced.text.filter { !it.isTranslated }.map { CastLyrics.Line(it.start.toLong(), it.text.trim()) }
        if (lines.none { it.text.isNotEmpty() }) return null
        return CastLyrics(mediaId, lines, shiftMs)
    }
}

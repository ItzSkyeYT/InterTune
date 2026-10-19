/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.util.Log
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import org.akanework.gramophone.logic.utils.parseTtml

private const val TAG = "KeptLyrics"

/**
 * Lyrics as kept, read whatever their form: TTML, as Better Lyrics gives, or LRC and plain text, as
 * the others do. Everything that turns kept lyrics into lines goes through here: the lookup, a
 * result picked in Search lyrics and lyrics edited by hand. The last two used to read everything as
 * LRC, so a Better Lyrics result picked in the search showed as a page of markup (19 Oct 2026).
 */
fun readKeptLyrics(text: String, trim: Boolean, multiline: Boolean): SemanticLyrics? {
    // TTML that cannot be read is no lyrics, never lines of markup: read as LRC, every tag of it
    // came out as the words of the song.
    if (LyricsMatch.isTtml(text)) return readTtml(text)
    return parseLrc(text, trim, multiline)
}

/** TTML read into lines, or null when it cannot be read. */
fun readTtml(text: String): SemanticLyrics? =
    runCatching { parseTtml(null, text) }.onFailure { Log.w(TAG, "TTML lyrics could not be read: ${it.message}") }.getOrNull()

/** What a result in Search lyrics shows of itself: its words, never TTML's markup. */
fun lyricsPreview(text: String): String {
    if (!LyricsMatch.isTtml(text)) return text
    return readTtml(text)?.unsyncedText?.map { it.first }?.filter { it.isNotBlank() }?.joinToString("\n").orEmpty()
}

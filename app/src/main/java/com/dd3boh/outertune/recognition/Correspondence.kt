/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.SongItem

/**
 * Whether a YouTube hit is plainly the thing Shazam named.
 *
 * Deliberately blunt, and deliberately strict. It is the gate for adding something without
 * asking, so the cost of a false yes is a karaoke version silently landing in somebody's
 * playlist, while the cost of a false no is one tap. Anything it is unsure about goes to
 * the user.
 *
 * Both sides are stripped of the decoration YouTube adds and Shazam does not: bracketed
 * asides, "official video", "remastered" and the like. What survives has to match outright,
 * not merely overlap, and the artist has to appear as well, because a cover carries the
 * right title and the wrong name.
 */
internal fun corresponds(track: Recognised, candidate: SongItem): Boolean {
    val title = normalise(track.title)
    val candidateTitle = normalise(candidate.title)
    if (title.isEmpty() || candidateTitle.isEmpty()) return false
    if (title != candidateTitle) return false

    val artist = normalise(track.artist.orEmpty())
    if (artist.isEmpty()) return false
    val candidateArtists = normalise(candidate.artists.joinToString(" ") { it.name })
    return candidateArtists.contains(artist) || artist.contains(candidateArtists)
}

private val NOISE = Regex(
    "\\((?:feat|ft|with|official|remaster|remastered|live|video|audio)[^)]*\\)" +
            "|\\[[^\\]]*\\]" +
            "|\\b(?:official (?:music )?video|lyrics?|audio|hd|hq|remastered?)\\b",
    RegexOption.IGNORE_CASE,
)

private fun normalise(text: String): String = text
    .replace(NOISE, " ")
    .lowercase()
    // Apostrophes are dropped rather than turned into a space, because they sit inside a word:
    // "don't" against "dont" has to survive this, and replacing it with a space made "don t",
    // which matched nothing. Every other punctuation mark separates words and becomes a space.
    .replace(Regex("[\u0027\u2019\u00B4\u0060]"), "")
    .replace(Regex("[^a-z0-9 ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()


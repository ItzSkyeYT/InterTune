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

    // The artist is the discriminator, so it is required outright. A cover carries the right title
    // and the wrong name, and that is the case this exists to catch.
    val artist = normalise(track.artist.orEmpty())
    if (artist.isEmpty()) return false
    val candidateArtists = normalise(candidate.artists.joinToString(" ") { it.name })
    if (!candidateArtists.contains(artist) && !artist.contains(candidateArtists)) return false

    // Titles need only agree from the start. Demanding they be equal was the first attempt and it
    // was far too strict to be useful: Shazam names a recording "Children" and YouTube lists it as
    // "Children (Dream Version)", so a correct match was rejected on every pass and, in continuous
    // mode, discarded in silence. Robert Miles cost two minutes of a real test to find that out.
    //
    // A prefix rather than a containment, and on a word boundary, so "Children" does not swallow
    // "Children of the Grave". With the artist already required, what remains is the same recording
    // under a longer name.
    return title == candidateTitle ||
            candidateTitle.startsWithWord(title) ||
            title.startsWithWord(candidateTitle)
}

/** True when [prefix] is a whole-word prefix, so "children" matches "children dream" not "childrens". */
private fun String.startsWithWord(prefix: String): Boolean =
    startsWith(prefix) && (length == prefix.length || this[prefix.length] == ' ')

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


/**
 * How the room's copy is playing relative to the recording Shazam knows.
 *
 * Derived from two recognitions of the same track rather than from one, which is the reason a
 * confident run listens twice before committing. The offset says where in the reference each sample
 * landed; if twelve seconds of wall clock advance it by twelve seconds the copy is unaltered, and if
 * it advances by fifteen the copy is a quarter faster.
 */
enum class PlaybackVariant { ORIGINAL, FASTER, SLOWER;

    companion object {
        /** Below this a difference is jitter, not an edit. Shazam's own timeskew sits near 0.004. */
        private const val TOLERANCE = 0.06

        fun between(first: Recognised, second: Recognised, secondsApart: Double): PlaybackVariant {
            if (secondsApart <= 0.0) return ORIGINAL
            val advanced = second.offsetSeconds - first.offsetSeconds
            // A negative or absurd delta means the track restarted or a different section matched,
            // so there is nothing to conclude and the safe answer is the ordinary one.
            if (advanced <= 0.0 || advanced > secondsApart * 3) return ORIGINAL
            val rate = advanced / secondsApart
            return when {
                rate > 1.0 + TOLERANCE -> FASTER
                rate < 1.0 - TOLERANCE -> SLOWER
                else -> ORIGINAL
            }
        }
    }
}

private val SLOWED = Regex("slowed|slow(ed)? ?\\+ ?reverb|daycore|screwed", RegexOption.IGNORE_CASE)
private val SPED = Regex("sped ?up|speed ?up|nightcore|fast(er)? version", RegexOption.IGNORE_CASE)

/**
 * Chooses which YouTube result to add, given how the room's copy is actually playing.
 *
 * Without this, a slowed edit playing in the room adds the original, and the original adds whatever
 * edit happens to rank first, which on YouTube is often a "slowed + reverb" upload. Both are the
 * wrong recording, and a title is the only thing there is to go on once Shazam has named the song.
 */
internal fun pickBest(
    track: Recognised,
    candidates: List<SongItem>,
    variant: PlaybackVariant,
): SongItem? {
    val eligible = candidates.filter { corresponds(track, it) }.ifEmpty { return null }
    return eligible.maxByOrNull { candidate ->
        val title = candidate.title
        val slowed = SLOWED.containsMatchIn(title)
        val sped = SPED.containsMatchIn(title)
        when (variant) {
            PlaybackVariant.ORIGINAL -> if (slowed || sped) 0 else 2
            PlaybackVariant.SLOWER -> if (slowed) 2 else if (sped) 0 else 1
            PlaybackVariant.FASTER -> if (sped) 2 else if (slowed) 0 else 1
        }
    }
}

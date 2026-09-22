/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.SongItem
import kotlin.math.abs

/**
 * One track as another service exported it.
 *
 * Deliberately the three fields every exporter agrees on. Soundiiz, TuneMyMusic and Exportify all
 * emit title, artist and duration in some column order, and Apple Music's XML has them too, so a
 * matcher built on these three works on every source without a per-service reader.
 *
 * [durationSeconds] is nullable because some exports omit it, and the matcher has to degrade rather
 * than refuse. It is also the field that does most of the work when present: see [score].
 */
data class WantedTrack(
    val title: String,
    val artist: String,
    val durationSeconds: Int? = null,
)

/** A candidate with the reason it scored what it did, so a review screen can say more than a number. */
data class Scored(
    val candidate: SongItem,
    val title: Double,
    val artist: Double,
    val duration: Double,
    val total: Double,
) {
    /** Whether this can be imported without a person looking at it. */
    val confident: Boolean get() = total >= CONFIDENT

    companion object {
        /**
         * Above this an import happens silently, below it the track goes to review.
         *
         * The cost is asymmetric, which is what sets it. A wrong silent import puts a karaoke
         * version or a sped-up edit in somebody's library and they may never work out why; a
         * needless review is one tap.
         *
         * Read off a sweep rather than chosen. Against 60 played songs from a real library, using
         * the library's own metadata, 0.84 auto-imports 87 percent with nothing wrong, while 0.80
         * buys three more points and lets a wrong one through. Above 0.84 it falls away fast, to
         * 73 percent at 0.88, for no gain that pass could measure.
         *
         * The honest caveat is the second pass, where the metadata is rewritten the way another
         * service would have it. There 0.84 auto-imports 77 percent and lets 3 percent through
         * wrong, and nothing below 0.88 reaches zero. That is worth knowing before trusting this
         * number, and worth re-measuring on a different library, because the one it was calibrated
         * against is close to the worst case: it is full of "(Slowed)", "(Sped Up)" and
         * "(Super Slowed)" edits, where the right answer and the wrong one differ by a single word
         * and a few seconds. Section 12 predicted 85 to 95 percent on mainstream catalogue and
         * much worse on heavily remixed material, and that is exactly the split this found.
         *
         * The practical reading: the review screen is not a fallback for this feature, it is part
         * of it. See MatchRateProbe, which prints the sweep this came from.
         */
        const val CONFIDENT = 0.84
    }
}

/**
 * Picks the YouTube result that is the track another service had, or nothing.
 *
 * The industry answer does not work here. Spotify and Apple Music both hand out an ISRC per
 * recording, which would be an exact join, but InnerTube exposes no ISRC anywhere, so there is
 * nothing to join on and matching has to be fuzzy.
 *
 * Fuzzy on three axes, and the third is the one that earns its place. Title and artist get a
 * candidate into contention, but they cannot separate the answers that are actually wrong: a
 * remix, a live take, a sped-up edit and a karaoke version all carry the right title and the right
 * artist. What they do not carry is the right length. So duration is not a tiebreak bolted on at
 * the end, it is the discriminator, and the weights say so.
 */
fun match(wanted: WantedTrack, candidates: List<SongItem>): Scored? =
    candidates.map { score(wanted, it) }.maxByOrNull { it.total }

fun score(wanted: WantedTrack, candidate: SongItem): Scored {
    val title = similarity(normalise(wanted.title), normalise(candidate.title))
    val artist = similarity(
        normalise(wanted.artist),
        normalise(candidate.artists.joinToString(" ") { it.name }),
    )
    val duration = durationScore(wanted.durationSeconds, candidate.duration)

    // Title and artist are a gate more than a score: something that fails either is not the track
    // under any length. Multiplying rather than adding is what makes that true, so a perfect
    // duration cannot carry a candidate whose name is wrong.
    val naming = TITLE_WEIGHT * title + ARTIST_WEIGHT * artist
    return Scored(candidate, title, artist, duration, naming * (DURATION_FLOOR + DURATION_LIFT * duration))
}

/**
 * How close two lengths are, in proportion to how long the track is.
 *
 * A few seconds between uploads of one recording is ordinary: a different master, a trimmed fade,
 * a silent tail. But a flat tolerance reads very differently at the two ends of a library. Six
 * seconds out of five minutes is two percent and almost certainly the same recording; six seconds
 * out of a hundred is six percent and is usually a different edit.
 *
 * Measured rather than assumed. With a flat three-to-twenty-second window the probe imported a
 * "(Super Slowed)" edit at 112s in place of the "(Slowed)" one at 106s, scoring 0.88 against a
 * threshold of 0.82, because six seconds looked small to a rule that had never heard of the track
 * being short. Scaling the window with the track is what separates those without making a
 * five-minute song fussy about its fade.
 *
 * A missing duration scores neutral rather than zero: an exporter that omitted the column has said
 * nothing about the track, and punishing silence would reject whole services.
 */
private fun durationScore(wanted: Int?, candidate: Int?): Double {
    if (wanted == null || candidate == null) return NEUTRAL_DURATION
    val off = abs(wanted - candidate)
    val exact = maxOf(EXACT_SECONDS, (wanted * EXACT_FRACTION).toInt())
    val wrong = maxOf(WRONG_SECONDS, (wanted * WRONG_FRACTION).toInt())
    return when {
        off <= exact -> 1.0
        off >= wrong -> 0.0
        else -> 1.0 - (off - exact).toDouble() / (wrong - exact)
    }
}

/**
 * Token overlap, which handles reordering and extra words that a prefix test does not.
 *
 * "Artist - Title" against "Title" and "Title (feat. X)" against "Title" both have to score well,
 * and both break a straight prefix comparison.
 *
 * Dice rather than intersection over the smaller side, and the difference is not academic. Over
 * the smaller side, any candidate that merely *contains* every word of the wanted title scores a
 * flat 1.0, because a superset has nothing missing. The probe caught that exactly: "(Slowed)" at
 * 106s was matched to "(Super Slowed)" at 112s and scored a perfect name, since every word of the
 * first appears in the second, and one extra word cost nothing. Dividing by the average of the two
 * sizes makes an extra word cost something, which is the whole difference between a track and an
 * edit of it.
 */
private fun similarity(a: String, b: String): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    val left = a.split(' ').filter { it.isNotEmpty() }.toSet()
    val right = b.split(' ').filter { it.isNotEmpty() }.toSet()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val shared = left.intersect(right).size
    return 2.0 * shared / (left.size + right.size)
}

/**
 * Everything one catalogue adds and another does not.
 *
 * The list is the decoration actually seen on exports and on YouTube: remaster years, single and
 * album version tags, featured-artist forms, and the upload furniture YouTube carries that no
 * service's export ever does.
 */
private val NOISE = Regex(
    "\\((?:feat|ft|with|featuring)[^)]*\\)" +
            "|\\[(?:feat|ft|with|featuring)[^\\]]*\\]" +
            "|\\b(?:feat|ft|featuring)\\.?\\s+[^-\\[(]*" +
            "|\\((?:[^)]*\\b(?:remaster|remastered|single|album|radio|mono|stereo|deluxe|bonus|version|edit|mix)\\b[^)]*)\\)" +
            "|\\[[^\\]]*\\b(?:remaster|remastered|single|album|radio|version|edit|mix)\\b[^\\]]*\\]" +
            "|\\b(?:official\\s+(?:music\\s+)?video|official\\s+audio|lyrics?\\s+video|lyrics?|audio|hd|hq|4k|mv)\\b" +
            "|\\b(?:remaster(?:ed)?)\\s*\\d{0,4}" +
            "|\\s+-\\s+(?:single|album|radio|mono|stereo)\\s+version\\b",
    RegexOption.IGNORE_CASE,
)

/** Latin letters keep their identity when an export strips or adds accents; everything else goes. */
internal fun normalise(text: String): String = text
    .replace(NOISE, " ")
    .lowercase()
    .let { stripAccents(it) }
    // Apostrophes sit inside a word, so they close up rather than split: "don't" has to meet
    // "dont". Every other mark separates words and becomes a space.
    .replace(Regex("['’´`]"), "")
    .replace(Regex("[^a-z0-9 ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun stripAccents(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

private const val TITLE_WEIGHT = 0.6
private const val ARTIST_WEIGHT = 0.4

/** A candidate with no usable duration still reaches [Scored.CONFIDENT] on a perfect name. */
private const val DURATION_FLOOR = 0.75
private const val DURATION_LIFT = 0.25
private const val NEUTRAL_DURATION = 0.5

/** Floors, so a very short track still gets a sane window rather than a fraction of nothing. */
private const val EXACT_SECONDS = 2
private const val WRONG_SECONDS = 10

/** And the proportional part, which is what does the work on anything of normal length. */
private const val EXACT_FRACTION = 0.015
private const val WRONG_FRACTION = 0.08

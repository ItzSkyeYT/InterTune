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
         *
         * Read again on 30 Sep 2026, after a version after a dash and one in brackets were made to
         * clean alike, over 300 played songs of the same library with the searches replayed so that
         * both runs scored the same candidates. The verbatim pass did not move: 78 percent imported
         * and one wrong. The rewritten pass went from 65 to 64 percent with the same six wrong, five
         * of them from the rewrite that drops the duration, where a perfect name alone scores 0.875.
         * A pass with versions written after a dash, as Spotify does, went from 75 to 81 percent
         * with none wrong. On the first 60, 0.84 is now the lowest threshold with nothing wrong in
         * the rewritten pass, where before it had one step to spare.
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
 * Featured-artist forms, bracketed or bare, which one catalogue puts in the title and another in
 * the artist list.
 *
 * Taken out before the version tags, because the bare form runs until the next dash or bracket and
 * needs them still there to know where to stop. It stops at a closing bracket too: YouTube writes
 * "Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)", and eating the bracket's end
 * would leave "Radio Edit" outside any tag.
 */
private val FEATURED = Regex(
    "\\((?:feat|ft|with|featuring)[^)]*\\)" +
            "|\\[(?:feat|ft|with|featuring)[^\\]]*\\]" +
            "|\\b(?:feat|ft|featuring)\\.?\\s+[^-\u2013\u2014\\[\\]()]*",
    RegexOption.IGNORE_CASE,
)

/**
 * A dash with a space each side and outside any bracket, which is how Spotify and Apple Music set a
 * version apart from the title. Not the hyphen in "Jay-Z", and not the one in "(Sped Up - Remix)".
 */
private val DASH = Regex("\\s+[-\u2013\u2014]\\s+(?![^()\\[\\]]*[)\\]])")

/** One bracket with no bracket inside it. */
private val BRACKET = Regex("\\([^()]*\\)|\\[[^\\[\\]]*\\]")

/**
 * The words in a version tag that say nothing about which recording it is.
 *
 * A remaster, the single or album version, the original mix and a radio edit are the track under
 * another label. A radio edit is shorter, but that is duration's job to notice, not the title's.
 */
private val GENERIC = Regex(
    "\\b(?:version|edit|mix|remaster(?:ed)?|digital|single|album|radio|mono|stereo|deluxe|bonus|track|edition|original|(?:19|20)\\d{2})\\b",
    RegexOption.IGNORE_CASE,
)
private val WORD = Regex("[\\p{L}\\p{N}]+")
private val YEAR = Regex("(?:19|20)\\d{2}")

/**
 * The version tags in [text], bracketed or after a dash, all cleaned by [versionTag].
 *
 * The two forms used to go through different rules. A bracket holding "version", "edit", "mix" or
 * "remaster" was deleted whole, while a dash suffix lost only "- Single Version" and a few like it.
 * Spotify writes the version after a dash and YouTube in brackets, so the same recording came out
 * two ways: Spotify's "Hide - CS01 Version" stayed "hide cs01 version" while YouTube's "Hide (CS01
 * Version)" became plain "hide", and the right answer, ranked first, went to review. Deleting the
 * bracket whole also threw away the part that tells versions apart, so "(Slowed Version)" and
 * "(Live Version)" read as the song itself.
 *
 * Only what follows a dash is a tag. What comes before the first one is left alone, since an
 * upload titled "Artist - Title" has the title there.
 */
private fun versionTags(text: String): String =
    text.split(DASH).mapIndexed { i, part ->
        val inner = BRACKET.replace(part) { versionTag(it.value) }
        if (i == 0) inner else versionTag(inner)
    }.joinToString(" ")

/**
 * [tag] without its generic words, if it is a version tag at all, and untouched if it is not.
 *
 * It is one when its last word, years aside, is generic: "CS01 Version", "Extended Mix", "Radio
 * Edit", "Remastered 2011". What else it holds is what tells the versions apart and stays: "cs01",
 * "extended", "slowed", "live", a remixer's name. A tag that does not end that way is kept whole,
 * which leaves "(Sittin' On)", "(Slowed)" and "- Skrillex Remix" as they were, and keeps a title
 * after a dash, like "Radio Ga Ga", from losing a word.
 */
private fun versionTag(tag: String): String {
    val last = WORD.findAll(tag).map { it.value }.lastOrNull { !YEAR.matches(it) } ?: return tag
    return if (GENERIC.matches(last)) tag.replace(GENERIC, " ") else tag
}

/**
 * The upload furniture YouTube carries that no service's export ever does, and remaster notes
 * written into the title itself rather than set off as a tag.
 */
private val NOISE = Regex(
    "\\b(?:official\\s+(?:music\\s+)?video|official\\s+audio|lyrics?\\s+video|lyrics?|audio|hd|hq|4k|mv)\\b" +
            "|\\b(?:remaster(?:ed)?)\\s*\\d{0,4}" +
            // Spotify writes the year first as often as last: "- 2013 Remaster" beside
            // "- Remastered 2011". Only the second form was stripped, which left "2013" in the
            // title and cost the import probe's Hotel California a fifth of its title score.
            "|\\b\\d{4}\\s+(?:digital\\s+)?remaster(?:ed)?\\b",
    RegexOption.IGNORE_CASE,
)

/** Latin letters keep their identity when an export strips or adds accents; everything else goes. */
internal fun normalise(text: String): String = versionTags(text.replace(FEATURED, " "))
    .replace(NOISE, " ")
    .lowercase()
    .let { stripAccents(it) }
    // Apostrophes sit inside a word, so they close up rather than split: "don't" has to meet
    // "dont". Every other mark separates words and becomes a space.
    .replace(Regex("['’´`]"), "")
    // Letters and digits of every script, not a to z: kept to Latin, a title in Japanese or
    // Cyrillic came out empty and scored nothing against anything, itself included.
    .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun stripAccents(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        // Composed again afterwards. Decomposing splits more than Latin accents: が becomes か
        // and a voicing mark outside the block removed above, and left apart the mark would
        // turn into a space and cut the word in two.
        .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }

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

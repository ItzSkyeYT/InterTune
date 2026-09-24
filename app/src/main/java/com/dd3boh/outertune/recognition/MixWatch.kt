/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import com.zionhuang.innertube.models.SongItem

/**
 * Tells a mashup from a song by the order Shazam names things in.
 *
 * Shazam only knows the pieces. On 24 Sep a Keep listening run over "Linkin Park / Slipknot /
 * Eminem - Damage [MASHUP]" named Faint seven times, then No Love once, then something it was unsure
 * of, then Faint again, and added Faint, which is not what was playing. That order is the tell. A
 * playlist moving on goes from one song to the next and never comes back; a mashup leaves its main
 * song for another and returns to it. So a song that is interrupted by something else and then
 * heard again, within a few minutes, is a piece of something bigger.
 *
 * How sure that makes it depends on the interruption. One window of something else could be Shazam
 * getting a single window wrong, which does happen on a noisy room, so that alone only earns a look
 * for the mashup. Two windows of it in a row, or a second interruption, is not a mistake repeated,
 * and the pieces come out of the list whether or not the mashup is found.
 *
 * Pure and fed by the engine, one call per window Shazam named, so it can be tested on the timeline
 * the real run produced.
 */
internal class MixWatch(private val spanMs: Long = SPAN_MS) {

    /** One window Shazam named, by its key, whether or not it was confident on YouTube. */
    data class Sighting(val key: String, val title: String, val artist: String?, val atMs: Long)

    /**
     * A song heard, left for something else, and heard again.
     *
     * @param pieces every different song seen from the first sighting of the returning one, the most
     * often heard first and otherwise oldest first. The search is built from the head of this, so a
     * one-window oddity in the middle, which the 24 Sep run had in "Lliving Life Mix", does not end
     * up in the query.
     * @param strong whether the interruption is too long, or too often, to be one wrong window.
     */
    data class Mix(val pieces: List<Sighting>, val strong: Boolean)

    private val seen = ArrayDeque<Sighting>()

    fun observe(sighting: Sighting): Mix? {
        seen.addLast(sighting)
        while (sighting.atMs - seen.first().atMs > spanMs) seen.removeFirst()

        val list = seen.toList()
        val previous = list.subList(0, list.size - 1).indexOfLast { it.key == sighting.key }
        if (previous < 0) return null
        // What came between this sighting and the last one of the same song. Empty is the song
        // simply carrying on.
        val interruption = list.subList(previous + 1, list.size - 1)
        if (interruption.isEmpty()) return null

        val first = list.indexOfFirst { it.key == sighting.key }
        val span = list.subList(first, list.size)
        // Runs of other songs between sightings of this one, this interruption included.
        val interruptions = span.indices.count { i ->
            i > 0 && span[i].key != sighting.key && span[i - 1].key == sighting.key
        }
        val counts = span.groupingBy { it.key }.eachCount()
        return Mix(
            // sortedByDescending is stable, so equal counts keep the order they were heard in.
            pieces = span.distinctBy { it.key }.sortedByDescending { counts[it.key] ?: 0 },
            // Two different songs in one gap, or a second gap. Two windows of one other song is
            // also what skipping back to the previous song looks like, so it does not count alone.
            strong = (interruption.size >= 2 && interruption.distinctBy { it.key }.size >= 2) ||
                    interruptions >= 2,
        )
    }

    /** Forgets everything, for a new run. */
    fun clear() = seen.clear()

    companion object {
        /**
         * How far back a return still counts. A mashup cuts back to its main song within a minute or
         * two; a song that comes round again after a whole other song has played is a playlist that
         * repeats, and the next song along would have ended the first long before this.
         */
        const val SPAN_MS = 150_000L
    }
}

/**
 * Finding the mashup on YouTube from the pieces Shazam heard in it.
 *
 * Searched as videos, because that is where mashups live: under the song filter the same queries
 * return the pieces themselves. Checked against the live search on 24 Sep (MixSearchProbe in
 * innertube): the pieces' artists with "mashup" put the Damage upload first in France and the US,
 * and their titles with "mashup" put it, a lyrics re-upload of it, and a different mashup of the
 * same two songs in the top three. So a result has to name at least two of the pieces to count at
 * all, and when two different uploads score the same the answer is a choice for the person, not a
 * guess.
 */
internal object MixSearch {

    /** The queries to run, most specific first. Empty when there is not enough to search with. */
    fun queries(pieces: List<MixWatch.Sighting>): List<String> {
        // The two most heard of each. More only narrows the search onto nothing.
        val titles = pieces.map { bareTitle(it.title) }.filter { it.isNotBlank() }.distinct().take(2)
        val artists = pieces.mapNotNull { it.artist?.let(::primaryArtist) }.filter { it.isNotBlank() }.distinct().take(2)
        return listOfNotNull(
            titles.takeIf { it.size >= 2 }?.joinToString(" ", postfix = " mashup"),
            artists.takeIf { it.size >= 2 }?.joinToString(" ", postfix = " mashup"),
        )
    }

    /** What came back, scored and ranked, best first, each once. Only results naming two pieces. */
    fun rank(pieces: List<MixWatch.Sighting>, results: List<List<SongItem>>): List<Pair<SongItem, Int>> {
        val scored = mutableMapOf<String, Pair<SongItem, Int>>()
        val hits = mutableMapOf<String, Int>()
        for (list in results) for (item in list) {
            val score = score(pieces, item) ?: continue
            hits[item.id] = (hits[item.id] ?: 0) + 1
            val best = scored[item.id]
            if (best == null || best.second < score) scored[item.id] = item to score
        }
        // Found by both queries is worth a point: the two ask different questions of the same songs.
        return scored.values
            .map { (item, score) -> item to score + if ((hits[item.id] ?: 0) > 1) 1 else 0 }
            .sortedByDescending { it.second }
    }

    /**
     * The mashup to take without asking, or null when there is none or it is a toss-up. Clear means
     * well ahead of the next one: a tie between two uploads is exactly the Damage case, where one
     * of them was a different mashup of the same songs.
     */
    fun clearWinner(ranked: List<Pair<SongItem, Int>>): SongItem? {
        val (top, score) = ranked.firstOrNull() ?: return null
        val next = ranked.getOrNull(1)?.second ?: 0
        return top.takeIf { score >= CLEAR_SCORE && score - next >= CLEAR_LEAD }
    }

    /**
     * The pieces as songs rather than as Shazam keys. Shazam can give one recording two keys, and
     * two keys for one song flipping back and forth is not a mashup of it with itself.
     */
    fun distinctSongs(pieces: List<MixWatch.Sighting>): List<MixWatch.Sighting> =
        pieces.distinctBy { words(bareTitle(it.title)) to it.artist?.let { a -> words(primaryArtist(a)) } }

    /** Two points a title named, one an artist, one for a word saying it is a mix. Null under two pieces. */
    internal fun score(pieces: List<MixWatch.Sighting>, item: SongItem): Int? {
        val text = " " + words(item.title + " " + item.artists.joinToString(" ") { it.name }) + " "
        var named = 0
        var score = 0
        for (piece in distinctSongs(pieces)) {
            val title = words(bareTitle(piece.title))
            val artist = piece.artist?.let { words(primaryArtist(it)) }.orEmpty()
            val titleHit = title.length >= 3 && " $title " in text
            val artistHit = artist.length >= 3 && " $artist " in text
            if (titleHit || artistHit) named++
            if (titleHit) score += 2
            if (artistHit) score += 1
        }
        if (named < 2) return null
        if (MIX_WORDS.containsMatchIn(item.title)) score += 1
        return score
    }

    /** "No Love (feat. Lil Wayne)" is "No Love", and so on. */
    internal fun bareTitle(title: String): String = title
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("\\s+-\\s+.*$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** The first name in a credit: "Eminem feat. Lil Wayne" is Eminem, "A & B" is A. */
    internal fun primaryArtist(artist: String): String =
        artist.split(Regex("\\s*(,|&| feat\\.? | ft\\.? | featuring | x | with )\\s*", RegexOption.IGNORE_CASE))
            .first()
            .trim()

    private fun words(text: String): String = text
        .lowercase()
        .replace(Regex("['’]"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val MIX_WORDS = Regex("mash ?up|medley|megamix|\\bmix\\b|remix|\\bvs\\.?\\b", RegexOption.IGNORE_CASE)

    /** Two titles and a mix word, or two titles and both artists. */
    private const val CLEAR_SCORE = 5
    private const val CLEAR_LEAD = 2
}

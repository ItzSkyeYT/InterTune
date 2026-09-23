/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.utils.SongVersions
import java.text.Normalizer

/**
 * Turns Last.fm's similar tracks for a song into songs the database already holds.
 *
 * Last.fm names its neighbours by title and artist and has no YouTube id for them, so each one is
 * looked up by a key made of both. Only songs already in the song table can match. That table
 * holds every song YouTube has shown beside a play, some 46,000 on the owner's library, and a
 * neighbour missing from it could only be reached with a YouTube search for each one: thirty
 * requests per play that nobody asked for.
 *
 * This is the matching the 23 Sep trial measured, when 57% of Last.fm's neighbours were found in
 * the library, with one change: letters outside the Latin alphabet are kept rather than reduced to
 * nothing, so a Japanese title can match at all.
 */
object SimilarMatch {
    /** One neighbour can be the same song uploaded several times; past this it is noise. */
    const val MAX_PER_NEIGHBOUR = 3

    private val FEATURING = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SEPARATOR = Regex("""\s*(?:,|&| x | feat\.? | ft\.? )\s*""", RegexOption.IGNORE_CASE)
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
    /** Latin accents only: Japanese voicing marks are combining too, and が is not か. */
    private val MARKS = Regex("""[\u0300-\u036F]+""")

    data class Neighbour(val title: String, val artist: String?)

    /** "Daft Punk & Pharrell Williams" is Daft Punk here, on both sides of the match. */
    fun firstArtist(artist: String?): String = artist.orEmpty().split(ARTIST_SEPARATOR, limit = 2).first().trim()

    /**
     * Base title and first artist, without version marks, featured artists or accents. Null when
     * either half comes out empty: a key with no title would match everything by that artist, and
     * one with no artist every song of that name.
     */
    fun key(title: String, artist: String?): String? {
        val name = fold(SongVersions.baseTitle(title.replace(FEATURING, ""), artist))
        val by = fold(firstArtist(artist).replace(NON_ALPHANUMERIC, " ").trim().lowercase())
        if (name.isEmpty() || by.isEmpty()) return null
        return "$name|$by"
    }

    /**
     * What to ask Last.fm for when the title as uploaded is not in its catalogue: "Take on Me",
     * not "Take on Me (Official Video) feat. Nobody". Null when that is no different from the
     * title, so the same question is never asked twice.
     */
    fun fallbackTitle(title: String, artist: String?): String? =
        SongVersions.plainTitle(title.replace(FEATURING, ""), artist).takeIf { it.isNotEmpty() && it != title }

    /**
     * What to ask Last.fm, in order, until one answers with something: the artist as stored, then
     * cut to its first name if that differs, each with the title and then its fallback. The trial
     * asked with the cut name only, which sends "Tyler" for Tyler, The Creator and "Simon" for
     * Simon & Garfunkel; the cut name stays as the second try for "Daft Punk & Pharrell Williams".
     */
    fun questions(title: String, artist: String?): List<Pair<String, String>> {
        val plain = fallbackTitle(title, artist)
        return listOf(artist.orEmpty().trim(), firstArtist(artist))
            .filter { it.isNotEmpty() }
            .distinct()
            .flatMap { a -> listOfNotNull(a to title, plain?.let { a to it }) }
    }

    /** Beyoncé and Beyonce are one artist; which one an upload used is an accident. */
    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")

    /** Every song in the table by its key. Built once and reused: keying 46,000 titles is not free. */
    class Index(songs: Iterable<SongRow>) {
        private val byKey = HashMap<String, MutableList<String>>()

        init {
            for (s in songs) key(s.title, s.artistName)?.let { byKey.getOrPut(it) { ArrayList(1) }.add(s.id) }
        }

        operator fun get(key: String): List<String> = byKey[key].orEmpty()
    }

    /**
     * The songs a seed's neighbours come to, most similar first, each once, leaving out the seed
     * and any version of it.
     */
    fun match(seedId: String, seedTitle: String, seedArtist: String?, neighbours: List<Neighbour>, index: Index): List<String> {
        val seedKey = key(seedTitle, seedArtist)
        val out = LinkedHashSet<String>()
        for (n in neighbours) {
            val k = key(n.title, n.artist) ?: continue
            if (k == seedKey) continue
            for (id in index[k].take(MAX_PER_NEIGHBOUR)) if (id != seedId) out += id
        }
        return out.toList()
    }
}

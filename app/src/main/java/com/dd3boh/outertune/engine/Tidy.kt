/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.utils.SongVersions

/** A song the listener has just had: enough of it in the last day, or any of it this session. */
data class PlayedSong(val id: String, val title: String, val artist: String?)

/**
 * The key two cards share when they are the same song to a listener: the title with its version
 * marks stripped, and the first artist. "Africa" and "Africa (Live)" by Toto share one; "Africa"
 * by someone else does not, because that is a different song with the same name.
 *
 * The artist goes into the base title as well, so an upload called "Toto - Africa" is read as
 * Africa. Without it "Pizza Hotline - Automata | MOTORSLICE OST" keyed as "pizza hotline", and
 * so did three other Pizza Hotline songs, so banning any one of them banned all four, for good.
 */
fun versionKey(title: String, artist: String?): String =
    SongVersions.baseTitle(title, artist) + "|" + artist.orEmpty().trim().lowercase()

/**
 * One pass over Home's rows in screen order. A row is handed over with its songs picked out by
 * three lookups (anything that is not a song passes through untouched), and what the pass keeps
 * it remembers, so no later row shows a song, or a version of a song, that an earlier row already
 * has. The Quick picks row is also asked to be fresh: nothing just played, and no version of it.
 */
class TidyPass(
    justPlayed: Collection<PlayedSong>,
    /** Songs the listener banned, with their versions: matched by id and by key. */
    bannedSongs: Collection<PlayedSong> = emptyList(),
    /** Artists the listener banned or snoozed, by artist id and by name. */
    private val bannedArtists: Set<String> = emptySet(),
) {
    private val playedIds = justPlayed.mapTo(HashSet()) { it.id }
    private val playedKeys = justPlayed.mapTo(HashSet()) { versionKey(it.title, it.artist) }
    private val bannedIds = bannedSongs.mapTo(HashSet()) { it.id }
    private val bannedKeys = bannedSongs.mapTo(HashSet()) { versionKey(it.title, it.artist) }
    private val seen = HashSet<String>()

    fun <T> row(
        items: List<T>,
        freshOnly: Boolean = false,
        id: (T) -> String?,
        title: (T) -> String?,
        artist: (T) -> String?,
        /** The artist's id where the item has one, for the artist bans. */
        artistId: (T) -> String? = { null },
        /**
         * Items the just-played rule should not touch by id.
         *
         * The engine's Again lane exists to offer a song you have heard well lately and are due
         * to hear again, admitting anything from an hour to a fortnight old. The just-played rule
         * then removed anything heard in the last day, so the two rules argued and the newer one
         * won: 72.7 percent of Again cards were culled after being placed, against 7 to 18 percent
         * for every other lane. The lane was doing its job and nobody ever saw the result.
         *
         * The whole just-played rule is waived for these, not only the id half. Waiving the id
         * alone does nothing, because a song's own version key is in the played keys too: play
         * "Africa" and both halves of the rule name it. A test said so before this shipped.
         *
         * What still protects the row is the line below: an exempt card claims its version key
         * like any other, so nothing further down can be another cut of it, and the engine's own
         * group rule already stops two versions inside one build.
         */
        exemptFromJustPlayed: (T) -> Boolean = { false },
        /**
         * How many cards one artist may hold in this row.
         *
         * Not about repetition for its own sake. The engine has no idea what genre anything is:
         * the tags it reads are treatments, slowed and reverbed and bass boosted, not styles. So
         * when a row comes out as ten of the same kind of thing, the only handle on it is that
         * one sort of music tends to arrive from a handful of artists at once. Capping them is a
         * blunt instrument aimed at a real problem, and it is the sharpest one available without
         * genre data the app does not have.
         *
         * Off by default, because it only makes sense where a row is meant to be varied.
         */
        maxPerArtist: Int = Int.MAX_VALUE,
    ): List<T> {
        val perArtist = HashMap<String, Int>()
        return items.filter { item ->
        val itemId = id(item) ?: return@filter true
        val itemTitle = title(item) ?: return@filter true
        val itemArtist = artist(item)
        val key = versionKey(itemTitle, itemArtist)
        if (itemId in bannedIds || key in bannedKeys) return@filter false
        if (bannedArtists.isNotEmpty() && (artistId(item)?.let { it in bannedArtists } == true || itemArtist?.trim()?.lowercase()?.let { it in bannedArtists } == true)) return@filter false
        // A preview is thirty seconds and a fade. It is never the thing somebody wanted, and it
        // costs the slot twice: once when it plays, again when they go and find the real one.
        if (SongTags.isPreview(itemTitle)) return@filter false
        if (freshOnly && !exemptFromJustPlayed(item) && (itemId in playedIds || key in playedKeys)) return@filter false
        // Order is preserved, so the ones that survive are the best of that artist rather than
        // whichever happened to come first.
        if (maxPerArtist < Int.MAX_VALUE) {
            val who = itemArtist?.trim()?.lowercase()
            if (!who.isNullOrEmpty()) {
                val had = perArtist.getOrDefault(who, 0)
                if (had >= maxPerArtist) return@filter false
                perArtist[who] = had + 1
            }
        }
        seen.add(key)
        }
    }
}

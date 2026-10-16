/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.versionKey
import com.dd3boh.outertune.models.MediaMetadata
import kotlin.random.Random

/**
 * What of a radio's next page joins the queue, and which song the page after it is asked from.
 *
 * When a radio queue runs low, the next page is asked from a song already in the queue, so it comes
 * from the same neighbourhood and much of it is the queue again. It used to be appended whole. In
 * the first half of October 2026, 3.9 percent of the songs that autoplayed had already played
 * earlier in the same run, with other songs in between, and 74 percent of those were skipped.
 *
 * So a song joins only when the queue does not hold it and this run has not reached it, by id and
 * by version ([versionKey]: the base title and the first artist), so a live take of a song heard an
 * hour ago counts as that song. The page keeps its order otherwise, and a song it lists twice
 * joins once.
 *
 * No Android in here, so it is tested.
 */
object RadioPage {

    /** How many of the newest songs the next page is asked from, one of them picked at random. */
    const val SEED_POOL = 4

    /**
     * The songs of [page] that may join: none that [queue] holds, none this run has [reached] (ids)
     * and none that is a version of either ([reachedVersions], see [AdaptiveQueue.Heard]).
     */
    fun fresh(
        page: List<MediaMetadata>,
        queue: List<MediaMetadata>,
        reached: Set<String> = emptySet(),
        reachedVersions: Set<String> = emptySet(),
    ): List<MediaMetadata> {
        val ids = HashSet<String>(reached)
        queue.mapTo(ids) { it.id }
        val versions = HashSet<String>(reachedVersions)
        queue.mapNotNullTo(versions) { versionOf(it) }
        // Each song that is let through is remembered as it goes, so the page cannot bring one
        // twice either. A song turned away is remembered too: it is one already had.
        return page.filter { song ->
            val newSong = ids.add(song.id)
            val newVersion = versionOf(song)?.let { versions.add(it) } ?: true
            newSong && newVersion
        }
    }

    /**
     * The song the page after this one is asked from.
     *
     * [anchor], a song the listener let finish, when there is one. Otherwise one of the last songs
     * that [joined], as it always was. When the page brought nothing new, one of the newest songs in
     * [queue] other than [asked], the song this page was asked from, so that the next song change
     * asks somewhere else instead of getting the same page again. [asked] itself only when the queue
     * holds nothing else.
     */
    fun nextSeed(
        anchor: String?,
        joined: List<MediaMetadata>,
        queue: List<MediaMetadata>,
        asked: String,
        random: Random = Random.Default,
    ): String = anchor
        ?: joined.takeLast(SEED_POOL).randomOrNull(random)?.id
        ?: queue.filter { it.id != asked }.takeLast(SEED_POOL).randomOrNull(random)?.id
        ?: asked

    fun versionOf(song: MediaMetadata): String? = versionOf(song.title, song.artists.firstOrNull()?.name)

    /**
     * The version a song belongs to, or null for a title with nothing to compare, such as one that
     * is nothing but brackets: that song is matched by its id alone, as SongVersions.isVersionOf
     * does. A key with no base title starts at the separator, since the base never holds one.
     */
    fun versionOf(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        return versionKey(title, artist).takeUnless { it.startsWith("|") }
    }
}

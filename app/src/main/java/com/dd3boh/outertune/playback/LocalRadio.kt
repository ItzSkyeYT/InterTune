/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.SongVersions

/**
 * A radio made from what the phone knows, for when YouTube gives none however it is asked
 * (RadioLadder; Unreleased.RADIO_FALLBACK): the queue says so in a line of its own.
 *
 * Its songs come from six lists around the song, read by MusicService (RadioSql), and are taken
 * from each in turn so no one of them makes the radio:
 *  - [Sources.lastFm]: Last.fm's similar tracks for the song, which he found "way more solid" than
 *    YouTube's (25 Sep 2026); stored only where Last.fm is the source of similar songs.
 *  - [Sources.youtube]: YouTube's related list for the song, stored when it last played.
 *  - [Sources.heardWith]: the listener's own, what they heard in the sessions they heard it in.
 *  - [Sources.listing]: the songs whose own lists name it.
 *  - [Sources.byItsArtists]: its artists' songs, those heard most first.
 *  - [Sources.nextHop]: what its neighbours list, the most named first.
 *
 * Only songs that can play ([Rules]): never one the listener keeps out (a ban, a snooze, a rest, an
 * artist kept out), never one gone from YouTube or one that needs YouTube while it cannot be had,
 * unless a copy is on the phone, and with a content switch on only songs the listener chose (liked,
 * saved, downloaded, a file), since a stored song keeps no mark of what the switches hide (as
 * QueueStart's stored related list does not, for the same reason). Never the song itself, a version
 * of it or a song twice. No artist takes more than [perArtist] places while others are left; when
 * nothing else is left, the rest may be theirs, so a radio of one artist's library is still one.
 *
 * No Android in here, so it is tested (LocalRadioTest).
 */
internal object LocalRadio {
    /** How many songs a radio made here starts with: about a page of YouTube's. */
    const val SIZE = 25

    class Sources(
        val lastFm: List<MediaMetadata> = emptyList(),
        val youtube: List<MediaMetadata> = emptyList(),
        val heardWith: List<MediaMetadata> = emptyList(),
        val listing: List<MediaMetadata> = emptyList(),
        val byItsArtists: List<MediaMetadata> = emptyList(),
        val nextHop: List<MediaMetadata> = emptyList(),
    ) {
        /** In the order they are taken from. */
        val all get() = listOf(lastFm, youtube, heardWith, listing, byItsArtists, nextHop)
    }

    /**
     * [keptOut] song ids and [artistsKeptOut] artist ids and lower-case names; [plays] whether a
     * song can play now; [chosenOnly] with a content switch on, and [chosen] whether the listener
     * chose a song.
     */
    class Rules(
        val keptOut: Set<String> = emptySet(),
        val artistsKeptOut: Set<String> = emptySet(),
        val plays: (MediaMetadata) -> Boolean = { true },
        val chosenOnly: Boolean = false,
        val chosen: (MediaMetadata) -> Boolean = { it.liked || it.inLibrary != null || it.isLocal },
    )

    fun pick(seed: MediaMetadata, sources: Sources, rules: Rules, size: Int = SIZE): List<MediaMetadata> {
        val seen = HashSet<String>().apply { add(seed.id) }
        val versions = HashSet<String>().apply { RadioPage.versionOf(seed)?.let(::add) }
        fun allowed(song: MediaMetadata): Boolean {
            if (song.id in seen || song.id in rules.keptOut) return false
            if (song.artists.any { a -> a.id?.let { it in rules.artistsKeptOut } == true || a.name.trim().lowercase() in rules.artistsKeptOut }) return false
            if (SongVersions.isVersionOf(song.title, seed.title)) return false
            if (RadioPage.versionOf(song)?.let { it in versions } == true) return false
            if (rules.chosenOnly && !rules.chosen(song)) return false
            return rules.plays(song)
        }

        val picked = ArrayList<MediaMetadata>(size)
        val perArtistTaken = HashMap<String, Int>()
        fun take(song: MediaMetadata) {
            picked += song
            seen += song.id
            RadioPage.versionOf(song)?.let(versions::add)
            artistOf(song)?.let { perArtistTaken[it] = (perArtistTaken[it] ?: 0) + 1 }
        }

        // Each list in turn, a song from each, the artist's share held; then the same without it.
        val lists = sources.all.map { it.iterator() }
        val cap = perArtist(size)
        val heldBack = ArrayList<MediaMetadata>()
        var moved = true
        while (picked.size < size && moved) {
            moved = false
            for (list in lists) {
                if (picked.size >= size) break
                while (list.hasNext()) {
                    val song = list.next()
                    if (!allowed(song)) continue
                    val artist = artistOf(song)
                    if (artist != null && (perArtistTaken[artist] ?: 0) >= cap) {
                        heldBack += song
                        continue
                    }
                    take(song)
                    moved = true
                    break
                }
            }
        }
        for (song in heldBack) {
            if (picked.size >= size) break
            if (allowed(song)) take(song)
        }
        return picked
    }

    /** At most a quarter of the radio, and never fewer than three. */
    fun perArtist(size: Int) = maxOf(3, size / 4)

    private fun artistOf(song: MediaMetadata): String? =
        song.artists.firstOrNull()?.let { it.id ?: it.name.trim().lowercase() }?.takeIf { it.isNotEmpty() }
}

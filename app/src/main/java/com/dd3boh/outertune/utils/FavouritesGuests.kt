/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlin.random.Random

/**
 * Songs by the favourites that go by for each song by a guest.
 *
 * Three, so one song in four is by somebody the listener did not choose. The request was for
 * "sometimes an artist I did not pick", and the two ways to get that wrong pull in opposite
 * directions. Much less than a quarter and the switch appears to do nothing: with ten favourites
 * one guest a round is a stranger every forty minutes. Much more and it stops being the
 * favourites' mix, since at one in two any stretch of it sounds like a station that happens to
 * play them. At one in four a guest turns up about every quarter of an hour and three songs out
 * of any four are still the ones that were asked for.
 */
const val FAVOURITES_PER_GUEST = 3

/**
 * The fewest similar artists invited. With one or two favourites, as many guests as favourites
 * would be the same stranger in every guest place.
 */
const val FEWEST_GUEST_ARTISTS = 3

/**
 * How many similar artists join: as many as there are favourites.
 *
 * The guests share a third as many places as the favourites have, so with equal numbers each
 * guest is heard about a third as often as each favourite. The favourites lead artist for artist
 * as well as song for song, and nobody who was not chosen becomes a regular.
 */
fun guestArtistsInvited(favouriteArtists: Int): Int =
    if (favouriteArtists <= 0) 0 else maxOf(FEWEST_GUEST_ARTISTS, favouriteArtists)

/**
 * The guests in the order they will be dealt in, from everything that might be one.
 *
 * [candidates] are songs YouTube lists beside the favourites' songs (FavouritesSql has the query
 * and where they come from), each with a [strength]: how many of the favourites' songs list it.
 * An artist's strength is the sum over their songs, which is how often YouTube has put them next
 * to the favourites, and the strongest [guestArtistsInvited] are the ones invited. A tie falls by
 * the artist's key, so the same library invites the same guests every time.
 *
 * Then the same dealing as for the favourites, [interleaveByArtist], and for the same reason: one
 * similar artist with forty songs known would otherwise hold every guest place.
 *
 * A candidate whose [artist] is null is left out, as interleaveBy leaves out a song with no
 * artist, and a song listed twice is queued once.
 */
fun <T, K> guestQueue(
    candidates: List<T>,
    favouriteArtists: Int,
    random: Random = Random.Default,
    id: (T) -> String,
    strength: (T) -> Int,
    artist: (T) -> K?,
): List<T> {
    val invited = guestArtistsInvited(favouriteArtists)
    if (invited == 0) return emptyList()

    val seen = HashSet<String>()
    val byArtist = LinkedHashMap<K, MutableList<T>>()
    for (candidate in candidates) {
        val key = artist(candidate) ?: continue
        if (seen.add(id(candidate))) byArtist.getOrPut(key) { mutableListOf() } += candidate
    }
    val strongest = byArtist.entries
        .sortedWith(compareByDescending<Map.Entry<K, List<T>>> { (_, songs) -> songs.sumOf(strength) }.thenBy { it.key.toString() })
        .take(invited)
        // Sorted within the artist too, so that the shuffle below starts from the same order
        // whichever way the query happened to return the rows.
        .map { (_, songs) -> songs.sortedBy(id) }
    return interleaveByArtist(strongest, random)
}

/**
 * The favourites, in the order they already have, with guests dealt in between them.
 *
 * The favourites are taken three at a time and each whole three earns one guest, placed after the
 * first, second or third of them. So the mix opens on a favourite, two guests never play back to
 * back, and a guest is not always the fourth song, which would be a rotation somebody hears after
 * a few minutes. No stretch from the start ever holds more than its one in four.
 *
 * The favourites themselves are not touched: take the guests out and what is left is exactly what
 * went in. That is what makes the strict switch honest, since turning it on removes songs and
 * moves none.
 *
 * Fewer than three favourites left at the end earn nothing, and when the guests run out the
 * favourites carry on alone. A guest's place is drawn for every three whether or not a guest is
 * left for it, so the same [random] puts the same guests in the same places however long the list
 * of favourites grows.
 */
fun <T> dealGuests(favourites: List<T>, guests: List<T>, random: Random = Random.Default): List<T> {
    if (guests.isEmpty()) return favourites

    val out = ArrayList<T>(favourites.size + favourites.size / FAVOURITES_PER_GUEST)
    val waiting = guests.iterator()
    for (three in favourites.chunked(FAVOURITES_PER_GUEST)) {
        val after = 1 + random.nextInt(FAVOURITES_PER_GUEST)
        if (three.size < FAVOURITES_PER_GUEST || !waiting.hasNext()) {
            out += three
            continue
        }
        out += three.subList(0, after)
        out += waiting.next()
        out += three.subList(after, three.size)
    }
    return out
}

/**
 * [guests] with everything that belongs to the favourites taken out, and nothing twice.
 *
 * The query already refuses a song with a bookmarked artist on it. This is the same rule applied
 * again at the moment of dealing, because the two are read at different times: an artist
 * bookmarked while the mix is on screen turns their songs into favourites at once, and a guest
 * read a minute earlier would then be dealt in beside itself.
 */
fun <T, K> strangersOnly(
    guests: List<T>,
    favouriteIds: Set<String>,
    favouriteArtists: Set<K>,
    id: (T) -> String,
    artists: (T) -> Collection<K>,
): List<T> {
    val seen = HashSet<String>()
    return guests.filter { guest ->
        val guestId = id(guest)
        guestId !in favouriteIds && artists(guest).none { it in favouriteArtists } && seen.add(guestId)
    }
}

/**
 * The favourites mix as the screen shows it: [favourites] alone when [strict], and otherwise with
 * the strangers among [guests] dealt in.
 *
 * Strict returns the list it was given, the same object, so the mix is what it was before there
 * were guests by construction and not by two code paths happening to agree.
 */
fun <T, K> favouritesMix(
    favourites: List<T>,
    guests: List<T>,
    strict: Boolean,
    favouriteArtists: Set<K>,
    random: Random = Random.Default,
    id: (T) -> String,
    artists: (T) -> Collection<K>,
): List<T> {
    if (strict) return favourites
    val favouriteIds = favourites.mapTo(HashSet(), id)
    return dealGuests(favourites, strangersOnly(guests, favouriteIds, favouriteArtists, id, artists), random)
}

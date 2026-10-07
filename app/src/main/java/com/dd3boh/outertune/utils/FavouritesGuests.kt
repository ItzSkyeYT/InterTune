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
 * The fewest similar artists invited, when there are that many. A mix small enough to have one or
 * two guest places would otherwise give them to the same stranger on every visit.
 */
const val FEWEST_GUEST_ARTISTS = 3

/** How many guests a mix of this many favourites' songs has room for: one for every whole three. */
fun guestPlaces(favouriteSongs: Int): Int = favouriteSongs.coerceAtLeast(0) / FAVOURITES_PER_GUEST

/**
 * The most songs any one guest is given: a third of what a favourite holds on average, rounded up.
 *
 * The guests share a third as many places as the favourites have songs. Held to this, no guest is
 * heard more than a third as often as the average favourite, however many of their songs YouTube
 * lists. So the favourites lead artist for artist as well as song for song, and nobody who was
 * not chosen becomes a regular.
 */
fun songsPerGuest(favouriteSongs: Int, favouriteArtists: Int): Int {
    if (favouriteSongs <= 0 || favouriteArtists <= 0) return 0
    val shared = favouriteArtists * FAVOURITES_PER_GUEST
    return (favouriteSongs + shared - 1) / shared
}

/**
 * The guests in the order they will be dealt in, from everything that might be one.
 *
 * [candidates] are songs YouTube lists beside the favourites' songs (FavouritesSql has the query
 * and where they come from), each with a [strength]: how many of the favourites' songs list it.
 * An artist's strength is the sum over their songs, which is how often YouTube has put them next
 * to the favourites. A tie falls by the artist's key, so the same library invites the same guests
 * every time.
 *
 * Artists are invited strongest first, each bringing the songs listed most up to [songsPerGuest],
 * until there are enough songs for every guest place, and at least [FEWEST_GUEST_ARTISTS] of them.
 * This was first written as "as many guests as there are favourites", which read well and was
 * wrong against a real library. Ten bookmarked artists, 283 songs, so 94 places: the eight
 * strongest similar artists had 29 songs listed between them, two to five each. The guests ran
 * out a third of the way down and the rest of the mix had none. Filling the places took 21 artists
 * there, none with more than eleven songs against a favourite's thirty-five on average.
 *
 * Then the same dealing as for the favourites, [interleaveByArtist], and for the same reason: one
 * similar artist with a dozen songs would otherwise sit in a dozen places running.
 *
 * A candidate whose [artist] is null is left out, as interleaveBy leaves out a song with no
 * artist, and a song listed twice is queued once.
 */
fun <T, K> guestQueue(
    candidates: List<T>,
    favouriteSongs: Int,
    favouriteArtists: Int,
    random: Random = Random.Default,
    id: (T) -> String,
    strength: (T) -> Int,
    artist: (T) -> K?,
): List<T> {
    val places = guestPlaces(favouriteSongs)
    val each = songsPerGuest(favouriteSongs, favouriteArtists)
    if (places == 0 || each == 0) return emptyList()

    val seen = HashSet<String>()
    val byArtist = LinkedHashMap<K, MutableList<T>>()
    for (candidate in candidates) {
        val key = artist(candidate) ?: continue
        if (seen.add(id(candidate))) byArtist.getOrPut(key) { mutableListOf() } += candidate
    }
    val strongestFirst = byArtist.entries
        .sortedWith(compareByDescending<Map.Entry<K, List<T>>> { (_, songs) -> songs.sumOf(strength) }.thenBy { it.key.toString() })

    val invited = ArrayList<List<T>>()
    var songs = 0
    for ((_, theirs) in strongestFirst) {
        if (songs >= places && invited.size >= FEWEST_GUEST_ARTISTS) break
        // By id after strength, so that the cut, and the shuffle that follows, start from the
        // same order whichever way the query happened to return the rows.
        val brought = theirs.sortedWith(compareByDescending(strength).thenBy(id)).take(each)
        invited += brought
        songs += brought.size
    }
    return interleaveByArtist(invited, random)
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

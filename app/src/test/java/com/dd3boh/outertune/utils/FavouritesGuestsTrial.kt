/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.FavouritesSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random

/**
 * The favourites mix with its guests, on a real library. Gated on `FAVOURITES_DB=<a copy of
 * song.db>`, so nothing private is needed for the ordinary run; the database is copied again
 * before it is opened.
 *
 * It reads what AutoPlaylistViewModel reads, the favourites and the songs YouTube lists beside
 * them, with the same two queries and the same limits, and deals them the way it does. The rule
 * for who is invited was first written to read well and was wrong against a real library, with
 * 29 guest songs for 94 places, which no test on made-up data would have shown. This is where to
 * look before changing it again.
 *
 * What holds on any library is asserted: the favourites keep their order, a favourite is never a
 * guest, nobody plays twice, the share is kept and no guest brings more than theirs. The figures
 * are printed, and no title or name with them.
 */
class FavouritesGuestsTrial {

    private class Track(val id: String, val artists: List<String>, val refs: Int = 0)

    private fun <T> Connection.rows(sql: String, vararg args: Long, read: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, value -> ps.setLong(i + 1, value) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }

    @Test
    fun `the mix with its guests on a real library`() {
        val path = System.getenv("FAVOURITES_DB")
        assumeTrue("set FAVOURITES_DB to run", !path.isNullOrBlank())
        val copy = File.createTempFile("favourites-trial", ".db").apply { deleteOnExit() }
        File(path!!).copyTo(copy, overwrite = true)

        DriverManager.getConnection("jdbc:sqlite:${copy.absolutePath}").use { db ->
            // Everybody billed on each song, in the order they are billed, and who is bookmarked.
            val billed = HashMap<String, MutableList<String>>()
            db.rows("SELECT songId, artistId FROM song_artist_map ORDER BY songId, position") {
                it.getString(1) to it.getString(2)
            }.forEach { (song, artist) -> billed.getOrPut(song) { mutableListOf() } += artist }
            val bookmarked = db.rows("SELECT id FROM artist WHERE bookmarkedAt IS NOT NULL") { it.getString(1) }.toSet()

            val favourites = db.rows(FavouritesSql.BY_BOOKMARKED_ARTISTS) { it.getString("id") }
                .map { Track(it, billed[it].orEmpty()) }
            assumeTrue("nothing is bookmarked in this library", favourites.isNotEmpty())
            val favouriteArtists = favourites.flatMap { it.artists }.filter { it in bookmarked }.distinct().size

            val readStarted = System.nanoTime()
            val candidates = db.rows(FavouritesSql.SIMILAR_TO_BOOKMARKED_ARTISTS, System.currentTimeMillis(), 1000) {
                it.getString("id") to it.getInt("refs")
            }.map { (id, refs) -> Track(id, billed[id].orEmpty(), refs) }
            val readMs = (System.nanoTime() - readStarted) / 1_000_000

            for (seed in 1L..20L) {
                val ordered = interleaveBy(favourites, Random(seed)) { song -> song.artists.firstOrNull { it in bookmarked } }
                val queue = guestQueue(
                    candidates = candidates,
                    favouriteSongs = favourites.size,
                    favouriteArtists = favouriteArtists,
                    random = Random(seed + 1),
                    id = { it.id },
                    strength = { it.refs },
                    artist = { it.artists.firstOrNull() },
                ).take(300)
                val mix = favouritesMix(ordered, queue, strict = false, bookmarked, Random(seed + 2), { it.id }) { it.artists }

                val favouriteIds = ordered.map { it.id }.toSet()
                val guests = mix.filter { it.id !in favouriteIds }
                assertEquals("seed $seed: the favourites moved", ordered.map { it.id }, mix.filter { it.id in favouriteIds }.map { it.id })
                assertEquals("seed $seed: somebody plays twice", mix.size, mix.map { it.id }.toSet().size)
                assertTrue("seed $seed: a favourite was dealt in as a guest", guests.none { guest -> guest.artists.any { it in bookmarked } })
                assertTrue("seed $seed: more than one song in four", guests.size <= guestPlaces(ordered.size))
                assertEquals("seed $seed: places left empty with guests waiting", minOf(queue.size, guestPlaces(ordered.size)), guests.size)
                assertFalse("seed $seed: opened on a guest", mix.first().id !in favouriteIds)
                for (i in 1 until mix.size) {
                    assertFalse("seed $seed: two guests back to back at $i", mix[i - 1].id !in favouriteIds && mix[i].id !in favouriteIds)
                }
                val most = queue.groupingBy { it.artists.first() }.eachCount().values.maxOrNull() ?: 0
                assertTrue("seed $seed: a guest brought $most songs", most <= songsPerGuest(favourites.size, favouriteArtists))
                assertEquals(
                    "seed $seed: kept to the favourites it is not the mix as it was",
                    ordered,
                    favouritesMix(ordered, queue, strict = true, bookmarked, Random(seed + 2), { it.id }) { it.artists },
                )

                if (seed == 1L) {
                    val withLists = db.rows(
                        """
                        SELECT COUNT(DISTINCT r.songId) FROM artist fave
                            CROSS JOIN song_artist_map theirs ON theirs.artistId = fave.id
                            CROSS JOIN related_song_map r ON r.songId = theirs.songId
                        WHERE fave.bookmarkedAt IS NOT NULL AND r.source = 0
                        """
                    ) { it.getInt(1) }.single()
                    val lastGuest = mix.indexOfLast { it.id !in favouriteIds }
                    println("favourites: ${favourites.size} songs by $favouriteArtists artists, $withLists with a related list stored")
                    println("candidates: ${candidates.size} songs by ${candidates.mapNotNull { it.artists.firstOrNull() }.distinct().size} artists, read in $readMs ms")
                    println("places: ${guestPlaces(favourites.size)}, at most ${songsPerGuest(favourites.size, favouriteArtists)} songs a guest")
                    println("invited: ${queue.map { it.artists.first() }.distinct().size} artists with ${queue.size} songs, the most from one $most")
                    println("mix: ${mix.size} songs, ${guests.size} of them guests, the last guest at ${lastGuest + 1}")
                }
            }
        }
    }
}

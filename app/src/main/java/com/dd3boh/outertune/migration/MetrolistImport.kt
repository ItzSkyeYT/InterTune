/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Bringing a Metrolist library over (his word of 14 Nov 2026, for 0.13). A Metrolist backup is a zip holding its
 * whole database, song.db, from its Android app (database versions 36 to 39) or from its new one built on KMP (65 on
 * 14 Nov). Metrolist and InterTune both come from InnerTune, so the tables both databases have keep the same keys and
 * the same meaning, and dates are stored alike: epoch milliseconds in UTC on both sides. What is copied is every
 * column both tables have, read from the two databases themselves, so a later Metrolist version is read the same way.
 *
 * Nothing of ours is replaced or removed. A row we already have stays as it is, except that a song liked or added to
 * the library there is liked or added here too. A play we already have is not added twice, a month's play count is
 * the larger of the two, and a playlist we already have, or already follow on YouTube, is not made again: the same
 * backup brought over twice changes nothing the second time.
 *
 * Their database is read on its own and never attached to ours: Android turns write-ahead logging off for good on a
 * database the moment another one is attached to it.
 */
object MetrolistImport {

    /** One database, as this needs it: theirs is only read, ours is written inside one transaction. */
    interface Db {
        fun tables(): Set<String>
        fun columns(table: String): List<String>
        fun userVersion(): Int
        fun query(sql: String, args: List<Any?> = emptyList(), row: (List<Any?>) -> Unit)

        /** Runs [sql] and says how many rows it changed. A row a constraint refuses throws. */
        fun exec(sql: String, args: List<Any?> = emptyList()): Int
    }

    /** The first version of Metrolist's own database this reads: Metrolist's own importer starts there too. */
    const val FIRST_VERSION = 36

    /** Without these a database is not Metrolist's. */
    private val THEIRS = setOf("song", "artist", "album", "playlist", "playlist_song_map", "song_artist_map", "event")

    /** OuterTune's queues, which InnerTune and Metrolist never had: a database with them is ours, for Restore. */
    private val OURS_ONLY = setOf("queue", "queue_song_map")

    /** The maps and the per-song tables, copied as they are once their songs, artists and albums are in. */
    private val PER_SONG = listOf("song_artist_map", "song_album_map", "album_artist_map", "format", "lyrics")

    enum class Refusal {
        /** Not a database Metrolist wrote. */
        NOT_METROLIST,

        /** InterTune's or OuterTune's own: Restore takes it. */
        OURS,

        /** InnerTune's, or an old Metrolist's: Restore takes InnerTune's, Metrolist itself refuses old ones too. */
        TOO_OLD,
    }

    fun refusal(theirs: Db): Refusal? {
        val tables = theirs.tables()
        return when {
            tables.containsAll(OURS_ONLY) -> Refusal.OURS
            !tables.containsAll(THEIRS) -> Refusal.NOT_METROLIST
            theirs.userVersion() < FIRST_VERSION -> Refusal.TOO_OLD
            else -> null
        }
    }

    /**
     * A liked song as the new app keeps it: in its settings (settings.json, likedSongsJson), not only in its database.
     * [artists] are (id, name), an id missing for an artist YouTube gave none.
     */
    data class LikedSong(
        val id: String,
        val title: String,
        val thumbnail: String?,
        val artists: List<Pair<String?, String>>,
        val albumId: String?,
        val albumName: String?,
        val durationSeconds: Long?,
    )

    /**
     * The new app's liked songs, newest first, from its settings.json: a list of entries (key, type, value) in which
     * likedSongsJson holds the songs as JSON and likedSongsRecencyJson their ids, newest first. Empty for the Android
     * app's backups, which keep likes in the database only, and for anything that cannot be read.
     */
    fun likedFromSettings(settingsJson: String?): List<LikedSong> {
        if (settingsJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val entries = Json.parseToJsonElement(settingsJson).jsonObject["entries"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            fun value(key: String) = entries.firstOrNull { it["key"]?.jsonPrimitive?.contentOrNull == key }?.get("value")?.jsonPrimitive?.contentOrNull
            val songs = Json.parseToJsonElement(value("likedSongsJson") ?: return emptyList()).jsonArray.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val album = o["album"] as? JsonObject
                LikedSong(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    thumbnail = o["thumbnail"]?.jsonPrimitive?.contentOrNull,
                    artists = (o["artists"] as? JsonArray).orEmpty().mapNotNull { a ->
                        val artist = a as? JsonObject ?: return@mapNotNull null
                        val name = artist["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        artist["id"]?.jsonPrimitive?.contentOrNull to name
                    },
                    albumId = album?.get("id")?.jsonPrimitive?.contentOrNull,
                    albumName = album?.get("name")?.jsonPrimitive?.contentOrNull,
                    durationSeconds = o["duration"]?.jsonPrimitive?.longOrNull,
                )
            }
            val order = value("likedSongsRecencyJson")
                ?.let { runCatching { Json.parseToJsonElement(it).jsonArray.mapNotNull { id -> id.jsonPrimitive.contentOrNull } }.getOrNull() }
                .orEmpty()
            val rank = HashMap<String, Int>().apply { order.forEachIndexed { i, id -> putIfAbsent(id, i) } }
            songs.withIndex().sortedBy { (i, song) -> rank[song.id] ?: (order.size + i) }.map { it.value }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    /** What a backup holds, shown before anything is written. */
    data class Summary(val inLibrary: Int, val liked: Int, val playlists: Int, val plays: Int)

    fun summary(theirs: Db, liked: List<LikedSong> = emptyList()): Summary {
        val likedIds = HashSet<Any?>()
        theirs.query("SELECT id FROM song WHERE liked = 1") { r -> likedIds += r[0] }
        liked.forEach { likedIds += it.id }
        return Summary(
            inLibrary = count(theirs, "SELECT COUNT(*) FROM song WHERE inLibrary IS NOT NULL"),
            liked = likedIds.size,
            playlists = count(theirs, "SELECT COUNT(*) FROM playlist"),
            plays = count(theirs, "SELECT COUNT(*) FROM event" + playsOnly(theirs)),
        )
    }

    /** What changed in ours, counted before and after. */
    data class Result(val songs: Int, val liked: Int, val inLibrary: Int, val playlists: Int, val plays: Int)

    /**
     * Copies [theirs] into [ours], which the caller holds in one transaction, then likes the new app's [liked] songs,
     * newest first: they are given liked dates a second apart from [now] back, so Liked keeps their order.
     */
    fun merge(theirs: Db, ours: Db, liked: List<LikedSong> = emptyList(), now: Long = System.currentTimeMillis()): Result {
        refusal(theirs)?.let { throw IllegalArgumentException("not a Metrolist database: $it") }
        val before = counts(ours)
        val both = theirs.tables() intersect ours.tables()

        copy(theirs, ours, "artist")
        copy(theirs, ours, "album")
        copy(theirs, ours, "song")
        // A song both have: liked or in the library there is liked or in the library here. Ours stays otherwise.
        theirs.query("SELECT id, liked, likedDate, inLibrary FROM song WHERE liked = 1 OR inLibrary IS NOT NULL") { r ->
            if (truthy(r[1])) ours.exec("UPDATE song SET liked = 1, likedDate = COALESCE(likedDate, ?) WHERE id = ? AND liked = 0", listOf(r[2], r[0]))
            if (r[3] != null) ours.exec("UPDATE song SET inLibrary = ? WHERE id = ? AND inLibrary IS NULL", listOf(r[3], r[0]))
        }
        // An album saved there is saved here, the way InnerTune marks it on both sides.
        theirs.query("SELECT id, bookmarkedAt FROM album WHERE bookmarkedAt IS NOT NULL") { r ->
            ours.exec("UPDATE album SET bookmarkedAt = ? WHERE id = ? AND bookmarkedAt IS NULL", listOf(r[1], r[0]))
        }
        theirs.query("SELECT id, bookmarkedAt FROM artist WHERE bookmarkedAt IS NOT NULL") { r ->
            ours.exec("UPDATE artist SET bookmarkedAt = ? WHERE id = ? AND bookmarkedAt IS NULL", listOf(r[1], r[0]))
        }
        for (table in PER_SONG) if (table in both) copy(theirs, ours, table)

        playlists(theirs, ours)

        // Plays: theirs that we do not have already. The new app's plays fetched from YouTube's history are left out:
        // they were not played in Metrolist, and their length is not known.
        theirs.query("SELECT songId, timestamp, playTime FROM event" + playsOnly(theirs)) { r ->
            ours.exec(
                "INSERT INTO event (songId, timestamp, playTime) SELECT ?, ?, ? " +
                        "WHERE NOT EXISTS (SELECT 1 FROM event WHERE songId = ? AND timestamp = ?)",
                listOf(r[0], r[1], r[2], r[0], r[1]),
            )
        }
        if ("playCount" in both) theirs.query("SELECT song, year, month, count FROM playCount") { r ->
            if (ours.exec("INSERT OR IGNORE INTO playCount (song, year, month, count) VALUES (?, ?, ?, ?)", r) == 0)
                ours.exec("UPDATE playCount SET count = MAX(count, ?) WHERE song = ? AND year = ? AND month = ?", listOf(r[3], r[0], r[1], r[2]))
        }
        if ("search_history" in both) theirs.query("SELECT query FROM search_history ORDER BY id") { r ->
            ours.exec("INSERT INTO search_history (query) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM search_history WHERE query = ?)", listOf(r[0], r[0]))
        }
        liked.forEachIndexed { i, song ->
            val at = now - i * 1_000L
            ours.exec(
                "INSERT OR IGNORE INTO song (id, title, duration, thumbnailUrl, albumId, albumName, liked, likedDate) VALUES (?, ?, ?, ?, ?, ?, 1, ?)",
                listOf(song.id, song.title, song.durationSeconds ?: -1L, song.thumbnail, song.albumId, song.albumName, at),
            )
            ours.exec("UPDATE song SET liked = 1, likedDate = COALESCE(likedDate, ?) WHERE id = ? AND liked = 0", listOf(at, song.id))
            song.artists.forEachIndexed { position, (artistId, name) ->
                if (artistId == null) return@forEachIndexed
                ours.exec("INSERT OR IGNORE INTO artist (id, name, lastUpdateTime) VALUES (?, ?, 0)", listOf(artistId, name))
                ours.exec("INSERT OR IGNORE INTO song_artist_map (songId, artistId, position) VALUES (?, ?, ?)", listOf(song.id, artistId, position))
            }
        }

        val after = counts(ours)
        return Result(
            songs = after.songs - before.songs,
            liked = after.liked - before.liked,
            inLibrary = after.inLibrary - before.inLibrary,
            playlists = after.playlists - before.playlists,
            plays = after.plays - before.plays,
        )
    }

    /**
     * Their playlists we have neither by id nor by their YouTube playlist, with their songs in their order. Their
     * rows' own ids are not copied: ours count up by themselves.
     */
    private fun playlists(theirs: Db, ours: Db) {
        val ids = HashSet<Any?>()
        val followed = HashSet<Any?>()
        ours.query("SELECT id, browseId FROM playlist") { r ->
            r[0]?.let(ids::add)
            r[1]?.let(followed::add)
        }
        val columns = shared(theirs, ours, "playlist")
        val id = columns.indexOf("id")
        val browseId = columns.indexOf("browseId")
        val made = ArrayList<Any>()
        theirs.query("SELECT ${list(columns)} FROM playlist") { r ->
            val browse = if (browseId >= 0) r[browseId] else null
            if (r[id] in ids || (browse != null && browse in followed)) return@query
            if (ours.exec(insert("playlist", columns), r) > 0) made += r[id]!!
        }
        val songs = shared(theirs, ours, "playlist_song_map") - "id"
        for (playlist in made) theirs.query("SELECT ${list(songs)} FROM playlist_song_map WHERE playlistId = ? ORDER BY position, id", listOf(playlist)) { r ->
            ours.exec("INSERT INTO playlist_song_map (${list(songs)}) VALUES (${marks(songs)})", r)
        }
    }

    /** Every row of theirs we do not have, by key, with the columns both tables have. */
    private fun copy(theirs: Db, ours: Db, table: String) {
        val columns = shared(theirs, ours, table)
        if (columns.isEmpty()) return
        theirs.query("SELECT ${list(columns)} FROM \"$table\"") { r -> ours.exec(insert(table, columns), r) }
    }

    private fun shared(theirs: Db, ours: Db, table: String): List<String> {
        val mine = ours.columns(table).toSet()
        return theirs.columns(table).filter { it in mine }
    }

    private fun insert(table: String, columns: List<String>) = "INSERT OR IGNORE INTO \"$table\" (${list(columns)}) VALUES (${marks(columns)})"
    private fun list(columns: List<String>) = columns.joinToString(",") { "\"$it\"" }
    private fun marks(columns: List<String>) = columns.joinToString(",") { "?" }

    /** The new app keeps plays fetched from YouTube's history beside its own: only its own count as plays here. */
    private fun playsOnly(theirs: Db) = if ("source" in theirs.columns("event")) " WHERE source != 'YOUTUBE'" else ""

    private fun truthy(value: Any?) = when (value) {
        is Number -> value.toLong() != 0L
        is Boolean -> value
        else -> false
    }

    private fun count(db: Db, sql: String): Int {
        var n = 0
        db.query(sql) { r -> n = (r[0] as Number).toInt() }
        return n
    }

    private data class Counts(val songs: Int, val liked: Int, val inLibrary: Int, val playlists: Int, val plays: Int)

    private fun counts(ours: Db) = Counts(
        songs = count(ours, "SELECT COUNT(*) FROM song"),
        liked = count(ours, "SELECT COUNT(*) FROM song WHERE liked = 1"),
        inLibrary = count(ours, "SELECT COUNT(*) FROM song WHERE inLibrary IS NOT NULL"),
        playlists = count(ours, "SELECT COUNT(*) FROM playlist"),
        plays = count(ours, "SELECT COUNT(*) FROM event"),
    )
}

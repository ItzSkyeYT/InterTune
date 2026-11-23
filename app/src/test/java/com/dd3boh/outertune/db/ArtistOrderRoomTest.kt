/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.AlbumWithSongs
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection

/**
 * A song's artists, an album's songs and a playlist's cover as Room really brings them, on a SQLite
 * that drops a view's ORDER BY once the view is joined. SQLite has done so since 3.37.0 and Android
 * ships such a SQLite from 14 on; sqlite-jdbc's here does it too (the first test checks).
 *
 * Room reads all three through a view that sorts (sorted_song_artist_map, sorted_song_album_map,
 * playlist_song_map_preview). Nothing of Room's side is written by hand here: the SQL of each
 * relation and the constructor each object is built with are read from DatabaseDao_Impl, the code
 * Room generated in this build, and replayed on a database made from the app's schema. So what the
 * tests see is what the app holds: before the fix, BbY WOW's artists as "Judeline, KAROL G, rusowsky".
 */
class ArtistOrderRoomTest {

    // ---- the songs, albums and playlists, with the app's schema ----

    private val db: Connection = SchemaDb.open()

    private fun exec(sql: String, vararg args: Any?) = db.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
        ps.executeUpdate()
    }

    private fun artist(id: String, name: String) =
        exec("INSERT INTO artist(id, name, lastUpdateTime, isLocal) VALUES (?, ?, 0, 0)", id, name)

    private fun song(id: String, title: String, thumbnail: String? = null) =
        exec("INSERT INTO song(id, title, duration, thumbnailUrl, isLocal, liked) VALUES (?, ?, 200, ?, 0, 0)", id, title, thumbnail)

    /** The song credits [artistIds] in this order, whatever order their ids sort in. */
    private fun credits(songId: String, vararg artistIds: String) = artistIds.forEachIndexed { i, a ->
        exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES (?, ?, ?)", songId, a, i)
    }

    /** KAROL G's BbY WOW as the walk app on the Pixel stored it on 23 Nov 2026: Judeline's id is one
     * the app made up (no channel in YouTube's credit), and "LA..." sorts before every "UC...". */
    private fun bbyWow() {
        artist("UC7n3gWRN0vQzgiOKc51aZ4w", "KAROL G")
        artist("LAQWgslcqk", "Judeline")
        artist("UCYcm1D23PES7WPlnOevJynA", "rusowsky")
        song("fRIhCiUVaKs", "BbY WOW")
        credits("fRIhCiUVaKs", "UC7n3gWRN0vQzgiOKc51aZ4w", "LAQWgslcqk", "UCYcm1D23PES7WPlnOevJynA")
    }

    // ---- Room's generated DAO, replayed ----

    private val generated = File("build/generated/ksp/coreDebug/kotlin/com/dd3boh/outertune/db/DatabaseDao_Impl.kt").readText()

    /** The generated function whose declaration holds [head], up to the next function. */
    private fun function(head: String): String {
        val start = generated.indexOf(head)
        assertTrue("DatabaseDao_Impl has no $head", start >= 0)
        val end = Regex("""\n  [a-z ]*fun """).find(generated, start + head.length)?.range?.first ?: generated.length
        return generated.substring(start, end)
    }

    private fun arguments(call: MatchResult) = call.groupValues[1].split(",").map { it.trim() }

    /**
     * What the DAO function [dao] builds as `_result = [type](...)` for the row with id [id]: each
     * argument Room fills from a relation is filled by running that relation's SQL, each other one
     * is taken from [own] by the name Room gives it (the query's own columns, given here).
     */
    private fun <T> asRoomBuildsIt(type: Class<T>, dao: String, id: String, own: Map<String, Any?>): T {
        val body = function(dao)
        val call = Regex("""_result = ${type.simpleName}\(([^)]*)\)""").find(body)
        assertTrue("$dao builds no ${type.simpleName}", call != null)
        val values = arguments(call!!).map { argument ->
            val filled = Regex("""\b$argument = (_collection\w+)\.(getValue|get)\(""").find(body)
            if (filled == null) own.getValue(argument) else relation(body, filled, id)
        }
        return type.cast(construct(type, values))!!
    }

    /** What Room fills an argument with from the relation [filled] names, for the parent with key [key]. */
    private fun relation(body: String, filled: MatchResult, key: String): Any? {
        val fetcher = Regex("""(__fetchRelationship\w+)\(_connection, ${filled.groupValues[1]}\)""").find(body)!!.groupValues[1]
        val found = fetch(fetcher, key)
        // get: a relation to one (a song's album), getValue: to many.
        return if (filled.groupValues[2] == "get") found.firstOrNull() else found
    }

    /** Types whose rows say nothing about order: their relations are left empty. */
    private val leftOut = setOf("AlbumEntity", "GenreEntity", "PlayCountEntity")

    /** The elements the generated [fetcher] makes for the parent with key [key], in the order SQLite gives their rows. */
    private fun fetch(fetcher: String, key: String): List<Any?> {
        val body = function("private fun $fetcher(")
        val type = Regex("""_map: ArrayMap<String, (?:MutableList<)?(\w+)""").find(body)!!.groupValues[1]
        if (type in leftOut) return emptyList()
        val head = Regex("""_stringBuilder\.append\("(.*?)"\)""").find(body)!!.groupValues[1]
        val keyColumn = Regex("""`(\w+)` IN \($""").find(head)!!.groupValues[1]
        val out = ArrayList<Any?>()
        db.prepareStatement("$head?)").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                while (rs.next()) {
                    // Room reads a junction's key from the last column, the other relations' by name.
                    val rowKey = if ("_junction." in head) rs.getString(meta.columnCount) else rs.getString(keyColumn)
                    if (rowKey != key) continue
                    val row = (1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) }
                    out += element(body, type, row)
                }
            }
        }
        return out
    }

    private fun Map<String, Any?>.text(column: String) = getValue(column) as String
    private fun Map<String, Any?>.number(column: String) = (getValue(column) as Number).toInt()

    /** One element of a relation, from its row. */
    private fun element(fetcher: String, type: String, row: Map<String, Any?>): Any? = when (type) {
        "ArtistEntity" -> ArtistEntity(id = row.text("id"), name = row.text("name"))
        "SongArtistMap" -> SongArtistMap(row.text("songId"), row.text("artistId"), row.number("position"))
        "SongAlbumMap" -> SongAlbumMap(row.text("songId"), row.text("albumId"), row.number("index"))
        // An album's song: its own relations say nothing about the album's order.
        "Song" -> Song(SongEntity(id = row.text("id"), title = row.text("title"), localPath = null), artists = emptyList())
        "String" -> row.values.first()
        else -> classOfTheApp(fetcher, type, row)
    }

    /**
     * An element that is a class of the app's own with relations of its own, built the way [fetcher]
     * builds it: a plain field from the row's column of that name, a relation by its own fetcher,
     * keyed by the column Room keys it by.
     */
    private fun classOfTheApp(fetcher: String, type: String, row: Map<String, Any?>): Any {
        val call = Regex("""_item\w* = $type\(([^)]*)\)""").find(fetcher)
        assertTrue("no $type built in its fetcher", call != null)
        val values = arguments(call!!).map { argument ->
            val filled = Regex("""\b$argument = (_collection\w+)\.(getValue|get)\((\w+)\)""").find(fetcher)
            if (filled == null) row.getValue(argument.removePrefix("_tmp").replaceFirstChar { it.lowercase() })
            else {
                val keyColumn = Regex("""\b${filled.groupValues[3]} = _stmt\.getText\(_columnIndexOf(\w+)\)""").find(fetcher)!!
                    .groupValues[1].replaceFirstChar { it.lowercase() }
                val inner = Regex("""(__fetchRelationship\w+)\(_connection, ${filled.groupValues[1]}\)""").find(fetcher)!!.groupValues[1]
                val found = fetch(inner, row.text(keyColumn))
                if (filled.groupValues[2] == "get") found.firstOrNull() else found
            }
        }
        return construct(Class.forName("com.dd3boh.outertune.db.entities.$type"), values)
    }

    /** The one constructor of [type] that takes [values] in this order, as Room's call picks it. */
    private fun construct(type: Class<*>, values: List<Any?>): Any {
        val fits = type.constructors.filter { c ->
            !c.isSynthetic && c.parameterCount == values.size && c.parameterTypes.zip(values).all { (p, v) ->
                if (v == null) !p.isPrimitive else if (p.isPrimitive) v is Number else p.isInstance(v)
            }
        }
        assertEquals("constructors of ${type.simpleName} for $values", 1, fits.size)
        val constructor = fits.single()
        return constructor.newInstance(*constructor.parameterTypes.zip(values).map { (p, v) ->
            if (p == Int::class.javaPrimitiveType) (v as Number).toInt() else v
        }.toTypedArray())
    }

    private fun songAsRoomBuildsIt(id: String, title: String) =
        asRoomBuildsIt(Song::class.java, "fun song(songId: String?)", id, mapOf("_tmpSong" to SongEntity(id = id, title = title, localPath = null)))

    // ---- the tests ----

    @Test
    fun `this SQLite drops the view's ORDER BY in Room's join, as Android 14 and newer do`() {
        bbyWow()
        // The SQL Room reads Song.artists with, run as it is: the rows come by the artists' ids.
        val sql = Regex("""_stringBuilder\.append\("(SELECT [^"]*FROM `sorted_song_artist_map` AS _junction[^"]*)"\)""").find(generated)!!.groupValues[1]
        val names = db.prepareStatement("$sql?)").use { ps ->
            ps.setString(1, "fRIhCiUVaKs")
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }
        assertEquals(listOf("Judeline", "KAROL G", "rusowsky"), names)
        val version = db.createStatement().use { st -> st.executeQuery("SELECT sqlite_version()").use { it.next(); it.getString(1) } }
        val (major, minor) = version.split(".").map { it.toInt() }
        assertTrue("SQLite $version", major > 3 || minor >= 37)
    }

    @Test
    fun `a song's artists come in the order the song credits them`() {
        bbyWow()
        artist("UCbbbbbbbbbbbbbbbbbbbbbb", "Main")
        artist("UCaaaaaaaaaaaaaaaaaaaaaa", "Guest")
        song("two", "Two names")
        credits("two", "UCbbbbbbbbbbbbbbbbbbbbbb", "UCaaaaaaaaaaaaaaaaaaaaaa")
        artist("UCcccccccccccccccccccccc", "Alone")
        song("one", "One name")
        credits("one", "UCcccccccccccccccccccccc")

        val bbyWow = songAsRoomBuildsIt("fRIhCiUVaKs", "BbY WOW")
        assertEquals(listOf("KAROL G", "Judeline", "rusowsky"), bbyWow.artists.map { it.name })
        // What lyrics, similar songs, scrobbles, shares and the notification read.
        assertEquals("KAROL G", bbyWow.artists.first().name)
        assertEquals(listOf("Main", "Guest"), songAsRoomBuildsIt("two", "Two names").artists.map { it.name })
        assertEquals(listOf("Alone"), songAsRoomBuildsIt("one", "One name").artists.map { it.name })
    }

    @Test
    fun `every song, album and playlist Room builds is built the way the tests replay`() {
        // song(), albumWithSongs() and playlist() stand for every list, queue, history and search:
        // each place Room builds one of these calls the same constructor with the same relations.
        for ((type, dao) in listOf("Song" to "fun song(songId: String?)", "AlbumWithSongs" to "fun albumWithSongs(albumId: String)", "Playlist" to "fun playlist(playlistId: String)")) {
            val replayed = arguments(Regex("""_result = $type\(([^)]*)\)""").find(function(dao))!!).size
            val everywhere = Regex("""\b$type\((_tmp[^)]*)\)""").findAll(generated).map { arguments(it).size }.toList()
            assertTrue(type, everywhere.isNotEmpty())
            assertEquals(type, setOf(replayed), everywhere.toSet())
        }
        assertTrue(Regex("""\bSong\((_tmp[^)]*)\)""").findAll(generated).count() > 50)
    }

    @Test
    fun `an album's songs come in the album's order, also one heard before the album was opened`() {
        exec("INSERT INTO album(id, title, songCount, duration, lastUpdateTime, isLocal) VALUES ('MPREb_toto', 'Toto IV', 3, 600, 0, 0)")
        song("zzRosanna", "Rosanna")
        song("aaMakeBelieve", "Make Believe")
        song("mmAfrica", "Africa")
        // Africa was played first, so its row was stored first; then the album page stored the rest.
        exec("INSERT INTO song_album_map(songId, albumId, `index`) VALUES ('mmAfrica', 'MPREb_toto', 2)")
        exec("INSERT INTO song_album_map(songId, albumId, `index`) VALUES ('zzRosanna', 'MPREb_toto', 0)")
        exec("INSERT INTO song_album_map(songId, albumId, `index`) VALUES ('aaMakeBelieve', 'MPREb_toto', 1)")

        val album = asRoomBuildsIt(
            AlbumWithSongs::class.java, "fun albumWithSongs(albumId: String)", "MPREb_toto",
            mapOf("_tmpAlbum" to AlbumEntity(id = "MPREb_toto", title = "Toto IV", songCount = 3, duration = 600), "_tmpDownloadCount" to 0),
        )
        assertEquals(listOf("Rosanna", "Make Believe", "Africa"), album.songs.map { it.song.title })
    }

    @Test
    fun `a playlist's cover is its first song, also one moved to the top`() {
        exec("INSERT INTO playlist(id, name, isEditable, isLocal) VALUES ('P', 'Mine', 1, 0)")
        for ((i, name) in listOf("one", "two", "three", "four", "five").withIndex()) {
            song(name, name, "https://pictures/$name")
            exec("INSERT INTO playlist_song_map(playlistId, songId, position) VALUES ('P', ?, ?)", name, i)
        }
        // The playlist screen's drag: "five" goes to the top, with the app's own SQL.
        exec(PlaylistSql.MOVE.replace(":playlistId", "'P'").replace(":fromPosition", "4").replace(":toPosition", "0"))

        val playlist = asRoomBuildsIt(
            Playlist::class.java, "fun playlist(playlistId: String)", "P",
            mapOf("_tmpPlaylist" to PlaylistEntity(id = "P", name = "Mine"), "_tmpSongCount" to 5, "_tmpDownloadCount" to 0),
        )
        assertEquals(listOf("five", "one", "two", "three").map { "https://pictures/$it" }, playlist.songThumbnails)
        assertEquals("https://pictures/five", playlist.thumbnails.first())
        assertNotEquals("https://pictures/one", playlist.thumbnails.first())
    }
}

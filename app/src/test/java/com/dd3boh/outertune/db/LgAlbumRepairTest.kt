/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/** LgAlbumRepair against the exported schema, the way the migration to 25 runs it on the phone. */
class LgAlbumRepairTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun rows(sql: String): List<List<String?>> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
        }
    }

    private fun quoted(value: String?) = value?.let { "'$it'" } ?: "NULL"

    /**
     * In one transaction and, as in a Room migration, with foreign keys off, so nothing cascades
     * and a row left pointing at a deleted album would go unnoticed but for the check at the end.
     * [danglingBefore] skips that check for a library that had such a row before the repair.
     */
    private fun repair(foreignKeys: Boolean = false, danglingBefore: Boolean = false) {
        exec("PRAGMA foreign_keys = ${if (foreignKeys) "ON" else "OFF"}")
        db.autoCommit = false
        LgAlbumRepair.STEPS.forEach(::exec)
        db.commit()
        db.autoCommit = true
        exec("PRAGMA foreign_keys = ON")
        if (!danglingBefore) assertEquals("rows pointing at nothing", emptyList<List<String?>>(), rows("PRAGMA foreign_key_check"))
    }

    private fun song(id: String, albumId: String?, local: Boolean = false, duration: Int = 200) = exec(
        "INSERT INTO song(id, title, duration, thumbnailUrl, liked, isLocal, albumId, albumName) " +
            "VALUES ('$id', '$id', $duration, 'thumb-$id', 0, ${if (local) 1 else 0}, ${quoted(albumId)}, 'album of $id')"
    )

    private fun album(
        id: String,
        title: String,
        songCount: Int = 1,
        duration: Int = 200,
        local: Boolean = false,
        savedAt: Long? = null,
        thumbnail: String? = "thumb-$id",
    ) = exec(
        "INSERT INTO album(id, title, thumbnailUrl, songCount, duration, lastUpdateTime, bookmarkedAt, isLocal) " +
            "VALUES ('$id', '$title', ${quoted(thumbnail)}, $songCount, $duration, 1000, $savedAt, ${if (local) 1 else 0})"
    )

    private fun inAlbum(songId: String, albumId: String, index: Int) =
        exec("INSERT INTO song_album_map(songId, albumId, `index`) VALUES ('$songId', '$albumId', $index)")

    /** An album row as the song path left it, songs filed in order. 0.10.9.6 counted them all as 1. */
    private fun madeUp(id: String, title: String, vararg songIds: String, local: Boolean = false, savedAt: Long? = null) {
        album(id, title, songCount = 1, local = local, savedAt = savedAt, thumbnail = songIds.firstOrNull()?.let { "thumb-$it" })
        songIds.forEachIndexed { i, songId -> inAlbum(songId, id, minOf(i, 1)) }
    }

    /** id, title, thumbnailUrl, songCount, duration, lastUpdateTime, bookmarkedAt, isLocal, playlistId, year. */
    private fun albumRow(id: String): List<String?>? = rows(
        "SELECT id, title, thumbnailUrl, songCount, duration, lastUpdateTime, bookmarkedAt, isLocal, playlistId, year FROM album WHERE id = '$id'"
    ).singleOrNull()

    /** The albums [songId] is in, with its place in each. */
    private fun placesOf(songId: String): List<Pair<String?, String?>> =
        rows("SELECT albumId, `index` FROM song_album_map WHERE songId = '$songId' ORDER BY albumId").map { it[0] to it[1] }

    /** Album ids in date added order, as Library sorts them. */
    private fun albumsByDateAdded(): List<String?> = rows("SELECT id FROM album ORDER BY rowid").map { it[0] }

    /** Every row of every table, rowids included, to tell whether anything at all changed. */
    private fun everything(): Map<String, List<List<String?>>> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .associate { (table) -> table!! to rows("SELECT rowid, * FROM `$table` ORDER BY rowid") }

    @Test
    fun `an album stored under a made-up id takes its real id`() {
        song("one", "MPREb_discovery", duration = 320)
        song("two", "MPREb_discovery", duration = -1)
        madeUp("LGaaaaaaaa", "Discovery", "one", "two")
        repair()

        assertNull(albumRow("LGaaaaaaaa"))
        // Title and thumbnail kept, both songs counted, a length YouTube never gave adding nothing.
        assertEquals(
            listOf("MPREb_discovery", "Discovery", "thumb-one", "2", "320", "1000", null, "0", null, null),
            albumRow("MPREb_discovery"),
        )
        assertEquals(listOf("MPREb_discovery" to "0"), placesOf("one"))
        assertEquals(listOf("MPREb_discovery" to "1"), placesOf("two"))
        // The songs themselves are not touched: their album id was right all along.
        assertEquals(listOf(listOf("MPREb_discovery"), listOf("MPREb_discovery")), rows("SELECT albumId FROM song ORDER BY id"))
    }

    @Test
    fun `a song whose album is stored joins it, counted only if it was not in it`() {
        album("MPREb_queen", "Greatest Hits", songCount = 2, duration = 400)
        song("bohemian", "MPREb_queen")
        song("killer", "MPREb_queen")
        song("radio", "MPREb_queen", duration = 150)
        inAlbum("bohemian", "MPREb_queen", 0)
        inAlbum("killer", "MPREb_queen", 1)
        // Heard before the album was opened, so filed under a made-up id as well.
        madeUp("LGbbbbbbbb", "Greatest Hits", "killer", "radio", savedAt = 7)
        repair()

        assertNull(albumRow("LGbbbbbbbb"))
        // One new song and its length; saved, as the row it came from was.
        assertEquals(
            listOf("MPREb_queen", "Greatest Hits", "thumb-MPREb_queen", "3", "550", "1000", "7", "0", null, null),
            albumRow("MPREb_queen"),
        )
        assertEquals(listOf("MPREb_queen" to "1"), placesOf("killer"))
        assertEquals(listOf("MPREb_queen" to "2"), placesOf("radio"))
    }

    @Test
    fun `two made-up rows for one album become that album once`() {
        // The same album under two titles, EGO and Ego, so the title lookup made a row for each.
        song("ego", "MPREb_ego", duration = 180)
        song("ego-slowed", "MPREb_ego", duration = 210)
        madeUp("LGcccccccc", "EGO", "ego")
        madeUp("LGdddddddd", "Ego", "ego-slowed", savedAt = 9)
        repair()

        assertNull(albumRow("LGcccccccc"))
        assertNull(albumRow("LGdddddddd"))
        // The first row's title and thumbnail, the second's bookmark.
        assertEquals(listOf("MPREb_ego", "EGO", "thumb-ego", "2", "390", "1000", "9", "0", null, null), albumRow("MPREb_ego"))
        assertEquals(listOf("MPREb_ego" to "0"), placesOf("ego"))
        assertEquals(listOf("MPREb_ego" to "1"), placesOf("ego-slowed"))
    }

    @Test
    fun `a song filed in two made-up rows of its album is in it once`() {
        song("a", "MPREb_x")
        song("b", "MPREb_x")
        madeUp("LGssssssss", "X", "a", "b")
        madeUp("LGtttttttt", "x", "b")
        repair()

        assertEquals(listOf("MPREb_x", "X", "thumb-a", "2", "400", "1000", null, "0", null, null), albumRow("MPREb_x"))
        assertEquals(listOf("MPREb_x" to "0"), placesOf("a"))
        assertEquals(listOf("MPREb_x" to "1"), placesOf("b"))
        assertEquals(listOf("MPREb_x"), albumsByDateAdded())
    }

    @Test
    fun `a made-up row holding several albums becomes each of them`() {
        // Every later song titled Greatest Hits went into the first row of that name.
        song("queen-1", "MPREb_queen")
        song("journey-1", "MPREb_journey")
        song("queen-2", "MPREb_queen")
        madeUp("LGeeeeeeee", "Greatest Hits", "queen-1", "journey-1", "queen-2")
        repair()

        assertNull(albumRow("LGeeeeeeee"))
        assertEquals(listOf("MPREb_queen", "Greatest Hits", "thumb-queen-1", "2", "400", "1000", null, "0", null, null), albumRow("MPREb_queen"))
        assertEquals(listOf("MPREb_journey", "Greatest Hits", "thumb-journey-1", "1", "200", "1000", null, "0", null, null), albumRow("MPREb_journey"))
        assertEquals(listOf("MPREb_queen" to "0"), placesOf("queen-1"))
        assertEquals(listOf("MPREb_queen" to "1"), placesOf("queen-2"))
        assertEquals(listOf("MPREb_journey" to "0"), placesOf("journey-1"))
    }

    @Test
    fun `songs with no album id of their own stay, and a row with no songs goes`() {
        song("no-id", null)
        song("blank-id", "  ")
        song("own-lg", "LGgggggggg")
        madeUp("LGffffffff", "Nothing to go by", "no-id", "blank-id")
        madeUp("LGgggggggg", "Rewritten", "own-lg")
        madeUp("LGhhhhhhhh", "Empty")
        exec("INSERT INTO artist(id, name, lastUpdateTime) VALUES ('UCartist', 'Artist', 0)")
        exec("INSERT INTO album_artist_map(albumId, artistId, `order`) VALUES ('LGhhhhhhhh', 'UCartist', 0)")
        val before = listOf(albumRow("LGffffffff"), albumRow("LGgggggggg"))
        repair()

        // Nowhere to put these songs, and deleting the rows would only lose the grouping.
        assertEquals(before, listOf(albumRow("LGffffffff"), albumRow("LGgggggggg")))
        assertEquals(listOf("LGffffffff" to "0"), placesOf("no-id"))
        assertEquals(listOf("LGgggggggg" to "0"), placesOf("own-lg"))
        // Nothing can open, fill or refetch an empty one.
        assertNull(albumRow("LGhhhhhhhh"))
        assertEquals(emptyList<List<String?>>(), rows("SELECT * FROM album_artist_map"))
    }

    @Test
    fun `files on the phone stay in their album, which turns local once the rest has gone`() {
        // The scanner gives each file a new album id, so the one a file carries names no album.
        song("file-1", "LBabcdefgh", local = true, duration = 240)
        song("file-2", "LBijklmnop", local = true, duration = 260)
        song("youtube", "MPREb_mix")
        madeUp("LGiiiiiiii", "Mix", "file-1", "youtube", "file-2")
        // A local album under an LG id works as it is: it opens from the phone and files find it by title.
        song("file-3", "LBqrstuvwx", local = true)
        madeUp("LGjjjjjjjj", "Tapes", "file-3", local = true)
        val tapes = albumRow("LGjjjjjjjj")
        repair()

        assertEquals(listOf("MPREb_mix", "Mix", "thumb-youtube", "1", "200", "1000", null, "0", null, null), albumRow("MPREb_mix"))
        assertEquals(listOf("LGiiiiiiii", "Mix", "thumb-file-1", "2", "500", "1000", null, "1", null, null), albumRow("LGiiiiiiii"))
        assertEquals(listOf("LGiiiiiiii" to "0"), placesOf("file-1"))
        assertEquals(listOf("LGiiiiiiii" to "1"), placesOf("file-2"))
        assertEquals(tapes, albumRow("LGjjjjjjjj"))
    }

    @Test
    fun `a mapping to a row that is not there is left as it was`() {
        // Only possible where foreign keys were off, as in an old migration. There is no row to
        // make the album from, and it is not this repair's to fix.
        exec("PRAGMA foreign_keys = OFF")
        song("orphan", "MPREb_orphan")
        inAlbum("orphan", "LGuuuuuuuu", 0)
        val before = everything()
        repair(danglingBefore = true)

        assertEquals(before, everything())
    }

    @Test
    fun `a genre under an LG id and ids that only look alike are left alone`() {
        exec("INSERT INTO genre(id, title, isLocal) VALUES ('LGkkkkkkkk', 'Synthwave', 1)")
        song("tagged", "MPREb_real")
        exec("INSERT INTO song_genre_map(songId, genreId, `index`) VALUES ('tagged', 'LGkkkkkkkk', 0)")
        // A video id can start with LG, and a shorter or longer id is not the generator's.
        song("a", "MPREb_a")
        song("b", "MPREb_b")
        album("LGbXCwjpNWg", "Eleven")
        album("LGshort", "Seven")
        inAlbum("a", "LGbXCwjpNWg", 0)
        inAlbum("b", "LGshort", 0)
        val before = everything()
        repair()

        assertEquals(before, everything())
    }

    @Test
    fun `a library with none is left exactly as it was`() {
        album("MPREb_saved", "Saved", songCount = 2, duration = 400, savedAt = 3)
        song("s1", "MPREb_saved")
        song("s2", "MPREb_saved")
        inAlbum("s1", "MPREb_saved", 0)
        inAlbum("s2", "MPREb_saved", 1)
        album("LBlocalalb", "Local", local = true)
        song("f1", "LBlocalone", local = true)
        inAlbum("f1", "LBlocalalb", 0)
        exec("INSERT INTO artist(id, name, lastUpdateTime) VALUES ('UCartist', 'Artist', 0)")
        exec("INSERT INTO album_artist_map(albumId, artistId, `order`) VALUES ('MPREb_saved', 'UCartist', 0)")
        val before = everything()
        repair()

        assertEquals(before, everything())
    }

    /** One of each kind, for the tests that look at the whole. */
    private fun mixedLibrary() {
        album("MPREb_first", "First")
        song("f", "MPREb_first")
        inAlbum("f", "MPREb_first", 0)
        song("q1", "MPREb_queen")
        song("j1", "MPREb_journey")
        madeUp("LGllllllll", "Greatest Hits", "q1", "j1")
        album("MPREb_between", "Between", songCount = 1)
        song("b1", "MPREb_between")
        song("b2", "MPREb_between")
        inAlbum("b1", "MPREb_between", 0)
        madeUp("LGmmmmmmmm", "Between", "b1", "b2")
        song("e1", "MPREb_ego")
        madeUp("LGnnnnnnnn", "EGO", "e1")
        song("e2", "MPREb_ego")
        madeUp("LGoooooooo", "Ego", "e2")
        song("file", "LBfilefile", local = true)
        song("yt", "MPREb_mix")
        madeUp("LGpppppppp", "Mix", "file", "yt")
        song("lost", null)
        madeUp("LGqqqqqqqq", "Lost", "lost")
        madeUp("LGrrrrrrrr", "Empty")
        album("MPREb_last", "Last")
    }

    @Test
    fun `albums keep their place in date added order`() {
        mixedLibrary()
        repair()

        // Each in the place of the row it came from, the albums of one row in the order their
        // songs came, an album spread over two rows in the place of the first.
        assertEquals(
            listOf(
                "MPREb_first", "MPREb_queen", "MPREb_journey", "MPREb_between", "MPREb_ego",
                "LGpppppppp", "MPREb_mix", "LGqqqqqqqq", "MPREb_last",
            ),
            albumsByDateAdded(),
        )
    }

    @Test
    fun `running it again changes nothing`() {
        mixedLibrary()
        repair()
        val once = everything()
        repair()

        assertEquals(once, everything())
    }

    @Test
    fun `with foreign keys on it comes out the same`() {
        // Room turns them on only after the migration, but every statement must hold either way.
        mixedLibrary()
        repair(foreignKeys = true)
        val on = everything()
        close()
        open()
        mixedLibrary()
        repair(foreignKeys = false)

        assertEquals(on, everything())
    }
}

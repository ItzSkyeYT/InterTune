/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.GenreEntity
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * A song read twice from the same rows is the same song.
 *
 * The screen keeps what it read and draws again only when a new read differs. PlayCountEntity and
 * GenreEntity were plain classes, equal only to themselves, so a song with a play count or a
 * genre never equalled its own re-read. A card that reads its song afresh each time it is drawn
 * (the cards of a row from YouTube do) then drew again on every read, for as long as it was in
 * the page, screen off included: on a Pixel 5, 1,200 database statements a second and half a
 * processor core for one song played once.
 */
class RowsCompareByValueTest {

    private val then: LocalDateTime = LocalDateTime.of(2026, 10, 12, 11, 30)

    /** One read of the same rows: every object in it is made anew, as the database makes them. */
    private fun read(count: Int = 3, genre: String = "Pop") = Song(
        song = SongEntity(id = "a", title = "A", localPath = null),
        artists = listOf(ArtistEntity(id = "x", name = "X", lastUpdateTime = then)),
        album = null,
        genre = listOf(GenreEntity(id = "g", title = genre)),
        playCount = listOf(PlayCountEntity(song = "a", year = 2026, month = 10, count = count)),
    )

    @Test
    fun `a song with a play count and a genre equals its own re-read`() {
        assertEquals(read(), read())
        assertEquals(read().hashCode(), read().hashCode())
    }

    @Test
    fun `one more play is a different song`() {
        assertNotEquals(read(count = 3), read(count = 4))
    }

    @Test
    fun `another genre is a different song`() {
        assertNotEquals(read(genre = "Pop"), read(genre = "Rock"))
    }

    @Test
    fun `a play count row and a genre row compare by what they hold`() {
        assertEquals(PlayCountEntity("a", 2026, 10, 3), PlayCountEntity("a", 2026, 10, 3))
        assertEquals(GenreEntity("g", "Pop"), GenreEntity("g", "Pop"))
    }

    /**
     * Read from the source, so the next row class is caught the day it is written: under
     * db/entities a class that holds data is a data class. LocalItem is the sealed parent of
     * rows, and holds nothing itself.
     */
    @Test
    fun `no class of rows is a plain class`() {
        val plain = File("src/main/java/com/dd3boh/outertune/db/entities").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""^(?:(?:open|abstract|internal|private|public) )*class (\w+)""", RegexOption.MULTILINE)
                    .findAll(file.readText()).map { it.groupValues[1] }
            }
            .toList()
        assertTrue("these compare by identity, so a re-read never equals the first read: $plain", plain.isEmpty())
    }
}

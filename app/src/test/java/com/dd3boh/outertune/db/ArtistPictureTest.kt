/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.pages.ArtistPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * An artist liked on their own page stood in Library > Artists as an empty round (28 Nov 2026, Modjo and Daft Punk,
 * played first, then liked): their row came from a song's credits, which carry no picture, and the heart only turned
 * the like on. A place that knows the picture now gives it to a row that has none, a row with one keeps it, and an
 * artist liked before gets theirs when their page opens (ArtistPicture.FILL, run here on the app's schema).
 */
class ArtistPictureTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun artist(id: String, name: String, thumbnailUrl: String? = null, bookmarkedAt: Long? = null) =
        db.prepareStatement("INSERT INTO artist(id, name, thumbnailUrl, lastUpdateTime, bookmarkedAt, isLocal) VALUES (?, ?, ?, 0, ?, 0)").use { ps ->
            listOf(id, name, thumbnailUrl, bookmarkedAt).forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeUpdate()
        }

    /** The row's name, picture and like, or null when there is no row. */
    private fun row(id: String): Triple<String, String?, Long?>? =
        db.prepareStatement("SELECT name, thumbnailUrl, bookmarkedAt FROM artist WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getObject(3)?.let { (it as Number).toLong() }) else null }
        }

    /** ArtistPicture.FILL with its names bound as Room binds them, by name, wherever they stand in it. Rows written. */
    private fun fill(id: String, picture: String): Int {
        val names = Regex(""":(\w+)""").findAll(ArtistPicture.FILL).map { it.groupValues[1] }.toList()
        val values = mapOf("id" to id, "picture" to picture)
        return db.prepareStatement(ArtistPicture.FILL.replace(Regex(""":\w+"""), "?")).use { ps ->
            names.forEachIndexed { i, n -> ps.setObject(i + 1, values.getValue(n)) }
            ps.executeUpdate()
        }
    }

    private val modjo = "UCa3ZAzCvVQ1iSdZm3Pm1dYQ"
    private val liked = 1764347000000
    private val pagePicture = "https://lh3.googleusercontent.com/modjo=w544-h544-p-l90-rj"

    @Test
    fun `a liked artist whose row came from a song's credits takes the page's picture, and keeps the like`() {
        artist(modjo, "Modjo", bookmarkedAt = liked)
        assertEquals(1, fill(modjo, pagePicture))
        assertEquals(Triple("Modjo", pagePicture, liked), row(modjo))
    }

    @Test
    fun `an artist not liked takes it too, so a like after the page opened finds it there`() {
        artist(modjo, "Modjo")
        fill(modjo, pagePicture)
        assertEquals(Triple("Modjo", pagePicture, null), row(modjo))
    }

    @Test
    fun `a row that has a picture keeps it`() {
        val own = "https://lh3.googleusercontent.com/own=w226-h226-p-l90-rj"
        artist(modjo, "Modjo", thumbnailUrl = own, bookmarkedAt = liked)
        assertEquals(0, fill(modjo, pagePicture))
        assertEquals(own, row(modjo)!!.second)
    }

    @Test
    fun `only that artist's row, and no row is made for an artist not stored`() {
        artist("UCdaftpunk000000000000000", "Daft Punk", bookmarkedAt = liked)
        assertEquals(0, fill(modjo, pagePicture))
        assertNull(row(modjo))
        assertNull(row("UCdaftpunk000000000000000")!!.second)
    }

    @Test
    fun `a like from a place that knows the picture gives it to a row with none`() {
        val credited = ArtistEntity(id = modjo, name = "Modjo")
        val likedNow = credited.localToggleLike().withPictureIfNone(pagePicture)
        assertEquals(pagePicture, likedNow.thumbnailUrl)
        assertNotNull(likedNow.bookmarkedAt)
        assertEquals(credited.copy(thumbnailUrl = pagePicture, bookmarkedAt = likedNow.bookmarkedAt), likedNow)
    }

    @Test
    fun `a like keeps the picture the row has, and a place that knows none changes nothing`() {
        val own = ArtistEntity(id = modjo, name = "Modjo", thumbnailUrl = "https://lh3.googleusercontent.com/own=w226-h226")
        assertEquals(own.thumbnailUrl, own.withPictureIfNone(pagePicture).thumbnailUrl)
        val credited = ArtistEntity(id = modjo, name = "Modjo")
        assertEquals(credited, credited.withPictureIfNone(null))
    }

    @Test
    fun `the page's picture is its head asked for at the size the library fills a missing one at`() {
        fun page(thumbnail: String?) = ArtistPage(
            artist = ArtistItem(id = modjo, title = "Modjo", thumbnail = thumbnail, shuffleEndpoint = null, radioEndpoint = null),
            sections = emptyList(),
        )
        assertEquals(pagePicture, ArtistPicture.fromPage(page("https://lh3.googleusercontent.com/modjo=w2880-h1200-p-l90-rj")))
        assertNull(ArtistPicture.fromPage(page(null)))
    }
}

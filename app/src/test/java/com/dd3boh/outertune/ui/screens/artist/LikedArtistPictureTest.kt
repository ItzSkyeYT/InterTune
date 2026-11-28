/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.artist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every place that likes an artist and knows their picture gives it to a row that has none (ArtistPicture). Liked on
 * their page after a song of theirs had played, Modjo and Daft Punk stood in Library > Artists as empty rounds (28 Nov
 * 2026): the song's credits had made their rows, with no picture, and the heart only turned the like on. The artist
 * menu of a library row knows no picture but the row's own, and is left as it is.
 */
class LikedArtistPictureTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the heart on an artist's page gives a row with no picture the page's`() {
        val page = source("ui/screens/artist/ArtistScreen.kt")
        val heart = page.substringAfter("val picture = artistPage?.let(ArtistPicture::fromPage)", "")
            .substringBefore("Icons.Rounded.FavoriteBorder")
        assertTrue("the heart reads no picture from the page", heart.isNotEmpty())
        assertTrue(heart, "update(artist.toggleLike().withPictureIfNone(picture))" in heart)
        assertFalse("update(artist.toggleLike())" in page)
    }

    @Test
    fun `the heart in the menu of an artist from YouTube gives it the item's picture`() {
        val menu = source("ui/menu/YouTubeArtistMenu.kt")
        assertTrue("update(libraryArtistLocal.artist.toggleLike().withPictureIfNone(artist.thumbnail))" in menu)
    }

    @Test
    fun `an artist liked on YouTube and synced in takes YouTube's picture`() {
        val sync = source("utils/SyncUtils.kt")
        assertTrue("update(localArtist.artist.localToggleLike().withPictureIfNone(remoteArtist.thumbnail))" in sync)
    }

    @Test
    fun `an artist's page, once loaded, gives their row the page's picture when it has none`() {
        val model = source("viewmodels/ArtistViewModel.kt")
        val loaded = model.substringAfter("YouTube.artist(artistId)").substringBefore(".onFailure")
        assertTrue(loaded, "ArtistPicture.fromPage(it)?.let { picture -> database.query { fillArtistPicture(artistId, picture) } }" in loaded)
        val dao = source("db/daos/ArtistsDao.kt")
        assertTrue("@Query(ArtistPicture.FILL)\n    fun fillArtistPicture(id: String, picture: String)" in dao)
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #219 (0.12.11, a radio): the song before was to be liked from the queue, and its ⋮ had no Like,
 * only Add to library, which saves the song without liking it. The queue's menu was the player's own,
 * whose heart is on the player, so nothing in it liked the song and nothing said which song it was
 * for. It now opens on the song's row with its heart, as every other song menu does. Read from the
 * source, as the other menu tests are.
 */
class QueueMenuLikeTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val menu = source("ui/menu/PlayerMenu.kt")

    @Test
    fun `a song's menu in the queue opens on the song's row with its heart`() {
        val queue = source("ui/player/Queue.kt")
        val show = queue.substringAfter("fun showSongMenu(song: MediaMetadata) {").substringBefore("haptic.performHapticFeedback")
        assertTrue(show.contains("PlayerMenu(") && show.contains("songHeader = true,"))
    }

    @Test
    fun `the player's own menu is as it was, its heart being on the player`() {
        val player = source("ui/player/Player.kt")
        val call = player.substringAfter("PlayerMenu(").substringBefore("\n                    )")
        assertFalse(call.contains("songHeader"))
        assertTrue("off unless asked", menu.contains("songHeader: Boolean = false,"))
    }

    @Test
    fun `the heart likes the menu's song, stored first, the way the player's heart does`() {
        val header = menu.substringAfter("if (songHeader) {").substringBefore("HorizontalDivider()")
        assertTrue(header.contains("onClick = { likeFromQueue(database, playerConnection, mediaMetadata) }"))
        assertTrue("the like as stored, so the heart turns at once", header.contains("val liked = librarySong?.song?.liked ?: mediaMetadata.liked"))
        val like = menu.substringAfter("internal fun likeFromQueue(").substringBefore("\n}\n")
        val insert = like.indexOf("insert(song)")
        val turn = like.indexOf("service.toggleLike(song.id)")
        assertTrue("a radio's song may have no row yet: stored, then liked, in one job", insert in 0 until turn)
    }

    @Test
    fun `a like from the menu shows on the song's row in the queue at once`() {
        // The rows drew the heart from the queue's own copy of each song, which nothing refreshes
        // after a like: the menu's heart turned and the row's did not, and a second tap took it back.
        val items = source("ui/component/items/Items.kt")
        val row = items.substringAfter("fun MediaMetadataListItem(").substringBefore("thumbnailContent = {")
        assertTrue(row.contains("val storedLike by remember(mediaMetadata.id) { database.songLiked(mediaMetadata.id) }.collectAsState(initial = null)"))
        assertTrue("the queue's copy only for a song with no row yet", row.contains("if (storedLike ?: mediaMetadata.liked) {"))
        assertFalse(row.contains("showLikedIcon && mediaMetadata.liked"))
        // The like alone, as it changes, null for a song not stored.
        assertTrue(source("db/daos/SongsDao.kt").contains("@Query(\"SELECT liked FROM song WHERE id = :songId\")\n    fun songLiked(songId: String): Flow<Boolean?>"))
        // Every row of the queue, and the playing song's row held at its edge.
        assertTrue(source("ui/player/Queue.kt").contains("MediaMetadataListItem("))
        assertTrue(source("ui/player/QueueHeldRow.kt").contains("MediaMetadataListItem("))
    }

    @Test
    fun `the row goes above the volume and the buttons, so none of them moves`() {
        val body = menu.substringAfter("fun PlayerMenu(")
        val header = body.indexOf("if (songHeader) {")
        assertTrue(header in 0 until body.indexOf("imageVector = Icons.AutoMirrored.Rounded.VolumeUp"))
        assertTrue(header < body.indexOf("GridMenu("))
    }
}

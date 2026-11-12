/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song saved again with its length gets that length when its row had none (Pixel 5, 5 Nov 2026).
 * The song table keeps every song the app has seen, often first from a radio or a search without a
 * length, and saving a song whose row exists was skipped whole. A playlist played as a queue knew
 * every length, but after a restart five of its hundred songs came back without one, and the
 * queue's total said 6:42:13+ instead of 7:04:45.
 */
class SongLengthFilledTest {
    private val dao = File("src/main/java/com/dd3boh/outertune/db/DatabaseDao.kt").readText()

    @Test
    fun `saving a song whose row exists fills in a length the row lacked`() {
        val insert = dao.substringAfter("fun insert(mediaMetadata: MediaMetadata, block: (SongEntity) -> SongEntity = { it }) {")
            .substringBefore("mediaMetadata.artists.forEachIndexed")
        assertTrue(insert, insert.contains("== -1L"))
        assertTrue(insert, insert.contains("fillSongDuration(mediaMetadata.id, mediaMetadata.duration)"))
    }

    @Test
    fun `a download fills in the length of a song saved without one, from its own stream only`() {
        // Mr. Saxobeat (Radio Edit) from a search's Songs shelf, which gave no length, downloaded
        // on the emulator on 6 Nov 2026: its row stayed at -1 until it was played.
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        val write = util.substringAfter("upsertFormatKeepingLoudness(").substringBefore("SabrOver.over(")
        assertTrue(write, write.contains("if (played.from == mediaId) {"))
        assertTrue(write, write.contains("playbackData.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }"))
        assertTrue(write, write.contains("fillSongDuration(mediaId, it)"))
    }

    @Test
    fun `only a missing length is filled, never one the row already has`() {
        val query = dao.substringBefore("fun fillSongDuration(").substringAfterLast("@Query(")
        assertTrue(query, query.contains("UPDATE song SET duration = :duration WHERE id = :id AND duration <= 0"))
    }
}

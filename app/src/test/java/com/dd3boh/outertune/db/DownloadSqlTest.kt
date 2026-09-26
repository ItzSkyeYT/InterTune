/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.playback.completedDownloadTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.time.LocalDateTime

/**
 * Which songs count as downloaded, run against the exported schema as on the device.
 *
 * A download scan stored epoch 0 for a failed or stopped download and epoch 1 for a queued one,
 * and every downloaded list and count asks only whether dateDownload is set.
 */
class DownloadSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = SchemaDb.open()
        song("never", null)
        song("failed", 0L)
        song("queued", 1L)
        song("landed", LANDED_MS)
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun song(id: String, dateDownload: Long?) =
        exec("INSERT INTO song(id, title, duration, liked, isLocal, dateDownload) VALUES ('$id', '$id', 200, 0, 0, ${dateDownload ?: "NULL"})")

    private fun downloaded(): List<String> = db.createStatement().use { st ->
        st.executeQuery(DownloadSql.DOWNLOADED_BY_DATE).use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    @Test
    fun `the sentinels an old scan stored read as downloads`() {
        assertEquals(listOf("failed", "queued", "landed"), downloaded())
    }

    @Test
    fun `once they are cleared only the finished download is listed`() {
        exec(DownloadSql.CLEAR_SENTINELS)
        assertEquals(listOf("landed"), downloaded())
    }

    @Test
    fun `clearing leaves real download dates alone`() {
        exec(DownloadSql.CLEAR_SENTINELS)
        exec(DownloadSql.CLEAR_SENTINELS)
        val stored = db.createStatement().use { st ->
            st.executeQuery("SELECT dateDownload FROM song WHERE id = 'landed'").use { rs -> rs.next(); rs.getLong(1) }
        }
        assertEquals(LANDED_MS, stored)
    }

    @Test
    fun `a scan stores only a finished download`() {
        val landed = completedDownloadTime(Download.STATE_COMPLETED, LANDED_MS)
        assertEquals(LocalDateTime.of(2023, 11, 14, 22, 13, 20), landed)
        listOf(
            Download.STATE_FAILED,
            Download.STATE_STOPPED,
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_REMOVING,
            Download.STATE_RESTARTING,
        ).forEach { assertNull("$it", completedDownloadTime(it, LANDED_MS)) }
    }

    private companion object {
        /** 14 Nov 2023, 22:13:20 as the app stores it. */
        const val LANDED_MS = 1_700_000_000_000L
    }
}

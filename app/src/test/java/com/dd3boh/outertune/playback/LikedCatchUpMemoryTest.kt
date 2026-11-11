/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The liked sync's catch up queued every liked song without a download at every sync: a download
 * the person had removed came back at the next one, and a download that always fails was queued
 * again each time, with one more "Download failed" notification. It now leaves a removal alone
 * until the person wants the song again, and tries a failure again after a day, three days and a
 * week, then no more.
 */
class LikedCatchUpMemoryTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_800_000_000_000L

    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val sync = File("src/main/java/com/dd3boh/outertune/utils/SyncUtils.kt").readText()

    @Test
    fun `a song never removed nor failed is caught up`() {
        assertTrue(LikedCatchUpMemory().mayCatchUp("new", t0))
    }

    @Test
    fun `a download the person removed stays removed until they want the song again`() {
        val memory = LikedCatchUpMemory()
        memory.removedByPerson("song", t0)
        assertFalse(memory.mayCatchUp("song", t0))
        assertFalse("however long after", memory.mayCatchUp("song", t0 + 365 * day))
        memory.wantedAgain(listOf("song"))
        assertTrue(memory.mayCatchUp("song", t0))
    }

    @Test
    fun `a failed download is tried again after a day, three days and a week, then not by the sync`() {
        val memory = LikedCatchUpMemory()
        var at = t0
        for (gap in listOf(day, 3 * day, 7 * day)) {
            memory.afterChange("song", Download.STATE_FAILED, at)
            assertFalse("not at the next sync", memory.mayCatchUp("song", at + 30 * 60 * 1000L))
            assertFalse(memory.mayCatchUp("song", at + gap - 1))
            assertTrue(memory.mayCatchUp("song", at + gap))
            at += gap
        }
        memory.afterChange("song", Download.STATE_FAILED, at)
        assertFalse("after the fourth failure", memory.mayCatchUp("song", at + 365 * day))
        // the person asks for it: tried at once, and the count starts again
        memory.wantedAgain(listOf("song"))
        assertTrue(memory.mayCatchUp("song", at))
        memory.afterChange("song", Download.STATE_FAILED, at)
        assertTrue(memory.mayCatchUp("song", at + day))
    }

    @Test
    fun `a download that lands forgets its failures and its removal`() {
        val memory = LikedCatchUpMemory()
        memory.afterChange("failed", Download.STATE_FAILED, t0)
        memory.removedByPerson("removed", t0)
        assertTrue(memory.afterChange("failed", Download.STATE_COMPLETED, t0))
        assertTrue(memory.afterChange("removed", Download.STATE_COMPLETED, t0))
        assertTrue(memory.mayCatchUp("failed", t0))
        assertTrue(memory.mayCatchUp("removed", t0))
        assertTrue(memory.ids.isEmpty())
    }

    @Test
    fun `the app's removal of a failed download is not the person's`() {
        // The listener frees a failed download's bytes at once: media3 then reports it as removing.
        val memory = LikedCatchUpMemory()
        memory.afterChange("song", Download.STATE_FAILED, t0)
        assertFalse("nothing kept changes", memory.afterChange("song", Download.STATE_REMOVING, t0))
        assertTrue("a failure, tried again after its wait", memory.mayCatchUp("song", t0 + day))
    }

    @Test
    fun `a removal with no failure before it is the person's`() {
        // Remove download, Clear all downloads, the notification's Cancel, the catch up's Stop.
        val memory = LikedCatchUpMemory()
        memory.afterChange("song", Download.STATE_QUEUED, t0)
        memory.afterChange("song", Download.STATE_DOWNLOADING, t0)
        assertTrue(memory.afterChange("song", Download.STATE_REMOVING, t0))
        assertFalse(memory.mayCatchUp("song", t0 + 365 * day))
    }

    @Test
    fun `a failed song queued again and then cancelled was cancelled by the person`() {
        val memory = LikedCatchUpMemory()
        memory.afterChange("song", Download.STATE_FAILED, t0)
        memory.afterChange("song", Download.STATE_REMOVING, t0)   // the app frees it
        memory.afterChange("song", Download.STATE_QUEUED, t0 + day)
        memory.afterChange("song", Download.STATE_REMOVING, t0 + day)   // the person's Cancel
        assertFalse(memory.mayCatchUp("song", t0 + 365 * day))
    }

    @Test
    fun `songs no longer liked are dropped`() {
        val memory = LikedCatchUpMemory()
        memory.removedByPerson("stillLiked", t0)
        memory.removedByPerson("unliked", t0)
        memory.afterChange("albumSong", Download.STATE_FAILED, t0)
        assertTrue(memory.keepOnly(setOf("stillLiked", "other")))
        assertEquals(setOf("stillLiked"), memory.ids)
        assertFalse("nothing more to drop", memory.keepOnly(setOf("stillLiked")))
    }

    @Test
    fun `it is kept across starts, and a line it cannot read is left out`() {
        val memory = LikedCatchUpMemory()
        memory.removedByPerson("removedSong", t0)
        memory.afterChange("failedSong", Download.STATE_FAILED, t0)
        memory.afterChange("failedSong", Download.STATE_FAILED, t0 + day)
        val file = File(dir.root, "liked-catch-up.txt")
        LikedCatchUpMemory.save(memory, file)
        assertFalse("written aside and moved over", File(dir.root, "liked-catch-up.txt.tmp").exists())
        file.appendText("failed broken x 12\nnonsense\n")

        val read = LikedCatchUpMemory.load(file)
        assertEquals(setOf("removedSong", "failedSong"), read.ids)
        assertFalse(read.mayCatchUp("removedSong", t0 + 365 * day))
        // two failures: the second wait, three days from the last
        assertFalse(read.mayCatchUp("failedSong", t0 + day + 3 * day - 1))
        assertTrue(read.mayCatchUp("failedSong", t0 + day + 3 * day))
    }

    @Test
    fun `no file is nothing remembered`() {
        assertTrue(LikedCatchUpMemory.load(File(dir.root, "none.txt")).ids.isEmpty())
    }

    // How DownloadUtil and the sync use it.

    @Test
    fun `the sync's catch up leaves out what the memory holds back`() {
        val catchUp = util.substringAfter("suspend fun downloadLikedSongs(").substringBefore("\n    }\n")
        assertTrue(catchUp.contains("val pending = withContext(catchUpWork) {"))
        assertTrue(catchUp.contains("missing.filter { catchUpMemory.mayCatchUp(it.id, now) }"))
        assertTrue(catchUp.contains("forgetSongsNoLongerLiked(missing.mapTo(HashSet()) { it.id })"))
        // one thing at a time, in the order the changes come
        assertTrue(util.contains("private val catchUpWork = Dispatchers.IO.limitedParallelism(1)"))
    }

    @Test
    fun `the person wants a song again by downloading it, liking it anew, or asking for every liked song`() {
        for (overload in listOf("fun download(songs: List<MediaMetadata>) {", "fun download(song: MediaMetadata) {", "fun download(song: SongEntity) {")) {
            assertTrue(overload, util.substringAfter(overload).trimStart().startsWith("wantedAgain("))
        }
        val like = util.substringAfter("fun autoDownloadOnLike(songs: List<SongEntity>) {").substringBefore("\n    }\n")
        val anew = like.indexOf("wantedAgain(songs.filter { it.liked }.map { it.id })")
        assertTrue("before the setting or Wi-Fi only can return", anew in 0 until like.indexOf("if (!autodownloadAllowedNow(mode)) return"))
        val row = util.substringAfter("fun startLikedDownloads(").substringBefore("fun cancelLikedDownloads(")
        assertTrue(row.indexOf("wantedAgain(batch)") in 0 until row.indexOf("pending.chunked(ENQUEUE_CHUNK)"))
        // liked anew on YouTube, before the catch up that follows
        val liked = sync.substringAfter("private suspend fun likedSongs(")
        val wanted = liked.indexOf("downloadUtil.wantedAgain(toWrite.filter { (_, localSong) -> localSong?.song?.liked != true }.map { it.first.id })")
        assertTrue(wanted > liked.indexOf("database.transactionNow {"))
        assertTrue(wanted < liked.lastIndexOf("downloadUtil.downloadLikedSongs()"))
    }

    @Test
    fun `a removal is remembered where it is made and where media3 reports it`() {
        val remove = util.substringAfter("fun removeDownload(id: String) {").substringBefore("\n    }\n")
        assertTrue(remove.contains("rememberForCatchUp { removedByPerson(id, now) }"))
        val listener = util.substringAfter("override fun onDownloadChanged(")
        val told = listener.indexOf("rememberForCatchUp { afterChange(download.request.id, download.state, changedAt) }")
        assertTrue(told > 0)
        assertTrue("before the failed download's removal is asked", told < listener.indexOf("runCatching { downloadManager.removeDownload(download.request.id) }"))
    }
}

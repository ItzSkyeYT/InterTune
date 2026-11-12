/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A download is kept once, where downloads live: the download cache, or the person's folder.
 *
 * Every download was written into the song cache as well, since its read went through that cache
 * with media3's default writer, and took twice its space: for good with the song cache on Unlimited,
 * and with a size a large batch pushed out the songs kept from streaming. What that copy quietly did
 * is done another way, each pinned here: the player finds a folder download as the app starts, and
 * the adaptive queue counts one as playable offline.
 */
class KeptOnceTest {
    private val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    private val landed = LocalDateTime.of(2026, 11, 4, 18, 0)

    @Test
    fun `a download reads the song cache and never writes it`() {
        // The read of a download: the song cache, then the network. Given no writer, media3 writes
        // all it reads into the cache; given null, the cache is read only.
        val chain = util.substringAfter("private val dataSourceFactory = ResolvingDataSource.Factory(").substringBefore("SabrOrHttp.Factory(")
        val cache = chain.indexOf(".setCache(playerCache)")
        val readOnly = chain.indexOf(".setCacheWriteDataSinkFactory(null)")
        assertTrue(cache > 0)
        assertTrue("the song cache is read only", readOnly > cache)
        assertTrue(readOnly < chain.indexOf(".setUpstreamDataSourceFactory("))
        // A song the cache holds whole is still copied from it.
        assertTrue(util.contains("if (playerCache.holdsWhole(mediaId)) {"))
    }

    @Test
    fun `a song the downloads hold whole leaves the song cache, and nothing else does`() {
        val downloads = mapOf(
            "inApp" to landed,
            "inFolder" to landed,
            "onItsWay" to DownloadUtil.STATE_DOWNLOADING,
            "noBytes" to landed,
            "inAppPart" to DownloadUtil.STATE_DOWNLOADING,
        )
        val keys = listOf("inApp", "inFolder", "onItsWay", "noBytes", "inAppPart", "streamedOnly")
        val drop = KeptOnce.copiesToDrop(keys, downloads, inFolders = setOf("inFolder")) { it == "inApp" || it == "inAppPart" }
        // A download on its way keeps the copy it may be read from; one the map counts but whose
        // bytes are nowhere keeps it too; a song only streamed is the song cache's own.
        assertEquals(listOf("inApp", "inFolder"), drop)
    }

    @Test
    fun `a date in the downloads map is a landed download only when it is a real date`() {
        assertTrue(hasLanded(landed))
        assertFalse("on its way", hasLanded(DownloadUtil.STATE_DOWNLOADING))
        assertFalse("failed or removed", hasLanded(DownloadUtil.STATE_INVALID))
        assertFalse(hasLanded(null))
    }

    @Test
    fun `a landed download takes its copy out of the song cache once the download cache holds it whole`() {
        val listener = util.substringAfter("override fun onDownloadChanged(").substringBefore("// For Report a problem's trail")
        val completed = listener.substringAfter("if (download.state == Download.STATE_COMPLETED) {")
        assertTrue(completed.contains("dropSongCacheCopyOnceDownloaded(download.request.id)"))
        val drop = util.substringAfter("private fun dropSongCacheCopyOnceDownloaded(id: String) {").substringBefore("\n    }\n")
        assertTrue(drop.contains("if (runCatching { downloadCache.holdsWhole(id) }.getOrDefault(false)) dropSongCacheCopy(id, \"it is downloaded\")"))
        assertTrue("off the manager's thread", drop.contains("CoroutineScope(Dispatchers.IO).launch {"))
    }

    @Test
    fun `the copies earlier versions left go at start, once the folders are listed`() {
        val init = util.substringAfter("    init {").substringBefore("PhoneBatterySaver.on")
        val rescan = init.indexOf("rescanDownloads()")
        val opened = init.indexOf("firstListing.countDown()")
        val sweep = init.indexOf("dropSongCacheCopiesOfDownloads()")
        assertTrue(rescan > 0)
        assertTrue("whatever the rescan did", init.substring(rescan, opened).contains("} finally {"))
        assertTrue(sweep > opened)
        assertTrue(sweep < init.indexOf("keepMissingCovers()"))
        val sweepBody = util.substringAfter("private fun dropSongCacheCopiesOfDownloads() {").substringBefore("\n    }\n")
        assertTrue(sweepBody.contains("KeptOnce.copiesToDrop(keys, downloads.value, inFolders)"))
        assertTrue(sweepBody.contains("downloadCache.holdsWhole(id)"))
    }

    @Test
    fun `a download in a folder is played under a key of its own, which the song cache never writes`() {
        assertEquals("folderfile:dQw4w9WgXcQ", downloadedKey("dQw4w9WgXcQ"))
        assertTrue(service.contains("is ResolvedSong.Downloaded -> dataSpec.buildUpon().setUri(resolved.file.uri)\n                    .setKey(downloadedKey(mediaId)).build()"))
        val sink = service.substringAfter("HybridCacheDataSinkFactory(playerCache) { dataSpec ->").substringBefore(".setFlags(")
        assertTrue(sink.contains("dataSpec.key?.startsWith(FOLDER_FILE_KEY) != true"))
        assertFalse("the old key for song files only", service.contains("SONG_FILE_KEY"))
    }

    @Test
    fun `as the app starts, a song with its file in a folder waits for the folders to be listed`() {
        val resolve = service.substringAfter("fun resolve(mediaId: String, position: Long, length: Long").substringBefore("val isDownload =")
        val branch = resolve.substringAfter("if (localPath != null) {").substringAfter("} else {")
        assertTrue(branch.contains("downloadUtil.folderFileOf(mediaId, FIRST_LISTING_WAIT_MS)?.takeIf { it.exists() }"))
        assertTrue(util.contains("afterFirstListing(firstListing, waitMs) { localMgr.isExists(id) }"))
    }

    @Test
    fun `a file in the list is found at once`() {
        val listed = CountDownLatch(1)
        val started = System.nanoTime()
        assertEquals("song", afterFirstListing(listed, 5_000) { "song" })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun `a file not in the list yet is found once the folders are listed`() {
        val listed = CountDownLatch(1)
        val list = AtomicReference<String?>(null)
        thread {
            Thread.sleep(100)
            list.set("song")
            listed.countDown()
        }
        assertEquals("song", afterFirstListing(listed, 5_000) { list.get() })
    }

    @Test
    fun `a list filled by a scan before the first listing opens is found on the way`() {
        // A full scan at start walks the folders while the rescan that opens the latch waits its turn.
        val listed = CountDownLatch(1)
        val list = AtomicReference<String?>(null)
        thread {
            Thread.sleep(100)
            list.set("song")
        }
        val started = System.nanoTime()
        assertEquals("song", afterFirstListing(listed, 5_000, stepMs = 50) { list.get() })
        assertTrue("well before the limit", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun `once the folders are listed, a song not in them is not waited for`() {
        val listed = CountDownLatch(1).apply { countDown() }
        val started = System.nanoTime()
        assertNull(afterFirstListing(listed, 5_000) { null as String? })
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun `a listing that does not come is waited for no longer than the limit`() {
        val listed = CountDownLatch(1)
        val started = System.nanoTime()
        assertNull(afterFirstListing(listed, 200) { null as String? })
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("$waited ms", waited in 150..3_000)
    }

    @Test
    fun `offline, the adaptive queue counts a download in a folder as playable`() {
        val offline = service.substringAfter("playsOffline = { s -> ").substringBefore("},")
        assertTrue(offline.contains("downloadUtil.isDownloaded(s.id)"))
        assertTrue("songs on the phone and songs streamed whole as before", offline.contains("s.isLocal") && offline.contains("playerCache.holdsWhole(s.id)"))
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * The song the app comes back to after it was closed is shown liked and downloaded when it is, from
 * the first look (his report of 27 Nov 2026, experiments/agents-1127/restore-state: the heart empty
 * and the plain Download arrow over a song that was both).
 *
 * After a start the player is empty, and the song on screen is read from the saved queue. The heart
 * then asked the database for the same row a second time, and the download button asked a map of the
 * downloads that stays empty until the download folders have been listed. On a phone busy starting,
 * both stood at "no" for as long. The row read with the queue now leads the heart (RestoredRow), and
 * the database's record of the downloads stands until the listing has landed (DownloadShown).
 */
class RestoredSongShownTest {

    private data class Row(val id: String, val liked: Boolean)

    private val day: LocalDateTime = LocalDateTime.of(2026, 11, 26, 22, 50)

    // ---- the download button

    @Test
    fun `a download the map holds is shown from the map`() {
        val later = day.plusDays(1)
        assertEquals(later, DownloadShown.of("a", mapOf("a" to later), mapOf("a" to day), emptySet()))
    }

    @Test
    fun `until the folders are listed a download the database records is shown`() {
        // The map is empty from the start until the first listing: the song showed as not downloaded.
        assertEquals(day, DownloadShown.of("a", emptyMap(), mapOf("a" to day), emptySet()))
        assertNull(DownloadShown.of("b", emptyMap(), mapOf("a" to day), emptySet()))
    }

    @Test
    fun `a download removed since the start does not come back from the record`() {
        // Remove download during the listing takes it out of the map and notes the song as touched.
        assertNull(DownloadShown.of("a", emptyMap(), mapOf("a" to day), setOf("a")))
    }

    @Test
    fun `only downloads that have landed are recorded`() {
        val recorded = DownloadShown.recorded(
            listOf("a" to day, "on its way" to DownloadUtil.STATE_DOWNLOADING, "failed" to DownloadUtil.STATE_INVALID, "none" to null)
        )
        assertEquals(mapOf("a" to day), recorded)
    }

    @Test
    fun `the record is shown until the first listing has landed, and never after`() {
        val record = RecordedUntilListed()
        record.offer(mapOf("a" to day))
        assertEquals(mapOf("a" to day), record.shown.value)
        record.listed()
        assertEquals(emptyMap<String, LocalDateTime>(), record.shown.value)
        // A read of the record that answers after the listing is not taken.
        record.offer(mapOf("a" to day))
        assertEquals(emptyMap<String, LocalDateTime>(), record.shown.value)
    }

    // ---- the heart

    @Test
    fun `the row read with the saved queue leads the database's answer for its song`() = runBlocking {
        val restored = RestoredRow<Row> { it.id }
        restored.set(Row("a", liked = true))
        assertEquals(listOf(Row("a", true), Row("a", true)), restored.lead("a", flowOf(Row("a", true))).toList())
        // The database's first answer may be late or none at all: the heart shows the like from the first look.
        assertEquals(listOf(Row("a", true)), restored.lead("a", flowOf()).toList())
    }

    @Test
    fun `another song is the database's alone`() = runBlocking {
        val restored = RestoredRow<Row> { it.id }
        restored.set(Row("a", liked = true))
        assertEquals(listOf(Row("b", false)), restored.lead("b", flowOf(Row("b", false))).toList())
        assertEquals(listOf<Row?>(null), restored.lead(null, flowOf(null)).toList())
    }

    @Test
    fun `once play has loaded the song the database answers alone`() = runBlocking {
        val restored = RestoredRow<Row> { it.id }
        restored.set(Row("a", liked = true))
        restored.clear()
        assertEquals(listOf(Row("a", false)), restored.lead("a", flowOf(Row("a", false))).toList())
    }

    @Test
    fun `a like changed after the restore is shown as it is now, not as it was read`() = runBlocking {
        // Unliked before play: the database said so, and a later look at the same song (the queue
        // edited before play shows it again) must not lead with the like as it was read.
        val restored = RestoredRow<Row> { it.id }
        restored.set(Row("a", liked = true))
        restored.lead("a", flowOf(Row("a", liked = false))).toList()
        assertEquals(Row("a", false), restored.lead("a", flowOf()).toList().single())
    }

    // ---- the wiring

    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `the player's song is led by the restored row, kept only while the player is empty`() {
        val connection = File(sources, "playback/PlayerConnection.kt").readText()
        assertTrue("currentSong no longer goes through the restored row", "restoredRow.lead(it?.id, database.song(it?.id))" in connection)
        assertTrue("the restore no longer reads the song's row with the queue", "database.getResumption(service.dataStore[PlayingQueueKey])" in connection)
        assertTrue("the restored row is kept over a player that holds a song", "if (player.currentMetadata == null) restoredRow.set(restored?.current)" in connection)
        val transition = connection.substringAfter("override fun onMediaItemTransition(").substringBefore("\n    }")
        val timeline = connection.substringAfter("override fun onTimelineChanged(").substringBefore("\n    }")
        assertTrue("a song loaded keeps the restored row", "restoredRow.clear()" in transition)
        assertTrue("a timeline loaded keeps the restored row", "restoredRow.clear()" in timeline)
        // Set before the song is shown, which starts the look at its row.
        assertTrue(connection.indexOf("restoredRow.set(restored?.current)") < connection.indexOf("mediaMetadata.value = player.currentMetadata ?: resumption?.getCurrentSong()"))
    }

    @Test
    fun `the screens' download goes by the record until the first listing`() {
        val downloads = File(sources, "playback/DownloadUtil.kt").readText()
        val getDownload = downloads.substringAfter("fun getDownload(songId: String)").substringBefore("\n\n")
        assertTrue("getDownload no longer shows the record:\n$getDownload", "DownloadShown.of(songId, live, recorded, scanTouchedIds)" in getDownload)
        // Offered before the first rescan, ended by every rescan's landing and in any case once the first is over.
        val start = downloads.substringAfter("Log.i(TAG, \"DownloadUtil init\")").substringAfter("CoroutineScope(dlCoroutine).launch {")
            .substringBefore("firstListing.countDown()")
        assertTrue(start.indexOf("recordedUntilListed.offer(") in 0 until start.indexOf("rescanDownloads()"))
        assertTrue("recordedUntilListed.listed()" in start.substringAfter("} finally {"))
        val rescan = downloads.substringAfter("private suspend fun rescanDownloadsLocked() {").substringBefore("Log.i(TAG, \"-rescanDownloads()\")")
        assertTrue("a rescan's landing no longer ends the record", rescan.indexOf("recordedUntilListed.listed()") > rescan.indexOf("applyRescanResult(downloads, result, scanTouchedIds)"))
        // Nothing that decides goes by the record: whether a song is downloaded is the map's alone.
        assertTrue("fun isDownloaded(id: String): Boolean = hasLanded(downloads.value[id])" in downloads)
    }

    // ---- the place

    @Test
    fun `a new service reads the saved queues only once the last one's save has landed`() {
        // Opened again soon after it was closed, the app runs a new service in the same process. With
        // the last one's save still writing (onDestroy waits 4 s for it, then goes on), the new board
        // read the place before the close and the screen the place after: Play went back to the board's.
        val service = File(sources, "playback/MusicService.kt").readText()
        val init = service.substringAfter("suspend fun initQueue(onlyIfNeeded: Boolean = false) {").substringBefore("\n    }\n")
        val waited = init.indexOf("queueSaveUnderWay?.takeIf { it.isActive }")
        assertTrue("initQueue no longer waits for the last service's save", waited >= 0)
        assertTrue("initQueue reads the saved queues before that save has landed", waited < init.indexOf("database.readQueue()"))
        assertTrue("the wait has no bound", "withTimeoutOrNull(LAST_QUEUE_SAVE_WAIT_MS) { save.join() }" in init)
        val deInit = service.substringAfter("fun deInitQueue(waitForSave: Boolean = true) {").substringBefore("\n    }\n")
        assertTrue("onDestroy's save is not noted for the next service", "queueSaveUnderWay = save" in deInit)
        assertTrue("a swipe's save is not noted for the next service", ".also { queueSaveUnderWay = it }" in deInit)
        // For the process, not the instance: the next service is another instance.
        val companion = service.substringAfter("\n    companion object {")
        assertTrue("private var queueSaveUnderWay: kotlinx.coroutines.Job? = null" in companion)
    }
}

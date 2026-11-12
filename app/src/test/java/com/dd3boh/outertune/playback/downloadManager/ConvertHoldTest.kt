/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app is kept up for as long as it converts songs (ConvertHold, ConvertHoldService). A song
 * converted after its download stood still while the person was in another app, since the download
 * service had stopped and nothing else kept the app's work going (a tester on 12 Nov 2026: downloads
 * "sometimes only finishing after you start downloading another song"). The counting takes plain
 * functions, so it is tested here without Android; the service itself is read as text.
 */
class ConvertHoldTest {

    private val events = mutableListOf<String>()
    private val waiting = mutableListOf<() -> Unit>()
    private var allowed = true

    private fun hold() = ConvertHold(
        start = { label -> events += "start $label"; allowed },
        stop = { events += "stop" },
        later = { delayMs, block -> events += "wait $delayMs"; waiting += block },
        lingerMs = 6_000L,
    )

    /** Every wait that is under way runs out. */
    private fun timePasses() {
        val due = waiting.toList()
        waiting.clear()
        due.forEach { it() }
    }

    @Test
    fun `the app is held from the start of a conversion and let go a moment after its end`() {
        val hold = hold()
        hold.begin("MP3")
        assertEquals(listOf("start MP3"), events)
        hold.end()
        assertEquals("not let go at once: the next song of a run is a moment away", listOf("start MP3", "wait 6000"), events)
        timePasses()
        assertEquals(listOf("start MP3", "wait 6000", "stop"), events)
    }

    @Test
    fun `a run of songs is one hold, kept between two songs`() {
        val hold = hold()
        hold.begin("FLAC")
        hold.end()
        hold.begin("FLAC")
        timePasses()
        assertEquals("the first song's wait runs out while the second is converted: still held", 0, events.count { it == "stop" })
        hold.end()
        hold.begin("FLAC")
        hold.end()
        timePasses()
        assertEquals("asked for once", 1, events.count { it.startsWith("start") })
        assertEquals("let go once, after the last", 1, events.count { it == "stop" })
        assertEquals("stop", events.last())
    }

    @Test
    fun `a wait from an earlier song does not let go of a later one's hold early`() {
        val hold = hold()
        hold.begin("WAV")
        hold.end()
        val first = waiting.removeAt(0)
        hold.begin("WAV")
        hold.end()
        first()
        assertEquals("the later song's own wait has not run out", 0, events.count { it == "stop" })
        timePasses()
        assertEquals(1, events.count { it == "stop" })
    }

    @Test
    fun `refused by Android, there is nothing to let go, and the next conversion asks again`() {
        val hold = hold()
        allowed = false
        hold.begin("MP3")
        hold.end()
        timePasses()
        assertEquals(listOf("start MP3"), events)
        allowed = true
        hold.begin("MP3")
        hold.end()
        timePasses()
        assertEquals(listOf("start MP3", "start MP3", "wait 6000", "stop"), events)
    }

    @Test
    fun `a new run after the app was let go holds it again`() {
        val hold = hold()
        hold.begin("M4A")
        hold.end()
        timePasses()
        hold.begin("M4A")
        hold.end()
        timePasses()
        assertEquals(listOf("start M4A", "wait 6000", "stop", "start M4A", "wait 6000", "stop"), events)
    }

    // ---- the service and where it is used, read as text

    private fun code(path: String) = File(path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val service = code("src/main/java/com/dd3boh/outertune/playback/downloadManager/ConvertHoldService.kt")

    @Test
    fun `the service is declared, private to the app, as the kind of foreground service downloads are`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val at = manifest.indexOf("android:name=\".playback.downloadManager.ConvertHoldService\"")
        assertTrue("ConvertHoldService is declared in the manifest", at >= 0)
        val element = manifest.substring(manifest.lastIndexOf('<', at), manifest.indexOf('>', at) + 1)
        assertTrue("android:exported=\"false\"" in element)
        assertTrue("android:foregroundServiceType=\"dataSync\"" in element)
        assertTrue("its permission is asked for", "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in manifest)
        assertTrue("FOREGROUND_SERVICE_TYPE_DATA_SYNC" in service)
    }

    @Test
    fun `a refusal by Android is a line in the log, never the end of the app`() {
        // startForegroundService closes the app when the promotion does not follow in time; a plain start does not.
        assertTrue("started with a plain start", "app.startService(" in service && "startForegroundService(" !in service)
        val start = service.substringAfter("start = {").substringBefore("stop = {")
        assertTrue("a start refused in the background is caught", "catch (e: IllegalStateException)" in start && "false" in start.substringAfter("catch"))
        val command = service.substringAfter("override fun onStartCommand").substringBefore("override fun onTimeout")
        assertTrue("the promotion is tried", "startForeground(ConvertNotice.ONGOING_ID, notification" in command)
        assertTrue("and its refusal caught", command.indexOf("try {") in 0 until command.indexOf("startForeground(") && "catch (e: IllegalStateException)" in command)
        assertTrue("not started again by Android after the app was ended", "START_NOT_STICKY" in command)
    }

    @Test
    fun `it stops at once when Android ends its time in the foreground`() {
        val timeout = service.substringAfter("override fun onTimeout").substringBefore("private fun shown")
        assertTrue("stopForeground(STOP_FOREGROUND_REMOVE)" in timeout && "stopSelf()" in timeout)
    }

    @Test
    fun `every conversion takes the hold, and gives it back however it ends`() {
        val converter = code("src/main/java/com/dd3boh/outertune/playback/downloadManager/SongConverter.kt")
        val convert = converter.substringAfter("fun convert(").substringBefore("private fun encode(")
        assertTrue("hold?.begin(ConvertNotice.label(plan.format))" in convert)
        assertTrue(convert.indexOf("hold?.begin(") in 0 until convert.indexOf("try {"))
        assertTrue("hold?.end()" in convert.substringAfter("} finally {"))
        val util = code("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt")
        val made = util.substringAfter("private val converter by lazy").substringBefore("private fun qualityNow")
        assertTrue("the downloads' converter is given the hold", "ConvertHoldService.hold(context)" in made && "MediaCodecPcmDecoder(), hold)" in made)
    }
}

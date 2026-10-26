/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lines are a phone's own, of 13 Oct 2026, the day a song was left at 31 s with no message
 * (issue #20), with the song's id and the server's name changed.
 */
class ProblemLogTest {
    private val song = listOf(
        "10-13 18:44:09.734 D/QueueBoard( 6711): Starting database save task",
        "10-13 18:44:09.739 D/MusicService( 6711): PLAYING: song id = aaaaaaaaaaa",
        "10-13 18:44:09.746 D/MusicService( 6711): PLAYING: remote song (online fetch)",
        "10-13 18:44:09.842 D/YTPlayerUtils( 6711): [aaaaaaaaaaa] stream client: VISIONOS, playabilityStatus: OK",
        "10-13 18:44:09.966 I/YTPlayerUtils( 6711): [aaaaaaaaaaa] [VISIONOS] found working stream (200), address issued to IPv4",
        "10-13 18:44:09.966 D/YTPlayerUtils( 6711): [aaaaaaaaaaa] stream url: rr3---sn-2oaig5-5n.googlevideo.com, itag 251, mime audio/webm, expire 1791931449, parameters: 40",
        "10-13 18:44:09.981 W/tertune.preview( 6711): Failed to query component store for system resources: 6",
        "10-13 18:44:09.985 W/tertune.preview( 6711): AIBinder_linkToDeath is being called with a non-null cookie and no onUnlink callback set.",
        "10-13 18:44:09.990 E/tertune.preview( 6711): Failed to query component interface for required system resources: 6",
        "10-13 18:44:09.997 W/LoudnessCodecController( 6711): MediaCodec mime type not supported for loudness annotation",
        "10-13 18:44:10.022 D/AudioTrack( 6711): setVolume(0.265766, 0.265766) pid : 6711",
        "10-13 18:44:10.061 D/MusicService( 6711): PLAYING: song id = aaaaaaaaaaa",
        "10-13 18:44:10.062 D/MusicService( 6711): PLAYING: remote song (temp cache)",
        "10-13 18:44:12.500 I/View    ( 6711): requestLayout is already in process",
        "10-13 18:44:12.600 V/BottomSheetPlayer( 6711): PLR-3.2b",
        "10-13 18:44:20.100 D/CCodecBufferChannel( 6711): [c2.android.opus.decoder#12] work failed to finish",
    )
    private val fault = listOf(
        "10-13 18:44:40.811 E/MediaCodecAudioRenderer( 6711): Audio sink error",
        "10-13 18:44:40.811 E/MediaCodecAudioRenderer( 6711):   androidx.media3.exoplayer.audio.AudioSink\$UnexpectedDiscontinuityException: Unexpected audio track timestamp discontinuity: expected 1000031393500, got 1000154241000",
        "10-13 18:44:40.811 E/MediaCodecAudioRenderer( 6711):       at androidx.media3.exoplayer.audio.DefaultAudioSink.handleBuffer(DefaultAudioSink.java:1061)",
        "10-13 18:44:40.811 E/MediaCodecAudioRenderer( 6711):       at android.os.HandlerThread.run(HandlerThread.java:139)",
    )
    private val after = listOf(
        "10-13 18:44:41.756 D/QueueBoard( 6711): Starting database save task",
        "10-13 18:44:41.760 D/MusicService( 6711): Playback ratio: 0.20481935 Min threshold: 0.3 (duration 155s)",
    )

    private fun words(excerpt: ProblemLog.Excerpt) = excerpt.lines.map { it.toString() }

    @Test
    fun `a song given up after its quiet tries is the fault, with the tries that led to it`() {
        val tries = listOf(
            "10-20 16:14:10.100 W/MusicService( 6711): " + StepLog.stallCut("aaaaaaaaaaa", 6_000, 83_000, 1, 3),
            "10-20 16:14:16.100 W/MusicService( 6711): " + StepLog.stallRoute("aaaaaaaaaaa", 12_000, 83_000, "VISIONOS", 2, 3),
            "10-20 16:14:30.000 W/MusicService( 6711): " + StepLog.quietRetry("aaaaaaaaaaa", "Timeout", 1_000, 3, 3),
        )
        val gaveUp = "10-20 16:14:48.084 E/MusicService( 6711): " + StepLog.gaveUp("aaaaaaaaaaa", 3, "no answer from YouTube's servers")
        val excerpt = ProblemLog.excerpt(song + tries + gaveUp + after)
        val shown = words(excerpt)
        assertEquals(StepLog.gaveUp("aaaaaaaaaaa", 3, "no answer from YouTube's servers"), excerpt.lines[excerpt.faultAt!!].message)
        for (line in tries) assertTrue(line, shown.any { it.endsWith(line.substringAfter("): ")) })
    }

    @Test
    fun `a line of the log is read into its time, its level, who wrote it and what it says`() {
        val line = ProblemLog.parse(fault[0])!!
        assertEquals("18:44:40.811", line.time)
        assertEquals('E', line.level)
        assertEquals("MediaCodecAudioRenderer", line.tag)
        assertEquals("Audio sink error", line.message)
        // A tag padded with spaces, and one with brackets in it.
        assertEquals("View", ProblemLog.parse(song[13])!!.tag)
        assertEquals("VRI[MainActivity]@cf0c640", ProblemLog.parse("10-13 18:44:12.500 I/VRI[MainActivity]@cf0c640( 6711): call setFrameRateCategory")!!.tag)
        assertNull(ProblemLog.parse("--------- beginning of main"))
    }

    @Test
    fun `the song left at 31 s is found, with the lines that say how it was fetched`() {
        val excerpt = ProblemLog.excerpt(song + fault + after)
        val at = excerpt.faultAt!!
        assertEquals("Audio sink error", excerpt.lines[at].message)
        val before = words(excerpt).take(at)
        assertTrue(before.any { "PLAYING: remote song (online fetch)" in it })
        assertTrue(before.any { "found working stream (200)" in it })
        assertTrue(before.any { "PLAYING: remote song (temp cache)" in it })
        // The trace under it, and what the app did next.
        assertTrue(words(excerpt).drop(at).any { "timestamp discontinuity: expected 1000031393500" in it })
        assertTrue(words(excerpt).drop(at).any { "Playback ratio: 0.20481935" in it })
    }

    @Test
    fun `what every phone writes for every song is left out`() {
        val shown = words(ProblemLog.excerpt(song + fault + after)).joinToString("\n")
        listOf("I/View", "BottomSheetPlayer", "CCodecBufferChannel", "AudioTrack", "AIBinder_linkToDeath", "Failed to query component", "LoudnessCodecController")
            .forEach { assertFalse("$it is shown", it in shown) }
    }

    @Test
    fun `an error of the phone's own parts is shown and is not taken for the fault`() {
        val phones = "10-13 18:44:50.000 E/SamsungAudioHal( 6711): something of its own failed"
        val excerpt = ProblemLog.excerpt(song + fault + after + phones)
        assertEquals("Audio sink error", excerpt.lines[excerpt.faultAt!!].message)
        assertTrue(words(excerpt).any { "SamsungAudioHal" in it })
    }

    @Test
    fun `of two faults the later one is the one shown`() {
        val later = listOf(
            "10-13 18:50:00.000 D/MusicService( 6711): PLAYING: song id = bbbbbbbbbbb",
            "10-13 18:50:33.580 E/ExoPlayerImplInternal( 6711): Playback error",
            "10-13 18:50:33.580 E/ExoPlayerImplInternal( 6711):   androidx.media3.exoplayer.ExoPlaybackException: Source error",
            "10-13 18:50:33.580 E/ExoPlayerImplInternal( 6711):   Caused by: java.io.EOFException",
        )
        val excerpt = ProblemLog.excerpt(song + fault + after + later)
        assertEquals("Playback error", excerpt.lines[excerpt.faultAt!!].message)
        // The middle of a trace never begins a fault, its last line least of all.
        assertFalse(ProblemLog.beginsFault(ProblemLog.parse(later[3])!!))
    }

    @Test
    fun `with no fault the last lines are shown as they are`() {
        val excerpt = ProblemLog.excerpt(song + after)
        assertNull(excerpt.faultAt)
        assertTrue(words(excerpt).last().endsWith("(duration 155s)"))
        val many = List(300) { "10-13 19:00:%02d.000 D/MusicService( 6711): line $it".format(it % 60) }
        assertEquals(ProblemLog.TAIL, ProblemLog.excerpt(many).lines.size)
    }

    @Test
    fun `no more than what led up to the fault and its trace is kept`() {
        val long = List(500) { "10-13 18:00:00.000 D/MusicService( 6711): earlier $it" } + fault + List(200) { "10-13 19:00:00.000 D/MusicService( 6711): later $it" }
        val excerpt = ProblemLog.excerpt(long)
        assertEquals(ProblemLog.BEFORE, excerpt.faultAt)
        assertEquals(ProblemLog.BEFORE + 1 + ProblemLog.AFTER, excerpt.lines.size)
        assertEquals("earlier 460", excerpt.lines.first().message)
    }

    @Test
    fun `a failed request's trace on System err is one line, not forty`() {
        val failed = listOf(
            "10-13 18:46:31.527 W/System.err( 6711): io.ktor.client.plugins.ClientRequestException: Client request invalid: 400",
            "10-13 18:46:31.528 W/System.err( 6711): \tat io.ktor.client.plugins.DefaultResponseValidationKt.invokeSuspend(DefaultResponseValidation.kt:54)",
            "10-13 18:46:31.528 W/System.err( 6711): \tat kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)",
        )
        val shown = words(ProblemLog.excerpt(song + failed))
        assertEquals(1, shown.count { "System.err" in it })
    }

    @Test
    fun `what is copied does not say where the phone is`() {
        val text = ProblemLog.report(ProblemLog.excerpt(song + fault + after), build = "0.11.5 (93, core release)", device = "samsung SM-S938B, Android 16 (API 36)", installedFrom = "F-Droid")
        assertFalse("the server's name is in it", "googlevideo.com" in text)
        assertFalse("sn-2oaig5" in text)
        assertTrue("stream url: a stream host, itag 251" in text)
        // The song's id stays: it is how the fault is found again.
        assertTrue("[aaaaaaaaaaa]" in text)
        assertTrue(text.startsWith("InterTune 0.11.5 (93, core release)\nsamsung SM-S938B, Android 16 (API 36)\nInstalled from: F-Droid\nThe last fault and what led up to it:\n"))
        assertTrue(">>> here\n18:44:40.811 E/MediaCodecAudioRenderer: Audio sink error" in text)
        // An address in a line goes as it does everywhere else.
        val withAddress = ProblemLog.scrub("18:00:00.000 W/MusicService: failed to connect to /192.168.1.20 (port 443) from /10.0.0.7")
        assertFalse("192.168.1.20" in withAddress)
        assertFalse("10.0.0.7" in withAddress)
    }

    @Test
    fun `what goes into the form's address is cut from the front, never the fault`() {
        val long = List(40) { "10-13 18:00:00.000 D/MusicService( 6711): earlier line number $it of the forty that led up to it" } + fault
        val report = ProblemLog.report(ProblemLog.excerpt(long), build = "b", device = "d", installedFrom = "F-Droid")
        assertEquals(report, ProblemLog.fitted(report, report.length))
        val cut = ProblemLog.fitted(report, 1200)
        assertTrue(cut.length <= 1200)
        assertTrue(cut.startsWith("InterTune b\nd\nInstalled from: F-Droid\nThe last fault and what led up to it:\n(the earlier lines are left out)\n"))
        assertTrue(cut.endsWith("at android.os.HandlerThread.run(HandlerThread.java:139)"))
        assertTrue(">>> here" in cut)
        // From the start of a line: no half line after the note.
        assertTrue(cut.lines()[5].startsWith("18:00:00.000 D/MusicService: earlier line number"))
    }

    @Test
    fun `with nothing wrong what is copied says so`() {
        val text = ProblemLog.report(ProblemLog.excerpt(song + after), build = "b", device = "d", installedFrom = "F-Droid")
        assertTrue("Nothing that looks like a fault. The last lines:" in text)
        assertFalse(">>> here" in text)
    }

    @Test
    fun `with no fault the phone's own warnings are left out`() {
        // A clean start on the emulator, 14 Oct 2026: with nothing gone wrong these filled the page.
        val phones = listOf(
            "10-14 10:13:42.223 W/tertune.preview( 6711): Unexpected CPU variant for x86: x86_64.",
            "10-14 10:13:43.842 W/System.err( 6711): SLF4J(W): No SLF4J providers were found.",
            "10-14 10:13:44.092 W/libc    ( 6711): Access denied finding property \"ro.vendor.perf.scroll_opt\"",
            "10-14 10:13:44.100 W/HWUI    ( 6711): Failed to initialize 101010-2 format, error = EGL_SUCCESS",
            "10-14 10:13:45.956 E/ActivityThread( 6711): Failed to find provider info for com.google.android.gms.chimera",
        )
        val shown = words(ProblemLog.excerpt(phones + song + after))
        listOf("tertune.preview", "SLF4J", "W/libc", "W/HWUI", "ActivityThread").forEach { name -> assertFalse("$name is shown", shown.any { name in it }) }
        assertTrue(shown.any { "PLAYING: remote song (online fetch)" in it })
        // A failed request's first line stays: it is the app's own.
        val failed = "10-14 10:20:00.000 W/System.err( 6711): io.ktor.client.plugins.ClientRequestException: Client request invalid: 400"
        assertTrue(words(ProblemLog.excerpt(song + failed)).any { "ClientRequestException" in it })
    }
}

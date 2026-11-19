/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A report kept the 40 lines before its fault, and a trace before the fault took all of them.
 *
 * #138 (11 Nov 2026, 0.12.9, signed in behind a VPN): "gave up on [4E4VaL5krv8] after 3 tries" stood under
 * the player's own trace of the same failure, written a frame to a line, and the 40 lines kept before it
 * began in that trace's middle. Not one line of the walk that led to it was left (which client said what,
 * whether a new visitorData was tried), and the question of why only signed-in playback failed could not
 * be answered from it. #147 (age-restricted, signed in) came the same way. The frames are its trace's frames,
 * as the report showed them; the walk's lines are the app's own words.
 */
class ProblemLogFramesTest {
    private fun line(time: String, level: Char, tag: String, message: String) = "11-11 18:$time $level/$tag( 4242): $message"
    private fun exo(message: String) = line("40:42.859", 'E', "ExoPlayerImplInternal", message)

    /** What the app wrote while it looked for the song: kept as information and warnings in a release. */
    private val walk = listOf(
        line("40:20.100", 'I', "MusicService", "PLAYING: song id = 4E4VaL5krv8"),
        line("40:20.101", 'I', "MusicService", "PLAYING: remote song (online fetch)"),
        line("40:20.900", 'W', "YTPlayerUtils", "[4E4VaL5krv8] [VISIONOS] answered OK, with no address"),
        line("40:21.400", 'I', "YTPlayerUtils", "[4E4VaL5krv8] VISIONOS refused and no new visitorData to be had, asking again with the web client"),
        line("40:24.300", 'W', "YTPlayerUtils", "[4E4VaL5krv8] No po token for the account"),
        line("40:24.900", 'W', "YTPlayerUtils", "[4E4VaL5krv8] [WEB_REMIX] got bad http status code 403, address issued to IPv4"),
        line("40:25.000", 'I', "YTPlayerUtils", "[4E4VaL5krv8] [VISIONOS] every direct address refused: playing over SABR"),
        line("40:25.200", 'I', "SabrOver", "[4E4VaL5krv8] over SABR: itag 251, 3412 kB"),
        line("40:41.700", 'W', "MusicService", "[4E4VaL5krv8] refused over SABR twice in a row"),
    )

    /** The player's trace of the failure, as #138's report showed it, a frame to a line. */
    private val playersTrace = listOf(
        exo("Playback error"),
        exo("  K2.p: Source error"),
        exo("      at K2.U.t(SourceFile:17)"),
        exo("      at K2.U.handleMessage(SourceFile:446)"),
        exo("      at android.os.Handler.dispatchMessage(Handler.java:111)"),
        exo("      at android.os.Looper.loopOnce(Looper.java:302)"),
        exo("      at android.os.Looper.loop(Looper.java:412)"),
        exo("      at android.os.HandlerThread.run(HandlerThread.java:85)"),
        exo("  Caused by: F2.A: Response code: 403"),
        exo("      at com.dd3boh.outertune.playback.sabr.SabrDataSource.read(SourceFile:237)"),
        exo("      at com.dd3boh.outertune.playback.sabr.SabrOrHttp.read(SourceFile:10)"),
        exo("      at com.dd3boh.outertune.playback.PaceGuardDataSource.read(SourceFile:13)"),
        exo("      at F2.q.read(SourceFile:6)"),
        exo("      at F2.I.read(SourceFile:13)"),
        exo("      at G2.f.read(SourceFile:56)"),
        exo("      at G2.f.read(SourceFile:56)"),
        exo("      at F2.G.read(SourceFile:3)"),
        exo("      at F2.H.read(SourceFile:3)"),
        exo("      at a3.k.c(SourceFile:11)"),
        exo("      at a3.k.a(SourceFile:19)"),
        exo("      at a3.k.j(SourceFile:1)"),
        exo("      at g3.b.h(SourceFile:9)"),
        exo("      at G4.l.z(SourceFile:48)"),
        exo("      at T2.P.a(SourceFile:152)"),
        exo("      at W2.i.run(SourceFile:35)"),
        exo("      at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1100)"),
        exo("      at java.util.concurrent.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:624)"),
        exo("      at java.lang.Thread.run(Thread.java:1571)"),
        exo("  Caused by: java.io.IOException: SABR stream failed: SABR requested a fresh player response"),
        exo("      at z5.e.z(SourceFile:79)"),
        exo("      at T7.a.g(SourceFile:9)"),
        exo("      at A8.q.r(SourceFile:7)"),
        exo("      at u8.a.g(SourceFile:23)"),
        exo("      at T7.a.g(SourceFile:32)"),
        exo("      at u8.M.run(SourceFile:107)"),
        exo("      at h6.w.run(SourceFile:615)"),
        exo("      at C8.j.run(SourceFile:3)"),
        exo("      at C8.a.run(SourceFile:88)"),
        exo("  Caused by: A6.V: SABR requested a fresh player response"),
        exo("      at A6.J.E(SourceFile:157)"),
        exo("      at A6.J.z(SourceFile:2043)"),
        exo("      at T7.a.g(SourceFile:9)"),
        exo("      at u8.M.run(SourceFile:115)"),
        exo("      ... 3 more"),
    )

    private val gaveUp = line("40:42.863", 'E', "MusicService", "PLAYING: gave up on [4E4VaL5krv8] after 3 tries: no answer from YouTube's servers")
    private val after = listOf(
        line("40:42.874", 'W', "System.err", "K2.p: Source error"),
        line("40:57.079", 'W', "WindowOnBackDispatcher", "OnBackInvokedCallback is not enabled for the application."),
    )

    private fun shown(excerpt: ProblemLog.Excerpt) = excerpt.lines.map { it.toString() }

    @Test
    fun `the player's trace before the fault leaves room for the walk that led to it`() {
        // As it was, the trace alone filled what is kept before the fault.
        assertTrue(playersTrace.size > ProblemLog.BEFORE)
        val excerpt = ProblemLog.excerpt(walk + playersTrace + gaveUp + after)
        assertEquals("PLAYING: gave up on [4E4VaL5krv8] after 3 tries: no answer from YouTube's servers", excerpt.lines[excerpt.faultAt!!].message)
        val before = shown(excerpt).take(excerpt.faultAt!!)
        for (step in walk) assertTrue(step, before.any { it.endsWith(step.substringAfter("): ")) })
    }

    @Test
    fun `what each part of the trace says stays, with the frame that says where`() {
        val excerpt = ProblemLog.excerpt(walk + playersTrace + gaveUp + after)
        val before = shown(excerpt).take(excerpt.faultAt!!).joinToString("\n")
        for (head in listOf(
            "Playback error", "K2.p: Source error", "Caused by: F2.A: Response code: 403",
            "Caused by: java.io.IOException: SABR stream failed: SABR requested a fresh player response",
            "Caused by: A6.V: SABR requested a fresh player response",
        )) assertTrue(head, head in before)
        for (first in listOf(
            "at K2.U.t(SourceFile:17)", "at com.dd3boh.outertune.playback.sabr.SabrDataSource.read(SourceFile:237)",
            "at z5.e.z(SourceFile:79)", "at A6.J.E(SourceFile:157)",
        )) assertTrue(first, first in before)
        // The rest are counted where they stood, "... 3 more" as the three frames it stands for.
        assertTrue(before, "E/ExoPlayerImplInternal:       ... 5 more frames left out" in before)
        assertTrue(before, "... 18 more frames left out" in before)
        assertTrue(before, "... 8 more frames left out" in before)
        assertTrue(before, "... 6 more frames left out" in before)
        assertTrue(before, "at android.os.Looper.loop(" !in before)
        assertTrue(before, "at java.lang.Thread.run(" !in before)
    }

    @Test
    fun `the fault's own trace is whole`() {
        val fault = listOf(
            line("44:40.811", 'E', "MediaCodecAudioRenderer", "Audio sink error"),
            line("44:40.811", 'E', "MediaCodecAudioRenderer", "  androidx.media3.exoplayer.audio.AudioSink\$UnexpectedDiscontinuityException: Unexpected audio track timestamp discontinuity"),
            line("44:40.811", 'E', "MediaCodecAudioRenderer", "      at androidx.media3.exoplayer.audio.DefaultAudioSink.handleBuffer(DefaultAudioSink.java:1061)"),
            line("44:40.811", 'E', "MediaCodecAudioRenderer", "      at androidx.media3.exoplayer.audio.DefaultAudioSink.write(DefaultAudioSink.java:900)"),
            line("44:40.811", 'E', "MediaCodecAudioRenderer", "      at android.os.HandlerThread.run(HandlerThread.java:139)"),
        )
        val excerpt = ProblemLog.excerpt(walk + playersTrace + fault)
        assertEquals("Audio sink error", excerpt.lines[excerpt.faultAt!!].message)
        assertEquals(fault.map { it.substringAfter("): ") }, excerpt.lines.drop(excerpt.faultAt!!).map { it.message })
    }

    @Test
    fun `a log with no trace before its fault is kept as it was`() {
        val excerpt = ProblemLog.excerpt(walk + gaveUp + after)
        assertEquals(walk.size, excerpt.faultAt)
        assertEquals(walk.map { it.substringAfter("): ") }, excerpt.lines.take(walk.size).map { it.message })
    }

    @Test
    fun `a single frame under the first is shown as it is`() {
        val two = listOf(exo("  K2.p: Source error"), exo("      at K2.U.t(SourceFile:17)"), exo("      at K2.U.handleMessage(SourceFile:446)"))
        val cut = ProblemLog.framesCut(two.map { ProblemLog.parse(it)!! })
        assertEquals(two.map { it.substringAfter("): ") }, cut.map { it.message })
    }

    @Test
    fun `a cut trace still reads as one in a report that has to be fitted`() {
        // The line that counts what was left out is a frame to the rest of the report's tools (fitted, traceHeads).
        assertTrue(ProblemLog.isFrame("18:40:42.859 E/ExoPlayerImplInternal:       ... 18 more frames left out"))
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import androidx.media3.common.C as Media3
import androidx.media3.datasource.DataSpec
import com.dd3boh.outertune.playback.Recovery
import com.dd3boh.outertune.sabr.SabrFile
import com.dd3boh.outertune.utils.RouteTimes
import com.dd3boh.outertune.utils.StreamCheck
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.zionhuang.innertube.PlayerAnswers
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Issue #138 (0.12.9, signed in behind a VPN): every direct address of a new song was refused,
 * SABR took the song over, and YouTube turned the start down at its first read: "SABR requested a
 * fresh player response". A start has no route to change, so the walk that followed came to the
 * same rung, made a new start from an answer asked the same way, and was turned down the same
 * way: at each of the player's four loads and each of its three quiet retries, sixteen walks of
 * the chain for one song in three and a half minutes, which then ended as "gave up after 3
 * tries: no answer from YouTube's servers".
 *
 * One refusal is still answered with a new start. Two in a row, the second before it brought any
 * music, and the song gets no third for [RouteTimes.SABR_REFUSED_REST_MS]: the walk ends in
 * YouTube's own words, which the player does not try again.
 */
class SabrRefusedTwiceTest {

    @After
    fun forget() {
        SabrStarts.forget(SONG)
        SabrStarts.forget(OTHER)
        SabrStarts.forgetRefusals()
        SabrClock.forgetReads()
    }

    /** Only carried by the exceptions, never read: made without its constructor, which wants a Uri. */
    private val spec: DataSpec = run {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    private val init = ByteArray(100) { it.toByte() }
    private val segments = List(8) { i -> ByteArray(10_000) { (i * 3 + it).toByte() } }
    private val at = segments.runningFold(init.size.toLong()) { start, s -> start + s.size }
    private val size = init.size.toLong() + segments.sumOf { it.size }

    /** YouTube's own words in the report's log, as InnerTubeX throws them. */
    private fun reload() = SabrProtocolException("SABR requested a fresh player response", SabrFailureKind.RELOAD_PLAYER)

    /** The song whole, as a start YouTube serves sends it. */
    private fun served(startMs: Long): Flow<SabrChunk> = flow {
        emit(SabrChunk(init, 0, true, null, null, null))
        segments.forEachIndexed { i, data ->
            if ((i + 1) * 1_000L > startMs) emit(SabrChunk(data, at[i], false, i, i * 1_000L, 1_000L))
        }
    }

    /** A start of [SONG] as SabrOver keeps one, reading [stream]: its address. */
    private fun start(stream: (Long) -> Flow<SabrChunk>): String =
        SabrStarts.put(SabrStarts.Start(SONG, 251, 1L, SabrFile(stream, contentLength = size, durationMs = 8_000), expiresAtMs = Long.MAX_VALUE))

    /** A start YouTube turns down at its first read, before a byte of the song. */
    private fun refusedStart(): String = start { flow<SabrChunk> { throw reload() } }

    /** What a read of [address] from the song's start fails with, having handed over [before] bytes first. */
    private fun failureOf(address: String, before: Int = 0): IOException {
        val read = SabrRead()
        try {
            read.open(address, 0, Media3.LENGTH_UNSET.toLong(), spec)
            var got = 0
            while (got < before) got += read.read(ByteArray(1_000), 0, 1_000)
            read.read(ByteArray(1_000), 0, 1_000)
            fail("the read brought bytes where YouTube was to turn it down")
            throw IllegalStateException()
        } catch (e: IOException) {
            return e
        } finally {
            read.close()
        }
    }

    /** A VISIONOS answer for [song] as SabrOver reads it: see SabrAheadTest. */
    private fun answerOf(song: String, at: Long): PlayerAnswers.Answer {
        val theirs = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"$song","lengthSeconds":"8"},""" +
            """"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AAAA"}}},""" +
            """"streamingData":{"expiresInSeconds":21540,""" +
            """"serverAbrStreamingUrl":"https://rr1---sn-test.googlevideo.com/videoplayback?sabr=1",""" +
            """"adaptiveFormats":[{"itag":251,"lastModified":"1","contentLength":$size,"approxDurationMs":"8000","mimeType":"audio/webm"},""" +
            """{"itag":160,"lastModified":"1","contentLength":1000,"approxDurationMs":"8000","mimeType":"video/mp4","width":256,"height":144}]}}"""
        return PlayerAnswers.Answer(song, YouTubeClient.VISIONOS, theirs, at = at)
    }

    /** The walk's choice in that answer. */
    private fun dataOf(): YTPlayerUtils.PlaybackData {
        val format = PlayerResponse.StreamingData.Format(
            itag = 251, url = null, mimeType = "audio/webm; codecs=\"opus\"", bitrate = 1, width = null, height = null,
            contentLength = size, quality = "tiny", fps = null, qualityLabel = null, averageBitrate = null, audioQuality = null,
            approxDurationMs = "8000", audioSampleRate = null, audioChannels = null, loudnessDb = null, lastModified = 1L,
            signatureCipher = null,
        )
        return YTPlayerUtils.PlaybackData(null, null, null, format, streamUrl = "", streamExpiresInSeconds = 21_540)
    }

    /** What the walk's last rung is handed for [song] at [now]: a start over SABR, or null for none. */
    private fun rung(song: String, now: Long, forDownload: Boolean = false) =
        SabrOver.over(answerOf(song, now), dataOf(), forDownload = forDownload, now = now)

    @Test
    fun `a start YouTube turns down is answered with one new start, and when that is turned down too the song gets no third for ten minutes`() {
        val first = failureOf(refusedStart())
        assertEquals("a refused stream, as before", 403, Recovery.status(first))
        assertNull("one refusal is no row: a start can age", SabrStarts.restsUntil(SONG))
        val now = System.currentTimeMillis()
        assertNotNull("the walk that follows makes a new start", rung(SONG, now))

        val second = failureOf(refusedStart())
        assertEquals(403, Recovery.status(second))
        val until = SabrStarts.restsUntil(SONG)
        assertNotNull("two in a row: the song rests", until)
        val then = System.currentTimeMillis()
        assertTrue("for ten minutes from the second: ${until!! - then} ms", until - then in RouteTimes.SABR_REFUSED_REST_MS - 30_000..RouteTimes.SABR_REFUSED_REST_MS)
        assertNull("no third start for the player: the walk ends in its own words", rung(SONG, then))
        assertNull("nor for a download of the song", rung(SONG, then, forDownload = true))
        assertNull("and nothing stands for its address", SabrStarts.forAddress(SabrStarts.addressOf(SONG, 251, 1L)))
        assertNotNull("another song is started as ever", rung(OTHER, then))
        assertNotNull("and this one again once the rest is over", rung(SONG, until))
    }

    @Test
    fun `a start that brought the player music ends the row, and a file's first piece alone does not`() {
        failureOf(refusedStart())
        // The song plays from a new start, whole: what was refused before it was that start's own.
        val read = SabrRead()
        try {
            read.open(start(::served), 0, Media3.LENGTH_UNSET.toLong(), spec)
            var got = 0L
            val buffer = ByteArray(4_096)
            while (true) {
                val n = read.read(buffer, 0, buffer.size)
                if (n == Media3.RESULT_END_OF_INPUT) break
                got += n
            }
            assertEquals(size, got)
            assertTrue("more than counts as music", size > SabrRead.SERVED_BYTES)
        } finally {
            read.close()
        }
        failureOf(refusedStart())
        assertNull("one refusal since the song last played", SabrStarts.restsUntil(SONG))
        assertNotNull(rung(SONG, System.currentTimeMillis()))

        // The first piece of the file and then the refusal: no music came, and it is the second in a row.
        val afterItsFirstPiece = start {
            flow<SabrChunk> {
                emit(SabrChunk(init, 0, true, null, null, null))
                throw reload()
            }
        }
        assertEquals(403, Recovery.status(failureOf(afterItsFirstPiece, before = init.size)))
        assertNotNull("two in a row", SabrStarts.restsUntil(SONG))
    }

    @Test
    fun `two reads of one start that is turned down count one refusal`() {
        val address = refusedStart()
        val reads = listOf(SabrRead(), SabrRead())
        try {
            reads.forEach { it.open(address, 0, Media3.LENGTH_UNSET.toLong(), spec) }
            for (read in reads) {
                val thrown = try {
                    read.read(ByteArray(1_000), 0, 1_000)
                    null
                } catch (e: IOException) {
                    e
                }
                assertEquals("each read is told of the refusal", 403, thrown?.let { Recovery.status(it) })
            }
        } finally {
            reads.forEach { it.close() }
        }
        assertNull("one start, one refusal", SabrStarts.restsUntil(SONG))
        assertNotNull(rung(SONG, System.currentTimeMillis()))
    }

    @Test
    fun `refusals more than the rest apart are no row`() {
        val start = SabrStarts.Start(SONG, 251, 1L, SabrFile({ flow<SabrChunk> { throw reload() } }, contentLength = size, durationMs = 8_000), expiresAtMs = Long.MAX_VALUE)
        val again = SabrStarts.Start(SONG, 251, 1L, SabrFile({ flow<SabrChunk> { throw reload() } }, contentLength = size, durationMs = 8_000), expiresAtMs = Long.MAX_VALUE)
        SabrStarts.refused(start, nowMs = 1_000_000)
        SabrStarts.refused(again, nowMs = 1_000_000 + RouteTimes.SABR_REFUSED_REST_MS)
        assertNull(SabrStarts.restsUntil(SONG, nowMs = 1_000_001 + RouteTimes.SABR_REFUSED_REST_MS))
        // A stand-in's start is counted for the song the player asked for, which its walks are paced by.
        val standIn = SabrStarts.Start(OTHER, 251, 1L, SabrFile({ flow<SabrChunk> { throw reload() } }, contentLength = size, durationMs = 8_000), expiresAtMs = Long.MAX_VALUE, songId = SONG)
        SabrStarts.refused(standIn, nowMs = 1_000_002 + RouteTimes.SABR_REFUSED_REST_MS)
        assertEquals(1_000_002 + 2 * RouteTimes.SABR_REFUSED_REST_MS, SabrStarts.restsUntil(SONG, nowMs = 1_000_003 + RouteTimes.SABR_REFUSED_REST_MS))
        assertNull("not the stand-in's own id", SabrStarts.restsUntil(OTHER, nowMs = 1_000_003 + RouteTimes.SABR_REFUSED_REST_MS))
    }

    @Test
    fun `the read counts a refusal where it forgot the start, the rung asks before it makes one, and the rest is a new visitorData's`() {
        val source = File("src/main/java/com/dd3boh/outertune/playback/sabr/SabrSource.kt").readText()
        assertTrue(source.contains("TYPE_READ) { song?.let { starts.refused(it) } }"))
        val over = File("src/main/java/com/dd3boh/outertune/playback/sabr/SabrOver.kt").readText()
        val asked = over.indexOf("SabrStarts.restsUntil(clockId, now)")
        assertTrue("before any start is taken or made", asked in 0 until over.indexOf("synchronized(making) {"))
        // The walk's rung goes on to its next answer and then ends as it does without SABR (ChainWalkTest).
        val walk = File("src/main/java/com/dd3boh/outertune/utils/YTPlayerUtils.kt").readText()
        assertTrue(walk.contains("val started = wire.overSabr(answer, asked, songId, forDownload) ?: continue"))
        assertEquals(StreamCheck.NEW_VISITOR_RETRY_MS, RouteTimes.SABR_REFUSED_REST_MS)
    }

    private companion object {
        const val SONG = "REFUSEDSONG"
        const val OTHER = "OTHERSONG01"
    }
}

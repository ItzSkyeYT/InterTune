/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import androidx.media3.common.C as Media3
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.dd3boh.outertune.sabr.SabrFile
import com.dd3boh.outertune.utils.StreamFamily
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.zionhuang.innertube.PlayerAnswers
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Which songs SABR reads ahead (SabrAhead): the one playing, and the next one in the queue once
 * the one playing is held to its end, through the player's own resolver; every other song lets go
 * of what it held as soon as the queue moves on. Quiet whatever goes wrong: a refused read ahead,
 * a resolve that fails, a next song that is not over SABR.
 */
class SabrAheadTest {

    private val init = ByteArray(100) { it.toByte() }
    private val segments = List(8) { i -> ByteArray(500) { (i * 3 + it).toByte() } }
    private val bytes = init + segments.fold(ByteArray(0)) { acc, s -> acc + s }
    private val at = segments.runningFold(init.size.toLong()) { start, s -> start + s.size }
    private val size = bytes.size.toLong()

    private fun chunksFrom(startMs: Long): List<SabrChunk> {
        val out = mutableListOf(SabrChunk(init, 0, true, null, null, null))
        segments.forEachIndexed { i, data ->
            if ((i + 1) * 1_000L > startMs) out += SabrChunk(data, at[i], false, i, i * 1_000L, 1_000L)
        }
        return out
    }

    private fun served(startMs: Long): Flow<SabrChunk> = flow { chunksFrom(startMs).forEach { emit(it) } }

    /** The read ahead's streams of each song, by when they were asked for. */
    private val aheadStreams = mutableMapOf<String, CopyOnWriteArrayList<Long>>()

    private val kept = mutableListOf<String>()

    @After
    fun forget() {
        kept.forEach { SabrStarts.forget(it) }
    }

    /** As inline as it can be: a resolve and what follows it are done when the call returns. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /**
     * A start of [song] as SabrOver keeps one, read ahead on a stream of its own, which tells
     * [told] when it is held to its end as SabrOver has it tell SabrAhead; its address.
     */
    private fun start(
        song: String,
        told: (String) -> Unit = {},
        aheadStream: (Long) -> Flow<SabrChunk> = ::served,
    ): String {
        kept += song
        val streams = aheadStreams.getOrPut(song) { CopyOnWriteArrayList() }
        val file = SabrFile(
            stream = ::served,
            contentLength = size,
            durationMs = 8_000,
            ahead = SabrFile.Ahead(
                stream = { startMs -> streams += startMs; aheadStream(startMs) },
                onRestHeld = { told(song) },
            ),
        )
        return SabrStarts.put(SabrStarts.Start(song, 251, 1L, file, expiresAtMs = Long.MAX_VALUE, songId = song, networkChanges = SabrOver.networkChangesSeen))
    }

    private fun fileOf(address: String) = SabrStarts.forAddress(address)!!.file

    private fun eventually(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > end) fail("not after 5 s: $what")
            Thread.sleep(5)
        }
    }

    private fun heldWhole(address: String) {
        assertTrue(fileOf(address).readAhead(0))
        eventually("held to its end") { fileOf(address).restHeld }
    }

    @Test
    fun `the songs neither playing nor next let go of what they held, and those two keep theirs`() {
        val a = start(A)
        val b = start(B)
        val c = start(C)
        listOf(a, b, c).forEach(::heldWhole)
        val ahead = SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { null })
        ahead.queueAt(B, C)
        assertEquals(0L, fileOf(a).heldSize)
        assertEquals(size, fileOf(b).heldSize)
        assertEquals(size, fileOf(c).heldSize)
        // A jump to a song of another queue: every one of them lets go.
        ahead.queueAt(D, null)
        assertEquals(0L, fileOf(b).heldSize)
        assertEquals(0L, fileOf(c).heldSize)
    }

    @Test
    fun `once the song playing is held to its end, the next one is resolved through the player's resolver and read ahead, once`() {
        val asked = CopyOnWriteArrayList<String>()
        lateinit var ahead: SabrAhead
        val a = start(A, told = { ahead.restHeld(it) })
        val b = start(B, told = { ahead.restHeld(it) })
        ahead = SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { id -> asked += id; if (id == B) b else null })
        ahead.queueAt(A, B)
        assertTrue("nothing before the song playing is held: $asked", asked.isEmpty())
        // The player's first read of the song playing starts its read ahead, which says when it is held.
        val reader = fileOf(a).open(0, -1)
        reader.read(ByteArray(10), 0, 10)
        reader.close()
        eventually("the next song resolved ahead") { asked.isNotEmpty() }
        assertEquals(listOf(B), asked)
        eventually("the next song held to its end") { fileOf(b).restHeld }
        assertEquals(listOf(0L), aheadStreams.getValue(B).toList())
        ahead.restHeld(A)
        ahead.queueAt(A, B)
        assertEquals("once for that pair", listOf(B), asked)
    }

    @Test
    fun `when the queue moves to a song already held, its next one is read ahead at once`() {
        val asked = CopyOnWriteArrayList<String>()
        val b = start(B)
        val c = start(C)
        heldWhole(b)
        val ahead = SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { id -> asked += id; c })
        ahead.queueAt(B, C)
        assertEquals(listOf(C), asked)
        eventually("the next song held to its end") { fileOf(c).restHeld }
    }

    @Test
    fun `a next song with a direct address, on the phone already, or left by the queue meanwhile is not read ahead`() {
        val a = start(A)
        val b = start(B)
        heldWhole(a)
        SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { "https://rr1---sn-example.googlevideo.com/videoplayback?itag=251" }).queueAt(A, B)
        SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { null }).queueAt(A, B)
        lateinit var moving: SabrAhead
        // The listener skips while the next song is being resolved.
        moving = SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { id ->
            if (id == B) {
                moving.queueAt(A, C)
                b
            } else {
                null
            }
        })
        moving.queueAt(A, B)
        assertTrue("${aheadStreams[B]}", aheadStreams.getValue(B).isEmpty())
        assertEquals(0L, fileOf(b).heldSize)
    }

    @Test
    fun `not allowed, as with Data Saver on, nothing is resolved ahead`() {
        val a = start(A)
        start(B)
        heldWhole(a)
        var resolved = false
        val ahead = SabrAhead(scope, playerFirstMs = 0, allowed = { false }, resolve = { resolved = true; null })
        ahead.queueAt(A, B)
        ahead.restHeld(A)
        assertFalse("resolved ahead with Data Saver on", resolved)
        assertFalse(ahead.allowedNow())
        assertFalse(SabrAhead(scope, playerFirstMs = 0, allowed = { error("could not tell") }, resolve = { null }).allowedNow())
    }

    @Test
    fun `the same song next as now, as with repeat one, reads nothing more`() {
        val a = start(A)
        heldWhole(a)
        var resolved = false
        val ahead = SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { resolved = true; null })
        ahead.queueAt(A, A)
        assertFalse("resolved the song playing again", resolved)
        assertEquals(size, fileOf(a).heldSize)
    }

    @Test
    fun `a next song whose start the song cache holds is read ahead from the first byte after it`() {
        val asked = CopyOnWriteArrayList<String>()
        val a = start(A)
        val b = start(B)
        heldWhole(a)
        val ahead = SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { id -> asked += id; b }, cachedUpTo = { at[3] })
        ahead.queueAt(A, B)
        assertEquals(listOf(B), asked)
        eventually("the rest of the next song held") { fileOf(b).restHeld }
        assertFalse("the part in the song cache is not fetched again", fileOf(b).holds(0))
        assertFalse(fileOf(b).holds(at[3] - 1))
        assertTrue(fileOf(b).holds(at[3]))
        assertEquals(size - at[3], fileOf(b).heldSize)
    }

    @Test
    fun `the next song waits for the player's own load first, and one the queue has left meanwhile is not resolved`() {
        val asked = CopyOnWriteArrayList<String>()
        val a = start(A)
        val b = start(B)
        val c = start(C)
        heldWhole(a)
        val ahead = SabrAhead(CoroutineScope(Dispatchers.Default), playerFirstMs = 300, allowed = { true }, resolve = { id -> asked += id; if (id == B) b else c })
        ahead.queueAt(A, B)
        assertTrue("resolved before its wait was over: $asked", asked.isEmpty())
        // Another song put next before the wait is over.
        ahead.queueAt(A, C)
        eventually("the song next now resolved ahead") { asked.isNotEmpty() }
        Thread.sleep(400)
        assertEquals(listOf(C), asked)
    }

    @Test
    fun `a next song left during its wait is asked for again when the queue comes back to it`() {
        val asked = CopyOnWriteArrayList<String>()
        val a = start(A)
        val b = start(B)
        heldWhole(a)
        val ahead = SabrAhead(CoroutineScope(Dispatchers.Default), playerFirstMs = 300, allowed = { true }, resolve = { id -> asked += id; b })
        ahead.queueAt(A, B)
        // A jump elsewhere before the wait is over, then back.
        ahead.queueAt(D, null)
        Thread.sleep(600)
        assertTrue("$asked", asked.isEmpty())
        // The jump let go of what the song held: held again, as the player's reads would have it.
        heldWhole(a)
        ahead.queueAt(A, B)
        eventually("the next song resolved ahead once the queue is back") { asked.toList() == listOf(B) }
    }

    @Test
    fun `a resolve that fails is quiet, and tried again at the queue's next change`() {
        val a = start(A)
        val b = start(B)
        heldWhole(a)
        var tries = 0
        val ahead = SabrAhead(scope, playerFirstMs = 0, allowed = { true }, resolve = { if (++tries == 1) throw java.net.UnknownHostException("no network") else b })
        ahead.queueAt(A, B)
        assertEquals(1, tries)
        // A radio adds songs, and the queue changes.
        ahead.queueAt(A, B)
        assertEquals(2, tries)
        eventually("the next song held to its end") { fileOf(b).restHeld }
    }

    @Test
    fun `a start forgotten, run out or replaced lets go of what it held`() {
        val a = start(A)
        heldWhole(a)
        val forgotten = fileOf(a)
        SabrStarts.forget(A)
        assertEquals(0L, forgotten.heldSize)

        kept += B
        val runsOut = SabrFile(::served, contentLength = size, durationMs = 8_000, ahead = SabrFile.Ahead(::served))
        val b = SabrStarts.put(SabrStarts.Start(B, 251, 1L, runsOut, expiresAtMs = System.currentTimeMillis() + 60_000))
        assertTrue(runsOut.readAhead(0))
        eventually("held") { runsOut.restHeld }
        assertEquals(null, SabrStarts.forAddress(b, nowMs = System.currentTimeMillis() + 120_000))
        assertEquals(0L, runsOut.heldSize)

        val c = start(C)
        heldWhole(c)
        val replaced = fileOf(c)
        start(C)
        assertEquals(0L, replaced.heldSize)
    }

    /**
     * A VISIONOS answer for [song] as SabrOver reads it: itag 251 of this file, and what InnerTubeX
     * needs to make a start of it (a video format to discard, the ustreamer config, the video id).
     */
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

    /** Only carried by the exceptions, never read: made without its constructor, which wants a Uri. */
    private val spec: DataSpec = run {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    @Test
    fun `a read ahead YouTube refuses never reaches the player, whose read gets the song whole and nothing to count against a route`() {
        val address = start(A, aheadStream = { startMs ->
            flow {
                for (chunk in chunksFrom(startMs)) {
                    if (chunk.sequenceNumber == 3) throw SabrProtocolException("SABR error refused (3)", SabrFailureKind.ATTESTATION_REQUIRED)
                    emit(chunk)
                }
            }
        })
        val read = SabrRead()
        try {
            assertEquals(size, read.open(address, 0, Media3.LENGTH_UNSET.toLong(), spec))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(333)
            while (true) {
                val n = read.read(buffer, 0, buffer.size)
                if (n == Media3.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
            assertArrayEquals(bytes, out.toByteArray())
        } finally {
            read.close()
        }
        assertTrue("the start is kept: no refusal forgot it", SabrStarts.forAddress(address) != null)
    }

    @Test
    fun `the stall watch's cut ends a read that waits on the read ahead, at once, as it ends one on its own stream`() {
        // A read ahead that sends the init chunk and then nothing, as a connection gone silent does.
        val address = start(A, aheadStream = { _ ->
            flow {
                emit(SabrChunk(init, 0, true, null, null, null))
                awaitCancellation()
            }
        })
        val calls = StreamFamily.Calls(OkHttpClient.Builder().build())
        val read = SabrRead(cutWith = calls)
        val reading = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        try {
            read.open(address, 0, Media3.LENGTH_UNSET.toLong(), spec)
            assertEquals(init.size, read.read(ByteArray(1_000), 0, 1_000))
            val next = reading.submit(Callable<IOException?> {
                try {
                    read.read(ByteArray(1_000), 0, 1_000)
                    null
                } catch (e: IOException) {
                    e
                }
            })
            Thread.sleep(300)
            assertFalse("it waits for the read ahead's bytes", next.isDone)
            assertEquals("the cut counts the read it ended", 1, calls.cancelInFlight())
            val thrown = next.get(3, TimeUnit.SECONDS)
            assertTrue("a failed read, which media3 opens again: $thrown", thrown is HttpDataSource.HttpDataSourceException)
        } finally {
            read.close()
            reading.shutdownNow()
        }
    }

    @Test
    fun `a second walk of the song that ends in the same format takes its live start, with what it read ahead, and makes no other`() {
        // The player's own load of the next song and SabrAhead's walk it at once, and both reach SABR.
        val address = start(A)
        heldWhole(address)
        val kept = SabrStarts.forAddress(address)!!
        val now = System.currentTimeMillis()
        val again = SabrOver.over(answerOf(A, now), dataOf(), now = now)
        assertEquals(address, again?.streamUrl)
        assertSame("the same start, not a new one", kept, SabrStarts.forAddress(address))
        assertEquals("with what it read ahead", size, kept.file.heldSize)
        assertTrue("it lasts no longer than the start it took", again!!.streamExpiresInSeconds <= 21_540)
    }

    @Test
    fun `after a change of network a new walk makes a start of its own from its answer, and the old one lets go of what it held`() {
        val address = start(A)
        heldWhole(address)
        val before = SabrStarts.forAddress(address)!!
        SabrOver.forgetConnections()
        val now = System.currentTimeMillis()
        val again = SabrOver.over(answerOf(A, now), dataOf(), now = now)
        assertEquals(address, again?.streamUrl)
        val after = SabrStarts.forAddress(address)
        assertTrue("a new start", after != null && after !== before)
        assertEquals(0L, before.file.heldSize)
        assertEquals("made after the change, and taken by the next walk", SabrOver.networkChangesSeen, after!!.networkChanges)
        assertSame(after, SabrStarts.forAddress(SabrOver.over(answerOf(A, now), dataOf(), now = now)!!.streamUrl))
    }

    @Test
    fun `a start is taken and made under one lock, so two walks ending together make one`() {
        val made = over.substringAfter("synchronized(making) {").substringBefore("SabrStarts.put(")
        assertTrue(made, "SabrStarts.forAddress(same, now)" in made)
        assertTrue(made, "it.networkChanges == networkChanges.get()" in made)
        assertTrue("networkChanges = networkChanges.get()," in over)
        val forget = over.substringAfter("fun forgetConnections() {").substringBefore("\n    }")
        assertTrue(forget, "networkChanges.incrementAndGet()" in forget)
    }

    @Test
    fun `a song held by its read ahead is found by its song even once its address is forgotten, as a change of network does`() {
        val address = start(A)
        assertEquals(null, SabrStarts.heldAt(A, 0, 251, 1L))
        heldWhole(address)
        assertEquals(address, SabrStarts.heldAt(A, 0, 251, 1L))
        assertEquals(address, SabrStarts.heldAt(A, size - 1, 251, null))
        assertEquals("not another song's", null, SabrStarts.heldAt(B, 0, 251, 1L))
        assertEquals("not another format's: the bytes at a position are its file's only", null, SabrStarts.heldAt(A, 0, 140, 1L))
        assertEquals(null, SabrStarts.heldAt(A, 0, 251, 2L))
        fileOf(address).dropAhead()
        assertEquals("nothing held, nothing to play without a network", null, SabrStarts.heldAt(A, 0, 251, 1L))
        // And the player's resolver asks for one before it walks the chain.
        val resolver = service.substringAfter("inner class SongResolver(").substringBefore("fun startCastServer")
        val known = resolver.indexOf("addresses.of(mediaId, System.currentTimeMillis())")
        val held = resolver.indexOf("SabrStarts.heldAt(mediaId, position, format.itag, format.lastModified)")
        val walk = resolver.indexOf("remote song (online fetch)")
        assertTrue("$known, $held, $walk", known >= 0 && held > known && walk > held)
    }

    // ---- where it is wired

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val over = File("src/main/java/com/dd3boh/outertune/playback/sabr/SabrOver.kt").readText()

    @Test
    fun `only the player's starts read ahead, at a download's pace, and never a download's own`() {
        assertTrue("val reads = ahead?.takeIf { Unreleased.SABR_AHEAD && !forDownload }" in over)
        assertTrue("ahead = reads?.let { songs ->" in over)
        val reading = over.substringAfter("SabrFile.Ahead(").substringBefore("allowed =")
        assertTrue(reading, "playbackPositionMs = downloadClock(startMs)" in reading)
        assertTrue("onRestHeld = { songs.restHeld(clockId) }" in over)
        assertTrue("songId = clockId," in over)
    }

    @Test
    fun `the queue is told of every change of song, queue, shuffle and repeat, and the next song is resolved by the player's own resolver`() {
        val events = service.substringAfter("override fun onEvents(").substringBefore("\n    }\n")
        assertTrue(events, "sabrAhead.queueAt(player.currentMediaItem?.mediaId, next)" in events)
        val told = events.substringAfter("if (Unreleased.SABR_AHEAD && events.containsAny(").substringBefore("sabrAhead.queueAt(")
        for (event in listOf("EVENT_MEDIA_ITEM_TRANSITION", "EVENT_TIMELINE_CHANGED", "EVENT_SHUFFLE_MODE_ENABLED_CHANGED", "EVENT_REPEAT_MODE_CHANGED")) {
            assertTrue("$event in $told", event in told)
        }
        val resolving = service.substringAfter("private fun resolveAhead(").substringBefore("\n\n")
        assertTrue(resolving, "playerResolver.resolve(id, 0L, C.LENGTH_UNSET.toLong(), ahead = true)" in resolving)
        // After the player's own resolve of the song, when one is out: the chain walked once.
        assertTrue(resolving, resolving.indexOf("while (resolvingSongs.containsKey(id)") in 0 until resolving.indexOf("playerResolver.resolve("))
        assertTrue("cachedUpTo = { id -> playerCache.getCachedLength(id, 0, Long.MAX_VALUE).coerceAtLeast(0) }," in service)
        assertTrue("val resolver = if (forPlayer) playerResolver else SongResolver(streamAddresses)" in service)
        assertTrue("if (Unreleased.SABR_AHEAD) SabrOver.ahead = sabrAhead" in service)
    }

    @Test
    fun `Data Saver and casting turn the read ahead off, and what was read ahead goes with the service`() {
        assertTrue("allowed = { Unreleased.SABR_AHEAD && !isCasting && !dataSaverOn() }," in service)
        val saver = service.substringAfter("private fun dataSaverOn()").substringBefore("\n\n")
        assertTrue(saver, "RESTRICT_BACKGROUND_STATUS_ENABLED" in saver && "isActiveNetworkMetered" in saver)
        val destroy = service.substringAfter("override fun onDestroy() {").substringBefore("override fun onBind(")
        assertTrue("if (SabrOver.ahead === sabrAhead) SabrOver.ahead = null" in destroy)
        assertTrue("sabrAhead.queueAt(null, null)" in destroy)
    }

    private companion object {
        const val A = "AHEADSONG0A"
        const val B = "AHEADSONG0B"
        const val C = "AHEADSONG0C"
        const val D = "AHEADSONG0D"
    }
}

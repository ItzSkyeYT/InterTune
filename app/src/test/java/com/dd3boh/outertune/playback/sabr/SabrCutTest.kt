/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.dd3boh.outertune.playback.Recovery
import com.dd3boh.outertune.sabr.SabrFile
import com.dd3boh.outertune.utils.StreamFamily
import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The stall watch's first step cuts the player's calls (StreamFamily.Calls.cancelInFlight). SABR
 * goes through a client of its own, so the cut never reached it: the read stayed blocked through
 * the first step, its address was forgotten at the second while the read still waited, and the
 * song was given up at the third, 18 s into a silence SABR might have come out of.
 */
class SabrCutTest {

    /** Every read a test opened, closed after it whatever happened, so that no thread is left waiting. */
    private val opened = mutableListOf<SabrRead>()

    @After
    fun forget() {
        opened.forEach { it.close() }
        reading.shutdownNow()
        SabrStarts.forget(SONG)
        SabrClock.forgetReads()
    }

    /** Only carried by the exceptions, never read: made without its constructor, which wants a Uri. */
    private val spec: DataSpec = run {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    private val streamStopped = CountDownLatch(1)

    /** A song whose stream sends its init chunk and then nothing, as a connection gone silent does. */
    private fun silentAfterItsStart(): String {
        val file = SabrFile(
            stream = {
                flow {
                    emit(SabrChunk(ByteArray(100), 0, true, null, null, null))
                    try {
                        awaitCancellation()
                    } finally {
                        streamStopped.countDown()
                    }
                }
            },
            contentLength = 10_000,
            durationMs = 60_000,
        )
        return SabrStarts.put(SabrStarts.Start(SONG, 251, 1L, file, expiresAtMs = Long.MAX_VALUE))
    }

    private val client = OkHttpClient.Builder().build()
    private val reading = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }

    private fun reader(cutWith: StreamFamily.Calls) = SabrRead(cutWith = cutWith).also { opened += it }

    /** The next read of [read], on a loader's thread of its own: what it threw, or null for bytes. */
    private fun blockedRead(read: SabrRead) = reading.submit(Callable<IOException?> {
        try {
            read.read(ByteArray(1_000), 0, 1_000)
            null
        } catch (e: IOException) {
            e
        }
    })

    @Test
    fun `the cut of the player's calls ends the player's SABR read, which media3 then opens again at the same byte`() {
        val playerCalls = StreamFamily.Calls(client)
        val read = reader(playerCalls)
        read.open(silentAfterItsStart(), 0, C.LENGTH_UNSET.toLong(), spec)
        assertEquals(100, read.read(ByteArray(1_000), 0, 1_000))
        val next = blockedRead(read)
        Thread.sleep(300)
        assertEquals("the cut counts the SABR read it ended", 1, playerCalls.cancelInFlight())
        val thrown = try {
            next.get(3, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            read.close()
            fail("the read still waited 3 s after the cut")
            null
        }
        assertTrue("a failed read: $thrown", thrown is HttpDataSource.HttpDataSourceException)
        assertNull("not a refusal", Recovery.status(thrown!!))
        val loads = Recovery.LoadFailures()
        loads.cutByUs("song", 6_000)
        assertEquals("the cut's own failure is not the route's", Recovery.Decision.IGNORE, loads.decide("song", thrown, 6_010))
        assertTrue("the stream it read is stopped", streamStopped.await(2, TimeUnit.SECONDS))
        read.close()
        assertEquals("a read closed is no longer cut", 0, playerCalls.cancelInFlight())
    }

    @Test
    fun `a read on other calls, a lyrics clip's or the cast server's, is never cut with the player's`() {
        val playerCalls = StreamFamily.Calls(client)
        val read = reader(StreamFamily.Calls(client))
        read.open(silentAfterItsStart(), 0, C.LENGTH_UNSET.toLong(), spec)
        assertEquals(100, read.read(ByteArray(1_000), 0, 1_000))
        val next = blockedRead(read)
        Thread.sleep(200)
        assertEquals(0, playerCalls.cancelInFlight())
        Thread.sleep(200)
        assertTrue("still waiting for bytes", !next.isDone)
        read.close()
        next.get(3, TimeUnit.SECONDS)
    }

    @Test
    fun `the player's SABR reads are cut with the player's calls, and SABR's connections go with a change of network`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue(service.contains("SabrOrHttp.Factory(OkHttpDataSource.Factory(calls), cutWith = calls)"))
        val change = service.substringAfter("connectivityObserver = NetworkConnectivityObserver(this@MusicService) {").substringBefore("\n            }\n")
        assertTrue(change, change.contains("runCatching { SabrOver.forgetConnections() }"))
        val source = File("src/main/java/com/dd3boh/outertune/playback/sabr/SabrSource.kt").readText()
        assertTrue(source.contains("SabrDataSource(cutWith = cutWith)"))
        assertTrue(source.contains("private val read = SabrRead(starts, cutWith)"))
    }

    private companion object {
        const val SONG = "CUTSONG0001"
    }
}

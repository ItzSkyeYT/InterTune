/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.upstream.Loader
import com.dd3boh.outertune.utils.StepTimedOut
import com.dd3boh.outertune.utils.YTPlayerUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * What the player does on its own when a song stops or a load fails, with the errors built as
 * media3 builds them.
 */
class RecoveryTest {
    /**
     * Only carried by the exceptions, never read. Made without its constructor, which wants a
     * Uri, and android.net.Uri is an empty stub on the JVM.
     */
    private val spec: DataSpec = run {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    private fun refused(code: Int) = HttpDataSource.InvalidResponseCodeException(code, "refused", null, emptyMap(), spec, ByteArray(0))

    /** A load's failure as the player reports it when it gives up. */
    private fun stopped(cause: IOException, code: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) = ExoPlaybackException.createForSource(cause, code)

    /** The resolver's own failure, which media3 wraps as an unexpected loader exception. */
    private fun resolverFailed(message: String, cause: Throwable?, code: Int) =
        stopped(Loader.UnexpectedLoaderException(PlaybackException(message, cause, code)))

    @Test
    fun `timeouts, refused streams and a connection that failed are passing`() {
        val passing = listOf(
            stopped(refused(403), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            stopped(refused(410), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            stopped(refused(503), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            resolverFailed("Timeout", SocketTimeoutException("timeout"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
            resolverFailed("Timeout", StepTimedOut("resolve", 20_000), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
            resolverFailed("Unknown error", IOException("unexpected end of stream"), PlaybackException.ERROR_CODE_REMOTE_ERROR),
            PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        for (error in passing) assertEquals(error.toString(), Recovery.Kind.PASSING, Recovery.kind(error))
    }

    @Test
    fun `YouTube's word on the song and the file's own faults are final`() {
        val final = listOf(
            stopped(Loader.UnexpectedLoaderException(YTPlayerUtils.SongUnavailable("This video is not available"))),
            resolverFailed("Sign in to confirm you’re not a bot", null, PlaybackException.ERROR_CODE_REMOTE_ERROR),
            resolverFailed("Sign in to confirm your age", null, PlaybackException.ERROR_CODE_REMOTE_ERROR),
            resolverFailed("YouTube refused the stream (HTTP 403)", null, PlaybackException.ERROR_CODE_REMOTE_ERROR),
            resolverFailed("File not found", Throwable(), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
            PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED),
            stopped(refused(404), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            stopped(Loader.UnexpectedLoaderException(YTPlayerUtils.ConnectionTurnedAway("This video is not available"))),
        )
        for (error in final) assertEquals(error.toString(), Recovery.Kind.FINAL, Recovery.kind(error))
    }

    @Test
    fun `a failed load is retried, the second within thirty seconds changes the route`() {
        val loads = Recovery.LoadFailures()
        val reset = HttpDataSource.HttpDataSourceException(SocketTimeoutException("timeout"), spec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, HttpDataSource.HttpDataSourceException.TYPE_READ)
        assertEquals(Recovery.Decision.RETRY, loads.decide("song", reset, 0))
        assertEquals(Recovery.Decision.CHANGE_ROUTE, loads.decide("song", reset, 29_000))
        assertEquals("counted afresh after a change", Recovery.Decision.RETRY, loads.decide("song", reset, 30_000))
        assertEquals("a minute apart is two blips", Recovery.Decision.RETRY, loads.decide("song", reset, 95_000))
        assertEquals(Recovery.Decision.RETRY, loads.decide("other", reset, 95_000))
    }

    @Test
    fun `a 403 or a 410 changes the route at once, and the resolver's own failure and the pace guard's cut are not the route's`() {
        val loads = Recovery.LoadFailures()
        assertEquals(Recovery.Decision.CHANGE_ROUTE, loads.decide("song", refused(403), 0))
        assertEquals(Recovery.Decision.CHANGE_ROUTE, loads.decide("song", refused(410), 0))
        assertEquals(Recovery.Decision.IGNORE, loads.decide("song", Loader.UnexpectedLoaderException(PlaybackException("x", null, 1000)), 0))
        assertEquals(Recovery.Decision.IGNORE, loads.decide("song", SlowStreamException("slow", spec), 0))
        assertEquals(Recovery.Decision.IGNORE, loads.decide(null, refused(403), 0))
    }

    @Test
    fun `the budget allows three a song and eight in all, within ten minutes`() {
        val budget = Recovery.Budget()
        assertEquals(1, budget.take("a", 0))
        assertEquals(2, budget.take("a", 1))
        assertEquals(3, budget.take("a", 2))
        assertNull("the fourth for one song", budget.take("a", 3))
        assertEquals("ten minutes after the last", 1, budget.take("a", 600_002))
        for (i in 0 until 7) budget.take("song$i", 600_003)
        assertNull("eight in all, past a song's first", budget.take("song0", 600_004))
        assertEquals("a new song's first is always allowed", 1, budget.take("b", 600_004))
        assertEquals(1, budget.take("b", 1_200_005))
    }

    @Test
    fun `the failure a cut of ours brings is not the route's`() {
        val loads = Recovery.LoadFailures()
        val reset = HttpDataSource.HttpDataSourceException(SocketTimeoutException("timeout"), spec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, HttpDataSource.HttpDataSourceException.TYPE_READ)
        assertEquals(Recovery.Decision.RETRY, loads.decide("song", reset, 0))
        loads.cutByUs("song", 6_000)
        assertEquals("the cut's own failure", Recovery.Decision.IGNORE, loads.decide("song", reset, 6_010))
        assertEquals("a real one after it still counts", Recovery.Decision.CHANGE_ROUTE, loads.decide("song", reset, 9_000))
        loads.cutByUs("song", 20_000)
        assertEquals("a refusal is never ours", Recovery.Decision.CHANGE_ROUTE, loads.decide("song", refused(403), 20_010))
    }

    @Test
    fun `a bad stretch on other songs leaves a new song its first quiet try`() {
        val budget = Recovery.Budget()
        for (song in 1..4) repeat(2) { budget.take("song$song", it * 1_000L) }
        assertEquals(1, budget.take("song5", 60_000L))
        assertNull("its second waits for the stretch to pass", budget.take("song5", 61_000L))
    }

    /** ktor's 5xx, made without its constructor, which wants a live response. Only its type is read. */
    private fun serverError(): Throwable {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, io.ktor.client.plugins.ServerResponseException::class.java) as Throwable
    }

    @Test
    fun `a resolve that failed on YouTube's own server error is passing`() {
        assertEquals(Recovery.Kind.PASSING, Recovery.kind(resolverFailed("Unknown error", serverError(), PlaybackException.ERROR_CODE_REMOTE_ERROR)))
    }
}

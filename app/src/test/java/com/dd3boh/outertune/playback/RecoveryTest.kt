/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import com.dd3boh.outertune.playback.sabr.SabrFailures
import com.dd3boh.outertune.utils.StepTimedOut
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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

    private fun noNetwork(cause: IOException) =
        HttpDataSource.HttpDataSourceException.createForIOException(cause, spec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)

    @Test
    fun `a load that failed for want of a network is not the route's, and only a refusal changes it then`() {
        val loads = Recovery.LoadFailures()
        val reset = HttpDataSource.HttpDataSourceException(SocketTimeoutException("timeout"), spec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, HttpDataSource.HttpDataSourceException.TYPE_READ)
        // Wi-Fi drops mid-song with nothing to fall back to: the read on the open connection
        // fails, then every try finds no name and no route. Counted, the second of them changed
        // the route, and the walk that followed failed for want of a network.
        assertEquals(Recovery.Decision.RETRY, loads.decide("song", reset, 0))
        for (at in listOf(100L, 1_100L, 3_100L)) {
            assertEquals(Recovery.Decision.IGNORE, loads.decide("song", noNetwork(UnknownHostException("rr1---sn-test.googlevideo.com")), at))
        }
        assertEquals(Recovery.Decision.IGNORE, loads.decide("song", noNetwork(NoRouteToHostException("no route")), 6_100))
        assertEquals(Recovery.Decision.IGNORE, loads.decide("song", noNetwork(ConnectException("Network is unreachable")), 7_100))
        // While the phone says it has none, nothing that fails is the route's either.
        assertEquals(Recovery.Decision.IGNORE, loads.decide("song", reset, 8_000, online = false))
        // A refusal is YouTube's answer: whatever the phone says, the network carried it.
        assertEquals(Recovery.Decision.CHANGE_ROUTE, loads.decide("song", refused(403), 9_000, online = false))
    }

    /** What media3's own policy makes of a failed load: a wait before its next try, or C.TIME_UNSET for none. */
    private fun retryDelay(error: IOException, errorCount: Int = 1): Long {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val event = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LoadEventInfo::class.java) as LoadEventInfo
        val info = LoadErrorHandlingPolicy.LoadErrorInfo(event, MediaLoadData(C.DATA_TYPE_MEDIA), error, errorCount)
        return DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(info)
    }

    /** What the loader holds when the resolver throws [thrown]: anything that is not an IOException, media3 wraps. */
    private fun asTheLoaderHasIt(thrown: Exception): IOException = thrown as? IOException ?: Loader.UnexpectedLoaderException(thrown)

    private val noInternet = PlaybackException("No internet", UnknownHostException("music.youtube.com"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    @Test
    fun `mid-song, a fetch that got no answer keeps the player trying, and reads as before if the buffer runs out`() {
        // A minute and a half in, the song's address forgotten, and the walk finds no network.
        val held = asTheLoaderHasIt(Recovery.resolveFailed(position = 1_500_000, failure = UnknownHostException("music.youtube.com"), error = noInternet))
        assertTrue("media3 tries again on its own, where the song used to be given up", retryDelay(held) != C.TIME_UNSET)
        assertTrue("and again, a few seconds apart", retryDelay(held, errorCount = 6) != C.TIME_UNSET)
        assertEquals("not the route's failure", Recovery.Decision.IGNORE, Recovery.LoadFailures().decide("song", held, 0))
        // The buffer ran out with the network still gone: the wait for it, as at a song's start.
        val stopped = stopped(held)
        val causeCode = generateSequence(stopped.cause) { it.cause }.filterIsInstance<PlaybackException>().firstOrNull()?.errorCode
        assertTrue(NetworkRetryPolicy.waitsForNetwork(false, stopped.errorCode, causeCode, localSong = false))
        assertEquals(Recovery.Kind.PASSING, Recovery.kind(stopped))

        val timeout = StepTimedOut("resolve", 20_000)
        val cut = asTheLoaderHasIt(Recovery.resolveFailed(1_500_000, timeout, PlaybackException("Timeout", timeout, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertTrue("a walk cut by its time, in a lift that keeps the phone online", retryDelay(cut) != C.TIME_UNSET)
        assertEquals(Recovery.Kind.PASSING, Recovery.kind(stopped(cut)))
    }

    @Test
    fun `a song's start, and YouTube's refusal mid-song, end the loading as before`() {
        assertSame(noInternet, Recovery.resolveFailed(0, UnknownHostException("music.youtube.com"), noInternet))
        val refusal = PlaybackException("Sign in to confirm you’re not a bot", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        assertSame(refusal, Recovery.resolveFailed(1_500_000, refusal, refusal))
        assertEquals(C.TIME_UNSET, retryDelay(asTheLoaderHasIt(refusal)))
    }

    // ---- SABR's failures, as its reads throw them (SabrFailures) ----

    private val forgotten = mutableListOf<String>()

    /** A failed read of a song over SABR, as SabrRead hands it to the player. */
    private fun sabr(e: IOException) = SabrFailures.asPlayerSees(e, spec, HttpDataSource.HttpDataSourceException.TYPE_READ) { forgotten += "start" }

    /** How SabrFile hands on a failure of InnerTubeX's, which is no IOException. */
    private fun wrapped(e: Throwable) = IOException("SABR stream failed: ${e.message}", e)

    /** What the player reports when it gives up on such a load: its error code is the source's own. */
    private fun stoppedBy(e: IOException) = stopped(e, (e as? DataSourceException)?.reason ?: PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    @Test
    fun `SABR refused is a refused stream, the route changes at once, the start is forgotten, a stop is tried again quietly`() {
        for (refusal in listOf(
            SabrProtocolException("SABR stream protection requires attestation (status 3, retry=0/unspecified)", SabrFailureKind.ATTESTATION_REQUIRED),
            SabrProtocolException("SABR requested a fresh player response", SabrFailureKind.RELOAD_PLAYER),
            SabrProtocolException("SABR error MEDIA_FORBIDDEN (2)"),
            SabrProtocolException("SABR request failed with HTTP 403", httpStatusCode = 403),
        )) {
            forgotten.clear()
            val thrown = sabr(wrapped(refusal))
            assertEquals(refusal.message, 403, Recovery.status(thrown))
            assertEquals(refusal.message, Recovery.Decision.CHANGE_ROUTE, Recovery.LoadFailures().decide("song", thrown, 0))
            assertEquals(refusal.message, Recovery.Kind.PASSING, Recovery.kind(stoppedBy(thrown)))
            assertEquals("the start is forgotten, so the next open walks again", listOf("start"), forgotten)
        }
    }

    @Test
    fun `a SABR start no longer known is an address gone, and the route changes at once`() {
        val gone = SabrFailures.startGone(spec)
        assertEquals(410, Recovery.status(gone))
        assertEquals(Recovery.Decision.CHANGE_ROUTE, Recovery.LoadFailures().decide("song", gone, 0))
        assertEquals(Recovery.Kind.PASSING, Recovery.kind(stoppedBy(gone)))
    }

    @Test
    fun `a gap or a short stream over SABR is a failed read, tried again, and the route changed at the second`() {
        val loads = Recovery.LoadFailures()
        val failures = listOf(
            0L to IOException("SABR left a gap: byte 5 missing, the next chunk starts at 9"),
            1_000L to IOException("SABR stream ended at byte 10 of 20"),
        )
        for ((at, e) in failures) {
            forgotten.clear()
            val thrown = sabr(e)
            assertTrue(thrown.toString(), thrown is HttpDataSource.HttpDataSourceException)
            assertEquals(Recovery.Kind.PASSING, Recovery.kind(stoppedBy(thrown)))
            assertTrue("the start stays: it is the read that failed", forgotten.isEmpty())
            assertEquals(if (at == 0L) Recovery.Decision.RETRY else Recovery.Decision.CHANGE_ROUTE, loads.decide("song", thrown, at))
        }
    }

    @Test
    fun `SABR's own server failing, or no network, is read as it is for a direct address`() {
        val server = sabr(wrapped(SabrProtocolException("SABR request failed with HTTP 503", httpStatusCode = 503)))
        assertEquals(503, Recovery.status(server))
        assertEquals(Recovery.Decision.RETRY, Recovery.LoadFailures().decide("song", server, 0))
        assertEquals(Recovery.Kind.PASSING, Recovery.kind(stoppedBy(server)))
        assertTrue("its start stays", forgotten.isEmpty())

        val offline = sabr(UnknownHostException("rr1---sn-test.googlevideo.com"))
        assertEquals(Recovery.Decision.IGNORE, Recovery.LoadFailures().decide("song", offline, 0))
        assertEquals(Recovery.Kind.PASSING, Recovery.kind(stoppedBy(offline)))
        val timeout = sabr(SocketTimeoutException("timeout"))
        assertEquals(Recovery.Decision.RETRY, Recovery.LoadFailures().decide("song", timeout, 0))
        assertEquals(Recovery.Kind.PASSING, Recovery.kind(stoppedBy(timeout)))
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun `no fault of SABR's throws out a good cached copy, and an interrupted read stays an interruption`() {
        val faults = listOf(
            wrapped(SabrProtocolException("refused", SabrFailureKind.ATTESTATION_REQUIRED)),
            IOException("SABR left a gap"),
            UnknownHostException("rr1---sn-test.googlevideo.com"),
            wrapped(SabrProtocolException("server", httpStatusCode = 503)),
        )
        for (fault in faults) assertTrue(fault.toString(), BadCachedCopy.isNetwork(sabr(fault)))
        assertTrue(BadCachedCopy.isNetwork(SabrFailures.startGone(spec)))
        // A seek or a stop interrupts a read on purpose: media3 knows what it did.
        val interrupted = InterruptedIOException("SABR read interrupted")
        assertSame(interrupted, sabr(interrupted))
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a failure says of itself reaches the log, the player's error screen and the crash report
 * without the phone's address.
 *
 * Android words a connection that failed as "failed to connect to music.youtube.com/203.0.113.57
 * (port 443) from /192.0.2.44 (port 51234) after 10000ms". libcore's
 * IoBridge.createMessageForException asks the socket where it is bound and writes that in, for a
 * ConnectException and for a SocketTimeoutException alike, and over IPv6 where it is bound is the
 * phone's public address. OkHttp's ConnectPlan puts "Failed to connect to
 * music.youtube.com/203.0.113.57:443" around a refusal, Media3 takes that whole line as the message
 * of its own exception, and a trace prints all three.
 *
 * The messages here are made up in those words, no phone wrote them. The addresses are ones set
 * aside for examples, with 192.0.2.44 and 2001:db8::1234 standing for the phone's.
 */
class ErrorTextTest {

    private val host = "music.youtube.com"
    private val cdn = "rr4---sn-25ge7nzr.googlevideo.com"

    /** Refused over IPv4, in the platform's words. */
    private val refused4 = "failed to connect to $host/203.0.113.57 (port 443) from /192.0.2.44 (port 51234) after 10000ms: " +
        "isConnected failed: ECONNREFUSED (Connection refused)"

    /** No answer over IPv6. A connection that timed out gives no reason after the wait. */
    private val timedOut6 = "failed to connect to $host/2001:db8:4007:80e::200e (port 443) from /2001:db8::1234 (port 51234) after 10000ms"

    /** Pieces of those addresses and ports. A line that keeps one of them has kept too much. */
    private val neverSaid = listOf("203.0.113", "192.0.2", "2001", "db8", "200e", "1234", "443", "port")

    @Test
    fun `a refused connection keeps its host, its wait and its reason, and loses both addresses and both ports`() {
        val line = ErrorText.withoutAddresses("java.net.ConnectException: $refused4")
        assertEquals(
            "java.net.ConnectException: failed to connect to $host/IPv4 from /IPv4 after 10000ms: " +
                "isConnected failed: ECONNREFUSED (Connection refused)",
            line,
        )
        neverSaid.forEach { assertFalse("the line carries $it", it in line) }
    }

    @Test
    fun `over IPv6, where the phone's address is a public one, they go the same way`() {
        val line = ErrorText.withoutAddresses("java.net.SocketTimeoutException: $timedOut6")
        assertEquals("java.net.SocketTimeoutException: failed to connect to $host/IPv6 from /IPv6 after 10000ms", line)
        neverSaid.forEach { assertFalse("the line carries $it", it in line) }
    }

    @Test
    fun `what OkHttp puts around a refusal loses them too, however the platform joins the two`() {
        assertEquals("Failed to connect to $host/IPv4", ErrorText.withoutAddresses("Failed to connect to $host/203.0.113.57:443"))
        // An IPv6 address and its port: the SDK 35 sources put the address in brackets, the SDK 30
        // ones run the two together, and both are on phones the app runs on.
        assertEquals("Failed to connect to $host/IPv6", ErrorText.withoutAddresses("Failed to connect to $host/[2001:db8:4007:80e::200e]:443"))
        assertEquals("Failed to connect to $host/IPv6", ErrorText.withoutAddresses("Failed to connect to $host/2001:db8:4007:80e::200e:443"))
        assertEquals("Failed to connect to $host/IPv6", ErrorText.withoutAddresses("Failed to connect to $host/2001:db8::1234:51234"))
        // Through a proxy set by its address, it is the proxy that could not be reached, and it has no name.
        assertEquals("Failed to connect to /IPv4", ErrorText.withoutAddresses("Failed to connect to /192.0.2.44:8080"))
    }

    @Test
    fun `an address is taken out however it is written`() {
        // Whole, with nothing left out of it, alone and with a port run on.
        assertEquals("$host/IPv6", ErrorText.withoutAddresses("$host/2001:db8:85a3:8d3:1319:8a2e:370:7348"))
        assertEquals("$host/IPv6", ErrorText.withoutAddresses("$host/2001:db8:85a3:8d3:1319:8a2e:370:7348:443"))
        // On the link only, with the interface it is on.
        assertEquals(
            "failed to connect to /IPv6 from /IPv6 after 5000ms",
            ErrorText.withoutAddresses("failed to connect to /fe80::db8:1ff:fe00:57%wlan0 (port 8009) from /fe80::db8:2ff:fe00:1234%wlan0 (port 40112) after 5000ms"),
        )
        // An IPv4 address inside an IPv6 one.
        assertEquals("$host/IPv6", ErrorText.withoutAddresses("$host/::ffff:203.0.113.57"))
        assertEquals("$host/IPv6", ErrorText.withoutAddresses("$host/64:ff9b::203.0.113.57"))
        // As a url's host, or as what one of its parameters says.
        assertEquals("http://IPv4/generate_204", ErrorText.withoutAddresses("http://192.0.2.44:8080/generate_204"))
        assertEquals("http://IPv6/generate_204", ErrorText.withoutAddresses("http://[2001:db8::1234]:8080/generate_204"))
        assertEquals("https://$cdn/videoplayback?ip=IPv4&itag=251", ErrorText.withoutAddresses("https://$cdn/videoplayback?ip=203.0.113.57&itag=251"))
        // At the end of a sentence, and before what went wrong with it.
        assertEquals("No route to IPv6.", ErrorText.withoutAddresses("No route to 2001:db8::1234."))
        assertEquals("IPv6: Connection refused", ErrorText.withoutAddresses("2001:db8::1234: Connection refused"))
        assertEquals("IPv4: Connection refused", ErrorText.withoutAddresses("192.0.2.44: Connection refused"))
    }

    @Test
    fun `the address a stream url was issued to is taken out of a url that is quoted, whichever family it is`() {
        // Ktor names the url it could not parse, and a stream url carries the listener's address
        // as its ip parameter, an IPv6 one with %3A for each colon.
        assertEquals(
            "io.ktor.http.URLParserException: Fail to parse url: https://$cdn/videoplayback?expire=1759876543&ip=IPv6&itag=251",
            ErrorText.withoutAddresses(
                "io.ktor.http.URLParserException: Fail to parse url: " +
                    "https://$cdn/videoplayback?expire=1759876543&ip=2001%3Adb8%3A85a3%3A%3A8a2e%3A370%3A7334&itag=251"
            ),
        )
        // In a signature cipher the url is a parameter itself, and everything in it is written over once more.
        assertEquals(
            "url=https%3A%2F%2F$cdn%2Fvideoplayback%3Fip%3DIPv6%26itag%3D251",
            ErrorText.withoutAddresses("url=https%3A%2F%2F$cdn%2Fvideoplayback%3Fip%3D2001%253Adb8%253A85a3%253A%253A8a2e%253A370%253A7334%26itag%3D251"),
        )
        assertEquals(
            "url=https%3A%2F%2F$cdn%2Fvideoplayback%3Fip%3DIPv4%26itag%3D251",
            ErrorText.withoutAddresses("url=https%3A%2F%2F$cdn%2Fvideoplayback%3Fip%3D203.0.113.57%26itag%3D251"),
        )
        // With a port behind it.
        assertEquals("https%3A%2F%2FIPv4%2Fgenerate_204", ErrorText.withoutAddresses("https%3A%2F%2F192.0.2.44%3A8080%2Fgenerate_204"))
        // What a url writes with a % that is not an address stays: a time, and a name with a space in it.
        assertEquals("at=12%3A34%3A56&title=Caf%C3%A9%20del%20Mar", ErrorText.withoutAddresses("at=12%3A34%3A56&title=Caf%C3%A9%20del%20Mar"))
    }

    @Test
    fun `a socket that was never given an address still says so`() {
        // A socket that is not bound yet reports :: and port 0. That is nobody's address, and it
        // says how far the attempt got.
        assertEquals(
            "failed to connect to $host/IPv6 from /:: after 10000ms: connect failed: ENETUNREACH (Network is unreachable)",
            ErrorText.withoutAddresses(
                "failed to connect to $host/2001:db8:4007:80e::200e (port 443) from /:: (port 0) after 10000ms: " +
                    "connect failed: ENETUNREACH (Network is unreachable)"
            ),
        )
    }

    @Test
    fun `what is not an address is left as it is`() {
        listOf(
            "java.net.UnknownHostException: Unable to resolve host \"$host\": No address associated with hostname",
            "javax.net.ssl.SSLException: Read error: ssl=0xb400007a5c0e8a18: I/O error during system call, Connection reset by peer",
            "java.net.SocketTimeoutException: timeout",
            "androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException: Response code: 403",
            "io.ktor.client.network.sockets.ConnectTimeoutException: Connect timeout has expired " +
                "[url=https://$host/youtubei/v1/player?prettyPrint=false, connect_timeout=15000 ms]",
            "java.io.IOException: unexpected end of stream on https://$cdn/...",
            "kotlinx.coroutines.JobCancellationException: StandaloneCoroutine was cancelled; job=StandaloneCoroutine{Cancelling}@a1b2c3d",
            "java.lang.IllegalStateException: SongEntity::localToggleLike has no receiver",
            "Caused by: android.system.ErrnoException: isConnected failed: ECONNREFUSED (Connection refused)",
            "\tat com.dd3boh.outertune.utils.YTPlayerUtils.streamStatus(YTPlayerUtils.kt:796)",
            "\tat libcore.io.IoBridge.isConnected(IoBridge.java:347)",
            "\tat w6.b.a(SourceFile:12)",
            "\tat a.b.c(Unknown Source:14)",
            "\t... 21 more",
            "Time: 2026-10-08 12:34:56 +0200",
            "ExoPlayerLib/1.8.0, okhttp/5.1.0",
            "stream chain: VISIONOS OK, HEAD 403; IOS OK, HEAD failed",
        ).forEach { assertEquals(it, ErrorText.withoutAddresses(it)) }
    }

    /**
     * A stream that could not be reached, top to bottom, as the error screen has it. The frames
     * and their line numbers are made up as well.
     */
    private fun streamRefused(okhttpSays: String, platformSays: String) = listOf(
        "androidx.media3.exoplayer.ExoPlaybackException: Source error",
        "\tat androidx.media3.exoplayer.ExoPlayerImplInternal.handleIoException(ExoPlayerImplInternal.java:864)",
        "\tat androidx.media3.exoplayer.ExoPlayerImplInternal.handleMessage(ExoPlayerImplInternal.java:840)",
        "\tat android.os.Handler.dispatchMessage(Handler.java:103)",
        "\tat android.os.Looper.loopOnce(Looper.java:232)",
        "\tat android.os.Looper.loop(Looper.java:317)",
        "\tat android.os.HandlerThread.run(HandlerThread.java:85)",
        "Caused by: androidx.media3.datasource.HttpDataSource\$HttpDataSourceException: java.net.ConnectException: Failed to connect to $okhttpSays",
        "\tat androidx.media3.datasource.okhttp.OkHttpDataSource.open(OkHttpDataSource.java:321)",
        "\tat androidx.media3.datasource.cache.CacheDataSource.openNextSource(CacheDataSource.java:802)",
        "\tat androidx.media3.exoplayer.upstream.Loader\$LoadTask.run(Loader.java:446)",
        "\tat java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1156)",
        "\tat java.util.concurrent.ThreadPoolExecutor\$Worker.run(ThreadPoolExecutor.java:651)",
        "\tat java.lang.Thread.run(Thread.java:1119)",
        "Caused by: java.net.ConnectException: Failed to connect to $okhttpSays",
        "\tat okhttp3.internal.connection.ConnectPlan.connectSocket(ConnectPlan.kt:260)",
        "\tat okhttp3.internal.connection.ConnectPlan.connectTcp(ConnectPlan.kt:128)",
        "\tat w6.b.a(SourceFile:12)",
        "\t... 9 more",
        "Caused by: java.net.ConnectException: failed to connect to $platformSays after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)",
        "\tat libcore.io.IoBridge.isConnected(IoBridge.java:347)",
        "\tat libcore.io.IoBridge.connectErrno(IoBridge.java:237)",
        "\tat libcore.io.IoBridge.connect(IoBridge.java:179)",
        "\tat java.net.PlainSocketImpl.socketConnect(PlainSocketImpl.java:142)",
        "\tat java.net.Socket.connect(Socket.java:646)",
        "\tat okhttp3.internal.platform.Platform.connectSocket(Platform.kt:128)",
        "\tat okhttp3.internal.connection.ConnectPlan.connectSocket(ConnectPlan.kt:258)",
        "\t... 12 more",
        "Caused by: android.system.ErrnoException: isConnected failed: ECONNREFUSED (Connection refused)",
        "\tat libcore.io.IoBridge.isConnected(IoBridge.java:334)",
        "\t... 18 more",
    ).joinToString("\n", postfix = "\n")

    @Test
    fun `a whole trace loses the addresses and nothing else`() {
        assertEquals(
            streamRefused("$cdn/IPv4", "$cdn/IPv4 from /IPv4"),
            ErrorText.withoutAddresses(streamRefused("$cdn/203.0.113.57:443", "$cdn/203.0.113.57 (port 443) from /192.0.2.44 (port 51234)")),
        )
        assertEquals(
            streamRefused("$cdn/IPv6", "$cdn/IPv6 from /IPv6"),
            ErrorText.withoutAddresses(
                streamRefused("$cdn/[2001:db8:4007:80e::200e]:443", "$cdn/2001:db8:4007:80e::200e (port 443) from /2001:db8::1234 (port 51234)")
            ),
        )
    }

    /**
     * The same failure as exceptions, the way Media3 hands it to the player: its own takes the
     * whole line of the one under it as its message. With a second attempt hung on it, over the
     * other family, so that the trace has every kind of line a trace can have.
     */
    private fun unreachable(): IOException {
        val refused = ConnectException("Failed to connect to $host/203.0.113.57:443").apply { initCause(ConnectException(refused4)) }
        refused.addSuppressed(SocketTimeoutException(timedOut6))
        return IOException(refused)
    }

    @Test
    fun `the trace of a failure comes through with every frame as it was`() {
        val failure = unreachable()
        val was = failure.stackTraceToString().lines()
        val said = ErrorText.of(failure).lines()
        assertEquals(was.size, said.size)
        val frames = was.indices.filter { was[it].trimStart().startsWith("at ") || was[it].trimStart().startsWith("... ") }
        assertTrue("the trace has only ${frames.size} frames", frames.size > 10)
        frames.forEach { assertEquals(was[it], said[it]) }
        assertEquals(
            listOf(
                "java.io.IOException: java.net.ConnectException: Failed to connect to $host/IPv4",
                "Caused by: java.net.ConnectException: Failed to connect to $host/IPv4",
                "\tSuppressed: java.net.SocketTimeoutException: failed to connect to $host/IPv6 from /IPv6 after 10000ms",
                "Caused by: java.net.ConnectException: failed to connect to $host/IPv4 from /IPv4 after 10000ms: " +
                    "isConnected failed: ECONNREFUSED (Connection refused)",
            ),
            said.filterIndexed { index, line -> index !in frames && line.isNotEmpty() },
        )
    }

    @Test
    fun `what has had its addresses taken out comes back as it is`() {
        // The last two only read as an address once a port is out of the middle of them.
        listOf(
            refused4, timedOut6, "from /:: (port 0)", "$host/[2001:db8::1234]:443", "$host/::ffff:203.0.113.57",
            unreachable().stackTraceToString(), "ip=2001%3Adb8%3A%3A1234&itag=251", "192.0.2 (port 443).44", ":: (port 443)1234",
        ).map { ErrorText.withoutAddresses(it) }.forEach { assertEquals(it, ErrorText.withoutAddresses(it)) }
    }

    // The player's error screen: the line it shows, the trace under it, and what the button copies.

    @Test
    fun `the error screen's line is the player's own and the first reason under it, without the addresses`() {
        // A stream that could not be reached. Media3's message is the whole line of OkHttp's.
        val refused = PlaybackException("Source error", unreachable(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(
            "Source error (2001): java.net.ConnectException: Failed to connect to $host/IPv4",
            ErrorText.playerLine(refused, "Unknown error"),
        )
        // One that timed out. OkHttp puts nothing around a timeout, so the platform's words, the
        // phone's address among them, used to be the line on the screen itself.
        val timedOut = PlaybackException("Source error", IOException(SocketTimeoutException(timedOut6)), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        val line = ErrorText.playerLine(timedOut, "Unknown error")
        assertEquals("Source error (2002): java.net.SocketTimeoutException: failed to connect to $host/IPv6 from /IPv6 after 10000ms", line)
        neverSaid.forEach { assertFalse("the line carries $it", it in line) }
    }

    @Test
    fun `the line falls back to the reason one further down, then to what it is given`() {
        val further = PlaybackException("Source error", IllegalStateException(null, ConnectException(refused4)), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(
            "Source error (2000): failed to connect to $host/IPv4 from /IPv4 after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)",
            ErrorText.playerLine(further, "Unknown error"),
        )
        val bare = PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals("Source error (2000): Unknown error", ErrorText.playerLine(bare, "Unknown error"))
        assertEquals("Source error (2000): ", ErrorText.playerLine(bare))
    }

    private val main = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `the error screen says nothing of the error but through these`() {
        val screen = File(main, "ui/player/ThumbnailPlaybackError.kt").readText()
        assertTrue("the line on the screen was not found", "ErrorText.playerLine(error, stringResource(R.string.error_unknown))" in screen)
        assertTrue("the line of the copied report was not found", "ErrorText.playerLine(error)" in screen)
        assertTrue("the trace was not found", "ErrorText.of(error)" in screen)
        // Each of these put what the error says of itself on the screen or the clipboard as it was.
        assertFalse("a message is read as it is", ".message" in screen)
        assertFalse("a trace is read as it is", "stackTraceToString" in screen)
    }

    // The crash report.

    @Test
    fun `a crash is kept without the addresses`() {
        val crashLog = File(main, "utils/CrashLog.kt").readText()
        assertTrue("the trace was not found", "append(ErrorText.of(throwable))" in crashLog)
        assertTrue("what the dialog reads is not what read() cleans", "?.let(::withoutAddresses)" in crashLog)
    }

    @Test
    fun `a crash kept by an older build is shown without them, under the header it has`() {
        // The header is the app's own and is not given to ErrorText: a version reads as an address.
        val header = "InterTune 0.10.9.5 (10905, core release)\nAndroid 16 (API 36), Google Pixel 9\nABIs: arm64-v8a\n" +
            "Time: 2026-10-08 12:34:56 +0200\nThread: DefaultDispatcher-worker-3\n\n"
        val frame = "\n\tat libcore.io.IoBridge.connectErrno(IoBridge.java:235)\n"
        val shown = CrashLog.withoutAddresses(header + "java.net.SocketTimeoutException: $timedOut6" + frame)
        assertEquals(header + "java.net.SocketTimeoutException: failed to connect to $host/IPv6 from /IPv6 after 10000ms" + frame, shown)
        // One this build kept has none left, and comes back as it is.
        assertEquals(shown, CrashLog.withoutAddresses(shown))
        // Something that is not a report at all is not guessed at.
        assertEquals("0.10.9.5", CrashLog.withoutAddresses("0.10.9.5"))
    }

    // The log.

    @Test
    fun `reportException writes the trace it always did, without the addresses`() {
        val failure = unreachable()
        val written = ByteArrayOutputStream()
        val err = System.err
        System.setErr(PrintStream(written, true))
        try {
            reportException(failure)
        } finally {
            System.setErr(err)
        }
        assertTrue("the trace was not written", ErrorText.of(failure) in written.toString())
        // Not the frames, whose line numbers are anybody's.
        val messages = written.toString().lines().filterNot { it.trimStart().startsWith("at ") }.joinToString("\n")
        neverSaid.forEach { assertFalse("the log carries $it", it in messages) }
    }

    @Test
    fun `nothing writes a trace but through here, save what never asks a server for anything`() {
        // These play and read what is on the phone. No connection is made, so no address is named.
        val onThePhone = setOf(
            "playback/LevelTap.kt",
            "utils/scanners/FFmpegScanner.kt",
            "utils/scanners/LocalMediaScanner.kt",
            "utils/scanners/TagLibScanner.kt",
        )
        val writesTrace = Regex("""\b(?:printStackTrace|stackTraceToString|getStackTraceString)\s*\(""")
        val found = File("src/main/java").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
            .filter { file -> file.readLines().any { writesTrace.containsMatchIn(code(it)) } }
            .map { it.invariantSeparatorsPath.substringAfter("src/main/java/com/dd3boh/outertune/") }
            .toSet()
        assertEquals(onThePhone + "utils/ErrorText.kt", found)
    }

    /** A line without what a comment says on it. */
    private fun code(line: String) = line.trimStart().let { if (it.startsWith("*") || it.startsWith("/*")) "" else it.substringBefore("//") }

    // The lines of the log that are handed a failure themselves, with Log.w(TAG, "...", failure).

    @Test
    fun `a failure handed to the log prints as it would, without the addresses`() {
        val failure = unreachable()
        val handed = ErrorText.forLog(failure)
        // Log asks what it is handed to print itself, to a writer.
        assertEquals(ErrorText.of(failure), handed.stackTraceToString())
        val stream = ByteArrayOutputStream()
        handed.printStackTrace(PrintStream(stream, true))
        assertEquals(ErrorText.of(failure), stream.toString())
        assertEquals("java.io.IOException: java.net.ConnectException: Failed to connect to $host/IPv4", handed.toString())
    }

    @Test
    fun `the log still finds what it looks for under a failure handed to it`() {
        // Log prints no trace for a failure with an UnknownHostException somewhere under it, so
        // that a phone which is only offline does not fill the log. It walks the causes to tell.
        val offline = IOException(UnknownHostException("Unable to resolve host \"$host\": No address associated with hostname"))
        val handed = ErrorText.forLog(offline)
        assertTrue(handed !== offline)
        assertSame(offline, handed.cause)
        assertTrue(generateSequence(handed) { it.cause }.any { it is UnknownHostException })
    }

    /**
     * The lines that log a request that failed, each by its file and some of its words. A failure
     * that comes from the phone itself (the database, a file, the widget) names no address, and
     * the lines for those hand it to Log as they did.
     */
    private val failedRequests = listOf(
        "recognition/ShazamClient.kt" to "Recognition request failed",
        "recognition/RecognitionEngine.kt" to "Listening failed",
        "recognition/RecognitionEngine.kt" to "Search for '\$query' failed",
        "recognition/RecognitionEngine.kt" to "Mashup search for '\$query' failed",
        "recognition/RecognitionEngine.kt" to "Could not push",
        "migration/LibraryImport.kt" to "YouTube could not be reached",
        "utils/BackgroundChecks.kt" to "Update check failed",
        "utils/BackgroundChecks.kt" to "Poll check failed",
        "utils/UpdateInstaller.kt" to "Update download or install failed",
        "utils/potoken/PoTokenGenerator.kt" to "Failed to obtain poToken, retrying",
        "utils/ActiveCount.kt" to "Ping could not be sent",
        "utils/PollChecker.kt" to "could not be sent, recorded locally anyway",
        "utils/SyncUtils.kt" to "Could not read playlist \$browseId",
        "utils/SyncUtils.kt" to "Could not read \$browseId",
        "utils/LastFmSimilar.kt" to "Last.fm similar failed",
        "playback/MusicService.kt" to "the new stream could not be fetched",
        "ui/dialog/CreatePlaylistDialog.kt" to "Could not create the playlist on YouTube Music",
    )

    @Test
    fun `a request that failed is logged through ErrorText`() {
        var lines = 0
        failedRequests.forEach { (path, words) ->
            val calls = logCalls(File(main, path).readText(), words)
            assertTrue("no line of $path says: $words", calls.isNotEmpty())
            calls.forEach { assertTrue("$path logs the failure as it is: $it", "ErrorText" in it) }
            lines += calls.size
        }
        // The search of the recognition engine fails in two places with the same words.
        assertEquals(failedRequests.size + 1, lines)
    }

    @Test
    fun `what Media3 logs of its own accord goes through ErrorText as well`() {
        // "ExoPlayerImplInternal: Playback error" and the whole trace under it are written by
        // Media3 itself, for every stream that could not be reached, and pass none of the above.
        // Its logger is replaced by one that writes what its own does, without the addresses.
        val logger = File(main, "playback/PlayerLogger.kt").readText()
        assertTrue(
            "what is written is not what ErrorText makes of it",
            "ErrorText.withoutAddresses(Media3Log.appendThrowableString(message, throwable))" in logger,
        )
        listOf("d", "i", "w", "e").forEach { level ->
            assertTrue("Log.$level is given something else", "Log.$level(tag, written(message, throwable))" in logger)
        }
        assertTrue("the logger is not Media3's", "Media3Log.setLogger(this)" in logger)
        assertTrue("the logger is never installed", "PlayerLogger.install()" in File(main, "App.kt").readText())
    }

    private val logCall = Regex("""\bLog\.[vdiwe]\s*\(""")

    /** Every call to Log in [source] that says [words], whole. A call often runs over several lines. */
    private fun logCalls(source: String, words: String): List<String> =
        logCall.findAll(source).map { source.substring(it.range.first, closingParen(source, it.range.last) + 1) }
            .filter { words in it }.toList()

    /** The index of the parenthesis closing the one at [open]. Those in a message come in pairs, so they count like any other. */
    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return text.lastIndex
    }
}

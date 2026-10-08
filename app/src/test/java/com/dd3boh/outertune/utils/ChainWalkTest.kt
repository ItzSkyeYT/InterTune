/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.StreamOrder.Memory
import com.zionhuang.innertube.AddressPolicy
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ResponseContext
import com.zionhuang.innertube.models.Thumbnails
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

/**
 * The player's own walk of the chain, with answers written here in place of YouTube's.
 *
 * StreamOrderTest says which client is asked first. This runs the walk itself, the code a song
 * goes through, for the cases that cannot be had on a phone on demand: the client that served the
 * last song refusing this one, no network, an app without a visitorData. Signed out throughout:
 * the account's client asks for a signature timestamp, which is a request to YouTube.
 */
class ChainWalkTest {
    /** Synthetic, in the shape YouTube issues them. */
    private val visitor = "CgtURVNUVklTSVRPUiiA98TVBjIECgJGUg%3D%3D"
    private val newVisitor = "CgtORVdWSVNJVE9SWSiA98TVBjIECgJGUg%3D%3D"

    private val bot = "Sign in to confirm you’re not a bot"

    /** The main client's refusal as a phone set to French gets it, which the throttle does not read. */
    private val botInFrench = "Connectez-vous pour confirmer que vous n’êtes pas un robot"

    private val stored = mutableListOf<String>()
    private val adopted = mutableListOf<String>()

    /** Never asked anything that matters here: a framework object whose methods all answer false or null. */
    private val connectivity: ConnectivityManager = run {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, ConnectivityManager::class.java) as ConnectivityManager
    }

    @Before
    fun fresh() {
        YouTube.cookie = null
        YouTube.visitorData = visitor
        YTPlayerUtils.streamMemory = Memory()
        YTPlayerUtils.onStreamMemoryChanged = { stored += it }
        YTPlayerUtils.onVisitorDataFound = { adopted += it }
    }

    @After
    fun leaveNothingBehind() {
        YouTube.visitorData = null
        YTPlayerUtils.streamMemory = Memory()
        YTPlayerUtils.onStreamMemoryChanged = null
        YTPlayerUtils.onVisitorDataFound = null
        Throttle.clear("the test is over")
    }

    /** What the clients say, each in turn and the last one again after that, and what each one's url gets. */
    private class Script(
        private val says: Map<String, List<Any>>,
        private val heads: Map<String, Int?> = emptyMap(),
    ) : YTPlayerUtils.Wire {
        val askedOf = mutableListOf<String>()
        private val turn = mutableMapOf<String, Int>()

        override suspend fun player(
            videoId: String,
            playlistId: String?,
            client: YouTubeClient,
            signatureTimestamp: Int?,
            webPlayerPot: String?,
            hlOverride: String?,
            policy: AddressPolicy?,
            asNewVisitor: Boolean,
        ): Result<PlayerResponse> {
            val name = if (asNewVisitor) "${client.clientName} as a new visitor" else client.clientName
            askedOf += name
            val lines = says[name] ?: error("$name was not expected to be asked")
            val at = turn.getOrDefault(name, 0)
            turn[name] = at + 1
            return when (val said = lines[minOf(at, lines.lastIndex)]) {
                is Throwable -> Result.failure(said)
                else -> Result.success(said as PlayerResponse)
            }
        }

        override fun head(url: String): Int? {
            val client = url.substringAfter("://").substringBefore('.')
            return if (client in heads) heads[client] else 200
        }
    }

    private fun refused(reason: String, status: String = "LOGIN_REQUIRED", carrying: String? = visitor) = PlayerResponse(
        responseContext = ResponseContext(visitorData = carrying, serviceTrackingParams = null),
        playabilityStatus = PlayerResponse.PlayabilityStatus(status, reason),
        playerConfig = null,
        streamingData = null,
        videoDetails = null,
        playbackTracking = null,
    )

    /** A playable answer whose stream url names [client], so [Script.head] knows whose it is. */
    private fun playable(client: String, loudness: Double, seconds: String) = PlayerResponse(
        responseContext = ResponseContext(visitorData = visitor, serviceTrackingParams = null),
        playabilityStatus = PlayerResponse.PlayabilityStatus("OK", null),
        playerConfig = PlayerResponse.PlayerConfig(PlayerResponse.PlayerConfig.AudioConfig(loudnessDb = loudness, perceptualLoudnessDb = null)),
        streamingData = PlayerResponse.StreamingData(
            formats = null,
            adaptiveFormats = listOf(
                PlayerResponse.StreamingData.Format(
                    itag = 251, url = "https://$client.example/videoplayback?itag=251", mimeType = "audio/webm; codecs=\"opus\"",
                    bitrate = 140_000, width = null, height = null, contentLength = 3_000_000, quality = "tiny", fps = null,
                    qualityLabel = null, averageBitrate = null, audioQuality = null, approxDurationMs = null,
                    audioSampleRate = 48_000, audioChannels = 2, loudnessDb = null, lastModified = null, signatureCipher = null,
                )
            ),
            expiresInSeconds = 21_540,
        ),
        videoDetails = PlayerResponse.VideoDetails(
            videoId = "TESTVIDEO01", title = "A song", author = "Somebody", channelId = "UCtest", lengthSeconds = seconds,
            musicVideoType = null, viewCount = "1", thumbnail = Thumbnails(emptyList()),
        ),
        playbackTracking = null,
    )

    private fun walk(script: Script) = runBlocking {
        YTPlayerUtils.playerResponseForPlayback("TESTVIDEO01", null, AudioQuality.HIGH, connectivity, script)
    }

    private fun remembering(worked: String, vararg refused: String) {
        val now = System.currentTimeMillis()
        YTPlayerUtils.streamMemory = Memory(worked, refused.associateWith { now - 60_000 })
    }

    /** 8 Oct 2026 as every phone saw it. */
    private val today = mapOf(
        "ANDROID_VR" to listOf(refused(bot)),
        "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
    )

    @Test
    fun `with nothing remembered the chain is walked as written, and what it found is kept`() {
        val script = Script(today)
        val data = walk(script).getOrThrow()

        assertEquals(listOf("ANDROID_VR", "VISIONOS"), script.askedOf)
        assertEquals("ANDROID_VR LOGIN_REQUIRED, VISIONOS OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        assertTrue(data.streamUrl.startsWith("https://VISIONOS.example/"))
        assertTrue(data.validated)
        assertEquals("VISIONOS", YTPlayerUtils.streamMemory.worked)
        assertEquals(setOf("ANDROID_VR"), YTPlayerUtils.streamMemory.refusedAt.keys)
        assertEquals(1, stored.size)
        assertEquals(YTPlayerUtils.streamMemory, StreamOrder.decode(stored.single()))
    }

    @Test
    fun `the next song is asked of the client that served, and of nobody else`() {
        walk(Script(today)).getOrThrow()
        stored.clear()
        val script = Script(today)
        val data = walk(script).getOrThrow()

        assertEquals(listOf("VISIONOS"), script.askedOf)
        assertEquals("VISIONOS OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        assertEquals("VISIONOS", YTPlayerUtils.lastStreamClient)
        // Everything the song needs is in the one answer: the main client was never asked.
        assertEquals(5.0, data.audioConfig?.effectiveLoudnessDb)
        assertEquals("200", data.videoDetails?.lengthSeconds)
        assertNull(data.playbackTracking)
        assertEquals("nothing changed, so nothing is stored", emptyList<String>(), stored)
    }

    @Test
    fun `when the client that served is refused the others are asked as written, and the one that serves is kept`() {
        remembering("VISIONOS", "ANDROID_VR")
        // The day it turns round: VISIONOS's url fails its check and ANDROID_VR plays again.
        val script = Script(
            says = mapOf(
                "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
                "ANDROID_VR" to listOf(playable("ANDROID_VR", loudness = 3.0, seconds = "201")),
            ),
            heads = mapOf("VISIONOS" to 403),
        )
        val data = walk(script).getOrThrow()

        assertEquals(listOf("VISIONOS", "ANDROID_VR"), script.askedOf)
        assertEquals("VISIONOS OK, HEAD 403, ANDROID_VR OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        assertTrue(data.streamUrl.startsWith("https://ANDROID_VR.example/"))
        // The main client was asked and its answer carries them, so its numbers are the ones used.
        assertEquals(3.0, data.audioConfig?.effectiveLoudnessDb)
        assertEquals("201", data.videoDetails?.lengthSeconds)
        assertEquals("ANDROID_VR", YTPlayerUtils.streamMemory.worked)
        assertEquals(setOf("VISIONOS"), YTPlayerUtils.streamMemory.refusedAt.keys)
        // And the song after it starts with ANDROID_VR, which is the chain as written.
        val next = Script(mapOf("ANDROID_VR" to listOf(playable("ANDROID_VR", loudness = 3.0, seconds = "201"))))
        walk(next).getOrThrow()
        assertEquals(listOf("ANDROID_VR"), next.askedOf)
    }

    @Test
    fun `a song no client can play is asked of every one of them, and changes nothing about who is first`() {
        remembering("VISIONOS", "ANDROID_VR")
        val gone = refused("This video is not available", status = "UNPLAYABLE")
        val script = Script(mapOf("VISIONOS" to listOf(gone), "ANDROID_VR" to listOf(refused(botInFrench)), "IOS" to listOf(gone)))
        val result = walk(script)

        assertTrue(result.isFailure)
        assertEquals(listOf("VISIONOS", "ANDROID_VR", "IOS"), script.askedOf)
        assertEquals("VISIONOS UNPLAYABLE, ANDROID_VR LOGIN_REQUIRED, IOS UNPLAYABLE", YTPlayerUtils.lastStreamTrail)
        assertEquals("This video is not available", result.exceptionOrNull()?.message)
        assertEquals("VISIONOS", YTPlayerUtils.streamMemory.worked)
        val next = Script(today)
        walk(next).getOrThrow()
        assertEquals(listOf("VISIONOS"), next.askedOf)
    }

    @Test
    fun `offline the first request fails the song at once, as the main client's always did`() {
        remembering("VISIONOS", "ANDROID_VR")
        val before = YTPlayerUtils.streamMemory
        val noNetwork = UnknownHostException("Unable to resolve host")
        val result = walk(Script(mapOf("VISIONOS" to listOf(noNetwork))))

        assertSame(noNetwork, result.exceptionOrNull())
        assertEquals("VISIONOS no answer", YTPlayerUtils.lastStreamTrail)
        assertEquals("a client the network never reached is not remembered as refused", before, YTPlayerUtils.streamMemory)
        // With nothing remembered the first request is the main client's, and it ends the same way.
        YTPlayerUtils.streamMemory = Memory()
        val script = Script(mapOf("ANDROID_VR" to listOf(noNetwork)))
        assertSame(noNetwork, walk(script).exceptionOrNull())
        assertEquals(listOf("ANDROID_VR"), script.askedOf)
    }

    @Test
    fun `a failure YouTube answered with does not end the song, the rest of the chain is asked`() {
        remembering("VISIONOS", "ANDROID_VR")
        // Standing for an HTTP status or a body the app cannot read: not the network's doing.
        val script = Script(mapOf("VISIONOS" to listOf(IllegalStateException("400")), "ANDROID_VR" to listOf(playable("ANDROID_VR", 3.0, "201"))))
        val data = walk(script).getOrThrow()

        assertEquals(listOf("VISIONOS", "ANDROID_VR"), script.askedOf)
        assertEquals("VISIONOS no answer, ANDROID_VR OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        assertTrue(data.streamUrl.startsWith("https://ANDROID_VR.example/"))
        assertEquals("ANDROID_VR", YTPlayerUtils.streamMemory.worked)
        assertEquals(setOf("VISIONOS"), YTPlayerUtils.streamMemory.refusedAt.keys)
    }

    @Test
    fun `an app without a visitorData asks the main client first whatever it remembers, and takes the one its answer carries`() {
        remembering("VISIONOS", "ANDROID_VR")
        YouTube.visitorData = null
        val script = Script(today)
        walk(script).getOrThrow()

        assertEquals(listOf("ANDROID_VR", "VISIONOS"), script.askedOf)
        assertEquals(visitor, YouTube.visitorData)
        assertEquals(listOf(visitor), adopted)
    }

    @Test
    fun `a refused VISIONOS still earns its try with a new visitorData, and that try starts with VISIONOS`() {
        remembering("VISIONOS", "ANDROID_VR")
        val script = Script(
            says = mapOf(
                // Refused with the visitorData the app has, served with the new one.
                "VISIONOS" to listOf(refused(bot), playable("VISIONOS", loudness = 5.0, seconds = "200")),
                "ANDROID_VR" to listOf(refused(botInFrench)),
                "IOS" to listOf(playable("IOS", loudness = 5.0, seconds = "200")),
                "ANDROID_VR as a new visitor" to listOf(refused(bot, carrying = newVisitor)),
            ),
            heads = mapOf("IOS" to 403),
        )
        val data = walk(script).getOrThrow()

        assertEquals(listOf("VISIONOS", "ANDROID_VR", "IOS", "ANDROID_VR as a new visitor", "VISIONOS"), script.askedOf)
        assertTrue(data.streamUrl.startsWith("https://VISIONOS.example/"))
        assertEquals(newVisitor, YouTube.visitorData)
        assertEquals("VISIONOS", YTPlayerUtils.streamMemory.worked)
        assertEquals(setOf("ANDROID_VR", "IOS"), YTPlayerUtils.streamMemory.refusedAt.keys)
    }
}

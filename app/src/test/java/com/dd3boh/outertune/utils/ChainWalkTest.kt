/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.StreamOrder.Memory
import com.dd3boh.outertune.utils.cipher.ChallengeSolver
import com.dd3boh.outertune.utils.cipher.PlayerScript
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.zionhuang.innertube.AddressPolicy
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ResponseContext
import com.zionhuang.innertube.models.Thumbnails
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * The player's own walk of the chain, with answers written here in place of YouTube's.
 *
 * StreamOrderTest says which client is asked first. This runs the walk itself, the code a song
 * goes through, for the cases that cannot be had on a phone on demand: the client that served the
 * last song refusing this one, no network, an app without a visitorData. Signed out, but for the
 * one test that says otherwise. The signature timestamp and the po tokens that the account's
 * client and a web client are asked with come from here too, as the answers do: the real ones
 * are a request to YouTube and a WebView.
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
        YTPlayerUtils.askWebClientFirst = false
        YTPlayerUtils.forgetWhatTheTrialLearned()
        YTPlayerUtils.streamMemory = Memory()
        YTPlayerUtils.onStreamMemoryChanged = { stored += it }
        YTPlayerUtils.onVisitorDataFound = { adopted += it }
    }

    @After
    fun leaveNothingBehind() {
        YouTube.cookie = null
        YouTube.visitorData = null
        YTPlayerUtils.askWebClientFirst = false
        YTPlayerUtils.forgetWhatTheTrialLearned()
        YTPlayerUtils.streamMemory = Memory()
        YTPlayerUtils.onStreamMemoryChanged = null
        YTPlayerUtils.onVisitorDataFound = null
        Throttle.clear("the test is over")
    }

    /**
     * What the clients say, each in turn and the last one again after that, and what each one's url
     * gets: by the client its host names, or by [headOf] when a test has to see the whole address.
     *
     * [playerScript] and [solver] are the experiment's. A walk without the experiment has neither,
     * and fails if it asks for one: that is what holds it to "exactly as before".
     */
    private class Script(
        private val says: Map<String, List<Any>>,
        private val heads: Map<String, Int?> = emptyMap(),
        private val headOf: ((String) -> Int?)? = null,
        private val playerScript: PlayerScript? = null,
        private val scriptWanted: Boolean = playerScript != null,
        private val solver: (suspend (List<String>, List<String>) -> ChallengeSolver.Solved)? = null,
    ) : YTPlayerUtils.Wire {
        val askedOf = mutableListOf<String>()

        /** Every address a check was made of, in order. */
        val checked = mutableListOf<String>()

        /** How often the solver was asked, and with what. */
        val solved = mutableListOf<Pair<List<String>, List<String>>>()

        /** What each client's request carried, for the ones that got any: the signature timestamp and the player po token. */
        val timestamps = mutableMapOf<String, Int>()
        val playerTokens = mutableMapOf<String, String>()
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
            signatureTimestamp?.let { timestamps[name] = it }
            webPlayerPot?.let { playerTokens[name] = it }
            val lines = says[name] ?: error("$name was not expected to be asked")
            val at = turn.getOrDefault(name, 0)
            turn[name] = at + 1
            return when (val said = lines[minOf(at, lines.lastIndex)]) {
                is Throwable -> Result.failure(said)
                else -> Result.success(said as PlayerResponse)
            }
        }

        override fun head(url: String): Int? {
            checked += url
            headOf?.let { return it(url) }
            val client = url.substringAfter("://").substringBefore('.')
            return if (client in heads) heads[client] else 200
        }

        override suspend fun playerScript(): PlayerScript? {
            check(scriptWanted) { "the player script was not expected to be asked for" }
            return playerScript
        }

        override suspend fun solve(script: PlayerScript, signatures: List<String>, ns: List<String>): ChallengeSolver.Solved {
            assertSame("solved against the script the request's timestamp came from", playerScript, script)
            solved += signatures to ns
            return (solver ?: error("the solver was not expected to be asked"))(signatures, ns)
        }

        override fun signatureTimestamp(videoId: String): Int? = TIMESTAMP

        override fun poTokens(videoId: String, sessionId: String?): PoTokenResult? = PoTokenResult(VIDEO_TOKEN, SESSION_TOKEN)

        companion object {
            /** What NewPipeExtractor reads, for the account's client. */
            const val TIMESTAMP = 20_375
            const val VIDEO_TOKEN = "token-made-for-the-video"
            const val SESSION_TOKEN = "token-made-for-the-session"
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

    /** [answer] with every address taken out of it, which is what a client that only streams over SABR gives. */
    private fun withoutAddress(answer: PlayerResponse) = answer.copy(
        streamingData = answer.streamingData?.let { data -> data.copy(adaptiveFormats = data.adaptiveFormats.map { it.copy(url = null) }) },
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
    fun `with no stream check answered the last client's address goes to the player unchecked, and says so`() {
        remembering("VISIONOS", "ANDROID_VR")
        // 8 Oct 2026 on the emulator, with every connection to a stream refused while YouTube
        // itself still answered: each client is asked and no address can be checked.
        val script = Script(
            says = mapOf(
                "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
                "ANDROID_VR" to listOf(refused(botInFrench)),
                "IOS" to listOf(playable("IOS", loudness = 5.0, seconds = "200")),
            ),
            heads = mapOf("VISIONOS" to null, "IOS" to null),
        )
        val data = walk(script).getOrThrow()

        assertEquals(listOf("VISIONOS", "ANDROID_VR", "IOS"), script.askedOf)
        assertEquals("VISIONOS OK, HEAD failed, ANDROID_VR LOGIN_REQUIRED, IOS OK, HEAD failed", YTPlayerUtils.lastStreamTrail)
        // IOS's, which answers 403 whenever it can be asked. It is still the best there is, and
        // what keeps it from being played for good is MusicService forgetting an address the
        // player fails on: see StreamAddresses.
        assertTrue(data.streamUrl.startsWith("https://IOS.example/"))
        assertFalse(data.validated)
        // A check that could not be made says nothing of the client, so VISIONOS is still first.
        assertEquals("VISIONOS", YTPlayerUtils.streamMemory.worked)
        assertEquals(setOf("ANDROID_VR"), YTPlayerUtils.streamMemory.refusedAt.keys)
        val next = Script(today)
        val after = walk(next).getOrThrow()
        assertEquals(listOf("VISIONOS"), next.askedOf)
        assertTrue(after.streamUrl.startsWith("https://VISIONOS.example/"))
        assertTrue(after.validated)
    }

    /**
     * 8 Oct 2026, 19:05, a Pixel 5 installed minutes before: the main client refused as ever, and
     * VISIONOS and IOS each answering OK with an address its check then got 403 for.
     */
    private fun everyAddressRefused(mainSays: String) = Script(
        says = mapOf(
            "ANDROID_VR" to listOf(refused(mainSays)),
            "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
            "IOS" to listOf(playable("IOS", loudness = 5.0, seconds = "200")),
            "ANDROID_VR as a new visitor" to listOf(refused(mainSays, carrying = newVisitor)),
        ),
        heads = mapOf("VISIONOS" to 403, "IOS" to 403),
    )

    @Test
    fun `a first song whose every address is refused is walked once more with a new visitorData, and then fails with the status`() {
        val script = everyAddressRefused(bot)
        val result = walk(script)

        assertTrue(result.isFailure)
        assertEquals(
            listOf("ANDROID_VR", "VISIONOS", "IOS", "ANDROID_VR as a new visitor", "ANDROID_VR", "VISIONOS", "IOS"),
            script.askedOf,
        )
        assertEquals("ANDROID_VR LOGIN_REQUIRED, VISIONOS OK, HEAD 403, IOS OK, HEAD 403", YTPlayerUtils.lastStreamTrail)
        // No client said why, so the status is all there is to say.
        assertEquals("YouTube refused the stream (HTTP 403)", result.exceptionOrNull()?.message)
        // The visitorData that changed nothing is given up again, and none is stored.
        assertEquals(visitor, YouTube.visitorData)
        assertEquals(emptyList<String>(), adopted)
        // Nobody served, so nobody is first for the next song: it is walked as written too.
        assertNull(YTPlayerUtils.streamMemory.worked)
        assertEquals(setOf("ANDROID_VR", "VISIONOS", "IOS"), YTPlayerUtils.streamMemory.refusedAt.keys)
    }

    @Test
    fun `what the throttle hears of such a song is the main client's refusal, where the phone's language lets it read one`() {
        // That evening Home said "YouTube has paused this connection" for five minutes. No client
        // that serves music had given the bot check: the words were the main client's, which it
        // gives for every song on every network, and which reach the throttle when a chain fails.
        walk(everyAddressRefused(bot))
        assertTrue("an English phone backs off on the main client's refusal", Throttle.isBlocked)

        // The main client is asked in the phone's language, and the throttle reads English only.
        Throttle.clear("the same song on a French phone")
        YTPlayerUtils.streamMemory = Memory()
        walk(everyAddressRefused(botInFrench))
        assertFalse("a French phone does not, for the very same answers", Throttle.isBlocked)
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

    /** The script the experiment's client is asked with. Its text is not read here: the stand-in solver needs none. */
    private val playerScript = PlayerScript(id = "0a1b2c3d", text = "not read in these tests", signatureTimestamp = 20_381)

    /**
     * A playable answer as a web client gives it: the address in a cipher, and an n in the address.
     * On a stream host of YouTube's, which is the only kind a solved address is taken from, and
     * its first name is what [Script.head] knows it by.
     */
    private fun ciphered() = playable("WEB_REMIX", loudness = 4.0, seconds = "200").let { answer ->
        val inner = "https://web-remix.googlevideo.com/videoplayback?itag=251&n=ISSUED"
        val cipher = "s=SCRAMBLED&sp=sig&url=" + URLEncoder.encode(inner, "UTF-8")
        answer.copy(
            streamingData = answer.streamingData?.let { data ->
                data.copy(adaptiveFormats = data.adaptiveFormats.map { it.copy(url = null, signatureCipher = cipher) })
            },
        )
    }

    /** What [ciphered] comes to once [solving] has answered, before any po token is put on it. */
    private val deciphered = "https://web-remix.googlevideo.com/videoplayback?itag=251&n=SOLVED&sig=UNSCRAMBLED"

    private val solving: suspend (List<String>, List<String>) -> ChallengeSolver.Solved = { signatures, ns ->
        ChallengeSolver.Solved(signatures.associateWith { "UNSCRAMBLED" }, ns.associateWith { "SOLVED" })
    }

    /** As on any phone in October 2026, with the experiment's switch on: VISIONOS served last and would be asked first. */
    private fun experimentOn(): Memory {
        remembering("VISIONOS", "ANDROID_VR")
        YTPlayerUtils.askWebClientFirst = true
        return YTPlayerUtils.streamMemory
    }

    @Test
    fun `with the experiment on the web client is asked first, with the timestamp of the script its address is then solved against`() {
        val before = experimentOn()
        val script = Script(mapOf("WEB_REMIX" to listOf(ciphered())), playerScript = playerScript, solver = solving)
        val data = walk(script).getOrThrow()

        assertEquals(listOf("WEB_REMIX"), script.askedOf)
        // The script's own timestamp, not the one NewPipeExtractor reads for the account's client.
        assertEquals(playerScript.signatureTimestamp, script.timestamps["WEB_REMIX"])
        assertEquals(Script.VIDEO_TOKEN, script.playerTokens["WEB_REMIX"])
        assertEquals(listOf(listOf("SCRAMBLED") to listOf("ISSUED")), script.solved)
        assertEquals(deciphered + "&pot=${Script.VIDEO_TOKEN}", data.streamUrl)
        assertEquals("one check, since the first token passed", 1, script.checked.size)
        assertEquals("WEB_REMIX OK, HEAD 200 with the video's token", YTPlayerUtils.lastStreamTrail)
        assertEquals("WEB_REMIX", YTPlayerUtils.lastStreamClient)
        assertTrue(data.validated)
        assertEquals("nothing is remembered of it", before, YTPlayerUtils.streamMemory)
        assertEquals("nothing changed, so nothing is stored", emptyList<String>(), stored)
    }

    @Test
    fun `an address the video's token leaves refused is tried with the session's, and the one that served goes first for the next song`() {
        experimentOn()
        fun script() = Script(
            says = mapOf("WEB_REMIX" to listOf(ciphered())),
            headOf = { url -> if (url.endsWith("&pot=${Script.SESSION_TOKEN}")) 200 else 403 },
            playerScript = playerScript,
            solver = solving,
        )
        val first = script()
        val data = walk(first).getOrThrow()

        assertEquals(
            listOf(deciphered + "&pot=${Script.VIDEO_TOKEN}", deciphered + "&pot=${Script.SESSION_TOKEN}"),
            first.checked,
        )
        assertEquals(deciphered + "&pot=${Script.SESSION_TOKEN}", data.streamUrl)
        assertEquals("WEB_REMIX OK, HEAD 200 with the session's token", YTPlayerUtils.lastStreamTrail)

        val second = script()
        walk(second).getOrThrow()
        assertEquals(listOf(deciphered + "&pot=${Script.SESSION_TOKEN}"), second.checked)
    }

    @Test
    fun `an address refused with every token and with none costs the experiment its turn, and the chain is asked as ever`() {
        val before = experimentOn()
        val script = Script(
            says = mapOf(
                "WEB_REMIX" to listOf(ciphered()),
                "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
            ),
            heads = mapOf("web-remix" to 403),
            playerScript = playerScript,
            solver = solving,
        )
        val data = walk(script).getOrThrow()

        assertEquals(listOf("WEB_REMIX", "VISIONOS"), script.askedOf)
        assertEquals(
            listOf(
                deciphered + "&pot=${Script.VIDEO_TOKEN}",
                deciphered + "&pot=${Script.SESSION_TOKEN}",
                deciphered,
                "https://VISIONOS.example/videoplayback?itag=251",
            ),
            script.checked,
        )
        assertEquals("WEB_REMIX OK, HEAD 403 with no token, VISIONOS OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        // No token on an address that is not a web client's.
        assertEquals("https://VISIONOS.example/videoplayback?itag=251", data.streamUrl)
        assertEquals("VISIONOS", YTPlayerUtils.lastStreamClient)
        assertEquals(before, YTPlayerUtils.streamMemory)
    }

    @Test
    fun `a solver that fails, or leaves a value unsolved, costs the experiment its turn and no more`() {
        val failing: suspend (List<String>, List<String>) -> ChallengeSolver.Solved = { _, _ -> error("the solver's two files are not in this build") }
        val halfway: suspend (List<String>, List<String>) -> ChallengeSolver.Solved = { signatures, _ ->
            ChallengeSolver.Solved(signatures.associateWith { "UNSCRAMBLED" }, emptyMap())
        }
        // What NewPipeExtractor's n function did on 9 Oct 2026: the value handed back as it came.
        val idle: suspend (List<String>, List<String>) -> ChallengeSolver.Solved = { signatures, ns ->
            ChallengeSolver.Solved(signatures.associateWith { "UNSCRAMBLED" }, ns.associateWith { it })
        }
        for (solver in listOf(failing, halfway, idle)) {
            experimentOn()
            val script = Script(
                says = mapOf(
                    "WEB_REMIX" to listOf(ciphered()),
                    "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
                ),
                playerScript = playerScript,
                solver = solver,
            )
            val data = walk(script).getOrThrow()

            assertEquals(listOf("WEB_REMIX", "VISIONOS"), script.askedOf)
            assertEquals("WEB_REMIX OK, address not deciphered, VISIONOS OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
            assertEquals("nothing of the web client's was fetched", listOf("https://VISIONOS.example/videoplayback?itag=251"), script.checked)
            assertTrue(data.streamUrl.startsWith("https://VISIONOS.example/"))
        }
    }

    @Test
    fun `a solver that never answers is given up on when its time is over, and the song goes on`() {
        experimentOn()
        YTPlayerUtils.trialLimitMs = 150
        val script = Script(
            says = mapOf(
                "WEB_REMIX" to listOf(ciphered()),
                "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
            ),
            playerScript = playerScript,
            solver = { _, _ -> awaitCancellation() },
        )
        val started = System.nanoTime()
        val data = walk(script).getOrThrow()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("given up on after its 150 ms, not waited for: took $tookMs ms", tookMs < 5_000)
        assertEquals("WEB_REMIX OK, address not deciphered, VISIONOS OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        assertTrue(data.streamUrl.startsWith("https://VISIONOS.example/"))
    }

    @Test
    fun `without a player script the web client is not asked at all`() {
        val before = experimentOn()
        val script = Script(
            says = mapOf("VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200"))),
            playerScript = null,
            scriptWanted = true,
        )
        walk(script).getOrThrow()

        assertEquals(listOf("VISIONOS"), script.askedOf)
        assertEquals("WEB_REMIX not asked, no player script, VISIONOS OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        assertEquals(before, YTPlayerUtils.streamMemory)
    }

    @Test
    fun `a web client whose answer has no address in it costs its turn and the chain is asked as ever`() {
        val before = experimentOn()
        val script = Script(
            says = mapOf(
                "WEB_REMIX" to listOf(withoutAddress(playable("WEB_REMIX", loudness = 4.0, seconds = "200"))),
                "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
            ),
            playerScript = playerScript,
        )
        val data = walk(script).getOrThrow()

        assertEquals(listOf("WEB_REMIX", "VISIONOS"), script.askedOf)
        assertEquals("WEB_REMIX OK, no address, VISIONOS OK, HEAD 200", YTPlayerUtils.lastStreamTrail)
        assertEquals("https://VISIONOS.example/videoplayback?itag=251", data.streamUrl)
        assertEquals(before, YTPlayerUtils.streamMemory)
    }

    @Test
    fun `what the experiment's client is refused with is not the chain's, and the song fails with what the chain's own clients said`() {
        experimentOn()
        val gone = refused("This video is not available", status = "UNPLAYABLE")
        // The web client gives the bot check, which from a client of the chain would back the app off.
        val script = Script(
            says = mapOf("WEB_REMIX" to listOf(refused(bot)), "VISIONOS" to listOf(gone), "ANDROID_VR" to listOf(refused(botInFrench)), "IOS" to listOf(gone)),
            playerScript = playerScript,
        )
        val failure = walk(script).exceptionOrNull()

        assertEquals(listOf("WEB_REMIX", "VISIONOS", "ANDROID_VR", "IOS"), script.askedOf)
        assertEquals("This video is not available", failure?.message)
        assertFalse("the experiment's refusal was not told to the throttle", Throttle.isBlocked)
    }

    @Test
    fun `signed in, the account's client is asked last and with a signature timestamp, and nothing is remembered of it`() {
        YouTube.cookie = "SAPISID=made-up"
        // The day after VISIONOS: its address and IOS's fail their check, and the account's is all that is left.
        val script = Script(
            says = mapOf(
                "ANDROID_VR" to listOf(refused(bot)),
                "VISIONOS" to listOf(playable("VISIONOS", loudness = 5.0, seconds = "200")),
                "IOS" to listOf(playable("IOS", loudness = 5.0, seconds = "200")),
                "ANDROID" to listOf(playable("ANDROID", loudness = 5.0, seconds = "200")),
            ),
            heads = mapOf("VISIONOS" to 403, "IOS" to 403),
        )
        val data = walk(script).getOrThrow()

        assertEquals(listOf("ANDROID_VR", "VISIONOS", "IOS", "ANDROID"), script.askedOf)
        assertEquals(
            "ANDROID_VR LOGIN_REQUIRED, VISIONOS OK, HEAD 403, IOS OK, HEAD 403, ANDROID (account) OK, HEAD 200",
            YTPlayerUtils.lastStreamTrail,
        )
        assertEquals(mapOf("ANDROID" to Script.TIMESTAMP), script.timestamps)
        // No client of this chain asks with a po token, so none is made and none is sent.
        assertEquals(emptyMap<String, String>(), script.playerTokens)
        assertTrue(data.streamUrl.startsWith("https://ANDROID.example/"))
        assertNull("a song the account served is not a reason to ask as the account first", YTPlayerUtils.streamMemory.worked)
        assertEquals(setOf("ANDROID_VR", "VISIONOS", "IOS"), YTPlayerUtils.streamMemory.refusedAt.keys)
    }
}

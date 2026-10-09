/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.playback.StandInMemory.Memory
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A song whose own id YouTube calls gone is looked for under another, played from there, and the
 * swap remembered. Everything else about asking for a stream stays as it was: a song that plays
 * costs one request and no search, and one that fails for any other reason fails the same way.
 */
class StandInsTest {
    private val t0 = 1_791_000_000_000L
    private var now = t0
    private val stored = mutableListOf<String>()
    private val gone = mutableListOf<String>()
    private val doubted = mutableListOf<List<String>>()
    private val plays = mutableListOf<String>()

    @Before
    fun fresh() {
        StandInMemory.current = Memory()
        StandInMemory.onChanged = { stored += it }
        GoneRun.played()
    }

    @After
    fun leaveNothingBehind() {
        StandInMemory.current = Memory()
        StandInMemory.onChanged = null
        GoneRun.played()
    }

    private fun stream(of: String) = YTPlayerUtils.PlaybackData(
        audioConfig = null,
        videoDetails = null,
        playbackTracking = null,
        format = PlayerResponse.StreamingData.Format(
            itag = 251, url = "https://$of.example/videoplayback", mimeType = "audio/webm; codecs=\"opus\"",
            bitrate = 140_000, width = null, height = null, contentLength = 3_000_000, quality = "tiny", fps = null,
            qualityLabel = null, averageBitrate = null, audioQuality = null, approxDurationMs = null,
            audioSampleRate = 48_000, audioChannels = 2, loudnessDb = null, lastModified = null, signatureCipher = null,
        ),
        streamUrl = "https://$of.example/videoplayback",
        streamExpiresInSeconds = 21_540,
    )

    private fun result(id: String, title: String = "Instant Crush", seconds: Int? = 337, artist: String = "Daft Punk") =
        SongItem(id = id, title = title, artists = listOf(Artist(name = artist, id = null)), album = null, duration = seconds, thumbnail = "", explicit = false)

    private val unavailable get() = YTPlayerUtils.SongUnavailable("This video is not available")

    /** What YouTube says of each id, what a search finds, and everything that was asked. */
    private inner class World(
        private val says: Map<String, () -> Result<YTPlayerUtils.PlaybackData>>,
        private val finds: (() -> List<SongItem>)? = null,
        private val knows: Boolean = true,
    ) {
        val asked = mutableListOf<String>()
        val standIns = StandIns(
            resolve = { id ->
                asked += "stream $id"
                (says[id] ?: error("$id was not expected to be asked")).invoke()
            },
            wanted = { id ->
                asked += "song $id"
                if (knows) StandIn.Wanted("Instant Crush", listOf("Daft Punk"), 337) else null
            },
            search = { query ->
                asked += "search $query"
                (finds ?: error("no search was expected")).invoke()
            },
            now = { now },
            log = {},
        ).also {
            it.onGone = { id -> gone += id }
            it.onDoubt = { ids -> doubted += ids }
            it.onPlays = { id -> plays += id }
        }

        fun play(id: String = "OLDID000001") = runBlocking { standIns.playbackData(id) }
    }

    @Test
    fun `a song that plays under its own id is asked for once, and nothing is searched`() {
        val world = World(mapOf("OLDID000001" to { Result.success(stream("OLDID000001")) }))

        val played = world.play().getOrThrow()

        assertEquals("https://OLDID000001.example/videoplayback", played.streamUrl)
        assertEquals(listOf("stream OLDID000001"), world.asked)
        assertEquals(listOf("OLDID000001"), plays)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a song that fails for any other reason fails as it did, and nothing is searched`() {
        for (failure in listOf(IOException("no network"), PlaybackException("YouTube refused the stream (HTTP 403)", null, PlaybackException.ERROR_CODE_REMOTE_ERROR))) {
            val world = World(mapOf("OLDID000001" to { Result.failure(failure) }))

            assertSame(failure, world.play().exceptionOrNull())
            assertEquals(listOf("stream OLDID000001"), world.asked)
        }
        assertEquals(emptyList<String>(), gone)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a song that is gone is played from the same recording under another id, and the swap is kept`() {
        val world = World(
            says = mapOf("OLDID000001" to { Result.failure(unavailable) }, "NEWID000001" to { Result.success(stream("NEWID000001")) }),
            finds = { listOf(result("OTHER000001", title = "Instant Crush (Live)"), result("NEWID000001")) },
        )

        val played = world.play().getOrThrow()

        assertEquals("https://NEWID000001.example/videoplayback", played.streamUrl)
        assertEquals(listOf("stream OLDID000001", "song OLDID000001", "search Instant Crush Daft Punk", "stream NEWID000001"), world.asked)
        assertEquals("NEWID000001", StandInMemory.idFor("OLDID000001", now))
        assertEquals(listOf("OLDID000001=NEWID000001@$t0"), stored)
        assertEquals(emptyList<String>(), gone)

        // The next time it is one request, as for any song.
        world.asked.clear()
        now += 60_000
        world.play().getOrThrow()
        assertEquals(listOf("stream NEWID000001"), world.asked)
    }

    @Test
    fun `a song that is gone and has no other copy fails with YouTube's words, and is reported gone`() {
        val failure = unavailable
        val world = World(
            says = mapOf("OLDID000001" to { Result.failure(failure) }),
            finds = { listOf(result("OTHER000001", title = "Instant Crush (Slowed)"), result("OTHER000002", artist = "Karaoke Hits")) },
        )

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(listOf("OLDID000001"), gone)
        assertEquals(emptyList<String>(), stored)
        assertEquals("OLDID000001", StandInMemory.idFor("OLDID000001", now))
    }

    @Test
    fun `a search that fails says nothing of the song`() {
        val failure = unavailable
        val world = World(says = mapOf("OLDID000001" to { Result.failure(failure) }), finds = { throw IOException("no network") })

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(emptyList<String>(), gone)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a search that does not answer in time says nothing of the song either`() {
        val failure = unavailable
        val standIns = StandIns(
            resolve = { Result.failure(failure) },
            wanted = { StandIn.Wanted("Instant Crush", listOf("Daft Punk"), 337) },
            search = { delay(60_000); listOf(result("NEWID000001")) },
            now = { now },
            log = {},
            searchLimitMs = 50,
        ).also { it.onGone = { id -> gone += id } }

        assertSame(failure, runBlocking { standIns.playbackData("OLDID000001") }.exceptionOrNull())
        assertEquals(emptyList<String>(), gone)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a stand-in that does not play is not kept, and the song is not called gone for it`() {
        val failure = unavailable
        val world = World(
            says = mapOf("OLDID000001" to { Result.failure(failure) }, "NEWID000001" to { Result.failure(IOException("reset")) }),
            finds = { listOf(result("NEWID000001")) },
        )

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(emptyList<String>(), gone)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a song the app knows nothing of is not searched for`() {
        val failure = unavailable
        val world = World(says = mapOf("OLDID000001" to { Result.failure(failure) }), knows = false)

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(listOf("stream OLDID000001", "song OLDID000001"), world.asked)
        assertEquals(emptyList<String>(), gone)
    }

    @Test
    fun `a stand-in that is gone in its turn is given up, and the song looked for afresh`() {
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0 - 1_000)
        val world = World(
            says = mapOf(
                "NEWID000001" to { Result.failure(unavailable) },
                "OLDID000001" to { Result.failure(unavailable) },
                "NEWID000002" to { Result.success(stream("NEWID000002")) },
            ),
            // The stand-in that just died still comes first in a search. It is not asked again.
            finds = { listOf(result("NEWID000001"), result("NEWID000002")) },
        )

        val played = world.play().getOrThrow()

        assertEquals("https://NEWID000002.example/videoplayback", played.streamUrl)
        assertEquals(
            listOf("stream NEWID000001", "stream OLDID000001", "song OLDID000001", "search Instant Crush Daft Punk", "stream NEWID000002"),
            world.asked,
        )
        assertEquals("NEWID000002", StandInMemory.idFor("OLDID000001", now))
        assertEquals(listOf("", "OLDID000001=NEWID000002@$t0"), stored)
    }

    @Test
    fun `when the likeliest copy is gone as well the next is tried, three at most, and then the song is gone`() {
        val failure = unavailable
        val world = World(
            says = mapOf(
                "OLDID000001" to { Result.failure(failure) },
                "NEWID000001" to { Result.failure(unavailable) },
                "NEWID000002" to { Result.failure(unavailable) },
                "NEWID000003" to { Result.failure(unavailable) },
            ),
            finds = { listOf(result("NEWID000001"), result("NEWID000002"), result("NEWID000003"), result("NEWID000004")) },
        )

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(listOf("stream NEWID000001", "stream NEWID000002", "stream NEWID000003"), world.asked.takeLast(3))
        assertEquals(listOf("OLDID000001"), gone)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a stand-in that fails for another reason is kept, and the song fails as any other would`() {
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0 - 1_000)
        val failure = IOException("no network")
        val world = World(mapOf("NEWID000001" to { Result.failure(failure) }))

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(listOf("stream NEWID000001"), world.asked)
        assertEquals("NEWID000001", StandInMemory.idFor("OLDID000001", now))
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `after a month the song's own id is asked again, and plays if it has come back`() {
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0 - StandInMemory.KEEP_MS)
        val world = World(mapOf("OLDID000001" to { Result.success(stream("OLDID000001")) }))

        assertEquals("https://OLDID000001.example/videoplayback", world.play().getOrThrow().streamUrl)
        assertEquals(listOf("stream OLDID000001"), world.asked)
    }

    // YouTube words a client it has stopped serving the way it words a song it has taken down.
    // A run of songs called gone, with nothing played between them, is therefore not believed.

    private fun goneWithoutCopy(vararg ids: String) = World(
        says = ids.associate { id -> id to { Result.failure<YTPlayerUtils.PlaybackData>(unavailable) } },
        finds = { emptyList() },
    )

    @Test
    fun `the third song in a row called gone takes back the first two, and the fourth is not looked for`() {
        val world = goneWithoutCopy("OLDID000001", "OLDID000002", "OLDID000003", "OLDID000004")

        world.play("OLDID000001")
        world.play("OLDID000002")
        assertEquals(listOf("OLDID000001", "OLDID000002"), gone)
        assertEquals(emptyList<List<String>>(), doubted)

        world.play("OLDID000003")
        assertEquals(listOf("OLDID000001", "OLDID000002"), gone)
        assertEquals(listOf(listOf("OLDID000001", "OLDID000002", "OLDID000003")), doubted)

        world.asked.clear()
        world.play("OLDID000004")
        assertEquals(listOf("stream OLDID000004"), world.asked)
        assertEquals(1, doubted.size)
    }

    @Test
    fun `a song that plays in between starts the count again`() {
        val world = World(
            says = mapOf(
                "OLDID000001" to { Result.failure(unavailable) },
                "OLDID000002" to { Result.failure(unavailable) },
                "FINE0000001" to { Result.success(stream("FINE0000001")) },
                "OLDID000003" to { Result.failure(unavailable) },
            ),
            finds = { emptyList() },
        )

        world.play("OLDID000001")
        world.play("OLDID000002")
        world.play("FINE0000001")
        world.play("OLDID000003")

        assertEquals(listOf("OLDID000001", "OLDID000002", "OLDID000003"), gone)
        assertEquals(emptyList<List<String>>(), doubted)
    }

    @Test
    fun `a song already looked for is not looked for again until something has played`() {
        val world = goneWithoutCopy("OLDID000001")

        world.play("OLDID000001")
        world.asked.clear()
        world.play("OLDID000001")

        // The player asks again at every retry and every press of Play.
        assertEquals(listOf("stream OLDID000001"), world.asked)
        assertEquals(listOf("OLDID000001"), gone)
    }

    @Test
    fun `while nothing of the kind is believed, a remembered stand-in that answers gone is kept`() {
        val run = goneWithoutCopy("OLDID000001", "OLDID000002", "OLDID000003")
        run.play("OLDID000001"); run.play("OLDID000002"); run.play("OLDID000003")
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000009", "NEWID000009", t0 - 1_000)
        stored.clear()
        val failure = unavailable
        val world = World(mapOf("NEWID000009" to { Result.failure(failure) }))

        assertSame(failure, world.play("OLDID000009").exceptionOrNull())
        assertEquals(listOf("stream NEWID000009"), world.asked)
        assertEquals("NEWID000009", StandInMemory.idFor("OLDID000009", now))
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a stand-in that plays ends a run as a song's own id does`() {
        val world = World(
            says = mapOf(
                "OLDID000001" to { Result.failure(unavailable) },
                "OLDID000002" to { Result.failure(unavailable) },
                "OLDID000003" to { Result.failure(unavailable) },
                "NEWID000003" to { Result.success(stream("NEWID000003")) },
                "OLDID000004" to { Result.failure(unavailable) },
            ),
            finds = { emptyList() },
        )
        world.play("OLDID000001")
        world.play("OLDID000002")
        val found = World(
            says = mapOf("OLDID000003" to { Result.failure(unavailable) }, "NEWID000003" to { Result.success(stream("NEWID000003")) }),
            finds = { listOf(result("NEWID000003")) },
        )
        found.play("OLDID000003").getOrThrow()

        world.play("OLDID000004")

        assertEquals(listOf("OLDID000001", "OLDID000002", "OLDID000004"), gone)
        assertEquals(emptyList<List<String>>(), doubted)
    }
}

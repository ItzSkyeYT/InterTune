/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.playback.StandInMemory.Entry
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
    private val day = 24L * 60 * 60 * 1000
    private var now = t0
    private val stored = mutableListOf<String>()
    private val gone = mutableListOf<String>()
    private val plays = mutableListOf<String>()
    private val reported = mutableListOf<String>()

    @Before
    fun fresh() {
        StandInMemory.current = Memory()
        StandInMemory.onChanged = { stored += it }
    }

    @After
    fun leaveNothingBehind() {
        StandInMemory.current = Memory()
        StandInMemory.onChanged = null
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
    private val isGone: () -> Result<YTPlayerUtils.PlaybackData> = { Result.failure(unavailable) }
    private fun serves(id: String): () -> Result<YTPlayerUtils.PlaybackData> = { Result.success(stream(id)) }

    /**
     * What YouTube says of each id, what a search finds for each song, and everything that was
     * asked. One instance, so one run, as the player has.
     */
    private inner class World(
        private val says: Map<String, () -> Result<YTPlayerUtils.PlaybackData>>,
        private val finds: Map<String, () -> List<SongItem>> = emptyMap(),
        private val knows: Boolean = true,
        on: Boolean = true,
    ) {
        val asked = mutableListOf<String>()
        private var lookedFor = ""
        private var walked: String? = null
        val standIns = StandIns(
            resolve = { id ->
                asked += "stream $id"
                (says[id] ?: error("$id was not expected to be asked")).invoke().also { walked = if (it.isSuccess) "OK" else "ERROR" }
            },
            wanted = { id ->
                asked += "song $id"
                lookedFor = id
                if (knows) StandIn.Wanted("Instant Crush", listOf("Daft Punk"), 337) else null
            },
            search = { query ->
                asked += "search $query"
                (finds[lookedFor] ?: finds["*"] ?: error("no search was expected for $lookedFor")).invoke()
            },
            now = { now },
            log = {},
            enabled = { on },
            trail = { walked },
            report = { reported += it },
        ).also {
            it.onGone = { id -> gone += id }
            it.onPlays = { id -> plays += id }
        }

        fun play(id: String = "OLDID000001") = runBlocking { standIns.playbackData(id) }
    }

    private fun nothingFound(vararg ids: String) = World(says = ids.associateWith { isGone }, finds = mapOf("*" to { emptyList() }))

    @Test
    fun `a song that plays under its own id is asked for once, and nothing is searched`() {
        val world = World(mapOf("OLDID000001" to serves("OLDID000001")))

        val played = world.play().getOrThrow()

        assertEquals("https://OLDID000001.example/videoplayback", played.data.streamUrl)
        assertEquals("OLDID000001", played.from)
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
    fun `with the switch off a song that is gone fails as it always has`() {
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0 - 1_000)
        val failure = unavailable
        val world = World(mapOf("OLDID000001" to { Result.failure(failure) }), on = false)

        assertSame(failure, world.play().exceptionOrNull())
        // Not even what is remembered is used: nothing but the song's own id is ever asked.
        assertEquals(listOf("stream OLDID000001"), world.asked)
        assertEquals(emptyList<String>(), gone)
        assertEquals(emptyList<String>(), plays)
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun `a song that is gone is played from the same recording under another id, and the swap is kept`() {
        val world = World(
            says = mapOf("OLDID000001" to isGone, "NEWID000001" to serves("NEWID000001")),
            finds = mapOf("*" to { listOf(result("OTHER000001", title = "Instant Crush (Live)"), result("NEWID000001")) }),
        )

        val played = world.play().getOrThrow()

        assertEquals("https://NEWID000001.example/videoplayback", played.data.streamUrl)
        assertEquals("the resolver has to know it is another upload's", "NEWID000001", played.from)
        assertEquals(listOf("stream OLDID000001", "song OLDID000001", "search Instant Crush Daft Punk", "stream NEWID000001"), world.asked)
        assertEquals("NEWID000001", StandInMemory.idFor("OLDID000001", now))
        assertEquals(listOf("OLDID000001=NEWID000001@$t0"), stored)
        assertEquals(emptyList<String>(), gone)
        assertEquals(listOf("OLDID000001"), plays)

        // The next time it is one request, as for any song.
        world.asked.clear()
        now += 60_000
        assertEquals("NEWID000001", world.play().getOrThrow().from)
        assertEquals(listOf("stream NEWID000001"), world.asked)
        assertEquals(listOf("OLDID000001", "OLDID000001"), plays)
    }

    @Test
    fun `a song that is gone and has no other copy fails with YouTube's words, and that is noted`() {
        val failure = unavailable
        val world = World(
            says = mapOf("OLDID000001" to { Result.failure(failure) }),
            finds = mapOf("*" to { listOf(result("OTHER000001", title = "Instant Crush (Slowed)"), result("OTHER000002", artist = "Karaoke Hits")) }),
        )

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(listOf("OLDID000001"), gone)
        assertEquals(listOf("OLDID000001=@$t0"), stored)
        assertEquals("OLDID000001", StandInMemory.idFor("OLDID000001", now))
    }

    @Test
    fun `a song looked for in vain is not searched for again for three days, and costs what it always did`() {
        val world = World(
            says = mapOf("OLDID000001" to isGone, "FINE0000001" to serves("FINE0000001")),
            finds = mapOf("*" to { emptyList() }),
        )
        world.play()
        world.play("FINE0000001")
        world.asked.clear()

        now += 2 * day
        world.play()
        assertEquals("one request, as before any of this", listOf("stream OLDID000001"), world.asked)
        assertEquals("and it is still reported, each time", listOf("OLDID000001", "OLDID000001"), gone)

        world.play("FINE0000001")
        world.asked.clear()
        now += 2 * day
        world.play()
        assertEquals(listOf("stream OLDID000001", "song OLDID000001", "search Instant Crush Daft Punk"), world.asked)
    }

    @Test
    fun `a search that fails says nothing of the song, and none is made for a while after`() {
        val failure = unavailable
        val world = World(
            says = mapOf("OLDID000001" to { Result.failure(failure) }, "OLDID000002" to isGone),
            finds = mapOf("*" to { throw IOException("no network") }),
        )

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(emptyList<String>(), gone)
        assertEquals(emptyList<String>(), stored)

        world.asked.clear()
        now += 60_000
        world.play("OLDID000002")
        assertEquals("the next song does not wait on a search that is failing", listOf("stream OLDID000002"), world.asked)

        world.asked.clear()
        now += StandIn.SEARCH_REST_MS
        world.play("OLDID000002")
        assertEquals(listOf("stream OLDID000002", "song OLDID000002", "search Instant Crush Daft Punk"), world.asked)
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
            enabled = { true },
            trail = { "VISIONOS ERROR" },
            report = { reported += it },
        ).also { it.onGone = { id -> gone += id } }

        assertSame(failure, runBlocking { standIns.playbackData("OLDID000001") }.exceptionOrNull())
        assertEquals(emptyList<String>(), gone)
        assertEquals(emptyList<String>(), stored)
        assertEquals(listOf("own id: VISIONOS ERROR | search did not answer in 50 ms"), reported)
    }

    @Test
    fun `a stand-in that does not play is not kept, and the song is not called gone for it`() {
        val failure = unavailable
        val world = World(
            says = mapOf("OLDID000001" to { Result.failure(failure) }, "NEWID000001" to { Result.failure(IOException("reset")) }),
            finds = mapOf("*" to { listOf(result("NEWID000001")) }),
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
    fun `a stand-in that is gone in its turn gives way to the next copy that plays`() {
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0 - 1_000)
        val world = World(
            says = mapOf("NEWID000001" to isGone, "OLDID000001" to isGone, "NEWID000002" to serves("NEWID000002")),
            // The stand-in that just died still comes first in a search. It is not asked again.
            finds = mapOf("*" to { listOf(result("NEWID000001"), result("NEWID000002")) }),
        )

        val played = world.play().getOrThrow()

        assertEquals("NEWID000002", played.from)
        assertEquals(
            listOf("stream NEWID000001", "stream OLDID000001", "song OLDID000001", "search Instant Crush Daft Punk", "stream NEWID000002"),
            world.asked,
        )
        assertEquals("NEWID000002", StandInMemory.idFor("OLDID000001", now))
        assertEquals(listOf("OLDID000001=NEWID000002@$t0"), stored)
    }

    @Test
    fun `when the likeliest copy is gone as well the next is tried, three at most, and then the song is gone`() {
        val failure = unavailable
        val world = World(
            says = mapOf(
                "OLDID000001" to { Result.failure(failure) },
                "NEWID000001" to isGone, "NEWID000002" to isGone, "NEWID000003" to isGone,
            ),
            finds = mapOf("*" to { listOf(result("NEWID000001"), result("NEWID000002"), result("NEWID000003"), result("NEWID000004")) }),
        )

        assertSame(failure, world.play().exceptionOrNull())
        assertEquals(listOf("stream NEWID000001", "stream NEWID000002", "stream NEWID000003"), world.asked.takeLast(3))
        assertEquals(listOf("OLDID000001"), gone)
        assertEquals(listOf("OLDID000001=@$t0"), stored)
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
    fun `after a month the song's own id is asked again, and what was kept of it goes if it is back`() {
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0 - StandInMemory.KEEP_MS)
        val world = World(mapOf("OLDID000001" to serves("OLDID000001")))

        assertEquals("OLDID000001", world.play().getOrThrow().from)
        assertEquals(listOf("stream OLDID000001"), world.asked)
        assertEquals(Memory(), StandInMemory.current)
    }

    // The error report's "stream chain" is the last walk made. After a lookup that is a copy's,
    // under an error that is the song's own, so the report is given the lookup's walks together.

    @Test
    fun `a song resolved in one walk under its own id leaves the report's chain as the walk wrote it`() {
        World(mapOf("OLDID000001" to serves("OLDID000001"))).play()
        World(mapOf("OLDID000001" to { Result.failure(IOException("no network")) })).play()

        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun `a lookup tells the report every walk it made, in order, and names no song`() {
        val world = World(
            says = mapOf("OLDID000001" to isGone, "NEWID000001" to isGone, "NEWID000002" to serves("NEWID000002")),
            finds = mapOf("*" to { listOf(result("NEWID000001"), result("NEWID000002")) }),
        )

        world.play()
        assertEquals(listOf("own id: ERROR | copy 1: ERROR | copy 2: OK"), reported)

        // Played from what it remembers, the one walk is the stand-in's, and the report says so.
        reported.clear()
        world.play()
        assertEquals(listOf("stand-in: OK"), reported)
    }

    @Test
    fun `a lookup that plays nothing tells the report why`() {
        val world = World(
            says = mapOf("OLDID000001" to isGone, "FINE0000001" to serves("FINE0000001")),
            finds = mapOf("*" to { listOf(result("OTHER000001", title = "Instant Crush (Live)")) }),
        )

        world.play()
        world.play()
        world.play("FINE0000001")
        world.play()

        assertEquals(
            listOf(
                "own id: ERROR | search: 1 results, none surely the same recording",
                "own id: ERROR | no search: looked for already since a stream last resolved",
                "own id: ERROR | no search: looked for these last days, and nothing was surely it",
            ),
            reported,
        )
    }

    @Test
    fun `a search that fails is in the report, and so is the rest it is given`() {
        val world = World(says = mapOf("OLDID000001" to isGone), finds = mapOf("*" to { throw IOException("no network") }))

        world.play()
        world.play()

        assertEquals(listOf("own id: ERROR | search failed: IOException", "own id: ERROR | no search: one failed a moment ago"), reported)
    }

    // A run of songs called gone, with no stream resolved between them, is therefore not believed.

    @Test
    fun `the third song in a row called gone puts back what the run noted, and the fourth is not looked for`() {
        val world = nothingFound("OLDID000001", "OLDID000002", "OLDID000003", "OLDID000004")

        world.play("OLDID000001")
        world.play("OLDID000002")
        assertEquals(listOf("OLDID000001", "OLDID000002"), gone)
        assertEquals(setOf("OLDID000001", "OLDID000002"), StandInMemory.current.of.keys)

        world.play("OLDID000003")
        assertEquals("the third is not reported", listOf("OLDID000001", "OLDID000002"), gone)
        assertEquals("and nothing is left noted of the three", Memory(), StandInMemory.current)

        world.asked.clear()
        world.play("OLDID000004")
        assertEquals(listOf("stream OLDID000004"), world.asked)
        assertEquals(Memory(), StandInMemory.current)
    }

    @Test
    fun `a run that is not believed puts back the stand-in a song had`() {
        val had = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0 - 1_000)
        StandInMemory.current = had
        val world = World(
            says = mapOf("NEWID000001" to isGone, "OLDID000001" to isGone, "OLDID000002" to isGone, "OLDID000003" to isGone),
            finds = mapOf("*" to { emptyList() }),
        )

        world.play("OLDID000001")
        assertEquals("for now it is noted as having no copy", Entry(null, t0), StandInMemory.current.of["OLDID000001"])
        world.play("OLDID000002")
        world.play("OLDID000003")

        assertEquals(had, StandInMemory.current)
    }

    @Test
    fun `a song that plays in between starts the count again`() {
        val world = World(
            says = mapOf("OLDID000001" to isGone, "OLDID000002" to isGone, "FINE0000001" to serves("FINE0000001"), "OLDID000003" to isGone),
            finds = mapOf("*" to { emptyList() }),
        )

        world.play("OLDID000001")
        world.play("OLDID000002")
        world.play("FINE0000001")
        world.play("OLDID000003")

        assertEquals(listOf("OLDID000001", "OLDID000002", "OLDID000003"), gone)
        assertEquals(setOf("OLDID000001", "OLDID000002", "OLDID000003"), StandInMemory.current.of.keys)
    }

    @Test
    fun `a song already looked for is not looked for again until something has played`() {
        val world = nothingFound("OLDID000001")

        world.play("OLDID000001")
        world.asked.clear()
        world.play("OLDID000001")

        // The player asks again at every retry and every press of Play.
        assertEquals(listOf("stream OLDID000001"), world.asked)
        assertEquals(listOf("OLDID000001"), gone)
    }

    @Test
    fun `while nothing of the kind is believed, a remembered stand-in that answers gone is kept`() {
        StandInMemory.current = StandInMemory.remember(Memory(), "OLDID000009", "NEWID000009", t0 - 1_000)
        val failure = unavailable
        val world = World(
            says = mapOf("OLDID000001" to isGone, "OLDID000002" to isGone, "OLDID000003" to isGone, "NEWID000009" to { Result.failure(failure) }),
            finds = mapOf("*" to { emptyList() }),
        )
        world.play("OLDID000001"); world.play("OLDID000002"); world.play("OLDID000003")
        world.asked.clear()

        assertSame(failure, world.play("OLDID000009").exceptionOrNull())
        assertEquals(listOf("stream NEWID000009"), world.asked)
        assertEquals("NEWID000009", StandInMemory.idFor("OLDID000009", now))
    }

    @Test
    fun `a stand-in that plays ends a run as a song's own id does`() {
        val world = World(
            says = mapOf(
                "OLDID000001" to isGone, "OLDID000002" to isGone, "OLDID000003" to isGone,
                "NEWID000003" to serves("NEWID000003"), "OLDID000004" to isGone,
            ),
            finds = mapOf("OLDID000003" to { listOf(result("NEWID000003")) }, "*" to { emptyList() }),
        )
        world.play("OLDID000001")
        world.play("OLDID000002")
        assertEquals("NEWID000003", world.play("OLDID000003").getOrThrow().from)

        world.play("OLDID000004")

        assertEquals(listOf("OLDID000001", "OLDID000002", "OLDID000004"), gone)
        assertEquals(listOf("OLDID000003"), plays)
    }

    @Test
    fun `downloads have a run of their own`() {
        val downloads = GoneRun()
        val player = GoneRun()
        repeat(GoneRun.LIMIT) { downloads.gone("OLDID00000$it") }

        assertEquals(true, downloads.doubted())
        assertEquals(false, player.doubted())
        assertEquals(true, player.maySearch("OLDID000009"))
    }
}

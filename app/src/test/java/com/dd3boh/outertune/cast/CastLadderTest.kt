/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The ladder a song goes down on a DLNA speaker that takes some forms and not others: which rungs,
 * in what order, when the walk moves on and where, and what is remembered for the next song.
 */
class CastLadderTest {
    private val opus = "audio/webm; codecs=\"opus\""

    private fun keys(rungs: List<CastRung>) = rungs.map { it.key }

    private fun rungs(sink: String?, mimeType: String = opus, canFetchAac: Boolean = true, canDecode: Boolean = true, rate: Int = 48_000) =
        CastLadder.rungs(ProtocolInfo.support(sink), mimeType, canFetchAac, canDecode, rate)

    // The order

    @Test
    fun `a speaker that lists only L16 gets raw audio, the song's own rate first, each labelled as DLNA then plainly`() {
        val zeppelinLike = "http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM," +
            "http-get:*:audio/L16;rate=48000;channels=2:DLNA.ORG_PN=LPCM,http-get:*:audio/L8;rate=44100;channels=2:*"
        assertEquals(
            listOf("l16-48000-2:dlna", "l16-48000-2:plain", "l16-44100-2:dlna", "l16-44100-2:plain"),
            keys(rungs(zeppelinLike)),
        )
        // AAC decodes at 44.1 kHz: that rate first.
        assertEquals("l16-44100-2:dlna", rungs(zeppelinLike, mimeType = "audio/mp4", rate = 44_100).first().key)
    }

    @Test
    fun `the song as it is first, then AAC, then raw audio, then WAV`() {
        val everything = "http-get:*:audio/webm:*,http-get:*:audio/mp4:*,http-get:*:audio/L16;rate=48000;channels=2:*,http-get:*:audio/wav:*"
        assertEquals(
            listOf(
                "asis:audio/webm:dlna", "asis:audio/webm:plain",
                "aac:dlna", "aac:plain",
                "l16-48000-2:dlna", "l16-48000-2:plain",
                "wav-48000-2:dlna", "wav-48000-2:plain",
            ),
            keys(rungs(everything)),
        )
    }

    @Test
    fun `only what the speaker lists, and every form when it lists nothing`() {
        // MP4 listed, so AAC; nothing raw listed.
        assertEquals(listOf("aac:dlna", "aac:plain"), keys(rungs("http-get:*:audio/mpeg:*,http-get:*:audio/x-m4a:*")))
        // A renderer that would not say: every form, raw at both LPCM rates, CD audio's first.
        assertEquals(
            listOf(
                "asis:audio/webm:dlna", "asis:audio/webm:plain", "aac:dlna", "aac:plain",
                "l16-44100-2:dlna", "l16-44100-2:plain", "l16-48000-2:dlna", "l16-48000-2:plain",
                "wav-48000-2:dlna", "wav-48000-2:plain",
            ),
            keys(CastLadder.rungs(FormatSupport.Unknown, opus, canFetchAac = true, canDecode = true, sourceRate = 48_000)),
        )
    }

    @Test
    fun `never L8, and no rung at all for a speaker that takes nothing the song can be made into`() {
        assertEquals(emptyList<String>(), keys(rungs("http-get:*:audio/L8;rate=44100;channels=2:*")))
        assertEquals(emptyList<String>(), keys(rungs("http-get:*:audio/mpeg:*")))
        assertTrue(rungs("http-get:*:audio/L16;rate=44100;channels=2:*,http-get:*:audio/L8;rate=8000;channels=1:*").none { "l8" in it.key })
    }

    @Test
    fun `a song on the phone has no AAC rung, and without raw audio there is no raw rung`() {
        val sink = "http-get:*:audio/mp4:*,http-get:*:audio/L16;rate=44100;channels=2:*"
        assertEquals(listOf("l16-44100-2:dlna", "l16-44100-2:plain"), keys(rungs(sink, mimeType = "audio/flac", canFetchAac = false, rate = 44_100)))
        assertEquals(listOf("aac:dlna", "aac:plain"), keys(rungs(sink, canDecode = false)))
        // An AAC song goes as it is to a speaker that names AAC its own way, and is never fetched again as AAC.
        assertEquals(listOf("asis:audio/mp4:dlna", "asis:audio/mp4:plain"), keys(rungs("http-get:*:audio/aac:*", mimeType = "audio/mp4", rate = 44_100)))
    }

    @Test
    fun `two L16 rates at most, the nearest first, and a bare L16 entry at both LPCM rates, CD audio's first`() {
        val many = (listOf(8_000, 16_000, 22_050, 32_000, 44_100, 96_000)).joinToString(",") { "http-get:*:audio/L16;rate=$it;channels=2:*" }
        assertEquals(listOf("l16-44100-2:dlna", "l16-44100-2:plain", "l16-96000-2:dlna", "l16-96000-2:plain"), keys(rungs(many)))
        // A speaker that names no rate may play any L16 as CD audio, and 48 kHz slow would still
        // count as playing: 44.1 first even for a song at 48, as the cast without the ladder sends.
        assertEquals(listOf("l16-44100-2", "l16-48000-2"), rungs("http-get:*:audio/L16:*").map { it.form.key }.distinct())
        // The Zeppelin Wireless's list as the app's message showed it to Geogos (4 Nov 2026), which
        // takes rates off, so perhaps not its whole word; never L8.
        assertEquals(
            listOf("l16-44100-2:dlna", "l16-44100-2:plain", "l16-48000-2:dlna", "l16-48000-2:plain"),
            keys(rungs("http-get:*:audio/l16:*,http-get:*:audio/l8:*")),
        )
        // A rate it names comes before one guessed.
        assertEquals(listOf("l16-48000-2", "l16-44100-2"), rungs("http-get:*:audio/L16;rate=48000;channels=2:*,http-get:*:audio/L16:*").map { it.form.key }.distinct())
        // The real rate, once decoded, changes nothing for guesses.
        val bare = ProtocolInfo.support("http-get:*:audio/L16:*")
        assertEquals(
            listOf("l16-44100-2:dlna", "l16-44100-2:plain", "l16-48000-2:dlna", "l16-48000-2:plain"),
            keys(CastLadder.afterDecoding(rungs("http-get:*:audio/L16:*"), bare, 48_000 to 2, null)),
        )
    }

    @Test
    fun `the song's rate is guessed from its type until it is decoded`() {
        assertEquals(48_000, CastLadder.estimatedRate(opus))
        assertEquals(44_100, CastLadder.estimatedRate("audio/mp4"))
        assertEquals(44_100, CastLadder.estimatedRate("audio/flac"))
    }

    @Test
    fun `the rung remembered for a speaker goes first, and only when this song has it`() {
        val ladder = rungs("http-get:*:audio/L16;rate=44100;channels=2:*,http-get:*:audio/L16;rate=48000;channels=2:*")
        assertEquals(
            listOf("l16-44100-2:plain", "l16-48000-2:dlna", "l16-48000-2:plain", "l16-44100-2:dlna"),
            keys(CastLadder.startingAt(ladder, "l16-44100-2:plain")),
        )
        assertEquals(ladder, CastLadder.startingAt(ladder, "asis:audio/flac:dlna"))
        assertEquals(ladder, CastLadder.startingAt(ladder, null))
    }

    // Moving on

    private val zeppelin = rungs("http-get:*:audio/L16;rate=44100;channels=2:*,http-get:*:audio/L16;rate=48000;channels=2:*")

    @Test
    fun `a refusal tries the same form plainly next, and a plain refusal the next form`() {
        val walk = LadderWalk(zeppelin)
        assertEquals("l16-48000-2:dlna", walk.current?.key)
        assertEquals("l16-48000-2:plain", walk.next(RungAnswer.Refused("UPnP 714"))?.key)
        assertEquals("l16-44100-2:dlna", walk.next(RungAnswer.Refused("UPnP 714"))?.key)
        assertNull(walk.next(RungAnswer.Plays))
        assertEquals(3, walk.tried.size)
        assertFalse(walk.cannotReach)
    }

    @Test
    fun `a speaker that read the song and failed on it skips the same bytes labelled the other way`() {
        // A speaker that lists 48 kHz and plays only 44.1: it reads the 48 kHz audio, and fails.
        val walk = LadderWalk(zeppelin)
        assertEquals("l16-44100-2:dlna", walk.next(RungAnswer.FailedOnContent("could not play it", 131_072))?.key)
    }

    @Test
    fun `three rungs never fetched in a row end the walk, as a speaker that cannot reach the phone`() {
        val walk = LadderWalk(zeppelin)
        walk.next(RungAnswer.NeverFetched(9_000))
        walk.next(RungAnswer.NeverFetched(9_000))
        assertNull(walk.next(RungAnswer.NeverFetched(9_000)))
        assertTrue(walk.cannotReach)
        assertNull(walk.current)
        // Not when something in between was fetched.
        val other = LadderWalk(zeppelin)
        other.next(RungAnswer.NeverFetched(9_000))
        other.next(RungAnswer.Refused("fetched 2 KB and stopped"))
        assertEquals("l16-44100-2:plain", other.next(RungAnswer.NeverFetched(9_000))?.key)
        assertFalse(other.cannotReach)
    }

    @Test
    fun `every rung failed leaves nothing to try`() {
        val walk = LadderWalk(rungs("http-get:*:audio/L16;rate=44100;channels=2:*"))
        walk.next(RungAnswer.Refused("UPnP 714"))
        assertNull(walk.next(RungAnswer.Refused("UPnP 714")))
        assertFalse(walk.cannotReach)
        assertEquals(listOf("l16-44100-2:dlna", "l16-44100-2:plain"), walk.tried.map { it.first.key })
    }

    @Test
    fun `the raw rungs are put in the order of the song's real rate once it is decoded`() {
        val walk = LadderWalk(CastLadder.startingAt(zeppelin, "l16-44100-2:plain"))
        assertEquals("l16-48000-2:dlna", walk.next(RungAnswer.Refused("it forgot"))?.key)
        // A song on the phone at 44.1 kHz, not the 48 guessed: what is left is put in its order,
        // without the rung tried already.
        val raw = CastLadder.rawForms(FormatSupport.Listed(setOf("audio/l16"), zeppelinOffers), 44_100, 2)
            .flatMap { listOf(CastRung(RungForm.Raw(it), RungLabels.DLNA), CastRung(RungForm.Raw(it), RungLabels.PLAIN)) }
        walk.replaceRest(raw)
        assertEquals(listOf("l16-44100-2:plain", "l16-44100-2:dlna", "l16-48000-2:dlna", "l16-48000-2:plain"), keys(walk.rungs))
        assertEquals("l16-44100-2:dlna", walk.current?.key)
    }

    @Test
    fun `once the song is decoded the raw rungs follow its real rate, and the remembered rung stays first`() {
        // Seen on the Pixel 5, 5 Nov 2026: the rung remembered went second after the re-order.
        val walk = LadderWalk(CastLadder.startingAt(zeppelin, "l16-48000-2:plain"))
        val support = FormatSupport.Listed(setOf("audio/l16"), zeppelinOffers)
        walk.replaceRest(CastLadder.afterDecoding(walk.rungs, support, 48_000 to 2, "l16-48000-2:plain"))
        assertEquals(listOf("l16-48000-2:plain", "l16-48000-2:dlna", "l16-44100-2:dlna", "l16-44100-2:plain"), keys(walk.rungs))
        // A song that cannot be decoded keeps only its other rungs.
        val mixed = rungs("http-get:*:audio/mp4:*,http-get:*:audio/L16;rate=44100;channels=2:*")
        assertEquals(listOf("aac:dlna", "aac:plain"), keys(CastLadder.afterDecoding(mixed, ProtocolInfo.support("http-get:*:audio/mp4:*,http-get:*:audio/L16;rate=44100;channels=2:*"), null, null)))
        // A remembered raw rung of a rate the song no longer has is not forced in.
        assertEquals(listOf("l16-44100-2:dlna", "l16-44100-2:plain", "l16-48000-2:dlna", "l16-48000-2:plain"), keys(CastLadder.afterDecoding(zeppelin, support, 44_100 to 2, "l16-96000-2:plain")))
    }

    private val zeppelinOffers = listOf(PcmOffer("audio/L16;rate=44100;channels=2", 44_100, 2), PcmOffer("audio/L16;rate=48000;channels=2", 48_000, 2))

    // Judging one try

    private fun judge(
        state: ReceiverState = ReceiverState.LOADING,
        refusal: String? = null,
        error: String? = null,
        fetch: FetchStats = FetchStats(),
        playAskedAt: Long = 1_000,
        now: Long = 1_500,
    ) = RungJudge.judge(refusal, state, error, fetch, playAskedAt, now)

    @Test
    fun `a fault or an error with little read is a refusal, and one after reading the song is the content`() {
        assertEquals(RungAnswer.Refused("SetAVTransportURI was refused (714, Illegal MIME-type)"), judge(refusal = "SetAVTransportURI was refused (714, Illegal MIME-type)"))
        assertEquals(RungAnswer.Refused("It could not play it"), judge(ReceiverState.FAILED, error = "It could not play it", fetch = FetchStats(2, 4_096, 0, 1_200)))
        assertEquals(RungAnswer.FailedOnContent("It could not play it", 131_072), judge(ReceiverState.FAILED, error = "It could not play it", fetch = FetchStats(2, 131_072, 0, 1_200)))
    }

    @Test
    fun `it plays once the speaker says so and has read the song`() {
        assertEquals(RungAnswer.Plays, judge(ReceiverState.PLAYING, fetch = FetchStats(1, 65_536, 1, 1_400)))
        assertEquals(RungAnswer.Plays, judge(ReceiverState.ENDED, fetch = FetchStats(1, 900_000, 0, 1_400)))
        // A speaker that says it plays and has asked for nothing has not played the song.
        assertNull(judge(ReceiverState.PLAYING))
        assertEquals(RungAnswer.NeverFetched(9_000), judge(ReceiverState.PLAYING, now = 10_000))
    }

    @Test
    fun `a speaker told to play is given 9 seconds to ask for the song, and a paused one all the time it wants`() {
        assertNull(judge(now = 9_999))
        assertEquals(RungAnswer.NeverFetched(9_000), judge(now = 10_000))
        assertNull(judge(playAskedAt = -1, now = 1_000_000))
    }

    @Test
    fun `a fetch dropped at once is a refusal after 3 quiet seconds, and a song read and never played is given up at 25`() {
        val dropped = FetchStats(requests = 1, bodyBytes = 8_192, open = 0, lastActivityAt = 2_000)
        assertNull(judge(fetch = dropped, now = 4_999))
        assertEquals(RungAnswer.Refused("It fetched 8 KB and stopped"), judge(fetch = dropped, now = 5_000))
        // Still reading: never quiet.
        assertNull(judge(fetch = dropped.copy(open = 1), now = 20_000))
        val read = FetchStats(requests = 1, bodyBytes = 2_000_000, open = 0, lastActivityAt = 3_000)
        assertNull(judge(fetch = read, now = 25_999))
        assertEquals(RungAnswer.FailedOnContent("It read the song and never played it", 2_000_000), judge(fetch = read, now = 26_000))
    }

    // Remembering

    @Test
    fun `a speaker is known by its UDN, else by its make and model`() {
        val renderer = CastDevice(CastProtocol.DLNA, "uuid:ABC-123", "Zeppelin Wireless", "192.168.1.30", 8080, model = "Bowers & Wilkins Zeppelin Wireless")
        assertEquals("udn:uuid:abc-123", CastRungMemory.speakerOf(renderer))
        assertEquals("model:Bowers & Wilkins Zeppelin Wireless", CastRungMemory.speakerOf(renderer.copy(id = "location:http://192.168.1.30:8080/d.xml")))
        assertNull(CastRungMemory.speakerOf(renderer.copy(id = "location:http://192.168.1.30:8080/d.xml", model = null)))
    }

    @Test
    fun `the rung that played is kept on the phone, forgotten when it fails, and read back after a restart`() {
        val file = Files.createTempFile("cast-rungs", ".json").toFile().apply { delete() }
        try {
            val memory = CastRungMemory(file)
            assertNull(memory.rungFor("udn:uuid:a"))
            memory.remember("udn:uuid:a", "l16-44100-2:plain")
            memory.remember("udn:uuid:b", "asis:audio/webm:dlna")
            assertEquals("l16-44100-2:plain", CastRungMemory(file).rungFor("udn:uuid:a"))
            memory.forget("udn:uuid:a")
            assertNull(CastRungMemory(file).rungFor("udn:uuid:a"))
            assertEquals("asis:audio/webm:dlna", CastRungMemory(file).rungFor("udn:uuid:b"))
            // A file that cannot be read is an empty memory, never a crash.
            file.writeText("{not json")
            assertNull(CastRungMemory(file).rungFor("udn:uuid:b"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the memory keeps the speakers used last, fifty at most`() {
        val memory = CastRungMemory(null)
        for (i in 1..60) memory.remember("udn:uuid:$i", "aac:dlna")
        assertNull(memory.rungFor("udn:uuid:10"))
        assertEquals("aac:dlna", memory.rungFor("udn:uuid:11"))
        assertEquals("aac:dlna", memory.rungFor("udn:uuid:60"))
    }
}

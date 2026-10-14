/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class FCastTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val item = CastItem(
        mediaId = "abc",
        url = "http://192.168.1.20:47800/c/token/s/abc.webm",
        mimeType = "audio/webm; codecs=\"opus\"",
        length = 3_000_000,
        durationMs = 215_000,
        title = "Song \"One\"",
        artist = "Some Band",
        album = "An Album",
        artworkUrl = "https://lh3.example/art.jpg",
    )

    // Framing

    @Test
    fun `a frame is a little-endian size, the opcode and the body`() {
        val frame = FCast.encode(FCast.SEEK, "{\"time\":1.5}")
        val body = "{\"time\":1.5}".toByteArray()
        assertEquals(4 + 1 + body.size, frame.size)
        assertEquals(1 + body.size, (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8))
        assertEquals(0, frame[2].toInt())
        assertEquals(0, frame[3].toInt())
        assertEquals(FCast.SEEK, frame[4].toInt())
        assertArrayEquals(body, frame.copyOfRange(5, frame.size))
    }

    @Test
    fun `a message without a body is the header alone, size 1`() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, FCast.PING.toByte()), FCast.encode(FCast.PING))
    }

    @Test
    fun `sizes past 255 carry into the next byte`() {
        val body = "x".repeat(300)
        val frame = FCast.encode(FCast.PLAY, body)
        assertEquals(301 and 0xFF, frame[0].toInt() and 0xFF)
        assertEquals(301 shr 8, frame[1].toInt())
    }

    @Test
    fun `nothing over the Android receiver's 32000 is sent`() {
        FCast.encode(FCast.PLAY, "x".repeat(31_999))
        try {
            FCast.encode(FCast.PLAY, "x".repeat(32_000))
            fail("Sent a frame the receiver drops the connection for")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `frames read back as written, one after another`() {
        val bytes = FCast.encode(FCast.VERSION_MESSAGE, "{\"version\":3}") + FCast.encode(FCast.PONG) +
            FCast.encode(FCast.PLAYBACK_ERROR, "{\"message\":\"\u00DCn\u00EFcode \u2713\"}")
        val input = ByteArrayInputStream(bytes)
        val first = FCast.read(input)!!
        assertEquals(FCast.VERSION_MESSAGE, first.opcode)
        assertEquals("{\"version\":3}", first.body)
        val second = FCast.read(input)!!
        assertEquals(FCast.PONG, second.opcode)
        assertNull(second.body)
        assertEquals("{\"message\":\"\u00DCn\u00EFcode \u2713\"}", FCast.read(input)!!.body)
        assertNull(FCast.read(input))
    }

    /** A stream that hands out one byte per read, as a slow network can. */
    private class Trickle(private val bytes: ByteArray) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return -1
            b[off] = bytes[at++]
            return 1
        }
    }

    @Test
    fun `a frame that arrives a byte at a time is read whole`() {
        val frame = FCast.read(Trickle(FCast.encode(FCast.SEEK, "{\"time\":12.25}")))!!
        assertEquals(FCast.SEEK, frame.opcode)
        assertEquals("{\"time\":12.25}", frame.body)
    }

    @Test
    fun `a connection closed inside a frame is an error, not the end`() {
        val cut = FCast.encode(FCast.PLAY, "{\"container\":\"audio/webm\"}").let { it.copyOf(it.size - 3) }
        try {
            FCast.read(ByteArrayInputStream(cut))
            fail("Read a cut frame")
        } catch (e: EOFException) {
            // Expected.
        }
        try {
            FCast.read(ByteArrayInputStream(byteArrayOf(5, 0)))
            fail("Read a cut header")
        } catch (e: EOFException) {
            // Expected.
        }
    }

    @Test
    fun `a size of 0 or past the limit is refused before anything is allocated`() {
        for (header in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 0, 0, 0x7F), byteArrayOf(-1, -1, -1, -1))) {
            try {
                FCast.read(ByteArrayInputStream(header + ByteArray(8)))
                fail("Took a size of ${header.toList()}")
            } catch (e: FCastProtocolException) {
                // Expected.
            }
        }
    }

    @Test
    fun `opcodes above a receiver's version are known`() {
        assertEquals(FCast.SET_VOLUME, FCast.highestOpcode(1))
        assertEquals(FCast.PONG, FCast.highestOpcode(2))
        assertEquals(FCast.EVENT, FCast.highestOpcode(3))
    }

    // What the sender sends

    @Test
    fun `version and initial`() {
        assertEquals(3, json(FCast.versionBody()).getValue("version").jsonPrimitive.int)
        val initial = json(FCast.initialBody("Pixel", "InterTune", "0.11"))
        assertEquals("Pixel", initial.getValue("displayName").jsonPrimitive.content)
        assertEquals("InterTune", initial.getValue("appName").jsonPrimitive.content)
        assertEquals("0.11", initial.getValue("appVersion").jsonPrimitive.content)
    }

    @Test
    fun `a v3 play carries the bare type, the url, the start in seconds and the metadata`() {
        val play = json(FCast.playBody(item, item.url + "?load=1", 62_500, version = 3, volume = 0.5f))
        assertEquals("audio/webm", play.getValue("container").jsonPrimitive.content)
        assertEquals(item.url + "?load=1", play.getValue("url").jsonPrimitive.content)
        assertEquals(62.5, play.getValue("time").jsonPrimitive.double, 0.0)
        assertEquals(1.0, play.getValue("speed").jsonPrimitive.double, 0.0)
        assertEquals(0.5, play.getValue("volume").jsonPrimitive.double, 0.0)
        val metadata = play.getValue("metadata").jsonObject
        assertEquals(0, metadata.getValue("type").jsonPrimitive.int)
        assertEquals("Song \"One\" - Some Band", metadata.getValue("title").jsonPrimitive.content)
        assertEquals("https://lh3.example/art.jpg", metadata.getValue("thumbnailUrl").jsonPrimitive.content)
        // The Android receiver cannot read `custom`, and a Play it cannot read is ignored.
        assertFalse("custom" in metadata)
    }

    @Test
    fun `a v2 play has no metadata or volume, and a v1 play no speed`() {
        val v2 = json(FCast.playBody(item, item.url, 0, version = 2, volume = 0.5f))
        assertFalse("metadata" in v2)
        assertFalse("volume" in v2)
        assertTrue("speed" in v2)
        val v1 = json(FCast.playBody(item, item.url, 0, version = 1))
        assertFalse("speed" in v1)
        assertEquals(setOf("container", "url", "time"), v1.keys)
    }

    @Test
    fun `a song with no title or picture sends no metadata, and one with only a title sends it alone`() {
        val bare = item.copy(title = null, artist = null, artworkUrl = null)
        assertFalse("metadata" in json(FCast.playBody(bare, bare.url, 0, 3)))
        val titled = item.copy(artist = " ", artworkUrl = null)
        val metadata = json(FCast.playBody(titled, titled.url, 0, 3)).getValue("metadata").jsonObject
        assertEquals("Song \"One\"", metadata.getValue("title").jsonPrimitive.content)
        assertFalse("thumbnailUrl" in metadata)
    }

    @Test
    fun `seek, volume, speed and subscribe`() {
        assertEquals(90.25, json(FCast.seekBody(90_250)).getValue("time").jsonPrimitive.double, 0.0)
        assertEquals(0.0, json(FCast.seekBody(-5)).getValue("time").jsonPrimitive.double, 0.0)
        assertEquals(1.0, json(FCast.setVolumeBody(3f)).getValue("volume").jsonPrimitive.double, 0.0)
        assertEquals(0.0, json(FCast.setVolumeBody(-1f)).getValue("volume").jsonPrimitive.double, 0.0)
        assertEquals(1.5, json(FCast.setSpeedBody(1.5f)).getValue("speed").jsonPrimitive.double, 0.0)
        val subscribe = json(FCast.subscribeBody(FCast.EVENT_MEDIA_ITEM_END))
        assertEquals(1, subscribe.getValue("event").jsonObject.getValue("type").jsonPrimitive.int)
    }

    // What the receiver sends

    @Test
    fun `the Android receiver's playback update`() {
        val update = FCast.parsePlaybackUpdate(
            """{"generationTime":1758980000123,"state":1,"time":12.345,"duration":215.0,"speed":1.0,"itemIndex":null}""",
        )!!
        assertEquals(FCast.STATE_PLAYING, update.state)
        assertEquals(12_345L, update.positionMs)
        assertEquals(215_000L, update.durationMs)
        assertEquals(1f, update.speed)
        assertEquals(1758980000123L, update.generationTime)
    }

    @Test
    fun `an idle update without times, and a zero duration, read as unknown`() {
        val idle = FCast.parsePlaybackUpdate("""{"generationTime":1,"state":0}""")!!
        assertEquals(FCast.STATE_IDLE, idle.state)
        assertNull(idle.positionMs)
        assertNull(idle.durationMs)
        val error = FCast.parsePlaybackUpdate("""{"generationTime":1,"state":0,"time":0.0,"duration":0.0,"speed":0.0}""")!!
        assertEquals(0L, error.positionMs)
        assertNull(error.durationMs)
        assertNull(error.speed)
    }

    @Test
    fun `updates that make no sense are dropped, not thrown`() {
        assertNull(FCast.parsePlaybackUpdate(null))
        assertNull(FCast.parsePlaybackUpdate("not json"))
        assertNull(FCast.parsePlaybackUpdate("[1,2]"))
        assertNull(FCast.parsePlaybackUpdate("""{"time":1}"""))
        assertNull(FCast.parsePlaybackUpdate("""{"state":"playing"}"""))
        val negative = FCast.parsePlaybackUpdate("""{"state":1,"time":-3}""")!!
        assertNull(negative.positionMs)
    }

    @Test
    fun `volume, error, version, initial, event and play update`() {
        assertEquals(0.25f, FCast.parseVolume("""{"generationTime":1,"volume":0.25}"""))
        assertEquals(1f, FCast.parseVolume("""{"volume":7}"""))
        assertNull(FCast.parseVolume("{}"))

        assertEquals("Source error", FCast.parseError("""{"message":"Source error"}"""))
        assertEquals("The receiver could not play it", FCast.parseError("{}"))
        assertEquals("The receiver could not play it", FCast.parseError(null))

        assertEquals(3L, FCast.parseVersion("""{"version":3}"""))
        assertEquals(4L, FCast.parseVersion("""{"version":4}"""))
        assertNull(FCast.parseVersion("""{"version":0}"""))
        assertEquals(3L, FCast.parseVersion("""{"version":"3"}"""))
        assertNull(FCast.parseVersion("""{"version":"three"}"""))

        val info = FCast.parseInitial("""{"displayName":"Living room","appName":"FCast","appVersion":"2.0","playData":null}""")!!
        assertEquals("Living room", info.displayName)
        assertEquals("2.0", info.appVersion)

        val event = FCast.parseEvent("""{"generationTime":5,"event":{"type":1,"item":{"container":"audio/webm","url":"http://x/a?load=2"}}}""")!!
        assertEquals(FCast.EVENT_MEDIA_ITEM_END, event.type)
        assertEquals("http://x/a?load=2", event.url)
        assertNull(FCast.parseEvent("""{"generationTime":5,"event":{"type":3,"key":"Enter","repeat":false,"handled":false}}""")!!.url)
        assertNull(FCast.parseEvent("""{"event":"end"}"""))

        assertEquals("http://x/b", FCast.parsePlayUpdateUrl("""{"generationTime":1,"playData":{"container":"video/mp4","url":"http://x/b"}}"""))
        assertNull(FCast.parsePlayUpdateUrl("""{"generationTime":1}"""))
    }

    @Test
    fun `a title with quotes, backslashes and new lines stays valid JSON`() {
        val odd = item.copy(title = "a \"b\" \\ c\nd\u0001", artist = null)
        val metadata = json(FCast.playBody(odd, odd.url, 0, 3)).getValue("metadata").jsonObject
        assertEquals("a \"b\" \\ c\nd\u0001", (metadata.getValue("title") as JsonPrimitive).content)
    }
}

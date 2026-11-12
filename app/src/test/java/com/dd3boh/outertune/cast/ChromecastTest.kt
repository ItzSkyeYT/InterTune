/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON a sender sends a Chromecast, and what it makes of the device's answers. */
class ChromecastTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `our own receiver comes first when the build asks for one, Google's always last`() {
        assertEquals(listOf(Chromecast.DEFAULT_MEDIA_RECEIVER), Chromecast.receivers(own = null))
        assertEquals(listOf("1A2B3C4D", Chromecast.DEFAULT_MEDIA_RECEIVER), Chromecast.receivers(own = "1A2B3C4D"))
        assertEquals(listOf(Chromecast.DEFAULT_MEDIA_RECEIVER), Chromecast.receivers(own = Chromecast.DEFAULT_MEDIA_RECEIVER))
    }

    @Test
    fun `lyrics go to our receiver as start and text pairs, and none puts them away`() {
        val lyrics = CastLyrics("vid1", listOf(CastLyrics.Line(1_200, "One"), CastLyrics.Line(3_400, "")), shiftMs = 300)
        val shown = json(Chromecast.lyrics(lyrics))
        assertEquals("LYRICS", shown.getValue("type").jsonPrimitive.content)
        assertEquals("vid1", shown.getValue("mediaId").jsonPrimitive.content)
        assertEquals(300L, shown.getValue("shiftMs").jsonPrimitive.long)
        val lines = shown.getValue("lines").jsonArray
        assertEquals(1_200L, lines[0].jsonArray[0].jsonPrimitive.long)
        assertEquals("One", lines[0].jsonArray[1].jsonPrimitive.content)
        assertEquals("", lines[1].jsonArray[1].jsonPrimitive.content)

        val away = json(Chromecast.lyrics(null))
        assertEquals("LYRICS", away.getValue("type").jsonPrimitive.content)
        assertNull(away["mediaId"])
    }

    @Test
    fun `lyrics too long for one Cast message lose lines from the end`() {
        val long = CastLyrics("vid1", (0 until 4_000).map { CastLyrics.Line(it * 1_000L, "A line of lyrics, long enough to count, number $it") })
        val text = Chromecast.lyrics(long)
        assertTrue(text.toByteArray().size <= Chromecast.MAX_LYRICS_BYTES)
        val lines = json(text).getValue("lines").jsonArray
        assertTrue(lines.size in 500 until 4_000)
        assertEquals(0L, lines.first().jsonArray[0].jsonPrimitive.long)
    }

    @Test
    fun `the phone's name goes to our receiver as HELLO`() {
        val hello = json(Chromecast.hello("Mel's Pixel"))
        assertEquals("HELLO", hello.text("type"))
        assertEquals("Mel's Pixel", hello.text("name"))
    }

    @Test
    fun `the songs coming next go as QUEUE, three at most, each with the words and cover it has`() {
        val next = listOf(
            CastNext("One", "Band", "https://i.ytimg.com/vi/one/hq.jpg"),
            CastNext("Two", null, null),
            CastNext("Three", " ", "https://i.ytimg.com/vi/three/hq.jpg"),
            CastNext("Four", "Band", null),
        )
        val queue = json(Chromecast.queue(next))
        assertEquals("QUEUE", queue.text("type"))
        val shown = queue.getValue("next").jsonArray.map { it.jsonObject }
        assertEquals(listOf("One", "Two", "Three"), shown.map { it.text("title") })
        assertEquals("Band", shown[0].text("artist"))
        assertEquals("https://i.ytimg.com/vi/one/hq.jpg", shown[0].text("cover"))
        assertNull(shown[1]["artist"])
        assertNull(shown[1]["cover"])
        assertNull("a blank artist is left out", shown[2]["artist"])
    }

    @Test
    fun `an empty queue empties the shelf`() {
        val queue = json(Chromecast.queue(emptyList()))
        assertEquals("QUEUE", queue.text("type"))
        assertEquals(0, queue.getValue("next").jsonArray.size)
    }

    @Test
    fun `a room goes as ROOM with its name, count, first letters and QR code, and none puts it away`() {
        val qr = "data:image/png;base64,iVBORw0KGgo="
        val room = json(Chromecast.room(CastRoom("Mel's room", 3, listOf("M", "A", "J"), qr)))
        assertEquals("ROOM", room.text("type"))
        assertEquals("Mel's room", room.text("name"))
        assertEquals(3, room.getValue("count").jsonPrimitive.int)
        assertEquals(listOf("M", "A", "J"), room.getValue("people").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(qr, room.text("qr"))

        val away = json(Chromecast.room(null))
        assertEquals("ROOM", away.text("type"))
        assertNull(away["name"])
        // A room with no name is as good as none.
        assertNull(json(Chromecast.room(CastRoom(" ", 2, listOf("M"))))["name"])
    }

    @Test
    fun `a room keeps its count past the letters one message carries, and leaves out a QR code too big for it`() {
        val many = CastRoom("Big", 12, ('A'..'L').map { it.toString() }, "data:image/png;base64," + "A".repeat(Chromecast.MAX_QR_CHARS))
        val room = json(Chromecast.room(many))
        assertEquals(12, room.getValue("count").jsonPrimitive.int)
        assertEquals(CastRoom.MAX_PEOPLE, room.getValue("people").jsonArray.size)
        assertNull(room["qr"])
        assertTrue(Chromecast.room(many).toByteArray().size < 64 * 1024)
    }

    @Test
    fun `the goodbye is a type and nothing else`() {
        val bye = json(Chromecast.bye())
        assertEquals("BYE", bye.text("type"))
        assertEquals(1, bye.size)
    }

    @Test
    fun `a load says which song it is, for our receiver to match its lyrics to`() {
        val item = CastItem(mediaId = "vid7", url = "http://192.168.1.2:47800/c/t/s/vid7.webm", mimeType = "audio/webm")
        val load = json(Chromecast.load(1, "session", item, item.url, 0, autoplay = true))
        assertEquals("vid7", load.getValue("media").jsonObject.getValue("customData").jsonObject.getValue("mediaId").jsonPrimitive.content)
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private val song = CastItem(
        mediaId = "vid1",
        url = "http://192.168.1.5:47800/c/tok/s/vid1.webm",
        mimeType = "audio/webm; codecs=\"opus\"",
        length = 3_000_000,
        durationMs = 200_500,
        title = "First",
        artist = "Band",
        album = "Record",
        artworkUrl = "https://i.ytimg.com/vi/vid1/hq.jpg",
    )

    // What the sender sends

    @Test
    fun `LOAD carries the song as a music track, where to start, and whether to play`() {
        val load = json(Chromecast.load(7, "session-1", song, song.url + "?load=3", 30_500, autoplay = false))
        assertEquals("LOAD", load.text("type"))
        assertEquals(7, load.getValue("requestId").jsonPrimitive.int)
        assertEquals("session-1", load.text("sessionId"))
        assertEquals(30.5, load.getValue("currentTime").jsonPrimitive.double, 0.0)
        assertFalse(load.getValue("autoplay").jsonPrimitive.boolean)
        val media = load.getValue("media").jsonObject
        assertEquals(song.url + "?load=3", media.text("contentId"))
        assertEquals(song.url + "?load=3", media.text("contentUrl"))
        assertEquals("BUFFERED", media.text("streamType"))
        assertEquals("audio/webm", media.text("contentType"))
        assertEquals(200.5, media.getValue("duration").jsonPrimitive.double, 0.0)
        val metadata = media.getValue("metadata").jsonObject
        assertEquals(3, metadata.getValue("metadataType").jsonPrimitive.int)
        assertEquals("First", metadata.text("title"))
        assertEquals("Band", metadata.text("artist"))
        assertEquals("Record", metadata.text("albumName"))
        assertEquals(song.artworkUrl, metadata.getValue("images").jsonArray.single().jsonObject.text("url"))
    }

    @Test
    fun `LOAD leaves out what is not known`() {
        val bare = song.copy(durationMs = -1, title = " ", artist = null, album = null, artworkUrl = null)
        val load = json(Chromecast.load(1, null, bare, bare.url, -5, autoplay = true))
        assertFalse("sessionId" in load)
        assertEquals(0.0, load.getValue("currentTime").jsonPrimitive.double, 0.0)
        assertTrue(load.getValue("autoplay").jsonPrimitive.boolean)
        val media = load.getValue("media").jsonObject
        assertFalse("duration" in media)
        assertEquals(setOf("metadataType"), media.getValue("metadata").jsonObject.keys)
    }

    @Test
    fun `play, pause, stop and seek name the media session`() {
        val pause = json(Chromecast.mediaCommand(Chromecast.PAUSE, 4, 12))
        assertEquals("PAUSE", pause.text("type"))
        assertEquals(4, pause.getValue("requestId").jsonPrimitive.int)
        assertEquals(12L, pause.getValue("mediaSessionId").jsonPrimitive.long)
        val seek = json(Chromecast.seek(5, 12, 45_250))
        assertEquals("SEEK", seek.text("type"))
        assertEquals(45.25, seek.getValue("currentTime").jsonPrimitive.double, 0.0)
        assertEquals(12L, seek.getValue("mediaSessionId").jsonPrimitive.long)
        // No resumeState: a seek leaves the song playing or paused as it was.
        assertFalse("resumeState" in seek)
    }

    @Test
    fun `the device's own messages`() {
        val connect = json(Chromecast.connect("InterTune/0.11"))
        assertEquals("CONNECT", connect.text("type"))
        assertEquals("InterTune/0.11", connect.text("userAgent"))
        assertEquals(0, connect.getValue("connType").jsonPrimitive.int)
        assertEquals("""{"type":"CLOSE"}""", Chromecast.close())
        assertEquals("""{"type":"PING"}""", Chromecast.ping())
        assertEquals("""{"type":"PONG"}""", Chromecast.pong())
        val launch = json(Chromecast.launch(2))
        assertEquals("LAUNCH", launch.text("type"))
        assertEquals("CC1AD845", launch.text("appId"))
        val stop = json(Chromecast.stopApp(9, "abc"))
        assertEquals("STOP", stop.text("type"))
        assertEquals("abc", stop.text("sessionId"))
        assertEquals("""{"type":"GET_STATUS","requestId":3}""", Chromecast.getStatus(3))
    }

    @Test
    fun `volume goes as a level in three decimals, or a mute`() {
        val level = json(Chromecast.setVolume(1, level = 0.3f)).getValue("volume").jsonObject
        assertEquals("0.3", level.getValue("level").jsonPrimitive.content)
        assertFalse("muted" in level)
        assertEquals(1.0, json(Chromecast.setVolume(1, level = 4f)).getValue("volume").jsonObject.getValue("level").jsonPrimitive.double, 0.0)
        val mute = json(Chromecast.setVolume(2, muted = false)).getValue("volume").jsonObject
        assertEquals(setOf("muted"), mute.keys)
    }

    // What the device sends

    @Test
    fun `a device's status, with its apps and its volume`() {
        val payload = Chromecast.parse(
            """{"requestId":3,"status":{"applications":[{"appId":"CC1AD845","displayName":"Default Media Receiver",
            "isIdleScreen":false,"launchedFromCloud":false,"namespaces":[{"name":"urn:x-cast:com.google.cast.media"}],
            "sessionId":"7e2b","statusText":"Ready To Cast","transportId":"web-4","universalAppId":"CC1AD845"},
            {"appId":"E8C28D3C","displayName":"Backdrop","isIdleScreen":true,"sessionId":"b1","transportId":"web-1"}],
            "userEq":{},"volume":{"controlType":"attenuation","level":0.35,"muted":false,"stepInterval":0.05}},
            "type":"RECEIVER_STATUS"}""",
        )!!
        assertEquals("RECEIVER_STATUS", payload.type)
        assertEquals(3, payload.requestId)
        val device = Chromecast.parseDevice(payload.body)!!
        assertEquals(2, device.apps.size)
        assertEquals(Chromecast.App("CC1AD845", "7e2b", "web-4", "Default Media Receiver", false), device.apps[0])
        assertTrue(device.apps[1].isIdleScreen)
        assertEquals(0.35f, device.level)
        assertEquals(false, device.muted)
        assertFalse(device.fixedVolume)
    }

    @Test
    fun `a device with nothing running, and one whose volume is its remote's alone`() {
        val device = Chromecast.parseDevice(json("""{"type":"RECEIVER_STATUS","status":{"volume":{"controlType":"fixed","level":1.0,"muted":true}}}"""))!!
        assertTrue(device.apps.isEmpty())
        assertTrue(device.fixedVolume)
        assertEquals(true, device.muted)
        assertNull(Chromecast.parseDevice(json("""{"type":"RECEIVER_STATUS"}""")))
    }

    @Test
    fun `a song's status, in milliseconds`() {
        val sessions = Chromecast.parseMedia(
            json(
                """{"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":3,"playbackRate":1,
                "playerState":"playing","currentTime":12.345,"supportedMediaCommands":12303,
                "volume":{"level":1,"muted":false},"media":{"contentId":"http://a/s.webm?load=2","streamType":"BUFFERED",
                "contentType":"audio/webm","duration":200.5}}]}""",
            ),
        )
        assertEquals(Chromecast.Media(3, "PLAYING", null, 12_345, 200_500, 1f, "http://a/s.webm?load=2"), sessions.single())
    }

    @Test
    fun `a status without the song's details, at its end, or empty`() {
        val ended = Chromecast.parseMedia(json("""{"status":[{"mediaSessionId":3,"playerState":"IDLE","idleReason":"FINISHED"}]}""")).single()
        assertEquals("IDLE", ended.playerState)
        assertEquals("FINISHED", ended.idleReason)
        assertNull(ended.positionMs)
        assertNull(ended.durationMs)
        assertNull(ended.contentId)
        assertTrue(Chromecast.parseMedia(json("""{"type":"MEDIA_STATUS","status":[]}""")).isEmpty())
        // A session without a number is nothing to go by.
        assertTrue(Chromecast.parseMedia(json("""{"status":[{"playerState":"PLAYING"}]}""")).isEmpty())
        val contentUrl = Chromecast.parseMedia(json("""{"status":[{"mediaSessionId":1,"media":{"contentId":"id","contentUrl":"http://u"}}]}""")).single()
        assertEquals("http://u", contentUrl.contentId)
    }

    @Test
    fun `why a request failed`() {
        assertEquals("error 104", Chromecast.reasonOf(json("""{"type":"LOAD_FAILED","requestId":4,"detailedErrorCode":104}""")))
        assertEquals("NOT_ALLOWED", Chromecast.reasonOf(json("""{"type":"LAUNCH_ERROR","reason":"NOT_ALLOWED"}""")))
        assertEquals("INVALID_MEDIA_SESSION_ID, 905", Chromecast.reasonOf(json("""{"reason":"INVALID_MEDIA_SESSION_ID","detailedErrorCode":905}""")))
        assertNull(Chromecast.reasonOf(json("""{"type":"LOAD_CANCELLED"}""")))
    }

    @Test
    fun `what cannot be read is null, and a message without a requestId answers nothing`() {
        assertNull(Chromecast.parse(null))
        assertNull(Chromecast.parse("not json"))
        assertNull(Chromecast.parse("[1,2]"))
        val ping = Chromecast.parse("""{"type":"PING"}""")!!
        assertEquals("PING", ping.type)
        assertEquals(0, ping.requestId)
    }

    @Test
    fun `our own receiver's presses are read as requests, and nothing else is`() {
        fun asked(text: String) = Chromecast.request(Chromecast.parse(text)!!)
        assertEquals(RemoteRequest.Next, asked("""{"type":"NEXT"}"""))
        assertEquals(RemoteRequest.Previous, asked("""{"type":"PREVIOUS"}"""))
        assertEquals(RemoteRequest.Repeat(RemoteRepeat.ONE), asked("""{"type":"REPEAT","mode":"REPEAT_SINGLE"}"""))
        assertEquals(RemoteRequest.Repeat(RemoteRepeat.ALL), asked("""{"type":"REPEAT","mode":"REPEAT_ALL_AND_SHUFFLE"}"""))
        assertEquals(RemoteRequest.Repeat(RemoteRepeat.OFF), asked("""{"type":"REPEAT","mode":"REPEAT_OFF"}"""))
        assertEquals(RemoteRequest.Shuffle(true), asked("""{"type":"SHUFFLE","on":true}"""))
        // What is not understood is not acted on.
        assertNull(asked("""{"type":"REPEAT","mode":"REPEAT_SOMETIMES"}"""))
        assertNull(asked("""{"type":"SHUFFLE"}"""))
        assertNull(asked("""{"type":"MEDIA_STATUS"}"""))
        assertEquals("urn:x-cast:dev.skye.intertune", Chromecast.NS_INTERTUNE)
    }
}

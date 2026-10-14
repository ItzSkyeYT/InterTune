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

/** Times, protocolInfo and DIDL-Lite as renderers want them. */
class DlnaTest {

    // Times

    @Test
    fun `times in the forms renderers send`() {
        assertEquals(0L, DlnaTime.parse("0:00:00"))
        assertEquals(83_000L, DlnaTime.parse("0:01:23"))
        assertEquals(3_723_000L, DlnaTime.parse("1:02:03"))
        assertEquals(3_723_000L, DlnaTime.parse("01:02:03"))
        assertEquals(83_500L, DlnaTime.parse("0:01:23.5"))
        assertEquals(83_123L, DlnaTime.parse("0:01:23.123"))
        assertEquals(83_123L, DlnaTime.parse("0:01:23.1234"))
        assertEquals(83_250L, DlnaTime.parse("0:01:23.1/4"))
        assertEquals(360_000_000L, DlnaTime.parse("100:00:00"))
        assertEquals(5_000L, DlnaTime.parse("+0:00:05"))
        // Hours dropped, or seconds alone.
        assertEquals(83_000L, DlnaTime.parse("01:23"))
        assertEquals(42_000L, DlnaTime.parse("42"))
        assertEquals(83_000L, DlnaTime.parse(" 0:01:23 "))
    }

    @Test
    fun `times that are missing or not implemented are unknown, not zero`() {
        assertNull(DlnaTime.parse(null))
        assertNull(DlnaTime.parse(""))
        assertNull(DlnaTime.parse("NOT_IMPLEMENTED"))
        assertNull(DlnaTime.parse("not_implemented"))
        assertNull(DlnaTime.parse("abc"))
        assertNull(DlnaTime.parse("1:2:3:4"))
        assertNull(DlnaTime.parse("0:-1:00"))
        assertNull(DlnaTime.parse("0:00:01.5/0"))
        assertNull(DlnaTime.parse("0:00:01.x"))
    }

    @Test
    fun `times written, whole for a seek and with milliseconds for a duration`() {
        assertEquals("0:00:00", DlnaTime.format(0))
        assertEquals("0:00:00", DlnaTime.format(-5))
        assertEquals("0:01:23", DlnaTime.format(83_999))
        assertEquals("1:02:03", DlnaTime.format(3_723_000))
        assertEquals("27:46:40", DlnaTime.format(100_000_000))
        assertEquals("0:03:45.000", DlnaTime.format(225_000, millis = true))
        assertEquals("0:00:01.050", DlnaTime.format(1_050, millis = true))
        for (ms in listOf(0L, 1_000L, 59_000L, 3_599_000L, 3_600_000L, 86_399_000L)) {
            assertEquals(ms, DlnaTime.parse(DlnaTime.format(ms)))
        }
    }

    // protocolInfo

    @Test
    fun `a sink list is split into its entries`() {
        val entries = ProtocolInfo.parse(
            "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:DLNA.ORG_PN=AAC_ISO_320,rtsp-rtp-udp:*:audio/L16:*",
        )
        assertEquals(3, entries.size)
        assertEquals(ProtocolInfo.Entry("http-get", "*", "audio/mpeg", "*"), entries[0])
        assertEquals("DLNA.ORG_PN=AAC_ISO_320", entries[1].additionalInfo)
        assertEquals("rtsp-rtp-udp", entries[2].protocol)
    }

    @Test
    fun `an escaped comma stays inside its entry, and junk entries are dropped`() {
        val entries = ProtocolInfo.parse(" http-get:*:audio/mpeg:A\\,B , ,nonsense, http-get:*:audio/flac:* ,")
        assertEquals(listOf("A,B", "*"), entries.map { it.additionalInfo })
    }

    @Test
    fun `what the renderer plays over HTTP, without parameters`() {
        val support = ProtocolInfo.support(
            "http-get:*:audio/mpeg:*,http-get:*:AUDIO/MP4;codecs=mp4a:*,rtsp-rtp-udp:*:audio/webm:*",
        )
        assertEquals(FormatSupport.Listed(setOf("audio/mpeg", "audio/mp4")), support)
    }

    @Test
    fun `a wildcard, an empty list or no HTTP at all says nothing`() {
        assertEquals(FormatSupport.Unknown, ProtocolInfo.support("http-get:*:*:*"))
        assertEquals(FormatSupport.Unknown, ProtocolInfo.support("http-get:*:audio/mpeg:*,http-get:*:audio/*:*"))
        assertEquals(FormatSupport.Unknown, ProtocolInfo.support(""))
        assertEquals(FormatSupport.Unknown, ProtocolInfo.support(null))
        assertEquals(FormatSupport.Unknown, ProtocolInfo.support("rtsp-rtp-udp:*:audio/mpeg:*"))
    }

    // DIDL-Lite

    private val item = CastItem(
        mediaId = "dQw4w9WgXcQ",
        url = "http://192.168.1.20:47800/c/abcd/s/dQw4w9WgXcQ.m4a?x=1&y=2",
        mimeType = "audio/mp4",
        length = 3_456_789,
        durationMs = 213_500,
        title = "Rock & Roll <Live>",
        artist = "AC/DC \"Band\"",
        album = "Album's",
        artworkUrl = "https://i.ytimg.com/vi/x/hq.jpg?a=1&b=2",
    )

    @Test
    fun `a track's metadata is well-formed and says everything once`() {
        val didl = Didl.track(item)
        val root = UpnpXml.parse(didl)!!
        assertEquals("DIDL-Lite", UpnpXml.localName(root))
        val track = UpnpXml.child(root, "item")!!
        assertEquals("dQw4w9WgXcQ", track.getAttribute("id"))
        assertEquals("Rock & Roll <Live>", UpnpXml.childText(track, "title"))
        assertEquals("AC/DC \"Band\"", UpnpXml.childText(track, "artist"))
        assertEquals("AC/DC \"Band\"", UpnpXml.childText(track, "creator"))
        assertEquals("Album's", UpnpXml.childText(track, "album"))
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg?a=1&b=2", UpnpXml.childText(track, "albumArtURI"))
        assertEquals("object.item.audioItem.musicTrack", UpnpXml.childText(track, "class"))
        val res = UpnpXml.child(track, "res")!!
        assertEquals(item.url, res.textContent)
        assertEquals("3456789", res.getAttribute("size"))
        assertEquals("0:03:33.500", res.getAttribute("duration"))
        assertEquals(
            "http-get:*:audio/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            res.getAttribute("protocolInfo"),
        )
        assertEquals("urn:schemas-upnp-org:metadata-1-0/upnp/", UpnpXml.child(track, "class")!!.namespaceURI)
    }

    @Test
    fun `a track without a length cannot promise byte seeking, and one without a title is named by its id`() {
        val bare = item.copy(length = -1, durationMs = -1, title = null, artist = null, album = null, artworkUrl = null)
        val track = UpnpXml.child(UpnpXml.parse(Didl.track(bare))!!, "item")!!
        assertEquals("dQw4w9WgXcQ", UpnpXml.childText(track, "title"))
        assertNull(UpnpXml.child(track, "artist"))
        val res = UpnpXml.child(track, "res")!!
        assertFalse(res.hasAttribute("size"))
        assertFalse(res.hasAttribute("duration"))
        assertTrue(res.getAttribute("protocolInfo").contains("DLNA.ORG_OP=00"))
    }
}

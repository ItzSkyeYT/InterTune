/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.NetworkPatience
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * On a very weak Wi-Fi signal a large "YouTube can't be reached ... if you use a VPN" note came up
 * with no VPN on, over a phone playing local songs (report of 25 Nov 2026). The patience of the
 * requests, the count of failures before the note, and the note's own words are what changed.
 */
class WeakNetworkTest {
    private fun source(path: String): String =
        listOf("src/main/java/", "app/src/main/java/", "../innertube/src/main/java/", "innertube/src/main/java/")
            .map { File(it + path) }.first { it.exists() }.readText()

    @Test
    fun `a weak network waits longer than a good one`() {
        assertTrue(NetworkPatience.connectTimeoutMs(true) > NetworkPatience.connectTimeoutMs(false))
        assertTrue(NetworkPatience.socketTimeoutMs(true) > NetworkPatience.socketTimeoutMs(false))
        assertTrue(NetworkPatience.requestTimeoutMs(true) > NetworkPatience.requestTimeoutMs(false))
        assertEquals(15_000L, NetworkPatience.connectTimeoutMs(false))
    }

    @Test
    fun `weak means a slow link, a signal at the edge or a metered connection`() {
        assertTrue(NetworkPatience.isWeak(downstreamKbps = 1_000, signalDbm = null, metered = false))
        assertTrue(NetworkPatience.isWeak(downstreamKbps = 100_000, signalDbm = -85, metered = false))
        assertTrue(NetworkPatience.isWeak(downstreamKbps = null, signalDbm = null, metered = true))
        assertFalse(NetworkPatience.isWeak(downstreamKbps = 100_000, signalDbm = -55, metered = false))
        assertFalse(NetworkPatience.isWeak(downstreamKbps = null, signalDbm = null, metered = false))
    }

    @Test
    fun `the pause grows between tries`() {
        assertEquals(1_000L, NetworkPatience.backoffMs(1))
        assertEquals(2_000L, NetworkPatience.backoffMs(2))
    }

    @Test
    fun `a connection never made is asked again, whatever the request`() {
        assertTrue(NetworkPatience.shouldRetry(ConnectTimeoutException("c"), "/youtubei/v1/like/like", 0))
        assertTrue(NetworkPatience.shouldRetry(UnknownHostException("music.youtube.com"), "/youtubei/v1/like/like", 1))
        assertTrue(NetworkPatience.shouldRetry(RuntimeException("wrapped", UnknownHostException("x")), "/youtubei/v1/browse", 1))
    }

    @Test
    fun `a wait that ran out is asked again once, and only for a read`() {
        assertTrue(NetworkPatience.shouldRetry(SocketTimeoutException("t"), "/youtubei/v1/search", 0))
        assertTrue(NetworkPatience.shouldRetry(SocketTimeoutException("t"), "/youtubei/v1/browse", 0))
        assertFalse(NetworkPatience.shouldRetry(SocketTimeoutException("t"), "/youtubei/v1/search", 1))
        assertFalse(NetworkPatience.shouldRetry(SocketTimeoutException("t"), "/youtubei/v1/like/like", 0))
        assertFalse(NetworkPatience.shouldRetry(SocketTimeoutException("t"), "/youtubei/v1/browse/edit_playlist", 0))
        assertFalse(NetworkPatience.shouldRetry(SocketTimeoutException("t"), "/youtubei/v1/player", 0))
        assertFalse(NetworkPatience.shouldRetry(IllegalStateException("not the network"), "/youtubei/v1/search", 0))
    }

    @Test
    fun `the client asks again and the weak network's waits are set on each request`() {
        val inner = source("com/zionhuang/innertube/InnerTube.kt")
        assertTrue("HttpRequestRetry" in inner)
        assertTrue("NetworkPatience.weak" in inner)
    }

    @Test
    fun `the VPN is named only when a VPN is on`() {
        val note = source("com/dd3boh/outertune/ui/component/WhyStoppedNote.kt")
        assertTrue("VpnState.active(" in note)
        val en = File("src/main/res/values/strings-ot.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/values/strings-ot.xml")
        val strings = en.readText()
        val plain = strings.substringAfter("name=\"why_stopped_unreachable_body_plain\">").substringBefore("</string>")
        assertFalse("VPN" in plain)
        assertTrue("VPN" in strings.substringAfter("name=\"why_stopped_unreachable_body\">").substringBefore("</string>"))
        assertTrue("TRANSPORT_VPN" in source("com/dd3boh/outertune/utils/VpnState.kt"))
    }

    @Test
    fun `Home says it in one line, and not at all while what plays is on the phone`() {
        val note = source("com/dd3boh/outertune/ui/component/WhyStoppedNote.kt")
        val home = note.substringAfter("fun WhyStoppedNoteIfAny(").substringBefore("internal fun NoteCard")
        assertTrue("UnreachableLine(" in home)
        assertTrue("rememberPlayingOnPhone()" in home)
        assertTrue("song.isLocal || song.id in downloads" in home)
        // The big card stays for the sign-in on Home, not for the connection.
        assertFalse("WhyStoppedNote(cause, onSignIn, onRetry = onRetry" in home)
    }
}

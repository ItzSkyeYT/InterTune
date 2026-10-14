/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.ParserException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class BadCachedCopyTest {
    private val dropped = mutableListOf<String>()
    private var online = true
    private val cached = mutableSetOf("song")
    private val doubt = BadCachedCopy(isCached = { it in cached }, online = { online }, drop = { dropped += it })
    private val unreadable = ParserException.createForMalformedContainer("bad element", null)

    @Test
    fun `a copy the player gave up on for its bytes goes at once`() {
        doubt.playerFailed("song", RuntimeException("Source error", unreadable))
        assertEquals(listOf("song"), dropped)
    }

    @Test
    fun `while the player keeps retrying, the copy goes on the third failure in a row`() {
        repeat(BadCachedCopy.TRIES - 1) { doubt.loadFailed("song", EOFException()) }
        assertTrue(dropped.isEmpty())
        doubt.loadFailed("song", EOFException())
        assertEquals(listOf("song"), dropped)
    }

    @Test
    fun `a load that goes through forgives what failed before it`() {
        repeat(BadCachedCopy.TRIES - 1) { doubt.loadFailed("song", EOFException()) }
        doubt.loadCompleted("song")
        doubt.loadFailed("song", EOFException())
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `the network's failures never cost a copy, nor does anything while offline`() {
        repeat(5) { doubt.loadFailed("song", RuntimeException(SocketTimeoutException())) }
        doubt.playerFailed("song", UnknownHostException())
        assertTrue(dropped.isEmpty())
        online = false
        repeat(5) { doubt.loadFailed("song", EOFException()) }
        doubt.playerFailed("song", unreadable)
        assertTrue("offline the copy is the only way the song plays", dropped.isEmpty())
    }

    @Test
    fun `nothing is dropped for a song with nothing cached`() {
        cached.clear()
        repeat(5) { doubt.loadFailed("song", EOFException()) }
        doubt.playerFailed("song", unreadable)
        assertTrue(dropped.isEmpty())
        assertFalse(BadCachedCopy.isNetwork(unreadable))
    }
}

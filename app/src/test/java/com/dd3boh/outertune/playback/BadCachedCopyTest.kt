/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
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

    @Test
    fun `a song whose copy was what failed goes on, once, until a load of it goes through`() {
        assertTrue(doubt.playerFailed("song", unreadable))
        assertEquals(listOf("song"), dropped)
        // Its bytes failed again, from YouTube this time: it stops as it always did.
        assertFalse(doubt.playerFailed("song", unreadable))
        doubt.loadCompleted("song")
        assertTrue("once it has played through, a later bad copy is gone past again", doubt.playerFailed("song", unreadable))
    }

    @Test
    fun `nothing goes on that the network stopped, or that had no copy to blame`() {
        assertFalse(doubt.playerFailed("song", UnknownHostException()))
        assertFalse(doubt.playerFailed("other", unreadable))
        online = false
        assertFalse(doubt.playerFailed("song", unreadable))
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `a song's fetch that got no answer mid-song is the network's, whatever ended the request`() {
        // The player tries such a fetch again on its own a few seconds apart, and three of them
        // in a row took the copy that was playing for a broken one.
        val unanswered = Recovery.ResolveNoAnswer(PlaybackException("Unknown error", EOFException("unexpected end of stream"), PlaybackException.ERROR_CODE_REMOTE_ERROR))
        repeat(5) { doubt.loadFailed("song", unanswered) }
        doubt.playerFailed("song", unanswered)
        assertTrue(dropped.isEmpty())
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #271 (0.12.11 on a Xiaomi, Android 16, 25 Nov 2026): "Too many PendingIntent created", and the player's service died
 * mid-song. media3 gives every custom button of the notification (like, shuffle, radio...) a new request code each time
 * it builds the notification (1.8.0 counts up, later versions pick one at random), so each update left a new
 * PendingIntent per button behind. Stock Android only warns past 5000; HyperOS refuses the next one. The app now makes
 * each button's PendingIntent once per session and gives the same one again on every update.
 */
class OncePerButtonTest {

    private class Made(val n: Int)

    @Test
    fun `the same button of the same session is made once, however often the notification is built`() {
        val once = OncePerButton<Any, Made>()
        val session = Any()
        var made = 0
        val first = once.get(session, "like") { Made(++made) }
        repeat(500) { assertSame(first, once.get(session, "like") { Made(++made) }) }
        assertEquals(1, made)
    }

    @Test
    fun `each button has its own`() {
        val once = OncePerButton<Any, Made>()
        val session = Any()
        val like = once.get(session, "like") { Made(1) }
        val shuffle = once.get(session, "shuffle") { Made(2) }
        assertNotSame(like, shuffle)
        assertSame(like, once.get(session, "like") { Made(3) })
    }

    @Test
    fun `a new session starts afresh, the old one's address would lead nowhere`() {
        val once = OncePerButton<Any, Made>()
        val old = once.get(Any(), "like") { Made(1) }
        val fresh = once.get(Any(), "like") { Made(2) }
        assertNotSame(old, fresh)
        assertEquals(2, fresh.n)
    }

    @Test
    fun `buttons whose extras keep changing cannot pile up`() {
        val once = OncePerButton<Any, Made>()
        val session = Any()
        var made = 0
        repeat(10_000) { i -> once.get(session, "seek{at=$i}") { Made(++made) } }
        assertTrue("kept ${once.size}", once.size <= OncePerButton.MOST)
    }

    @Test
    fun `the key is the action and its extras, in any order`() {
        assertEquals("like", OncePerButton.key("like", emptyMap()))
        assertEquals(OncePerButton.key("radio", mapOf("a" to 1, "b" to "x")), OncePerButton.key("radio", mapOf("b" to "x", "a" to 1)))
        assertTrue(OncePerButton.key("radio", mapOf("a" to 1)) != OncePerButton.key("radio", mapOf("a" to 2)))
    }

    private val source = File("src/main/java/com/dd3boh/outertune/playback/PlainPlayNotifications.kt").readText()

    @Test
    fun `both kinds of custom button go through it, and it lives as long as the notification's provider`() {
        val custom = source.substringAfter("override fun createCustomAction(").substringBefore("override fun createMediaActionPendingIntent(")
        assertEquals(custom, 2, Regex("""buttons\.get\(mediaSession, """).findAll(custom).count())
        // Made once with the provider, not with each notification, or nothing would be kept between two updates.
        assertTrue(source, "private val buttons = OncePerButton<MediaSession, PendingIntent>()" in source)
        assertTrue(source, "PlainPlayActions(service, actionFactory, buttons)" in source)
    }
}

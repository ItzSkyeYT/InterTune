/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the listener does by hand is theirs at once (26 Nov 2026). For eight seconds after the room moved a guest
 * (mine's window), its own Next, Previous, a song tapped in the queue or a list, or a seek with everyone allowed to,
 * was taken for the room's own move: the next look put the room's song back and gave itself eight seconds more. Now a
 * command asked of the app's player (the screens, the notification, a headset, a car, a watch) is marked as the
 * listener's where it comes in, and only the room's own moves, made on the service's own player, fall in the window.
 * This reads the source where a service, its player and a room would be needed.
 */
class RoomByHandTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()
    private val service = File(src, "playback/MusicService.kt").readText()
    private val connection = File(src, "playback/PlayerConnection.kt").readText()
    private val routed = File(src, "playback/RoutedPlayer.kt").readText()

    /** The text of the function that starts with [signature]: from its opening brace to the one that closes it. */
    private fun body(text: String, signature: String): String {
        val from = text.indexOf(signature)
        assertTrue("no $signature", from >= 0)
        val open = text.indexOf('{', from)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("no end to $signature")
    }

    @Test
    fun `a change made by hand is the listener's whatever the room did a moment before`() {
        // Each of the player's changes the session hears asks whether this turn is one a command by hand came in.
        assertTrue("private fun listenerChose(what: String, pressed: Boolean = handTurn) {" in session)
        assertTrue("(!pressed && System.currentTimeMillis() < ownCommandUntil)" in body(session, "private fun listenerChose("))
        val hand = body(session, "fun byHand()")
        assertTrue(hand, "handTurn = true" in hand)
        // Over at the end of the turn, before anything else the main thread does, the room's next look included.
        assertTrue(hand, "postAtFrontOfQueue { handTurn = false }" in hand)
    }

    @Test
    fun `every seek, skip and list asked of the app's player is by hand, never the service's own moves`() {
        // The screens and the session (notification, headset, car, watch) all ask the routed player.
        assertTrue(body(routed, "override fun handleSeek(").contains("onAsked()"))
        assertTrue(body(routed, "override fun handleSetMediaItems(").contains("onAsked()"))
        assertTrue("routed.onAsked = ::byHand" in service)
        assertTrue("together.value?.byHand()" in body(service, "fun byHand()"))
        // And the player the screens act on in the same tap, after a cold start too.
        val load = connection.substringAfter("    fun loadQueueIfEmpty(): Player {").substringBefore("\n    }")
        assertTrue(load, load.indexOf("service.byHand()") in 0 until load.indexOf("return service.activePlayer"))
        // The room's own moves are made on the service's own player, never through the routed one.
        for (move in listOf("private fun toLeadersSong(", "private fun matchQueue(", "private fun seek(", "private fun arrive(")) {
            assertFalse(move, "service.player." in body(session, move))
        }
    }

    @Test
    fun `a song or a list started here takes a guest its own way at once, before it has loaded`() {
        val play = body(connection, "fun playQueue(")
        assertTrue(play, play.indexOf("service.together.value?.listenerStarts()") in 0 until play.indexOf("service.playQueue("))
        assertTrue("listenerChose(\"a song of their own\", pressed = true)" in body(session, "fun listenerStarts()"))
    }
}

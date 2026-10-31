/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every unlock brings a new controller and with it a new PlayerConnection. The one before must stop
 * listening to the player, or each lock and unlock left one more listener rebuilding the queue on
 * every change (found 30 Oct 2026 while chasing the queue's flash on unlock).
 */
class PlayerConnectionDisposedTest {
    private val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    @Test
    fun `the connection before is disposed when a new controller comes`() {
        val callback = main.substringAfter("controllerViewModel.addControllerCallback(lifecycle) { controller, _ ->").substringBefore("\n        }")
        val dispose = callback.indexOf("playerConnection?.dispose()")
        val make = callback.indexOf("playerConnection = PlayerConnection(")
        assertTrue("no dispose before the new connection", dispose in 0 until make)
    }

    @Test
    fun `the connection is disposed when the activity goes`() {
        val destroy = main.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()")
        assertTrue(destroy.indexOf("playerConnection?.dispose()") in 0 until destroy.indexOf("playerConnection = null"))
    }

    @Test
    fun `dispose takes the listener off the player`() {
        val connection = File("src/main/java/com/dd3boh/outertune/playback/PlayerConnection.kt").readText()
        assertTrue(connection.substringAfter("fun dispose()").substringBefore("}").contains("player.removeListener(this)"))
    }
}

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
 * What the cast session does with the screens of InterTune's own receiver, read from its source:
 * a JVM test has neither the main thread nor the service. What each send does once called is the
 * link's (ChromecastLinkTest); this is that the session calls it, and in the right order.
 */
class CastSessionSourceTest {
    private val source = File("src/main/java/com/dd3boh/outertune/playback/CastSession.kt").readText()

    private fun after(text: String, marker: String): String = text.substringAfter(marker, "").also { assertTrue("$marker is not in CastSession", it.isNotEmpty()) }

    @Test
    fun `the phone's name is sent as soon as the receiver is connected, before the first song is handed over`() {
        val connected = after(source, "link.connect()")
        val hello = connected.indexOf("link.hello(")
        val handOver = connected.indexOf("service.handOverTo(")
        assertTrue("hello at $hello, hand over at $handOver", hello in 0 until handOver)
        assertTrue(source.contains("""tell("The phone's name") { link.hello(phoneName()) }"""))
    }

    @Test
    fun `the phone's name is the one in the system settings, or the model`() {
        val body = after(source, "private fun phoneName()").substringBefore("\n    }\n")
        assertTrue(body, "Settings.Global.DEVICE_NAME" in body && "Build.MODEL" in body && "PhoneName.of(" in body)
    }

    @Test
    fun `every send to the screens is made through tell, which logs a failure and never stops the cast`() {
        assertTrue(source.contains("""tell("The queue") { link.showQueue(next) }"""))
        assertTrue(source.contains("""tell("The room") { link.showRoom(room) }"""))
        val body = after(source, "private suspend fun tell(").substringBefore("\n    }\n")
        assertTrue(body, "CancellationException" in body && "Log.w(TAG" in body)
    }

    @Test
    fun `the queue and the room are followed for as long as the cast lasts, and let go of when it ends`() {
        assertTrue(source.contains("queueJob = scope.launch { followQueue(link, remote) }"))
        assertTrue(source.contains("roomJob = scope.launch { followRoom(link) }"))
        val end = after(source, "private fun end(playHere: Boolean?)")
        assertTrue(end.contains("queueJob?.cancel()") && end.contains("roomJob?.cancel()"))
    }

    @Test
    fun `a cast ends with the goodbye first, then the song's stop, off the main thread`() {
        val end = after(source, "private fun end(playHere: Boolean?)")
        val closing = after(end, "closing.launch {").substringBefore("\n            }\n")
        val bye = closing.indexOf("CastFarewell.to(link)")
        val stop = closing.indexOf("link.stop()")
        assertTrue("goodbye at $bye, stop at $stop: $closing", bye in 0 until stop)
        // `closing` runs on IO, which is where a socket may be written and a wait is harmless.
        assertTrue(source.contains("private val closing = CoroutineScope(SupervisorJob() + Dispatchers.IO)"))
    }
}

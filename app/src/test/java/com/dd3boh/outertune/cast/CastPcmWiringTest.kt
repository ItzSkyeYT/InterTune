/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Raw audio goes to a renderer only with Unreleased.CAST_PCM, which is held to debug and preview
 * builds, and is worked out on the worker that talks to the renderer, never the main thread. Read
 * from the source, as ReleasedFlagsTest does, because a flag is a constant of the build.
 */
class CastPcmWiringTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence is not taken for code. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the flag is held back to debug and preview builds`() {
        assertTrue(Regex("""\bval CAST_PCM = BuildConfig\.PREVIEW_FEATURES\s*$""", RegexOption.MULTILINE).containsMatchIn(code("constants/Unreleased.kt")))
    }

    @Test
    fun `the remote player and the probe choose raw audio only with the flag`() {
        for (path in listOf("playback/RemotePlayer.kt", "playback/CastProbe.kt")) {
            val text = code(path)
            assertTrue(path, "canDecode = Unreleased.CAST_PCM" in text)
            assertEquals(path, 1, Regex("""canDecode =""").findAll(text).count())
        }
    }

    @Test
    fun `the server is given a decoder only with the flag`() {
        assertTrue("pcmDecoder = if (Unreleased.CAST_PCM) MediaCodecPcmDecoder() else null" in code("playback/MusicService.kt"))
        val constructed = sources.walkTopDown().filter { it.extension == "kt" && "MediaCodecPcmDecoder()" in code(it.relativeTo(sources).path) }
            .map { it.relativeTo(sources).path }.sorted().toList()
        // The other is the conversion of downloads and exports to another format (Unreleased.DOWNLOAD_FORMATS): the same decoder,
        // asked for there by DownloadFormats.effective and never given to the cast server.
        assertEquals(listOf("playback/DownloadUtil.kt", "playback/MusicService.kt"), constructed)
    }

    @Test
    fun `the app's sound goes on raw audio once, by the link that says it shapes it or else by the server`() {
        val session = code("playback/CastSession.kt")
        val castTo = session.substringAfter("fun castTo(").substringBefore("private var lyricsJob")
        // One sound for the cast, handed to the link and, unless the link puts it on itself, to the server.
        assertTrue("val sound = service.castSound()" in castTo)
        assertTrue("CastLinks.create(device, scope, sender, CastNetwork.wifi(connectivityManager)?.network, sound)" in castTo)
        val assigned = castTo.indexOf("server.pcmSound = if (link.caps.shapesSound) null else sound")
        assertTrue("the server is told before the first song goes", assigned in 0 until castTo.indexOf("service.handOverTo("))
        assertEquals(1, Regex("""\.pcmSound =""").findAll(session).count())
        // The AirPlay link is the one that does, with the sound it is given.
        assertTrue("sound = sound," in code("cast/CastLinks.kt"))
        assertTrue("shapesSound = sound != null" in code("cast/AirPlayLink.kt"))
        // The sound is the phone's own equalizer, as set now.
        val service = code("playback/MusicService.kt").substringAfter("fun castSound(): CastSound").substringBefore("private fun createRenderersFactory")
        assertTrue("equalizer = { equalizerProcessor.settings }" in service)
    }

    @Test
    fun `a song is decoded on the remote player's worker, inside its send`() {
        val player = code("playback/RemotePlayer.kt")
        val send = player.substringAfter("private suspend fun send(").substringBefore("private suspend fun failed(")
        assertTrue("server.preparePcm(" in send)
        assertEquals(1, Regex("""preparePcm\(""").findAll(player).count())
        // send is only ever run from the work queue, which runs on Dispatchers.IO.
        assertTrue("scope.launch(Dispatchers.IO) {\n            for (job in work)" in player)
    }
}

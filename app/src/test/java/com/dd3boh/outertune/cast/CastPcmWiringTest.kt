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
        val probe = code("playback/CastProbe.kt")
        assertTrue("canDecode = Unreleased.CAST_PCM" in probe)
        assertEquals(1, Regex("""canDecode =""").findAll(probe).count())
        // The remote player reads the flag once, as the default of what it is given, and both its
        // ways of choosing (CastFormats, CastLadder) read that.
        val player = code("playback/RemotePlayer.kt")
        assertTrue("private val canDecode: Boolean = Unreleased.CAST_PCM," in player)
        assertEquals(2, Regex("""canDecode = canDecode""").findAll(player).count())
        assertEquals(0, Regex("""canDecode = Unreleased""").findAll(player).count())
        assertTrue("canDecode" !in code("playback/MusicService.kt"))
    }

    @Test
    fun `the ladder is held back to debug and preview builds, and given to the remote player only with its flag`() {
        assertTrue(Regex("""\bval CAST_LADDER = BuildConfig\.PREVIEW_FEATURES\s*$""", RegexOption.MULTILINE).containsMatchIn(code("constants/Unreleased.kt")))
        val service = code("playback/MusicService.kt")
        assertTrue("rungMemory = if (Unreleased.CAST_LADDER) castRungMemory else null," in service)
        // On the phone only: never in a backup.
        assertTrue("CastRungMemory(File(noBackupFilesDir, CastRungMemory.FILE))" in service)
        // DLNA only: FCast and Chromecast keep their single choice.
        assertTrue("private val climbing = rungMemory != null && link.device.protocol == CastProtocol.DLNA" in code("playback/RemotePlayer.kt"))
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
    fun `the ladder's two sentences say why and what to do in two lines of a toast, held from translators with their French beside them`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("casting_refused_every_form", "casting_never_asked")) {
            val en = Regex("""<string name="$name" translatable="false">(.+?)</string>""").find(english)?.groupValues?.get(1)
            val fr = Regex("""<string name="$name">(.+?)</string>""").find(french)?.groupValues?.get(1)
            for (text in listOf(en, fr)) {
                // Android shows two lines of a toast, about 35 characters each on a Pixel 5: with the
                // speaker's name, what to do was cut off (5 Nov 2026).
                val shown = text?.replace("\\'", "'")
                assertTrue("$name: $text", shown != null && '%' !in shown && shown.length <= 64)
                // Why, then what to do: two sentences.
                assertEquals("$name: $text", 2, Regex("""[.?!](\s|$)""").findAll(shown!!).count())
                assertTrue("$name has a long dash", '\u2014' !in shown && '\u2013' !in shown)
            }
        }
        val service = code("playback/MusicService.kt")
        assertTrue("getString(R.string.casting_refused_every_form)" in service)
        assertTrue("getString(R.string.casting_never_asked)" in service)
    }

    @Test
    fun `a song is decoded on the remote player's worker, inside its send or a rung it sends`() {
        val player = code("playback/RemotePlayer.kt")
        val send = player.substringAfter("private suspend fun send(").substringBefore("private suspend fun failed(")
        assertTrue("server.preparePcm(" in send)
        val rung = player.substringAfter("private suspend fun tryRung(").substringBefore("private suspend fun watchClimbs(")
        assertTrue("server.preparePcm(" in rung)
        assertTrue("server.pcmSourceOf(" in rung)
        assertEquals(2, Regex("""preparePcm\(""").findAll(player).count())
        // send and tryRung only ever run from the work queue, which runs on Dispatchers.IO: a rung
        // goes from the watcher to the next only through it.
        assertTrue("scope.launch(Dispatchers.IO) {\n            for (job in work)" in player)
        assertTrue("work.trySend { tryRung(climb, next) }" in player)
        assertEquals(1, Regex("""(?<!fun )tryRung\(climb, rungs\.first\(\)\)""").findAll(player).count())
    }

    @Test
    fun `a speaker that played no form keeps the song's copy on the phone and is not sent the song again`() {
        val error = code("playback/MusicService.kt").substringAfter("override fun onPlayerError(").substringBefore("private fun scheduleQuietRetry(")
        assertTrue("val speakerGaveUp = isCasting && Unreleased.CAST_LADDER && error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR" in error)
        // The copy is judged only when the speaker did not give up, and once: it was thrown out and
        // the song sent again, so the ladder walked twice before anyone heard why.
        assertTrue("val goOn = !speakerGaveUp && badCachedCopy.playerFailed(failedId, error)" in error)
        assertEquals(1, Regex("""badCachedCopy\.playerFailed\(""").findAll(error).count())
        assertTrue(error.indexOf("val goOn =") < error.indexOf("player.prepare()"))
        // What the speaker said is the message, as it is for any receiver's failure.
        assertTrue("isCasting && error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR && !error.message.isNullOrBlank()) error.message!!" in error)
    }
}

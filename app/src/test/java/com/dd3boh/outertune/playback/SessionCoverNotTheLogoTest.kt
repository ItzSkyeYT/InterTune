/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cover on the lock screen, in the shade and in the notification is never the app's logo.
 *
 * A song whose cover could not be loaded (no connection, nothing of it on the phone, a file with no
 * picture) was given a picture of OuterTune's logo instead, and it read as the song's cover. Where
 * there is no cover the system is now given a plain square with a music note, as the widget draws a
 * song with no cover, and the real one replaces it when it comes.
 */
class SessionCoverNotTheLogoTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    /** Only what is code: a comment may say what is not done. */
    private fun code(path: String) = source(path).lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `what hands the system its covers never draws the logo`() {
        for (path in listOf("utils/SessionBitmapLoader.kt", "playback/SessionPlayer.kt")) {
            val code = code(path)
            assertTrue("$path is empty", code.isNotBlank())
            // drawPlaceholder draws the logo; LocalArtworkPath goes through Coil's fetcher, which
            // swaps the logo in for a file with no picture and calls it a success
            for (logo in listOf("drawPlaceholder", "placeholder_icon", "LocalArtworkPath", "CoilBitmapLoader")) {
                assertFalse("$path uses $logo", code.contains(logo))
            }
        }
    }

    @Test
    fun `the session is given that loader`() {
        val service = code("playback/MusicService.kt")
        assertTrue(Regex("""val covers = SessionBitmapLoader\(""").containsMatchIn(service))
        assertTrue(service.contains(".setBitmapLoader(covers)"))
        assertEquals("one loader for the session", 1, Regex("""\.setBitmapLoader\(""").findAll(service).count())
        assertFalse(service.contains("CoilBitmapLoader("))
    }

    @Test
    fun `nothing in playback names the logo`() {
        // the notification, the session, the car and the cast receiver all take their pictures from here
        val named = File("src/main/java/com/dd3boh/outertune/playback").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> listOf("drawPlaceholder", "placeholder_icon").any { file.readText().contains(it) } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), named)
    }

    @Test
    fun `where there is no cover the system is shown a music note, and the real one when it comes`() {
        val loader = code("utils/SessionBitmapLoader.kt")
        assertTrue(loader.contains("R.drawable.music_note"))
        assertTrue("the stand-in is what LastAsked hands out when nothing could be had", Regex("""instead = \{ noCover\(""").containsMatchIn(loader))
        assertTrue("a picture that cannot be decoded is no cover either", Regex("""decodeBitmap[\s\S]*?\?: noCover\(""").containsMatchIn(loader))
        // and the connection coming back has the cover tried for at once
        val service = code("playback/MusicService.kt")
        assertTrue(Regex("""isNetworkConnected\.collect\(scope\) \{ online ->\s*if \(online\) covers\.tryAgain\(\)""").containsMatchIn(service))
    }
}

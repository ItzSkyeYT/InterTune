/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #108: the app closed on a phone with an Android 15 build for an older model ("cannot use a recycled
 * source in createBitmap", in MediaSession.setMetadata). The cover kept for the playing song went
 * out itself at every song change and every play and pause, and that phone's system frees a cover
 * once it has made its own smaller copy, so the next hand-out was a freed cover. Now each ask gets a
 * copy of its own, and the kept one is brought down to the size the system keeps when it is found.
 */
class SessionCoverOwnCopyTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText().lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private val loader = code("utils/SessionBitmapLoader.kt")

    @Test
    fun `each ask for the playing song's cover gets a copy of its own`() {
        assertTrue("the kept covers are shared as copies", loader.contains("share = ::forTheSystem"))
        val body = loader.substringAfter("private fun forTheSystem(").lines().take(4).joinToString("\n")
        assertTrue("forTheSystem makes a copy", body.contains(".copy("))
        assertTrue("a kept cover found freed is never handed out", body.contains("isRecycled"))
    }

    @Test
    fun `what is found is brought down to the size the system keeps, once`() {
        assertTrue(Regex("""find = \{[^}]*fitted\(""").containsMatchIn(loader))
        assertTrue("a picture from a file too", Regex("""decodeSampled\(data, artPx\)\?\.let \{ fitted\(""").containsMatchIn(loader))
    }

    @Test
    fun `the covers are never freed by the app`() {
        for (path in listOf("utils/SessionBitmapLoader.kt", "utils/LastAsked.kt", "playback/SessionPlayer.kt", "playback/MusicService.kt")) {
            assertFalse("$path frees a bitmap", code(path).contains(".recycle()"))
        }
    }
}

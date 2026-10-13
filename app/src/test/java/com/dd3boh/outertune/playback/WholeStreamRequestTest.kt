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
 * A song is asked of YouTube in one request that runs to its end, never as a first piece with
 * the rest to follow.
 *
 * It used to be asked for 512 KB at a time, the first time round. The player was handed those
 * bytes as if they were the whole file, and what kept the song going was the player failing: the
 * cut fell inside an audio frame, the frame came up short, the load was tried again and that
 * second request ran to the end. A cut that fell exactly between two frames left nothing short.
 * The player read a file that ended there, and the song was over at about thirty seconds with
 * no error: the next one started, or the same one from its beginning with repeat on. Always the
 * same songs, about one in three hundred, and only when the song was asked for afresh, so a
 * second try played through (issue #20, and a song on a phone on 13 Oct 2026 whose piece ended
 * on the last byte of frame 1,570, which is 31.4 s, the second it was left at).
 */
class WholeStreamRequestTest {
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    /** The function that tells the player where a song's bytes are. */
    private val whereTheBytesAre: String = run {
        val start = service.indexOf("private fun createDataSourceFactory")
        assertTrue("the function that finds a song's bytes was not found", start >= 0)
        val end = service.indexOf("\n    }\n", start)
        assertTrue("its end was not found", end > start)
        service.substring(start, end)
    }

    @Test
    fun `a new address is handed over as it is, to be read to the song's end`() {
        assertTrue(
            "the address YouTube has just given is no longer what the player gets",
            "dataSpec.withUri(streamUrl.toUri())\n" in whereTheBytesAre,
        )
    }

    @Test
    fun `nothing there asks for a part of a song`() {
        // subrange() on what goes back to the player is how a request was cut to 512 KB.
        assertFalse("a request is cut short again", ".subrange(" in whereTheBytesAre)
    }

    @Test
    fun `the 512 KB that is left is only how much of a song the cache must hold to be read from`() {
        val uses = service.lines().map { it.trim() }
            .filter { "CHUNK_LENGTH" in it && "const val" !in it && !it.startsWith("//") && !it.startsWith("*") }
        assertTrue("CHUNK_LENGTH is used somewhere new: $uses", uses.all { "isCached(" in it })
    }
}

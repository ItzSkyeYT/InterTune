/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.zionhuang.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A recognised song that is tapped starts loading at once, as a song tapped anywhere else does.
 *
 * QueueStart puts the song a queue names in the player and starts it while the rest of the queue
 * is asked for (QueueStartTest). The recognition screens named none: a song it might have been,
 * and a song in the list of everything heard, went in as a queue made of a video id alone, so the
 * player held nothing until YouTube had answered, 1.7 s on the day it was measured.
 *
 * Whether a call names its song cannot be seen from outside the screen, so this reads the source,
 * the way PlayOriginCoverageTest does.
 */
class RecognisedSongStartTest {

    private val screens = listOf("RecognitionScreen.kt", "RecognitionHistoryScreen.kt")
        .map { File("src/main/java/com/dd3boh/outertune/ui/screens/$it") }

    @Test
    fun `a queue handed its song names it, and one made of an id names none`() {
        val song = MediaMetadata(id = "a", title = "A", artists = emptyList(), duration = 180, genre = null)
        assertSame(song, YouTubeQueue.radio(song).preloadItem)
        assertSame(song, YouTubeQueue(WatchEndpoint(videoId = "a"), song).preloadItem)
        assertNull(YouTubeQueue(WatchEndpoint(videoId = "a")).preloadItem)
    }

    @Test
    fun `every song played from the recognition screens is handed to its queue`() {
        val calls = mutableListOf<String>()
        val bare = mutableListOf<String>()
        for (file in screens) {
            val text = file.readText()
            Regex("""\bplayQueue\(""").findAll(text).forEach { m ->
                val at = "${file.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
                val args = argumentsOf(text, m.range.last + 1)
                val made = Regex("""\bYouTubeQueue\(""").find(args)
                val named = "YouTubeQueue.radio(" in args ||
                        (made != null && topLevel(argumentsOf(args, made.range.last + 1)).size >= 2)
                calls += at
                if (!named) bare += at
            }
        }
        assertEquals("the calls this was written for: $calls", 3, calls.size)
        assertEquals("songs played with no song named:\n" + bare.joinToString("\n"), emptyList<String>(), bare)
    }

    /**
     * The list of everything heard keeps what Shazam called the song, with the id YouTube gave
     * for it: not its title there, its artists or its cover. A song made of those words would be
     * shown in the player like that, and written to the library like that the first time it
     * played. So the song handed over is the library's own, when it holds one.
     */
    @Test
    fun `a heard song is named by the library's row of it, never by the words the history kept`() {
        val history = screens.last().readText()
        assertTrue("database.song(id).first()?.toMediaMetadata()" in history)
        assertFalse("MediaMetadata(" in history.replace("toMediaMetadata()", ""))
    }

    /** What is between the bracket before [from] and the one that closes it. */
    private fun argumentsOf(text: String, from: Int): String {
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(from, i - 1)
    }

    /** The arguments themselves: split at the commas that are in no bracket of their own. */
    private fun topLevel(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for ((i, c) in args.withIndex()) {
            if (c in "({[") depth++
            if (c in ")}]") depth--
            if (c == ',' && depth == 0) {
                parts += args.substring(start, i)
                start = i + 1
            }
        }
        parts += args.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.utils.YouTubeLink.Channel
import com.dd3boh.outertune.utils.YouTubeLink.Playlist
import com.dd3boh.outertune.utils.YouTubeLink.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinkTest {
    @Test
    fun `plain words are searches, not links`() {
        for (text in listOf(
            "watch", "c", "channel", "playlist", "watch?v=dQw4w9WgXcQ", "c/queen", "playlist?list=PL123",
            "now that's what i call music", "null", "", "   ", "youtu.be", "music.youtube.com/watch?v=dQw4w9WgXcQ",
        )) {
            assertNull(text, YouTubeLink.parse(text))
        }
    }

    @Test
    fun `other hosts are not YouTube`() {
        assertNull(YouTubeLink.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeLink.parse("https://music.youtube.com.example.org/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeLink.parse("ftp://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `YouTube Music song links`() {
        assertEquals(
            Video("dQw4w9WgXcQ", null),
            YouTubeLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=Ab-cD_12"),
        )
        assertEquals(
            Video("dQw4w9WgXcQ", "RDAMVMdQw4w9WgXcQ"),
            YouTubeLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ"),
        )
        assertEquals(Video("dQw4w9WgXcQ", null), YouTubeLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(Video("dQw4w9WgXcQ", null), YouTubeLink.parse("http://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals(Video("dQw4w9WgXcQ", null), YouTubeLink.parse("https://youtu.be/dQw4w9WgXcQ?si=x1"))
        assertEquals(Video("dQw4w9WgXcQ", null), YouTubeLink.parse("  https://YouTube.com/watch?v=dQw4w9WgXcQ "))
    }

    @Test
    fun `playlist and album links`() {
        assertEquals(
            Playlist("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"),
            YouTubeLink.parse("https://music.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"),
        )
        assertEquals(
            Playlist("OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY"),
            YouTubeLink.parse("https://music.youtube.com/playlist?list=OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY&si=q"),
        )
        assertNull(YouTubeLink.parse("https://music.youtube.com/playlist"))
    }

    @Test
    fun `channel links`() {
        assertEquals(
            Channel("UCiMhD4jzUqG-IgPzUmmytRQ"),
            YouTubeLink.parse("https://music.youtube.com/channel/UCiMhD4jzUqG-IgPzUmmytRQ"),
        )
        // The id, not whatever tab follows it.
        assertEquals(
            Channel("UCiMhD4jzUqG-IgPzUmmytRQ"),
            YouTubeLink.parse("https://www.youtube.com/channel/UCiMhD4jzUqG-IgPzUmmytRQ/videos"),
        )
        assertNull(YouTubeLink.parse("https://music.youtube.com/channel/"))
    }

    @Test
    fun `a YouTube address with nothing to open`() {
        assertNull(YouTubeLink.parse("https://music.youtube.com/"))
        assertNull(YouTubeLink.parse("https://music.youtube.com/watch"))
        assertNull(YouTubeLink.parse("https://music.youtube.com/explore"))
    }
}

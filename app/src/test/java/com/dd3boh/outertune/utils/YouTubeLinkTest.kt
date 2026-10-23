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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `the share page's own links, the same pages under the app's scheme`() {
        // Brave refuses a page's link to a YouTube address while it keeps YouTube's links for
        // itself ("Open YouTube links in Brave", brave-browser#46614): the share page hands it these.
        assertEquals(Video("dQw4w9WgXcQ", null), YouTubeLink.parse("intertune://watch?v=dQw4w9WgXcQ"))
        assertEquals(Video("dQw4w9WgXcQ", "PL123"), YouTubeLink.parse("intertune://watch?v=dQw4w9WgXcQ&list=PL123"))
        assertEquals(
            Playlist("OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY"),
            YouTubeLink.parse("INTERTUNE://playlist?list=OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY"),
        )
        assertNull(YouTubeLink.parse("intertune://watch"))
        assertNull(YouTubeLink.parse("intertune://settings"))
        assertNull(YouTubeLink.parse("intertune:watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `a song link shows the song, and only the app's own play link plays it at once`() {
        // A link from outside (the share page, a friend's message, the search box) opens the song
        // for the listener to choose what to do; the phone's own search, a tap on a result, plays.
        assertEquals(false, (YouTubeLink.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ") as Video).playNow)
        assertEquals(false, (YouTubeLink.parse("intertune://watch?v=dQw4w9WgXcQ") as Video).playNow)
        assertEquals(Video("dQw4w9WgXcQ", null, playNow = true), YouTubeLink.parse("intertune://play?v=dQw4w9WgXcQ"))
        // Not a page of YouTube's: only under the app's scheme.
        assertNull(YouTubeLink.parse("https://music.youtube.com/play?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `a browser may hand the app its own scheme`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val filter = Regex("""<intent-filter>(?:(?!</intent-filter>).)*android:scheme="intertune"(?:(?!</intent-filter>).)*</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value
        assertNotNull("a filter for intertune:// links", filter)
        assertTrue(filter!!.contains("android.intent.action.VIEW"))
        assertTrue("or no browser hands it over", filter.contains("android.intent.category.BROWSABLE"))
    }
}

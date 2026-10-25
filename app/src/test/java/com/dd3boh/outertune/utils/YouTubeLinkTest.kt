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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

    private val bohemian = Video("BSTsnWoslP4", null)
    private val fiveMoreHours = Playlist("OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8")

    @Test
    fun `the share page's link opens the song it carries, without playing it`() {
        // Tapped in a chat, the link reaches the app whole, the part after the # with it.
        assertEquals(bohemian, YouTubeLink.parse("https://intertune.app/s/#v=BSTsnWoslP4&t=Bohemian%20Rhapsody&a=Queen&d=354&k=t"))
        assertEquals(bohemian, YouTubeLink.parse("https://www.intertune.app/s/#v=BSTsnWoslP4&t=Bohemian%20Rhapsody&a=Queen"))
        assertEquals(bohemian, YouTubeLink.parse("https://intertune.app/s#v=BSTsnWoslP4&t=Bohemian%20Rhapsody&a=Queen"))
        assertEquals(bohemian, YouTubeLink.parse("  HTTPS://InterTune.app/s/?ref=chat#v=BSTsnWoslP4 "))
        assertEquals(false, (YouTubeLink.parse("https://intertune.app/s/#v=BSTsnWoslP4&t=Bohemian%20Rhapsody") as Video).playNow)
    }

    @Test
    fun `the share page's album link opens the album`() {
        assertEquals(
            fiveMoreHours,
            YouTubeLink.parse("https://intertune.app/s/#l=OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8&t=Five%20More%20Hours&a=Deorro%2C%20Chris%20Brown"),
        )
        assertEquals(fiveMoreHours, YouTubeLink.parse("https://intertune.app/s#l=OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8"))
    }

    @Test
    fun `the share page's other forms, read the way the page reads them`() {
        // Only the id: the page asks YouTube for the name, the app needs nothing more.
        assertEquals(bohemian, YouTubeLink.parse("https://intertune.app/s/#v=BSTsnWoslP4"))
        // Any order, and + for a space, as the page's URLSearchParams takes them.
        assertEquals(bohemian, YouTubeLink.parse("https://intertune.app/s/#t=Bohemian+Rhapsody&a=Queen&v=BSTsnWoslP4"))
        // A name with an & or an = in it is still the name, not one more key.
        assertEquals(bohemian, YouTubeLink.parse("https://intertune.app/s/#t=Rock%20%26%20Roll%20v%3DdQw4w9WgXcQ&v=BSTsnWoslP4"))
        // A song and a list: the page shows the song, and so does the app.
        assertEquals(bohemian, YouTubeLink.parse("https://intertune.app/s/#v=BSTsnWoslP4&l=OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8"))
        // The first of two, as URLSearchParams.get.
        assertEquals(bohemian, YouTubeLink.parse("https://intertune.app/s/#v=BSTsnWoslP4&v=dQw4w9WgXcQ"))
        // Any list the page takes, not only an album's.
        assertEquals(
            Playlist("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"),
            YouTubeLink.parse("https://intertune.app/s/#l=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&t=Mix"),
        )
        // An id the page refuses (a song's has 11 characters) leaves the list beside it, or nothing.
        assertEquals(fiveMoreHours, YouTubeLink.parse("https://intertune.app/s/#v=BSTsnWosl&l=OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8"))
        assertNull(YouTubeLink.parse("https://intertune.app/s/#v=BSTsnWoslP4x"))
        assertNull(YouTubeLink.parse("https://intertune.app/s/#l=PL123"))
        // Nothing to open: the page alone, an empty #, a name with no id, an id outside the #.
        assertNull(YouTubeLink.parse("https://intertune.app/s/"))
        assertNull(YouTubeLink.parse("https://intertune.app/s/#"))
        assertNull(YouTubeLink.parse("https://intertune.app/s/#t=Bohemian%20Rhapsody&a=Queen"))
        assertNull(YouTubeLink.parse("https://intertune.app/s/?v=BSTsnWoslP4"))
    }

    @Test
    fun `nothing else on the site is a link to open`() {
        for (text in listOf(
            "https://intertune.app/", "https://intertune.app", "https://intertune.app/#v=BSTsnWoslP4",
            "https://intertune.app/wiki/", "https://intertune.app/wiki/#v=BSTsnWoslP4", "https://intertune.app/sitemap.xml",
            "https://intertune.app/settings#v=BSTsnWoslP4", "https://intertune.app/s/icons/spotify.svg#v=BSTsnWoslP4",
            "https://intertune.app/watch?v=BSTsnWoslP4", "https://intertune.app/playlist?list=OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8",
            "https://intertune.app.example.org/s/#v=BSTsnWoslP4", "https://notintertune.app/s/#v=BSTsnWoslP4",
            "ftp://intertune.app/s/#v=BSTsnWoslP4", "intertune://s#v=BSTsnWoslP4",
        )) {
            assertNull(text, YouTubeLink.parse(text))
        }
    }

    @Test
    fun `what Share sends is what the app opens`() {
        val song = ShareLinks.song("BSTsnWoslP4", "Bohemian Rhapsody", listOf("Queen"), 354, albumTrack = true).page
        assertEquals(bohemian, YouTubeLink.parse(song!!))
        val album = ShareLinks.album(
            "https://music.youtube.com/browse/MPREb_hLZFwxCac4p", "OLAK5uy_nHlcGzMzrlzyjd82XbcfULZKFjHuNNC_8",
            "Five More Hours", listOf("Deorro", "Chris Brown"),
        ).page
        assertEquals(fiveMoreHours, YouTubeLink.parse(album!!))
        // Names that need escaping on the way.
        val awkward = ShareLinks.song("dQw4w9WgXcQ", "Rock & Roll = \"Live\" #1? 100%", listOf("AC/DC", "Beyoncé"), 61, albumTrack = false).page
        assertEquals(Video("dQw4w9WgXcQ", null), YouTubeLink.parse(awkward!!))
    }

    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    @Test
    fun `Android opens the share page's links in the app, verified, and leaves the rest of the site to the browser`() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val filters = manifest.getElementsByTagName("intent-filter").elements().filter { filter ->
            filter.getElementsByTagName("data").elements().any { "intertune.app" in it.getAttribute("android:host") }
        }
        assertEquals("one filter for the site", 1, filters.size)
        val filter = filters.single()
        assertEquals(".MainActivity", (filter.parentNode as Element).getAttribute("android:name"))
        fun all(tag: String, attribute: String) =
            filter.getElementsByTagName(tag).elements().map { it.getAttribute("android:$attribute") }.filter { it.isNotEmpty() }.toSet()
        // Checked by Android against intertune.app/.well-known/assetlinks.json: once it passes, a tap opens the app straight away.
        assertEquals("verified", "true", filter.getAttribute("android:autoVerify"))
        assertEquals(setOf("android.intent.action.VIEW"), all("action", "name"))
        assertEquals(setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"), all("category", "name"))
        assertEquals(setOf("https"), all("data", "scheme"))
        assertEquals(setOf("intertune.app"), all("data", "host"))
        // The share page and nothing else of the site. A pathPrefix of "/s" would take /sitemap.xml too, and
        // any page named with an s, which the app would open on Home with nothing to show.
        for (pattern in listOf("pathPattern", "pathAdvancedPattern", "pathSuffix")) {
            assertTrue(pattern, all("data", pattern).isEmpty())
        }
        val paths = all("data", "path")
        val prefixes = all("data", "pathPrefix")
        fun claimed(path: String) = path in paths || prefixes.any { path.startsWith(it) }
        for (path in listOf("/s", "/s/")) assertTrue(path, claimed(path))
        for (path in listOf("/", "/wiki/", "/wiki/faq/", "/sitemap.xml", "/settings", "/s/icons/spotify.svg", "/404.html", "/releases/")) {
            assertFalse(path, claimed(path))
        }
        // And every page it takes, the app opens.
        for (path in paths + prefixes) {
            assertNotNull(path, YouTubeLink.parse("https://intertune.app$path#v=BSTsnWoslP4"))
        }
    }
}

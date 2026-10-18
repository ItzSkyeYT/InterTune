/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.search

import com.dd3boh.outertune.utils.YouTubeLink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** InterTune in the phone's own search: what is asked, what is answered, and in which builds. */
class SystemSearchTest {

    @Test
    fun `the words come from the path, or else the selection`() {
        assertEquals("daft punk", SystemSearch.words(listOf("search_suggest_query", "daft punk"), null))
        assertEquals("one more time", SystemSearch.words(listOf("search_suggest_query"), arrayOf(" one more time ")))
        assertEquals("", SystemSearch.words(listOf("search_suggest_query"), null))
    }

    @Test
    fun `every link a result carries is one the app opens`() {
        assertEquals(YouTubeLink.Video("dQw4w9WgXcQ", null), YouTubeLink.parse(SystemSearch.songLink("dQw4w9WgXcQ")))
        assertEquals(YouTubeLink.Channel("UCabc_-1"), YouTubeLink.parse(SystemSearch.artistLink("UCabc_-1")))
        assertEquals(YouTubeLink.Album("MPREb_abc123"), YouTubeLink.parse(SystemSearch.albumLink("MPREb_abc123")))
        assertEquals("LPabcd1234", SystemSearch.playlistOf(SystemSearch.playlistLink("LPabcd1234")))
    }

    @Test
    fun `only an album is taken from a browse link`() {
        assertNull(YouTubeLink.parse("https://music.youtube.com/browse/FEmusic_home"))
        assertNull(YouTubeLink.parse("https://music.youtube.com/browse/"))
    }

    @Test
    fun `a playlist link is the app's own and nothing else`() {
        assertNull(SystemSearch.playlistOf("https://music.youtube.com/playlist?list=PL123"))
        assertNull(SystemSearch.playlistOf("intertune://album/LPabcd1234"))
        assertNull(SystemSearch.playlistOf("intertune://playlist/../secret"))
    }

    @Test
    fun `local files have no link to open them`() {
        assertTrue(SystemSearch.onYouTube(false, "dQw4w9WgXcQ"))
        assertFalse(SystemSearch.onYouTube(true, "dQw4w9WgXcQ"))
    }

    @Test
    fun `YouTube Music is asked from three letters`() {
        assertFalse(SystemSearch.goesOnline("da"))
        assertFalse(SystemSearch.goesOnline("  da "))
        assertTrue(SystemSearch.goesOnline("daf"))
    }

    @Test
    fun `the library comes first and nothing is given twice`() {
        val mine = SystemSearch.Row("song", "a", "One", "X", SystemSearch.songLink("a"))
        val again = SystemSearch.Row("song", "a", "One", "X", SystemSearch.songLink("a"))
        val more = SystemSearch.Row("song", "b", "Two", "Y", SystemSearch.songLink("b"))
        assertEquals(listOf(mine, more), SystemSearch.merge(listOf(mine), listOf(again, more, more)))
    }

    @Test
    fun `only the newest question goes, and never two within a second`() {
        var clock = 10_000L
        val pacing = SystemSearch.Pacing(1_000) { clock }
        val first = pacing.ask()
        val second = pacing.ask()
        assertFalse("a letter typed since: the older question stays home", pacing.isNewest(first))
        assertTrue(pacing.isNewest(second))
        assertEquals(0L, pacing.wait())
        clock += 300
        assertEquals("the next one waits out the second", 700L, pacing.wait())
        clock += 5_000
        assertEquals(0L, pacing.wait())
    }

    @Test
    fun `words asked again are answered from what was kept`() {
        val recent = SystemSearch.Recent<Int>(2)
        recent["Daft Punk"] = 1
        assertEquals(1, recent[" daft punk "])
        recent["b"] = 2
        recent["c"] = 3
        assertNull("the oldest goes", recent["daft punk"])
    }

    @Test
    fun `no more than One UI shows of one app`() {
        val most = SystemSearch.SONGS + SystemSearch.ARTISTS + SystemSearch.ALBUMS + SystemSearch.PLAYLISTS +
            SystemSearch.ONLINE_SONGS + SystemSearch.ONLINE_ARTISTS + SystemSearch.ONLINE_ALBUMS
        assertTrue("$most rows, One UI reads twenty", most <= SystemSearch.MOST)
    }

    @Test
    fun `words handed over by the phone's search open the app's own search for them`() {
        val link = SystemSearch.searchLinkFor("android.intent.action.SEARCH", " daft punk & co ")
        assertEquals("daft punk & co", SystemSearch.searchOf(link!!))
        assertNull("not a search", SystemSearch.searchLinkFor("android.intent.action.VIEW", "daft punk"))
        assertNull("no words", SystemSearch.searchLinkFor("android.intent.action.SEARCH", "  "))
        assertNull(SystemSearch.searchOf("https://music.youtube.com/search?q=x"))
        assertNull(SystemSearch.searchOf(SystemSearch.playlistLink("LPabcd1234")))
    }

    private val src = File("src")

    @Test
    fun `every build is in the phone's search, each with its own provider`() {
        // In every build from 0.12 (his yes of 18 Oct 2026): the main manifest declares it once.
        val main = File(src, "main/AndroidManifest.xml").readText()
        assertTrue("the searchable", "@xml/system_searchable" in main)
        assertTrue("the provider is guarded", "android:readPermission=\"android.permission.GLOBAL_SEARCH\"" in main)
        assertTrue("the provider is named by the build's id", "android:authorities=\"\${applicationId}.systemsearch\"" in main)
        for (build in listOf("debug", "preview")) {
            val manifest = File(src, "$build/AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()
            assertFalse("$build no longer declares its own", "SystemSearchProvider" in manifest)
        }
        // Each build's searchable asks its own provider: the release's id, and the two that add to it.
        for ((build, id) in listOf("main" to "dev.skye.intertune", "debug" to "dev.skye.intertune.debug", "preview" to "dev.skye.intertune.preview")) {
            val authority = File(src, "$build/res/values/system_search.xml").readText()
            assertTrue("$build's searchable asks its own provider", ">$id.systemsearch<" in authority)
        }
        assertTrue("includeInGlobalSearch is what lists the app", "android:includeInGlobalSearch=\"true\"" in File(src, "main/res/xml/system_searchable.xml").readText())
    }
}

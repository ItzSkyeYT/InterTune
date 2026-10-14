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

    private val src = File("src")

    @Test
    fun `only debug and preview builds are in the phone's search, each with its own provider`() {
        val main = File(src, "main/AndroidManifest.xml").readText()
        assertFalse("a release must not be listed yet", "android.app.searchable" in main || "SystemSearchProvider" in main)
        for (build in listOf("debug", "preview")) {
            val manifest = File(src, "$build/AndroidManifest.xml").readText()
            assertTrue("$build declares the searchable", "@xml/system_searchable" in manifest)
            assertTrue("$build guards the provider", "android:readPermission=\"android.permission.GLOBAL_SEARCH\"" in manifest)
            assertTrue("$build names its provider by its id", "android:authorities=\"\${applicationId}.systemsearch\"" in manifest)
            val authority = File(src, "$build/res/values/system_search.xml").readText()
            assertTrue("$build's searchable asks that same provider", ">dev.skye.intertune.$build.systemsearch<" in authority)
        }
        assertTrue("includeInGlobalSearch is what lists the app", "android:includeInGlobalSearch=\"true\"" in File(src, "main/res/xml/system_searchable.xml").readText())
    }
}

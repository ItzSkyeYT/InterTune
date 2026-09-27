/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Play X" as voice assistants, car systems and automation apps send it: a play-from-search
 * intent with the words in SearchManager.QUERY and no data.
 *
 * The manifest named the action only inside the YouTube link filters, which also want a link, so a
 * plain request never reached the app. This pins the filter, what MainActivity takes from the
 * intent and the request it hands the media session. What the session then plays needs a device.
 */
class PlayFromSearchTest {

    private class Filter(val actions: Set<String>, val categories: Set<String>, val data: List<Map<String, String>>)

    /** MainActivity's intent filters, as the manifest declares them. */
    private fun mainActivityFilters(): List<Filter> {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activity = manifest.getElementsByTagName("activity").elements()
            .single { it.getAttribute("android:name") == ".MainActivity" }
        return activity.getElementsByTagName("intent-filter").elements().map { filter ->
            fun named(tag: String) = filter.getElementsByTagName(tag).elements().map { it.getAttribute("android:name") }.toSet()
            Filter(
                actions = named("action"),
                categories = named("category"),
                data = filter.getElementsByTagName("data").elements().map { data ->
                    (0 until data.attributes.length).associate { data.attributes.item(it).nodeName to data.attributes.item(it).nodeValue }
                },
            )
        }
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    @Test
    fun `a play-from-search intent with no data matches a filter of MainActivity`() {
        assertEquals("android.media.action.MEDIA_PLAY_FROM_SEARCH", MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        // An intent with no data only matches a filter with none, and startActivity only finds a
        // filter with the default category.
        val plain = mainActivityFilters().filter {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH in it.actions && it.data.isEmpty()
        }
        assertEquals(1, plain.size)
        assertTrue(plain.single().categories.toString(), "android.intent.category.DEFAULT" in plain.single().categories)
    }

    @Test
    fun `the YouTube links still open the app`() {
        val links = mainActivityFilters().filter { "android.intent.action.VIEW" in it.actions && it.data.isNotEmpty() }
        fun opens(scheme: String, host: String? = null) = links.any { filter ->
            filter.data.any { it["android:scheme"] == scheme } &&
                (host == null || filter.data.any { it["android:host"] == host })
        }
        for (host in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")) {
            assertTrue(host, opens("https", host))
        }
        assertTrue(opens("vnd.youtube"))
    }

    @Test
    fun `the words of a plain request, trimmed, and empty when it names nothing`() {
        val action = MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
        assertEquals("daft punk", searchToPlay(action, hasData = false, query = "  daft punk "))
        // "Play some music": resume, rather than nothing.
        assertEquals("", searchToPlay(action, hasData = false, query = ""))
        assertEquals("", searchToPlay(action, hasData = false, query = null))
    }

    @Test
    fun `a link, or any other intent, is not a play from search`() {
        // With data it came in through a YouTube filter, and the link handling has it.
        assertNull(searchToPlay(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, hasData = true, query = "daft punk"))
        assertNull(searchToPlay("android.intent.action.VIEW", hasData = false, query = "daft punk"))
        assertNull(searchToPlay(MainActivity.ACTION_PLAY_LIKED, hasData = false, query = null))
        assertNull(searchToPlay(null, hasData = false, query = "daft punk"))
    }

    @Test
    fun `the request is a search as the session takes one from Android Auto`() {
        // No id and no uri: MediaLibrarySessionCallback reads that as a search, and plays by the words.
        val request = searchPlayRequest("daft punk")
        assertEquals("", request.mediaId)
        assertNull(request.requestMetadata.mediaUri)
        assertEquals("daft punk", request.requestMetadata.searchQuery)
        assertEquals("", searchPlayRequest("").requestMetadata.searchQuery)
    }
}

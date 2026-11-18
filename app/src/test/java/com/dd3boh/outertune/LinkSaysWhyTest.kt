/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A YouTube link pasted in the search bar that opens nothing says why. On 15 Nov 2026 (the link-download check) a lecture's
 * link pasted on Home closed the search and did nothing: YouTube Music answers for a video it doesn't carry with no title,
 * artist or length, the app's message for that went to a snackbar host the search bar made for itself and never drew, and
 * a link YouTube didn't answer for at all (offline) only went to the log.
 */
class LinkSaysWhyTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val search = File(src, "ui/screens/search/SearchScreen.kt").readText()
    private val navigator = File(src, "MainActivityUtils.kt").readText().let { text ->
        text.substring(text.indexOf("fun youtubeNavigator("), text.indexOf("suspend fun scanInit("))
    }

    @Test
    fun `the search bar says it on the app's snackbar, the one on screen`() {
        val bar = search.substring(search.indexOf("fun SearchBarContainer("))
        assertTrue("val snackbarHostState = LocalSnackbarHostState.current" in bar)
        assertFalse("a host of its own is drawn nowhere", Regex("""remember\s*\{\s*SnackbarHostState\(\)\s*}""").containsMatchIn(search))
    }

    @Test
    fun `a link YouTube didn't answer for says so, with Retry, wherever it came from`() {
        assertFalse("nothing only goes to the log", Regex("""onFailure\s*\{\s*reportException\(it\)\s*}""").containsMatchIn(navigator))
        // The album link and the song link both.
        assertEquals(2, Regex("""\.onFailure\(::failed\)""").findAll(navigator).count())
        val failed = navigator.substring(navigator.indexOf("fun failed("), navigator.indexOf("when (link)"))
        assertTrue("showSnackbar" in failed)
        assertTrue("actionLabel = context.getString(R.string.retry)" in failed)
        // Offline, the plain words the app has for it; online, that the link didn't open.
        assertTrue("R.string.error_no_internet" in failed)
        assertTrue("R.string.link_open_failed" in failed)
        assertTrue("Retry asks again", "youtubeNavigator(context, navController, coroutineScope, playerConnection, snackbarHostState, uri, menuState)" in failed)
    }

    @Test
    fun `a video that is no song on YouTube Music says so`() {
        val none = navigator.substring(navigator.indexOf("if (s == null)"), navigator.indexOf("} else if (!link.playNow && s.album != null)"))
        assertTrue("R.string.err_invalid_ytm_song" in none)
    }

    @Test
    fun `a link opens what it links to in the library's search too`() {
        // It searched the library for the address, and found nothing.
        assertTrue("if (searchSource == SearchSource.LOCAL && YouTubeLink.parse(it) == null)" in search)
    }

    @Test
    fun `the words are short and plain, in English and French`() {
        fun texts(folder: String): Map<String, String> {
            val file = File("src/main/res/$folder/strings-ot.xml").readText()
            return listOf("err_invalid_ytm_song", "link_open_failed").associateWith { name ->
                // link_open_failed is new in 0.13 and held from translators until the release; err_invalid_ytm_song is not.
                val found = Regex("""<string name="$name"(?: translatable="false")?>([^<]*)</string>""").find(file)
                assertNotNull("$folder: $name", found)
                found!!.groupValues[1]
            }
        }
        for (folder in listOf("values", "values-fr")) {
            for ((name, text) in texts(folder)) {
                // Two short sentences at most, no dashes, no semicolons (TASTE.md, the app's words).
                assertTrue("$folder $name: $text", text.count { it == '.' } <= 2 && text.length <= 90)
                assertFalse("$folder $name: $text", text.contains('—') || text.contains(';'))
            }
        }
        assertEquals("InterTune can\\'t play this video. It isn\\'t a song on YouTube Music.", texts("values")["err_invalid_ytm_song"])
    }
}

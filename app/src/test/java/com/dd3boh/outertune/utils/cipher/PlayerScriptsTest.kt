/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Finding YouTube's player script and reading it, with pages written here in place of YouTube's. */
class PlayerScriptsTest {
    /** The line of iframe_api that names the player, in the shape YouTube writes it. */
    private val iframeApi = """var scriptUrl = 'https:\/\/www.youtube.com\/s\/player\/0a1b2c3d\/www-widgetapi.vflset\/www-widgetapi.js';try{var ttPolicy=1}catch(e){}"""
    private val scriptAddress = "https://www.youtube.com/s/player/0a1b2c3d/player_ias.vflset/en_GB/base.js"
    private val script = """var _yt_player={};(function(g){var a={signatureTimestamp:20375,other:1};})(_yt_player);"""

    private class Pages(val pages: MutableMap<String, String?>) {
        val asked = mutableListOf<String>()
        fun get(url: String): String? {
            asked += url
            return pages[url]
        }
    }

    private var clock = 1_000_000L

    private fun scripts(pages: Pages) = PlayerScripts(pages::get) { clock }

    @Test
    fun `the player is named in the page NewPipe reads it from too`() {
        assertEquals("0a1b2c3d", PlayerScripts.idIn(iframeApi))
        assertNull(PlayerScripts.idIn("nothing of the kind"))
        // Never an address built from something that is not a name.
        assertNull(PlayerScripts.idIn("""player\/..%2f..%2fevil\/"""))
        assertEquals(scriptAddress, PlayerScripts.address("0a1b2c3d"))
    }

    @Test
    fun `the signature timestamp is read from the script, written either way`() {
        assertEquals(20375, PlayerScripts.signatureTimestampIn("a={signatureTimestamp:20375,b:2}"))
        assertEquals(20375, PlayerScripts.signatureTimestampIn("this.signatureTimestamp=20375;"))
        assertNull(PlayerScripts.signatureTimestampIn("no timestamp here"))
        assertNull(PlayerScripts.signatureTimestampIn("signatureTimestamp:99999999999999999999"))
    }

    @Test
    fun `the script is fetched once and kept, with the timestamp read from that very text`() = runBlocking {
        val pages = Pages(mutableMapOf(PlayerScripts.IFRAME_API to iframeApi, scriptAddress to script))
        val scripts = scripts(pages)
        val first = scripts.current()!!

        assertEquals("0a1b2c3d", first.id)
        assertEquals(script, first.text)
        assertEquals(20375, first.signatureTimestamp)
        assertEquals(listOf(PlayerScripts.IFRAME_API, scriptAddress), pages.asked)

        clock += PlayerScripts.REFRESH_MS - 1
        assertSame(first, scripts.current())
        assertEquals("nothing was asked a second time", 2, pages.asked.size)
    }

    @Test
    fun `after a while the page is asked again, and a player that has not changed is not fetched again`() = runBlocking {
        val pages = Pages(mutableMapOf(PlayerScripts.IFRAME_API to iframeApi, scriptAddress to script))
        val scripts = scripts(pages)
        val first = scripts.current()!!

        clock += PlayerScripts.REFRESH_MS
        assertSame(first, scripts.current())
        assertEquals(listOf(PlayerScripts.IFRAME_API, scriptAddress, PlayerScripts.IFRAME_API), pages.asked)

        // A new player: its script is fetched, and its timestamp is the one that goes out from now on.
        val newAddress = "https://www.youtube.com/s/player/9f8e7d6c/player_ias.vflset/en_GB/base.js"
        pages.pages[PlayerScripts.IFRAME_API] = iframeApi.replace("0a1b2c3d", "9f8e7d6c")
        pages.pages[newAddress] = script.replace("20375", "20381")
        clock += PlayerScripts.REFRESH_MS
        val second = scripts.current()!!
        assertEquals("9f8e7d6c", second.id)
        assertEquals(20381, second.signatureTimestamp)
    }

    @Test
    fun `a script that cannot be had is not asked for again at once, and is asked for again later`() = runBlocking {
        val pages = Pages(mutableMapOf(PlayerScripts.IFRAME_API to null, scriptAddress to script))
        val scripts = scripts(pages)

        assertNull(scripts.current())
        assertNull(scripts.current())
        assertEquals("one try, not one for every song", listOf(PlayerScripts.IFRAME_API), pages.asked)

        pages.pages[PlayerScripts.IFRAME_API] = iframeApi
        clock += PlayerScripts.RETRY_MS
        assertEquals(20375, scripts.current()?.signatureTimestamp)
    }

    @Test
    fun `a script without a timestamp is no use, and the one before it is kept`() = runBlocking {
        val pages = Pages(mutableMapOf(PlayerScripts.IFRAME_API to iframeApi, scriptAddress to script))
        val scripts = scripts(pages)
        val first = scripts.current()!!

        val newAddress = "https://www.youtube.com/s/player/9f8e7d6c/player_ias.vflset/en_GB/base.js"
        pages.pages[PlayerScripts.IFRAME_API] = iframeApi.replace("0a1b2c3d", "9f8e7d6c")
        pages.pages[newAddress] = "var _yt_player={};"
        clock += PlayerScripts.REFRESH_MS
        assertSame(first, scripts.current())
    }
}

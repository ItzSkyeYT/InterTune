/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The list of AI artists the AI filter goes by: the bundled asset, and how a list is read. */
class AiArtistListTest {

    private val asset = File("src/main/assets/${AiArtistList.ASSET}")

    @Test
    fun `the bundled list reads whole, over a thousand channel ids from its sources`() {
        val list = AiArtistList.parse(asset.readText())
        assertEquals("lines that are not channel ids", 0, list.unreadable)
        assertTrue("${list.ids.size} ids", list.ids.size > 1000)
        assertEquals(listOf("known", "own", "cevval"), list.sources.keys.toList())
        assertEquals(1023, list.sources["cevval"])
        assertEquals(list.ids.size, list.sources.values.sum())
        assertTrue(list.built!!.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun `the known AI acts are on it, by their YouTube Music artist ids`() {
        val list = AiArtistList.parse(asset.readText())
        val acts = mapOf(
            "UCLeR-eGh2OFy9ydX7IUcOTw" to "The Velvet Sundown",
            "UCTH2Iy0erLYQSPRILNaG2sg" to "Breaking Rust",
            "UCg7vw1Vm0kn1PHI8SXpac1A" to "Xania Monet",
            "UC8dZlbz3KhPF1_g1SFYJzXw" to "IngaRose",
        )
        for ((id, name) in acts) assertTrue("$name is not on the list", id in list.ids)
        assertEquals(acts.size, list.sources["known"])
    }

    @Test
    fun `the list names its sources and the day it was built, and says what it left out`() {
        val head = asset.readLines().takeWhile { it.startsWith("#") }.joinToString("\n")
        assertTrue("github.com/cevvalkoala/CevvalYoutubeAIBlocklist" in head)
        assertTrue("Unlicense" in head)
        assertTrue(Regex("""copy of \d{4}-\d{2}-\d{2}""").containsMatchIn(head))
        assertTrue(Regex("""left out: \d+ @handles""").containsMatchIn(head))
    }

    @Test
    fun `comments, sources and notes are read as such, a damaged line costs only itself`() {
        val list = AiArtistList.parse(
            """
            # a list
            # built 2026-10-25
            [known]
            UCLeR-eGh2OFy9ydX7IUcOTw The Velvet Sundown
            UCTH2Iy0erLYQSPRILNaG2sg	Breaking Rust

            [cevval]
            UCLeR-eGh2OFy9ydX7IUcOTw
            @SomeHandle
            UCtooShort
            UCxxxxxxxxxxxxxxxxxxxxx1
            """.trimIndent()
        )
        assertEquals(setOf("UCLeR-eGh2OFy9ydX7IUcOTw", "UCTH2Iy0erLYQSPRILNaG2sg", "UCxxxxxxxxxxxxxxxxxxxxx1"), list.ids)
        // An id two sources share counts once, for the first.
        assertEquals(mapOf("known" to 2, "cevval" to 1), list.sources)
        assertEquals("2026-10-25", list.built)
        assertEquals(2, list.unreadable)
    }

    @Test
    fun `an empty or unreadable file is an empty list, not a crash`() {
        val empty = AiArtistList.parse("")
        assertTrue(empty.ids.isEmpty())
        assertNull(empty.built)
        assertEquals(1, AiArtistList.parse("not a list at all").unreadable)
    }

    @Test
    fun `a channel id is UC and 22 characters of the url-safe alphabet`() {
        assertTrue(AiArtistList.isChannelId("UC8dZlbz3KhPF1_g1SFYJzXw"))
        assertTrue(AiArtistList.isChannelId("UCLeR-eGh2OFy9ydX7IUcOTw"))
        assertFalse(AiArtistList.isChannelId("UC8dZlbz3KhPF1_g1SFYJzX"))
        assertFalse(AiArtistList.isChannelId("LA8dZlbz3KhPF1_g1SFYJzXw"))
        assertFalse(AiArtistList.isChannelId("FEmusic_library_privately_owned_artist_detail"))
        assertFalse(AiArtistList.isChannelId("UC8dZlbz3KhPF1+g1SFYJzXw"))
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two fixes of 27 Oct 2026. #53: a list given the same item twice crashed the app ("Key ... was
 * already used"), and YouTube can list a song twice on an artist's page. dvd: the slim bar's icons
 * sat high, because each tab still handed Material an empty label, which keeps its room.
 */
class ListKeysAndSlimBarTest {
    private fun read(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `the artist's lists never key two rows the same`() {
        for (path in listOf("ui/screens/artist/ArtistItemsScreen.kt", "ui/screens/artist/ArtistScreen.kt")) {
            assertFalse("$path still keys by hashCode", read(path).contains("key = { _, item -> item.hashCode() }"))
        }
        val items = read("ui/screens/artist/ArtistItemsScreen.kt")
        assertEquals(2, Regex("""key = \{ index, item -> "\$\{item\.id\}#\${'$'}index" \}""").findAll(items).count())
        val artist = read("ui/screens/artist/ArtistScreen.kt")
        assertTrue(artist.contains("key = { index, item -> \"song:\${item.id}#\$index\" }"))
        assertEquals(2, Regex("""items = section\.items\.distinctBy \{ it\.id \}""").findAll(artist).count())
        assertTrue(artist.contains("items = libraryAlbums.distinctBy { it.id }"))
    }

    @Test
    fun `a slim bar's tabs have no label, so the icon sits in the middle`() {
        val main = read("MainActivity.kt")
        assertEquals(2, Regex("""label = if \(slimNav\) null else \(\{ TabLabel\(""").findAll(main).count())
        assertFalse(main.contains("if (!slimNav) TabLabel("))
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * The apps the import's first page offers, and the page each one's button opens in the browser.
 * An address that a tap opens is checked here letter by letter: https, the tool's own host and
 * nothing else, and for TuneMyMusic the page that exports to a file, not the one that transfers
 * to another service.
 */
class ImportSourcesTest {

    @Test
    fun `every app has a tool, and its button opens a page on that tool's own site`() {
        for (source in ImportSource.entries) {
            val address = URI(source.address)
            assertEquals(source.name, "https", address.scheme)
            assertEquals(source.name, source.tool.host, address.host)
            assertTrue(source.name, address.userInfo == null && address.query == null && address.fragment == null)
        }
    }

    @Test
    fun `Spotify goes through Exportify and the others through TuneMyMusic's export to a file`() {
        assertEquals(ImportTool.EXPORTIFY, ImportSource.SPOTIFY.tool)
        assertEquals("https://exportify.app/", ImportSource.SPOTIFY.address)
        for (source in ImportSource.entries - ImportSource.SPOTIFY) {
            assertEquals(source.name, ImportTool.TUNEMYMUSIC, source.tool)
            if (source != ImportSource.OTHER) {
                assertTrue(source.name, Regex("""/transfer/[a-z-]+-to-file""").matches(URI(source.address).path))
            }
        }
        // The app it could be anything from gets the tool's front page, where the apps are listed.
        assertEquals("/", URI(ImportSource.OTHER.address).path)
    }

    @Test
    fun `Spotify is first, anything else is last, and each app is there once under its own name`() {
        assertEquals(ImportSource.SPOTIFY, ImportSource.entries.first())
        assertEquals(ImportSource.OTHER, ImportSource.entries.last())
        val named = ImportSource.entries - ImportSource.OTHER
        assertTrue(named.all { it.label.isNotBlank() })
        assertEquals(named.size, named.map { it.label }.toSet().size)
        assertEquals("", ImportSource.OTHER.label)
        assertEquals(listOf(ImportSource.APPLE_MUSIC), ImportSource.entries.filter { it.alsoFromComputer })
    }

    @Test
    fun `every app named has its icon, and the row for any other app has none`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/LibraryImportScreen.kt").readText()
        val icons = screen.substringAfter("private fun sourceIcon(").substringBefore("\n}\n")
        for (source in ImportSource.entries - ImportSource.OTHER) {
            val line = icons.lines().single { "ImportSource.${source.name} ->" in it }
            val drawable = Regex("""R\.drawable\.([a-z_]+)""").find(line)!!.groupValues[1]
            assertEquals("import_source_" + source.name.lowercase(), drawable)
            assertTrue(drawable, File("src/main/res/drawable-nodpi/$drawable.png").isFile)
        }
        assertTrue("ImportSource.OTHER -> null" in icons)
    }

    @Test
    fun `no tool here asks for money before it gives a file`() {
        // Soundiiz writes files on its paid plans only (12 Oct 2026). Its files are still read.
        assertFalse(ImportTool.entries.any { "soundiiz" in it.label.lowercase() || "soundiiz" in it.host })
        assertFalse(ImportSource.entries.any { "soundiiz" in it.address })
    }
}

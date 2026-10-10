/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What is kept of a player script between runs, and what a new process does with it. No network: every script here is made up. */
class SolverFilesTest {
    private val folder: File = Files.createTempDirectory("solver-files").toFile()
    private val files = SolverFiles(folder)

    private val first = "0a1b2c3d"
    private val second = "9z8y7x6w"
    private fun scriptOf(id: String) = "var player='$id';signatureTimestamp:20381,rest"

    @After
    fun clean() {
        folder.deleteRecursively()
    }

    @Test
    fun `nothing kept is nothing read`() {
        assertNull(files.current())
        assertNull(files.script(first))
        assertNull(files.prepared(first))
    }

    @Test
    fun `a script is read back with when its player was last seen, and that time can move on`() {
        files.keepScript(first, scriptOf(first), seenAt = 1_000)
        assertEquals(first, files.current()?.id)
        assertEquals(1_000L, files.current()?.seenAt)
        assertEquals(scriptOf(first), files.script(first))
        files.seen(first, 5_000)
        assertEquals(5_000L, files.current()?.seenAt)
        // Seeing a player whose script is not kept changes nothing.
        files.seen(second, 9_000)
        assertEquals(first, files.current()?.id)
    }

    @Test
    fun `a prepared script is kept beside its script, and goes when it is dropped`() {
        files.keepPrepared(first, "prepared with no script beside it")
        assertNull("not without the script it was made from", files.prepared(first))
        files.keepScript(first, scriptOf(first), seenAt = 1_000)
        files.keepPrepared(first, "function sig(){} function n(){}")
        assertEquals("function sig(){} function n(){}", files.prepared(first))
        files.dropPrepared(first)
        assertNull(files.prepared(first))
        assertEquals("the script itself stays", scriptOf(first), files.script(first))
    }

    @Test
    fun `a new player takes the place of the old one, prepared script and all`() {
        files.keepScript(first, scriptOf(first), seenAt = 1_000)
        files.keepPrepared(first, "prepared from the first")
        files.keepScript(second, scriptOf(second), seenAt = 2_000)
        assertEquals(second, files.current()?.id)
        assertNull(files.script(first))
        assertNull(files.prepared(first))
        assertEquals(setOf("current", "$second.player.js"), folder.list()?.toSet())
    }

    @Test
    fun `a name that is not a player's never becomes a file's`() {
        for (odd in listOf("../../escape", "a/b", "", "short", "way-too-long-to-be-a-player-name", "with space")) {
            files.keepScript(odd, "text", seenAt = 1)
            files.keepPrepared(odd, "text")
            assertNull(odd, files.script(odd))
            assertNull(odd, files.prepared(odd))
        }
        assertTrue("nothing was written anywhere", folder.list().isNullOrEmpty())
        assertTrue(folder.parentFile?.list()?.none { it == "escape.player.js" } != false)
    }

    @Test
    fun `a note of the current player that cannot be read is no note`() {
        folder.mkdirs()
        for (damaged in listOf("", "0a1b2c3d", "0a1b2c3d soon", "../x 5", "one two three")) {
            File(folder, "current").writeText(damaged)
            assertNull(damaged, files.current())
        }
    }

    // --- what PlayerScripts does with it ---

    private fun page(id: String) = """var scriptUrl = 'https:\/\/www.youtube.com\/s\/player\/$id\/www-widgetapi.vflset\/www-widgetapi.js';"""

    private class Network(var current: String, private val scripts: (String) -> String) {
        val asked = mutableListOf<String>()
        val fetch: (String) -> String? = { url ->
            asked += url
            if (url == PlayerScripts.IFRAME_API) """var scriptUrl = 'https:\/\/www.youtube.com\/s\/player\/$current\/www-widgetapi.vflset\/www-widgetapi.js';""" else scripts(current)
        }
    }

    @Test
    fun `a new process takes up the script the last one kept, and within six hours asks YouTube nothing`() = runBlocking {
        var now = 1_000_000L
        val network = Network(first, ::scriptOf)
        val before = PlayerScripts(network.fetch, files) { now }.current()
        assertEquals(listOf(PlayerScripts.IFRAME_API, PlayerScripts.address(first)), network.asked)

        now += PlayerScripts.REFRESH_MS - 1
        val later = Network(first, ::scriptOf)
        val taken = PlayerScripts(later.fetch, files) { now }.current()
        assertEquals("nothing was fetched", emptyList<String>(), later.asked)
        assertEquals(before?.id, taken?.id)
        assertEquals(before?.text, taken?.text)
        assertEquals(20_381, taken?.signatureTimestamp)
    }

    @Test
    fun `after six hours only the page is asked, when it still names the player that is kept`() = runBlocking {
        var now = 1_000_000L
        PlayerScripts(Network(first, ::scriptOf).fetch, files) { now }.current()

        now += PlayerScripts.REFRESH_MS + 1
        val later = Network(first, ::scriptOf)
        val scripts = PlayerScripts(later.fetch, files) { now }
        val taken = scripts.current()
        assertEquals("the page, and not the script again", listOf(PlayerScripts.IFRAME_API), later.asked)
        assertEquals(first, taken?.id)
        assertEquals("and the time it was seen moved on", now, files.current()?.seenAt)
        assertSame("once more within the six hours asks nothing", taken, scripts.current())
        assertEquals(1, later.asked.size)
    }

    @Test
    fun `a new player is fetched and kept, and the old one's files go`() = runBlocking {
        var now = 1_000_000L
        PlayerScripts(Network(first, ::scriptOf).fetch, files) { now }.current()
        files.keepPrepared(first, "prepared from the first")

        now += PlayerScripts.REFRESH_MS + 1
        val later = Network(second, ::scriptOf)
        val taken = PlayerScripts(later.fetch, files) { now }.current()
        assertEquals(listOf(PlayerScripts.IFRAME_API, PlayerScripts.address(second)), later.asked)
        assertEquals(second, taken?.id)
        assertEquals(second, files.current()?.id)
        assertNull(files.prepared(first))
        assertNull(files.script(first))
    }

    @Test
    fun `a kept script with no timestamp in it is not taken up, and YouTube is asked as before`() = runBlocking {
        files.keepScript(first, "not a player script at all", seenAt = 1_000_000L)
        val network = Network(first, ::scriptOf)
        val taken = PlayerScripts(network.fetch, files) { 1_000_001L }.current()
        assertEquals(listOf(PlayerScripts.IFRAME_API, PlayerScripts.address(first)), network.asked)
        assertEquals(20_381, taken?.signatureTimestamp)
    }
}

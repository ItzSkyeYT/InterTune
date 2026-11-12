/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.edit.InMemoryTransitionStore
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.TransitionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * A write of the saved transitions asked for by a tap (the pill's menu, Settings > Mixing > Clear)
 * runs in a coroutine of its own that outlives the page, where nothing catches what it throws: a
 * full disk or a file that cannot be replaced closed the app. Every such write goes through
 * MixStores.changeTransitions, which catches and says so.
 */
class MixStoreWritesTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `a write the store refuses is caught and told, never thrown`() {
        val refusing = object : TransitionStore by InMemoryTransitionStore() {
            override fun put(spec: TransitionSpec) = throw IOException("no space left on device")
            override fun delete(pair: SongPair): Boolean = throw IOException("cannot replace transitions.json")
            override fun clear(): Int = throw IOException("no space left on device")
        }
        assertFalse(MixStores.tryChange(refusing) { it.clear() })
        assertFalse(MixStores.tryChange(refusing) { it.delete(SongPair("a", "b")) })
        assertTrue(MixStores.tryChange(InMemoryTransitionStore()) { it.clear() })
    }

    @Test
    fun `no page writes the saved transitions straight from a tap`() {
        val straight = Regex("""MixStores\.transitions\([^)]*\)\s*\.\s*(put|delete|clear)\(""")
        val found = File(sources, "ui").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> straight.findAll(code(file.readText())).map { "${file.relativeTo(sources).path}: ${it.value}" } }
            .toList()
        assertEquals(emptyList<String>(), found)
    }
}

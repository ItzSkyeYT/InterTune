/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import androidx.compose.ui.graphics.Color
import com.dd3boh.outertune.ui.theme.ThemeSeed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The song's colours before Play, after an update or a restart (24 Nov 2026). */
class ThemeBeforePlayTest {

    private class MapStore : ThemeSeed.Store {
        val ints = mutableMapOf<String, Int>()
        val strings = mutableMapOf<String, String>()
        override fun getInt(key: String) = ints[key]
        override fun getString(key: String) = strings[key]
        override fun put(key: String, int: Int?, string: String?) {
            // The two keys of the seed, set or removed together.
            ints.clear(); strings.clear()
            if (int != null) ints["colour"] = int
            if (string != null) strings["song"] = string
        }
    }

    private val blue = Color(0xFF3366CC)
    private val green = Color(0xFF33AA55)

    @Test
    fun `nothing kept, nothing at start`() {
        val seed = ThemeSeed(MapStore())
        assertNull(seed.atStart())
        assertNull(seed.forSong("a"))
    }

    @Test
    fun `a new song's colour is written and read back at start`() {
        val store = MapStore()
        ThemeSeed(store).write("a", blue)
        val next = ThemeSeed(store)
        assertEquals(blue, next.atStart())
        assertEquals(blue, next.forSong("a"))
    }

    @Test
    fun `the kept colour is for that song only`() {
        val store = MapStore()
        ThemeSeed(store).write("a", blue)
        assertNull(ThemeSeed(store).forSong("b"))
    }

    @Test
    fun `a newer song replaces the older one's colour`() {
        val store = MapStore()
        val seed = ThemeSeed(store)
        seed.write("a", blue)
        seed.write("b", green)
        assertEquals(green, seed.atStart())
        assertNull(seed.forSong("a"))
        assertEquals(green, seed.forSong("b"))
    }

    @Test
    fun `cleared, the next start has none`() {
        val store = MapStore()
        val seed = ThemeSeed(store)
        seed.write("a", blue)
        seed.clear()
        assertNull(seed.atStart())
        assertNull(seed.forSong("a"))
    }

    private fun source(path: String): String {
        val roots = listOf("", "app/")
        val f = roots.map { File(it + path) }.first { it.exists() }
        return f.readText()
    }

    @Test
    fun `the theme starts from the kept colour, not the default, and follows the song the mini player shows`() {
        val main = source("src/main/java/com/dd3boh/outertune/MainActivity.kt")
        val start = main.indexOf("var themeColor by rememberSaveable")
        assertTrue(start > 0)
        val first = main.substring(start, start + 400)
        assertTrue("the first value reads the kept colour", first.contains("themeSeed.atStart()"))
        assertTrue("dynamic theme off keeps the default", first.contains("enableDynamicTheme"))
        val effect = main.substring(main.indexOf("LaunchedEffect(playerConnection, enableDynamicTheme"))
            .substringBefore("OuterTuneTheme(")
        assertTrue("the song shown, not the player's empty one", effect.contains("playerConnection.mediaMetadata.collectLatest"))
        assertTrue(!effect.contains("service.currentMediaMetadata"))
        assertNotNull(effect.indexOf("themeSeed.write"))
        assertTrue(effect.contains("themeSeed.write"))
    }
}

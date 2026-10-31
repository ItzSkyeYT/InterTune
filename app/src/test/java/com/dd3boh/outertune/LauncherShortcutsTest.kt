/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The launcher's shortcuts send the actions MainActivity handles.
 *
 * Each build that installs under a package of its own carries its own shortcuts.xml, and nothing
 * else ties the actions in them to MainActivity's constants. The debug build's kept the actions
 * from before the rename to dev.skye.intertune, so its shortcuts opened the app and did nothing.
 */
class LauncherShortcutsTest {

    private val handled = setOf(
        MainActivity.ACTION_SEARCH,
        MainActivity.ACTION_SONGS,
        MainActivity.ACTION_ALBUMS,
        MainActivity.ACTION_PLAYLISTS,
        MainActivity.ACTION_PLAY_LIKED,
    )

    @Test
    fun `every build's shortcuts carry the actions MainActivity handles`() {
        val files = File("src").listFiles().orEmpty()
            .associate { it.name to File(it, "res/xml/shortcuts.xml") }
            .filterValues { it.exists() }
        // Fewer would mean this is not looking where it thinks it is.
        assertTrue(files.keys.toString(), files.keys.containsAll(listOf("main", "debug", "preview", "beta")))
        val packages = mapOf(
            "main" to "dev.skye.intertune", "debug" to "dev.skye.intertune.debug",
            "preview" to "dev.skye.intertune.preview", "beta" to "dev.skye.intertune.tester",
        )

        for ((sourceSet, file) in files) {
            val actions = Regex("android:action=\"([^\"]+)\"").findAll(file.readText())
                .map { it.groupValues[1] }.toList()
            assertEquals(sourceSet, handled.size, actions.size)
            assertEquals(sourceSet, handled, actions.toSet())
            // Each shortcut opens the app it is in, not the release's or the Preview's: the one that installs under a
            // package of its own names that package in every shortcut.
            val targets = Regex("android:targetPackage=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toList()
            assertEquals(sourceSet, List(handled.size) { packages.getValue(sourceSet) }, targets)
        }
    }
}

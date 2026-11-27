/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings home is its rows' names. On 24 Nov 2026 it kept two lines, under Library and content
 * and under Advanced; on 26 Nov he sent the two rows with "just remove these", as the testers had
 * said they stood out once the rest were gone. The one line left is the update that is waiting: it
 * says something the name cannot. The strings stay in the files for translators until a cleanup.
 */
class SettingsHomeLinesTest {

    private val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/SettingsScreen.kt")
        .readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the only line on the Settings home is the update that is waiting`() {
        assertEquals(1, Regex("""\bdescription = """).findAll(screen).count())
        assertTrue("description = pendingUpdate?.let {" in screen)
    }

    @Test
    fun `Library and content and Advanced have their names alone`() {
        assertFalse("R.string.settings_library_description" in screen)
        assertFalse("R.string.settings_advanced_description" in screen)
    }
}

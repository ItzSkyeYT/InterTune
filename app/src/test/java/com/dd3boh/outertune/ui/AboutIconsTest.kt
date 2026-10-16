/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * About's rows wear an icon each, like every other page of Settings: a card of bare titles reads
 * as headings, not as things to tap (his question of 16 Oct 2026, and Local media's folders row).
 */
class AboutIconsTest {
    @Test
    fun `every row in About wears an icon`() {
        val about = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/AboutScreen.kt").readText()
        val rows = about.split("PreferenceEntry(").drop(1)
        assertTrue("About has fewer rows than it did", rows.size >= 10)
        for (row in rows) {
            val head = row.substring(0, row.indexOf("onClick"))
            val title = head.lines().firstOrNull { "title" in it }?.trim()
            assertTrue("a row without an icon: $title", "icon = {" in head)
        }
    }

    @Test
    fun `the address shows under its row`() {
        val about = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/AboutScreen.kt").readText()
        assertTrue("description = Links.EMAIL" in about)
    }
}

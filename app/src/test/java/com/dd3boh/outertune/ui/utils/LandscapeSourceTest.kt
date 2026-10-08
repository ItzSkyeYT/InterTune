/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * What the layout for a phone on its side asks of the screens. It reads the source, because
 * none of it can be made to fail to compile.
 */
class LandscapeSourceTest {

    private val sources = File("src/main/java")

    private fun kotlinFiles() = sources.walkTopDown().filter { it.extension == "kt" }

    /**
     * A top bar's default insets leave room for the navigation rail. Five pages handed theirs the
     * system's own instead, and wherever the rail showed (a tablet, a phone on its side) their
     * back button was drawn under it. Only Dimensions.kt, which builds the default, names them.
     */
    @Test
    fun `no page hands a top bar the system's own insets`() {
        val found = kotlinFiles()
            .filter { it.name != "Dimensions.kt" && "TopAppBarDefaults.windowInsets" in it.readText() }
            .map { it.relativeTo(sources).path }
            .toList()
        assertEquals(
            "these would put the back button under the rail:\n" + found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
    }

    /**
     * A header goes beside its list only where rememberHeaderBeside says the window has two
     * upright widths to give, and a list runs two abreast only by rememberListColumns. A screen
     * that decided either from Landscape.active alone would squeeze a small phone on its side
     * into halves of 265dp.
     */
    @Test
    fun `every screen takes its halves and its columns from the one place`() {
        val wrong = kotlinFiles().filter { it.name != "Landscape.kt" }.mapNotNull { file ->
            val text = file.readText()
            when {
                "HeaderBesideList(" in text && "rememberHeaderBeside()" !in text ->
                    "${file.name}: HeaderBesideList without rememberHeaderBeside"

                "itemsInColumns(" in text && "rememberListColumns()" !in text ->
                    "${file.name}: itemsInColumns without rememberListColumns"

                else -> null
            }
        }.toList()
        assertEquals(wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    /**
     * Beside its list the header never scrolls away, and it says the page's name. A page that
     * also put the name in the top bar once its list had moved said it twice, one above the
     * other: Liked, Downloaded and Favourite artists did, which are one screen.
     */
    @Test
    fun `a page with its header beside its list does not say its name in the top bar as well`() {
        val twice = kotlinFiles().filter { "HeaderBesideList(" in it.readText() }.flatMap { file ->
            file.readLines().filter { "if (showTopBarTitle" in it && "showTopBarTitle && !twoPanes" !in it }
                .map { "${file.name}: ${it.trim()}" }
        }.toList()
        assertEquals(twice.joinToString("\n"), emptyList<String>(), twice)
    }
}

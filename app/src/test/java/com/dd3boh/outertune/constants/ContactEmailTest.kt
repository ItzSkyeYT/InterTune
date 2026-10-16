/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The address About copies is the project's own, and only Links spells it out. */
class ContactEmailTest {
    @Test
    fun `About copies the project's address`() {
        assertEquals("intertune.dev@gmail.com", Links.EMAIL)
        val about = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/AboutScreen.kt").readText()
        assertTrue("About no longer copies Links.EMAIL", "AnnotatedString(Links.EMAIL)" in about)
    }

    @Test
    fun `the old alias is gone from the app`() {
        File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.forEach {
            assertFalse("${it.path} still has the old address", "bravealias" in it.readText())
        }
    }
}

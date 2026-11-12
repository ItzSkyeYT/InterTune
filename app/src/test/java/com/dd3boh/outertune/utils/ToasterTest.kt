/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A new toast takes the place of the one showing instead of waiting behind it (his word, 4 Nov
 * 2026). Read from the source, as the flag tests are: a toast made anywhere but Toaster would
 * queue again.
 */
class ToasterTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `every toast of the app goes through Toaster`() {
        val direct = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Toaster.kt" }
            .filter { "Toast.makeText(" in it.readText() }
            .map { it.relativeTo(sources).path }
            .toList()
        assertEquals(emptyList<String>(), direct)
    }

    @Test
    fun `Toaster puts the one showing away before it shows the next`() {
        val toaster = File(sources, "utils/Toaster.kt").readText()
        val cancel = toaster.indexOf("showing?.cancel()")
        val show = toaster.indexOf("toast.show()")
        assertTrue(cancel >= 0 && show > cancel)
        assertTrue("it keeps no screen alive", "context.applicationContext" in toaster)
    }
}

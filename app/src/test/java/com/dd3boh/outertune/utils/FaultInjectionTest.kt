/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Faults made on purpose belong to debug builds and nowhere else. Read from the source, as the
 * other guards are, and tried with the switch off.
 */
class FaultInjectionTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @After
    fun off() {
        FaultInjection.enabled = true
        FaultInjection.set(FaultInjection.Mode.NONE)
    }

    @Test
    fun `the switch is the build's own debug flag`() {
        assertTrue(source("utils/FaultInjection.kt").contains("internal var enabled: Boolean = BuildConfig.DEBUG"))
    }

    @Test
    fun `every client gets the interceptor only when the switch is on`() {
        for (path in listOf("utils/YTPlayerUtils.kt", "utils/StreamFamily.kt")) {
            val text = source(path)
            val calls = Regex("""FaultInjection\.interceptor\(""").findAll(text).count()
            val guarded = Regex("""if \(FaultInjection\.enabled\) addInterceptor\(FaultInjection\.interceptor\(""").findAll(text).count()
            assertTrue("$path has one", calls > 0)
            assertEquals(path, calls, guarded)
        }
    }

    @Test
    fun `the developer options' row is in a debug build only`() {
        val frag = source("ui/screens/settings/fragments/DeveloperFrag.kt")
        val row = frag.indexOf("FaultInjection.Mode.entries")
        val guard = frag.lastIndexOf("if (BuildConfig.DEBUG) {", row)
        assertTrue(row > 0 && guard > 0)
        assertFalse("the guard is closed before the row", frag.substring(guard, row).contains("\n        }\n"))
    }

    @Test
    fun `with the switch off nothing can be set and nothing hangs`() {
        FaultInjection.enabled = false
        FaultInjection.set(FaultInjection.Mode.PLAYER_HANGS_VISIONOS)
        assertEquals(FaultInjection.Mode.NONE, FaultInjection.mode)
        assertFalse(FaultInjection.hangsPlayer("VISIONOS", isVisionosItself = true))
    }

    @Test
    fun `only VISIONOS itself is made to hang, not its other identities`() {
        FaultInjection.enabled = true
        FaultInjection.set(FaultInjection.Mode.PLAYER_HANGS_VISIONOS)
        assertTrue(FaultInjection.hangsPlayer("VISIONOS", isVisionosItself = true))
        assertFalse(FaultInjection.hangsPlayer("VISIONOS", isVisionosItself = false))
        assertFalse(FaultInjection.hangsPlayer("IOS", isVisionosItself = false))
    }
}

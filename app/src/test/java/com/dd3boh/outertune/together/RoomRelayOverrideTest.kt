/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The relays of a room can be overridden for the test kit (TogetherRelaysKey, our own relay on ws://10.0.2.2:7781), in
 * a debug build. It was read in every build: a key restored from a debug build's backup pointed a release at a relay it
 * refuses (cleartext is allowed only in src/debug), and its rooms never connected (review of 0.13, 18 Nov 2026).
 */
class RoomRelayOverrideTest {

    @Test
    fun `only a debug build takes the relays from the settings`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        val relays = dialog.substringAfter("suspend fun togetherRelays(").substringBefore("\n\n")
        val key = relays.indexOf("[TogetherRelaysKey]")
        val gate = relays.indexOf("?.takeIf { BuildConfig.DEBUG }")
        assertTrue("the override is read with no look at the build", gate > key && key >= 0)
        assertTrue(gate < relays.indexOf("?.split(','"))
        assertTrue("?: TogetherSession.PUBLIC_RELAYS" in relays)
        assertTrue("import com.dd3boh.outertune.BuildConfig" in dialog)
        // and it is the only place the key is read
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        val readers = sources.filter { "[TogetherRelaysKey]" in it.readText() }.map { it.name }.toList()
        assertTrue("$readers", readers == listOf("TogetherDialog.kt"))
    }
}

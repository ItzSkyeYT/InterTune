/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The room's lines that count songs ("Added to the room: 3 songs play next", "Ana added 5 songs to the queue") are
 * plurals, so a language whose word changes with the number (2 to 4 and 5 or more in Polish or Russian) can say each
 * right. They became plurals before they ever went to translators (23 Nov 2026).
 */
class RoomCountTextsTest {

    private val names = listOf("together_suggested_many", "together_suggested_many_end", "together_suggested_cut", "together_from_guest_many")

    private fun read(path: String) = File("src/main/$path").readText()

    @Test
    fun `the counting lines are plurals in English and French`() {
        for (file in listOf("res/values/strings-ot.xml", "res/values-fr/strings-ot.xml")) {
            val text = read(file)
            for (name in names) {
                val block = text.substringAfter("<plurals name=\"$name\">", "").substringBefore("</plurals>")
                assertTrue("$name is not a plural in $file", block.isNotEmpty())
                assertTrue("$name has no one in $file", "quantity=\"one\"" in block)
                assertTrue("$name has no other in $file", "quantity=\"other\"" in block)
                assertFalse("$name is still a string in $file", "<string name=\"$name\"" in text)
            }
        }
    }

    @Test
    fun `the code reads them as plurals`() {
        val code = read("java/com/dd3boh/outertune/playback/PlayerConnection.kt") +
            read("java/com/dd3boh/outertune/together/TogetherSession.kt")
        for (name in names) {
            assertTrue("$name is not read with getQuantityString", "R.plurals.$name" in code)
            assertFalse("$name is still read as a string", "R.string.$name" in code)
        }
    }
}

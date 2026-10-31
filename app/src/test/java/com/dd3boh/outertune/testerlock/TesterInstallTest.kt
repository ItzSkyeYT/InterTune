/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The number an install makes for itself: random, 128 bits, and the shape the door accepts. */
class TesterInstallTest {

    @Test
    fun `a number is 32 lower-case hex digits`() {
        repeat(200) {
            val install = TesterInstall.make()
            assertEquals(32, install.length)
            assertTrue(install, Regex("^[0-9a-f]{32}$").matches(install))
            assertTrue(install, TesterInstall.isWellFormed(install))
        }
    }

    @Test
    fun `two installs never make the same number`() {
        val numbers = List(2000) { TesterInstall.make() }
        assertEquals(numbers.size, numbers.toSet().size)
        // 128 bits: every digit takes every value somewhere, and no position is stuck.
        for (at in 0 until 32) assertTrue("position $at", numbers.map { it[at] }.toSet().size >= 12)
    }

    @Test
    fun `the bytes are the generator's, one for one, the highest digit first`() {
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) bytes[i] = (i * 17 + 0xa0).toByte()
            }
        }
        val expected = (0 until 16).joinToString("") { "%02x".format((it * 17 + 0xa0) and 0xff) }
        assertEquals(expected, TesterInstall.make(fixed))
        assertEquals("a0b1c2d3e4f5061728394a5b6c7d8e9f", TesterInstall.make(fixed))
    }

    @Test
    fun `a number that is not the door's shape is not well formed`() {
        val good = "0123456789abcdef0123456789abcdef"
        assertTrue(TesterInstall.isWellFormed(good))
        for (bad in listOf(
            "", "0", good.dropLast(1), good + "0", good.uppercase(), good.replace('0', 'g'), good.replace('1', '-'),
            " $good", "$good\n", good.replaceRange(5, 6, " "),
        )) {
            assertFalse(bad, TesterInstall.isWellFormed(bad))
        }
    }
}

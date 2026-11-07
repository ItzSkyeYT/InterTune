/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * MixCode.STAMP, which the Mix editor keeps its analyses and Auto's picks under, is the stamp of
 * the sources as they are: computed here again the way the build writes it, so a build that
 * stopped writing it, or wrote it from other files, is caught.
 */
class MixCodeTest {
    @Test
    fun `the stamp is the one of this module's sources and the FFT it borrows`() {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((label, root) in listOf("mix" to File("src/main"), "fingerprint" to File("../fingerprint/src/main"))) {
            assertTrue(root.absolutePath, root.isDirectory)
            val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
            for (path in files) {
                digest.update("$label/$path".toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(root.resolve(path).readBytes())
                digest.update(0)
            }
        }
        val stamp = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(stamp, MixCode.STAMP)
    }
}

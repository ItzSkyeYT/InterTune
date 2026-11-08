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
    private fun stampOf(keep: (label: String, path: String) -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((label, root) in listOf("mix" to File("src/main"), "fingerprint" to File("../fingerprint/src/main"))) {
            assertTrue(root.absolutePath, root.isDirectory)
            val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
            for (path in files) {
                if (!keep(label, path)) continue
                digest.update("$label/$path".toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(root.resolve(path).readBytes())
                digest.update(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val mixing = listOf("/auto/", "/engine/", "/edit/", "/pill/", "/Wsola.kt", "/MixRender.kt", "/MixPlan.kt", "/HeldTail.kt", "/Planner.kt")

    private fun analysis(label: String, path: String) = label == "fingerprint" || mixing.none { "/$path".contains(it) }

    @Test
    fun `the stamp is the one of this module's sources and the FFT it borrows`() {
        assertEquals(stampOf { _, _ -> true }, MixCode.STAMP)
    }

    /**
     * 8 Nov 2026, "WHY ISNT IT CACHED": every change to how a change is rendered made the editor
     * analyse both songs again and Auto pick again. The analysis is kept under the code it comes
     * from only, Auto's pick under that and Auto's own rules.
     */
    @Test
    fun `the analysis is kept through a change to mixing, Auto's pick through a change to rendering`() {
        assertEquals(stampOf(::analysis), MixCode.ANALYSIS)
        val auto = stampOf { label, path ->
            analysis(label, path) || (label == "mix" && ("/$path".contains("/auto/") || path.endsWith("engine/AutoSpec.kt") || path.endsWith("engine/AutoOnBands.kt")))
        }
        assertEquals(auto, MixCode.AUTO)
        // The analysis reads none of the code it is not kept under.
        val root = File("src/main/kotlin/com/dd3boh/outertune/mix")
        val analysed = root.listFiles { f -> f.isFile && analysis("mix", "kotlin/com/dd3boh/outertune/mix/${f.name}") }!!
        assertTrue(analysed.size >= 10)
        for (f in analysed) {
            val text = f.readText()
            for (other in listOf("mix.auto.", "mix.engine.", "mix.edit.", "mix.pill.", "Wsola", "MixRender", "MixPlan", "HeldTail", "Planner")) {
                assertTrue("${f.name} uses $other, which the analysis is not kept under", other !in text)
            }
        }
    }
}

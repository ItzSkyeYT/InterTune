/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every version the app is built as has its "New in" card (RELEASE.md, his word of 2 Nov 2026): 0.12.6
 * and 0.12.7 shipped without one, and nobody noticed until he did. A version that only fixes things
 * says so by being in [NOTHING_TO_SHOW]; everything else needs at least one entry in NEW_THINGS
 * for its own version, reachable in its own build.
 */
class WhatsNewForThisVersionTest {
    private val gradle = File("build.gradle.kts").readText()
    private val versionName = Regex("""versionName = "([^"]+)"""").find(gradle)!!.groupValues[1]
    private val versionCode = Regex("""versionCode = (\d+)""").find(gradle)!!.groupValues[1].toInt()

    /**
     * Versions that only fix things, named here on purpose. 0.12.9: radios in Indonesian, Finnish
     * and Danish (#83), a song YouTube no longer plays skipped instead of stopping the music (#82),
     * and the report's boxes kept until sent; nothing to show round.
     */
    private val NOTHING_TO_SHOW = setOf("0.12.9")

    @Test
    fun `the version this build is has its own New in entries`() {
        if (versionName in NOTHING_TO_SHOW) return
        val own = NEW_THINGS.filter { it.release == versionName }
        assertTrue(
            "No New in $versionName: add its entries to NEW_THINGS (Walkthrough.kt), or name it in NOTHING_TO_SHOW if it only fixes things",
            own.isNotEmpty(),
        )
    }

    @Test
    fun `its entries can be shown by this build`() {
        val own = NEW_THINGS.filter { it.release == versionName }
        for (thing in own) {
            assertTrue("${thing.id} counts from ${thing.sinceVersionCode}, after this build's $versionCode", thing.sinceVersionCode <= versionCode)
        }
    }

    @Test
    fun `every entry names a release people know`() {
        for (thing in NEW_THINGS) {
            assertTrue("${thing.id}: '${thing.release}'", Regex("""\d+\.\d+(\.\d+)?""").matches(thing.release))
        }
    }
}

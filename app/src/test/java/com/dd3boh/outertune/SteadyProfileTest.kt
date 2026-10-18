/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-Droid builds every release again and publishes it only if its APK matches ours byte for byte.
 * The baseline profile R8 rewrites came out different now and then from one commit (2 builds in 11
 * on 18 Oct 2026, a lost hot flag on a class merged from lambdas), and only R8's threads explain
 * that: the code it wrote was the same every time.
 */
class SteadyProfileTest {
    @Test
    fun `R8 runs on one thread, so its baseline profile is the same in every build`() {
        val properties = File("../gradle.properties").readLines().map { it.trim() }
        assertTrue(properties.contains("android.r8.threadPoolSize=1"))
        // And the profile stays in the APK: turning it off would be steady too, and slower.
        assertTrue(properties.none { it.startsWith("android.enableArtProfiles=false") })
    }
}

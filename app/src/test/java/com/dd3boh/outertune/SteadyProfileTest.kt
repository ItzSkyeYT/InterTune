/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune

import com.dd3boh.outertune.engine.EngineParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-Droid builds every release again and publishes it only if its APK matches ours byte for byte.
 * The baseline profile R8 rewrites came out different now and then from one commit (2 builds in 11
 * on 18 Oct 2026, a lost hot flag on a class merged from lambdas), and only R8's threads explain
 * that: the code it wrote was the same every time. On one thread R8 still found in one run that the
 * engine's row size is always 20, and wrote 20 where it is read, and in the next run did not, from the
 * same inputs (0.13's third release candidate, 28 Nov 2026): proguard-rules.pro keeps that field.
 */
class SteadyProfileTest {
    @Test
    fun `R8 runs on one thread, so its baseline profile is the same in every build`() {
        val properties = File("../gradle.properties").readLines().map { it.trim() }
        assertTrue(properties.contains("android.r8.threadPoolSize=1"))
        // And the profile stays in the APK: turning it off would be steady too, and slower.
        assertTrue(properties.none { it.startsWith("android.enableArtProfiles=false") })
    }

    @Test
    fun `the engine's row size is a field R8 reads in every build, never a 20 it found in one run`() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(
            Regex("""-keepclassmembers,allowshrinking,allowobfuscation class com\.dd3boh\.outertune\.engine\.EngineParams \{\s*int rowSize;\s*\}""")
                .containsMatchIn(rules)
        )
        // The rule names the field: renamed in EngineParams, it would keep nothing.
        assertEquals(Int::class.javaPrimitiveType, EngineParams::class.java.getDeclaredField("rowSize").type)
    }
}

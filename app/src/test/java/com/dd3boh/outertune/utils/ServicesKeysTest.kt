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
import java.util.Properties

/**
 * The build reads each service's address or key from services.properties by name, and a name it does
 * not find becomes an empty string without a word (build.gradle.kts, `service`). An empty counter
 * address turns the daily count and the polls off for everyone, the switches greyed out, and nothing
 * fails anywhere. So every name the build asks for must be in the file with a value. Written when
 * polls.umamiUrl became polls.countUrl (14 Nov 2026).
 */
class ServicesKeysTest {

    private val services = Properties().apply { File("../services.properties").reader().use { load(it) } }
    private val asked = Regex("""service\("([^"]+)"\)""").findAll(File("build.gradle.kts").readText())
        .map { it.groupValues[1] }.toSet()

    @Test
    fun `every name the build reads is in services properties with a value`() {
        assertTrue("the build reads no names: the pattern above no longer matches build.gradle.kts", asked.isNotEmpty())
        val missing = asked.filter { services.getProperty(it).isNullOrBlank() }
        assertTrue("missing or empty in services.properties: $missing", missing.isEmpty())
    }

    @Test
    fun `the count goes to InterTune's own counter`() {
        assertTrue("polls.countUrl" in asked)
        assertEquals("https://intertune-count.intertune.workers.dev", services.getProperty("polls.countUrl"))
    }
}

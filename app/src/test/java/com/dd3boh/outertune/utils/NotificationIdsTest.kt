/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every notification the app posts has an id of its own. Two that share one replace each other: until
 * 0.12.6, a song What's playing found took the place of an update waiting to be installed (both 4244).
 */
class NotificationIdsTest {

    @Test
    fun `no two notifications share an id`() {
        val declared = Regex("""val (\w*NOTIFICATION_ID)\s*=\s*(\d+)""")
        val ids = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            declared.findAll(file.readText()).map { Triple(it.groupValues[2].toInt(), it.groupValues[1], file.name) }
        }.toList()
        assertTrue("found only ${ids.size} notification ids", ids.size >= 6)
        val shared = ids.groupBy { it.first }.filterValues { it.size > 1 }
        assertTrue("shared notification ids: $shared", shared.isEmpty())
    }
}

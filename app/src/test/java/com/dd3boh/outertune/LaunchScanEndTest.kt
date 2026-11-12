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
 * The scan at launch said "Scanner failed: ..." and straight after it "Local media and song
 * downloads refresh complete!" (audit of 2 Nov 2026, 3.9): the end was announced whenever a scan
 * had been started, failed or not. Now a scan that failed says only that.
 */
class LaunchScanEndTest {
    private val source = File("src/main/java/com/dd3boh/outertune/MainActivityUtils.kt").readText()

    @Test
    fun `the end of the scan is announced only when it did not fail`() {
        val announced = Regex(
            "if \\(([^)]*)\\) \\{\\s*coroutineScope\\.launch \\{\\s*snackbarHostState\\.showSnackbar\\(\\s*message = context\\.getString\\(R\\.string\\.scanner_auto_end\\)"
        ).find(source)
        assertTrue("the completion snackbar is where it was", announced != null)
        assertTrue(announced!!.groupValues[1], announced.groupValues[1].contains("!scanFailed"))
    }

    @Test
    fun `and a failure of the scan is what sets it`() {
        val scan = source.substringAfter("// equivalent to (quick scan)").substringBefore("// post scan actions")
        val caught = scan.substringAfter("} catch (e: Exception) {").substringBefore("} finally {")
        assertTrue(caught, caught.contains("scanFailed = true"))
    }
}

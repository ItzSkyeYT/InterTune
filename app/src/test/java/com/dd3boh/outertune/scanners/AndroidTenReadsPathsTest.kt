/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.scanners

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Songs on the phone are read by their paths: the scanners open each file by path
 * (ParcelFileDescriptor.open), and the player plays it from its path. Android 10 runs an app that
 * targets it or later under scoped storage, where a path to a file another app wrote cannot be
 * opened, unless the app asks for the old way with requestLegacyExternalStorage. Without it the
 * local library found no song on Android 10 ("Some songs couldn't be read") and a song already in
 * it did not play. Upstream OuterTune fixed the same in 44ae23618, "app: Fix file access on Q".
 * Android 11 and later ignore the flag and read paths through their own storage again.
 */
class AndroidTenReadsPathsTest {

    private val application: String = File("src/main/AndroidManifest.xml").readText()
        .substringAfter("<application")
        .substringBefore(">")

    @Test
    fun `Android 10 lets the app read the phone's storage by path`() {
        assertTrue(
            "the app asks Android 10 for the files' paths (has: $application)",
            "android:requestLegacyExternalStorage=\"true\"" in application,
        )
    }

    @Test
    fun `the scanners still open the songs by their paths, which is why`() {
        // Should the scanners move to MediaStore or the picked folder's own uris, the flag can go.
        for (scanner in listOf("TagLibScanner", "FFmpegScanner")) {
            val code = File("src/main/java/com/dd3boh/outertune/utils/scanners/$scanner.kt").readText()
            assertTrue("$scanner opens its files another way now", "ParcelFileDescriptor.open(file," in code)
        }
    }
}

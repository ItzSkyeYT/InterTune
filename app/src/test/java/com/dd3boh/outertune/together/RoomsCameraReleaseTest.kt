/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Listen together's Scan button needs the camera. A release that holds rooms back must not declare
 * it, and one that has rooms (0.13 on, his word of 14 Nov 2026) must: the release manifest strips the
 * camera exactly while the flag is held back.
 */
class RoomsCameraReleaseTest {

    @Test
    fun `a release asks for the camera exactly when it has rooms`() {
        val flag = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        val held = Regex("""val LISTEN_TOGETHER = BuildConfig\.PREVIEW_FEATURES""").containsMatchIn(flag)
        val release = File("src/release/AndroidManifest.xml").readText()
        val stripped = Regex("""android\.permission\.CAMERA"\s+tools:node="remove"""").containsMatchIn(release)
        assertTrue("rooms held back but the release still asks for the camera", !held || stripped)
        assertTrue("rooms are in the release but its manifest takes the camera away", held || !stripped)
    }
}

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
 * Listen together's Scan button needs the camera; a release that holds rooms back must not declare
 * it. The release manifest strips it for as long as the flag stays held back.
 */
class RoomsCameraReleaseTest {

    @Test
    fun `a release with rooms held back declares no camera`() {
        val flag = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        val held = Regex("""val LISTEN_TOGETHER = BuildConfig\.PREVIEW_FEATURES""").containsMatchIn(flag)
        val release = File("src/release/AndroidManifest.xml").readText()
        val stripped = Regex("""android\.permission\.CAMERA"\s+tools:node="remove"""").containsMatchIn(release)
        assertTrue("rooms held back but the release still asks for the camera", !held || stripped)
    }
}

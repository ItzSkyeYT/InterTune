/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The sound bars are song recognition's, everywhere it appears (his word of 16 Oct 2026), so the
 * settings that are not recognition wear their own: Audio quality the HQ mark, Fix song volumes the
 * auto-fix wand (a speaker sat beside Audio normalization's), the scanner's sensitivity sliders.
 */
class RecognitionIconAloneTest {
    @Test
    fun `settings that are not recognition do not wear its sound bars`() {
        for (path in listOf(
            "src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/PlayerFrag.kt",
            "src/main/java/com/dd3boh/outertune/ui/screens/settings/fragments/LocalMediaSettingsFrag.kt",
            "src/main/java/com/dd3boh/outertune/ui/dialog/ImportM3uDialog.kt",
        )) assertFalse("$path wears the sound bars", "Icons.Rounded.GraphicEq" in File(path).readText())
    }
}

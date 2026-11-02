/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A constant rate is asked of the encoder only where one can hold it. On the Android 7 emulator
 * none can, Transformer was left with no encoder at all ("encoding format unsupported", 2 Nov 2026),
 * and no lyrics video could be made there.
 */
class LyricsVideoEncoderTest {
    @Test
    fun `a constant rate is asked for only where an encoder can hold it`() {
        val video = File("src/main/java/com/dd3boh/outertune/lyrics/LyricsVideo.kt").readText()
        assertTrue(video.contains(".apply { if (constantRate) setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }"))
        assertFalse("never asked for whatever the phone can do", video.contains("\n                            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)"))
        assertTrue(video.contains("isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)"))
    }
}

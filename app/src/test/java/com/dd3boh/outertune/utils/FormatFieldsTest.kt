/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatFieldsTest {
    private fun format(mimeType: String, contentLength: Long?) = PlayerResponse.StreamingData.Format(
        itag = 251,
        url = null,
        mimeType = mimeType,
        bitrate = 128000,
        width = null,
        height = null,
        contentLength = contentLength,
        quality = "medium",
        fps = null,
        qualityLabel = null,
        averageBitrate = null,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
        approxDurationMs = null,
        audioSampleRate = 48000,
        audioChannels = 2,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
    )

    @Test
    fun `codecs is read out of a normal mimeType`() {
        val f = format("audio/webm; codecs=\"opus\"", 123456L)
        assertEquals("opus", f.codecsOrEmpty())
        assertEquals(123456L, f.contentLengthOrZero())
    }

    @Test
    fun `a mimeType with no codecs parameter no longer crashes`() {
        // format.mimeType.split("codecs=")[1] used to throw IndexOutOfBoundsException here.
        val f = format("audio/webm", 123456L)
        assertEquals("", f.codecsOrEmpty())
    }

    @Test
    fun `a null contentLength no longer crashes`() {
        // format.contentLength!! used to throw NullPointerException here.
        val f = format("audio/webm; codecs=\"opus\"", null)
        assertEquals(0L, f.contentLengthOrZero())
    }

    @Test
    fun `a codecs value with a semicolon after it is still read correctly`() {
        val f = format("audio/mp4; codecs=\"mp4a.40.2\"; foo=bar", 1L)
        assertEquals("mp4a.40.2", f.codecsOrEmpty())
    }

    @Test
    fun `an unquoted codecs value is still read, not just the quoted form`() {
        // Unquoted values are read too, as the split this replaces read them.
        val f = format("audio/webm; codecs=opus", 1L)
        assertEquals("opus", f.codecsOrEmpty())
    }

    @Test
    fun `an unquoted codecs value followed by another parameter stops at the semicolon`() {
        val f = format("audio/webm; codecs=opus; foo=bar", 1L)
        assertEquals("opus", f.codecsOrEmpty())
    }
}

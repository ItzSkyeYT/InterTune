/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A download taken up partway at another quality than it began with (Auto: one stream on Wi-Fi, another on
 * mobile data; or Audio quality or Download quality changed meanwhile) joined the start of one stream and the
 * rest of another in one file: only the same itag with another length was caught, and by the song's format
 * row, which the player rewrites whenever it plays. The part is judged by the length its own cache recorded.
 */
class DownloadResumeStreamTest {

    @Test
    fun `a part is another stream's only when both lengths are known and differ`() {
        assertTrue(partIsAnotherStream(3_400_000L, 5_100_000L))
        assertFalse("the same stream goes on", partIsAnotherStream(3_400_000L, 3_400_000L))
        assertFalse("a part whose length was never learnt is not judged", partIsAnotherStream(-1L, 5_100_000L))
        assertFalse("a stream of unknown length is not judged", partIsAnotherStream(3_400_000L, 0L))
    }

    @Test
    fun `two copies are the same stream only when both recorded the same length`() {
        assertTrue(sameRecordedStream(3_400_000L, 3_400_000L))
        assertFalse(sameRecordedStream(3_400_000L, 5_100_000L))
        assertFalse("not known for one of them: not taken for the same", sameRecordedStream(-1L, 3_400_000L))
        assertFalse(sameRecordedStream(3_400_000L, -1L))
    }

    @Test
    fun `the resume is judged by the download cache's own length, and a later range is read from the player's copy only when it is the same stream`() {
        val util = File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText()
        val partway = util.substringAfter("if (dataSpec.position > 0L) {").substringBefore("throw IOException(")
        assertTrue("partIsAnotherStream(recordedLength(downloadCache, mediaId), format.contentLengthOrZero())" in partway)
        assertTrue("the part goes, so the next try starts at 0", "downloadCache.removeResource(mediaId)" in partway)
        assertTrue("a stand-in's upload is still caught", "anotherUpload(" in partway)
        assertTrue(
            "the player's copy is read through for a later range only when it is the same stream",
            "playerCache.isCached(mediaId, dataSpec.position, length) && sameRecordedStream(recordedLength(playerCache, mediaId), recordedLength(downloadCache, mediaId))" in util,
        )
        assertTrue("media3's own record of the length", "ContentMetadata.getContentLength(cache.getContentMetadata(mediaId))" in util)
    }
}

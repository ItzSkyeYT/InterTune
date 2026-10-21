/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import com.dd3boh.outertune.sabr.SabrFile
import com.metrolist.innertubex.sabr.SabrChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * A SABR stream stopped by a cancellation ends the read with a failure, never as the end of the
 * file. The stall watch's cut cancels the stream, and when the stream's own close won the race
 * with the reader's, the reader saw a clean end: media3 took the cut point for the song's length,
 * the song stopped there and the cache kept it that short.
 */
class SabrEarlyEndTest {

    @Test
    fun `a stream that stops on a cancellation is a failed read, not the end of the song`() {
        val file = SabrFile(
            stream = {
                flow {
                    emit(SabrChunk(ByteArray(100), 0, true, null, null, null))
                    throw CancellationException("stopped")
                }
            },
            contentLength = 10_000,
            durationMs = 60_000,
        )
        val reader = file.open(0)
        try {
            assertEquals(100, reader.read(ByteArray(1_000), 0, 1_000))
            val n = try {
                reader.read(ByteArray(1_000), 0, 1_000)
            } catch (e: IOException) {
                return
            }
            fail("the read ended cleanly ($n) at byte 100 of 10000")
        } finally {
            reader.close()
        }
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the remote player does with each report about the song it sent last. */
class SentSongTest {
    private val entry = RemoteEntry(key = 1L, mediaId = "vid1", durationMs = 200_000L, item = "vid1")

    private fun sent(
        ticket: Int = 3,
        linkLoad: Int = 7,
        mimeType: String = "audio/webm; codecs=\"opus\"",
        choice: FormatChoice = FormatChoice.AsIs,
        local: Boolean = false,
        retry: Boolean = false,
    ) = SentSong(RemoteCommand.Load(ticket, entry, 0L, true), linkLoad, mimeType, choice, local, retry)

    private fun report(state: ReceiverState, load: Int = 7, at: Long = 1_000L, volume: Float? = null) =
        ReceiverStatus(
            link = LinkState.CONNECTED,
            state = state,
            load = load,
            mediaId = "vid1",
            reportedAt = at,
            volume = volume,
            error = if (state == ReceiverState.FAILED) "TV stopped without playing it" else null,
        )

    @Test
    fun `a failure is retried as AAC once, however often the receiver repeats it`() {
        val song = sent()
        assertEquals(ReportAction.RETRY_AS_AAC, song.onReport(report(ReceiverState.FAILED), 3, FormatSupport.Unknown))
        // A DLNA renderer's next poll, and an FCast receiver's own volume change, while the AAC
        // copy is still being fetched.
        assertEquals(ReportAction.IGNORE, song.onReport(report(ReceiverState.FAILED, at = 6_000L), 3, FormatSupport.Unknown))
        assertEquals(ReportAction.IGNORE, song.onReport(report(ReceiverState.FAILED, at = 6_500L, volume = 0.3f), 3, FormatSupport.Unknown))
    }

    @Test
    fun `a failure with nothing to retry fails once`() {
        // The receiver listed our type and still failed it.
        val listed = sent()
        val webm = FormatSupport.Listed(setOf("audio/webm", "audio/mp4"))
        assertEquals(ReportAction.FAILED, listed.onReport(report(ReceiverState.FAILED), 3, webm))
        assertEquals(ReportAction.IGNORE, listed.onReport(report(ReceiverState.FAILED, at = 6_000L), 3, webm))

        // A song on the phone has no AAC copy, and AAC already sent is not sent again.
        assertEquals(ReportAction.FAILED, sent(local = true).onReport(report(ReceiverState.FAILED), 3, FormatSupport.Unknown))
        assertEquals(ReportAction.FAILED, sent(mimeType = "audio/mp4").onReport(report(ReceiverState.FAILED), 3, FormatSupport.Unknown))
    }

    @Test
    fun `the retry failing too is final`() {
        val retry = sent(linkLoad = 8, choice = FormatChoice.Aac, retry = true)
        assertEquals(ReportAction.FAILED, retry.onReport(report(ReceiverState.FAILED, load = 8), 3, FormatSupport.Unknown))
        assertEquals(ReportAction.IGNORE, retry.onReport(report(ReceiverState.FAILED, load = 8, at = 6_000L), 3, FormatSupport.Unknown))
    }

    @Test
    fun `a song already skipped past is not retried`() {
        assertEquals(ReportAction.FAILED, sent(ticket = 3).onReport(report(ReceiverState.FAILED), 4, FormatSupport.Unknown))
    }

    @Test
    fun `reports about another song are ignored, and everything else is followed every time`() {
        val song = sent()
        assertEquals(ReportAction.IGNORE, song.onReport(report(ReceiverState.FAILED, load = 6), 3, FormatSupport.Unknown))
        assertEquals(ReportAction.IGNORE, song.onReport(report(ReceiverState.PLAYING, load = 8), 3, FormatSupport.Unknown))
        for (state in listOf(ReceiverState.LOADING, ReceiverState.PLAYING, ReceiverState.PLAYING, ReceiverState.PAUSED, ReceiverState.ENDED)) {
            assertEquals(state.name, ReportAction.FOLLOW, song.onReport(report(state), 3, FormatSupport.Unknown))
        }
    }

    @Test
    fun `a retry that plays names the type the receiver refused`() {
        val retry = sent(linkLoad = 8, choice = FormatChoice.Aac, retry = true)
        assertEquals("audio/webm", retry.refusedType(report(ReceiverState.PLAYING, load = 8)))
        assertEquals("audio/webm", retry.refusedType(report(ReceiverState.PAUSED, load = 8)))
        assertNull(retry.refusedType(report(ReceiverState.LOADING, load = 8)))
        assertNull(retry.refusedType(report(ReceiverState.FAILED, load = 8)))
        assertNull(retry.refusedType(report(ReceiverState.PLAYING, load = 7)))
        // The song as it was, playing, shows nothing refused.
        assertNull(sent().refusedType(report(ReceiverState.PLAYING)))
    }
}

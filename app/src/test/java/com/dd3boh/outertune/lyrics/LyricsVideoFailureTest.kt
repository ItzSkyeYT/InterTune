/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.dd3boh.outertune.utils.ProblemLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "The share video lyrics is not working for any song" (#general, 10 Nov 2026): the person sent
 * the app's report twice, and it said "Nothing that looks like a fault", because the video wrote
 * why it failed as a warning under two tags the report did not read. What a failure says is kept
 * where a report finds it: at the error level, once, naming the step, Transformer's code and the
 * codec; and under tags the report reads.
 */
@UnstableApi
class LyricsVideoFailureTest {
    private val video = File("src/main/java/com/dd3boh/outertune/lyrics/LyricsVideo.kt").readText()
    private val card = File("src/main/java/com/dd3boh/outertune/ui/component/LyricsCard.kt").readText()

    @Test
    fun `a failure names the step, Transformer's code and the codec it blamed`() {
        val codec = ExportException.CodecInfo("video/avc, 720x1280", true, false, "c2.mtk.avc.encoder")
        val encoder = ExportException.createForCodec(IllegalStateException("configure"), ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, codec)
        assertEquals("ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, video encoder c2.mtk.avc.encoder", LyricsVideo.said(encoder))
        val decoder = ExportException.createForCodec(IllegalStateException("x"), ExportException.ERROR_CODE_DECODING_FAILED, ExportException.CodecInfo("audio/opus", false, true, "c2.android.opus.decoder"))
        assertEquals("ERROR_CODE_DECODING_FAILED, audio decoder c2.android.opus.decoder", LyricsVideo.said(decoder))
        // no codec named: the code alone; not Transformer's at all: the exception and its words
        assertEquals("ERROR_CODE_IO_UNSPECIFIED", LyricsVideo.said(ExportException.createForAssetLoader(java.io.IOException("x"), ExportException.ERROR_CODE_IO_UNSPECIFIED)))
        assertEquals("IllegalStateException: no video track", LyricsVideo.said(IllegalStateException("no video track")))
        assertEquals("IllegalStateException", LyricsVideo.said(IllegalStateException()))
    }

    @Test
    fun `each step of the video says which one it was`() {
        for (step in listOf("SOUND", "PICTURE", "JOIN")) assertTrue(step, video.contains("inStep(Step.$step)"))
        val failed = LyricsVideo.Failed(LyricsVideo.Step.PICTURE, "ERROR_CODE_ENCODING_FAILED", IllegalStateException())
        assertEquals("The picture step failed: ERROR_CODE_ENCODING_FAILED", failed.message)
    }

    @Test
    fun `the failure is written once at the error level, with its trace, and not as a warning the report skips`() {
        assertTrue(video.contains("Log.e(TAG, \"The lyrics video could not be made: "))
        assertTrue("the trace goes with it", video.contains("?: said(failure)}\", failure)"))
        // the export's own line has no trace, since make() writes it once
        assertTrue(video.contains("Log.w(TAG, \"Export to \${into.name} failed: \${said(exportException)}\")"))
        // the dialog writes only what the video did not
        assertTrue(card.contains("if (failure !is LyricsVideo.Failed) Log.e(\"LyricsCard\", \"The lyrics video could not be made\", failure)"))
    }

    @Test
    fun `a picture the encoder failed is tried once more in software, and a file that could not be read or written is not`() {
        val codec = ExportException.CodecInfo("video/avc, 720x1280", true, false, "c2.mtk.avc.encoder")
        for (code in listOf(
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        )) assertTrue(code.toString(), LyricsVideo.another(ExportException.createForCodec(IllegalStateException(), code, codec)))
        // what the encoder handed over, or nothing at all, can be a hardware encoder's doing too
        assertTrue(LyricsVideo.another(ExportException.createForUnexpected(IllegalStateException("x"))))
        // a full disk or a file that is gone fails the same in software; what is not Transformer's is not tried again
        assertFalse(LyricsVideo.another(ExportException.createForAssetLoader(java.io.IOException("x"), ExportException.ERROR_CODE_IO_FILE_NOT_FOUND)))
        assertFalse(LyricsVideo.another(ExportException.createForAssetLoader(java.io.IOException("x"), ExportException.ERROR_CODE_IO_UNSPECIFIED)))
        assertFalse(LyricsVideo.another(IllegalStateException("not Transformer's")))
    }

    @Test
    fun `the second try asks for Android's own encoders, and says both failures if it fails too`() {
        // the software encoders are Google's: c2.android and OMX.google, not the phone maker's c2.mtk, c2.qti, OMX.qcom
        for (name in listOf("c2.android.avc.encoder", "OMX.google.h264.encoder")) assertTrue(name, LyricsVideo.softwareCodec(name))
        for (name in listOf("c2.mtk.avc.encoder", "c2.qti.avc.encoder", "OMX.qcom.video.encoder.avc", "OMX.MTK.VIDEO.ENCODER.AVC")) assertFalse(name, LyricsVideo.softwareCodec(name))
        // the picture goes through pictureOf: as asked first, then with the selector of software encoders only
        assertTrue(video.contains("inStep(Step.PICTURE) { pictureOf("))
        assertTrue(video.contains("drawPicture(context, painter, clip, folder, into, Encoding.HARDWARE, progress)"))
        assertTrue(video.contains("drawPicture(context, painter, clip, folder, into, Encoding.SOFTWARE, progress)"))
        assertTrue(video.contains(".apply { if (encoding == Encoding.SOFTWARE) setVideoEncoderSelector(SOFTWARE_ENCODERS) }"))
        // both failures reach the log and the dialog: the first on its own line before the second try, then both in the one that is thrown
        assertTrue(video.contains("Log.w(TAG, \"The picture's first try failed ("))
        assertTrue(video.contains("\"${'$'}{said(first)}; second try, in software: ${'$'}{said(second)}\""))
        assertTrue(video.contains("second.addSuppressed(first)"))
        // a sound that cannot be decoded by the first decoder of the phone goes on to the next
        assertTrue(video.contains("DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build()"))
    }

    @Test
    fun `the report reads the tags of every lyrics share and save`() {
        for (tag in listOf("LyricsVideo", "LyricsCard", "LyricsSave")) assertTrue(tag, tag in ProblemLog.TELLING)
    }

    @Test
    fun `every way a lyrics share or save can fail is written at the error level`() {
        val save = File("src/main/java/com/dd3boh/outertune/lyrics/LyricsSave.kt").readText()
        // Written as warnings, none of these could begin the report's "last fault" (10 Nov 2026).
        for ((source, line) in listOf(
            video to "Log.e(TAG, \"Could not share the lyrics video\", it)",
            card to "Log.e(TAG, \"Could not share the lyrics card\", it)",
            save to "Log.e(TAG, \"No room for \$name in \${place(video)}\", it)",
            save to "Log.e(TAG, \"Could not save \$name\", failure)",
            save to "Log.e(TAG, \"Could not save into \$uri\", failure)",
        )) assertTrue(line, source.contains(line))
        for (source in listOf(video, card, save)) {
            assertTrue("no failure left as a warning", Regex("""Log\.w\(TAG, "(Could not|No room)""").findAll(source).none())
        }
    }

    @Test
    fun `the dialog says it could not be made, with Report a problem beside it, instead of a toast that went`() {
        assertTrue(card.contains("stringResource(R.string.lyrics_video_failed)"))
        assertTrue(card.contains("stringResource(R.string.help_bug_report_action)"))
        // under the words, which step stopped and what the codec said, so that a screenshot of the dialog names the cause
        assertTrue(card.contains("failed = (failure as? LyricsVideo.Failed)?.let { \"${'$'}{it.step.name.lowercase()}: ${'$'}{it.said}\" } ?: LyricsVideo.said(failure)"))
        assertTrue(card.contains("text = said,"))
        assertTrue(card.contains("ProblemReportDialog(onDismiss = { reporting = false })"))
    }
}

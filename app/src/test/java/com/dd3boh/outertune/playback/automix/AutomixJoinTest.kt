/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * AutoMix switched on in the middle of a song: the stage joins a stream only as the sink configures
 * it, so the change out of the song playing stayed plain until the next song (7 Nov 2026). The
 * sink in front of DefaultAudioSink configures the song playing again just before a flush the
 * renderer asked for, which DefaultAudioSink applies at that flush, with the stage in the chain.
 */
class AutomixJoinTest {

    private val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(48_000).build()

    /** A sink that only writes down what it was asked, in order. */
    private fun recording(calls: MutableList<String>): AudioSink =
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> calls += "configure ${(args[0] as Format).sampleRate}"
                "flush", "reset" -> calls += method.name
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Long.TYPE -> 0L
                java.lang.Integer.TYPE -> 0
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as AudioSink

    private fun stage(wanted: Boolean) = AutomixAudioProcessor(log = {}).also { it.wanted = wanted }

    @Test
    fun `switched on mid-song, the song playing is configured again just before the flush`() {
        val calls = mutableListOf<String>()
        val stage = stage(wanted = false)
        val sink = AutomixAudioSink(recording(calls), stage)
        sink.configure(format, 0, null)
        stage.wanted = true
        sink.flush()
        assertEquals(listOf("configure 48000", "configure 48000", "flush"), calls)
    }

    @Test
    fun `a stage already in the chain, or not wanted, is left as it is`() {
        val calls = mutableListOf<String>()
        val off = AutomixAudioSink(recording(calls), stage(wanted = false))
        off.configure(format, 0, null)
        off.flush()
        assertEquals(listOf("configure 48000", "flush"), calls)

        calls.clear()
        val inChain = stage(wanted = true)
        // In the chain as the pipeline has it: configured with AutoMix on, then flushed.
        inChain.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        inChain.flush()
        assertTrue(inChain.activeForStream)
        val on = AutomixAudioSink(recording(calls), inChain)
        on.configure(format, 0, null)
        on.flush()
        assertEquals(listOf("configure 48000", "flush"), calls)
    }

    @Test
    fun `nothing is configured again after a reset, before the renderer configures a stream`() {
        val calls = mutableListOf<String>()
        val stage = stage(wanted = true)
        val sink = AutomixAudioSink(recording(calls), stage)
        sink.configure(format, 0, null)
        sink.reset()
        sink.flush()
        assertEquals(listOf("configure 48000", "reset", "flush"), calls)
    }

    @Test
    fun `the controller seeks to where the music is as AutoMix comes on, and a late seek is no reason to stay plain`() {
        val dir = File("src/main/java/com/dd3boh/outertune/playback/automix")
        val controller = File(dir, "AutomixController.kt").readText()
        val setter = controller.substringAfter("var enabled = false").substringBefore("private var joining")
        assertTrue("AutoMix coming on joins the song playing", "joinNow()" in setter)
        val join = controller.substringAfter("private fun joinNow()").substringBefore("\n    }\n")
        assertTrue("it joins by a seek to where the music is", "seekInPlace()" in join)
        // A seek to the very millisecond the player is at is dropped by the player, and nothing joins.
        val inPlace = controller.substringAfter("private fun seekInPlace()").substringBefore("\n    }\n")
        assertTrue(inPlace, "player.seekTo(if (at > 0) at - 1 else 1)" in inPlace)
        assertTrue("player.seekTo(player.currentPosition)" !in controller)
        val guards = File(dir, "AutomixGuards.kt").readText()
        assertTrue("no refusal for a seek near the end", "last minute" !in guards && "seekedLate" !in guards)
    }
}

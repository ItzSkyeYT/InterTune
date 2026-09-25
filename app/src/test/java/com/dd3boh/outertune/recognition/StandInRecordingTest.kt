/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.ContextWrapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The recording a debug build listens to in place of the microphone, when there is one: see
 * [MicrophoneListener]. One second windows, since it plays at the pace of the clock.
 */
class StandInRecordingTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A listener whose files directory holds [samples] as room.pcm, 16 kHz mono s16le. */
    private fun listening(samples: ShortArray): MicrophoneListener {
        val dir = folder.newFolder()
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        File(dir, "room.pcm").writeBytes(bytes.array())
        return MicrophoneListener(object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        })
    }

    /** [count] samples all over the range, the negative ones included. */
    private fun samples(count: Int) = ShortArray(count) { (it * 997 % 65536 - 32768).toShort() }

    @Test
    fun `a recording shorter than a listen is heard as far as it goes`() = runBlocking {
        // A quarter of a second, against a one second listen. This used to come back empty.
        val room = samples(4_000)
        assertArrayEquals(room, listening(room).record(seconds = 1))
    }

    @Test
    fun `listening on ends by saying the recording has ended, after its last whole window`() = runBlocking {
        // A second and a twentieth: one whole window, and a part of one that is dropped, as the
        // microphone drops the part window it is in when stopped.
        val room = samples(16_800)
        val windows = mutableListOf<MicrophoneListener.Window>()
        val ended = runCatching { listening(room).stream(seconds = 1).collect { windows += it } }.exceptionOrNull()
        assertTrue("ended with $ended", ended is MicrophoneListener.RecordingEnded)
        assertEquals(1, windows.size)
        assertArrayEquals(room.copyOf(16_000), windows.single().samples)
    }

    @Test
    fun `stopping partway is not the recording ending`() = runBlocking {
        // A single listen stops at its answer, and that is a stop, not the end of the recording.
        val room = samples(40_000)
        assertArrayEquals(room.copyOf(16_000), listening(room).stream(seconds = 1).first().samples)
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * What happens to the windows the microphone records while the engine is still busy with an
 * earlier one: see [freshWindows]. The newest waits, the rest are dropped, and the recording never
 * waits for anybody.
 */
class FreshWindowsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `windows recorded while one is identified leave only the newest waiting`() = runBlocking {
        // Timed out rather than left to hang: a recording held up by the listener would block at
        // its second window, waiting for a listener that is waiting for it.
        withTimeout(10_000) {
            val busy = CompletableDeferred<Unit>()
            val answered = CompletableDeferred<Unit>()
            val identified = mutableListOf<Int>()
            flow {
                emit(0)
                busy.await()
                // Five windows while the first is still with Shazam.
                for (window in 1..5) emit(window)
                answered.complete(Unit)
            }.freshWindows().collect { window ->
                identified += window
                if (window == 0) {
                    busy.complete(Unit)
                    answered.await()
                }
            }
            // Not 0 to 5, one after another, minutes behind the room by the end.
            assertEquals(listOf(0, 5), identified)
        }
    }

    @Test
    fun `a listener that keeps up is given every window`() = runBlocking {
        withTimeout(10_000) {
            val taken = Array(5) { CompletableDeferred<Unit>() }
            val identified = mutableListOf<Int>()
            flow {
                for (window in 0 until 5) {
                    emit(window)
                    taken[window].await()
                }
            }.freshWindows().collect { window ->
                identified += window
                taken[window].complete(Unit)
            }
            assertEquals(listOf(0, 1, 2, 3, 4), identified)
        }
    }

    @Test
    fun `the stream drops what a slow listener missed`() = runBlocking {
        // Through the real stream, fed from the recording a debug build listens to in place of the
        // microphone, since that is the one way to run it here: three one second windows, each of
        // a different value, played at the pace of the clock.
        val dir = folder.newFolder()
        val samples = ShortArray(3 * 16_000) { ((it / 16_000 + 1) * 1000).toShort() }
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        File(dir, "room.pcm").writeBytes(bytes.array())
        val listener = MicrophoneListener(object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        })

        withTimeout(30_000) {
            // Thirty tenth-of-a-second chunks in all, the last read just before the last window.
            val chunks = AtomicInteger()
            val allRead = CompletableDeferred<Unit>()
            val identified = mutableListOf<Int>()
            listener.stream(seconds = 1) { if (chunks.incrementAndGet() == 30) allRead.complete(Unit) }
                .take(2)
                .collect { window ->
                    identified += window.samples.first() / 1000 - 1
                    // Busy with the first window until the whole recording has been heard, and a
                    // second more for its last window to be cut.
                    if (identified.size == 1) {
                        allRead.await()
                        delay(1_000)
                    }
                }
            assertEquals(listOf(0, 2), identified)
        }
    }
}

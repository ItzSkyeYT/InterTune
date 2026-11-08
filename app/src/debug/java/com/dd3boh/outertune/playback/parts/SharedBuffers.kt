/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.parts

import android.os.Build
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import com.dd3boh.outertune.parts.OrtSpecModel
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/**
 * The runtime's buffers in shared memory: native, mapped, freed when the session closes. Android
 * takes ByteBuffer.allocateDirect from the Java heap, where a session's 78 MB would count against
 * the app's limit (256 MB on the Pixel 5 and the S25 Ultra). For the parts bench and the editor's
 * own splitting.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
internal class SharedBuffers : OrtSpecModel.Buffers {
    private val held = IdentityHashMap<ByteBuffer, SharedMemory>()

    override fun allocate(bytes: Int): ByteBuffer {
        val memory = SharedMemory.create("intertune-parts", bytes)
        val buffer = memory.mapReadWrite()
        synchronized(held) { held[buffer] = memory }
        return buffer
    }

    override fun free(buffer: ByteBuffer) {
        val memory = synchronized(held) { held.remove(buffer) } ?: return
        SharedMemory.unmap(buffer)
        memory.close()
    }

    companion object {
        /** Shared memory where Android has it (8.1 and later), the Java heap before. */
        fun orHeap(): OrtSpecModel.Buffers =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) SharedBuffers() else OrtSpecModel.Buffers.Direct
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.parts

import android.content.Context
import android.os.Build
import android.util.Log
import com.dd3boh.outertune.parts.OrtSpecModel
import com.dd3boh.outertune.parts.WindowSplitter
import com.dd3boh.outertune.ui.screens.mix.PartsMaker
import java.io.File
import java.nio.ShortBuffer
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The Mix editor's parts made on this phone (Unreleased.AUTOMIX_PARTS, debug app only): SCNet
 * Standard from files/mix/models/scnet-int8.ort, put there by hand with run-as, through
 * WindowSplitter, the way the PC's split_windows.py makes them. Found by name from PartsMaker.open,
 * since only the debug build carries ONNX Runtime.
 *
 * The runtime's 78 MB of buffers sit in shared memory, off the Java heap; its own memory is native,
 * about a gigabyte while a window splits. So one model for the editor at a time: a maker holds it
 * from its first split until it is closed, and another waits, asking its cancel as it waits. A
 * playlist's batch (PartsBatchService) opens its own beside it, as many as its count of splits at
 * once, which leaves the memory for the editor's.
 */
object PartsOnPhone {
    private const val TAG = "PartsOnPhone"

    /** Where the model goes, in the app's files. */
    const val MODEL = "mix/models/scnet-int8.ort"

    /** The runtime's threads: on the PC they helped up to 4 (PARTS-NOW.md); the phones are not timed yet. */
    private val THREADS = minOf(4, Runtime.getRuntime().availableProcessors())

    private val one = Semaphore(1)

    /** A maker for the model on this phone; null without it, which is logged. */
    @JvmStatic
    fun open(context: Context): PartsMaker? {
        val model = File(context.filesDir, MODEL)
        if (!model.isFile) {
            Log.i(TAG, "No model at ${model.path}: windows are not split on this phone")
            return null
        }
        return Maker(model)
    }

    /**
     * A maker for one of a playlist's splits at once (PartsBatch): [threads] of the runtime, and none
     * of the editor's turn, which it leaves free. Null without the model, which is logged.
     */
    @JvmStatic
    fun openForBatch(context: Context, threads: Int): PartsMaker? {
        val model = File(context.filesDir, MODEL)
        if (!model.isFile) {
            Log.i(TAG, "No model at ${model.path}: the playlist's windows are not split on this phone")
            return null
        }
        return Maker(model, threads, takesTurn = false)
    }

    private class Maker(private val model: File, private val threads: Int = THREADS, private val takesTurn: Boolean = true) : PartsMaker {
        private var held = false
        private var session: OrtSpecModel? = null

        override fun split(window: ShortBuffer, rate: Int, channels: Int, targets: List<File>, progress: (Double) -> Unit, cancelled: () -> Boolean): Boolean {
            if (takesTurn && !held) {
                while (!one.tryAcquire(250, TimeUnit.MILLISECONDS)) if (cancelled()) return false
                held = true
            }
            // Made meanwhile by another opening of the editor.
            if (targets.all { it.isFile && it.length() == window.limit() * 2L }) return true
            val m = session ?: load().also { session = it }
            val splitter = WindowSplitter(m)
            val started = System.nanoTime()
            val done = splitter.split(window, rate, channels, targets, progress, cancelled)
            if (done) {
                Log.i(
                    TAG,
                    String.format(
                        Locale.ROOT, "%s: %.1f s at %d Hz split in %.1f s, %d segments, the model %.1f s of it",
                        targets.first().name.substringBefore('.'), window.limit().toDouble() / channels / rate, rate,
                        (System.nanoTime() - started) / 1e9, splitter.segments, splitter.modelNanos / 1e9,
                    ),
                )
            } else {
                Log.i(TAG, "${targets.first().name.substringBefore('.')}: the split was cancelled")
            }
            return done
        }

        private fun load(): OrtSpecModel {
            val started = System.nanoTime()
            val m = OrtSpecModel(
                OrtSpecModel.environment(), model.path,
                // No arena and no planned buffers: 1.24 GB at the peak instead of 2.2 GB on the S25 Ultra, and faster (19 Oct).
                OrtSpecModel.Options(threads = threads, lowMemory = true),
                // Shared memory where Android has it (8.1 and later), the Java heap before.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) SharedBuffers() else OrtSpecModel.Buffers.Direct,
            )
            Log.i(TAG, String.format(Locale.ROOT, "Model %s (%d bytes) loaded in %.1f s, %d threads", model.path, model.length(), (System.nanoTime() - started) / 1e9, threads))
            return m
        }

        override fun close() {
            val m = session
            session = null
            if (m != null) runCatching { m.close() }.onFailure { Log.w(TAG, "The model could not be let go", it) }
            if (held) {
                held = false
                one.release()
            }
        }
    }
}

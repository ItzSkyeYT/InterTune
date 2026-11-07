/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.parts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * SCNet through ONNX Runtime: one session, the input and the output held in native memory that
 * the runtime reads and writes in place, so a segment's 62 MB of output never lands on the Java
 * heap. Not thread-safe: one per thread, created on that thread (its pool of [Options.threads] is
 * made with it and takes its priority).
 *
 * The runtime's Java API is compiled against here and supplied by the build that uses it: the
 * debug app's onnxruntime-android, the desktop runtime in the tests.
 */
class OrtSpecModel(env: OrtEnvironment, modelPath: String, val options: Options = Options()) : SpecModel, AutoCloseable {

    /**
     * [threads] for the runtime's own pool inside one run; [lowMemory] turns off its arena and the
     * buffers it plans ahead (1.24 GB at the peak instead of 2.2 GB on the S25 Ultra on 19 Oct, and
     * faster there); [spinning] lets idle pool threads spin, which costs battery for little.
     */
    data class Options(val threads: Int = 1, val lowMemory: Boolean = false, val spinning: Boolean = false)

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(options.threads)
        setInterOpNumThreads(1)
        addConfigEntry("session.intra_op.allow_spinning", if (options.spinning) "1" else "0")
        addConfigEntry("session.inter_op.allow_spinning", "0")
        if (options.lowMemory) {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
    }

    val session: OrtSession = env.createSession(modelPath, sessionOptions)

    private val inputBuffer = direct(Scnet.SPEC_SIZE)
    private val outputBuffer = direct(Scnet.OUTPUT_SIZE)
    private val input = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 4, Scnet.BINS.toLong(), Scnet.FRAMES.toLong()))
    private val output = OnnxTensor.createTensor(env, outputBuffer, longArrayOf(1, 16, Scnet.BINS.toLong(), Scnet.FRAMES.toLong()))

    override fun run(spec: FloatArray) {
        inputBuffer.clear()
        inputBuffer.put(spec, 0, Scnet.SPEC_SIZE)
        // The output is pinned: the runtime writes into outputBuffer, and closing the result leaves it be.
        session.run(mapOf(Scnet.INPUT_NAME to input), mapOf(Scnet.OUTPUT_NAME to output)).close()
    }

    override fun readStem(stem: Int, into: FloatArray) {
        val view = outputBuffer.duplicate()
        view.position(stem * Scnet.SPEC_SIZE)
        view.get(into, 0, Scnet.SPEC_SIZE)
    }

    override fun close() {
        input.close()
        output.close()
        session.close()
        sessionOptions.close()
    }

    companion object {
        /**
         * The runtime's environment, with its telemetry off. The Android runtime from Maven would
         * otherwise send usage events to Microsoft (its TelemetryInitializer and 1DS client, taken
         * out of the debug app's manifest as well).
         */
        fun environment(): OrtEnvironment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }

        private fun direct(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}

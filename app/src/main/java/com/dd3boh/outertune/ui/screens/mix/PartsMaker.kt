/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.constants.Unreleased
import java.io.File
import java.nio.ShortBuffer

/**
 * Makes an editor window's parts on this phone, into the parts store's files. The debug app's
 * (PartsOnPhone, in its own source set beside ONNX Runtime, which only that build carries) splits
 * the window with SCNet as the PC's split_windows.py does. [PairLoader] opens one only where a
 * window has no parts, and closes it once the pair is loaded: the model holds about a gigabyte
 * while it is open.
 */
interface PartsMaker : AutoCloseable {
    /**
     * Splits [window] (16-bit, interleaved, [channels] at [rate] Hz, from 0 to its limit) into
     * [targets], vocals, drums, bass and melody as [PartsStore.NAMES] has them, each written under
     * a temporary name and put in place when whole. [progress] hears the fraction done; [cancelled]
     * is asked between segments. True once all four are in place, false when cancelled, with
     * nothing left behind. Failures are thrown.
     */
    fun split(window: ShortBuffer, rate: Int, channels: Int, targets: List<File>, progress: (Double) -> Unit, cancelled: () -> Boolean): Boolean

    /** Lets the model go. Never throws. */
    override fun close()

    companion object {
        private const val TAG = "MixEditor"

        /** The debug app's maker, found by name: only that build has it. */
        const val DEBUG_MAKER = "com.dd3boh.outertune.playback.parts.PartsOnPhone"

        /**
         * The debug app's maker, where the model is on this phone (Unreleased.AUTOMIX_PARTS); null
         * in every other build, and without the model, which the maker logs.
         */
        fun open(context: Context): PartsMaker? {
            if (!Unreleased.AUTOMIX_PARTS) return null
            return runCatching {
                Class.forName(DEBUG_MAKER).getMethod("open", Context::class.java).invoke(null, context) as PartsMaker?
            }.onFailure { Log.w(TAG, "Parts cannot be made on this phone in this build", it) }.getOrNull()
        }
    }
}

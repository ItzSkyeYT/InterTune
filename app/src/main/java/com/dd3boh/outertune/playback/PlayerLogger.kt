/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import com.dd3boh.outertune.utils.ErrorText
import androidx.media3.common.util.Log as Media3Log

/**
 * What Media3 writes to the log, without the network addresses.
 *
 * The player logs what goes wrong inside it under tags of its own, the whole trace with it:
 * "ExoPlayerImplInternal: Playback error" for every song that ends in one, and a stream that could
 * not be reached is the commonest of them. Those lines are written by Media3 and never pass
 * through the app, so nothing done to reportException reaches them. Media3 lets its logger be
 * replaced, and this one writes what its own does, line for line, through [ErrorText].
 */
object PlayerLogger : Media3Log.Logger {

    /** Once, when the app starts: a player made before this would log as Media3 does by itself. */
    fun install() = Media3Log.setLogger(this)

    override fun d(tag: String, message: String, throwable: Throwable?) {
        Log.d(tag, written(message, throwable))
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        Log.i(tag, written(message, throwable))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, written(message, throwable))
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, written(message, throwable))
    }

    /** Media3's own wording of a message and what was thrown, which is what its logger writes. */
    private fun written(message: String, throwable: Throwable?): String =
        ErrorText.withoutAddresses(Media3Log.appendThrowableString(message, throwable))
}

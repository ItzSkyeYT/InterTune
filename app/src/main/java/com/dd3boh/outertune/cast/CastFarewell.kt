/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * How a cast ends on a TV that has a screen for it: InterTune's own receiver says "Back on your
 * phone", with the last cover, for a moment, before the song and the player are stopped.
 */
object CastFarewell {
    /** How long the TV is left to say it. */
    const val SHOWN_MS = 3_000L

    private const val TAG = "CastFarewell"

    /**
     * Tells [link]'s receiver the cast is over and gives it [shownMs] to show that. The receiver's
     * song is held quiet meanwhile, so the phone, which already plays on, is not heard twice. True
     * when the receiver was told. A receiver with no screen of ours, or one that is gone, is not
     * waited for: this returns false at once, and the caller goes on to stop it as it always did.
     * Run off the main thread: it writes to the network and waits.
     */
    suspend fun to(link: ReceiverLink, shownMs: Long = SHOWN_MS): Boolean {
        val said = try {
            link.sayBye()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The goodbye to ${link.device.name} was not sent", e)
            false
        }
        if (!said) return false
        Log.i(TAG, "Told ${link.device.name} the cast is over; its screen has $shownMs ms before the song is stopped")
        try {
            link.pause()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "${link.device.name} was not held quiet for its goodbye: ${e.message}")
        }
        delay(shownMs)
        return true
    }
}

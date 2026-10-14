/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.datasource.HttpDataSource
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * When to throw out a song's cached copy because it cannot be what it says it is.
 *
 * A copy made of pieces that are not all the song's own, another upload's bytes or bytes no player
 * can read, was kept for good. The song then failed at the same place every time it was played:
 * the player retried that place every few seconds for as long as anyone waited, or it stopped
 * with a source error there (seen on the emulator, 13 Oct 2026, while chasing issue #20). Nothing
 * ever took the copy away, so playing the song again met it again.
 *
 * Now a copy goes when the song fails for a reason that is not the network, while the phone is
 * online and the copy is there: at once when the player gives up, and after [TRIES] failed loads
 * in a row while it is still retrying, which a retry then reads whole from YouTube. A copy that
 * was fine costs the song's data once more, which is the whole price of being wrong.
 *
 * When the player has given up, the song goes on from where it stopped instead of ending on an
 * error that will not come again: once, until a load of it goes through, so that a song whose
 * bytes fail from YouTube as well is not tried for ever.
 */
internal class BadCachedCopy(
    private val isCached: (String) -> Boolean,
    private val online: () -> Boolean,
    private val drop: (String) -> Unit,
) {
    private val failures = HashMap<String, Int>()

    /** The song [playerFailed] last sent on again, until a load of it goes through. */
    private var triedAgain: String? = null

    /** A load of [mediaId] failed, and the player may try it again. */
    fun loadFailed(mediaId: String?, error: Throwable) {
        if (mediaId == null || isNetwork(error) || !online()) return
        val count = (failures[mediaId] ?: 0) + 1
        failures[mediaId] = count
        if (count >= TRIES && isCached(mediaId)) {
            failures.remove(mediaId)
            drop(mediaId)
        }
    }

    /** A load of [mediaId] went through: what failed before was passing. */
    fun loadCompleted(mediaId: String?) {
        if (mediaId == null) return
        failures.remove(mediaId)
        if (mediaId == triedAgain) triedAgain = null
    }

    /**
     * The player gave up on [mediaId]. True when the song is to go on from where it stopped: its
     * copy was what failed and is gone, and it has not been sent on already since a load of it
     * last went through.
     */
    fun playerFailed(mediaId: String?, error: Throwable): Boolean {
        if (mediaId == null) return false
        failures.remove(mediaId)
        if (isNetwork(error) || !online() || !isCached(mediaId)) return false
        drop(mediaId)
        if (mediaId == triedAgain) return false
        triedAgain = mediaId
        return true
    }

    companion object {
        const val TRIES = 3

        /** Whether [error], or anything that caused it, is the network's, which no copy can mend. */
        fun isNetwork(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(10).any {
            it is HttpDataSource.HttpDataSourceException || it is UnknownHostException || it is ConnectException ||
                it is SocketException || it is SSLException || it is InterruptedIOException
        }
    }
}

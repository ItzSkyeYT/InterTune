/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.metrolist.innertubex.sabr.findSabrProtocolException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A failed read of a song over SABR, put the way the player's recovery reads a direct address's
 * (Recovery, BadCachedCopy, NetworkRetryPolicy), so that SABR needs no rules of its own there. No
 * Android in here, so it is tested (RecoveryTest).
 *
 * - YouTube refused the start: InnerTubeX's attestation required, a fresh player answer asked
 *   for, an error part, a protocol fault, any other status of its server. HTTP 403, once the
 *   start is forgotten: the route changes at the first failure, and a stopped song gets its quiet
 *   retry. As a bad status with no status in it, it was a plain retry of the same refused start,
 *   the route changed only at the second, and a stop was for good.
 * - A start no longer known, run out or forgotten: HTTP 410, as an address that has run out.
 * - SABR's own server failing after the library's own tries (408, 425, 429, 5xx): that status,
 *   and the start stays.
 * - No network, a timeout: as media3's HTTP source puts OkHttp's own.
 * - A gap, a short stream, any other failed read: a failed read, which media3 opens again at the
 *   same byte, and the start stays.
 * - An interruption, a seek or a stop: as it is.
 */
@OptIn(UnstableApi::class)
object SabrFailures {
    /** A start the address no longer stands for. */
    fun startGone(spec: DataSpec): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(410, "SABR start no longer known", null, emptyMap(), spec, ByteArray(0))

    /**
     * What the player is told of [e], from a read of [type] of [spec]. [forget] is called for a
     * refusal: the start is no good, and the next open walks the chain again.
     */
    fun asPlayerSees(e: IOException, spec: DataSpec, type: Int, forget: () -> Unit): IOException {
        // A timeout is an InterruptedIOException too, and the network's, not an interruption.
        if (e is InterruptedIOException && e !is SocketTimeoutException) return e
        e.findSabrProtocolException()?.let { refusal ->
            val status = refusal.httpStatusCode
            if (status != null && isTheServers(status)) return refused(status, refusal.kind.name, e, spec)
            forget()
            return refused(if (status == 410) 410 else 403, refusal.kind.name, e, spec)
        }
        if (generateSequence<Throwable>(e) { it.cause }.take(8).any { it.isNetwork() }) {
            return HttpDataSource.HttpDataSourceException.createForIOException(e, spec, type)
        }
        return HttpDataSource.HttpDataSourceException(e, spec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, type)
    }

    /** Statuses of SABR's own server that say nothing against the start. */
    private fun isTheServers(status: Int) = status == 408 || status == 425 || status == 429 || status in 500..599

    private fun refused(status: Int, kind: String, cause: IOException, spec: DataSpec) =
        HttpDataSource.InvalidResponseCodeException(status, "SABR $kind", cause, emptyMap(), spec, ByteArray(0))

    private fun Throwable.isNetwork(): Boolean =
        this is UnknownHostException || this is ConnectException || this is NoRouteToHostException ||
            this is SocketTimeoutException || this is SocketException || this is SSLException ||
            javaClass.simpleName == "HttpRequestTimeoutException"
}

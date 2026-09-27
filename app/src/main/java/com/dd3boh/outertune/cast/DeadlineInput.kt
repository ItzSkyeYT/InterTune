/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A socket's input with one deadline for everything read from it, in [System.nanoTime] time.
 *
 * A read timeout alone is per read, so a peer sending a byte every few seconds could hold a server
 * worker, or a request to a renderer, for hours; this shortens the timeout of each read to what is
 * left of the deadline. [firstByteBy] is an earlier deadline for the first byte alone, so a peer
 * that connects and says nothing is let go well before one still sending its request.
 */
internal class DeadlineInput(
    private val socket: Socket,
    private val deadline: Long,
    private val firstByteBy: Long = deadline,
) : InputStream() {
    private val input = socket.getInputStream()
    private var heardFrom = false

    private fun arm() {
        val until = if (heardFrom) deadline else minOf(firstByteBy, deadline)
        val leftMs = (until - System.nanoTime()) / 1_000_000L
        if (leftMs <= 0) throw SocketTimeoutException(if (heardFrom) "Past the deadline" else "Nothing was sent")
        socket.soTimeout = leftMs.toInt()
    }

    override fun read(): Int {
        arm()
        return input.read().also { if (it >= 0) heardFrom = true }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        arm()
        return input.read(buffer, offset, length).also { if (it > 0) heardFrom = true }
    }
}

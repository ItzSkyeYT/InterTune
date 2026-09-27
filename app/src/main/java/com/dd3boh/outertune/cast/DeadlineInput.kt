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
 * left of the deadline.
 */
internal class DeadlineInput(private val socket: Socket, private val deadline: Long) : InputStream() {
    private val input = socket.getInputStream()

    private fun arm() {
        val leftMs = (deadline - System.nanoTime()) / 1_000_000L
        if (leftMs <= 0) throw SocketTimeoutException("Past the deadline")
        socket.soTimeout = leftMs.toInt()
    }

    override fun read(): Int {
        arm()
        return input.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        arm()
        return input.read(buffer, offset, length)
    }
}

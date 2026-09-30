/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** A receiver broke the Cast v2 framing: a length out of bounds, a message cut short, or bytes that are no CastMessage. */
class CastChannelException(message: String) : IOException(message)

/**
 * Cast v2 on the wire, as Chromecasts and Google TV devices speak it over TLS: the CastMessage
 * protobuf, encoded and decoded here by hand, so that seven fields cost no protobuf library and
 * nothing is added to the build.
 *
 * Every message is a 4-byte big-endian length, then the CastMessage, whose fields are:
 *
 *     1 protocol_version  varint  0 (CASTV2_1_0)
 *     2 source_id         string
 *     3 destination_id    string
 *     4 namespace         string
 *     5 payload_type      varint  0 STRING, 1 BINARY
 *     6 payload_utf8      string  JSON, for everything a sender needs
 *     7 payload_binary    bytes   only for device authentication, which this sender does not do
 *
 * Newer receivers have fields past these (for a message sent in parts, among others), and a
 * protobuf reader skips a field it does not know by its wire type, so this does too. A message
 * without one of the strings reads as having it empty, and is then simply about nothing this app
 * listens to: dropping the connection over it would cost the cast for no gain.
 */
object CastChannel {
    /**
     * The largest message sent or read. Chrome's own Cast channel refuses anything longer than
     * 64 KB, so no receiver sends more; a length past it means the stream is out of step.
     */
    const val MAX_MESSAGE = 64 * 1024

    const val PAYLOAD_STRING = 0
    const val PAYLOAD_BINARY = 1

    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LENGTH_DELIMITED = 2
    private const val FIXED32 = 5

    class Message(
        val sourceId: String,
        val destinationId: String,
        val namespace: String,
        val payloadUtf8: String? = null,
        val payloadBinary: ByteArray? = null,
        val payloadType: Int = if (payloadUtf8 == null && payloadBinary != null) PAYLOAD_BINARY else PAYLOAD_STRING,
        val protocolVersion: Int = 0,
    ) {
        override fun toString() = "Message($sourceId -> $destinationId, $namespace, ${payloadUtf8 ?: payloadBinary?.let { "${it.size} bytes" }})"
    }

    /** [message] as the protobuf bytes of a CastMessage, without the length in front. */
    fun encode(message: Message): ByteArray {
        val out = ByteArrayOutputStream()
        // Fields 1 and 5 are required in the schema, so they are written even at their default.
        out.tag(1, VARINT)
        out.varint(message.protocolVersion.toLong())
        out.text(2, message.sourceId)
        out.text(3, message.destinationId)
        out.text(4, message.namespace)
        out.tag(5, VARINT)
        out.varint(message.payloadType.toLong())
        message.payloadUtf8?.let { out.text(6, it) }
        message.payloadBinary?.let { out.bytes(7, it) }
        return out.toByteArray()
    }

    /** [message] as it goes on the socket: its length, then its bytes. */
    fun frame(message: Message): ByteArray {
        val body = encode(message)
        require(body.size <= MAX_MESSAGE) { "A Cast message of ${body.size} bytes is over the limit" }
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size ushr 24).toByte()
        frame[1] = (body.size ushr 16).toByte()
        frame[2] = (body.size ushr 8).toByte()
        frame[3] = body.size.toByte()
        body.copyInto(frame, 4)
        return frame
    }

    /** The CastMessage in [bytes]. Throws [CastChannelException] on bytes that are not one. */
    fun decode(bytes: ByteArray): Message {
        val reader = Reader(bytes)
        var version = 0
        var source = ""
        var destination = ""
        var namespace = ""
        var payloadType = PAYLOAD_STRING
        var utf8: String? = null
        var binary: ByteArray? = null
        while (reader.hasMore()) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            if (field <= 0) throw CastChannelException("A field numbered $field")
            when (wireType) {
                VARINT -> {
                    val value = reader.varint()
                    when (field) {
                        1 -> version = value.toInt()
                        5 -> payloadType = value.toInt()
                    }
                }
                LENGTH_DELIMITED -> {
                    val value = reader.bytes()
                    when (field) {
                        2 -> source = value.toString(Charsets.UTF_8)
                        3 -> destination = value.toString(Charsets.UTF_8)
                        4 -> namespace = value.toString(Charsets.UTF_8)
                        6 -> utf8 = value.toString(Charsets.UTF_8)
                        7 -> binary = value
                    }
                }
                FIXED64 -> reader.skip(8)
                FIXED32 -> reader.skip(4)
                // Groups (3 and 4) went out of protobuf before Cast existed, and 6 and 7 are not wire types.
                else -> throw CastChannelException("Wire type $wireType in field $field")
            }
        }
        return Message(source, destination, namespace, utf8, binary, payloadType, version)
    }

    /**
     * Reads the next message, blocking, or null when the receiver closed the connection between two
     * messages. A connection closed inside one, or a length past [MAX_MESSAGE], throws.
     */
    fun read(input: InputStream): Message? {
        val header = ByteArray(4)
        val first = input.read(header, 0, 4)
        if (first < 0) return null
        readFully(input, header, first, 4 - first)
        val length = ((header[0].toLong() and 0xFF) shl 24) or
            ((header[1].toLong() and 0xFF) shl 16) or
            ((header[2].toLong() and 0xFF) shl 8) or
            (header[3].toLong() and 0xFF)
        if (length > MAX_MESSAGE) throw CastChannelException("A message of $length bytes")
        val body = ByteArray(length.toInt())
        readFully(input, body, 0, body.size)
        return decode(body)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val count = input.read(buffer, offset + done, length - done)
            if (count < 0) throw EOFException("The receiver closed inside a message")
            done += count
        }
    }

    private fun ByteArrayOutputStream.tag(field: Int, wireType: Int) = varint((field shl 3 or wireType).toLong())

    private fun ByteArrayOutputStream.varint(value: Long) {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            write(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        write(rest.toInt())
    }

    private fun ByteArrayOutputStream.text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    private fun ByteArrayOutputStream.bytes(field: Int, value: ByteArray) {
        tag(field, LENGTH_DELIMITED)
        varint(value.size.toLong())
        write(value)
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        fun hasMore() = position < bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= bytes.size) throw CastChannelException("A number cut short")
                val byte = bytes[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw CastChannelException("A number over ten bytes")
            }
        }

        fun bytes(): ByteArray {
            val length = varint()
            if (length < 0 || length > bytes.size - position) throw CastChannelException("A field of $length bytes past the end")
            return bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
        }

        fun skip(count: Int) {
            if (count > bytes.size - position) throw CastChannelException("A field cut short")
            position += count
        }
    }
}

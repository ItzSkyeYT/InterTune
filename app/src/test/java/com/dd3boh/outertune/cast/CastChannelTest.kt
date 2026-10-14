/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

/**
 * The CastMessage codec against bytes worked out by hand from the protobuf encoding rules, and
 * checked with an encoder written separately in Python: tag bytes (field number times 8, plus the
 * wire type), varints, and lengths in front of strings.
 */
class CastChannelTest {
    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val connection = "urn:x-cast:com.google.cast.tp.connection"

    /**
     * CONNECT from sender-0 to receiver-0: 08 00 is field 1 (varint) = 0; 12 08 is field 2
     * (string) of 8 bytes; 1a 0a field 3 of 10; 22 28 field 4 of 40; 28 00 field 5 = 0 (STRING);
     * 32 12 field 6 of 18.
     */
    private val connect = "0800" +
        "1208" + "73656e6465722d30" +
        "1a0a" + "72656365697665722d30" +
        "2228" + "75726e3a782d636173743a636f6d2e676f6f676c652e636173742e74702e636f6e6e656374696f6e" +
        "2800" +
        "3212" + "7b2274797065223a22434f4e4e454354227d"

    private val connectMessage = CastChannel.Message("sender-0", "receiver-0", connection, """{"type":"CONNECT"}""")

    @Test
    fun `a CONNECT encodes to the bytes protobuf gives`() {
        assertEquals(connect, CastChannel.encode(connectMessage).hex())
    }

    @Test
    fun `a frame is the length, big-endian, then the message`() {
        assertEquals("00000058$connect", CastChannel.frame(connectMessage).hex())
    }

    @Test
    fun `those bytes decode back to the message`() {
        val message = CastChannel.decode(bytes(connect))
        assertEquals("sender-0", message.sourceId)
        assertEquals("receiver-0", message.destinationId)
        assertEquals(connection, message.namespace)
        assertEquals(CastChannel.PAYLOAD_STRING, message.payloadType)
        assertEquals("""{"type":"CONNECT"}""", message.payloadUtf8)
        assertNull(message.payloadBinary)
        assertEquals(0, message.protocolVersion)
    }

    @Test
    fun `a payload of more than 127 bytes has a length of two bytes`() {
        val payload = """{"type":"LOAD","x":"${"a".repeat(200)}"}"""
        assertEquals(222, payload.length)
        val encoded = CastChannel.encode(CastChannel.Message("client-1", "web-5", "urn:x-cast:com.google.cast.media", payload)).hex()
        // 222 is 0xde as a varint's first seven bits with the high bit set, then 0x01.
        assertEquals(
            "08001208636c69656e742d311a057765622d35" +
                "2220" + "75726e3a782d636173743a636f6d2e676f6f676c652e636173742e6d65646961" +
                "2800" + "32de01" + payload.toByteArray().hex(),
            encoded,
        )
        assertEquals(payload, CastChannel.decode(bytes(encoded)).payloadUtf8)
    }

    @Test
    fun `fields it does not know are skipped by their wire type, wherever they are`() {
        val withUnknown = bytes(
            "0800" +
                "4001" + // field 8, varint (a newer receiver's "continued")
                "1208" + "73656e6465722d30" +
                "489601" + // field 9, varint 150
                "51" + "0102030405060708" + // field 10, 64-bit
                "1a0a" + "72656365697665722d30" +
                "5d" + "01020304" + // field 11, 32-bit
                "2228" + "75726e3a782d636173743a636f6d2e676f6f676c652e636173742e74702e636f6e6e656374696f6e" +
                "7a03" + "616263" + // field 15, bytes
                "2800" +
                "8001" + "05" + // field 16, whose tag takes two bytes
                "3212" + "7b2274797065223a22434f4e4e454354227d",
        )
        val message = CastChannel.decode(withUnknown)
        assertEquals("sender-0", message.sourceId)
        assertEquals("receiver-0", message.destinationId)
        assertEquals(connection, message.namespace)
        assertEquals("""{"type":"CONNECT"}""", message.payloadUtf8)
    }

    @Test
    fun `a binary payload is kept as bytes, and its type read`() {
        val message = CastChannel.Message("sender-0", "receiver-0", "urn:x-cast:com.google.cast.tp.deviceauth", payloadBinary = byteArrayOf(1, 2, 3))
        assertEquals(CastChannel.PAYLOAD_BINARY, message.payloadType)
        val encoded = CastChannel.encode(message)
        assertEquals("28013a03010203", encoded.hex().takeLast(14))
        val decoded = CastChannel.decode(encoded)
        assertEquals(CastChannel.PAYLOAD_BINARY, decoded.payloadType)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.payloadBinary)
        assertNull(decoded.payloadUtf8)
    }

    @Test
    fun `a message missing its strings reads them as empty`() {
        val message = CastChannel.decode(bytes("08002800"))
        assertEquals("", message.sourceId)
        assertEquals("", message.namespace)
        assertNull(message.payloadUtf8)
    }

    @Test
    fun `messages are read one after another, and a clean end is null`() {
        val second = CastChannel.Message("receiver-0", "sender-0", "urn:x-cast:com.google.cast.tp.heartbeat", """{"type":"PONG"}""")
        val input = ByteArrayInputStream(CastChannel.frame(connectMessage) + CastChannel.frame(second))
        assertEquals("""{"type":"CONNECT"}""", CastChannel.read(input)!!.payloadUtf8)
        assertEquals("urn:x-cast:com.google.cast.tp.heartbeat", CastChannel.read(input)!!.namespace)
        assertNull(CastChannel.read(input))
    }

    @Test
    fun `a length past the limit is refused before anything is read into memory`() {
        expectFailure<CastChannelException> { CastChannel.read(ByteArrayInputStream(bytes("00010001"))) }
        expectFailure<CastChannelException> { CastChannel.read(ByteArrayInputStream(bytes("ffffffff"))) }
    }

    @Test
    fun `a message cut short throws`() {
        expectFailure<EOFException> { CastChannel.read(ByteArrayInputStream(bytes("0000000a0800"))) }
        expectFailure<EOFException> { CastChannel.read(ByteArrayInputStream(bytes("0000"))) }
    }

    @Test
    fun `bytes that are no CastMessage throw`() {
        // A group, which protobuf dropped long ago.
        expectFailure<CastChannelException> { CastChannel.decode(bytes("0b")) }
        // A string longer than what is left.
        expectFailure<CastChannelException> { CastChannel.decode(bytes("12056162")) }
        // A varint that never ends.
        expectFailure<CastChannelException> { CastChannel.decode(bytes("08ffffffffffffffffffff01")) }
        expectFailure<CastChannelException> { CastChannel.decode(bytes("08ff")) }
        // Field 0 does not exist.
        expectFailure<CastChannelException> { CastChannel.decode(bytes("0000")) }
    }

    @Test
    fun `a message over the limit is not sent`() {
        val huge = CastChannel.Message("a", "b", "c", "x".repeat(CastChannel.MAX_MESSAGE))
        expectFailure<IllegalArgumentException> { CastChannel.frame(huge) }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}

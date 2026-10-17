/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The listening rooms' core: BIP-340 signatures, the room code and its seal, Nostr events, and following the host. */
class TogetherCoreTest {

    @Test
    fun `BIP-340's first test vector, byte for byte`() {
        val secret = "0000000000000000000000000000000000000000000000000000000000000003".hexToBytes()
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", Secp256k1.publicKey(secret).toHex())
        val signature = Secp256k1.sign(secret, ByteArray(32), aux = ByteArray(32))
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215" +
                "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            signature.toHex(),
        )
        assertTrue(Secp256k1.verify(Secp256k1.publicKey(secret), ByteArray(32), signature))
    }

    @Test
    fun `the generator's own key, and signatures that do not belong are refused`() {
        val one = ByteArray(32).also { it[31] = 1 }
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", Secp256k1.publicKey(one).toHex())
        val secret = Secp256k1.newSecretKey()
        val message = Secp256k1.sha256("a song".toByteArray())
        val signature = Secp256k1.sign(secret, message)
        assertTrue(Secp256k1.verify(Secp256k1.publicKey(secret), message, signature))
        assertFalse(Secp256k1.verify(Secp256k1.publicKey(secret), Secp256k1.sha256("another".toByteArray()), signature))
        val bent = signature.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertFalse(Secp256k1.verify(Secp256k1.publicKey(secret), message, bent))
    }

    @Test
    fun `two phones reach the same shared secret`() {
        val a = Secp256k1.newSecretKey()
        val b = Secp256k1.newSecretKey()
        assertEquals(
            Secp256k1.sharedX(a, Secp256k1.publicKey(b))!!.toHex(),
            Secp256k1.sharedX(b, Secp256k1.publicKey(a))!!.toHex(),
        )
    }

    @Test
    fun `room codes read however they are typed, and only the room opens its seal`() {
        val code = RoomCode.generate()
        assertEquals(12, code.raw.length)
        assertEquals(code, RoomCode.parse(code.pretty.lowercase().replace("-", " ")))
        assertEquals(code, RoomCode.parse(code.appLink))
        assertNull(RoomCode.parse("K7QX-M2PA-9DT0"))       // a 0 is never in a code
        assertNull(RoomCode.parse("short"))
        assertEquals(32, code.tag.length)
        val sealed = code.seal("""{"t":"here"}""")
        assertEquals("""{"t":"here"}""", code.open(sealed))
        assertNull(RoomCode.generate().open(sealed))
        assertNotEquals(code.seal("same"), code.seal("same"))  // a fresh nonce every time
    }

    @Test
    fun `Nostr events hash and sign as NIP-01 says, quotes and newlines included`() {
        assertEquals(
            """[0,"ab",1700000000,21430,[["t","x\"y"]],"line\nnext\\"]""",
            NostrEvent.serialize("ab", 1_700_000_000, 21430, listOf(listOf("t", "x\"y")), "line\nnext\\"),
        )
        val secret = Secp256k1.newSecretKey()
        val event = NostrEvent.signed(secret, TogetherMessage.KIND, listOf(listOf("t", "tag")), "sealed")
        assertTrue(event.valid())
        assertFalse(event.copy(content = "changed").valid())
        assertEquals(event, NostrEvent.parse(event.toJson()))
    }

    @Test
    fun `messages go and come back whole`() {
        val state = TogetherMessage.State("dQw4w9WgXcQ", "A song", "Someone", 61_250, true, 1.0, 1_700_000_000_123)
        assertEquals(state, TogetherMessage.decode(TogetherMessage.encode(state)))
        val pong = TogetherMessage.Pong(10, 20, "ab")
        assertEquals(pong, TogetherMessage.decode(TogetherMessage.encode(pong)))
        assertNull(TogetherMessage.decode("""{"t":"what"}"""))
    }

    @Test
    fun `the fastest pong sets the clock gap`() {
        val gap = ClockGap()
        gap.heard(askedAtMs = 1_000, hostMs = 6_100, nowMs = 1_200)      // 200 ms round trip, host 5 s ahead
        assertEquals(5_000L, gap.offsetMs)
        gap.heard(askedAtMs = 2_000, hostMs = 7_400, nowMs = 2_600)      // slower: ignored
        assertEquals(5_000L, gap.offsetMs)
        gap.heard(askedAtMs = 3_000, hostMs = 8_020, nowMs = 3_040)      // faster: taken
        assertEquals(5_000L, gap.offsetMs)
        assertEquals(40L, gap.bestRoundTripMs)
        assertEquals(15_000L, gap.hostNow(10_000))
    }

    @Test
    fun `a guest nudges small gaps, seeks big ones, and leaves a tiny one alone`() {
        val state = TogetherMessage.State("id", "", "", positionMs = 60_000, playing = true, speed = 1.0, atMs = 100_000)
        assertEquals(62_000L, Follow.target(state, hostNowMs = 102_000))
        assertEquals(60_000L, Follow.target(state.copy(playing = false), hostNowMs = 102_000))
        assertTrue(Follow.decide(62_000, 61_990, 1.0) is Follow.Steady)
        val behind = Follow.decide(62_000, 61_940, 1.0) as Follow.Nudge
        assertEquals(1.025, behind.speed, 0.0001)                      // 60 ms behind: half the full nudge
        val ahead = Follow.decide(62_000, 62_300, 1.0) as Follow.Nudge
        assertEquals(0.95, ahead.speed, 0.0001)                         // 300 ms ahead: the full nudge down
        assertEquals(Follow.Seek(62_000), Follow.decide(62_000, 60_000, 1.0))
    }
}

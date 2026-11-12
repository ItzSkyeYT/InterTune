/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Schnorr signatures on secp256k1 as BIP-340 defines them: what a Nostr relay checks on every event
 * it is sent (NIP-01). Written from the BIP's own description, with BigInteger and Jacobian
 * coordinates; a signature takes one multiplication of the curve's generator, a few milliseconds.
 * Verification is here for the tests and for anyone who wants it: relays verify, and a room's
 * messages are already sealed with its key (RoomCode).
 *
 * Not constant time. Keys are made fresh for each room session and never leave the phone, which is
 * the point at which that matters little.
 */
object Secp256k1 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val random = SecureRandom()

    /** A point in Jacobian coordinates; [z] zero is the point at infinity. */
    private class Jacobian(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val infinite: Boolean get() = z.signum() == 0
    }

    private class Affine(val x: BigInteger, val y: BigInteger)

    private val INFINITY = Jacobian(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G = Jacobian(GX, GY, BigInteger.ONE)

    private fun double(p: Jacobian): Jacobian {
        if (p.infinite || p.y.signum() == 0) return INFINITY
        val y2 = p.y.multiply(p.y).mod(P)
        val s = BigInteger.valueOf(4).multiply(p.x).multiply(y2).mod(P)
        val m = BigInteger.valueOf(3).multiply(p.x).multiply(p.x).mod(P)
        val x3 = m.multiply(m).subtract(s.shiftLeft(1)).mod(P)
        val y3 = m.multiply(s.subtract(x3)).subtract(BigInteger.valueOf(8).multiply(y2).multiply(y2)).mod(P)
        val z3 = p.y.multiply(p.z).shiftLeft(1).mod(P)
        return Jacobian(x3, y3, z3)
    }

    private fun add(a: Jacobian, b: Jacobian): Jacobian {
        if (a.infinite) return b
        if (b.infinite) return a
        val z1z1 = a.z.multiply(a.z).mod(P)
        val z2z2 = b.z.multiply(b.z).mod(P)
        val u1 = a.x.multiply(z2z2).mod(P)
        val u2 = b.x.multiply(z1z1).mod(P)
        val s1 = a.y.multiply(b.z).multiply(z2z2).mod(P)
        val s2 = b.y.multiply(a.z).multiply(z1z1).mod(P)
        if (u1 == u2) return if (s1 == s2) double(a) else INFINITY
        val h = u2.subtract(u1).mod(P)
        val r = s2.subtract(s1).mod(P)
        val h2 = h.multiply(h).mod(P)
        val h3 = h2.multiply(h).mod(P)
        val u1h2 = u1.multiply(h2).mod(P)
        val x3 = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P)
        val y3 = r.multiply(u1h2.subtract(x3)).subtract(s1.multiply(h3)).mod(P)
        val z3 = h.multiply(a.z).multiply(b.z).mod(P)
        return Jacobian(x3, y3, z3)
    }

    private fun multiply(k: BigInteger, p: Jacobian): Jacobian {
        var result = INFINITY
        for (i in k.bitLength() - 1 downTo 0) {
            result = double(result)
            if (k.testBit(i)) result = add(result, p)
        }
        return result
    }

    private fun affine(p: Jacobian): Affine? {
        if (p.infinite) return null
        val zInv = p.z.modInverse(P)
        val zInv2 = zInv.multiply(zInv).mod(P)
        return Affine(p.x.multiply(zInv2).mod(P), p.y.multiply(zInv2).multiply(zInv).mod(P))
    }

    /** The point with x [x] and an even y, or null when there is none (BIP-340's lift_x). */
    private fun liftX(x: BigInteger): Affine? {
        if (x >= P) return null
        val c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != c) return null
        return Affine(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    /** 32 big-endian bytes. */
    internal fun bytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun int(bytes: ByteArray) = BigInteger(1, bytes)

    internal fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray())
        return sha256(t, t, *parts)
    }

    /** A new secret key, 32 bytes, in range. */
    fun newSecretKey(): ByteArray {
        while (true) {
            val candidate = ByteArray(32).also(random::nextBytes)
            val d = int(candidate)
            if (d.signum() > 0 && d < N) return candidate
        }
    }

    /** The x-only public key of [secret], 32 bytes. */
    fun publicKey(secret: ByteArray): ByteArray {
        val d = int(secret)
        require(d.signum() > 0 && d < N) { "secret key out of range" }
        return bytes32(affine(multiply(d, G))!!.x)
    }

    /** BIP-340 Sign: 64 bytes over the 32-byte [message], with [aux] fresh randomness (or zeros). */
    fun sign(secret: ByteArray, message: ByteArray, aux: ByteArray = ByteArray(32).also(random::nextBytes)): ByteArray {
        require(message.size == 32 && aux.size == 32)
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "secret key out of range" }
        val p = affine(multiply(d0, G))!!
        val d = if (p.y.testBit(0)) N.subtract(d0) else d0
        val px = bytes32(p.x)
        val t = bytes32(d).let { db -> val h = taggedHash("BIP0340/aux", aux); ByteArray(32) { (db[it].toInt() xor h[it].toInt()).toByte() } }
        val k0 = int(taggedHash("BIP0340/nonce", t, px, message)).mod(N)
        require(k0.signum() != 0) { "nonce was zero" }
        val r = affine(multiply(k0, G))!!
        val k = if (r.y.testBit(0)) N.subtract(k0) else k0
        val rx = bytes32(r.x)
        val e = int(taggedHash("BIP0340/challenge", rx, px, message)).mod(N)
        return rx + bytes32(k.add(e.multiply(d)).mod(N))
    }

    /** BIP-340 Verify. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || message.size != 32 || signature.size != 64) return false
        val p = liftX(int(publicKey)) ?: return false
        val r = int(signature.copyOfRange(0, 32))
        val s = int(signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32), publicKey, message)).mod(N)
        val sG = multiply(s, G)
        val eP = multiply(N.subtract(e), Jacobian(p.x, p.y, BigInteger.ONE))
        val big = affine(add(sG, eP)) ?: return false
        return !big.y.testBit(0) && big.x == r
    }

    /** The x coordinate of [secret] times the point whose x-only key is [theirs]: a shared secret. */
    fun sharedX(secret: ByteArray, theirs: ByteArray): ByteArray? {
        val point = liftX(int(theirs)) ?: return null
        val shared = affine(multiply(int(secret), Jacobian(point.x, point.y, BigInteger.ONE))) ?: return null
        return bytes32(shared.x)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

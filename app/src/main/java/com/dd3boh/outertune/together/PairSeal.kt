/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A message for one phone of a room alone: sealed with a key only the two phones can make, each
 * from its own secret and the other's public key (the point they share, hashed). The room's own
 * key cannot keep a new code from a phone that was just sent out of the room; this can.
 */
internal object PairSeal {
    private val random = SecureRandom()

    /** [plain] for the phone with public key [theirKeyHex], as base64(iv + ciphertext); null for a key that is no point. */
    fun seal(mySecret: ByteArray, theirKeyHex: String, plain: String): String? {
        val key = key(mySecret, theirKeyHex) ?: return null
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return (iv + cipher.doFinal(plain.toByteArray())).toByteString().base64()
    }

    /** What [seal] sealed for this phone from the one with key [theirKeyHex], or null. */
    fun open(mySecret: ByteArray, theirKeyHex: String, sealed: String): String? = runCatching {
        val key = key(mySecret, theirKeyHex) ?: return null
        val data = sealed.decodeBase64()?.toByteArray() ?: return null
        if (data.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        cipher.updateAAD(AAD)
        String(cipher.doFinal(data, 12, data.size - 12))
    }.getOrNull()

    private fun key(mySecret: ByteArray, theirKeyHex: String): SecretKeySpec? = runCatching {
        Secp256k1.sharedX(mySecret, theirKeyHex.hexToBytes())
    }.getOrNull()?.let { SecretKeySpec(Secp256k1.sha256(it, AAD), "AES") }

    private val AAD = "intertune/together/pair".toByteArray()
}

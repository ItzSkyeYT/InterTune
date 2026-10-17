/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * A listening room is named by a code of twelve characters, K7QX-M2PA-9DTE, read aloud or typed
 * without doubt (no 0, O, 1 or I). Everything comes from it: the tag the relays file the room's
 * messages under, and the key they are sealed with. Relays only ever see the tag and ciphertext;
 * whoever has the code can read and write. 32^12 codes, about 10^18, and the key stretched so that
 * guessing one from the tag is slow. Android 8 and later: PBKDF2 with SHA-256 arrived there.
 *
 * The idea is OpenTune's (their rooms over Nostr, read 17 Oct 2026); the code is our own.
 */
class RoomCode private constructor(val raw: String) {

    val pretty: String get() = raw.chunked(4).joinToString("-")

    /** What relays index the room's events by. */
    val tag: String by lazy { Secp256k1.sha256("intertune/together/tag/$raw".toByteArray()).toHex().take(32) }

    private val key: SecretKeySpec by lazy {
        val spec = PBEKeySpec(raw.toCharArray(), "intertune/together/key".toByteArray(), ITERATIONS, 256)
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    /** [plain] sealed as base64(iv + ciphertext), bound to this room's tag. */
    fun seal(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(tag.toByteArray())
        return (iv + cipher.doFinal(plain.toByteArray())).toByteString().base64()
    }

    /** What [seal] sealed, or null when it is not this room's or was tampered with. */
    fun open(sealed: String): String? = runCatching {
        val data = sealed.decodeBase64()?.toByteArray() ?: return null
        if (data.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        cipher.updateAAD(tag.toByteArray())
        String(cipher.doFinal(data, 12, data.size - 12))
    }.getOrNull()

    /** A link that opens the room in InterTune. */
    val appLink: String get() = "intertune://together/$raw"

    override fun equals(other: Any?) = other is RoomCode && other.raw == raw
    override fun hashCode() = raw.hashCode()
    override fun toString() = pretty

    companion object {
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val LENGTH = 12
        private const val ITERATIONS = 40_000
        private val random = SecureRandom()

        fun generate() = RoomCode(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

        /** A code typed or pasted in any form ("k7qx m2pa-9dte", or the app link); null when it is not one. */
        fun parse(text: String?): RoomCode? {
            val value = text?.trim() ?: return null
            val candidate = if (value.startsWith("intertune://together/", ignoreCase = true)) {
                value.substring("intertune://together/".length)
            } else value
            if (!candidate.matches(Regex("[A-Za-z0-9\\s-]+"))) return null
            val cleaned = candidate.uppercase().filter { it.isLetterOrDigit() }
            return cleaned.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }?.let(::RoomCode)
        }
    }
}

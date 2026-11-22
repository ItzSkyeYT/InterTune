/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

/**
 * InterTune's developer in a room: a spanner on his circle and his name in gold (his ask of 22 Nov 2026, "a special
 * shield/spanner badge as i'm the dev"). Nothing more: no power of any kind comes with it.
 *
 * It cannot be faked by choosing a name. The developer key is a secp256k1 key pair like the rooms' own (BIP-340
 * Schnorr, [Secp256k1]); only its public half is here. A phone that holds the private half (put in by its owner, see
 * [DevKey]) adds a proof to its Here: the key's signature over this room's tag and that phone's own key in the room
 * ([message]). Every phone checks it against [PUBLIC_KEY], and only a proof made for that room and that sender counts:
 * copied into another phone's Here, or into another room, it fails. A build before this one ignores the field, and a
 * phone without the key sends none.
 */
object DevBadge {
    /** The developer key's public half, x-only. The private half is in no build and nowhere in the repo. */
    const val PUBLIC_KEY = "dad8bbd0400b4d4f5ee584061a5d210b1751a34636abae71c83429998ced7ca9"

    /** What a proof signs, apart from the room's tag and the sender: these proofs, and nothing else the key may sign. */
    private const val DOMAIN = "intertune/together/dev/"

    private val HEX_64 = Regex("[0-9a-f]{64}")
    private val HEX_128 = Regex("[0-9a-f]{128}")

    /** sha256 over [DOMAIN], the room's [tag] and the sender's x-only key [sender], both as bytes. */
    internal fun message(tag: String, sender: String): ByteArray =
        Secp256k1.sha256(DOMAIN.toByteArray(), tag.hexToBytes(), sender.hexToBytes())

    /** The proof the developer key [secret] gives for the phone with key [sender] in the room filed under [tag], hex. */
    fun prove(secret: ByteArray, tag: String, sender: String): String = Secp256k1.sign(secret, message(tag, sender)).toHex()

    /**
     * Whether [proof] is [publicKey]'s signature for the phone with key [sender] in the room filed under [tag]. Anything
     * else is false, never an exception: a word from anyone in the room lands here.
     */
    fun verifies(proof: String?, tag: String, sender: String, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
        proof != null && HEX_128.matches(proof) && HEX_64.matches(sender) &&
            Secp256k1.verify(publicKey.hexToBytes(), message(tag, sender), proof.hexToBytes())
    }.getOrDefault(false)

    /** [text] as pasted, when it is the private half of [publicKey] (64 hex characters); null for anything else. */
    fun keyOf(text: String?, publicKey: String = PUBLIC_KEY): ByteArray? = runCatching {
        val hex = text?.trim()?.lowercase()?.takeIf { HEX_64.matches(it) } ?: return null
        hex.hexToBytes().takeIf { Secp256k1.publicKey(it).toHex() == publicKey }
    }.getOrNull()
}

/**
 * Who in a room proved in their Here to be InterTune's developer ([DevBadge]), by their key in the room. A proof is
 * checked once, off the main thread (the session asks [due], checks, then gives the answer to [answer]); a key whose
 * proof failed is not checked again in this room, so a stranger cannot keep a phone busy with made-up proofs.
 */
internal class RoomDevs {
    private val proven = mutableMapOf<String, String>()
    private val refused = mutableSetOf<String>()

    /** Whether the phone with [key] proved it. */
    fun proven(key: String): Boolean = key in proven

    /** [key]'s Here said [proof], or none (which takes back what it proved): whether it is to be checked now. */
    fun due(key: String, proof: String?): Boolean {
        if (proof == null) {
            proven.remove(key)
            return false
        }
        return key !in refused && proven[key] != proof
    }

    /** The check of [key]'s [proof] said [ok]. */
    fun answer(key: String, proof: String, ok: Boolean) {
        if (ok) {
            proven[key] = proof
        } else {
            proven.remove(key)
            refused += key
        }
    }
}

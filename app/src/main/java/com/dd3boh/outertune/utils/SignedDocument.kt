/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * A document only the maintainer can have written: the news and questions, the donors, the newest version.
 *
 * Each is signed with his key, ECDSA on P-256 with SHA-256, which java.security has on every Android this
 * app runs on. So the app can take it from any host (the gist, intertune.app, the counter worker, Codeberg):
 * a host that is taken over, or a copy somebody else puts up, can only serve what he signed. The file:
 *
 * ```
 * { ...the old fields, for the builds before 0.13, which read only those...,
 *   "signed": { "key": "<id of the key>", "payload": "<the document, base64>", "sig": "<signature, base64>" } }
 * ```
 *
 * The payload is the document itself, its exact bytes, with three fields of its own: "kind" (news, donors or
 * update, so one cannot pass for another), "seq" (when it was signed, in seconds: a phone keeps the highest it
 * has taken and refuses a lower one, so an old document cannot be served again) and "expires". Only the payload
 * is believed; the old fields beside it are never read from here.
 *
 * Two keys are built in ([SigningKeys]), the main one and a spare kept offline, so a lost key is not the end. A
 * document signed with the spare may withdraw the main key ("revokes": [id]) for the day it is stolen. The main
 * key cannot withdraw the spare. Written with tools/announce/announce.py, format in docs/announcements.md.
 *
 * kotlinx.serialization and okio rather than org.json and android.util.Base64, so the rules run in unit tests.
 */
object SignedDocument {
    const val NEWS = "news"
    const val DONORS = "donors"
    const val UPDATE = "update"

    /** The longest payload read, in bytes. The whole file is about twice that at most, with the old fields. */
    const val MOST_BYTES = 512 * 1024

    /** How far ahead of the phone's clock a document may be dated: a phone a few days slow still reads it. */
    const val AHEAD_SECONDS = 7 * 24 * 60 * 60L

    /** A public key, named by [id], the first 16 hex characters of the SHA-256 of its encoded form. */
    class Key(val id: String, val publicKey: PublicKey, val spare: Boolean)

    sealed interface Result

    /** A document to believe: [payload] as text, [seq] to keep, and the key ids it withdraws ([revokes]). */
    data class Accepted(val payload: String, val seq: Long, val expires: Long, val key: String, val revokes: Set<String>) : Result

    /**
     * A document not to believe, and [why] in a few words for the log. [signed]: the file was at least a signed
     * one, so its host did answer with a document (an older copy, say), not with nothing or a sign-in page.
     */
    data class Refused(val why: String, val signed: Boolean) : Result

    /** A key from its SubjectPublicKeyInfo in base64, as tools/announce/public-keys.json holds it. */
    fun key(base64: String, spare: Boolean): Key {
        val der = base64.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("not base64")
        return Key(idOf(der), KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)), spare)
    }

    fun idOf(encoded: ByteArray): String = encoded.toByteString().sha256().hex().take(16)

    /**
     * Opens [body] as a [kind] document. Taken only when it is signed by one of [keys] that is not withdrawn
     * ([revoked]), is of that kind, is not older than [highestSeq], nor dated more than a week ahead of [now],
     * nor past its expiry. The checks run in that order, and the first that fails is the answer.
     */
    fun open(body: String, kind: String, keys: List<Key>, highestSeq: Long, revoked: Set<String>, now: Long): Result {
        if (body.length > 3 * MOST_BYTES) return Refused("too large", false)
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Refused("not JSON", false)
        val block = root["signed"] as? JsonObject ?: return Refused("not signed", false)
        val keyId = block.text("key")
        val payload = block.text("payload")?.decodeBase64()?.toByteArray()
        val signature = block.text("sig")?.decodeBase64()?.toByteArray()
        if (keyId == null || payload == null || signature == null) return Refused("an unreadable signature", false)
        if (payload.size > MOST_BYTES) return Refused("too large", true)

        val key = keys.firstOrNull { it.id == keyId } ?: return Refused("signed with a key this build does not know", true)
        if (!key.spare && keyId in revoked) return Refused("signed with a withdrawn key", true)
        val good = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key.publicKey)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!good) return Refused("the signature does not match", true)

        // Signed by the maintainer from here on: what is left are his own rules about it.
        val text = String(payload, Charsets.UTF_8)
        val doc = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return Refused("an unreadable document", true)
        if ((doc["kind"] as? JsonPrimitive)?.contentOrNull != kind) return Refused("not a $kind document", true)
        val seq = (doc["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
            ?: return Refused("no sequence number", true)
        if (seq < highestSeq) return Refused("older than one already taken", true)
        if (seq > now / 1000 + AHEAD_SECONDS) return Refused("dated ahead of this phone's clock", true)
        val expires = expiry(doc) ?: return Refused("no expiry", true)
        if (now >= expires) return Refused("expired", true)

        // Only the spare withdraws, and only the main key: a stolen main key cannot lock the spare out.
        val revokes = if (!key.spare) emptySet() else (doc["revokes"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { id -> keys.any { it.id == id && !it.spare } }
            .toSet()
        return Accepted(text, seq, expires, key.id, revokes)
    }

    /**
     * Whether a payload taken before is still in date. What was taken stays believed on the phone (its own
     * storage), until its expiry: an offline phone keeps showing it, a phone offline past it shows nothing.
     */
    fun inDate(payload: String?, now: Long): Boolean {
        val doc = payload?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return false
        val expires = expiry(doc) ?: return false
        return now < expires
    }

    private fun expiry(doc: JsonObject): Long? =
        (doc["expires"] as? JsonPrimitive)?.let { runCatching { AnnouncementParser.parseTime(it, endOfDay = true) }.getOrNull() }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/**
 * The public halves of the maintainer's two keys (tools/announce/public-keys.json). The private halves are on
 * his PC only, in ~/.config/intertune/announce-keys/, and in the encrypted backup. A new key means a new build.
 */
object SigningKeys {
    /** Signs every document. */
    private const val MAIN = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEL3slWo8tsivEyiJfhzw7pEf8ALUx/tclB71w+fwrvPS+fbUm6iQoLX/93Zu23q11SLxaCmbXFTIPS4aHPB7Gew=="

    /** Kept offline. Signs only when the main key is lost or stolen, and may then withdraw it. */
    private const val SPARE = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECziy0Cr6Ckv/UwEpu9I9cJSnf8fPDG8SgP2IX6Ot2F324TdhPfICCjB+WPqIQX9a3d0RB+gWa4muVIyjQoTSPw=="

    val BUILT_IN: List<SignedDocument.Key> by lazy {
        listOf(SignedDocument.key(MAIN, spare = false), SignedDocument.key(SPARE, spare = true))
    }
}

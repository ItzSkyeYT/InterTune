/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Only a document the maintainer signed is believed, from any host (SignedDocument). Keys made here for the test,
 * and documents signed by tools/announce/announce.py itself (src/test/resources/signed, signed with a throwaway
 * key whose private half was deleted), so the tool and the app are held to the same format.
 */
class SignedDocumentTest {

    private val main = pair()
    private val spare = pair()
    private val stranger = pair()
    private val keys = listOf(key(main, spare = false), key(spare, spare = true))

    @Test
    fun `a document signed with the main key is taken, as it was signed`() {
        val result = open(sign(main, payload()))
        assertTrue(result.toString(), result is SignedDocument.Accepted)
        result as SignedDocument.Accepted
        assertEquals(payload(), result.payload)
        assertEquals(SEQ, result.seq)
        assertEquals(keys[0].id, result.key)
        assertEquals(1_802_520_000_000L, result.expires)
    }

    @Test
    fun `the spare signs too`() {
        assertTrue(open(sign(spare, payload())) is SignedDocument.Accepted)
    }

    @Test
    fun `the old fields beside the signed part are never what is believed`() {
        val result = open(sign(main, payload())) as SignedDocument.Accepted
        val doc = Json.parseToJsonElement(result.payload).jsonObject
        val title = doc["announcements"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content
        assertEquals("Signed", title)
    }

    @Test
    fun `a changed byte, a stranger's key and no signature at all are refused`() {
        val good = sign(main, payload())
        val forged = good.replace(base64(payload().toByteArray()), base64(payload().replace("Signed", "Forged").toByteArray()))
        assertRefused(forged, "the signature does not match", signed = true)
        assertRefused(sign(stranger, payload()), "signed with a key this build does not know", signed = true)
        assertRefused(LEGACY, "not signed", signed = false)
        assertRefused("<html>Sign in to the hotel's wifi</html>", "not JSON", signed = false)
    }

    @Test
    fun `a signature that is not one, and a key named as another, are refused`() {
        assertRefused(sign(main, payload()).replace(Regex("\"sig\":\"[^\"]+\""), "\"sig\":\"not base64!\""), "an unreadable signature", false)
        assertRefused(sign(main, payload()).replace(Regex("\"sig\":\"[^\"]+\""), "\"sig\":\"${base64(ByteArray(64) { 1 })}\""), "the signature does not match", true)
        // Signed by the spare but naming the main key: checked against the key it names, and it does not match.
        assertRefused(sign(spare, payload(), keyId = keys[0].id), "the signature does not match", true)
    }

    @Test
    fun `an older document is refused, the same one is taken again`() {
        assertRefused(sign(main, payload()), "older than one already taken", true, highest = SEQ + 1)
        assertTrue(open(sign(main, payload()), highest = SEQ) is SignedDocument.Accepted)
        assertTrue(open(sign(main, payload(seq = SEQ + 1)), highest = SEQ) is SignedDocument.Accepted)
    }

    @Test
    fun `a document dated more than a week ahead of the phone is refused`() {
        val now = NOW / 1000
        assertRefused(sign(main, payload(seq = now + 8 * DAY)), "dated ahead of this phone's clock", true)
        assertTrue(open(sign(main, payload(seq = now + 6 * DAY))) is SignedDocument.Accepted)
    }

    @Test
    fun `an expired document, or one with no expiry, is refused`() {
        assertRefused(sign(main, payload(expires = "2026-11-15T11:00:00Z")), "expired", true)
        assertRefused(sign(main, payload().replace("\"expires\":\"2027-02-13T12:00:00Z\",", "")), "no expiry", true)
        assertRefused(sign(main, payload().replace("\"seq\":$SEQ,", "")), "no sequence number", true)
        assertRefused(sign(main, payload().replace("\"seq\":$SEQ,", "\"seq\":\"$SEQ\",")), "no sequence number", true)
    }

    @Test
    fun `a document of another kind cannot pass for this one`() {
        assertRefused(sign(main, payload(kind = "donors")), "not a news document", true)
        assertTrue(SignedDocument.open(sign(main, payload(kind = "donors")), "donors", keys, 0, emptySet(), NOW) is SignedDocument.Accepted)
    }

    @Test
    fun `only the spare withdraws a key, and only the main one`() {
        val revokes = ",\"revokes\":[\"${keys[0].id}\",\"${keys[1].id}\",\"0123456789abcdef\"]"
        assertEquals(emptySet<String>(), (open(sign(main, payload(extra = revokes))) as SignedDocument.Accepted).revokes)
        assertEquals(setOf(keys[0].id), (open(sign(spare, payload(extra = revokes))) as SignedDocument.Accepted).revokes)
    }

    @Test
    fun `a withdrawn main key is refused from then on, the spare never`() {
        val withdrawn = setOf(keys[0].id, keys[1].id)
        assertRefused(sign(main, payload()), "signed with a withdrawn key", true, revoked = withdrawn)
        assertTrue(open(sign(spare, payload()), revoked = withdrawn) is SignedDocument.Accepted)
    }

    @Test
    fun `a payload larger than the app reads is refused`() {
        val big = payload(extra = ",\"pad\":\"${"x".repeat(SignedDocument.MOST_BYTES)}\"")
        assertRefused(sign(main, big), "too large", true)
    }

    @Test
    fun `a payload kept is believed until its expiry`() {
        assertTrue(SignedDocument.inDate(payload(), NOW))
        assertFalse(SignedDocument.inDate(payload(), 1_802_520_000_000L))
        assertFalse(SignedDocument.inDate("not a document", NOW))
        assertFalse(SignedDocument.inDate(null, NOW))
    }

    @Test
    fun `the documents tools announce makes open here`() {
        val dir = File("src/test/resources/signed")
        val fixture = Json.parseToJsonElement(File(dir, "test-keys.json").readText()).jsonObject
        val made = listOf(
            SignedDocument.key(fixture["main"]!!.jsonObject["spki"]!!.jsonPrimitive.content, spare = false),
            SignedDocument.key(fixture["spare"]!!.jsonObject["spki"]!!.jsonPrimitive.content, spare = true),
        )
        assertEquals(fixture["main"]!!.jsonObject["id"]!!.jsonPrimitive.content, made[0].id)
        assertEquals(fixture["spare"]!!.jsonObject["id"]!!.jsonPrimitive.content, made[1].id)
        val now = fixture["now"]!!.jsonPrimitive.long * 1000 + 3_600_000
        fun open(name: String, kind: String) = SignedDocument.open(File(dir, name).readText(), kind, made, 0, emptySet(), now)

        val news = open("news.json", SignedDocument.NEWS) as SignedDocument.Accepted
        val doc = Json.parseToJsonElement(news.payload).jsonObject
        assertEquals("release-0.13", doc["announcements"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Écouter ensemble", doc["announcements"]!!.jsonArray[0].jsonObject["translations"]!!.jsonObject["fr"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue("the payload names its pictures", "pictures" in doc)
        assertEquals(made[0].id, news.key)
        assertEquals(emptySet<String>(), news.revokes)

        val withdraws = open("news-spare-revokes-main.json", SignedDocument.NEWS) as SignedDocument.Accepted
        assertEquals(setOf(made[0].id), withdraws.revokes)
        assertTrue(open("donors.json", SignedDocument.DONORS) is SignedDocument.Accepted)
        assertTrue(open("latest.json", SignedDocument.UPDATE) is SignedDocument.Accepted)
        assertTrue(open("news.json", SignedDocument.DONORS) is SignedDocument.Refused)
        // The real keys do not open what the test key signed.
        assertTrue(SignedDocument.open(File(dir, "news.json").readText(), SignedDocument.NEWS, SigningKeys.BUILT_IN, 0, emptySet(), now) is SignedDocument.Refused)
    }

    @Test
    fun `the app holds two keys, the main one and the spare`() {
        val built = SigningKeys.BUILT_IN
        assertEquals(listOf("0d7c502f69564228", "fc3b54e6840fa81a"), built.map { it.id })
        assertEquals(listOf(false, true), built.map { it.spare })
        assertEquals("EC", built[0].publicKey.algorithm)
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun open(body: String, highest: Long = 0, revoked: Set<String> = emptySet()) =
        SignedDocument.open(body, SignedDocument.NEWS, keys, highest, revoked, NOW)

    private fun assertRefused(body: String, why: String, signed: Boolean, highest: Long = 0, revoked: Set<String> = emptySet()) {
        val result = open(body, highest, revoked)
        assertEquals(SignedDocument.Refused(why, signed), result)
    }

    companion object {
        /** 15 Nov 2026, 12:00 UTC, in seconds: when the test documents were signed. */
        const val SEQ = 1_794_744_000L

        /** An hour later, in milliseconds. */
        const val NOW = SEQ * 1000 + 3_600_000L
        const val DAY = 24 * 60 * 60L

        /** What builds before 0.13 read: no signature at all. */
        const val LEGACY = """{"polls":[],"announcements":[{"id":"n","banner":"B","title":"Unsigned"}]}"""

        fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun key(pair: KeyPair, spare: Boolean) = SignedDocument.Key(SignedDocument.idOf(pair.public.encoded), pair.public, spare)

        fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun payload(seq: Long = SEQ, expires: String = "2027-02-13T12:00:00Z", kind: String = "news", extra: String = "") =
            """{"kind":"$kind","seq":$seq,"expires":"$expires"$extra,"announcements":[{"id":"n","banner":"B","title":"Signed"}]}"""

        /** The file as the hosts serve it: the old fields for older builds, and the signed part. */
        fun sign(pair: KeyPair, payload: String, keyId: String = SignedDocument.idOf(pair.public.encoded)): String {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(bytes)
                sign()
            }
            return """{"polls":[],"announcements":[{"id":"n","banner":"B","title":"Unsigned"}],""" +
                """"signed":{"key":"$keyId","payload":"${base64(bytes)}","sig":"${base64(sig)}"}}"""
        }
    }
}

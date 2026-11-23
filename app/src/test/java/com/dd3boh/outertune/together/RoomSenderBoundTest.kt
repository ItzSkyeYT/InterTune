/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The room's seal knows only the room, and its events carry the room's tag in clear: anyone subscribed to the public
 * relays sees every room's sealed words, and could sign one again with a key of their own. Its signature was then good
 * and it opened, so a suggestion was queued again, a proposal asked again, a stranger listed, and the owner's Here, so
 * re-signed, made a stranger the owner for a guest that had not heard the owner yet (review 2, 18 Nov 2026). Now the
 * sender's key is sealed inside each word, and a word whose signer is someone else is refused.
 */
class RoomSenderBoundTest {

    private val code = RoomCode.generate()
    private val tags = listOf(listOf("t", code.tag))

    /** What [secret]'s phone sends: [message] sealed with its own key inside, in an event it signs. */
    private fun sent(secret: ByteArray, message: TogetherMessage): NostrEvent {
        val me = Secp256k1.publicKey(secret).toHex()
        return NostrEvent.signed(secret, TogetherMessage.KIND, tags, code.seal(TogetherMessage.encode(message, sender = me)))
    }

    /** What the session takes of [event]: opened with the room's code, from its own signer only. */
    private fun taken(event: NostrEvent): TogetherMessage? = code.open(event.content)?.let { TogetherMessage.decodeFrom(it, event.pubkey) }

    @Test
    fun `a member's word signed again by another key is refused`() {
        val member = Secp256k1.newSecretKey()
        val stranger = Secp256k1.newSecretKey()
        for (message in listOf(
            TogetherMessage.Suggest("dQw4w9WgXcQ", "A song", "Someone", "", "Pixel"),
            TogetherMessage.Here("Mel", owner = true, lead = "ab12"),
            TogetherMessage.Propose(listOf(TogetherMessage.Song("dQw4w9WgXcQ", "A song", "Someone", "")), 1_000, "Pixel"),
            TogetherMessage.End,
        )) {
            val real = sent(member, message)
            assertTrue(real.valid())
            assertEquals(message, taken(real))
            // the same sealed word in an event of the stranger's: its signature is good and it opens, but it is not theirs
            val copy = NostrEvent.signed(stranger, TogetherMessage.KIND, tags, real.content)
            assertTrue(copy.valid())
            assertNotNull(code.open(copy.content))
            assertNull("a re-signed $message was taken", taken(copy))
        }
    }

    @Test
    fun `a word with no key inside, or another's, is refused`() {
        val member = Secp256k1.newSecretKey()
        val other = Secp256k1.publicKey(Secp256k1.newSecretKey()).toHex()
        val here = TogetherMessage.Here("Mel", owner = true)
        // a build before this one sealed no key
        val old = NostrEvent.signed(member, TogetherMessage.KIND, tags, code.seal(TogetherMessage.encode(here)))
        assertNull(taken(old))
        // a key inside that is not the signer's
        val named = NostrEvent.signed(member, TogetherMessage.KIND, tags, code.seal(TogetherMessage.encode(here, sender = other)))
        assertNull(taken(named))
        // the key travels inside the seal, never in clear
        val real = sent(member, here)
        assertFalse(Secp256k1.publicKey(member).toHex() in real.content)
        assertTrue("\"k\":\"${real.pubkey}\"" in code.open(real.content)!!)
    }

    @Test
    fun `each sealed word is taken once, whatever event carries it`() {
        val seals = TakenSeals(kept = 3)
        val a = code.seal("a")
        assertTrue(seals.take(a))
        assertFalse(seals.take(a))
        // a seal is never made twice: the same text sealed again is another word
        assertTrue(seals.take(code.seal("a")))
        assertTrue(seals.take(code.seal("b")))
        assertTrue(seals.take(code.seal("c")))
        // the oldest beyond those kept is forgotten
        assertTrue(seals.take(a))
    }

    @Test
    fun `the session seals its key in, and takes a word only from its signer, then once`() {
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        assertTrue("code.seal(TogetherMessage.encode(message, sender = me))" in session.substringAfter("private fun send("))
        assertTrue("code.seal(TogetherMessage.encode(TogetherMessage.End, sender = me))" in session.substringAfter("fun closeNow()"))
        assertFalse("TogetherMessage::decode" in session)
        assertFalse("TogetherMessage.decode(" in session)
        val onEvent = session.substringAfter("private fun onEvent(").substringBefore("val now = System.currentTimeMillis()")
        val opened = onEvent.indexOf("code.open(event.content)?.let { TogetherMessage.decodeFrom(it, from) } ?: return")
        val once = onEvent.indexOf("if (!seals.take(event.content)) return")
        assertTrue(opened >= 0)
        assertTrue("each word once, after the signer's check", once > opened)
        // the session never sends a word without its key: every encode in it names this phone
        assertEquals(0, Regex("""TogetherMessage\.encode\((?![^)]*sender = me)""").findAll(session).count())
    }
}

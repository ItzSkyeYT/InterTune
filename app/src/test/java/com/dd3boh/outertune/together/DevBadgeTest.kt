/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.ui.dialog.GLOW_CYCLE_MS
import com.dd3boh.outertune.ui.dialog.GLOW_HALF
import com.dd3boh.outertune.ui.dialog.GLOW_PASS_MS
import com.dd3boh.outertune.ui.dialog.glowAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * InterTune's developer in rooms (DevBadge): his badge comes from a proof only his key makes, bound to the room and to
 * the phone that sends it, and checked by every phone against the key's public half. Nothing else marks anyone.
 */
class DevBadgeTest {

    /** A key pair of the tests' own: the developer's private key is in no repo and no test. */
    private val secret = Secp256k1.newSecretKey()
    private val public = Secp256k1.publicKey(secret).toHex()
    private val room = RoomCode.generate().tag
    private val phone = Secp256k1.publicKey(Secp256k1.newSecretKey()).toHex()

    @Test
    fun `a proof made for a room and a phone holds for them`() {
        val proof = DevBadge.prove(secret, room, phone)
        assertEquals(128, proof.length)
        assertTrue(DevBadge.verifies(proof, room, phone, publicKey = public))
    }

    @Test
    fun `the same proof fails in another room, from another phone, or for another key`() {
        val proof = DevBadge.prove(secret, room, phone)
        assertFalse("another room", DevBadge.verifies(proof, RoomCode.generate().tag, phone, publicKey = public))
        val stranger = Secp256k1.publicKey(Secp256k1.newSecretKey()).toHex()
        assertFalse("another phone", DevBadge.verifies(proof, room, stranger, publicKey = public))
        // made by any key but the developer's: never his badge
        assertFalse("another key", DevBadge.verifies(proof, room, phone))
        val other = Secp256k1.publicKey(Secp256k1.newSecretKey()).toHex()
        assertFalse(DevBadge.verifies(proof, room, phone, publicKey = other))
    }

    @Test
    fun `anything that is not a proof is no proof, and never throws`() {
        for (junk in listOf(null, "", "zz", "00".repeat(64), "ab".repeat(80), DevBadge.prove(secret, room, phone).uppercase())) {
            assertFalse("$junk", DevBadge.verifies(junk, room, phone, publicKey = public))
        }
        assertFalse(DevBadge.verifies(DevBadge.prove(secret, room, phone), room, "not a key", publicKey = public))
        assertFalse(DevBadge.verifies(DevBadge.prove(secret, room, phone), "not a tag", phone, publicKey = public))
    }

    /**
     * A proof the developer key made, on the PC that keeps it, for a made-up room and a key nobody holds (BIP-341's H):
     * it ties [DevBadge.PUBLIC_KEY] to that key and the proof's make to this code.
     */
    @Test
    fun `the developer key's own proof holds against the key in the app`() {
        val tag = "00112233445566778899aabbccddeeff"
        val sender = "50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0"
        val proof = "188efe4f8a2707dde64c121d82ccb41d0504f8670b3b6d2bff28c7b93d7c9420" +
            "2db6d3ff8e30b33da15e47ac3f2f79371fdc0a980142808c5ad08e1d7a79b856"
        assertTrue(DevBadge.verifies(proof, tag, sender))
        assertFalse(DevBadge.verifies(proof, "00112233445566778899aabbccddeefe", sender))
        assertFalse(DevBadge.verifies(proof, tag, phone))
    }

    @Test
    fun `only the developer key is taken as one, pasted with spaces or in capitals`() {
        val hex = secret.toHex()
        assertNotNull(DevBadge.keyOf(hex, publicKey = public))
        assertNotNull(DevBadge.keyOf("  ${hex.uppercase()}\n", publicKey = public))
        assertTrue(DevBadge.keyOf(hex, publicKey = public)!!.contentEquals(secret))
        // another key, or not a key at all
        assertNull(DevBadge.keyOf(Secp256k1.newSecretKey().toHex(), publicKey = public))
        assertNull(DevBadge.keyOf(hex))
        for (junk in listOf(null, "", hex.dropLast(1), hex + "0", "g" + hex.drop(1), "00".repeat(32), "ff".repeat(32))) {
            assertNull("$junk", DevBadge.keyOf(junk, publicKey = public))
        }
    }

    @Test
    fun `a Here carries the proof inside the seal, and one without it reads as before`() {
        val proof = DevBadge.prove(secret, room, phone)
        val with = TogetherMessage.Here("Skye", owner = false, dev = proof)
        val back = TogetherMessage.decode(TogetherMessage.encode(with)) as TogetherMessage.Here
        assertEquals(proof, back.dev)
        // a phone without the key sends no field at all
        val without = TogetherMessage.Here("Pixel", owner = false)
        assertFalse("\"dev\"" in TogetherMessage.encode(without))
        assertNull((TogetherMessage.decode(TogetherMessage.encode(without)) as TogetherMessage.Here).dev)
        // a build before this one: no field, read as ever
        val old = TogetherMessage.decode("""{"t":"here","name":"Ti-Die","owner":true,"lead":"ab12","since":5}""") as TogetherMessage.Here
        assertEquals(TogetherMessage.Here("Ti-Die", owner = true, lead = "ab12", leadSince = 5), old)
        // something else in its place is no proof
        assertNull((TogetherMessage.decode("""{"t":"here","name":"x","owner":false,"dev":null}""") as TogetherMessage.Here).dev)
        val long = TogetherMessage.decode("""{"t":"here","name":"x","owner":false,"dev":"${"a".repeat(5000)}"}""") as TogetherMessage.Here
        assertFalse(DevBadge.verifies(long.dev, room, phone, publicKey = public))
    }

    @Test
    fun `a proof copied into another phone's Here fails there`() {
        val code = RoomCode.generate()
        val tags = listOf(listOf("t", code.tag))
        val mine = Secp256k1.newSecretKey()
        val me = Secp256k1.publicKey(mine).toHex()
        val proof = DevBadge.prove(secret, code.tag, me)
        val real = NostrEvent.signed(mine, TogetherMessage.KIND, tags, code.seal(TogetherMessage.encode(TogetherMessage.Here("Skye", false, dev = proof), sender = me)))
        val taken = code.open(real.content)?.let { TogetherMessage.decodeFrom(it, real.pubkey) } as TogetherMessage.Here
        assertTrue(DevBadge.verifies(taken.dev, code.tag, real.pubkey, publicKey = public))
        // a stranger's own Here with the proof in it
        val theirs = Secp256k1.newSecretKey()
        val them = Secp256k1.publicKey(theirs).toHex()
        val copy = NostrEvent.signed(theirs, TogetherMessage.KIND, tags, code.seal(TogetherMessage.encode(TogetherMessage.Here("Skye", false, dev = proof), sender = them)))
        val copied = code.open(copy.content)?.let { TogetherMessage.decodeFrom(it, copy.pubkey) } as TogetherMessage.Here
        assertFalse(DevBadge.verifies(copied.dev, code.tag, copy.pubkey, publicKey = public))
        // and in another room, from the same phone
        val elsewhere = RoomCode.generate()
        assertFalse(DevBadge.verifies(proof, elsewhere.tag, me, publicKey = public))
    }

    @Test
    fun `each proof is checked once, and a phone whose proof failed is not checked again`() {
        val devs = RoomDevs()
        assertFalse(devs.due("a", null))
        assertTrue(devs.due("a", "p1"))
        devs.answer("a", "p1", ok = true)
        assertTrue(devs.proven("a"))
        assertFalse("the same proof again", devs.due("a", "p1"))
        // a Here without it takes it back
        assertFalse(devs.due("a", null))
        assertFalse(devs.proven("a"))
        // a stranger's made-up proofs: one check, then none
        assertTrue(devs.due("b", "junk"))
        devs.answer("b", "junk", ok = false)
        assertFalse(devs.proven("b"))
        assertFalse(devs.due("b", "more junk"))
    }

    @Test
    fun `the light crosses the name, then rests off it`() {
        assertEquals(-GLOW_HALF, glowAt(0f), 1e-4f)
        val passed = GLOW_PASS_MS.toFloat() / GLOW_CYCLE_MS
        assertEquals(1f + GLOW_HALF, glowAt(passed - 1e-6f), 1e-3f)
        for (time in listOf(passed, 0.8f, 0.999f)) assertTrue(glowAt(time) - GLOW_HALF > 1f)
    }

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `only a checked proof marks someone, and this phone only while it holds the key`() {
        val session = source("together/TogetherSession.kt")
        // a member is the developer's only as RoomDevs says, and RoomDevs hears only DevBadge's check
        assertTrue("dev = devs.proven(key)" in session)
        assertEquals(1, Regex("""devs\.answer\(""").findAll(session).count())
        assertTrue("DevBadge.verifies(proof, code.tag, from)" in session)
        // this phone's own proof comes from the key kept here, made for this room and this phone's key
        assertTrue("DevBadge.prove(it, code.tag, me)" in session)
        assertTrue("DevKey.flow(service)" in session)
        val dialog = source("ui/dialog/TogetherDialog.kt")
        assertTrue("dev = member.dev" in dialog)
        assertTrue("meDev = status.dev" in dialog)
        assertTrue("status.members.any { it.leader && it.dev }" in dialog)
        // the badge and the light are drawn only for those
        val person = dialog.substringAfter("private fun Person(").substringBefore("/** One or two plain lines")
        assertTrue(Regex("""if \(dev\) \{\s+DevBadgeMark\(""").containsMatchIn(person))
        assertTrue(Regex("""if \(dev\) \{\s+GlowingName\(""").containsMatchIn(person))
    }

    @Test
    fun `the key file on the developer's PC is never read by the app, which keeps the key in its own preference`() {
        val main = File("src/main")
        val mentions = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "java") }
            .filter { file -> file.readText().let { "intertune-dev" in it || "dev.key" in it } }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), mentions)
        // the one 64-character key in the badge's code is the public half
        val badge = source("together/DevBadge.kt")
        assertEquals(listOf(DevBadge.PUBLIC_KEY), Regex("\"([0-9a-fA-F]{64})\"").findAll(badge).map { it.groupValues[1] }.toList())
        // kept in a file of its own, out of the app's backups, the cloud's and a move to a new phone
        assertTrue("preferencesDataStore(name = FILE)" in source("together/DevKey.kt"))
        assertEquals("dev_key", DevKey.FILE)
        assertTrue("datastore/dev_key.preferences_pb" in File("src/main/res/xml/data_extraction_rules.xml").readText())
        assertTrue("path=\"datastore/\"" in File("src/main/res/xml/backup_rules.xml").readText())
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chain's order and the kill switches, set from the document the news comes in. */
class RemoteRoutesTest {
    private val day = 24 * 60 * 60 * 1000L

    /** 21 Oct 2026, noon UTC. */
    private val now = 1_792_584_000_000L

    private val chain = listOf("ANDROID_VR", "VISIONOS", "VISIONOS_104", "VISIONOS_SAFARI", "WEB_REMIX", "IOS")

    private fun document(block: String) = """{"polls": [], "announcements": [], "playback": $block}"""

    private val closed = document(
        """{"v": 1, "id": "2026-10-21-visionos-closed", "minVersionCode": 96, "maxVersionCode": 120, "until": "2026-11-15",
           "order": ["VISIONOS_104", "VISIONOS_SAFARI", "WEB_REMIX", "VISIONOS", "IOS"], "disabled": ["VISIONOS"],
           "hedge": true, "uncheckedPlay": false, "watchdog": true, "sabr": true}""",
    )

    private fun parse(json: String, version: Int = 100, notes: MutableList<String> = mutableListOf()) =
        RemoteRoutes.parse(json, version, fetchedAt = now, now = now, notes = notes)

    @After
    fun none() {
        RemoteRoutes.current = null
    }

    @Test
    fun `a valid block reorders the chain and takes a client out of it`() {
        val block = parse(closed)
        assertNotNull(block)
        assertEquals(listOf("VISIONOS_104", "VISIONOS_SAFARI", "WEB_REMIX", "IOS", "ANDROID_VR"), RemoteRoutes.applyTo(chain, block))
        assertFalse(block!!.uncheckedPlay)
        assertTrue(block.hedge)
    }

    @Test
    fun `clients it does not name keep their place after the ones it does, and names this build does not know are dropped`() {
        val notes = mutableListOf<String>()
        val block = parse(document("""{"v": 1, "id": "x", "order": ["TV_SAMSUNG", "IOS", "IOS"]}"""), notes = notes)
        assertEquals(listOf("IOS"), block!!.order)
        assertEquals(listOf("IOS", "ANDROID_VR", "VISIONOS", "VISIONOS_104", "VISIONOS_SAFARI", "WEB_REMIX"), RemoteRoutes.applyTo(chain, block))
        assertTrue(notes.toString(), notes.any { "TV_SAMSUNG" in it })
    }

    @Test
    fun `a block that disables every client serving whole songs is refused, and the chain keeps them`() {
        val notes = mutableListOf<String>()
        val block = parse(document("""{"v": 1, "id": "x", "disabled": ["VISIONOS", "VISIONOS_104", "VISIONOS_SAFARI", "WEB_REMIX"]}"""), notes = notes)
        assertEquals(emptyList<String>(), block!!.disabled)
        assertTrue(notes.contains("it disabled every full-stream client"))
        // And on a chain without the web client, disabling VISIONOS's names alone is not applied either.
        val noWeb = chain - "WEB_REMIX"
        val partial = parse(document("""{"v": 1, "id": "x", "disabled": ["VISIONOS", "VISIONOS_104", "VISIONOS_SAFARI"]}"""))
        assertEquals(noWeb, RemoteRoutes.applyTo(noWeb, partial))
    }

    @Test
    fun `outside its versions, expired, of another version, or not JSON, nothing changes`() {
        for (json in listOf(
            closed.replace("\"minVersionCode\": 96", "\"minVersionCode\": 101"),
            document("""{"v": 1, "id": "x", "until": "2026-10-01"}"""),
            document("""{"v": 2, "id": "x"}"""),
            document("""{"v": 1, "id": "not an id!"}"""),
            "not json",
            """{"polls": []}""",
            "{\"playback\": {\"v\": 1, \"id\": \"x\"}, \"pad\": \"" + "a".repeat(RemoteRoutes.MOST_BYTES) + "\"}",
        )) {
            val block = parse(json)
            assertNull(json.take(80), block)
            assertEquals(chain, RemoteRoutes.applyTo(chain, block))
        }
    }

    @Test
    fun `a field of the wrong type is dropped, not the block`() {
        val block = parse(document("""{"v": 1, "id": "x", "order": "VISIONOS", "disabled": ["IOS"], "hedge": "false", "watchdog": 0}"""))
        assertEquals(emptyList<String>(), block!!.order)
        assertEquals(listOf("IOS"), block.disabled)
        assertTrue("a string is not a boolean", block.hedge)
        assertTrue("nor is a number", block.watchdog)
    }

    @Test
    fun `a block lasts fourteen days without an until, and never more than forty five`() {
        assertEquals(now + 14 * day, parse(document("""{"v": 1, "id": "x"}"""))!!.until)
        assertEquals(now + 45 * day, parse(document("""{"v": 1, "id": "x", "until": "2027-06-01"}"""))!!.until)
    }

    @Test
    fun `the switches are on with no block, and off only while a block in force says so`() {
        assertTrue(RemoteRoutes.uncheckedPlay && RemoteRoutes.hedge && RemoteRoutes.watchdog)
        RemoteRoutes.read(document("""{"v": 1, "id": "x", "uncheckedPlay": false}"""), 100, fetchedAt = System.currentTimeMillis())
        assertFalse(RemoteRoutes.uncheckedPlay)
        RemoteRoutes.read(null, 100, fetchedAt = System.currentTimeMillis())
        assertTrue(RemoteRoutes.uncheckedPlay)
    }

    @Test
    fun `SABR's rung has a switch of its own, on unless a block in force says off`() {
        assertTrue(RemoteRoutes.sabr)
        assertTrue(parse(closed)!!.sabr)
        RemoteRoutes.read(document("""{"v": 1, "id": "x", "sabr": false}"""), 100, fetchedAt = System.currentTimeMillis())
        assertFalse(RemoteRoutes.sabr)
        assertTrue("the other switches stay as they are", RemoteRoutes.hedge && RemoteRoutes.uncheckedPlay && RemoteRoutes.watchdog)
        assertTrue("a string is not a boolean", parse(document("""{"v": 1, "id": "x", "sabr": "false"}"""))!!.sabr)
        RemoteRoutes.read(null, 100, fetchedAt = System.currentTimeMillis())
        assertTrue(RemoteRoutes.sabr)
        // Expired, the block switches nothing off.
        RemoteRoutes.read(document("""{"v": 1, "id": "x", "sabr": false}"""), 100, fetchedAt = System.currentTimeMillis() - 50 * day)
        assertTrue(RemoteRoutes.sabr)
    }
}

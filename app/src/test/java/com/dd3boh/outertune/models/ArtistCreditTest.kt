/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His report of 27 Nov 2026: "Thread The Needle", by Hoax, So What and Dynamite MC, liked and downloaded, was by
 * one artist, "Hoax, So What, & Dynamite MC", whose page was empty. YouTube's own text for that credit, with its
 * serial comma (its player still gives it as the video's author, "et" in French and "und" in German), was stored as
 * one artist; YouTube names the three one by one now (Hoax and Dynamite MC linked, So What with no page), and
 * the stored song takes them when they spell its one name, and only then.
 */
class ArtistCreditTest {

    private val three = listOf("Hoax", "So What", "Dynamite MC")

    @Test
    fun `his song's one name is its three artists, however YouTube wrote it`() {
        assertTrue(ArtistCredit.spells("Hoax, So What, & Dynamite MC", three))
        assertTrue(ArtistCredit.spells("Hoax, So What & Dynamite MC", three))
        assertTrue(ArtistCredit.spells("Hoax, So What, Dynamite MC", three))
        assertTrue(ArtistCredit.spells("Hoax, So What et Dynamite MC", three))
        assertTrue(ArtistCredit.spells("Hoax, So What und Dynamite MC", three))
        assertTrue(ArtistCredit.spells("hoax, so what, & dynamite mc", three))
        assertTrue(ArtistCredit.spells("  Hoax, So What, & Dynamite MC ", three))
    }

    @Test
    fun `two artists, a feature, a cross, and a credit in Japanese`() {
        assertTrue(ArtistCredit.spells("Anyma & Baset", listOf("Anyma", "Baset")))
        assertTrue(ArtistCredit.spells("Eminem feat. Lil Wayne", listOf("Eminem", "Lil Wayne")))
        assertTrue(ArtistCredit.spells("Fred again.. x Skrillex", listOf("Fred again..", "Skrillex")))
        assertTrue(ArtistCredit.spells("米津玄師、菅田将暉", listOf("米津玄師", "菅田将暉")))
        // A name of YouTube's that holds a joiner itself is found whole.
        assertTrue(ArtistCredit.spells("Earth, Wind & Fire & The Emotions", listOf("Earth, Wind & Fire", "The Emotions")))
    }

    @Test
    fun `names that are not the whole credit never split it`() {
        // A band whose name has a comma and an ampersand is one artist.
        assertFalse(ArtistCredit.spells("Earth, Wind & Fire", listOf("Earth", "Fire")))
        assertFalse(ArtistCredit.spells("Earth, Wind & Fire", listOf("Earth, Wind & Fire")))
        // A word left over is not a joiner: "So" of "So What" is not dropped.
        assertFalse(ArtistCredit.spells("Hoax, So What", listOf("Hoax", "What")))
        assertFalse(ArtistCredit.spells("Hoax, So What, & Dynamite MC", listOf("Hoax", "Dynamite MC")))
        // Out of order, short of the end, or with nothing between two names.
        assertFalse(ArtistCredit.spells("Hoax, So What, & Dynamite MC", listOf("So What", "Hoax", "Dynamite MC")))
        assertFalse(ArtistCredit.spells("Hoax, So What, & Dynamite MC", listOf("Hoax", "So What")))
        assertFalse(ArtistCredit.spells("Hoax So What", listOf("Hoax", "So What")))
        assertFalse(ArtistCredit.spells("HoaxSo What", listOf("Hoax", "So What")))
        assertFalse(ArtistCredit.spells("Hoax, So What", listOf("Hoax", "")))
        assertFalse(ArtistCredit.spells("Hoax", listOf("Hoax")))
    }

    @Test
    fun `only an artist made up here, never a file's, gives way`() {
        assertTrue(ArtistCredit.splits("LAqWgslcqk", "Hoax, So What, & Dynamite MC", false, three))
        assertFalse(ArtistCredit.splits("LAqWgslcqk", "Hoax, So What, & Dynamite MC", true, three))
        assertFalse(ArtistCredit.splits("UC0-C_jjGdhQ4x9pmMSEZLcg", "Hoax, So What, & Dynamite MC", false, three))
        assertFalse(ArtistCredit.splits("LAqWgslcqk", "Hoax", false, three))
    }

    @Test
    fun `an id made up here is LA and eight letters, and no id of YouTube's is`() {
        assertTrue(ArtistCredit.isMadeUp("LAqWgslcqk"))
        assertFalse(ArtistCredit.isMadeUp("UC0-C_jjGdhQ4x9pmMSEZLcg"))
        assertFalse(ArtistCredit.isMadeUp("MPLAUCmMUZbaYdNH0bEd1PAlAqsA"))
        assertFalse(ArtistCredit.isMadeUp("FEmusic_library_privately_owned_artist_detaila_po_1"))
        assertFalse(ArtistCredit.isMadeUp("LAqWgslcq1"))
        assertFalse(ArtistCredit.isMadeUp(null))
    }

    @Test
    fun `the generator still makes ids that read as made up`() {
        val source = File("src/main/java/com/dd3boh/outertune/db/entities/ArtistEntity.kt").readText()
        assertTrue(
            "ArtistEntity.generateArtistId changed: ArtistCredit.isMadeUp must follow it",
            "\"LA\" + RandomStringUtils.insecure().next(8, true, false)" in source,
        )
    }

    @Test
    fun `only a name that may be several is asked about`() {
        assertTrue(ArtistCredit.maybeSeveral("Hoax, So What, & Dynamite MC"))
        assertTrue(ArtistCredit.maybeSeveral("Anyma & Baset"))
        assertTrue(ArtistCredit.maybeSeveral("Simon and Garfunkel"))
        assertTrue(ArtistCredit.maybeSeveral("Fred again.. x Skrillex"))
        assertFalse(ArtistCredit.maybeSeveral("Milk Talk"))
        assertFalse(ArtistCredit.maybeSeveral("So What"))
        assertFalse(ArtistCredit.maybeSeveral("Andy and"))
    }

    @Test
    fun `a file's tag is split as before, with no empty name`() {
        assertEquals(listOf("Alpha", "Beta", "Gamma"), ArtistCredit.fromTag("Alpha, Beta & Gamma"))
        // The serial comma left an empty name between "," and "&" in the MediaStore and FFmpeg readers.
        assertEquals(listOf("Alpha", "Beta", "Gamma"), ArtistCredit.fromTag("Alpha, Beta, & Gamma"))
        assertEquals(listOf("Alpha", "Beta"), ArtistCredit.fromTag("Alpha feat. Beta"))
        assertEquals(listOf("Alpha", "Beta"), ArtistCredit.fromTag(" Alpha; Beta ;"))
        assertEquals(listOf("Alpha"), ArtistCredit.fromTag("Alpha & Alpha"))
        assertEquals(emptyList<String>(), ArtistCredit.fromTag(""))
        assertEquals(emptyList<String>(), ArtistCredit.fromTag(null))
    }

    @Test
    fun `every scanner splits a tag through fromTag`() {
        val scanners = File("src/main/java/com/dd3boh/outertune/utils/scanners")
        for (name in listOf("TagLibScanner.kt", "FFmpegScanner.kt", "LocalMediaScanner.kt")) {
            val code = File(scanners, name).readText()
            assertTrue("$name: no ArtistCredit.fromTag", "ArtistCredit.fromTag(" in code)
            assertFalse("$name: an artist tag split by hand", Regex("""rtist\w*\??\.split\(ARTIST_SEPARATORS\)""").containsMatchIn(code))
        }
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SpeakerEntity
import org.akanework.gramophone.logic.utils.parseTtml
import org.akanework.gramophone.logic.utils.ttmlSingers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * Who sings each line, as the TTML reader keeps it from Better Lyrics' answers (ttmlSingers). The answers are Better
 * Lyrics' own of 23 Nov 2026 (the lyrics measurement's cache), their sung words swapped for invented ones
 * (src/test/resources/lyrics/betterlyrics, made by experiments/agents-1123/lyrics-big/build_fixtures.py): every agent,
 * role, time and tag as it came. Where big lines put each singer is in LyricsBigTest.
 */
class TtmlSingersTest {

    companion object {
        /** A recorded answer's TTML. */
        fun ttmlOf(name: String): String =
            requireNotNull(TtmlSingersTest::class.java.getResourceAsStream("/lyrics/betterlyrics/$name")) { "missing $name" }
                .use { it.readBytes().decodeToString() }
                .let { Json.parseToJsonElement(it).jsonObject.getValue("ttml").jsonPrimitive.content }

        /** A recorded answer read as the app reads it, with the XML reader a JVM test has. */
        fun linesOf(name: String): List<SemanticLyrics.LyricLine> {
            val read = parseTtml(null, ttmlOf(name), KXmlParser())
            assertTrue("$name was not read as timed lines", read is SemanticLyrics.SyncedLyrics)
            return (read as SemanticLyrics.SyncedLyrics).text
        }
    }

    /** How many lines each singer has, background vocals and translations left out. */
    private fun sung(lines: List<SemanticLyrics.LyricLine>) =
        lines.filter { !it.isTranslated && it.speaker?.isBackground != true }.groupingBy { it.speaker }.eachCount()

    @Test
    fun `a duet of two people is two voices`() {
        // The answer's 40 lines of v1 and 7 of v2.
        assertEquals(mapOf(SpeakerEntity.Voice1 to 40, SpeakerEntity.Voice2 to 7), sung(linesOf("somebody-v1-v2.json")))
    }

    @Test
    fun `lines sung together are the group's`() {
        // A singer named on a single word (a span) is not the line's: the paragraph's agent is.
        assertEquals(
            mapOf(SpeakerEntity.Voice1 to 32, SpeakerEntity.Voice2 to 10, SpeakerEntity.Group to 16),
            sung(linesOf("closer-v1-v2-group.json")),
        )
        // A second agent declared a group sings with the first.
        assertEquals(mapOf(SpeakerEntity.Voice1 to 23, SpeakerEntity.Group to 13), sung(linesOf("go-your-own-way-v2-group.json")))
    }

    @Test
    fun `a singer declared of type other is the second voice, no longer the first one's`() {
        // v1 and v2000 ("other"): it was read as voice 1 too, so the song read as a solo.
        val lines = linesOf("despecha-v2000-other.json")
        assertEquals(mapOf(SpeakerEntity.Voice1 to 49, SpeakerEntity.Voice2 to 7), sung(lines))
        // Its translation (en-US) is read, a line under each line, with that line's singer.
        val translated = lines.indices.filter { lines[it].isTranslated }
        assertEquals(56, translated.size)
        for (i in translated) assertEquals("translation $i", lines[i - 1].speaker, lines[i].speaker)
    }

    @Test
    fun `background vocals are their singer's`() {
        val lines = linesOf("happy-v1-v2-bg.json")
        val background = lines.filter { it.speaker?.isBackground == true }
        assertEquals(29, background.size)
        assertTrue(background.any { it.speaker == SpeakerEntity.Background })
        assertTrue(background.all { it.speaker == SpeakerEntity.Background || it.speaker == SpeakerEntity.Voice2Background })
    }

    @Test
    fun `singers take turns in the order they are declared, then in the order they first sing`() {
        val persons = listOf("v1" to "person", "v2" to "person", "v1000" to "group", "v2000" to "other")
        // Apple's way: two people, a group, and another voice that sings too.
        assertEquals(
            mapOf("v1" to SpeakerEntity.Voice1, "v2" to SpeakerEntity.Voice2, "v1000" to SpeakerEntity.Group, "v2000" to SpeakerEntity.Voice1),
            ttmlSingers(persons, listOf("v1", "v2", "v1000", "v2000")),
        )
        // A voice declared that nobody sings takes no turn: Composer declares v2 in every document.
        assertEquals(
            mapOf("v1" to SpeakerEntity.Voice1, "v2000" to SpeakerEntity.Voice2, "v1000" to SpeakerEntity.Group),
            ttmlSingers(persons, listOf("v1", "v2000", "v1")),
        )
        // Agents the head never declared come after the declared ones, as they first sing; none throws.
        assertEquals(
            mapOf("v1" to SpeakerEntity.Voice1, "b" to SpeakerEntity.Voice2, "a" to SpeakerEntity.Voice1),
            ttmlSingers(listOf("v1" to "person"), listOf("b", "v1", "a", "b")),
        )
        // An agent with no id is nobody's, and a line with none is the first voice's (the reader's default).
        assertEquals(mapOf("v1" to SpeakerEntity.Voice1), ttmlSingers(listOf(null to "person", "v1" to "person"), listOf(null, "v1")))
    }

    @Test
    fun `a line that names an agent the head never declared is read, where it lost the whole lyrics`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><head><metadata>
            <ttm:agent type="person" xml:id="v1"/></metadata></head><body><div>
            <p begin="00:00:01.000" end="00:00:02.000" ttm:agent="v1">Paper boats</p>
            <p begin="00:00:02.000" end="00:00:03.000" ttm:agent="v2">Lantern river</p>
            </div></body></tt>
        """.trimIndent()
        val read = parseTtml(null, ttml, KXmlParser()) as SemanticLyrics.SyncedLyrics
        assertEquals(listOf(SpeakerEntity.Voice1, SpeakerEntity.Voice2), read.text.map { it.speaker })
    }
}

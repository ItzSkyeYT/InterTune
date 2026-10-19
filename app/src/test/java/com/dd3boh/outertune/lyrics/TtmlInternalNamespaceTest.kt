/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.isLyricTtmlInternal
import org.akanework.gramophone.logic.utils.parseTtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

/**
 * Better Lyrics answers in lrc.red's copy of Apple's lyrics TTML, whose times are minutes and seconds.
 * Read with the standard's rules none of them was a time, the parse failed and the markup itself was
 * shown as the lyrics (19 Oct 2026). The song here is made up; the shape is the one Better Lyrics sent.
 */
class TtmlInternalNamespaceTest {

    private fun ttml(namespace: String) = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="$namespace" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="Line" xml:lang="en"><head><metadata><ttm:agent type="person" xml:id="v1"><ttm:name type="full">Someone</ttm:name></ttm:agent><sourceMetadata xmlns="$namespace" leadingSilence="0.000"><translations/><songwriters><songwriter>Someone Else</songwriter></songwriters></sourceMetadata></metadata></head><body dur="02:57.479"><div begin="00:18.055" end="00:32.381" lrc:songPart="Verse"><p begin="00:18.055" end="00:19.517" lrc:key="L1" ttm:agent="v1">First made-up line</p><p begin="00:25.9" end="00:28.18" lrc:key="L2" ttm:agent="v1">Second made-up line</p></div><div begin="01:02.5" end="01:10" lrc:songPart="Chorus"><p begin="01:02.5" end="01:10" lrc:key="L3" ttm:agent="v1">Third made-up line</p></div></body></tt>
    """.trimIndent()

    private fun lines(namespace: String): List<SemanticLyrics.LyricLine> {
        val parsed = parseTtml(null, ttml(namespace), KXmlParser())
        assertNotNull("the TTML was not read", parsed)
        return (parsed as SemanticLyrics.SyncedLyrics).text.filter { it.text.isNotBlank() }
    }

    @Test
    fun `lrc dot red's namespace is read like Apple's, minutes and seconds included`() {
        val lines = lines("http://lrc.red/lyric-ttml-internal")
        assertEquals(listOf("First made-up line", "Second made-up line", "Third made-up line"), lines.map { it.text })
        assertEquals(listOf(18_055uL, 25_900uL, 62_500uL), lines.map { it.start })
    }

    @Test
    fun `Apple's own namespace still reads the same`() {
        val lines = lines("http://music.apple.com/lyric-ttml-internal")
        assertEquals(listOf(18_055uL, 25_900uL, 62_500uL), lines.map { it.start })
    }

    @Test
    fun `only the internal namespaces are taken for Apple's`() {
        assertTrue(isLyricTtmlInternal("http://music.apple.com/lyric-ttml-internal"))
        assertTrue(isLyricTtmlInternal("http://lrc.red/lyric-ttml-internal"))
        assertFalse(isLyricTtmlInternal("http://www.w3.org/ns/ttml"))
        assertFalse(isLyricTtmlInternal(null))
    }

    @Test
    fun `lyrics TTML that cannot be read is never read as LRC`() {
        val helper = File("src/main/java/com/dd3boh/outertune/lyrics/LyricsHelper.kt").readText()
        val parse = helper.substringAfter("private fun parse(").substringBefore("\n    }\n")
        assertTrue(parse.contains("if (LyricsMatch.isTtml(text)) return readTtml(text)"))
        assertTrue(helper.contains("readable = { !LyricsMatch.isTtml(it) || readTtml(it) != null }"))
    }
}

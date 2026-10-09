/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every three-dot button has a name for a screen reader.
 *
 * It is the menu of a song, an album, an artist, a playlist: on most lists the only way to it,
 * since a long press there selects. Twenty of the app's twenty-two were drawn with no
 * description on 9 Oct 2026, so each row of every list ended in a button with nothing to say.
 * This reads the source, as PlayOriginCoverageTest does: a new list copied from an old one
 * brings its button along, name or no name.
 */
class MenuButtonsNamedTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The three dots, and whatever is said of them in the lines that draw them. */
    private val threeDots = Regex("""Icons\.Rounded\.MoreVert,\s*(?:\n\s*)?(?:tint = [^\n]+,\s*\n\s*)?contentDescription = (\S[^\n,)]*)""")

    @Test
    fun `no three-dot button is drawn without a name`() {
        val unnamed = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            threeDots.findAll(text).filter { it.groupValues[1] == "null" }
                .map { "${file.relativeTo(sources)}:${text.substring(0, it.range.first).count { c -> c == '\n' } + 1}" }
        }.toList()

        assertEquals("three-dot buttons with nothing for a screen reader to say:\n" + unnamed.joinToString("\n"), emptyList<String>(), unnamed)
    }

    @Test
    fun `the search finds the buttons it is there for`() {
        val found = sources.walkTopDown().filter { it.extension == "kt" }.sumOf { threeDots.findAll(it.readText()).count() }
        assertTrue("only $found three-dot buttons found: has the way they are written changed?", found >= 20)
        assertEquals("null", threeDots.find("Icon(Icons.Rounded.MoreVert, contentDescription = null)")!!.groupValues[1])
        assertEquals("null", threeDots.find("Icons.Rounded.MoreVert,\n                            contentDescription = null\n")!!.groupValues[1])
    }
}

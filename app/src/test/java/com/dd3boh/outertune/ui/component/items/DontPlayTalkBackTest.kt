/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.items

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song set to Don't play this song was only drawn at half strength, so TalkBack read it as any other song, and then
 * it was skipped with nothing said why (the 0.13 accessibility read, 18 Nov 2026). While it is dimmed, its row or tile
 * now has a state TalkBack says with its title. A state only: nothing on screen moves (DontPlayGreyTest).
 */
class DontPlayTalkBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The source of [path] with its comments taken out. */
    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    private val items get() = source("ui/component/items/Items.kt")

    @Test
    fun `any other song gets nothing, a song not to play gets its state and only that`() {
        assertSame(Modifier, Modifier.dontPlaySaid(null))
        assertNotSame(Modifier, Modifier.dontPlaySaid("Plays only when you pick it"))
        assertTrue("fun Modifier.dontPlaySaid(said: String?): Modifier = if (said != null) semantics { stateDescription = said } else this" in items)
    }

    @Test
    fun `a list row says it on its own node, the one its tap is on, exactly while it is dimmed`() {
        val basic = items.let { text ->
            val start = text.indexOf("inline fun ListItem(")
            text.substring(start, text.indexOf("\nfun ListItem(", start))
        }
        assertTrue(basic, "val offSaid = if (switchedOff) stringResource(R.string.dont_play_state) else null" in basic)
        // on all three looks of the row (playing, selected, plain), after its own modifier, which carries the tap
        assertTrue(basic, "modifier = (if (isActive) {" in basic)
        assertTrue(basic, "                .padding(horizontal = 8.dp)\n        }).dontPlaySaid(offSaid)\n    ) {" in basic)
        assertEquals(1, Regex("""dontPlaySaid\(""").findAll(basic).count())
        assertEquals("the dimming as it was", 2, Regex("""dontPlayDim\(""").findAll(basic).count())
    }

    @Test
    fun `a tile says it on itself, with its dimming`() {
        val start = items.indexOf("fun GridItem(\n    modifier: Modifier = Modifier,\n    title: @Composable () -> Unit,")
        assertTrue(start >= 0)
        val tile = items.substring(start, items.indexOf("\n@Composable", start))
        assertTrue(tile, "val offSaid = if (isDontPlay) stringResource(R.string.dont_play_state) else null" in tile)
        assertTrue(tile, "}.dontPlayDim(isDontPlay).dontPlaySaid(offSaid)" in tile)
    }

    @Test
    fun `the words are short, in English and French, held from translators for now`() {
        val en = File("src/main/res/values/strings-ot.xml").readLines().single { "name=\"dont_play_state\"" in it }
        val fr = File("src/main/res/values-fr/strings-ot.xml").readLines().single { "name=\"dont_play_state\"" in it }
        for (line in listOf(en, fr)) {
            assertTrue(line, "translatable=\"false\"" in line)
            val words = line.substringAfter(">").substringBeforeLast("<")
            assertTrue(line, words.split(' ').size <= 6)
        }
        assertTrue(en, ">Plays only when you pick it<" in en)
    }
}

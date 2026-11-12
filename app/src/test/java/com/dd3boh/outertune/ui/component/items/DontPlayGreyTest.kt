/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.items

import androidx.compose.ui.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song the listener chose not to play (DontPlay, #78) shows switched off wherever it is listed:
 * its picture, title and line at half strength, as a setting that is off does. It stays in its
 * place, tappable and played when picked, and nothing in the row moves. Read from the source, as
 * RowsAskLessTest does: the rows are Compose, and the set they ask is the one DontPlayTest covers.
 */
class DontPlayGreyTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The source of [path] with its comments taken out. */
    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    private val items get() = source("ui/component/items/Items.kt")
    private val songs get() = source("ui/component/items/SongItems.kt")

    /** The text of the declaration that starts with [header], up to the next one that opens a line with @Composable. */
    private fun declaration(text: String, header: String): String {
        val start = text.indexOf(header)
        assertTrue("no $header", start >= 0)
        val end = text.indexOf("\n@Composable", start).takeIf { it > 0 } ?: text.length
        return text.substring(start, end)
    }

    @Test
    fun `it shows at half strength, the strength a setting that is off has`() {
        assertEquals(0.5f, DontPlayAlpha)
        assertTrue(".alpha(if (isEnabled) 1f else 0.5f)" in source("ui/component/Preference.kt"))
    }

    @Test
    fun `a row of any other song gets no layer, and the dimming is a layer and nothing else`() {
        assertSame(Modifier, Modifier.dontPlayDim(false))
        assertNotSame(Modifier, Modifier.dontPlayDim(true))
        // No padding, size or offset in it: nothing in the row can move.
        assertTrue("fun Modifier.dontPlayDim(on: Boolean): Modifier = if (on) alpha(DontPlayAlpha) else this" in items)
    }

    @Test
    fun `a list row dims its picture and its title with the line under it, never the row, so the buttons, the highlight and the tap stay as they are`() {
        val basic = items.let { text ->
            val start = text.indexOf("inline fun ListItem(")
            text.substring(start, text.indexOf("\nfun ListItem(", start))
        }
        // Only a song waiting in the list: the one playing, picked by hand, is on.
        assertTrue("val switchedOff = isDontPlay && !isActive" in basic)
        assertTrue("Modifier.padding(6.dp).dontPlayDim(switchedOff)" in basic)
        assertTrue(".padding(horizontal = 6.dp)\n                .dontPlayDim(switchedOff)" in basic)
        assertEquals("the picture and the title's column, no more", 2, Regex("""dontPlayDim\(""").findAll(basic).count())
        // The row's own modifier carries the click, the highlight and the height: none of it is dimmed.
        assertFalse(basic.substringBefore("Box(").contains("dontPlayDim"))
        // What comes after the column, the menu, the checkbox and the drag handle, is outside it.
        assertTrue(basic.indexOf("trailingContent()") > basic.lastIndexOf("dontPlayDim("))
    }

    @Test
    fun `a tile dims whole, and the wrappers pass it on`() {
        val tile = declaration(items, "fun GridItem(\n    modifier: Modifier = Modifier,\n    title: @Composable () -> Unit,")
        assertTrue("isDontPlay: Boolean = false" in tile)
        assertTrue("}.dontPlayDim(isDontPlay)" in tile)
        val named = declaration(items, "fun GridItem(\n    modifier: Modifier = Modifier,\n    title: String,")
        assertTrue("isDontPlay = isDontPlay" in named)
        val badges = declaration(items, "\nfun ListItem(")
        assertTrue("isDontPlay = isDontPlay" in badges)
    }

    @Test
    fun `every row that lists a song asks whether it is one not to play, and a song playing is not dimmed in a tile either`() {
        val asking = listOf(
            items to "fun MediaMetadataListItem(",
            items to "fun YouTubeListItem(",
            items to "fun YouTubeGridItem(",
            songs to "fun SongListItem(",
            songs to "fun SongGridItem(",
        )
        for ((text, header) in asking) {
            val row = declaration(text, header)
            assertTrue("$header does not ask", "rememberIsDontPlay(" in row)
            assertTrue("$header does not pass it on", "isDontPlay = " in row)
        }
        // A YouTube row asks for songs only: an album or a playlist is not a song.
        assertTrue("item is SongItem && rememberIsDontPlay(" in declaration(items, "fun YouTubeListItem("))
        assertTrue("item is SongItem && !isActive && rememberIsDontPlay(" in declaration(items, "fun YouTubeGridItem("))
        assertTrue("!isActive && rememberIsDontPlay(" in declaration(songs, "fun SongGridItem("))
        // The list rows leave "playing" to the row itself (switchedOff), so they pass it as it is.
        assertFalse("!isActive" in declaration(songs, "fun SongListItem("))
    }

    @Test
    fun `the set is followed once for the whole app and a row only asks it, with no query of its own`() {
        val main = source("MainActivity.kt")
        assertEquals(1, Regex("""rememberDontPlay\(\)""").findAll(main).count())
        assertTrue("LocalDontPlay provides dontPlay" in main)
        assertTrue("val LocalDontPlay = compositionLocalOf { DontPlay.NONE }" in main)
        val helper = declaration(items, "fun rememberIsDontPlay(")
        assertTrue("LocalDontPlay.current" in helper)
        assertTrue("remember(dontPlay, id, title, artist) { dontPlay.covers(id, title, artist) }" in helper)
        for (text in listOf(items, songs)) {
            assertFalse("a row follows the set itself", "rememberDontPlay(" in text || "ContentFilter" in text)
            assertFalse("a row asks the database", Regex("""database\.\w*(?i:dontPlay)""").containsMatchIn(text))
        }
    }

    @Test
    fun `the queue's rows are the shared ones, so a song waiting in the queue is dimmed too`() {
        assertTrue("MediaMetadataListItem(" in source("ui/player/Queue.kt"))
        assertTrue("MediaMetadataListItem(" in source("ui/player/QueueHeldRow.kt"))
    }

    @Test
    fun `the What's new card says it stays listed, greyed out and skipped, in three sentences, and no line says it will not appear`() {
        val en = File("src/main/res/values/strings-ot.xml").readLines()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readLines()
        val card = en.single { "name=\"new_dont_play_body\"" in it }
        assertTrue("held from translators", "translatable=\"false\"" in card)
        assertTrue("it stays listed, greyed out, and is skipped" in card)
        val body = card.substringAfter(">").substringBeforeLast("<")
        assertTrue(body, body.split(Regex("""(?<=\.)\s""")).size <= 3)
        val french = fr.single { "name=\"new_dont_play_body\"" in it }
        assertTrue("grisé" in french)
        assertTrue("il est passé" in french)
        val wontAppear = Regex("""won.t (appear|show|be (in|listed))|n.apparaît|n.apparaîtra|ne s.affiche""", RegexOption.IGNORE_CASE)
        for (line in (en + fr).filter { "dont_play" in it }) {
            assertFalse(line, wontAppear.containsMatchIn(line))
        }
    }
}

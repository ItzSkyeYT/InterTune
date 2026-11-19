/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Custom order in the library (Library, Playlists, Albums, Artists) could only be changed by a long drag with a finger,
 * and TalkBack stopped on every row's handle, an unnamed button that did nothing (the 0.13 accessibility read, 18 Nov
 * 2026). Now each row or tile that can be dragged has Move up and Move down, the same move a drag makes and kept where
 * a drag's drop is, and the handles are no stop of their own. Read from the source, as the other order tests do: the
 * rows are Compose.
 */
class LibraryOrderTalkBackTest {

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val library get() = code("ui/component/Library.kt")

    /** The text of the declaration that starts with [header], up to the next one that opens a line with @Composable. */
    private fun declaration(text: String, header: String): String {
        val start = text.indexOf(header)
        assertTrue("no $header", start >= 0)
        val end = text.indexOf("\n@Composable", start).takeIf { it > 0 } ?: text.length
        return text.substring(start, end)
    }

    @Test
    fun `the first has no Move up, the last no Move down, and a row not listed has neither`() {
        val steps = mutableListOf<Int>()
        val first = OrderMoves.of(0, 3) { steps += it }
        assertNull(first.up)
        assertNotNull(first.down)
        val middle = OrderMoves.of(1, 3) { steps += it }
        middle.up!!()
        middle.down!!()
        first.down!!()
        assertEquals("one place each way", listOf(-1, 1, 1), steps)
        val last = OrderMoves.of(2, 3) { steps += it }
        assertNotNull(last.up)
        assertNull(last.down)
        val alone = OrderMoves.of(0, 1) { }
        assertNull(alone.up)
        assertNull(alone.down)
        val gone = OrderMoves.of(-1, 3) { }
        assertNull(gone.up)
        assertNull(gone.down)
    }

    @Test
    fun `the handles are no stop of their own and nothing about them moves`() {
        val row = declaration(library, "private fun RowDragHandle(")
        assertTrue(row, "modifier = Modifier\n            .clearAndSetSemantics { }\n            .then(dragHandle)" in row)
        // still the same button, so the handle keeps its size, its place and its ripple
        assertTrue(row, "IconButton(\n        onClick = { }," in row)
        assertFalse("an unnamed button again", "modifier = dragHandle\n" in row)
        val tile = declaration(library, "private fun TileWithDragHandle(")
        assertTrue(tile, "CircleShape)\n                .clearAndSetSemantics { }\n                .then(dragHandle)" in tile)
        assertTrue(tile, ".size(36.dp)" in tile && ".padding(14.dp)" in tile)
    }

    @Test
    fun `every row and tile of the library takes the moves where TalkBack stops on it, by its tap`() {
        for (name in listOf("LibraryArtistListItem", "LibraryAlbumListItem", "LibraryPlaylistListItem")) {
            val item = library.substringAfter("fun $name(").substringBefore("\n)\n")
            assertTrue(name, "dragHandle: Modifier? = null,\n    // With it, TalkBack's way to move the row: Move up and Move down.\n    moves: OrderMoves? = null," in item)
            assertTrue(name, "modifier = modifier\n        .orderMoves(moves)\n        .fillMaxWidth()\n        .clickable {" in item)
        }
        for (name in listOf("LibraryArtistGridItem", "LibraryAlbumGridItem", "LibraryPlaylistGridItem")) {
            val item = library.substringAfter("fun $name(")
            assertTrue(name, "moves: OrderMoves? = null," in item.substringBefore("\n)"))
            assertTrue(name, "TileWithDragHandle(modifier, dragHandle, moves) { tile ->" in item.substringBefore("\n}\n"))
        }
        // the tile under the handle is the one tapped, so the moves go on it, not on the box around both
        assertTrue("tile(Modifier.orderMoves(moves))" in declaration(library, "private fun TileWithDragHandle("))
        val actions = library.substringAfter("private fun Modifier.orderMoves(")
        assertTrue(actions, "stringResource(R.string.order_move_up)" in actions && "stringResource(R.string.order_move_down)" in actions)
        assertTrue(actions, "moves.up?.let { move -> CustomAccessibilityAction(up) { move(); true } }" in actions)
        assertTrue(actions, "moves.down?.let { move -> CustomAccessibilityAction(down) { move(); true } }" in actions)
    }

    @Test
    fun `each page moves a row as a drag does, and keeps it in the one place a drag's drop is kept`() {
        val pages = mapOf(
            "ui/screens/library/LibraryAlbumsScreen.kt" to "albums",
            "ui/screens/library/LibraryArtistsScreen.kt" to "artists",
            "ui/screens/library/LibraryPlaylistsScreen.kt" to "playlists",
        )
        for ((file, listed) in pages) {
            val screen = code(file)
            assertEquals("$file: one save, shared by the drag and the moves", 1, Regex("""saveCustomOrder\(""").findAll(screen).count())
            assertTrue(file, "if (keepShown(ids)) return@LaunchedEffect" in screen)
            assertTrue(file, "fun keepShown(kept: List<String>): Boolean {" in screen)
            assertTrue(file, "if (!customOrder || shown == kept || shown.toSet() != kept.toSet()) return false" in screen)
            assertTrue(file, "moveRow(id, dragged[to].id)" in screen)
            assertTrue(file, "keepShown(droppedOrder ?: $listed.orEmpty().map { it.id })" in screen)
            val handles = Regex("""dragHandle = Modifier\.draggableHandle\(\),\n\s+moves = movesOf\(\w+\.id\),""").findAll(screen).count()
            assertEquals("$file: every handle with its moves", 2, handles)
            assertEquals(file, 2, Regex("""draggableHandle\(\)""").findAll(screen).count())
        }
        val all = code("ui/screens/library/LibraryScreen.kt")
        assertEquals(1, Regex("""saveCustomOrder\(""").findAll(all).count())
        assertTrue("if (keepShown(keys)) return@LaunchedEffect" in all)
        assertTrue("moveItem(key, libraryKey(dragged[to]))" in all)
        assertTrue("keepShown(droppedOrder ?: allItems.map(::libraryKey))" in all)
        assertTrue("libraryRow(item, Modifier, Modifier.draggableHandle(), movesOf(libraryKey(item)))" in all)
        assertTrue("libraryTile(item, Modifier, Modifier.draggableHandle(), movesOf(libraryKey(item)))" in all)
        assertEquals(6, Regex("""dragHandle = dragHandle,\n\s+moves = moves,""").findAll(all).count())
    }

    @Test
    fun `Move up and Move down are written in English and French, and held from translators for now`() {
        for (file in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val lines = File(file).readLines()
            for (name in listOf("order_move_up", "order_move_down")) {
                val line = lines.single { "name=\"$name\"" in it }
                assertTrue(line, "translatable=\"false\"" in line)
            }
        }
    }
}

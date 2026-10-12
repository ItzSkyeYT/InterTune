/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The buttons at the head of a page of songs have a name for a screen reader.
 *
 * A playlist, an album and an artist each start with round buttons that are a picture and nothing
 * else: a pencil, a download arrow, a heart, the queue. On 12 Oct 2026 twenty-seven of them were
 * drawn with no description, the lock beside the sort order and two in the selection bar among
 * them, so each was a button with nothing to say. This reads the source, as MenuButtonsNamedTest
 * does: a button that loses its name still compiles, and still works for everybody who can see it.
 */
class HeaderButtonsNamedTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The pages of a playlist, an album and an artist, and the bars they share: on top, to sort, to select. */
    private val files: List<File> =
        listOf("ui/screens/playlist", "ui/screens/artist").flatMap { folder -> File(sources, folder).listFiles().orEmpty().filter { it.extension == "kt" }.sortedBy { it.name } } +
            listOf("ui/screens/AlbumScreen.kt", "ui/component/FloatingTopBar.kt", "ui/component/SortHeader.kt", "ui/component/SelectHeader.kt").map { File(sources, it) }

    private fun page(name: String) = files.single { it.name == name }

    /** A call as it is written: where it starts, what stands between its brackets, and the block after them. */
    private class Call(val at: Int, val arguments: String, val block: String)

    /** A round button that is a picture: Material's, the app's own, the small one beside a sort order. */
    private val iconButton = Regex("""(?<!\w)\w*Icon(?:Toggle)?Button\(""")
    private val picture = Regex("""(?<![\w.])(?:Icon|Image)\(""")

    /** A name given to the button itself, whatever it shows. */
    private val onTheButton = Regex("""\.named\(|[sS]emantics\s*\{[^}]*contentDescription\s*=""")

    /** A row's own button, the three dots mostly, is not the page's: MenuButtonsNamedTest has those. */
    private val rowsOwn = Regex("""trailingContent\s*=\s*\{""")

    /**
     * The source with its comments and the words inside its quotes blanked, each character for a
     * space, so that a bracket in a sentence is not taken for one of the code's and every place
     * keeps its line.
     */
    private fun code(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val end = when {
                text.startsWith("//", i) -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                text.startsWith("\"\"\"", i) -> text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                    j + 1
                }
                text[i] == '\'' && text.getOrNull(i + 1) == '\\' && text.getOrNull(i + 3) == '\'' -> i + 4
                text[i] == '\'' && text.getOrNull(i + 2) == '\'' -> i + 3
                else -> i
            }.coerceAtMost(text.length)
            if (end == i) {
                i++
                continue
            }
            // The quotes stay, so that words in quotes still read as something said.
            val quoted = text[i] == '"' || text[i] == '\''
            for (k in (if (quoted) i + 1 else i) until (if (quoted) end - 1 else end)) if (out[k] != '\n') out[k] = ' '
            i = end
        }
        return out.toString()
    }

    /** Where the bracket opened at [open] is closed. */
    private fun String.closing(open: Int): Int {
        var depth = 0
        for (i in open until length) when (this[i]) {
            '(', '{', '[' -> depth++
            ')', '}', ']' -> if (--depth == 0) return i
        }
        error("the bracket at $open is never closed")
    }

    /** Every call of [name] in this code, a declaration aside. */
    private fun String.calls(name: Regex): List<Call> = name.findAll(this).mapNotNull { found ->
        val line = substring(lastIndexOf('\n', found.range.first) + 1, found.range.first)
        if (Regex("""\bfun\s+(?:[\w.<>]+\.)?$""").containsMatchIn(line)) return@mapNotNull null
        val close = closing(found.range.last)
        val after = (close + 1 until length).firstOrNull { this[it] != ' ' && this[it] != '\t' }
        val block = if (after != null && this[after] == '{') substring(after, closing(after) + 1) else ""
        Call(found.range.first, substring(found.range.last + 1, close), block)
    }.toList()

    /** What stands between a call's brackets, cut at the commas of its own level. */
    private fun String.atCommas(): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        forEachIndexed { i, c ->
            when (c) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    parts += substring(start, i)
                    start = i + 1
                }
            }
        }
        return (parts + substring(start)).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** What a picture's call says of it: contentDescription by name, or the second in line, after the picture. */
    private fun Call.said(): String? {
        val each = arguments.atCommas()
        each.firstNotNullOfOrNull { Regex("""^contentDescription\s*=\s*(.+)$""", RegexOption.DOT_MATCHES_ALL).find(it) }?.let { return it.groupValues[1].trim() }
        val inLine = each.filterNot { Regex("""^\w+\s*=[^=]""").containsMatchIn(it) }
        val pictureByName = each.any { Regex("""^(?:imageVector|painter|bitmap)\s*=""").containsMatchIn(it) }
        return inLine.getOrNull(if (pictureByName) 0 else 1)
    }

    /**
     * Whether a screen reader has something to say for the button: a name on the button itself,
     * or one on every picture it can show. A turning wheel has no words of its own, so a button
     * that can show one is named on the button.
     */
    private fun Call.isNamed(): Boolean {
        if (onTheButton.containsMatchIn(arguments)) return true
        if ("CircularProgressIndicator(" in block) return false
        val pictures = block.calls(picture).map { it.said() }
        return pictures.isNotEmpty() && pictures.none { it == null || it == "null" || it == "\"\"" }
    }

    /** The picture buttons of a page that are not a row's own. */
    private fun headerButtons(code: String): List<Call> {
        val rows = rowsOwn.findAll(code).map { it.range.first..code.closing(it.range.last) }.toList()
        return code.calls(iconButton).filter { button -> rows.none { button.at in it } }
    }

    private fun headerButtons(file: File) = headerButtons(code(file.readText()))

    @Test
    fun `no button at the head of these pages is drawn without a name`() {
        val unnamed = files.flatMap { file ->
            val code = code(file.readText())
            headerButtons(code).filterNot { it.isNamed() }
                .map { "${file.relativeTo(sources)}:${code.substring(0, it.at).count { c -> c == '\n' } + 1}" }
        }

        assertEquals("buttons with nothing for a screen reader to say:\n" + unnamed.joinToString("\n"), emptyList<String>(), unnamed)
    }

    @Test
    fun `the search finds the buttons it is there for`() {
        val found = files.associate { it.name to headerButtons(it).size }
        assertTrue("only ${found.values.sum()} buttons found: has the way they are written changed?", found.values.sum() >= 30)
        for (name in listOf("LocalPlaylistScreen.kt", "OnlinePlaylistScreen.kt", "AutoPlaylistScreen.kt", "AlbumScreen.kt", "ArtistScreen.kt", "SelectHeader.kt")) {
            assertTrue("$name: only ${found[name]} buttons found", found.getValue(name) >= 2)
        }
    }

    @Test
    fun `a nameless button is told from a named one`() {
        fun named(source: String) = headerButtons(code(source)).single().isNamed()

        assertFalse(named("IconButton(onClick = { }) { Icon(Icons.Rounded.Edit, contentDescription = null) }"))
        assertFalse(named("IconButton(\n    onClick = { }\n) {\n    Icon(\n        Icons.Rounded.Edit,\n        null\n    )\n}"))
        assertFalse(named("IconButton(onClick = { }) { Icon(painter = painterResource(R.drawable.favorite), contentDescription = null, tint = red) }"))
        assertTrue(named("IconButton(onClick = { }) { Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit)) }"))
        assertTrue(named("IconButton(onClick = { }) { Icon(Icons.Rounded.Edit, stringResource(R.string.edit)) }"))
        assertTrue(named("IconButton(onClick = { }) { Icon(imageVector = Icons.Rounded.Edit, tint = red, contentDescription = likeName(liked)) }"))
        assertTrue(named("IconButton(onClick = { }) { Icon(Icons.Rounded.Edit, contentDescription = \"Edit\") }"))
        assertFalse(named("IconButton(onClick = { }) { Icon(Icons.Rounded.Edit, contentDescription = \"\") }"))

        // A wheel says nothing, alone or taking a picture's place.
        assertFalse(named("IconButton(onClick = { }) { CircularProgressIndicator() }"))
        assertFalse(named("IconButton(onClick = { }) { if (busy) CircularProgressIndicator() else Icon(Icons.Rounded.Sync, contentDescription = name) }"))
        assertTrue(named("IconButton(onClick = { }, modifier = Modifier.named(name)) { CircularProgressIndicator() }"))

        // The small button beside a sort order is one call with no block.
        assertFalse(named("ResizableIconButton(icon = Icons.Rounded.Close, onClick = { })"))
        assertTrue(named("ResizableIconButton(icon = Icons.Rounded.Close, modifier = Modifier.semantics { contentDescription = name }, onClick = { })"))

        // Brackets and names in quotes and in comments are not code.
        assertFalse(named("IconButton(onClick = { say(\"} )\") /* } */ }) { // contentDescription = name }\n    Icon(Icons.Rounded.Edit, contentDescription = null)\n}"))

        // A row's own button is left to the test of the three dots, and a declaration is no button.
        assertTrue(headerButtons(code("Item(trailingContent = { IconButton(onClick = { }) { Icon(Icons.Rounded.Add, contentDescription = null) } })")).isEmpty())
        assertTrue(headerButtons(code("@Composable\nfun IconButton(onClick: () -> Unit) { }")).isEmpty())
    }

    /** The one button that [which] picks out, and a failure that says [what] was looked for when there is not exactly one. */
    private fun List<Call>.one(what: String, which: (Call) -> Boolean): Call =
        filter(which).also { assertEquals("$what: has the way it is written changed?", 1, it.size) }.single()

    @Test
    fun `the download button says what a tap does, in each of its three states`() {
        for (name in listOf("LocalPlaylistScreen.kt", "OnlinePlaylistScreen.kt", "AutoPlaylistScreen.kt", "AlbumScreen.kt")) {
            val buttons = headerButtons(page(name))
            // All there: a tap takes the downloads away.
            val done = buttons.one("$name, the tick of a finished download") { "Icons.Rounded.OfflinePin" in it.block }
            assertTrue(name, "R.string.remove_download" in done.block)
            // On its way: a tap on the wheel takes away what has come and what was still to come.
            val onItsWay = buttons.one("$name, the wheel of a download on its way") { "removeDownload" in it.arguments && "CircularProgressIndicator(" in it.block }
            assertTrue(name, ".named(stringResource(R.string.cancel_download))" in onItsWay.arguments)
            val notYet = buttons.one("$name, the arrow that downloads") { Regex("""Icons\.Rounded\.Download\b""").containsMatchIn(it.block) }
            assertTrue(name, "R.string.action_download" in notYet.block)
        }
    }

    @Test
    fun `the lock says whether the order is locked, a heart what a tap does, and the selection bar which way it goes`() {
        val lock = headerButtons(page("LocalPlaylistScreen.kt")).one("the lock beside a playlist's sort order") { "Icons.Rounded.LockOpen" in it.block }
        assertTrue("stringResource(if (locked) R.string.playlist_order_locked else R.string.playlist_order_unlocked)" in lock.block)

        // An album's, an online playlist's and an artist's.
        val hearts = files.flatMap { headerButtons(it) }.filter { "R.drawable.favorite_border" in it.block || "Icons.Rounded.FavoriteBorder" in it.block }
        assertTrue("only ${hearts.size} hearts found: has the way they are written changed?", hearts.size >= 3)
        for (heart in hearts) assertTrue(heart.block, "contentDescription = likeName(" in heart.block)

        val all = headerButtons(page("SelectHeader.kt")).one("the selection bar's button for all or none") { "Icons.Rounded.SelectAll" in it.block }
        assertTrue("stringResource(if (allSelected) R.string.select_all else R.string.deselect_all)" in all.block)
    }

    @Test
    fun `the new names are open to translators and have their French`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("playlist_order_locked", "playlist_order_unlocked", "cancel_download", "playlist_load_all", "select_all", "deselect_all", "cancel_selection")) {
            assertTrue("$name is not open to translators", "<string name=\"$name\">" in english)
            assertTrue("$name has no French", "<string name=\"$name\">" in french)
            // In use, too: a name nothing says is a name a translator works on for nothing.
            assertTrue("$name is said by no button", files.any { "R.string.$name" in it.readText() })
        }
    }
}

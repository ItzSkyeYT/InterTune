/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Folders tab in the app's own style (his pick of 15 Nov 2026, option C of three drawings of
 * his own screen): the app's search bar on top, Local scanner as a chip like the Library's with the
 * tree and list switch at the end of its row, and folders with a tinted folder icon. Nothing goes:
 * the tab's own search (it filters the songs under the open folder) stays, as an icon beside the
 * switch, and the sort row is not touched (FolderOrderTest holds it).
 */
class FoldersTabTidyTest {
    private val main = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(main, path).readText()

    private val screen = source("ui/screens/library/FolderScreen.kt")

    /** The row under the app's search bar: from where it is made to where the sort row is. */
    private val tools = screen.substringAfter("val tools = @Composable {").substringBefore("val sort = @Composable {")

    @Test
    fun `the first row of the header is the Library's chips, with Local scanner a chip that opens its settings`() {
        assertTrue("the row is made", tools.isNotBlank() && screen.contains("val tools = @Composable {"))
        assertTrue(tools.contains("ChipsRow("))
        assertTrue(screen.contains("import com.dd3boh.outertune.ui.component.ChipsRow"))
        assertTrue(tools.contains("R.string.scanner_local_title"))
        assertTrue(tools.contains("Icons.Rounded.SdCard"))
        assertTrue(tools.contains("navController.navigate(\"settings/local\")"))
        // The text button with the card beside it, which no other screen drew, is gone from here.
        assertFalse(screen.contains("IconTextButton"))
    }

    @Test
    fun `the chip is made like the Library's chips and its screen reader says button`() {
        val chips = source("ui/component/ChipsRow.kt")
        val acting = chips.substringAfter("data class ActionChip(")
        assertTrue(acting.contains("AssistChip("))
        // The picture before the words, and the same spacing, colours and border as the chips that pick.
        assertTrue(acting.contains("leadingIcon"))
        assertTrue(acting.contains("Spacer(Modifier.width(12.dp))"))
        assertTrue(acting.contains("Spacer(Modifier.width(8.dp))"))
        assertTrue(acting.contains("containerColor = MaterialTheme.colorScheme.surface"))
        assertTrue(acting.contains("FilterChipDefaults.filterChipBorder(enabled = true, selected = false)"))
        // The chips that pick are as they were.
        val picking = chips.substringBefore("data class ActionChip(")
        assertTrue(picking.contains("colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface)"))
        assertEquals(1, Regex("""fun <E> ChipsRow\(""").findAll(chips).count())
        assertEquals(1, Regex("""fun <E> ChipsLazyRow\(""").findAll(chips).count())
    }

    @Test
    fun `the search is still there, an icon at the end of that row that turns the row into the field`() {
        val chips = tools.indexOf("ChipsRow(")
        // (The field has a magnifier in front of its words, before the chips in the file: the row's is after them.)
        val search = tools.indexOf("Icons.Rounded.Search", startIndex = chips)
        val tree = tools.indexOf("onFlatSubfoldersChange(!flatSubfolders)")
        // The end of the row: after the chip, and before the switch, which is the last thing in it.
        assertTrue("the chip, then the search, then the switch", chips in 0 until search && search < tree)
        assertTrue(Regex("""IconButton\(\s*onClick = \{ isSearching = true \}""").containsMatchIn(tools))
        assertTrue(tools.contains("contentDescription = stringResource(R.string.search)"))
        // While searching the row is the field, as it was: no chip and no switch beside it.
        val searching = tools.substringAfter("if (isSearching) {").substringBefore("} else {")
        assertTrue(searching.contains("TextField("))
        assertTrue(searching.contains(".focusRequester(focusRequester)"))
        assertTrue(searching.contains("onValueChange = { query = it }"))
        assertFalse(searching.contains("ChipsRow("))
        assertFalse(searching.contains("onFlatSubfoldersChange"))
        // The words typed still filter the songs below the open folder, a moment after the last key.
        assertTrue(screen.contains("viewModel.searchInDir(query.text)"))
        assertTrue(screen.contains("items = if (isSearching) searchedSongs else mutableSongs,"))
        // The magnifier it had in front of it is in front of the words, and the field can be left: the icon that
        // opened it is not under it any more, so it has a button at its end that closes it and clears the words,
        // as the bar over a folder's page does.
        assertTrue(searching.contains("leadingIcon = {"))
        assertTrue(searching.contains("Icons.Rounded.Search"))
        assertTrue(searching.contains("trailingIcon = {"))
        assertTrue(Regex("""onClick = \{\s*isSearching = false\s*query = TextFieldValue\(\)\s*\}""").containsMatchIn(searching))
    }

    @Test
    fun `the tree and list switch ends the row like the Library's grid switch, with a name for a screen reader`() {
        val switch = tools.substring(tools.indexOf("onFlatSubfoldersChange(!flatSubfolders)"))
        assertTrue(tools.contains("Icons.AutoMirrored.Rounded.List"))
        assertTrue(tools.contains("Icons.Rounded.AccountTree"))
        assertTrue(switch.contains("R.string.view_as_list"))
        assertTrue(switch.contains("R.string.view_as_tree"))
        // The Library's switch is an IconButton with this padding at the end of its row.
        assertTrue(switch.contains("modifier = Modifier.padding(end = 6.dp)"))
        assertFalse("the unnamed picture button", tools.contains("ResizableIconButton"))
        for (folder in listOf("values", "values-fr")) {
            val words = File("src/main/res/$folder/strings-ot.xml").readText()
            assertTrue(folder, words.contains("<string name=\"view_as_tree\">"))
            assertTrue(folder, words.contains("<string name=\"view_as_list\">"))
        }
    }

    @Test
    fun `the sort row and the Library's own chips keep their place, and a phone on its side still puts the row and the sort on one line`() {
        // The Library's chips (when the tab is shown inside it) come first, then the row, then the sort row.
        val library = screen.indexOf("libraryFilterContent()")
        val row = screen.indexOf("if (!oneHeaderLine) tools()")
        val sorts = screen.indexOf("if (oneHeaderLine) SideBySide(tools, sort) else sort()")
        assertTrue(library in 0 until row && row < sorts)
        assertTrue(screen.contains("val sort = @Composable {"))
        assertTrue(screen.contains("SortHeader("))
        assertTrue(screen.contains("FolderMenu("))
    }

    @Test
    fun `a folder's row draws its folder in the accent colour on a tinted square as big and as round as a song's cover`() {
        val items = source("ui/component/items/SongItems.kt")
        val thumbnail = items.substringAfter("fun FolderThumbnail(").substringBefore("@Composable")
        assertTrue(thumbnail.contains(".size(ListThumbnailSize)"))
        assertTrue(thumbnail.contains("RoundedCornerShape(ThumbnailCornerRadius)"))
        assertTrue(thumbnail.contains("Icons.Rounded.Folder"))
        // The accent for the folder, and the same accent, thin, under it.
        assertTrue(thumbnail.contains("tint = MaterialTheme.colorScheme.primary"))
        assertTrue(Regex("""MaterialTheme\.colorScheme\.primary\.copy\(alpha = 0\.\d+f\)""").containsMatchIn(thumbnail))
        // The song's cover, for the numbers to be those.
        val song = items.substringAfter("fun SongListItem(")
        assertTrue(song.contains("RoundedCornerShape(ThumbnailCornerRadius)"))
        assertTrue(song.contains("modifier = Modifier.size(ListThumbnailSize)"))
        // Every folder row has it: the three ways SongFolderItem is called, and none draws a folder of its own.
        assertEquals(1, Regex("""Icons\.Rounded\.Folder\b""").findAll(items).count())
        assertEquals(3, Regex("""thumbnailContent = \{\s*FolderThumbnail\(\)\s*\}""").findAll(items).count())
    }

    @Test
    fun `the Folders tab's rows are those, the previous folder's too`() {
        // The folders and the way up, both through SongFolderItem, so both have it.
        assertEquals(2, Regex("""SongFolderItem\(""").findAll(screen).count())
        // The way up says so in the phone's language: it was English in every one.
        assertTrue(screen.contains("subtitle = stringResource(R.string.previous_folder)"))
        assertFalse(screen.contains("\"Previous folder\""))
        assertTrue(File("src/main/res/values/strings-ot.xml").readText().contains("<string name=\"previous_folder\">Previous folder</string>"))
        assertTrue(File("src/main/res/values-fr/strings-ot.xml").readText().contains("<string name=\"previous_folder\">Dossier parent</string>"))
    }
}

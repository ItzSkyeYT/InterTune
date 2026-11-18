/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where Remove from library on a file of the phone's and Exclude folder are (LocalRemoval, Unreleased.LOCAL_REMOVE),
 * read from the source as the other tests of places do, from the pictures he picked (experiments/mocks-1115,
 * 1-local-songs, A): a local song's entry where a YouTube song's is, with its words; a folder's last of a folder
 * row's menu; the folder on screen read again in place; what each takes out; and the words, held, short, in English
 * and French.
 */
class LocalRemovePlacesTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()

    /** The grid of a menu: the text of its GridMenu's block. */
    private fun grid(path: String): String {
        val text = code(path)
        val start = text.indexOf("GridMenu(")
        assertTrue("$path has no grid", start >= 0)
        val open = text.indexOf('{', text.indexOf(") {", start))
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("$path: the grid never closes")
    }

    private fun titles(grid: String) = Regex("""title = R\.string\.(\w+)""").findAll(grid).map { it.groupValues[1] }.toList()

    @Test
    fun `a local song's menu has Remove from library after Details, where and as a YouTube song has it, under the flag`() {
        val grid = grid("ui/menu/SongMenu.kt")
        val local = grid.indexOf("if (Unreleased.LOCAL_REMOVE && song.song.isLocal && song.song.inLibrary != null && localAddress != null) {")
        assertTrue("no Remove from library for a local song", local > 0)
        val entry = grid.substring(local, grid.indexOf("}", grid.indexOf("LocalRemoveMenu.removeSong(", local)) + 1)
        assertTrue(entry, "icon = Icons.Rounded.LibraryAddCheck" in entry && "title = R.string.remove_from_library" in entry)
        assertTrue(entry, "onDismiss()" in entry)
        // After Details and after the YouTube song's own entry, before what a song played from history has, Don't
        // play this song and Block artist: the order of the picture, and nothing anyone knows the place of moves.
        assertTrue(grid.indexOf("title = R.string.details") < local)
        assertTrue(grid.indexOf("if (!song.song.isLocal) {\n            if (song.song.inLibrary == null) {") < local)
        assertTrue(local < grid.indexOf("if (event != null) {"))
        assertTrue(local < grid.indexOf("dontPlayMenuItem("))
        assertEquals("menu_block_artist", titles(grid).filter { it != "together_suggest" }.last())
        // Twice in the grid: a YouTube song's and a file's, never both for one song.
        assertEquals(2, titles(grid).count { it == "remove_from_library" })
        // The address is worked out for files only, with the flag.
        val menu = code("ui/menu/SongMenu.kt")
        assertTrue("if (Unreleased.LOCAL_REMOVE && song.song.isLocal) song.song.localPath?.let { LocalRemoval.addressOf(context, it) } else null" in menu)
    }

    @Test
    fun `a folder row's menu ends with Exclude folder, and the menu of the folder on screen has none`() {
        val grid = grid("ui/menu/FolderMenu.kt")
        assertEquals("local_exclude_folder", titles(grid).last())
        assertEquals("m3u_export", titles(grid).dropLast(1).last())
        val entry = grid.substring(grid.indexOf("if (excludeAddress != null) {"))
        assertTrue(entry, "icon = Icons.Rounded.FolderOff" in entry && "LocalRemoveMenu.excludeFolder(" in entry)
        assertTrue(entry, "folder.getFullSquashedDir()" in entry)
        val menu = code("ui/menu/FolderMenu.kt")
        assertTrue("if (Unreleased.LOCAL_REMOVE && excludable) LocalRemoval.addressOf(context, folder.getFullSquashedDir()) else null" in menu)
        assertTrue("excludable: Boolean = false," in menu)
        // The row's menu says it can; the header's, over the folder open on screen, keeps the default.
        val row = code("ui/component/items/SongItems.kt").substringAfter("fun SongFolderItem(\n    folder: DirectoryTree,")
        assertTrue("excludable = true," in row.substringBefore("fun SongGridItem("))
        val screen = code("ui/screens/library/FolderScreen.kt")
        assertFalse("excludable" in screen)
    }

    @Test
    fun `the folder on screen is read again in place after each removal and each Undo`() {
        val model = code("viewmodels/LibraryViewModels.kt").substringAfter("class LibraryFoldersViewModel")
        assertTrue("LocalRemoval.changes.drop(1).collect {" in model)
        val after = model.substringAfter("LocalRemoval.changes.drop(1).collect {").substringBefore("}\n        }")
        assertTrue(after, "getLocalSongs()" in after && "getSongCount()" in after)
        // One at a time: the folders on the back stack all read again at the one signal (their keys no longer depend
        // on it: DirectoryTreeKeysTest).
        assertTrue(after, "removalReloads.withLock {" in after)
        val removal = code("utils/scanners/LocalRemoval.kt")
        // The trees kept for the Folders tab are dropped first: a folder opened later reads the table again.
        assertTrue("clearDtCache()\n        changed.value++" in removal)
    }

    @Test
    fun `a folder emptied in place by a removal still goes up by its two dots`() {
        // Found 18 Nov 2026 (the review of local-remove, L2): removing the last song of the folder on screen reads it
        // again in place, empty, and ".." went up only for a folder with songs in it, so it did nothing; Back worked.
        val screen = code("ui/screens/library/FolderScreen.kt")
        val up = screen.substringAfter("folderTitle = \"..\",").substringBefore("navController.navigateUp()")
        assertTrue(up, "if (!currDir.isSkeleton || currDir.culmSongs.value > 0) {" in up)
        // Read in place: the tree the folder shows is no skeleton once read, empty or not.
        val model = code("viewmodels/LibraryViewModels.kt").substringAfter("suspend fun getLocalSongs(")
        assertTrue("dt.isSkeleton = false" in model.substringBefore("localSongDirectoryTree.value = dt"))
    }

    @Test
    fun `a song goes with its like, as a YouTube song's Remove from library has it, and a folder's songs keep theirs`() {
        val removal = code("utils/scanners/LocalRemoval.kt")
        val song = removal.substringAfter("suspend fun removeSong(").substringBefore("suspend fun excludeFolder(")
        assertTrue(song, "database.transactionNow { toggleInLibrary(song.id, null) }" in song)
        assertTrue(song, "likes = true" in song)
        val folder = removal.substringAfter("suspend fun excludeFolder(").substringBefore("suspend fun undo(")
        assertTrue(folder, "database.transactionNow { rows.forEach { disableLocalSong(it.id) } }" in folder)
        assertTrue(folder, "likes = false" in folder)
        // Only the files of the phone's under that folder, checked again after the query's LIKE.
        assertTrue(folder, "s.isLocal && s.localPath?.let { under(it, folder) } == true" in folder)
        // Both write their line on Excluded scan paths, and Undo takes off only a line it added.
        assertTrue(song, "addEntry(context, address)" in song)
        assertTrue(folder, "addEntry(context, address)" in folder)
        val undo = removal.substringAfter("suspend fun undo(")
        assertTrue(undo, "change.entry?.let { removeEntry(context, it) }" in undo)
        assertTrue(undo, "restoreInLibrary(r.id, r.inLibrary, r.liked, r.likedDate)" in undo)
        // A library write the phone refuses takes the new line off the list again: nothing half done.
        assertTrue(song, "undoingEntry(context, address, added) {" in song)
        assertTrue(folder, "undoingEntry(context, address, added) {" in folder)
        assertTrue("if (added) runCatching { removeEntry(context, entry) }\n            throw e" in removal)
    }

    @Test
    fun `the work and Undo outlive the menu, and a failure never closes the app`() {
        val glue = code("ui/menu/LocalRemoveMenu.kt")
        assertTrue("CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> reportException(e) })" in glue)
        // A refused write is said in the app's own words, and then nothing is offered to undo.
        assertTrue("NotSaved.say(context, e)\n        null" in glue)
        assertTrue("saying(app) { LocalRemoval.removeSong(app, database, song, address) } ?: return@launch" in glue)
        assertTrue("saying(app) { LocalRemoval.excludeFolder(app, database, folder, address) } ?: return@launch" in glue)
        assertTrue("snackbar.showSnackbar(message = said, actionLabel = undo, duration = SnackbarDuration.Long)" in glue)
        assertTrue("if (result == SnackbarResult.ActionPerformed) {" in glue)
    }

    @Test
    fun `the flag is held back, and its words are held from translators, short, in English and French`() {
        val flags = code("constants/Unreleased.kt")
        assertTrue("val LOCAL_REMOVE = BuildConfig.PREVIEW_FEATURES" in flags)
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        val words = mapOf(
            "local_exclude_folder" to ("Exclude folder" to "Exclure le dossier"),
            "local_removed" to ("Removed from your library. The file stays." to "Retiré de votre bibliothèque. Le fichier reste."),
            "local_folder_excluded" to ("%1\$s excluded. Its files stay." to "%1\$s exclu. Ses fichiers restent."),
        )
        for ((name, pair) in words) {
            assertTrue(name, "<string name=\"$name\" translatable=\"false\">${pair.first}</string>" in english)
            assertTrue(name, "<string name=\"$name\" translatable=\"false\">${pair.second}</string>" in french)
            for (text in listOf(pair.first, pair.second)) {
                assertFalse(text, '—' in text)
                assertTrue(text, Regex("[.!?](?=\\s|$)").findAll(text).count() <= 2)
            }
        }
        // The song's entry keeps the words a YouTube song's has, already translated.
        assertTrue("<string name=\"remove_from_library\">Remove from library</string>" in File("src/main/res/values/strings.xml").readText())
    }
}

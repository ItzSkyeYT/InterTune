/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.db.SchemaDb
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
        val local = grid.indexOf("if (Unreleased.LOCAL_REMOVE && song.song.isLocal && song.song.inLibrary != null && localAddress != null && !scanBusy) {")
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
        val entry = grid.substring(grid.indexOf("if (excludeAddress != null && !scanBusy) {"))
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
    fun `neither is offered while a scan runs, and a tap or an Undo that meets one waits for its end`() {
        // Found 18 Nov 2026 (the review of local-remove, L3): a scan reads Excluded scan paths when it starts, so a
        // full rescan or MediaStore's sync already running put a removed song back, and any scan's disable pass took
        // an Undo out again.
        for (menu in listOf("ui/menu/SongMenu.kt", "ui/menu/FolderMenu.kt")) {
            val text = code(menu)
            assertTrue(menu, "val scanBusy = LocalRemoval.scanning(LocalMediaScanner.scannerState.collectAsState().value)" in text)
            assertEquals(menu, 1, Regex("""!scanBusy\)""").findAll(text).count())
        }
        val removal = code("utils/scanners/LocalRemoval.kt")
        for (work in listOf("suspend fun removeSong(", "suspend fun excludeFolder(", "suspend fun undo(")) {
            val body = removal.substringAfter(work).substringAfter("{\n").trimStart()
            assertTrue(work, body.startsWith("afterScan()\n"))
        }
        assertTrue("internal suspend fun afterScan(state: StateFlow<Int> = LocalMediaScanner.scannerState, settle: Long = 1000) {" in removal)
        // The scanner's own states: finding the files 1, reading them 2, writing the library 3, linking artists 5.
        val states = code("utils/scanners/LocalMediaScanner.kt").substringAfter("-1: Inactive").substringBefore("*/")
        for (state in listOf("1: Discovering", "2: Scanning", "3: Syncing", "5: Ytm artist linking")) assertTrue(state in states)
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
    fun `the snackbar lives as long as its activity, and Undo's write as long as the app`() {
        // Found 18 Nov 2026 (the review of local-remove, L5): in a dark mode or language switch within its 10 seconds
        // the activity is made anew with a host of its own, and the old host's snackbar never went: the wait for it
        // never ended.
        val glue = code("ui/menu/LocalRemoveMenu.kt")
        assertEquals(2, Regex("""val shows = context\.activity\(\)\?\.lifecycleScope \?: scope""").findAll(glue).count())
        assertEquals(2, Regex("""offerUndo\(app, database, snackbar, said, undo, change, shows\)""").findAll(glue).count())
        val offer = glue.substringAfter("private fun offerUndo(").substringBefore("private tailrec fun Context.activity()")
        assertTrue(offer, "shows.launch(CoroutineExceptionHandler { _, e -> reportException(e) }) {" in offer)
        assertTrue(offer, "scope.launch { saying(context) { LocalRemoval.undo(context, database, change) } }" in offer)
        // Not the screen's own lifetime (a nav entry's): leaving the screen keeps the snackbar.
        assertTrue("is ComponentActivity -> this\n        is ContextWrapper -> baseContext.activity()" in glue)
        assertFalse("LocalLifecycleOwner" in glue)
    }

    @Test
    fun `a liked song's message says it left Liked songs too, in the app's own name for them`() {
        // His pick of 19 Nov 2026: "The like goes too, and the message says so". Chosen by the very song whose like the
        // removal keeps for Undo (LocalRemoval.Row), so the message and Undo agree.
        val glue = code("ui/menu/LocalRemoveMenu.kt")
        assertTrue("val said = context.getString(if (song.liked) R.string.local_removed_liked else R.string.local_removed)" in glue)
        val removal = code("utils/scanners/LocalRemoval.kt").substringAfter("suspend fun removeSong(").substringBefore("suspend fun excludeFolder(")
        assertTrue(removal, "val row = Row(song.id, song.inLibrary, song.liked, song.likedDate)" in removal)
        // A folder's songs keep their likes: its message stays as it was.
        assertFalse("local_removed_liked" in glue.substringAfter("fun excludeFolder("))
        // Liked songs as the app names them everywhere else, in English and in French.
        assertTrue("<string name=\"liked_songs\">Liked songs</string>" in File("src/main/res/values/strings.xml").readText())
        assertTrue("<string name=\"liked_songs\">Titres aimés</string>" in File("src/main/res/values-fr/strings.xml").readText())
    }

    @Test
    fun `the like goes with the song and Undo brings it back, run on the app's own tables`() {
        val dao = code("db/daos/SongsDao.kt")
        fun query(name: String) = Regex("""@Query\("([^"]+)"\)\s*\n\s*fun $name\(""").find(dao)!!.groupValues[1]
        // The removal's write, toggleInLibrary(song.id, null): out of the library, and the like with it.
        val toggle = dao.substringAfter("fun toggleInLibrary(songId: String, inLibrary: LocalDateTime?) {").substringBefore("\n    }\n")
        assertTrue(toggle, "inLibrary(songId, inLibrary)" in toggle && "if (inLibrary == null) {\n            removeLike(songId)" in toggle)
        SchemaDb.open().use { db ->
            fun exec(sql: String) = db.createStatement().use { it.execute(sql) }
            fun row(): List<Any?> = db.createStatement().use { st ->
                st.executeQuery("SELECT inLibrary, liked, likedDate FROM song WHERE id = 's'").use { rs ->
                    assertTrue(rs.next())
                    listOf(rs.getObject(1), rs.getInt(2), rs.getObject(3))
                }
            }
            exec("INSERT INTO song(id, title, duration, liked, likedDate, inLibrary) VALUES ('s', 's', 200, 1, 1731000000000, 1730000000000)")
            val before = row()
            exec(query("inLibrary").replace(":inLibrary", "NULL").replace(":songId", "'s'"))
            exec(query("removeLike").replace(":songId", "'s'"))
            assertEquals("out of the library and out of Liked songs", listOf(null, 0, null), row())
            // Undo: restoreInLibrary with what the removal kept of the song.
            exec(
                query("restoreInLibrary").replace(":inLibrary", "1730000000000").replace(":likedDate", "1731000000000")
                    .replace(":liked", "1").replace(":songId", "'s'")
            )
            assertEquals("liked again, from the same day", before, row())
        }
    }

    @Test
    fun `on for everyone in 0_13, and its words are open to translators, short, in English and French`() {
        val flags = code("constants/Unreleased.kt")
        assertTrue("val LOCAL_REMOVE = true" in flags)
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        val words = mapOf(
            "local_exclude_folder" to ("Exclude folder" to "Exclure le dossier"),
            "local_removed" to ("Removed from your library. The file stays." to "Retiré de votre bibliothèque. Le fichier reste."),
            "local_removed_liked" to ("Removed from your library and Liked songs. The file stays." to "Retiré de votre bibliothèque et des Titres aimés. Le fichier reste."),
            "local_folder_excluded" to ("%1\$s excluded. Its files stay." to "%1\$s exclu. Ses fichiers restent."),
        )
        for ((name, pair) in words) {
            assertTrue(name, "<string name=\"$name\">${pair.first}</string>" in english)
            assertTrue(name, "<string name=\"$name\">${pair.second}</string>" in french)
            for (text in listOf(pair.first, pair.second)) {
                assertFalse(text, '—' in text)
                assertTrue(text, Regex("[.!?](?=\\s|$)").findAll(text).count() <= 2)
            }
        }
        // The song's entry keeps the words a YouTube song's has, already translated.
        assertTrue("<string name=\"remove_from_library\">Remove from library</string>" in File("src/main/res/values/strings.xml").readText())
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The extra copies of downloads a bug left in the download folder (dvd, 23 Nov 2026: "Title [id].mka (1)"
 * to "(5)" beside each of his 397 liked songs), and the move that no longer writes one. Read from the
 * source, as DownloadKeepsFolderFilesTest does: what is kept and what goes is tested in
 * DownloadCopiesTest.
 */
class DownloadCopiesWiringTest {
    private val sources = "src/main/java/com/dd3boh/outertune"
    private val cleanup = File("$sources/playback/DownloadCopiesCleanup.kt").readText()
    private val util = File("$sources/playback/DownloadUtil.kt").readText()
    private val folder = File("$sources/playback/downloadManager/DirectoryMangerOt.kt").readText()
    private val note = File("$sources/ui/component/DownloadCopiesNote.kt").readText()
    private val screen = File("$sources/ui/screens/playlist/AutoPlaylistScreen.kt").readText()
    private val flags = File("$sources/constants/Unreleased.kt").readText()

    private fun body(text: String, start: String) = text.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `the cleanup is in every build, and nothing is looked for or shown but under its flag`() {
        assertTrue(flags.contains("val DOWNLOAD_COPIES_CLEANUP = true"))
        assertTrue("nothing is looked for without it", cleanup.contains("if (!Unreleased.DOWNLOAD_COPIES_CLEANUP || _state.value is State.Removing) return"))
        assertTrue("and no card", note.contains("if (!Unreleased.DOWNLOAD_COPIES_CLEANUP) return"))
        val start = util.substringAfter("firstListing.countDown()").substringBefore("dropSongCacheCopiesOfDownloads()")
        assertTrue("looked for once a start, after the first listing", start.contains("if (Unreleased.DOWNLOAD_COPIES_CLEANUP) {"))
        assertTrue(start.contains("copies.find()"))
    }

    @Test
    fun `the card is on Downloaded songs only, and nothing goes but on its Remove`() {
        assertTrue(screen.contains("if (playlistType == PlaylistType.DOWNLOAD && Unreleased.DOWNLOAD_COPIES_CLEANUP) {"))
        assertTrue(note.contains("NoteButton(stringResource(R.string.dl_copies_remove), downloadUtil::removeCopies)"))
        assertTrue(note.contains("NoteButton(stringResource(R.string.dl_copies_not_now), downloadUtil.copies::notNow)"))
        // Remove is the one way in: the card's button, then the cleanup, then the folder's delete.
        val callers = File(sources).walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("removeCopies()") || it.readText().contains("::removeCopies") }
            .map { it.name }.toSet()
        assertEquals(setOf("DownloadUtil.kt", "DownloadCopiesNote.kt"), callers)
        assertTrue(util.contains("copies.remove()"))
        val deleter = Regex("""\.removeCopy\(|::removeCopy\b""")
        val deleters = File(sources).walkTopDown().filter { it.extension == "kt" && deleter.containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("DownloadCopiesCleanup.kt"), deleters)
        assertTrue("and the removal's own steps only", body(cleanup, "private fun removeFrom(").contains("DownloadCopies.remove(song, folder::lookAt, folder::removeCopy, step)"))
        assertEquals("nowhere else", 1, deleter.findAll(cleanup).count())
    }

    @Test
    fun `the file a song keeps is looked at first, never deleted, and named and recorded only after its copies have gone`() {
        // What goes and what stays is DownloadCopies.remove's, tested in DownloadCopiesTest; here, what comes of it.
        val one = body(cleanup, "private fun removeFrom(")
        val deleted = one.indexOf("val gone = DownloadCopies.remove(song, folder::lookAt, folder::removeCopy, step)")
        val untouched = one.indexOf("if (gone == null) {")
        val lost = one.indexOf("if (gone.keptLost) {")
        val renamed = one.indexOf("folder.keepFile(song.id, song.keep.key, name)")
        val recorded = one.indexOf("record(song.id, path)")
        assertTrue(deleted in 0 until untouched)
        assertTrue("nothing named or recorded when it was not there as found", untouched < lost)
        assertTrue("nor when it went with a copy", lost < renamed)
        assertTrue(renamed < recorded)
        for (end in listOf(untouched, lost)) {
            assertTrue(one.substring(end).substringBefore("\n        }\n").contains("return Removed("))
        }
        assertFalse(one.contains("removeCopy(song.keep"))
        // The download's name taken only once it is free.
        assertTrue(one.contains("song.renameTo?.takeIf { gone.allGone && folder.canLookUp() && folder.lookUp(it) == null }"))
    }

    @Test
    fun `a song the player has open is never touched, and a scan or a move never meets the cleanup in the folder`() {
        val remove = cleanup.substringAfter("suspend fun remove(): Removed? {").substringBefore("/** One song's copies gone")
        assertTrue(remove.contains("val (now, later) = waiting.partition { !isOpen(it.id) }"))
        assertTrue(remove.contains("withContext(Dispatchers.IO) { lock.withLock { removeFrom(song, found, step) } }"))
        assertTrue(cleanup.contains("val found = withContext(Dispatchers.IO) { lock.withLock { look() } } ?: return"))
        assertTrue("the scan's own lock", util.contains("DownloadCopiesCleanup(context, database, { localMgr }, scanLock, inPlay::isOpen)"))
        // Made before init, which starts its first look on another thread.
        assertTrue(util.indexOf("val copies by lazy {") in 0 until util.indexOf("    init {\n"))
    }

    @Test
    fun `a song whose copy was named back just as the app ended keeps its download at the next start`() {
        // The copy a song keeps is named back to its download's name, then recorded: an app ended between
        // the two left the database the copy's old name, and on a phone whose folder lists as empty the
        // next start's rescan let the song's download go, its file right there (27 Nov 2026).
        val one = body(cleanup, "private fun removeFrom(")
        assertTrue("named first, recorded after", one.indexOf("folder.keepFile(song.id, song.keep.key, name)") in 0 until one.indexOf("record(song.id, path)"))
        val look = folder.substringAfter("fun lookAgain(").substringBefore("private fun adopt(")
        assertTrue("the scan looks under the name it was given", look.contains("val found = document(uri) ?: namedBack(id, root, path)"))
        val back = body(folder, "private fun namedBack(")
        assertTrue("in the main folder only, where the clean-up works", back.contains("if (root.dir !== mainDir) return null"))
        assertTrue("at its top only", back.contains("if ('/' in relative) return null"))
        assertTrue("a copy of this very song", back.contains("DownloadCopies.ownNameOf(relative)?.takeIf { DownloadCopies.parse(relative)?.id == id }"))
    }

    @Test
    fun `Not now keeps the card down a week while copies are left, and more copies bring it back at once`() {
        // His word of 27 Nov 2026: it kept the card down until more copies came, for good for dvd's folder.
        val find = body(cleanup, "suspend fun find() {")
        assertTrue(find.contains("val shows = DownloadCopies.cardShows(files, putOff, putOffAt, System.currentTimeMillis())"))
        assertTrue(find.contains("_state.value = if (shows) State.Found(files, found.plan.bytes) else State.None"))
        val notNow = body(cleanup, "fun notNow() {")
        assertTrue(notNow.contains("it[DownloadCopiesPutOffKey] = shown.files"))
        assertTrue(notNow.contains("it[DownloadCopiesPutOffAtKey] = at"))
        // A look by name in a folder that lists as empty rests 12 hours only when the card would stay down.
        assertTrue(cleanup.contains("val quiet = !DownloadCopies.cardShows("))
    }

    @Test
    fun `the folder's listing looks one file at a time only at the songs with a copy`() {
        // A look a file, at every start, was about a millisecond for each download of everyone's folder.
        val listed = body(folder, "fun ourFilesListed(): DownloadCopies.Listed? {")
        assertTrue(listed.contains("val wanted = DownloadCopies.ofSongsWithCopies(listed.mapNotNull { it.name })"))
        assertTrue(listed.indexOf("if (name !in wanted) return@mapNotNull null") in 0 until listed.indexOf("described(file.uri)"))
    }

    @Test
    fun `only the folder the app was given is looked at`() {
        val listed = body(folder, "fun ourFilesListed(): DownloadCopies.Listed? {")
        assertTrue(listed.contains("(mainDir as? TreeDocumentFileOt)?.listFilesOrNull()"))
        assertFalse("not the extra folders", listed.contains("allDirs"))
        assertFalse("not a folder inside it", listed.contains("scanDfRecursive"))
        val lookUp = body(folder, "fun lookUp(name: String): DownloadCopies.File? {")
        assertTrue(lookUp.contains("val main = mainDir?.let { rootOf(it) } ?: return null"))
    }

    @Test
    fun `the database ends pointing at the file kept, as a scan records a listed file`() {
        val record = body(util, "private fun recordKeptCopy(id: String, path: String) {")
        assertTrue(record.indexOf("scanTouchedIds.add(id)") in 0 until record.indexOf("downloads.update { it + (id to date) }"))
        assertTrue(record.contains("database.registerDownloadSong(id, date, path)"))
        assertTrue("the date it had, kept", record.contains("downloads.value[id]?.takeIf { hasLanded(it) } ?: LocalDateTime.now()"))
    }

    @Test
    fun `its words are in English and French, open to translators`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        val names = listOf("dl_copies_title", "dl_copies_body", "dl_copies_remove", "dl_copies_not_now", "dl_copies_removing", "dl_copies_removed")
        for (name in names) {
            assertTrue("$name in English, open to translators", Regex("""<(string|plurals) name="$name">""").containsMatchIn(english))
            assertTrue("$name in French", Regex("""<(string|plurals) name="$name">""").containsMatchIn(french))
        }
        // Next to the move's words, the closest of the folder's.
        assertTrue(english.indexOf("name=\"dl_copies_title\"") > english.indexOf("<plurals name=\"dl_move_notice_held\">"))
        assertFalse("no em dash", Regex("""name="dl_copies_[a-z_]+"[^>]*>[^<]*\u2014""").containsMatchIn(english + french))
    }
}

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
    fun `the cleanup is held back for the debug app, the Preview and the Tester app`() {
        assertTrue(flags.contains("val DOWNLOAD_COPIES_CLEANUP = BuildConfig.PREVIEW_FEATURES"))
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
        val deleters = File(sources).walkTopDown().filter { it.extension == "kt" && it.readText().contains(".removeCopy(") }.map { it.name }.toSet()
        assertEquals(setOf("DownloadCopiesCleanup.kt"), deleters)
        assertTrue("and the removal's own steps only", body(cleanup, "private fun removeFrom(").contains("folder.removeCopy(copy.key)"))
    }

    @Test
    fun `the file a song keeps is looked at first, never deleted, and named and recorded only after its copies have gone`() {
        val one = body(cleanup, "private fun removeFrom(")
        val kept = one.indexOf("val kept = folder.lookAt(song.keep.key)")
        val untouched = one.indexOf("if (kept == null || kept.name != song.keep.name || kept.size == 0L) {")
        val deleted = one.indexOf("folder.removeCopy(copy.key)")
        val renamed = one.indexOf("folder.keepFile(song.id, song.keep.key, name)")
        val recorded = one.indexOf("record(song.id, path)")
        assertTrue(kept in 0 until untouched)
        assertTrue("nothing of the song is touched when it is not there as found", untouched < deleted)
        assertTrue(deleted < renamed)
        assertTrue(renamed < recorded)
        // Only the copies the plan names, each looked at again and taken only if it is still the file found.
        assertTrue(one.contains("for (copy in song.remove) {"))
        assertTrue(one.contains("now.name != copy.name || DownloadCopies.parse(now.name)?.id != song.id -> allGone = false"))
        assertFalse(one.contains("removeCopy(song.keep"))
        // The download's name taken only once it is free.
        assertTrue(one.contains("song.renameTo?.takeIf { allGone && folder.canLookUp() && folder.lookUp(it) == null }"))
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
    fun `its words are in English and French, held from translators until it ships`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        val names = listOf("dl_copies_title", "dl_copies_body", "dl_copies_remove", "dl_copies_not_now", "dl_copies_removing", "dl_copies_removed")
        for (name in names) {
            assertTrue("$name in English, held", Regex("""<(string|plurals) name="$name" translatable="false">""").containsMatchIn(english))
            assertTrue("$name in French", Regex("""<(string|plurals) name="$name">""").containsMatchIn(french))
        }
        // Next to the move's words, the closest of the folder's.
        assertTrue(english.indexOf("name=\"dl_copies_title\"") > english.indexOf("<plurals name=\"dl_move_notice_held\">"))
        assertFalse("no em dash", Regex("""name="dl_copies_[a-z_]+"[^>]*>[^<]*\u2014""").containsMatchIn(english + french))
    }
}

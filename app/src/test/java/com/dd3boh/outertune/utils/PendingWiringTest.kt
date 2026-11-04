/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every change made in the app for the YouTube Music account goes through PendingChanges, and every
 * kind of sync sends what waits before it reads the account and leaves alone what still waits. Read
 * from the source, as SyncFailureTest is: the paths are the point, and a call site that sends by
 * itself again compiles and passes every other test.
 */
class PendingWiringTest {

    private val main = File("src/main/java/com/dd3boh/outertune")

    /** The source without its comments, so that a word in a comment proves nothing. */
    private fun code(path: String) = File(main, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /** The body of the function declared by [declaration] in [text], braces matched. */
    private fun body(text: String, declaration: String): String {
        val start = text.indexOf(declaration)
        assertTrue("$declaration is gone: look at how the sync is built now", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }

    private val writes = Regex(
        """YouTube\.(likeVideo|likePlaylist|subscribeChannel|addToPlaylist|addPlaylistToPlaylist|removeFromPlaylist|moveSongPlaylist|renamePlaylist|deletePlaylist)\("""
    )

    @Test
    fun `nothing writes to the account but the sender of the changes kept for it`() {
        val found = main.walkTopDown().filter { it.extension == "kt" }
            .filterNot { it.name == "PendingSend.kt" }
            .flatMap { file -> writes.findAll(code(file.relativeTo(main).path)).map { "${file.relativeTo(main)}: ${it.value}" } }
            .toList()
        assertEquals("sent by itself, not kept until the account has it", emptyList<String>(), found)
        // And the sender does send each kind.
        val sender = code("utils/PendingSend.kt")
        for (call in listOf("likeVideo", "likePlaylist", "subscribeChannel", "addToPlaylist", "removeFromPlaylist", "moveSongPlaylist", "renamePlaylist", "deletePlaylist")) {
            assertTrue(call, "YouTube.$call(" in sender)
        }
    }

    @Test
    fun `nothing is kept while nobody is signed in or sync is read only`() {
        val record = body(code("utils/PendingSend.kt"), "fun record(")
        assertTrue("if (!app.mayPushToYouTube()) return" in record)
        // And nothing is sent then either: the gate in YouTube refuses it, and that keeps it waiting.
        assertTrue("if (!context.mayPushToYouTube())" in body(code("utils/PendingSend.kt"), "suspend fun send(context: Context"))
    }

    @Test
    fun `the changes are kept in the app's files that are not backed up, and a restore forgets them`() {
        assertTrue("PendingStore(File(it.noBackupFilesDir, FILE))" in code("utils/PendingSend.kt"))
        val restore = body(code("viewmodels/BackupRestoreViewModel.kt"), "private suspend fun restoreValidatedDatabase(")
        val swap = restore.indexOf("DatabaseFiles.replace(")
        val forget = restore.indexOf("PendingChanges.forgetAll()")
        assertTrue("forgotten after the backup's library is in place", swap in 0 until forget)
    }

    @Test
    fun `each kind of sync sends what waits before it reads the account`() {
        val sync = code("utils/SyncUtils.kt")
        for ((kind, read) in mapOf(
            "likedSongs" to "YouTube.playlist(\"LM\")",
            "librarySongs" to "getRemoteData<SongItem>(",
            "albums" to "getRemoteData<AlbumItem>(",
            "artists" to "getRemoteData<ArtistItem>(",
            "playlists" to "YouTube.library(\"FEmusic_liked_playlists\")",
        )) {
            val b = body(sync, "private suspend fun $kind(")
            val sent = b.indexOf("PendingChanges.sendThenWaiting(context)")
            assertTrue("$kind sends first", sent >= 0 && sent < b.indexOf(read))
        }
        val one = body(sync, "suspend fun syncPlaylist(")
        assertTrue(one.indexOf("PendingChanges.sendThenWaiting(context)") in 0 until one.indexOf("YouTube.playlist(browseId)"))
    }

    @Test
    fun `the sync leaves alone what waits`() {
        val sync = code("utils/SyncUtils.kt")
        val liked = body(sync, "private suspend fun likedSongs(")
        assertTrue("LikedSync.toLikeHere(" in liked)
        assertTrue("LikedSync.toUnlikeHere(" in liked)
        val library = body(sync, "private suspend fun librarySongs(")
        assertTrue("LibrarySync.toAdd(" in library)
        assertTrue("LibrarySync.toRemove(" in library)
        val albums = body(sync, "private suspend fun albums(")
        assertTrue("SavedSync.albumsToSave(" in albums && "SavedSync.albumsToUnsave(" in albums)
        val artists = body(sync, "private suspend fun artists(")
        assertTrue("SavedSync.artistsToFollow(" in artists && "SavedSync.artistsToUnfollow(" in artists)
        val playlists = body(sync, "private suspend fun playlists(")
        assertTrue("PlaylistLibrarySync.step(" in playlists && "PlaylistLibrarySync.toUnsave(" in playlists)
        // Every row with a YouTube playlist behind it, saved or not (finding 9): not only the library's.
        assertTrue("database.syncedPlaylistRows()" in playlists)
        assertFalse("database.playlistByBrowseId(" in playlists)
        val one = body(sync, "suspend fun syncPlaylist(")
        val transaction = one.substringAfter("database.transaction {")
        // Checked again just before the songs are replaced, for a change made while they were read.
        assertTrue("PlaylistSync.mayReplace(browseId, PendingChanges.waitingNow(context))" in transaction)
        assertTrue(transaction.indexOf("phoneFileRows(playlistId)") in 0 until transaction.indexOf("clearPlaylist(playlistId)"))
        assertTrue("PlaylistSync.rowsAfter(" in transaction)
    }

    @Test
    fun `hearts, saves and follows keep their change instead of sending it once`() {
        for ((path, call) in mapOf(
            "db/entities/SongEntity.kt" to "PendingChanges.liked(this, liked = !liked)",
            "db/entities/PlaylistEntity.kt" to "PendingChanges.savedPlaylist(this, saved = bookmarkedAt == null)",
            "db/entities/AlbumEntity.kt" to "PendingChanges.savedAlbum(this, saved = bookmarkedAt == null)",
            "db/entities/ArtistEntity.kt" to "PendingChanges.followed(this, followed = bookmarkedAt == null)",
        )) {
            val text = code(path)
            assertTrue(path, call in body(text, "fun toggleLike("))
            assertFalse(path, "YouTube." in text)
        }
    }

    @Test
    fun `both ways out of the library send the unlike they make (finding 7)`() {
        val toggle = body(code("db/entities/SongEntity.kt"), "fun toggleLibrary(")
        assertTrue("if (inLibrary != null) PendingChanges.tookOutOfLibrary(this)" in toggle)
        val dao = body(code("db/daos/SongsDao.kt"), "fun toggleInLibrary(")
        assertTrue("val was = if (inLibrary == null) songRow(songId) else null" in dao)
        assertTrue("was?.let { PendingChanges.tookOutOfLibrary(it) }" in dao)
    }

    @Test
    fun `an add is pushed once, from what went in, and never before the duplicates prompt`() {
        val dialog = code("ui/dialog/AddToPlaylistDialog.kt")
        assertTrue("database.addSongRowsToPlaylist(playlist, ids)" in dialog)
        assertTrue("PendingChanges.added(playlist.playlist, rows)" in body(dialog, "fun pushToYouTube("))
        // The menus' own pushes in onPreAdd went out before the prompt, even when it was cancelled.
        for (menu in listOf("SongMenu", "PlayerMenu", "YouTubeSongMenu", "AlbumMenu", "YouTubeAlbumMenu", "YouTubePlaylistMenu")) {
            val text = code("ui/menu/$menu.kt")
            val preAdd = text.substringAfter("onPreAdd = ").substringBefore("onDismiss")
            assertFalse(menu, "YouTube.add" in preAdd)
            assertFalse(menu, "PendingChanges" in preAdd)
        }
    }
}

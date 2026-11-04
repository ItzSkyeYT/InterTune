/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the choice of a playlist's cover (Unreleased.PLAYLIST_COVER) is offered, and the words it
 * says. It is for a playlist of one's own and only behind the flag; a playlist from YouTube keeps
 * YouTube's picture, and with the flag off Edit playlist keeps its pencil and a tap on the cover in
 * the header does what it did, which is nothing. This reads the source, as ReleasedFlagsTest does:
 * a place that offers the choice by another test still compiles, and works for a playlist it must not.
 */
class PlaylistCoverWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so that a sentence that names a call is not taken for one. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun kotlin(path: String) = code(File(sources, path).readText())

    private val screen = kotlin("ui/screens/playlist/LocalPlaylistScreen.kt")

    /** Every Kotlin file of the app, comments taken out, by its path under the sources. */
    private val all: Map<String, String> = sources.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(sources).path to code(it.readText()) }

    private fun filesWith(text: String) = all.filterValues { text in it }.keys

    // ---- the flag ----

    @Test
    fun `the flag is on in debug and preview builds and off in a release`() {
        val flags = kotlin("constants/Unreleased.kt")
        assertTrue(flags.lines().any { it.trim() == "val PLAYLIST_COVER = BuildConfig.DEBUG" })
        // Said where ReleasedFlagsTest says it too: it is not one of the flags a release has on.
        val released = File("src/test/java/com/dd3boh/outertune/constants/ReleasedFlagsTest.kt").readText()
        assertFalse("PLAYLIST_COVER" in released)
    }

    @Test
    fun `the flag is read in one place, which asks the playlist as well`() {
        // PlaylistCovers.canChoose is the whole question: the flag and a playlist of one's own.
        assertEquals(setOf("utils/PlaylistCovers.kt"), filesWith("Unreleased.PLAYLIST_COVER"))
        val canChoose = kotlin("utils/PlaylistCovers.kt").lines().single { "fun canChoose(" in it }
        assertTrue(canChoose, "enabled: Boolean = Unreleased.PLAYLIST_COVER" in canChoose)
        assertTrue(canChoose, "enabled && browseId == null" in canChoose)
    }

    // ---- where it is offered ----

    @Test
    fun `the screen asks once whether the choice is offered, and everything hangs on the answer`() {
        assertEquals(1, Regex("""PlaylistCovers\.canChoose\(""").findAll(screen).count())
        assertTrue(
            "canChoose must be asked of the playlist's browseId",
            "PlaylistCovers.canChoose(currentPlaylist.playlist.browseId)" in screen
        )
        // The tap on the header's cover is a link to the sheet only while the choice is offered, else null.
        assertTrue("val onChangeCover: (() -> Unit)? = if (coverChoosable) ({ showCoverSheet = true }) else null" in screen)
        // The sheet is composed only while it is offered and asked for.
        assertTrue("if (showCoverSheet && coverChoosable) {" in screen)
    }

    @Test
    fun `every header the screen draws is given the tap, and a header with none is a picture`() {
        val calls = Regex("""LocalPlaylistHeader\(""").findAll(screen).count()
        // Its own definition is one of them.
        assertEquals(4, calls)
        assertEquals(3, Regex("""onChangeCover = onChangeCover,""").findAll(screen).count())
        val header = screen.substring(screen.indexOf("fun LocalPlaylistHeader("))
        assertTrue("onChangeCover: (() -> Unit)? = null" in header)
        assertTrue("if (onChangeCover != null) {" in header)
        // Named for a screen reader, with the words Edit playlist has under its cover.
        assertTrue("stringResource(R.string.playlist_cover_change)" in header.substringAfter("if (onChangeCover != null) {").substringBefore("} else {"))
        assertTrue(".named(" in header.substringAfter("if (onChangeCover != null) {").substringBefore("} else {"))
    }

    @Test
    fun `Edit playlist shows the cover only where the choice is offered, else its pencil as before`() {
        val dialog = screen.substring(screen.indexOf("if (showEditDialog) {"), screen.indexOf("var showRemoveDownloadDialog"))
        assertTrue("if (onChangeCover != null) {" in dialog)
        assertTrue("PlaylistCoverEditor(" in dialog)
        val otherwise = dialog.substringAfter("} else {").substringBefore("title = {")
        assertTrue("Icon(imageVector = Icons.Rounded.Edit, contentDescription = null)" in otherwise)
        // The keyboard goes down before the sheet comes up over the dialog.
        assertTrue("keyboard?.hide()" in dialog)
        assertTrue("focus.clearFocus()" in dialog)
    }

    @Test
    fun `the dialog gives up the cover for its pencil where it would push OK under the keyboard`() {
        val editor = kotlin("ui/dialog/PlaylistCoverSheet.kt").let { it.substring(it.indexOf("fun PlaylistCoverEditor(")) }
        // Decided from the screen's height and the font, not from the keyboard, so that it does not jump when the keyboard comes.
        assertTrue("LocalConfiguration.current.screenHeightDp.dp < EditorHeight + RestOfTheDialog * fontScale + ScreenBesideTheDialog" in editor)
        assertTrue("Icon(imageVector = Icons.Rounded.Edit, contentDescription = null)" in editor.substringBefore("return"))
        // The window is shrunk to what the keyboard leaves, so that the dialog with its cover stays in view.
        assertTrue("DialogAboveKeyboard(always = true)" in editor.substringAfter("return"))
    }

    @Test
    fun `no other screen offers the sheet`() {
        // Not the automatic playlists (liked, downloaded), nor a playlist from YouTube's own page.
        assertEquals(setOf("ui/screens/playlist/LocalPlaylistScreen.kt", "ui/dialog/PlaylistCoverSheet.kt"), filesWith("PlaylistCoverSheet("))
        assertEquals(setOf("ui/screens/playlist/LocalPlaylistScreen.kt", "ui/dialog/PlaylistCoverSheet.kt"), filesWith("PlaylistCoverEditor("))
    }

    // ---- what a pick does ----

    @Test
    fun `a photo comes from Android's photo picker, which asks for no permission`() {
        assertTrue("ActivityResultContracts.PickVisualMedia()" in screen)
        assertTrue("PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)" in screen)
        for (file in listOf("ui/dialog/PlaylistCoverSheet.kt", "utils/PlaylistCovers.kt", "viewmodels/LocalPlaylistViewModel.kt")) {
            assertFalse("$file asks for a permission", "RequestPermission" in all.getValue(file) || "READ_MEDIA" in all.getValue(file) || "READ_EXTERNAL_STORAGE" in all.getValue(file))
        }
    }

    @Test
    fun `a photo is kept as a JPEG at quality 90 and at most 1024 px`() {
        val covers = kotlin("utils/PlaylistCovers.kt")
        assertTrue("const val MAX_SIDE = 1024" in covers)
        assertTrue("const val QUALITY = 90" in covers)
        assertTrue("standing.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)" in covers)
        assertTrue("fit(decoded.width, decoded.height)" in covers)
        // In the app's own files, where it stays when the photo is deleted from the phone.
        assertTrue("keep(context.filesDir, playlistId)" in covers)
    }

    @Test
    fun `the choice is written by one statement that refuses a playlist from YouTube`() {
        assertTrue("browseId IS NULL" in kotlin("db/PlaylistSql.kt").substringAfter("const val SET_COVER"))
        // Nothing else writes a cover: not a copy of the whole row, which could put an old one back.
        val writers = all.filterValues { "setPlaylistCover(" in it }.keys
        assertEquals(setOf("db/daos/PlaylistsDao.kt", "viewmodels/LocalPlaylistViewModel.kt"), writers)
    }

    @Test
    fun `a replaced photo is deleted after the new cover is written, never before`() {
        val model = kotlin("viewmodels/LocalPlaylistViewModel.kt")
        val write = model.substring(model.indexOf("private fun write("))
        assertTrue(write.indexOf("setPlaylistCover(") in 0 until write.indexOf("PlaylistCovers.forget("))
        assertTrue("except = kept" in write)
        // A write no row took keeps what is named, and drops the photo it just made.
        assertTrue("if (written > 0)" in write)
    }

    @Test
    fun `deleting a playlist deletes its photo`() {
        assertTrue("PlaylistCovers.forget(files, it.playlist.id)" in screen)
        assertTrue("PlaylistCovers.forget(files, playlist.playlist.id)" in kotlin("ui/menu/PlaylistMenu.kt"))
    }

    @Test
    fun `a kept photo is drawn from its file wherever a playlist is drawn, and the songs' covers stand in when it is gone`() {
        assertTrue("PlaylistCovers.model(files, playlist.thumbnailUrl, automatic, px)" in kotlin("ui/component/items/PlaylistItems.kt"))
        assertTrue("!PlaylistCovers.isPhoto(playlist.thumbnailUrl)" in kotlin("db/entities/Playlist.kt"))
    }

    // ---- the app's own backup ----

    @Test
    fun `a backup carries the photos after the database, and a restore puts them in place last`() {
        val writer = kotlin("utils/BackupWriter.kt")
        assertTrue(writer.indexOf("ZipEntry(InternalDatabase.DB_NAME)") in 0 until writer.indexOf("PlaylistCovers.addTo(zip, context.filesDir)"))
        val restore = kotlin("viewmodels/BackupRestoreViewModel.kt")
        val body = restore.substring(restore.indexOf("fun restoreValidatedDatabase("))
        // Staged as they are read, like the settings, so that a refused backup changes nothing.
        assertTrue("else -> PlaylistCovers.stage(stagedCovers, entry.name, inputStream)" in body)
        val refused = body.substring(body.indexOf("if (!databaseValidated) {"), body.indexOf("Found valid database"))
        assertTrue("stagedCovers.deleteRecursively()" in refused)
        // In place only once the database and the settings are: the photos come last, and cost the restore nothing.
        val place = body.indexOf("runCatching { PlaylistCovers.place(stagedCovers, context.filesDir) }")
        assertTrue(body.indexOf("DatabaseFiles.replace(probeFile") in 0 until place)
        assertTrue(body.indexOf("settingsBytes?.let") in 0 until place)
    }

    // ---- the words ----

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()

    private val words = listOf(
        Triple("playlist_cover_change", "Change cover", "Changer la pochette"),
        Triple("playlist_cover_title", "Playlist cover", "Pochette de la playlist"),
        Triple("playlist_cover_note", "The cover above changes as you pick.", "La pochette au-dessus change à chaque choix."),
        Triple("playlist_cover_automatic", "Automatic", "Automatique"),
        Triple("playlist_cover_automatic_note", "The first song\\'s cover", "La pochette du premier titre"),
        Triple("playlist_cover_photo", "A photo", "Une photo"),
        Triple("playlist_cover_photo_note", "From your phone", "Depuis votre téléphone"),
        Triple("playlist_cover_songs", "A song from this playlist", "Un titre de cette playlist"),
    )

    @Test
    fun `the words are the ones that were drawn, in English and in French`() {
        for ((name, en, fr) in words) {
            assertTrue("$name in English", "<string name=\"$name\" translatable=\"false\">$en</string>" in english)
            assertTrue("$name in French", "<string name=\"$name\">$fr</string>" in french)
        }
    }

    @Test
    fun `the words are held from translators like the other texts that wait, and have no em dash`() {
        val held = Regex("""<string name="(playlist_cover_\w+)"[^>]*>""").findAll(english).map { it.value }.toList()
        assertEquals(words.size, held.size)
        for (line in held) assertTrue(line, "translatable=\"false\"" in line)
        val inFrench = Regex("""<string name="playlist_cover_\w+"[^>]*>([^<]*)</string>""").findAll(french).map { it.groupValues[1] }.toList()
        assertEquals(words.size, inFrench.size)
        for (text in inFrench + words.map { it.second }) assertFalse(text, '—' in text || '–' in text)
    }

    @Test
    fun `every one of the words is read by the code`() {
        val used = all.filterKeys { it != "ui/dialog/PlaylistCoverSheet.kt" && it != "ui/screens/playlist/LocalPlaylistScreen.kt" }.values.joinToString("\n") +
            all.getValue("ui/dialog/PlaylistCoverSheet.kt") + screen
        for ((name, _, _) in words) assertTrue("$name is not used", "R.string.$name" in used)
    }
}

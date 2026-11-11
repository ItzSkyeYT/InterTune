/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * A failure the person was told about is in the report (10 Nov 2026: "The video could not be made",
 * and two reports that did not hold the error).
 */
class FailureLogTest {
    private val root = IOException("No space left on device")
    private val failure = IllegalStateException("Could not write the backup", root)

    private fun raw(level: Char, tag: String, message: String, second: Int = 10) =
        "11-10 23:00:%02d.100 %s/%s( 9001): %s".format(second, level, tag, message)

    private fun filler(n: Int) = (1..n).map { raw('I', "MusicService", "PLAYING: song $it of the queue is played from the cache", 1 + it % 8) }

    @Test
    fun `the line says what was done, the exception's class and message, its root cause and where in the app it began`() {
        val line = FailureLog.line("Creating a backup", failure)
        assertTrue(line, line.startsWith("Creating a backup did not work: IllegalStateException: Could not write the backup (caused by IOException: No space left on device), at FailureLogTest."))
        // The first frame of the app's own: here the test, which sits in the app's package.
        assertTrue(line, "(FailureLogTest.kt:" in line)
        assertFalse("one line", '\n' in line)
    }

    @Test
    fun `without an exception the reason stands in its place`() {
        assertEquals(
            "Fetching the lyrics again did not work: no lyrics provider answered",
            FailureLog.line("Fetching the lyrics again", why = "no lyrics provider answered"),
        )
        assertEquals("Saving a change did not work", FailureLog.line("Saving a change"))
        // An exception with no message is its class.
        assertTrue(FailureLog.line("Saving a change", IOException()).startsWith("Saving a change did not work: IOException, at "))
    }

    @Test
    fun `an address, a folder of the phone or a picked file's address is not in it`() {
        val said = IOException(
            "failed to connect to music.youtube.com/203.0.113.57 (port 443) from /192.0.2.44 (port 51234): " +
                "/storage/emulated/0/Music/Alice's phone/Song.mp3: open failed: ENOENT, " +
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlice"
        )
        val line = FailureLog.line("Exporting the songs to a folder", said)
        for (secret in listOf("203.0.113.57", "192.0.2.44", "Alice", "/storage/", "primary%3A")) assertFalse("$secret in: $line", secret in line)
        assertTrue(line, "music.youtube.com" in line && "ENOENT" in line)
    }

    @Test
    fun `only the first line of a message is kept, and no more than a few hundred characters`() {
        val long = IOException("first line\nsecond line\n" + "x".repeat(2_000))
        assertFalse(FailureLog.line("Saving a change", long).contains("second line"))
        val wordy = IOException("y".repeat(2_000), IOException("z".repeat(2_000)))
        val line = FailureLog.line("Saving a change", wordy)
        assertTrue("${line.length} characters", line.length < 2 * FailureLog.MOST + 250)
        assertEquals("why is cut too", FailureLog.MOST, FailureLog.line("a", why = "w".repeat(2_000)).substringAfter("did not work: ").length)
    }

    @Test
    fun `recording never throws, whatever it is given`() {
        FailureLog.record("Creating a backup", failure)
        FailureLog.record("Creating a backup", why = "no room")
        FailureLog.record("Creating a backup")
    }

    @Test
    fun `a failure the person was told about is the report's fault, with what led up to it`() {
        val line = FailureLog.line("Creating a backup", failure)
        val log = filler(30) +
            raw('W', "System.err", "java.lang.IllegalStateException: Could not write the backup", 20) +
            raw('E', FailureLog.TAG, line, 21) +
            raw('I', "MusicService", "PLAYING: the queue goes on", 22)
        val excerpt = ProblemLog.excerpt(log)
        assertNotNull("a fault is found", excerpt.faultAt)
        val at = excerpt.lines[excerpt.faultAt!!]
        assertEquals(FailureLog.TAG, at.tag)
        assertEquals(line, at.message)

        val report = ProblemLog.report(excerpt, build = "0.13", device = "Google Pixel 5", installedFrom = "updater")
        assertTrue(report, report.contains("The last fault and what led up to it:"))
        assertFalse(report, report.contains("Nothing that looks like a fault"))
        assertTrue(report, report.contains("${ProblemLog.FAULT_MARK}\n23:00:21.100 E/Failure: $line"))
        // what led up to it, from the line the failure's own code wrote
        assertTrue(report, report.contains("W/System.err: java.lang.IllegalStateException: Could not write the backup"))
        // and the issue's title is its words
        assertTrue(ReportRelay.title(excerpt), ReportRelay.title(excerpt)!!.startsWith("Failure: Creating a backup did not work: IllegalStateException"))
    }

    @Test
    fun `as a warning under the tag of the code that caught it, as before, it was not`() {
        val before = filler(5) + raw('W', "BackupRestoreViewModel", "Could not create the backup", 20)
        val excerpt = ProblemLog.excerpt(before)
        assertNull("nothing looked like a fault", excerpt.faultAt)
        assertTrue(ProblemLog.report(excerpt, build = "0.13", device = "Google Pixel 5", installedFrom = "updater").contains("Nothing that looks like a fault"))
    }

    @Test
    fun `it is still there when the report is cut to fit the form's link`() {
        val line = FailureLog.line("Creating a backup", failure)
        val log = filler(45) + raw('E', FailureLog.TAG, line, 21) + filler(10)
        val report = ProblemLog.report(ProblemLog.excerpt(log), build = "0.13", device = "Google Pixel 5", installedFrom = "updater")
        for (limit in listOf(900, 1_500)) {
            val cut = ProblemLog.fitted(report, limit)
            assertTrue("$limit: ${cut.length} characters", cut.length <= limit)
            assertTrue("$limit: $cut", cut.contains("E/Failure: $line"))
        }
    }

    // ---- the places that show a failure ----

    private val main = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String): List<String> {
        val file = File(main, path)
        assertTrue("$file was not found", file.isFile)
        return file.readLines()
    }

    /**
     * Where the person is told it did not work, the line that records why is beside it: Report a
     * problem has the fault only when something wrote it. Each is a toast's words and the file that
     * shows it; the recording is within a few lines before or after.
     */
    @Test
    fun `a toast that says something did not work has its failure recorded beside it`() {
        val told = listOf(
            "viewmodels/BackupRestoreViewModel.kt" to "backup_create_failed",
            "viewmodels/BackupRestoreViewModel.kt" to "err_restore_incompatible_database",
            "ui/dialog/CreatePlaylistDialog.kt" to "create_sync_playlist_failed",
            "ui/dialog/ImportM3uDialog.kt" to "m3u_import_playlist_failed",
            "ui/menu/LyricsMenu.kt" to "lyrics_refetch_failed",
            "ui/component/LyricsCard.kt" to "fun saidSaved",
            "utils/NotSaved.kt" to "not_saved",
            "MainActivity.kt" to "play_search_failed",
            "playback/CastSession.kt" to "R.string.cast_lost",
            "ui/screens/settings/UpdateSettings.kt" to "update_check_failed",
        )
        val missing = told.filter { (path, words) ->
            val lines = source(path)
            val at = lines.indexOfFirst { words in it }
            assertTrue("$path no longer says $words", at >= 0)
            lines.subList((at - 8).coerceAtLeast(0), (at + 6).coerceAtMost(lines.size)).none { "FailureLog.record(" in it }
        }
        assertEquals("shown to the person, written nowhere the report reads", emptyList<Pair<String, String>>(), missing)
    }

    @Test
    fun `the actions that fail without a toast of their own record it too`() {
        // How many records each file has at least: its catches that the person sees the end of.
        val records = mapOf(
            "viewmodels/BackupRestoreViewModel.kt" to 3,
            "ui/screens/settings/fragments/BackupRestoreFrag.kt" to 2,
            "ui/dialog/ImportM3uDialog.kt" to 2,
            "recognition/RecognitionEngine.kt" to 2,
        )
        val short = records.filter { (path, least) -> source(path).count { "FailureLog.record(" in it } < least }
        assertEquals("fewer records than there are failures shown", emptyMap<String, Int>(), short)
    }
}

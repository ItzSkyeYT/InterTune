/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Pixel 5, 28 Nov 2026: a Metrolist backup picked under setup's "I have a backup", or under Restore in Backup and
 * restore, got the toast "Could not restore incompatible database" and nothing more, while Import from Metrolist takes
 * it. Restore now says what the file is, with the import offered for that same file. No restore runs on the JVM, so
 * this reads the source, as the restore's other tests do. How a Metrolist database is told is MetrolistImportTest's.
 */
class RestoreMetrolistTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private fun strings(path: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$path").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private val model = source("viewmodels/BackupRestoreViewModel.kt")

    @Test
    fun `a database Restore cannot open is told Metrolist's by the import's own test, before anything is touched`() {
        val refused = model.substringAfter("if (!databaseValidated) {").substringBefore("\n        }\n")
        assertTrue(refused, "if (databaseFound && madeByMetrolist(probeFile)) throw MetrolistBackupPicked()" in refused)
        assertTrue(refused, "return false" in refused)
        val made = model.substringAfter("private fun madeByMetrolist(file: File): Boolean").substringBefore("\n\n")
        assertTrue(made, "MetrolistBackup.Theirs(file).use { MetrolistImport.refusal(it) == null }" in made)
        assertTrue("a file that cannot be read is not Metrolist's", "getOrDefault(false)" in made)
        // The music, the live database and the settings are reached only past it.
        assertTrue(model.indexOf("madeByMetrolist(probeFile)") < model.indexOf("MusicService.alive()?.stopForRestore()"))
    }

    @Test
    fun `it offers the import for that same file in place of the toast, and records no fault`() {
        val failure = model.substringAfter("fun restore(uri: Uri) {").substringAfter("}.onFailure {").substringBefore("\n                }\n")
        val offer = failure.indexOf("if (it is MetrolistBackupPicked) {")
        assertTrue(failure, offer >= 0 && offer < failure.indexOf("reportException(it)"))
        val picked = failure.substring(offer).substringBefore("\n                    }\n")
        assertTrue(picked, "metrolist.value = Metrolist.FromRestore(uri)" in picked)
        assertTrue(picked, "return@onFailure" in picked)
        assertFalse(picked, "FailureLog.record(" in picked || "Toaster.show(" in picked)
    }

    @Test
    fun `Backup and restore and setup both show it, with Import from Metrolist as its button`() {
        val frag = source("ui/screens/settings/fragments/BackupRestoreFrag.kt")
        val dialogs = frag.substringAfter("fun MetrolistImportDialogs(").substringBefore("\n}\n")
        val offer = dialogs.substringAfter("is BackupRestoreViewModel.Metrolist.FromRestore ->").substringBefore("is BackupRestoreViewModel.Metrolist.Done")
        assertTrue(offer, "text = stringResource(R.string.restore_is_metrolist)" in offer)
        assertTrue(offer, "action = stringResource(R.string.metrolist_import)" in offer)
        assertTrue(offer, "onAction = { viewModel.readMetrolist(m.uri) }" in offer)
        assertTrue(offer, "onDismiss = { viewModel.closeMetrolist() }" in offer)
        // A restore from Backup and restore still waits for its question, and setup's starts at once, as its button's does.
        assertTrue("MetrolistImportDialogs(viewModel, restore = { toRestore = it })" in frag)
        for (setup in listOf("ui/screens/ShortSetup.kt", "ui/screens/SetupWizard.kt")) {
            assertTrue(setup, "MetrolistImportDialogs(backupRestoreViewModel, restore = { backupRestoreViewModel.restore(it) })" in source(setup))
        }
        // Setup's backup button waits while the import reads the file or brings the library over.
        assertEquals(2, Regex("""enabled = !restoring && !importing,\s+busy = restoring \|\| importing,""").findAll(source("ui/screens/ShortSetup.kt")).count())
    }

    @Test
    fun `a backup picked in Import from another app gets its own answer`() {
        val screen = source("ui/screens/settings/LibraryImportScreen.kt")
        assertTrue("ImportParse.Problem.BACKUP -> stringResource(R.string.import_library_error_backup)" in screen)
    }

    @Test
    fun `the words, in English and French, name the buttons as the app does`() {
        val en = strings("values/strings-ot.xml") + strings("values/strings.xml")
        val fr = strings("values-fr/strings-ot.xml") + strings("values-fr/strings.xml")
        assertEquals("This is a backup from Metrolist. Import it to bring over your likes, playlists and plays.", en["restore_is_metrolist"])
        assertEquals("C'est une sauvegarde de Metrolist. Importez-la pour récupérer vos j'aime, vos playlists et vos écoutes.", fr["restore_is_metrolist"])
        assertEquals(
            "That file is a backup, not a list of songs. Use Restore, or Import from Metrolist for one from Metrolist.",
            en["import_library_error_backup"],
        )
        assertEquals(
            "Ce fichier est une sauvegarde, pas une liste de titres. Utilisez Restaurer, ou Importer depuis Metrolist pour une sauvegarde Metrolist.",
            fr["import_library_error_backup"],
        )
        for (words in listOf(en, fr)) {
            val text = words.getValue("import_library_error_backup")
            assertTrue(text, words.getValue("action_restore") in text && words.getValue("metrolist_import") in text)
            for (key in listOf("restore_is_metrolist", "import_library_error_backup")) {
                val line = words.getValue(key)
                assertEquals(line, 2, Regex("""[.!?](?=\s|$)""").findAll(line).count())
                assertFalse(line, '—' in line || '–' in line)
            }
        }
    }
}

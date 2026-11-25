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
 * dvd's OnePlus 13R showed none of his backups in Restore's file picker, in the Tester app and the released one, right
 * after he had uninstalled the app to come back from one (#testers, 24 Nov 2026): the picker asked for
 * application/octet-stream, and his phone gave .backup files another type. Every picker for a backup now shows every
 * file; what is picked is read in full before anything is touched.
 */
class BackupPickerTest {
    private val files = listOf(
        "ui/screens/settings/fragments/BackupRestoreFrag.kt",
        "ui/screens/SetupWizard.kt",
        "ui/screens/ShortSetup.kt",
    ).associateWith { File("src/main/java/com/dd3boh/outertune/$it").readText() }

    @Test
    fun `a backup is picked from every file, whatever type the phone gives it`() {
        assertEquals(listOf("*/*"), BackupRestoreViewModel.PICK_BACKUP.toList())
        for ((name, code) in files) {
            assertFalse(name, Regex("""(restoreLauncher|metrolistLauncher)\.launch\(arrayOf\(""").containsMatchIn(code))
            assertFalse(name, "backupTypes" in code)
        }
        assertEquals(2, Regex("""restoreLauncher\.launch\(BackupRestoreViewModel\.PICK_BACKUP\)""").findAll(files.getValue("ui/screens/ShortSetup.kt")).count())
        assertTrue("restoreLauncher.launch(BackupRestoreViewModel.PICK_BACKUP)" in files.getValue("ui/screens/SetupWizard.kt"))
        val settings = files.getValue("ui/screens/settings/fragments/BackupRestoreFrag.kt")
        assertTrue("restoreLauncher.launch(BackupRestoreViewModel.PICK_BACKUP)" in settings)
        assertEquals(2, Regex("""metrolistLauncher\.launch\(BackupRestoreViewModel\.PICK_BACKUP\)""").findAll(settings).count())
    }

    @Test
    fun `whatever is picked is read in full before anything is touched`() {
        val model = File("src/main/java/com/dd3boh/outertune/viewmodels/BackupRestoreViewModel.kt").readText()
        val restore = model.substringAfter("fun restore(uri: Uri) {").substringBefore("\n    }\n")
        assertTrue(restore, "restoreValidatedDatabase(uri)" in restore)
    }
}

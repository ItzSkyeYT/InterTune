/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.utils.abortedMeansBlocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Samsung's Auto Blocker lets the person tap Update, then drops the install and answers "aborted",
 * as for a cancel, so the update prompt came back as if nothing had happened. These hold the rules
 * that turn that answer into a prompt that says why and what to do.
 */
class UpdateBlockedTest {

    @Test
    fun `Auto Blocker's abort after the person tapped Update is a block, a cancel is not`() {
        // What a Galaxy S25 Ultra answered with Auto Blocker on, 19 Oct 2026.
        assertTrue(abortedMeansBlocked("INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package"))
        // What Android says when the person taps Cancel.
        assertFalse(abortedMeansBlocked("User rejected permissions"))
        assertFalse(abortedMeansBlocked(null))
    }

    @Test
    fun `a blocked install names Auto Blocker only on a phone that has it`() {
        assertEquals(
            UpdateTrouble.BLOCKED_SAMSUNG,
            updateTrouble(fromFdroid = false, blocked = true, failed = false, samsung = true),
        )
        assertEquals(
            UpdateTrouble.BLOCKED,
            updateTrouble(fromFdroid = false, blocked = true, failed = false, samsung = false),
        )
    }

    @Test
    fun `a failure with a reason is shown, and nothing when all is well`() {
        assertEquals(
            UpdateTrouble.FAILED,
            updateTrouble(fromFdroid = false, blocked = false, failed = true, samsung = false),
        )
        assertNull(updateTrouble(fromFdroid = false, blocked = false, failed = false, samsung = true))
    }

    @Test
    fun `an F-Droid install is left to F-Droid, whatever the phone blocks`() {
        assertNull(updateTrouble(fromFdroid = true, blocked = true, failed = false, samsung = true))
    }

    @Test
    fun `no timer abandons an install, the prompt waits for the app to be in front, and the apk is kept`() {
        val installer = File("src/main/java/com/dd3boh/outertune/utils/UpdateInstaller.kt").readText()
        // A ten second watchdog abandoned installs Android was still checking after the person
        // tapped Update, and ones shown in a pop-up window that never paused the app.
        assertFalse(installer.contains("delay("))
        // Started from behind, Android drops the confirmation without a word.
        assertTrue(installer.contains("pendingConfirm = confirm"))
        assertTrue(installer.contains("if (!inFront) return"))
        // A blocked or cancelled install is tried again without fetching the apk again.
        assertFalse(installer.contains("// The session holds its own copy now, so the cached file is no longer needed."))
        assertTrue(installer.contains("PackageInstaller.STATUS_FAILURE_BLOCKED"))
        val settings = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/UpdateSettings.kt").readText()
        assertFalse("Settings used to fetch the apk again even when it was already here", settings.contains("downloadAndInstall"))
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue(main.contains("updateInstaller.onShown()"))
        assertTrue(main.contains("updateInstaller.onHidden()"))
        assertTrue("Later and Skip stop a download under way", main.contains("if (updateInstaller.isBusy) updateInstaller.cancel()"))
    }
}

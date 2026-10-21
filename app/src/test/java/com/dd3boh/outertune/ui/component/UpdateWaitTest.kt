/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.UpdateInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's window says nothing once its button is tapped, and the app said nothing either, so
 * people tapped Install again and again, and each tap started the install over. From the tap on
 * Install the update dialog now says what is happening. These hold which form it takes, what it
 * says and which buttons it has, and that none of them installs.
 */
class UpdateWaitTest {

    private val shown = UpdateInstaller.State.AwaitingConfirmation(shownAt = 1_000L)

    @Test
    fun `the copy, and the moment before Android's window opens, say the update is handed over`() {
        assertEquals(UpdateWaitPhase.PREPARING, updateWaitPhase(UpdateInstaller.State.Preparing, sinceWindowMs = 0))
        assertEquals(UpdateWaitPhase.PREPARING, updateWaitPhase(UpdateInstaller.State.AwaitingConfirmation(), sinceWindowMs = 60_000))
    }

    @Test
    fun `just after Android's window, nothing is said until its answer is heard, then the wait is`() {
        assertEquals(UpdateWaitPhase.SETTLING, updateWaitPhase(shown, sinceWindowMs = 0))
        assertEquals(UpdateWaitPhase.SETTLING, updateWaitPhase(shown, sinceWindowMs = UPDATE_SETTLE_MS - 1))
        assertEquals(UpdateWaitPhase.WAITING, updateWaitPhase(shown, sinceWindowMs = UPDATE_SETTLE_MS))
        assertEquals(UpdateWaitPhase.WAITING, updateWaitPhase(shown, sinceWindowMs = 3_600_000))
    }

    @Test
    fun `installing says so, however long since the window`() {
        assertEquals(UpdateWaitPhase.INSTALLING, updateWaitPhase(UpdateInstaller.State.Installing, sinceWindowMs = 0))
        assertEquals(UpdateWaitPhase.INSTALLING, updateWaitPhase(UpdateInstaller.State.Installing, sinceWindowMs = 600_000))
    }

    @Test
    fun `the offer, a download and every end are the prompt's own`() {
        for (state in listOf(
            UpdateInstaller.State.Idle,
            // A background download nobody tapped is one of these, and never reaches Android.
            UpdateInstaller.State.Downloading(10, 100),
            UpdateInstaller.State.NeedsPermission,
            UpdateInstaller.State.Failed("no answer from Android"),
            UpdateInstaller.State.Blocked,
        )) {
            assertNull("$state", updateWaitPhase(state, sinceWindowMs = 60_000))
        }
    }

    @Test
    fun `only the wait has buttons, and none of them installs`() {
        assertEquals(
            listOf(UpdateWaitButton.SHOW_WINDOW, UpdateWaitButton.STOP),
            updateWaitButtons(UpdateWaitPhase.WAITING),
        )
        for (phase in listOf(UpdateWaitPhase.PREPARING, UpdateWaitPhase.SETTLING, UpdateWaitPhase.INSTALLING)) {
            assertTrue("$phase", updateWaitButtons(phase).isEmpty())
        }
        assertTrue(UpdateWaitButton.entries.none { "INSTALL" in it.name })
    }

    @Test
    fun `Back and a tap outside put the wait aside, and close nothing else`() {
        assertTrue(updateWaitDismissable(UpdateWaitPhase.WAITING))
        for (phase in listOf(UpdateWaitPhase.PREPARING, UpdateWaitPhase.SETTLING, UpdateWaitPhase.INSTALLING)) {
            assertFalse("$phase", updateWaitDismissable(phase))
        }
    }

    @Test
    fun `each phase has its own line, but the moment after the window`() {
        assertNull(updateWaitLine(UpdateWaitPhase.SETTLING))
        val lines = listOf(UpdateWaitPhase.PREPARING, UpdateWaitPhase.WAITING, UpdateWaitPhase.INSTALLING)
            .map { assertNotNull("$it", updateWaitLine(it)); updateWaitLine(it) }
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test
    fun `Android's button is named as the phone shows it`() {
        // PackageInstallerActivity keeps "Install" for an update up to Android 11 and says "Update"
        // from Android 12 (AOSP, read 21 Oct 2026).
        assertEquals(R.string.update_wait_android_install, androidUpdateButton(24))
        assertEquals(R.string.update_wait_android_install, androidUpdateButton(30))
        assertEquals(R.string.update_wait_android_update, androidUpdateButton(31))
        assertEquals(R.string.update_wait_android_update, androidUpdateButton(36))
    }

    @Test
    fun `the busy form gets no Install button, the prompt turns into it, and it shows whatever the snooze`() {
        val prompt = File("src/main/java/com/dd3boh/outertune/ui/component/UpdatePrompt.kt").readText()
        val buttons = prompt.substringAfter("confirmButton = {").substringBefore("dismissButton = null")
        val busy = buttons.indexOf("if (busy != null) {")
        assertTrue("the busy buttons come first", busy in 0 until buttons.indexOf("onInstall()"))
        assertTrue(buttons.substring(busy).substringBefore("}").contains("return@Column"))
        // One AlertDialog for both, so the window stays and only its insides change.
        assertEquals(1, Regex("""\bAlertDialog\(""").findAll(prompt).count())
        assertTrue("dismissOnBackPress = dismissable" in prompt)

        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("busy = busyPhase" in main)
        val visible = main.substringAfter("val updatePromptVisible = ").substringBefore("pendingUpdate?.let")
        assertTrue("the busy form does not wait for the snooze", visible.indexOf("busyPhase != null") in 0 until visible.indexOf("!snoozed"))
    }

    @Test
    fun `the Settings row hands a busy install back to the installer, never to a new one`() {
        val settings = File("src/main/java/com/dd3boh/outertune/ui/screens/settings/UpdateSettings.kt").readText()
        val busy = settings.substringAfter("is UpdateInstaller.State.AwaitingConfirmation, UpdateInstaller.State.Installing ->")
            .substringBefore("UpdateInstaller.State.NeedsPermission ->")
        assertTrue(busy.trim().startsWith("installer.installOrDownload(found.downloadUrl, found.sizeBytes)"))
        assertFalse("cancel()" in busy)
        assertTrue("UpdateInstaller.State.Preparing -> Unit" in settings)
    }

    @Test
    fun `the words are in English and French, short, and without a dash`() {
        val names = listOf(
            "update_wait_title", "update_wait_preparing", "update_wait_waiting", "update_wait_android_update",
            "update_wait_android_install", "update_wait_installing", "update_wait_show_window", "update_wait_stop",
        )
        for (folder in listOf("values", "values-fr")) {
            val texts = Regex("<string name=\"(update_wait_[a-z_]+)\">(.*?)</string>")
                .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals(folder, names.toSet(), texts.keys)
            for ((name, text) in texts) {
                assertFalse("$folder $name", '\u2014' in text || '\u2013' in text)
                assertTrue("$folder $name", Regex("[.!?](?=\\s|$)").findAll(text).count() <= 3)
            }
            // Android's button, named as in the app's other texts that name a button.
            val waiting = texts.getValue("update_wait_waiting")
            assertTrue(waiting, if (folder == "values") "\\\"%1\$s\\\"" in waiting else "« %1\$s »" in waiting)
        }
    }
}

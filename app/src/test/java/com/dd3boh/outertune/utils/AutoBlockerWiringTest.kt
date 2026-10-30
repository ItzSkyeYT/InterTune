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
 * The parts of the Auto Blocker return that no function can hold, read from the source as
 * UpdateBlockedTest does: that both ways in ask for the install to start again, that the app
 * leaving and coming back is what makes it so, that every refusal is noted by the build that sees
 * it, where the word afterwards is shown, and what both say. The rules themselves are in
 * AutoBlockerReturnTest.
 */
class AutoBlockerWiringTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val main = source("MainActivity.kt")
    private val installer = source("utils/UpdateInstaller.kt")
    private val autoBlocker = source("utils/AutoBlocker.kt")

    @Test
    fun `Open Auto Blocker asks for the install to start again, in the dialog and in Settings`() {
        val dialog = main.substringAfter("onOpenAutoBlocker = {").substringBefore("onTryAgain")
        assertTrue(dialog.contains("AutoBlocker.openForUpdate(this@MainActivity)"))
        assertFalse(dialog.contains("AutoBlocker.open("))

        val settings = source("ui/screens/settings/UpdateSettings.kt")
        val row = settings.substringAfter("UpdateInstaller.State.Blocked ->").substringBefore("// The apk already downloaded")
        assertTrue(row.contains("AutoBlocker.openForUpdate(context)"))
        assertFalse(row.contains("AutoBlocker.open(context)"))
    }

    @Test
    fun `the tap is asked for only when a page opened, since there is nowhere to come back from otherwise`() {
        val open = autoBlocker.substringAfter("fun openForUpdate(context: Context) {").substringBefore("\n    }")
        assertTrue(open.contains("if (open(context)) AutoBlockerReturn.ask(context)"))
    }

    @Test
    fun `back in front the install starts again once, and only after the person has left`() {
        val observer = main.substringAfter("if (event == Lifecycle.Event.ON_RESUME) {").substringBefore("Lifecycle.Event.ON_PAUSE")
        assertTrue(observer.contains("updateInstaller.onShown()"))
        assertTrue(observer.contains("installAgainAfterAutoBlocker()"))

        // Leaving is what makes the next resume a return.
        val stop = main.substringAfter("override fun onStop() {").substringBefore("\n    }")
        assertTrue(stop.contains("AutoBlockerReturn.left()"))

        val again = main.substringAfter("private suspend fun installAgainAfterAutoBlocker() {").substringBefore("\n    }\n")
        // Asked of the settings first, so nearly every resume costs one look.
        assertTrue(again.indexOf("AutoBlockerReturnAtKey") in 0 until again.indexOf("updateChecker.found()"))
        // The tap is used up by the verdict, with the time and with whether the person left.
        assertTrue(again.contains("AutoBlockerReturn.take(store, System.currentTimeMillis(), AutoBlockerReturn.away)"))
        assertTrue(again.contains("verdict != ReturnVerdict.INSTALL_AGAIN"))
        // The way Try again does it: the apk that is there, or the download first.
        assertTrue(again.contains("updateInstaller.installOrDownload(found.downloadUrl, found.sizeBytes)"))
        // Never another settings screen opened by itself, and never on F-Droid.
        assertFalse(again.contains("startActivity"))
        assertFalse(again.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
        assertTrue(again.contains("InstallSource.F_DROID"))
        assertTrue(again.contains("canRequestInstall()"))
    }

    @Test
    fun `no retry is started from the installer or by a timer, and the apk is not asked for again`() {
        assertFalse("the ten second watchdog stays gone", installer.contains("delay("))
        assertFalse(installer.contains("AutoBlockerReturn.take"))
        assertFalse(installer.contains("AutoBlockerReturn.ask"))
    }

    @Test
    fun `every refusal goes through one place that notes it, on a Samsung, by the build that saw it`() {
        assertEquals("the state is set to Blocked in one place", 1, Regex("""_state\.value = State\.Blocked""").findAll(installer).count())
        assertTrue("the three refusals use it", Regex("""\bblocked\(\)""").findAll(installer).count() >= 4)
        val place = installer.substringAfter("private fun blocked() {").substringBefore("\n    }")
        assertTrue(place.contains("_state.value = State.Blocked"))
        assertTrue(place.contains("if (AutoBlocker.likely()) AutoBlockerReturn.noteBlock(context, BuildConfig.VERSION_CODE)"))
    }

    @Test
    fun `the Samsung pretence is for debug builds and nothing else`() {
        val pretend = autoBlocker.substringAfter("fun pretendFrom(context: Context) {").substringBefore("\n    }")
        assertTrue(pretend.contains("BuildConfig.DEBUG && File(context.filesDir, FAKE_SAMSUNG_FILE).exists()"))
        // Read once, at the start of the app, before anything asks.
        assertTrue(main.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {").take(400).contains("AutoBlocker.pretendFrom(this)"))
    }

    @Test
    fun `the word afterwards is a card on Home beside the welcome card, drawn once and forgotten as it is`() {
        val home = source("ui/screens/HomeScreen.kt")
        val welcome = home.indexOf("item(key = \"welcome_card\")")
        val note = home.indexOf("item(key = \"auto_blocker_note\")")
        val announcement = home.indexOf("item(key = \"announcement_banner\")")
        assertTrue("welcome card, then the note, then the announcement", welcome in 0 until note && note < announcement)
        val item = home.substring(note, announcement)
        assertTrue(item.contains("LaunchedEffect(Unit) { onAutoBlockerNoteShown() }"))
        assertTrue(item.contains("AutoBlockerNote("))

        // Latched up, so the card stays until it is answered, and drawn only where Auto Blocker exists.
        assertTrue(main.contains("reminderOwed(blockedBy, BuildConfig.VERSION_CODE) && AutoBlocker.likely()"))
        assertTrue(main.contains("var autoBlockerNoteUp by rememberSaveable { mutableStateOf(false) }"))
        // The graph of destinations is built again when what it is given changes: remembered, as the welcome card's are.
        for (name in listOf("autoBlockerNoteShown", "autoBlockerNoteOpen", "autoBlockerNoteDone")) {
            assertTrue(name, main.contains("val $name = remember {"))
        }
        assertTrue(main.contains("autoBlockerNote = { autoBlockerNoteUp },"))
        // Open Auto Blocker there only opens it: nothing is waiting for the person to come back.
        val open = main.substringAfter("val autoBlockerNoteOpen = remember {").substringBefore("val autoBlockerNoteDone")
        assertTrue(open.contains("AutoBlocker.open(this@MainActivity)"))
        assertFalse(open.contains("openForUpdate"))
    }

    private fun texts(folder: String): Map<String, String> =
        Regex("<string name=\"([a-z_]+)\">(.*?)</string>")
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    @Test
    fun `the message and the note are said as the owner wrote them in English`() {
        val english = texts("values")
        assertEquals(
            "The install did not go through: Samsung's Auto Blocker probably stopped it. Turn it off, then come back here " +
                    "and the install starts again by itself. You can turn Auto Blocker back on right after.",
            english.getValue("update_blocked_samsung"),
        )
        assertEquals(
            "Updated. If you turned Auto Blocker off for this, you can turn it back on now.",
            english.getValue("auto_blocker_note"),
        )
    }

    @Test
    fun `both are in French under the name the French already uses, short, and without a dash`() {
        val french = texts("values-fr")
        for (name in listOf("update_blocked_samsung", "auto_blocker_note")) {
            val text = french.getValue(name)
            assertTrue("$name in French keeps \"Bloqueur automatique\"", text.contains("Bloqueur automatique"))
            assertFalse("$name in French does not say Auto Blocker", text.contains("Auto Blocker"))
        }
        // The button that goes with them says it in French too.
        assertEquals("Ouvrir le Bloqueur automatique", french.getValue("update_open_auto_blocker"))
        for (folder in listOf("values", "values-fr")) {
            val all = texts(folder)
            for (name in listOf("update_blocked_samsung", "auto_blocker_note")) {
                val text = all.getValue(name)
                assertFalse("$folder $name", '\u2014' in text || '\u2013' in text)
                // Two or three short sentences at most.
                assertTrue("$folder $name", Regex("[.!?](?=\\s|$)").findAll(text).count() in 1..3)
            }
            assertTrue("$folder has the Done button's word", "action_done" in all)
        }
    }

    @Test
    fun `the note's buttons are the ones the app already words`() {
        val note = source("ui/component/AutoBlockerNote.kt")
        assertTrue(note.contains("R.string.update_open_auto_blocker"))
        assertTrue(note.contains("R.string.action_done"))
        assertTrue(note.contains("R.string.auto_blocker_note"))
    }
}

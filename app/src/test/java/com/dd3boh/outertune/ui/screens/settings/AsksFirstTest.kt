/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two rows of Settings wiped something at one tap: Restore replaced the library and the settings
 * as soon as a file was chosen, and Clear in Song recognition emptied up to 200 recognised songs.
 * Each now asks first, the way the Your data page asks. And Log out, which nobody needs asking
 * about since signing in again undoes it, left the sign-in page's cookies on the phone.
 *
 * This reads the source, as the other tests of these screens do: the screens are drawn by Compose.
 */
class AsksFirstTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private fun strings(folder: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }

    private fun sentences(text: String) = Regex("[.!?](?=\\s|$)").findAll(text).count()

    @Test
    fun `a restore waits for a yes once its file is chosen`() {
        val frag = source("ui/screens/settings/fragments/BackupRestoreFrag.kt")
        // The picker hands the file over to be asked about. It starts nothing.
        val picked = frag.substringAfter("val restoreLauncher = ").substringBefore("\n    }\n")
        assertTrue("toRestore = uri" in picked)
        assertFalse("viewModel.restore" in picked)
        // The one place a restore starts is the question's yes.
        assertEquals(1, Regex("""viewModel\.restore\(""").findAll(frag).count())
        val ask = frag.substringAfter("toRestore?.let { file ->").substringBefore("\n    }\n")
        assertTrue("AskFirstDialog(" in ask)
        assertTrue(Regex("""onConfirm = \{\s+toRestore = null\s+viewModel\.restore\(file\)\s+\}""").containsMatchIn(ask))
        assertTrue("onDismiss = { toRestore = null }" in ask)
    }

    @Test
    fun `the two notes about restoring stand under Restore, not at the foot of the page`() {
        val frag = source("ui/screens/settings/fragments/BackupRestoreFrag.kt")
        val afterRestore = frag.substringAfter("Tour.SETTING_RESTORE").substringBefore("Tour.SETTING_AUTO_BACKUP")
        assertTrue("InfoLabel(stringResource(R.string.import_innertune_tooltip))" in afterRestore)
        assertTrue("InfoLabel(stringResource(R.string.restore_lm_tooltip))" in afterRestore)
        assertFalse("_tooltip" in source("ui/screens/settings/BackupSettings.kt"))
    }

    @Test
    fun `Clear in Song recognition waits for a yes`() {
        val screen = source("ui/screens/settings/RecognitionSettings.kt")
        assertTrue("onClick = { askClear = true }," in screen)
        // What it empties is emptied in one place, the question's yes.
        assertEquals(1, Regex("""viewModel\.clearHistory\(\)""").findAll(screen).count())
        val ask = screen.substringAfter("if (askClear) {").substringBefore("\n        }\n")
        assertTrue("AskFirstDialog(" in ask)
        assertTrue("viewModel.reset(); viewModel.clearRecognised(); viewModel.clearHistory()" in ask.substringAfter("onConfirm = {"))
        assertTrue("onDismiss = { askClear = false }" in ask)
    }

    @Test
    fun `each question says what goes and that it cannot be undone, in English and in French`() {
        for (folder in listOf("values", "values-fr")) {
            val texts = strings(folder)
            for (name in listOf("restore_ask", "recognise_clear_ask")) {
                assertTrue("$folder $name", texts.getValue("${name}_title").trim().endsWith("?"))
                val text = texts.getValue("${name}_text")
                assertEquals("$folder: $text", 2, sentences(text))
                assertFalse('—' in text || '–' in text)
            }
        }
        val english = strings("values")
        assertTrue("This cannot be undone." in english.getValue("restore_ask_text"))
        assertTrue("This cannot be undone." in english.getValue("recognise_clear_ask_text"))
        assertTrue("settings" in english.getValue("restore_ask_text") && "restarts" in english.getValue("restore_ask_text"))
    }

    @Test
    fun `logging out clears the sign-in page's cookies as well as what the app stored`() {
        val app = source("App.kt")
        val forget = app.substringAfter("fun forgetAccount(context: Context) {").substringBefore("\n        }\n")
        assertTrue("settings.remove(InnerTubeCookieKey)" in forget)
        assertTrue("cookies.removeAllCookies { cookies.flush() }" in forget)
        // From any thread, since one of its two callers is not the main one, and never fatal.
        assertTrue("Handler(Looper.getMainLooper()).post {" in forget && "runCatching {" in forget)
    }
}

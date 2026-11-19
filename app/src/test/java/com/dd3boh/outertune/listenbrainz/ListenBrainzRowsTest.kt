/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ListenBrainz card in Account and sync, read from the source as the other settings tests
 * read theirs: where it is and what holds it back, its three states, the check before a token is
 * kept, and its texts, held from translators with their French. Its search entries are
 * SettingsIndexTest's, the length of its (i) ExplanationsStayShortTest's.
 */
class ListenBrainzRowsTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun fileCode(path: String) = code(File(sources, path).readText())

    private val frag = fileCode("ui/screens/settings/fragments/ListenBrainzFrag.kt")
    private val screen = fileCode("ui/screens/settings/AccountSyncSettings.kt")
    private val sender = fileCode("listenbrainz/ListenBrainzSender.kt")

    private fun strings(folder: String): Map<String, String> =
        Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/$folder/strings-ot.xml").readText())
            .associate { it.groupValues[1] to (it.groupValues[2] + "|" + it.groupValues[3]) }

    @Test
    fun `the card is under Last_fm's, and only with the flag`() {
        val held = screen.substringAfter("if (Unreleased.LISTENBRAINZ) {").substringBefore("PreferenceGroupTitle(\n            title = stringResource(R.string.grp_sync)")
        assertTrue("ListenBrainzFrag()" in held)
        assertTrue("R.string.grp_listenbrainz" in held)
        assertTrue(screen.indexOf("LastFmFrag(navController)") < screen.indexOf("ListenBrainzFrag()"))
        assertEquals(1, Regex("""ListenBrainzFrag\(\)""").findAll(screen).count())
    }

    @Test
    fun `three states - not connected, connected, and a token refused`() {
        val notConnected = frag.substringAfter("if (token.isBlank()) {").substringBefore("return")
        assertTrue("R.string.listenbrainz_connect)" in notConnected)
        assertTrue("connecting = true" in notConnected)
        val refused = frag.substringAfter("if (refusedAt > 0L) {").substringBefore("} else {")
        assertTrue("R.string.listenbrainz_refused)" in refused)
        // A refused token leads back to the dialog, with the plays still waiting.
        assertTrue("connecting = true" in refused)
        val connected = frag.substringAfter("} else {").substringBefore("ExplainedSwitchPreference(")
        assertTrue("R.string.listenbrainz_connected_as, user" in connected)
        assertTrue("sender.disconnect()" in connected)
        // The switch and its (i), once connected either way.
        val switch = frag.substringAfter("ExplainedSwitchPreference(").substringBefore("\n    )")
        assertTrue("R.string.listenbrainz_scrobble_explain" in switch)
        assertTrue("ListenBrainzScrobbleKey" in frag)
    }

    @Test
    fun `the token is never shown once connected, nor logged`() {
        assertFalse(Regex("""Text\(\s*token""").containsMatchIn(frag))
        assertFalse(Regex("""stringResource\([^)]*\btoken\b""").containsMatchIn(frag))
        assertFalse("Log." in frag)
    }

    @Test
    fun `a token is asked about before it is kept, and only kept when ListenBrainz knows it`() {
        val connect = sender.substringAfter("suspend fun connect(").substringBefore("suspend fun disconnect(")
        val asked = connect.indexOf("client.validateToken(")
        val kept = connect.indexOf("it[ListenBrainzTokenKey] = key")
        assertTrue(asked in 0 until kept)
        assertTrue("if (!check.valid) return@onSuccess" in connect)
        // The dialog closes on a token ListenBrainz knows and says which of the two went wrong otherwise.
        val dialog = frag.substringAfter("private fun ListenBrainzConnectDialog(")
        assertTrue("check.valid -> onDismiss()" in dialog)
        assertTrue("check == null -> problem = R.string.listenbrainz_unreachable" in dialog)
        assertTrue("else -> problem = R.string.listenbrainz_token_unknown" in dialog)
        assertTrue("Links.LISTENBRAINZ_SETTINGS" in dialog)
        // Read whole: code() cuts a line at its first //, which an address has.
        assertTrue("const val LISTENBRAINZ_SETTINGS = \"https://listenbrainz.org/settings/\"" in File(sources, "constants/Links.kt").readText())
    }

    @Test
    fun `disconnecting forgets the token, the name and the plays waiting`() {
        val disconnect = sender.substringAfter("suspend fun disconnect(").substringBefore("private suspend fun refused(")
        for (forgotten in listOf("it.remove(ListenBrainzTokenKey)", "it.remove(ListenBrainzUserKey)", "outbox.clear()")) {
            assertTrue(forgotten, forgotten in disconnect)
        }
    }

    @Test
    fun `every text it uses is held from translators, with its French, and its search words are open as every search word is`() {
        val english = strings("values")
        val french = strings("values-fr")
        val used = Regex("""R\.string\.(\w+)""").findAll(frag + screen).map { it.groupValues[1] }
            .filter { it.startsWith("listenbrainz") || it == "grp_listenbrainz" }.toSet()
        assertTrue(used.size >= 14)
        for (name in used) {
            for ((folder, texts) in listOf("values" to english, "values-fr" to french)) {
                val text = texts[name] ?: error("$name is not in $folder")
                assertTrue("$name is open to translators in $folder", "translatable=\"false\"" in text.substringBefore("|"))
            }
        }
        // Settings' search words are open to translators even for a held feature (SettingsSearchTest on the 0.13
        // line, varispeed's the same): they are only words to find a row by, and say nothing a release has not got.
        for ((folder, texts) in listOf("values" to english, "values-fr" to french)) {
            val text = texts["search_words_listenbrainz"] ?: error("search_words_listenbrainz is not in $folder")
            assertFalse("search_words_listenbrainz is held back in $folder", "translatable=\"false\"" in text.substringBefore("|"))
        }
        assertTrue("%1\$s" in french.getValue("listenbrainz_connected_as"))
    }
}

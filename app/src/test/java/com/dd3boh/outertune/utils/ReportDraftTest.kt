/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The words typed on Report a problem or the crash page stay until a report is sent (frances on
 * Discord, 4 Nov 2026: "i wish it would save the text you've written when submitting a problem
 * report until you submit it"), and only on this phone. See ReportDraft.
 */
class ReportDraftTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val keeperScopes = mutableListOf<CoroutineScope>()

    /** One file, as one install has: a second keeper on it is the app started again. */
    private val store: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) { dir.root.resolve("${ReportDraft.FILE}.preferences_pb") }
    }

    private fun keeper(settleMs: Long = 50L) =
        DraftKeeper(store, CoroutineScope(Dispatchers.IO + SupervisorJob()).also { keeperScopes += it }, settleMs)

    private fun inFile(): Map<String, String> = runBlocking {
        store.data.first().asMap().mapKeys { it.key.name }.mapValues { it.value.toString() }
    }

    /** Waits for the file to hold [expected], two seconds at most. */
    private fun fileComesTo(expected: Map<String, String>) {
        val end = System.currentTimeMillis() + 2_000
        while (inFile() != expected && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(expected, inFile())
    }

    @After
    fun close() {
        keeperScopes.forEach { it.cancel() }
        storeScope.cancel()
    }

    @Test
    fun `what was typed comes back when the page opens again, in this run and after a restart`() = runBlocking {
        val first = keeper()
        first.keep(DraftKeeper.Draft("The song stopped when the screen went off", "frances on Discord"))
        // Closed and opened again at once: the words are there before they reach the file.
        assertEquals(DraftKeeper.Draft("The song stopped when the screen went off", "frances on Discord"), first.read())
        fileComesTo(mapOf("what" to "The song stopped when the screen went off", "contact" to "frances on Discord"))
        // The app closed and started again: read from the file.
        assertEquals(DraftKeeper.Draft("The song stopped when the screen went off", "frances on Discord"), keeper().read())
    }

    @Test
    fun `typing is written once it pauses, and the newest words are what stays`() {
        val keeper = keeper(settleMs = 200L)
        val words = "It stops at every song"
        for (n in 1..words.length) keeper.keep(DraftKeeper.Draft(words.take(n), ""))
        fileComesTo(mapOf("what" to words))
    }

    @Test
    fun `once sent, the words are gone, from memory and from the file`() = runBlocking {
        val keeper = keeper()
        keeper.keep(DraftKeeper.Draft("It skipped nothing", "me@example.org"))
        fileComesTo(mapOf("what" to "It skipped nothing", "contact" to "me@example.org"))
        keeper.sent()
        assertEquals(DraftKeeper.Draft(), keeper.read())
        assertEquals(emptyMap<String, String>(), inFile())
        assertEquals(DraftKeeper.Draft(), keeper().read())
    }

    @Test
    fun `words still waiting to be written never land after the report that took them was sent`() = runBlocking {
        val keeper = keeper(settleMs = 150L)
        keeper.keep(DraftKeeper.Draft("typed just before Send", ""))
        keeper.sent()
        // Past the moment the waiting words would have been written.
        Thread.sleep(500)
        assertEquals(emptyMap<String, String>(), inFile())
        assertEquals(DraftKeeper.Draft(), keeper.read())
    }

    @Test
    fun `boxes emptied by hand leave nothing in the file`() {
        val keeper = keeper()
        keeper.keep(DraftKeeper.Draft("something", "someone"))
        fileComesTo(mapOf("what" to "something", "contact" to "someone"))
        keeper.keep(DraftKeeper.Draft("", "  "))
        fileComesTo(emptyMap())
    }

    @Test
    fun `a file that cannot be read is no draft, and nothing breaks`() = runBlocking {
        dir.root.resolve("${ReportDraft.FILE}.preferences_pb").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(DraftKeeper.Draft(), keeper().read())
    }

    // ---- where it is kept, and where it is not ----

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `it stays on the phone, out of the cloud backup, out of a move to a new phone, and out of the app's own backups`() {
        val file = "datastore/${ReportDraft.FILE}.preferences_pb"
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        fun excludes(section: String, path: String) = Regex("""<exclude\s+domain="file"\s+path="${Regex.escape(path)}"\s*/>""").containsMatchIn(section)
        assertTrue("cloud backup", excludes(cloud, "datastore/") || excludes(cloud, file))
        assertTrue("a move to a new phone", excludes(transfer, "datastore/") || excludes(transfer, file))
        val old = File("src/main/res/xml/backup_rules.xml").readText()
        assertTrue("Android 11 and earlier", excludes(old, "datastore/") || excludes(old, file))
        // The app's own backup holds the settings file and the library, and nothing of this one.
        assertFalse(source("utils/BackupWriter.kt").contains("ReportDraft"))
        assertTrue("its own file, not the settings", source("utils/ReportDraft.kt").contains("preferencesDataStore(name = FILE)"))
    }

    @Test
    fun `nothing of it goes to the log`() {
        val draft = source("utils/ReportDraft.kt") + source("ui/component/ReportWords.kt")
        assertFalse(draft.contains("Log."))
        for (page in listOf("ui/component/ProblemReportDialog.kt", "ui/component/CrashReportDialog.kt")) {
            val logged = Regex("""Log\.[a-z]\(.*\)""").findAll(source(page)).map { it.value }.toList()
            assertTrue("$page logs the words: $logged", logged.none { "words." in it })
        }
    }

    @Test
    fun `both pages bring the words back, keep them as they are typed, and let them go only when a report was sent`() {
        for (page in listOf("ui/component/ProblemReportDialog.kt", "ui/component/CrashReportDialog.kt")) {
            val text = source(page)
            assertTrue("$page: no kept words", text.contains("val words = rememberReportWords()"))
            assertTrue("$page: the box is not the kept words", text.contains("value = words.what,") && text.contains("onValueChange = words::typeWhat,"))
            assertTrue("$page: the contact is not the kept words", text.contains("value = words.contact,") && text.contains("onValueChange = words::typeContact,"))
            assertFalse("$page: words of its own that are not kept", text.contains("rememberSaveable { mutableStateOf(\"\") }"))
            // Filed or held by the site, never when it failed: the words are still wanted then.
            val sending = text.substringAfter("ReportRelay.send(").substringBefore("outcome = result")
            assertTrue("$page: cleared only after a send that did not fail", sending.contains("if (result is ReportRelay.Outcome.Failed) Log.w(") && sending.contains("else words.sent()"))
            assertEquals("$page: cleared somewhere else too", 1, Regex("""words\.sent\(\)""").findAll(text).count())
            // Shown once they are back, so that nothing typed is replaced by them.
            assertTrue("$page: the boxes are shown before the words are back", text.contains("words.back)"))
        }
    }
}

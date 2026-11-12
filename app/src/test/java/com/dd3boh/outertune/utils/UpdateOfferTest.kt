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
 * Skip this version quiets the checks that run by themselves, and nothing else: the check the person
 * starts in Settings > Updates offers that version again, with the same offer and buttons as for any
 * other. The rule is [shouldOffer]; the rest is read from the source, since the checker needs a
 * phone's storage and network, as UpdateBlockedTest and AutoBlockerWiringTest do for the same files.
 */
class UpdateOfferTest {

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    private val checker = source("utils/UpdateChecker.kt")
    private val settings = source("ui/screens/settings/UpdateSettings.kt")

    // The rule. 104 is the build that is running, 105 the skipped release, 106 a later one.

    @Test
    fun `a newer version nobody skipped is offered by every check`() {
        for (manual in listOf(false, true)) {
            assertTrue("manual $manual", shouldOffer(latestCode = 105, installedCode = 104, skippedCode = -1, manual = manual))
        }
    }

    @Test
    fun `the skipped version is held back from the checks that run by themselves`() {
        assertFalse(shouldOffer(latestCode = 105, installedCode = 104, skippedCode = 105, manual = false))
    }

    @Test
    fun `a check the person starts offers the skipped version again`() {
        assertTrue(shouldOffer(latestCode = 105, installedCode = 104, skippedCode = 105, manual = true))
    }

    @Test
    fun `a version newer than the skipped one is offered either way`() {
        for (manual in listOf(false, true)) {
            assertTrue("manual $manual", shouldOffer(latestCode = 106, installedCode = 104, skippedCode = 105, manual = manual))
        }
    }

    @Test
    fun `a skip of some other version hides nothing`() {
        for (manual in listOf(false, true)) {
            assertTrue("older skip, manual $manual", shouldOffer(latestCode = 105, installedCode = 104, skippedCode = 103, manual = manual))
            assertTrue("later skip, manual $manual", shouldOffer(latestCode = 105, installedCode = 104, skippedCode = 107, manual = manual))
        }
    }

    @Test
    fun `a version no newer than the one running is never offered, skipped or not, asked for or not`() {
        for (manual in listOf(false, true)) {
            for (skipped in listOf(-1, 103, 104, 105)) {
                assertFalse("same, skipped $skipped, manual $manual", shouldOffer(latestCode = 104, installedCode = 104, skippedCode = skipped, manual = manual))
                assertFalse("older, skipped $skipped, manual $manual", shouldOffer(latestCode = 103, installedCode = 104, skippedCode = skipped, manual = manual))
            }
        }
    }

    // The wiring.

    @Test
    fun `the release just fetched and the copy kept on disk are judged by the one rule`() {
        // A flag of its own that changes only what is offered: force is still the opt in's and the
        // rate limit's, and every caller that does not say manual is asking as it always did.
        assertTrue(checker.contains("suspend fun check(force: Boolean = false, manual: Boolean = false): Update?"))

        val fetched = checker.substringAfter("suspend fun check(").substringBefore("suspend fun found()")
        assertTrue(fetched.contains("shouldOffer("))
        assertTrue("the person's ask reaches the rule", fetched.contains("manual)"))

        val kept = checker.substringAfter("private suspend fun restoreFromDisk(").substringBefore("private fun fakeUpdate()")
        assertTrue(kept.contains("shouldOffer("))
        assertTrue("what is read back from disk is never offered by hand", kept.contains("manual = false"))

        // No second, written-out comparison to drift from it.
        assertFalse(checker.contains("== store.get(DismissedUpdateCodeKey"))
        assertFalse(checker.contains("<= BuildConfig.VERSION_CODE"))
    }

    @Test
    fun `only the Check for updates row asks by hand`() {
        val row = settings.substringAfter("title = { Text(stringResource(R.string.check_for_update)) },").substringBefore("update?.let { found ->")
        assertTrue(row.contains("updateChecker.check(force = true, manual = true)"))

        // Not the checks on opening the app or in the background, nor the ones that follow saying
        // yes to updates (the switch, the home card, the setup and the catch up).
        val asks = mutableListOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            Regex("""\bcheck\(([^()]*)\)""").findAll(file.readText()).forEach { call ->
                if (Regex("""\bmanual\s*=\s*true""").containsMatchIn(call.groupValues[1])) asks += file.name
            }
        }
        assertEquals(listOf("UpdateSettings.kt"), asks)

        assertTrue(source("MainActivity.kt").contains("coroutineScope.launch { updateChecker.check() }"))
        assertTrue(source("utils/BackgroundChecks.kt").contains("deps.updateChecker().check()"))
    }

    @Test
    fun `offering the skipped version by hand leaves the skip in place for the checks that run by themselves`() {
        // Only dismiss() writes the skip, and nothing takes it away: a version is skipped until a
        // later one comes, or until it is installed and so is no longer newer than the build.
        val writes = mutableListOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            if (Regex("""\[DismissedUpdateCodeKey\]\s*=""").containsMatchIn(text) || text.contains("remove(DismissedUpdateCodeKey")) writes += file.name
        }
        assertEquals(listOf("UpdateChecker.kt"), writes)
        assertFalse(checker.contains("remove(DismissedUpdateCodeKey"))
        assertEquals(1, Regex("""\[DismissedUpdateCodeKey\]\s*=""").findAll(checker).count())
        val dismiss = checker.substringAfter("suspend fun dismiss(").substringBefore("private suspend fun clearAvailable()")
        assertTrue(dismiss.contains("it[DismissedUpdateCodeKey] = versionCode"))
    }
}

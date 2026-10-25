/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Worked out on this phone. Nothing was uploaded." The start screen says so, and these hold it
 * to that, read from the source as ReleasedFlagsTest reads the flags: the Recap's code talks to
 * nothing on the network, its one count goes through PollChecker as a banner's does
 * (RecapCountTest), and its tools for testing stay in debug builds.
 */
class RecapPrivacyTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so that a sentence naming something is not taken for code. */
    private fun code(file: File) = file.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val recapFiles = listOf("recap", "ui/screens/recap")
        .flatMap { File(sources, it).listFiles()?.toList().orEmpty() }
        .filter { it.extension == "kt" }

    @Test
    fun `nothing of the Recap talks to the network, the sample of a debug build aside`() {
        assertTrue("the Recap's files were not found", recapFiles.size >= 10)
        val network = listOf("okhttp3", "HttpURLConnection", "java.net.URL", "YouTube.", "Umami", "PollChecker", "Firebase")
        for (file in recapFiles.filter { it.name != "RecapSample.kt" }) {
            val text = code(file)
            for (word in network) assertFalse("${file.name} uses $word", word in text)
        }
    }

    @Test
    fun `its count is asked for in one place, when the story is opened`() {
        val callers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "PollChecker.kt" && "recapOpened(" in code(it) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("RecapViewModel.kt"), callers)
        // From the story's start screen, the moment it is opened: the Recap looked at.
        assertTrue("onOpened = { viewModel.opened(year) }" in code(File(sources, "ui/screens/recap/RecapEntry.kt")))
    }

    @Test
    fun `the made-up year is written in a debug build only, and never over a phone's own listening`() {
        val sample = code(File(sources, "recap/RecapSample.kt"))
        assertTrue("fun offered() = BuildConfig.BUILD_TYPE == \"debug\" && Unreleased.RECAP" in sample)
        assertTrue("if (!offered()) return null" in sample)
        // Only the shell may send the broadcast.
        assertTrue("Manifest.permission.DUMP" in sample)
        // Refused before anything is searched for or written.
        val refusal = sample.indexOf("if (existing > MOST_EXISTING_LISTENS)")
        assertTrue("the sample does not check what the database holds", refusal >= 0)
        assertTrue(refusal < sample.indexOf("ImportSearch.songs") && refusal < sample.indexOf("transactionNow"))
        assertEquals(50, RecapSample.MOST_EXISTING_LISTENS)
    }

    @Test
    fun `the made-up year is never counted`() {
        val sample = code(File(sources, "recap/RecapSample.kt"))
        // Written down as counted, for the year and the one before, after the refusal and before anything else is written.
        val counted = sample.indexOf("it[ViewedPollIdsKey] = it[ViewedPollIdsKey].orEmpty() + RecapCount.id(thisYear - 1) + RecapCount.id(thisYear)")
        assertTrue("the sample does not write its Recap down as counted", counted >= 0)
        assertTrue(sample.indexOf("if (existing > MOST_EXISTING_LISTENS)") < counted)
        assertTrue(counted < sample.indexOf("ImportSearch.songs") && counted < sample.indexOf("transactionNow"))
        // And the count is only ever sent for a first opening, which that record rules out.
        val poll = code(File(sources, "utils/PollChecker.kt"))
        assertTrue("if (!firstOpening(RecapCount.id(year)) || !Polls.isConfigured) return@withContext" in poll)
    }

    @Test
    fun `the developer's ways in are a debug build's only`() {
        val developer = code(File(sources, "ui/screens/settings/fragments/DeveloperFrag.kt"))
        assertTrue("if (BuildConfig.DEBUG && Unreleased.RECAP) {" in developer.lines().map { it.trim() })
        val entry = code(File(sources, "ui/screens/recap/RecapEntry.kt"))
        // "Today is the reveal day" counts in a debug build alone, for the pop-up and for the banner.
        assertEquals(2, Regex("""revealedEarly && BuildConfig\.DEBUG""").findAll(entry).count())
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A poll document put on a test phone (files/poll-stand-in.json) to try a question before it goes
 * out: read in debug and preview builds only, and while it is there nothing is sent, so a test answer
 * never lands among the real ones. This reads the source, as the flag tests do: BuildConfig.DEBUG is a
 * constant of the build and a test cannot turn it.
 */
class PollStandInTest {

    private val checker = File("src/main/java/com/dd3boh/outertune/utils/PollChecker.kt").readText()

    @Test
    fun `only a debug or preview build reads it`() {
        assertTrue(checker.contains("private fun standIn(): String? = if (!BuildConfig.DEBUG) null"))
        assertTrue(checker.contains("private const val STAND_IN = \"poll-stand-in.json\""))
        // Read in place of the gist, and only once the switches said yes.
        val check = checker.substringAfter("suspend fun check(").substringBefore("val last = store.get(LastPollFetchKey")
        assertTrue(check.indexOf("standIn()?.let { return@withContext pick(it) }") > check.indexOf("AnnouncementsEnabledKey, false)) {"))
    }

    @Test
    fun `while it is there no answer and no opening is sent`() {
        val answer = checker.substringAfter("suspend fun answer(").substringBefore("suspend fun viewed(")
        assertTrue(answer.indexOf("if (standIn() != null) {") in 0 until answer.indexOf("send(Polls.UMAMI_EVENT"))
        assertTrue(answer.substringAfter("if (standIn() != null) {").substringBefore("}").contains("return@withContext"))
        val viewed = checker.substringAfter("suspend fun viewed(").substringBefore("private fun send(")
        assertTrue(viewed.contains("if (!first || !Polls.isConfigured || standIn() != null) return@withContext"))
    }
}

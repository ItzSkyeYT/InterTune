/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Bluetooth signal log stays out of a release, and what two releases left behind is deleted.
 *
 * The log writes down every Bluetooth device the phone hears, with its address and its name. It
 * was a measurement for "Quieter as you walk away", and 0.11 and 0.11.1 showed its row to anyone
 * with that setting on, writing to a folder Android backs up. A JVM test cannot open Settings,
 * so the row, the folder and the call at start are read from the source, comments taken out, the
 * way PlayBookCallSitesTest does.
 */
class SignalLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the logs two releases left are deleted and nothing else is`() {
        val logs = listOf("rssi-1760000000000.csv", "rssi-1.csv").map { folder.newFile(it) }
        val others = listOf("rssi-notes.txt", "rssi-.csv", "my-rssi-1.csv", "rssi-12.csv.bak", "songs.csv")
            .map { folder.newFile(it) }
        val named = folder.newFolder("rssi-2.csv")

        ProximityProbe.forgetOldLogs(folder.root)

        logs.forEach { assertFalse("${it.name} is still there", it.exists()) }
        others.forEach { assertTrue("${it.name} was deleted", it.exists()) }
        assertTrue("a folder that happens to carry the name was deleted", named.isDirectory)
    }

    @Test
    fun `a folder that is missing is no error`() {
        ProximityProbe.forgetOldLogs(null)
        ProximityProbe.forgetOldLogs(File(folder.root, "not-there"))
    }

    @Test
    fun `the flag is on in a debug build only`() {
        val flags = source("constants/Unreleased.kt")
        assertTrue(
            "SIGNAL_LOG is not BuildConfig.DEBUG",
            Regex("""val SIGNAL_LOG\s*=\s*BuildConfig\.DEBUG\b""").containsMatchIn(flags),
        )
    }

    @Test
    fun `the row is behind the flag`() {
        val row = blockAfter(source("ui/screens/settings/fragments/PlayerFrag.kt"), "fun ColumnScope.ProximityVolumeFrag()")
        val gate = Regex("""if\s*\(\s*!Unreleased\.SIGNAL_LOG\s*\)\s*return""").find(row)?.range?.first ?: -1
        val probe = row.indexOf("ProximityProbe(")
        assertTrue("the setting no longer makes a ProximityProbe, so the order below proves nothing", probe >= 0)
        assertTrue("nothing returns before the row when SIGNAL_LOG is off", gate >= 0)
        assertTrue("the probe is made before the flag is read", gate < probe)
    }

    @Test
    fun `nothing else in the app makes a probe`() {
        val makers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ProximityProbe.kt" }
            .filter { "ProximityProbe(" in stripComments(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(listOf("PlayerFrag.kt"), makers)
    }

    @Test
    fun `the log is written to the cache, which no backup carries`() {
        val probe = source("playback/ProximityProbe.kt")
        assertFalse("the probe still reaches for the folder Android backs up", "getExternalFilesDir" in probe)
        assertTrue("the probe does not write to the cache on shared storage", "externalCacheDir" in probe)
    }

    @Test
    fun `the app deletes the old logs when it starts`() {
        val start = blockAfter(source("App.kt"), "override fun onCreate()")
        assertTrue(
            "onCreate does not hand the backed-up folder to forgetOldLogs",
            "ProximityProbe.forgetOldLogs(getExternalFilesDir(null))" in start,
        )
    }

    private fun source(path: String) = stripComments(File("src/main/java/com/dd3boh/outertune/$path").readText())

    /** The block that the one [head] in [text] opens, between its braces. */
    private fun blockAfter(text: String, head: String): String {
        val at = text.indexOf(head)
        assertTrue("no $head", at >= 0)
        assertEquals("more than one $head", -1, text.indexOf(head, at + 1))
        val open = text.indexOf('{', at)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        throw AssertionError("The block of $head never closes")
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing the app sends to the download service can close it when Android refuses the start.
 *
 * media3's senders start the service with a bare startService, which Android refuses while the app
 * is in the background (a BackgroundServiceStartNotAllowedException, an IllegalStateException).
 * Issue #65: the automatic scan's last step, resuming the downloads, ran on a worker thread after
 * the person had left for the update installer, and nothing there catches an exception.
 *
 * The guard itself is DownloadServiceSends, tested on the JVM in DownloadServiceSendsTest. This
 * test is the rule that every send goes through it, and that a resume it refused is sent again
 * when the app is back in front.
 */
class DownloadSendsGuardedTest {

    private val util = withoutComments(File("src/main/java/com/dd3boh/outertune/playback/DownloadUtil.kt").readText())
    private val activity = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()

    /** Comments blanked out, line breaks kept, so a position still has its line number. */
    private fun withoutComments(source: String): String {
        val blank = { m: MatchResult -> m.value.replace(Regex("[^\n]"), " ") }
        return source
            .replace(Regex("(?s)/\\*.*?\\*/"), blank)
            .replace(Regex("(?m)(^|\\s)//.*$"), blank)
    }

    private fun lineOf(code: String, at: Int) = code.substring(0, at).count { it == '\n' } + 1

    /** The braces of every lambda handed to the guard: sends.attempt("what") { ... } and sends.resume { ... }. */
    private fun guarded(code: String): List<IntRange> =
        Regex("""\bsends\.(?:attempt\([^)]*\)|resume)\s*\{""").findAll(code).map { call ->
            val open = call.range.last
            var depth = 0
            var close = open
            while (close < code.length) {
                if (code[close] == '{') depth++
                if (code[close] == '}' && --depth == 0) break
                close++
            }
            open..close
        }.toList()

    @Test
    fun `every message to the download service is sent through the guard`() {
        val sends = Regex("""\bDownloadService\s*\.\s*(?:send|start)\w*\s*\(""").findAll(util).map { it.range.first }.toList()
        assertTrue("DownloadUtil sends to the download service", sends.isNotEmpty())
        val inGuard = guarded(util)
        val bare = sends.filter { at -> inGuard.none { at in it } }.map { lineOf(util, it) }
        assertEquals("DownloadService sends outside sends.attempt { } and sends.resume { }, on lines", emptyList<Int>(), bare)
    }

    @Test
    fun `the service is started in no other way`() {
        assertFalse("startService", Regex("""\bstartService\s*\(""").containsMatchIn(util))
        assertFalse("startForegroundService", Regex("""\bstartForegroundService\s*\(""").containsMatchIn(util))
        assertFalse("ContextCompat.startForegroundService", "ContextCompat.startForegroundService" in util)
    }

    @Test
    fun `the resume at the start goes through the guard that remembers a refusal`() {
        assertTrue(
            "resumeDownloadsOnStart is sends.resume { DownloadService.sendResumeDownloads(...) }",
            Regex("""fun resumeDownloadsOnStart\(\)\s*(?:=|\{)\s*sends\.resume\s*\{\s*DownloadService\.sendResumeDownloads\(""").containsMatchIn(util),
        )
    }

    @Test
    fun `a resume that was refused is sent again when the app is in front`() {
        val onResume = activity.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        assertTrue(
            "onResume sends it, once Android has refused it",
            "if (DownloadUtil.resumeOwed) downloadUtil.resumeDownloadsOnStart()" in onResume,
        )
        // The flag is read off the class, before the lazily injected DownloadUtil is asked for:
        // the activity builds nothing at launch that no screen has asked for (StartupSingletonsTest).
        assertTrue(
            "the flag comes first",
            onResume.indexOf("DownloadUtil.resumeOwed") < onResume.indexOf("downloadUtil.resumeDownloadsOnStart()"),
        )
        assertFalse("not read off the instance", "downloadUtil.resumeOwed" in activity)
    }
}

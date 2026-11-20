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
 * How ListenBrainz is wired into playing, read from the source as the other wiring tests do, since
 * no player runs on the JVM: playing now at a song's first sound, a counted play handed to both
 * services on the same rule, kept before it is sent, and a token that never reaches a log.
 */
class ListenBrainzWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun fileCode(path: String) = code(File(sources, path).readText())

    private val service = fileCode("playback/MusicService.kt")
    private val scrobbler = fileCode("utils/Scrobbler.kt")
    private val sender = fileCode("listenbrainz/ListenBrainzSender.kt")

    private fun body(text: String, head: String): String {
        val start = text.indexOf(head)
        assertTrue("no $head", start >= 0)
        var depth = 0
        var i = text.indexOf('{', start)
        val open = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("$head never closes")
    }

    @Test
    fun `playing now goes at a song's first sound, not at every change of song`() {
        val open = body(service, "private fun openListen(")
        assertTrue("scrobbler.nowPlaying(" in open)
        // After the paused history's early return: nothing goes while it is on.
        assertTrue(open.indexOf("if (listenHistoryPaused)") < open.indexOf("scrobbler.nowPlaying("))
        val transition = body(service, "override fun onMediaItemTransition(")
        assertFalse("scrobbler.nowPlaying(" in transition)
        assertEquals(1, Regex("""scrobbler\.nowPlaying\(""").findAll(service).count())
    }

    @Test
    fun `a counted play with history on is handed to the scrobbler`() {
        assertTrue("scrobbler.scrobble(" in body(service, "if (counted && !historyPaused) {"))
        assertEquals(1, Regex("""scrobbler\.scrobble\(""").findAll(service).count())
    }

    @Test
    fun `the scrobbler hands each play to ListenBrainz beside Last_fm, on one rule`() {
        val nowPlaying = body(scrobbler, "suspend fun nowPlaying(")
        assertTrue(nowPlaying.indexOf("ListenReporting.sendsNowPlaying(") < nowPlaying.indexOf("listenBrainz.nowPlaying(metadata)"))
        val scrobble = body(scrobbler, "suspend fun scrobble(")
        val rule = scrobble.indexOf("ListenRules.qualifies(playedMs, duration)")
        assertTrue(rule >= 0 && rule < scrobble.indexOf("listenBrainz.scrobble(metadata, startedAtSeconds, duration)"))
        // Neither waits for the other, and Last.fm's own copy of the rule is not read any more.
        assertTrue("launch { listenBrainz.scrobble(" in scrobble && "launch { listenBrainz.nowPlaying(" in nowPlaying)
        assertFalse("LastFm.qualifies(" in scrobbler)
    }

    @Test
    fun `a play is kept before it is sent`() {
        val scrobble = body(sender, "suspend fun scrobble(")
        val kept = scrobble.indexOf("outbox.keep(kept)")
        assertTrue(kept >= 0 && kept < scrobble.indexOf("client.single("))
        // Off the outbox only once ListenBrainz took it or refused it for good.
        assertEquals(2, Regex("""outbox\.remove\(listOf\(kept\)\)""").findAll(scrobble).count())
    }

    @Test
    fun `what could not go is sent later, once there is a connection`() {
        val worker = fileCode("listenbrainz/ListenBrainzWorker.kt")
        assertTrue("setRequiredNetworkType(NetworkType.CONNECTED)" in worker)
        assertTrue("setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)" in worker)
        assertTrue("OutboxRun.Next.LATER -> return Result.retry()" in worker)
        assertTrue("ListenBrainzWorker.schedule(context)" in body(sender, "suspend fun scrobble("))
        assertTrue("ListenBrainzWorker.schedule(context)" in body(sender, "suspend fun connect("))
        val app = fileCode("App.kt")
        assertTrue("if (ListenOutbox.anyWaiting(this@App)) ListenBrainzWorker.schedule(this@App)" in app)
    }

    @Test
    fun `nothing is sent or kept without the flag`() {
        assertTrue("Unreleased.LISTENBRAINZ && token.isNotBlank()" in sender)
        assertTrue("if (!Unreleased.LISTENBRAINZ) return Result.success()" in fileCode("listenbrainz/ListenBrainzWorker.kt"))
        assertTrue("val LISTENBRAINZ = BuildConfig.PREVIEW_FEATURES" in fileCode("constants/Unreleased.kt"))
    }

    @Test
    fun `the app says who it is, as ListenBrainz requires`() {
        assertTrue("ListenBrainz.App(CLIENT_NAME, BuildConfig.VERSION_NAME, Links.REPO)" in sender)
        assertTrue("const val CLIENT_NAME = \"InterTune\"" in sender)
    }

    @Test
    fun `the token never reaches a log`() {
        val folder = File(sources, "listenbrainz")
        val files = folder.listFiles()!!.filter { it.extension == "kt" }
        assertTrue(files.size >= 4)
        for (file in files) {
            val logs = code(file.readText()).lines().filter { "Log." in it }
            for (line in logs) {
                assertFalse("${file.name}: $line", Regex("""\$\{?(token|key|pasted)\b""").containsMatchIn(line))
            }
        }
    }
}

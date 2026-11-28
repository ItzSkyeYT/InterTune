/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What's playing? with the microphone refused (28 Nov 2026, on Android 14): the music was paused
 * before Android's question and stayed paused after a no, the screen only said "Tap to listen"
 * again, and once Android stopped asking (two refusals) a tap did nothing visible at all. Now the
 * music stops only for a listen that starts, and a refusal says why with the way to Android's
 * settings, where the microphone is allowed again.
 */
class MicrophoneRefusedTest {
    private fun source(path: String) = File("src/main/$path").readText()
    private val screenPath = "java/com/dd3boh/outertune/ui/screens/RecognitionScreen.kt"
    private val sheetPath = "java/com/dd3boh/outertune/ui/component/RecognitionSheet.kt"
    private val activityPath = "java/com/dd3boh/outertune/recognition/PhoneListenActivity.kt"

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("no \"$from\"", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("no \"$to\" after \"$from\"", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `the screen pauses the music only for a listen that starts, never before Android's question`() {
        val screen = source(screenPath)
        // One place pauses, and it is only ever called once the microphone is known to be allowed.
        assertEquals("the screen pauses the player somewhere else too", 1, Regex("""\.player\.pause\(\)""").findAll(screen).count())
        val pause = between(screen, "fun pauseForTheRoom()", "\n    }\n")
        assertTrue("the pause is no longer in pauseForTheRoom", "playerConnection.player.pause()" in pause)

        val listen = between(screen, "fun listen(keepGoing: Boolean", "// The tile's or the widget's tap is a tap on the big button")
        val check = listen.indexOf("if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)")
        val ask = listen.indexOf("permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)")
        assertTrue("listen() no longer checks the microphone before it asks", check >= 0 && ask > check)
        val beforeTheCheck = listen.substring(0, check)
        assertFalse("the music is paused before the microphone is known", "pause" in beforeTheCheck.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n"))
        val askBranch = listen.substring(listen.lastIndexOf("} else {", ask), ask)
        assertFalse("the music is paused on the way to Android's question", "pauseForTheRoom()" in askBranch)
        assertEquals("a start from listen() no longer pauses the room's music", 2, Regex("""pauseForTheRoom\(\)""").findAll(listen.substring(check, ask)).count())

        val answer = between(screen, "val permissionLauncher = rememberLauncherForActivityResult(", "\n    }\n")
        val granted = answer.indexOf("if (granted) {")
        assertTrue("the answer no longer starts on a yes", granted >= 0)
        assertTrue("a yes no longer pauses before it starts", "pauseForTheRoom()" in answer.substring(granted))
        assertFalse("the answer pauses before it knows", "pauseForTheRoom()" in answer.substring(0, granted))
    }

    @Test
    fun `a refusal says why under the button, and the line opens Android's settings, also once Android stops asking`() {
        val screen = source(screenPath)
        val answer = between(screen, "val permissionLauncher = rememberLauncherForActivityResult(", "\n    }\n")
        // Android's own no, with no question shown, comes back through the same answer: a refusal is
        // said whichever way it came.
        assertTrue("a no is no longer kept to be said", "micRefused = !granted" in answer)
        val line = between(screen, "if (micRefused && !listening) item(key = \"mic_refused\")", "\n        }\n")
        assertTrue("the line no longer says why", "R.string.recognition_needs_microphone" in line)
        assertTrue("the line no longer opens Android's settings", "MicrophoneSettings.open(context)" in line)
        assertTrue("the line is not under the button", screen.indexOf("item(key = \"mic_refused\")") > screen.indexOf("item(key = \"listen\")"))
        assertTrue("the line is not right under the button", screen.indexOf("item(key = \"mic_refused\")") < screen.indexOf("item(key = \"source\")"))
        assertTrue("the line no longer goes when the microphone is allowed", "var micRefused by rememberMicrophoneRefused()" in screen)
    }

    @Test
    fun `a playlist's listen says the same, and already asks before it pauses`() {
        val sheet = source(sheetPath)
        val start = between(sheet, "fun start(instead: Boolean = false)", "\n    }\n")
        assertTrue("the sheet's start no longer pauses the room's music", "playerConnection.player.pause()" in start)
        val answer = between(sheet, "val permission = rememberLauncherForActivityResult(", "\n    }\n")
        assertTrue("a no is no longer kept to be said", "micRefused = !granted" in answer)
        assertFalse("the sheet pauses before Android's answer", "pause()" in answer)
        val idle = between(sheet, "RecognitionEngine.State.Idle ->", "is RecognitionEngine.State.Listening ->")
        assertTrue("a refusal in the sheet no longer says why", "R.string.recognition_needs_microphone" in idle)
        assertTrue("the sheet's line no longer opens Android's settings", "MicrophoneSettings.open(context)" in idle)
        assertTrue("var micRefused by rememberMicrophoneRefused()" in sheet)
    }

    @Test
    fun `the Song on this phone tile opens Android's settings once Android stops asking for the microphone`() {
        val activity = source(activityPath)
        val refuse = between(activity, "private fun refuseWithoutMicrophone()", "\n    }\n")
        assertTrue("the tile no longer says why", "R.string.recognition_phone_needs_permission" in refuse)
        assertTrue("shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)" in refuse)
        assertTrue("the tile no longer opens Android's settings", "MicrophoneSettings.open(this, ownTask = true)" in refuse)
        assertTrue("granted[Manifest.permission.RECORD_AUDIO] == false -> refuseWithoutMicrophone()" in activity)
    }

    @Test
    fun `the page opened is InterTune's own in Android's settings`() {
        val settings = source("java/com/dd3boh/outertune/recognition/MicrophoneSettings.kt")
        assertTrue("Settings.ACTION_APPLICATION_DETAILS_SETTINGS" in settings)
        assertTrue("\"package:\${context.packageName}\".toUri()" in settings)
        val messages = source("java/com/dd3boh/outertune/ui/component/RecognitionMessages.kt")
        val refused = between(messages, "fun rememberMicrophoneRefused()", "\n}\n")
        assertTrue("the line stays once allowed in Android's settings", "Lifecycle.Event.ON_RESUME" in refused)
        assertTrue("MicrophoneSettings.allowed(context)" in refused)
        assertTrue("the refusal is lost on a rotation", "rememberSaveable" in refused)
    }

    @Test
    fun `the words, in English and French`() {
        val en = source("res/values/strings-ot.xml")
        val fr = source("res/values-fr/strings-ot.xml")
        assertTrue(
            "<string name=\"recognition_needs_microphone\">InterTune needs the microphone to listen. Tap to allow it in Android\\'s settings.</string>" in en,
        )
        assertTrue(
            "<string name=\"recognition_needs_microphone\">InterTune a besoin du micro pour écouter. Touchez pour l\\'autoriser dans les réglages d\\'Android.</string>" in fr,
        )
        for ((name, text) in listOf("en" to en, "fr" to fr)) {
            assertEquals("$name has the line more than once", 1, Regex("name=\"recognition_needs_microphone\"").findAll(text).count())
        }
    }
}

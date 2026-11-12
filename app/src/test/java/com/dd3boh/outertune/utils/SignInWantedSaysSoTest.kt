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
 * What the player says when YouTube wants a sign-in (issue #124, where it read "Source error
 * (2000): Unexpected Z: Unknown error"): in plain words, with the way out beside Retry, on the
 * error screen and in the toast. What counts as YouTube wanting one is SignInWantedTest's.
 */
class SignInWantedSaysSoTest {
    private val main = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `the error screen says it in plain words, with the way out beside Retry`() {
        val screen = File(main, "ui/player/ThumbnailPlaybackError.kt").readText()
        assertTrue(screen.contains("YTPlayerUtils.isSignInWanted(error)"))
        val words = screen.indexOf("stringResource(R.string.err_sign_in_wanted)")
        assertTrue("no sentence for it", words >= 0)
        // Signed out, the sign-in is the way out; signed in, the old words stand, which say the account is not the fault.
        assertTrue(screen.contains("rememberSignedIn()"))
        assertTrue("signed in keeps the sentence that was there", screen.indexOf("stringResource(R.string.err_youtube_busy)") in 0 until screen.indexOf("stringResource(R.string.err_age_restricted)"))
        // The buttons: Log in for who is signed out, the proxy setting for everyone, both before Retry.
        val logIn = screen.indexOf("onClick = onSignIn")
        val proxy = screen.indexOf("onClick = onProxy")
        val retry = screen.indexOf("onClick = retry")
        assertTrue("no way to sign in", logIn > 0 && screen.contains("stringResource(R.string.action_login)"))
        assertTrue("no way to the network settings", proxy > 0 && screen.contains("stringResource(R.string.region_note_proxy)"))
        assertTrue("the ways out come before Retry", logIn < retry && proxy < retry)
        assertTrue("Log in is for the one signed out", screen.substring(maxOf(0, logIn - 160), logIn).contains("!signedIn"))
    }

    @Test
    fun `the sheet goes down on the way to the sign-in and to the proxy setting`() {
        val player = File(main, "ui/player/Player.kt").readText()
        val thumbnail = File(main, "ui/player/Thumbnail.kt").readText()
        assertTrue(thumbnail.contains("onSignIn = onSignIn"))
        assertTrue(thumbnail.contains("onProxy = onProxy"))
        val signIn = player.substringAfter("val leaveForSignIn = remember(").substringBefore("val leaveForProxy")
        assertTrue(signIn, signIn.contains("navController.navigate(\"login\")") && signIn.contains("state.collapseSoft()"))
        val proxy = player.substringAfter("val leaveForProxy = remember(").substringBefore("// Focus follows")
        assertTrue(proxy, proxy.contains("openProxySetting()") && proxy.contains("state.collapseSoft()"))
        // Every cover the player draws can hold the error.
        assertEquals(4, Regex("""onSignIn = leaveForSignIn""").findAll(player).count())
        assertEquals(4, Regex("""onProxy = leaveForProxy""").findAll(player).count())
    }

    @Test
    fun `the toast says it too, and a signed in listener is told what a turned away connection is told`() {
        val service = File(main, "playback/MusicService.kt").readText()
        val onError = service.substringAfter("override fun onPlayerError(").substringBefore("override fun onPlayWhenReadyChanged(")
        assertTrue(onError.contains("YTPlayerUtils.isSignInWanted(error)"))
        assertTrue(onError.contains("R.string.error_sign_in_wanted"))
        assertTrue("signed in, a sign-in is not what is asked", onError.contains("WhyStopped.signedIn(YouTube.cookie)"))
        // Before the raw line, after the ones that were there.
        assertTrue(onError.indexOf("R.string.error_sign_in_wanted") < onError.indexOf("ErrorText.withoutAddresses(\"plr: "))
    }

    @Test
    fun `the words are in English and in French, short, and without a dash`() {
        for (folder in listOf("values", "values-fr")) {
            val text = File("src/main/res/$folder/strings-ot.xml").readText()
            for (name in listOf("err_sign_in_wanted", "error_sign_in_wanted")) {
                val line = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(text)?.groupValues?.get(1)
                assertTrue("$folder has no $name", line != null)
                assertFalse("$folder/$name has a dash", "—" in line!! || "–" in line)
                val sentences = line.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }
                assertTrue("$folder/$name is ${sentences.size} sentences", sentences.size <= 2)
            }
        }
        // The toast fits on its two lines in both: ToastLengthTest reads every Toaster.show, this one included.
    }
}

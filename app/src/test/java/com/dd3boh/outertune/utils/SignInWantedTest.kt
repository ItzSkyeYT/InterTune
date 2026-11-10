/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.upstream.Loader
import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #124: on a VPN, signed in, every song stopped with "Source error (2000): Unexpected Z:
 * Unknown error". Five routes had answered LOGIN_REQUIRED (the bot check), the account's web
 * client had answered OK with an address the app could not decipher, and the walk ended in the
 * last of those: "Missing stream expire time", which the player can only call an unknown error.
 *
 * Now a walk that found no stream says YouTube wanted a sign-in when the routes that answered
 * did. What the player says of it is SignInWantedSaysSoTest's.
 */
class SignInWantedTest {
    private val bot = "Sign in to confirm you’re not a bot"

    private fun said(status: String, reason: String?) = PlayerResponse.PlayabilityStatus(status, reason)

    // --- What counts as YouTube wanting a sign-in. ---

    @Test
    fun `the bot check from a client that serves music is YouTube wanting a sign-in, whatever the others said`() {
        // What the log of #124 holds: five LOGIN_REQUIRED and the account's OK.
        val issue = listOf(
            said("LOGIN_REQUIRED", bot), said("LOGIN_REQUIRED", bot), said("LOGIN_REQUIRED", bot),
            said("LOGIN_REQUIRED", bot), said("LOGIN_REQUIRED", bot), said("OK", null),
        )
        assertEquals(bot, StreamCheck.signInWanted(issue))
        // One client's word is enough, as it is for the throttle: the others may call a song gone.
        assertEquals(bot, StreamCheck.signInWanted(listOf(said("UNPLAYABLE", "This video is not available"), said("LOGIN_REQUIRED", bot))))
    }

    @Test
    fun `LOGIN_REQUIRED from every client that refused is the same, in whatever words YouTube wrote it`() {
        assertEquals("Please sign in", StreamCheck.signInWanted(listOf(said("LOGIN_REQUIRED", "Please sign in"), said("LOGIN_REQUIRED", "Please sign in"), said("OK", null))))
        assertEquals("with no words at all, the bot check's", StreamCheck.SIGN_IN_WORDS, StreamCheck.signInWanted(listOf(said("LOGIN_REQUIRED", null))))
    }

    @Test
    fun `the age gate, a song that is gone, a mixed answer, an answer that served and no answer are not it`() {
        assertNull(StreamCheck.signInWanted(emptyList()))
        assertNull(StreamCheck.signInWanted(listOf(said("OK", null))))
        // One song's, not the network's: Throttle.looksLikeAgeGate has it.
        assertNull(StreamCheck.signInWanted(listOf(said("LOGIN_REQUIRED", "Sign in to confirm your age"), said("LOGIN_REQUIRED", "Sign in to confirm your age"))))
        assertNull(StreamCheck.signInWanted(listOf(said("LOGIN_REQUIRED", "This video is private. Sign in if you have been granted access to this video"))))
        // Some say sign in and some say something else, in words that are not the bot check's: not clear.
        assertNull(StreamCheck.signInWanted(listOf(said("LOGIN_REQUIRED", "Please sign in"), said("UNPLAYABLE", "This video is not available"))))
        assertNull(StreamCheck.signInWanted(listOf(said("LOGIN_REQUIRED", "Please sign in"), said("ERROR", "Something went wrong"))))
    }

    // --- Where the player finds it. ---

    private fun handedOver(cause: Throwable) =
        ExoPlaybackException.createForSource(Loader.UnexpectedLoaderException(cause), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    @Test
    fun `it is found where the player hands it over, by its type or by YouTube's words`() {
        val typed = YTPlayerUtils.SignInWanted(null)
        assertEquals("YouTube's own words, which the throttle and the error screen already read", StreamCheck.SIGN_IN_WORDS, typed.message)
        assertEquals(PlaybackException.ERROR_CODE_REMOTE_ERROR, typed.errorCode)
        assertTrue(YTPlayerUtils.isSignInWanted(typed))
        assertTrue("wrapped the way a loader's failure reaches onPlayerError", YTPlayerUtils.isSignInWanted(handedOver(typed)))
        // In words the throttle does not read, only the type says it.
        assertFalse(Throttle.isBlock(handedOver(YTPlayerUtils.SignInWanted("Please sign in"))))
        assertTrue(YTPlayerUtils.isSignInWanted(handedOver(YTPlayerUtils.SignInWanted("Please sign in"))))
        // And YouTube's words alone, as the walk has always thrown them, are found as before.
        assertTrue(YTPlayerUtils.isSignInWanted(handedOver(PlaybackException(bot, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))))
    }

    @Test
    fun `the unknown error, the age gate, a song that is gone and a connection turned away are not it`() {
        assertFalse(YTPlayerUtils.isSignInWanted(null))
        assertFalse(YTPlayerUtils.isSignInWanted(handedOver(PlaybackException("Unknown error", Exception("Missing stream expire time"), PlaybackException.ERROR_CODE_REMOTE_ERROR))))
        assertFalse(YTPlayerUtils.isSignInWanted(handedOver(PlaybackException("Sign in to confirm your age", null, PlaybackException.ERROR_CODE_REMOTE_ERROR))))
        assertFalse(YTPlayerUtils.isSignInWanted(handedOver(YTPlayerUtils.SongUnavailable("This video is not available"))))
        assertFalse(YTPlayerUtils.isSignInWanted(handedOver(YTPlayerUtils.ConnectionTurnedAway("This video is not available"))))
    }

    @Test
    fun `a chain of causes that never ends is given up on`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(YTPlayerUtils.isSignInWanted(a))
    }
}

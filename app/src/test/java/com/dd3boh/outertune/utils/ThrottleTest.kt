/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English wording the fallback clients' refusals arrive in, now that YTPlayerUtils asks them
 * with hl=en whatever the app's language. The main client is still asked in the app's language,
 * so its reason only matches here when the app is in English. YouTubeClientTest checks that the
 * override reaches the request; these cases pin the wording it relies on.
 */
class ThrottleTest {
    @Test
    fun `the bot check is recognised`() {
        assertTrue(Throttle.looksLikeBlock("Sign in to confirm you're not a bot"))
        assertTrue(Throttle.looksLikeBlock("Sign in to confirm you’re not a bot"))
    }

    @Test
    fun `the age gate text is not read as a block`() {
        assertFalse(Throttle.looksLikeBlock("Sign in to confirm your age"))
        assertFalse(Throttle.looksLikeBlock("Sign in to confirm your age, this video may be inappropriate for some users."))
    }

    @Test
    fun `the age gate is recognised as itself`() {
        assertTrue(Throttle.looksLikeAgeGate("Sign in to confirm your age"))
        assertTrue(Throttle.looksLikeAgeGate("This video may be inappropriate for some users."))
        assertTrue(Throttle.looksLikeAgeGate("age-restricted"))
    }

    @Test
    fun `an unrelated refusal is neither a block nor an age gate`() {
        assertFalse(Throttle.looksLikeBlock("This video is not available"))
        assertFalse(Throttle.looksLikeAgeGate("This video is not available"))
        assertFalse(Throttle.looksLikeBlock(null))
        assertFalse(Throttle.looksLikeAgeGate(null))
    }

    @Test
    fun `note trips the back off only for a block, not any non-OK status`() {
        Throttle.clear("test setup")
        Throttle.note("LOGIN_REQUIRED", "Sign in to confirm you're not a bot")
        assertTrue(Throttle.isBlocked)
        Throttle.clear("test teardown")
    }

    @Test
    fun `note does not trip for a plain unavailable song`() {
        Throttle.clear("test setup")
        Throttle.note("UNPLAYABLE", "This video is not available")
        assertFalse(Throttle.isBlocked)
    }
}

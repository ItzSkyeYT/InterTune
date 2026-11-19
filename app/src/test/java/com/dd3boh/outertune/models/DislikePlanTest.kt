/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.models.DislikePlan.Plan
import com.dd3boh.outertune.models.DislikePlan.Tell
import com.dd3boh.outertune.models.DislikePlan.Unlike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One tap on Dislike or Don't play this song (#171, #78): what stays here and what YouTube Music is
 * told. Every case of the five answers that decide it, with the flag and without.
 */
class DislikePlanTest {

    private fun plan(
        flag: Boolean = true,
        held: Boolean = false,
        local: Boolean = false,
        liked: Boolean = false,
        mayPush: Boolean = true,
        signedIn: Boolean = true,
    ) = DislikePlan.of(flag, held, local, liked, mayPush, signedIn)

    @Test
    fun `without the flag it is Don't play this song as it always was, and nothing leaves the phone`() {
        for (held in listOf(false, true)) for (local in listOf(false, true)) for (liked in listOf(false, true))
            for (mayPush in listOf(false, true)) for (signedIn in listOf(false, true)) {
                assertEquals(Plan(keep = !held, unlike = Unlike.NO, tell = Tell.NOTHING), plan(false, held, local, liked, mayPush, signedIn))
            }
        assertFalse(DislikePlan.shown(flag = false, local = false))
    }

    @Test
    fun `a local file keeps Don't play this song, which YouTube cannot hear`() {
        assertFalse(DislikePlan.shown(flag = true, local = true))
        assertEquals(Plan(keep = true, unlike = Unlike.NO, tell = Tell.NOTHING), plan(local = true, liked = true))
        assertEquals(Plan(keep = true, unlike = Unlike.NO, tell = Tell.NOTHING), plan(local = true, liked = true, mayPush = false, signedIn = false))
        assertEquals(Plan(keep = false, unlike = Unlike.NO, tell = Tell.NOTHING), plan(local = true, held = true))
    }

    @Test
    fun `a song from YouTube Music is disliked, and YouTube Music hears it when the account may be changed`() {
        assertTrue(DislikePlan.shown(flag = true, local = false))
        assertEquals(Plan(keep = true, unlike = Unlike.NO, tell = Tell.DISLIKE), plan())
        // Sync on read only, or signed out: kept from playing here, nothing sent.
        assertEquals(Plan(keep = true, unlike = Unlike.NO, tell = Tell.NOTHING), plan(mayPush = false))
        assertEquals(Plan(keep = true, unlike = Unlike.NO, tell = Tell.NOTHING), plan(mayPush = false, signedIn = false))
    }

    @Test
    fun `a liked song loses its like here only once YouTube Music has taken the thumbs down, which replaces it there`() {
        // Taken back after the answer, and only if the thumbs down landed: a failed one keeps the like
        // here, as YouTube Music keeps it, and the next sync has nothing to put back.
        assertEquals(Plan(keep = true, unlike = Unlike.AFTER_DISLIKE, tell = Tell.DISLIKE), plan(liked = true))
    }

    @Test
    fun `with sync on read only the like stays, as the next sync would like the song again from YouTube Music`() {
        assertEquals(Plan(keep = true, unlike = Unlike.NO, tell = Tell.NOTHING), plan(liked = true, mayPush = false, signedIn = true))
    }

    @Test
    fun `signed out the like goes at once, as nothing syncs it back`() {
        assertEquals(Plan(keep = true, unlike = Unlike.NOW, tell = Tell.NOTHING), plan(liked = true, mayPush = false, signedIn = false))
    }

    @Test
    fun `Remove dislike lets it play and takes the thumbs down back, but never a like YouTube Music holds`() {
        assertEquals(Plan(keep = false, unlike = Unlike.NO, tell = Tell.REMOVE), plan(held = true))
        assertEquals(Plan(keep = false, unlike = Unlike.NO, tell = Tell.NOTHING), plan(held = true, mayPush = false))
        assertEquals(Plan(keep = false, unlike = Unlike.NO, tell = Tell.NOTHING), plan(held = true, mayPush = false, signedIn = false))
        // Kept from playing while liked here (set before Dislike existed, or a thumbs down that did not
        // land): removelike would take the like.
        assertEquals(Plan(keep = false, unlike = Unlike.NO, tell = Tell.NOTHING), plan(held = true, liked = true))
    }
}

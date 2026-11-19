/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.models.DislikePlan.Plan
import com.dd3boh.outertune.models.DislikePlan.Tell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One tap on Dislike or Don't play this song (#171, #78): what stays here and what YouTube Music is
 * told. Every case of the four answers that decide it, with the flag and without.
 */
class DislikePlanTest {

    private fun plan(flag: Boolean = true, held: Boolean = false, local: Boolean = false, liked: Boolean = false, mayPush: Boolean = true) =
        DislikePlan.of(flag, held, local, liked, mayPush)

    @Test
    fun `without the flag it is Don't play this song as it always was, and nothing leaves the phone`() {
        for (held in listOf(false, true)) for (local in listOf(false, true)) for (liked in listOf(false, true)) for (mayPush in listOf(false, true)) {
            assertEquals(Plan(keep = !held, unlikeHere = false, tell = Tell.NOTHING), plan(false, held, local, liked, mayPush))
        }
        assertFalse(DislikePlan.shown(flag = false, local = false))
    }

    @Test
    fun `a local file keeps Don't play this song, which YouTube cannot hear`() {
        assertFalse(DislikePlan.shown(flag = true, local = true))
        assertEquals(Plan(keep = true, unlikeHere = false, tell = Tell.NOTHING), plan(local = true, liked = true))
        assertEquals(Plan(keep = false, unlikeHere = false, tell = Tell.NOTHING), plan(local = true, held = true))
    }

    @Test
    fun `a song from YouTube Music is disliked, and YouTube Music hears it when the account may be changed`() {
        assertTrue(DislikePlan.shown(flag = true, local = false))
        assertEquals(Plan(keep = true, unlikeHere = false, tell = Tell.DISLIKE), plan())
        // Signed out, or sync on read only: kept from playing here, nothing sent.
        assertEquals(Plan(keep = true, unlikeHere = false, tell = Tell.NOTHING), plan(mayPush = false))
    }

    @Test
    fun `a liked song loses its like here, and only the thumbs down is sent, never a removelike beside it`() {
        assertEquals(Plan(keep = true, unlikeHere = true, tell = Tell.DISLIKE), plan(liked = true))
        assertEquals(Plan(keep = true, unlikeHere = true, tell = Tell.NOTHING), plan(liked = true, mayPush = false))
    }

    @Test
    fun `Remove dislike lets it play and takes the thumbs down back, but never a like YouTube Music holds`() {
        assertEquals(Plan(keep = false, unlikeHere = false, tell = Tell.REMOVE), plan(held = true))
        assertEquals(Plan(keep = false, unlikeHere = false, tell = Tell.NOTHING), plan(held = true, mayPush = false))
        // Kept from playing while liked here (set before Dislike existed): removelike would take the like.
        assertEquals(Plan(keep = false, unlikeHere = false, tell = Tell.NOTHING), plan(held = true, liked = true))
    }
}

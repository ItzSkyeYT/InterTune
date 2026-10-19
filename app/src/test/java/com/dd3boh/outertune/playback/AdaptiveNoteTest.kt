/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.AdaptiveNote.Kind
import com.dd3boh.outertune.playback.QueuePlanner.Pick
import com.dd3boh.outertune.playback.QueuePlanner.Replace
import com.dd3boh.outertune.playback.QueuePlanner.Slot
import com.dd3boh.outertune.playback.QueuePlanner.Unwanted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which line the queue sheet says about the last change, and how long it stands. See AdaptiveNote. */
class AdaptiveNoteTest {

    private fun replaced(id: String, seed: String = "kept", why: Unwanted? = null) =
        Replace(Slot(10, "old-$id", "artist", 0.0, unwanted = why, byWords = why == null), Pick(id, seed, "a-$id", 1.0))

    private fun note(kind: AdaptivePass.Kind, replaced: List<Replace> = emptyList(), inserts: List<Pick> = emptyList(), dropped: Int = 0, liked: String? = null) =
        AdaptiveNote.of(kind, replaced, inserts, dropped, liked, liked?.let { "Paper Moon" }, played = 7)

    @Test
    fun `picks like the liked song come first, with its title and every song put in counted`() {
        val n = note(AdaptivePass.Kind.LIKE, listOf(replaced("r1")), listOf(Pick("i1", "liked", "x", 1.0), Pick("i2", "liked", "y", 1.0)), liked = "liked")!!
        assertEquals(Kind.LIKE, n.kind)
        assertEquals(3, n.count)
        assertEquals("Paper Moon", n.title)
        assertEquals(setOf("r1", "i1", "i2"), n.picks)
        assertEquals(7, n.setAt)
    }

    @Test
    fun `then picks after a skip, then picks in place of habits and bans, then any other picks`() {
        assertEquals(Kind.AFTER_SKIP, note(AdaptivePass.Kind.SKIP, listOf(replaced("r1", why = Unwanted.LASTING_DISLIKE)))!!.kind)
        assertEquals(Kind.HABITS, note(AdaptivePass.Kind.ORDINARY, listOf(replaced("r1", why = Unwanted.LASTING_DISLIKE), replaced("r2", why = Unwanted.BANNED)))!!.kind)
        assertEquals(Kind.FINISH, note(AdaptivePass.Kind.ORDINARY, listOf(replaced("r1", why = Unwanted.LASTING_DISLIKE), replaced("r2")))!!.kind)
        assertEquals("a like whose picks are not in yet claims nothing", Kind.FINISH, note(AdaptivePass.Kind.LIKE, listOf(replaced("r1")), liked = "liked")!!.kind)
    }

    @Test
    fun `songs only taken out say so, and a pass that changed nothing leaves the note before it`() {
        val n = note(AdaptivePass.Kind.SKIP, dropped = 2)!!
        assertEquals(Kind.DROPPED, n.kind)
        assertEquals(2, n.count)
        assertNull(note(AdaptivePass.Kind.SKIP))
    }

    @Test
    fun `a note stands while a song it put in is still ahead, and a took-out note for four songs`() {
        val picks = AdaptiveNote.Note(Kind.AFTER_SKIP, 2, picks = setOf("a", "b"), setAt = 3)
        assertTrue(AdaptiveNote.stands(picks, setOf("b", "z"), played = 9))
        assertFalse("every song it put in has played or gone", AdaptiveNote.stands(picks, setOf("z"), played = 9))

        val out = AdaptiveNote.Note(Kind.DROPPED, 2, setAt = 3)
        assertTrue(AdaptiveNote.stands(out, emptySet(), played = 6))
        assertFalse(AdaptiveNote.stands(out, emptySet(), played = 7))
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * What the sync does with a change made here that the account has not had yet: it leaves it
 * standing, and once the account has it, reads the account as before. Each case below is one the
 * scan of 4 Nov 2026 found undone by the next sync, under "Keep all local content" too (its findings
 * 3, 7, 8 and 9), as the sync decides it, with the account's answer standing in for YouTube.
 */
class SyncKeepsWaitingTest {

    private fun waiting(vararg changes: Change) = Waiting(changes.mapIndexed { i, c -> Pending(i + 1L, "dsid:me", 0, c) })

    // ---- finding 8: an unlike that did not reach YouTube ----

    @Test
    fun `an unlike made offline is not liked again by the next liked sync`() {
        // Liked music on YouTube still holds b: the unlike made on the phone has not reached it.
        val lm = listOf("a", "b", "c")
        val toLike = LikedSync.toLikeHere(lm, waiting(Change.Like("b", liked = false)))
        assertEquals(listOf("a", "c"), toLike)
    }

    @Test
    fun `once the unlike has reached YouTube, a song liked again on another device comes in as before`() {
        // Nothing waits any more: whatever Liked music holds is liked here.
        assertEquals(listOf("a", "b"), LikedSync.toLikeHere(listOf("a", "b"), Waiting.NONE))
    }

    @Test
    fun `a like made offline is not taken back by an overwrite sync that does not see it in Liked music yet`() {
        val picked = setOf("fresh", "stale")
        assertEquals(setOf("stale"), LikedSync.toUnlikeHere(picked, waiting(Change.Like("fresh", liked = true))))
    }

    // ---- finding 7: Remove from library ----

    @Test
    fun `a song taken out of the library here stays out of the library and unliked while its unlike waits`() {
        val removed = PendingRules.outOfLibrary("b", isLocal = false, liked = true)!!
        val w = waiting(removed)
        // The liked sync does not like it again, and the library sync does not put it back.
        assertEquals(listOf("a"), LikedSync.toLikeHere(listOf("a", "b"), w))
        assertEquals(listOf("a"), LibrarySync.toAdd(listOf("a", "b"), w))
    }

    @Test
    fun `a heart tapped off is an unlike, not a library removal`() {
        // The library sync only holds back what was taken out of the library here.
        assertEquals(listOf("a", "b"), LibrarySync.toAdd(listOf("a", "b"), waiting(Change.Like("b", liked = false))))
    }

    @Test
    fun `an overwrite library sync does not take out a song liked here whose like waits`() {
        val removed = LibrarySync.toRemove(listOf("liked-offline", "gone"), remoteIds = setOf("kept"), waiting(Change.Like("liked-offline", true)))
        assertEquals(listOf("gone"), removed)
    }

    // ---- finding 3: an add to a synced playlist ----

    @Test
    fun `a playlist with an add that waits is not replaced by the copy YouTube still has`() {
        val w = waiting(Change.Add("PL1", mapId = 3, videoId = "new", duplicate = false))
        assertFalse(PlaylistSync.mayReplace("PL1", w))
        // With "VL" in front too, as YouTube writes it on a page.
        assertFalse(PlaylistSync.mayReplace("VLPL1", w))
        // Another playlist is synced as before.
        assertTrue(PlaylistSync.mayReplace("PL2", w))
    }

    @Test
    fun `a removal or a drag that waits keeps the playlist as it is here too`() {
        assertFalse(PlaylistSync.mayReplace("PL1", waiting(Change.Remove("PL1", 1, "a", "S1"))))
        assertFalse(PlaylistSync.mayReplace("PL1", waiting(Change.Move("PL1", 1, "a", "S1", 2, "b", "S2"))))
        // Once nothing waits, YouTube's copy, which now has them, is read as before.
        assertTrue(PlaylistSync.mayReplace("PL1", Waiting.NONE))
    }

    @Test
    fun `a new name that waits keeps only the name, and the songs edited on the web still come in`() {
        val w = waiting(Change.Rename("PL1", "Mine"))
        assertTrue(PlaylistSync.mayReplace("PL1", w))
        assertEquals("Mine", w.nameFor("PL1"))
    }

    @Test
    fun `a file from the phone in a synced playlist stays where it was when YouTube's copy comes in`() {
        val remote = listOf(PlaylistSync.Row("a", "S1"), PlaylistSync.Row("b", "S2"), PlaylistSync.Row("c", "S3"))
        // Here: a, phone1, b, phone2. YouTube: a, b, c (c added on the web).
        val after = PlaylistSync.rowsAfter(remote, phoneFiles = listOf(1 to "phone1", 3 to "phone2"))
        assertEquals(listOf("a", "phone1", "b", "phone2", "c"), after.map { it.songId })
        // YouTube's rows keep their ids; the files have none.
        assertEquals(listOf("S1", null, "S2", null, "S3"), after.map { it.setVideoId })
    }

    @Test
    fun `a file from the phone past the end of a shorter playlist goes at its end`() {
        val after = PlaylistSync.rowsAfter(listOf(PlaylistSync.Row("a", "S1")), phoneFiles = listOf(9 to "phone"))
        assertEquals(listOf("a", "phone"), after.map { it.songId })
        assertEquals(listOf("only"), PlaylistSync.rowsAfter(emptyList(), listOf(0 to "only")).map { it.songId })
    }

    // ---- finding 9: a playlist un-saved here, still saved on YouTube ----

    private fun row(id: String, browseId: String?, saved: Boolean) =
        PlaylistEntity(id = id, name = id, browseId = browseId, bookmarkedAt = if (saved) LocalDateTime.of(2026, 1, 1, 0, 0) else null)

    @Test
    fun `a playlist taken out of the library here and still in the account's is saved again on the same row, not given a second one`() {
        val rows = listOf(row("LPold", "PL1", saved = false))
        val step = PlaylistLibrarySync.step("PL1", rows, Waiting.NONE)
        assertTrue(step is PlaylistLibrarySync.Step.Existing)
        step as PlaylistLibrarySync.Step.Existing
        assertEquals("LPold", step.row.id)
        assertTrue(step.saveAgain)
    }

    @Test
    fun `of two rows for one playlist the saved one is taken, then the oldest`() {
        val rows = listOf(row("LPold", "PL1", saved = false), row("LPsaved", "PL1", saved = true))
        assertEquals("LPsaved", (PlaylistLibrarySync.step("PL1", rows, Waiting.NONE) as PlaylistLibrarySync.Step.Existing).row.id)
        val unsaved = listOf(row("LPfirst", "PL1", saved = false), row("LPsecond", "PL1", saved = false))
        assertEquals("LPfirst", (PlaylistLibrarySync.step("PL1", unsaved, Waiting.NONE) as PlaylistLibrarySync.Step.Existing).row.id)
    }

    @Test
    fun `a playlist taken out of the library or deleted here, not yet on the account, is left alone`() {
        val rows = listOf(row("LP1", "PL1", saved = false))
        assertEquals(PlaylistLibrarySync.Step.Leave, PlaylistLibrarySync.step("PL1", rows, waiting(Change.SavePlaylist("PL1", saved = false))))
        // Deleted here: no row at all, and none is made again.
        assertEquals(PlaylistLibrarySync.Step.Leave, PlaylistLibrarySync.step("PL1", emptyList(), waiting(Change.Delete("PL1"))))
    }

    @Test
    fun `a playlist new to the phone is a new row, and a name given here stays while it waits`() {
        assertEquals(PlaylistLibrarySync.Step.New, PlaylistLibrarySync.step("PL9", listOf(row("LP1", "PL1", true)), Waiting.NONE))
        val renamed = PlaylistLibrarySync.step("PL1", listOf(row("LP1", "PL1", true)), waiting(Change.Rename("PL1", "Mine")))
        assertEquals(PlaylistLibrarySync.Step.Existing(row("LP1", "PL1", true), saveAgain = false, keepName = true), renamed)
    }

    @Test
    fun `an overwrite sync does not take out of the library a playlist saved here whose save waits`() {
        val unsave = PlaylistLibrarySync.toUnsave(listOf("PLsavedHere", "PLgone"), remoteIds = setOf("PLother"), waiting(Change.SavePlaylist("PLsavedHere", true)))
        assertEquals(listOf("PLgone"), unsave)
    }

    // ---- albums and artists, the same rule ----

    @Test
    fun `an album or an artist taken out here is not saved again while that waits, nor one saved here taken out`() {
        val w = waiting(
            Change.SaveAlbum("MPREout", "OLAKout", saved = false), Change.SaveAlbum("MPREin", "OLAKin", saved = true),
            Change.Follow("UCout", null, followed = false), Change.Follow("UCin", null, followed = true),
        )
        assertEquals(listOf("MPREother"), SavedSync.albumsToSave(listOf("MPREout", "MPREother"), w))
        assertEquals(listOf("MPREgone"), SavedSync.albumsToUnsave(listOf("MPREin", "MPREgone"), setOf("x"), w))
        assertEquals(listOf("UCother"), SavedSync.artistsToFollow(listOf("UCout", "UCother"), w))
        assertEquals(listOf("UCgone"), SavedSync.artistsToUnfollow(listOf("UCin", "UCgone"), setOf("x"), w))
    }

    // ---- what the account changes by itself still comes in ----

    @Test
    fun `with nothing waiting the sync does what it always did`() {
        val none = Waiting.NONE
        assertTrue(none.isEmpty)
        assertEquals(listOf("a", "b"), LikedSync.toLikeHere(listOf("a", "b"), none))
        assertEquals(setOf("a"), LikedSync.toUnlikeHere(setOf("a"), none))
        assertEquals(listOf("a"), LibrarySync.toAdd(listOf("a"), none))
        assertTrue(PlaylistSync.mayReplace("PL1", none))
        assertEquals(listOf("a", "b"), PlaylistSync.rowsAfter(listOf(PlaylistSync.Row("a", "1"), PlaylistSync.Row("b", "2")), emptyList()).map { it.songId })
    }
}

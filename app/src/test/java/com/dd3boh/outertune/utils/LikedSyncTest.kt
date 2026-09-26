/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.utils.LikedSync.LocalLike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

/**
 * The liked songs sync used to unlike every local like missing from YouTube's Liked music, so a
 * first sign-in after months of liking songs signed out emptied them. These pin the rule that
 * replaced it: only what YouTube is known to have had, and has now lost, is taken back.
 */
class LikedSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 9, 26, 10, 0)
    private val old = now.minusDays(30)

    private fun likes(vararg ids: String, date: LocalDateTime? = old) = ids.map { LocalLike(it, date) }

    private fun unlike(
        local: List<LocalLike>,
        remote: Set<String>,
        complete: Boolean = true,
        snapshot: Set<String>?,
        addOnly: Boolean = false,
    ) = LikedSync.idsToUnlike(local, remote, complete, snapshot, addOnly, now, graceDays = 3)

    @Test
    fun `first sign-in after liking songs signed out takes nothing back`() {
        // 300 likes made signed out, none of which YouTube ever saw; LM holds other songs.
        val local = (1..300).map { LocalLike("local$it", old) }
        val remote = setOf("yt1", "yt2", "yt3")
        assertEquals(emptySet<String>(), unlike(local, remote, snapshot = null))
    }

    @Test
    fun `a like missing from LM that YouTube never had is kept`() {
        val local = likes("a", "b", "neverPushed")
        val snapshot = setOf("a", "b")
        val remote = setOf("a", "b")
        assertEquals(emptySet<String>(), unlike(local, remote, snapshot = snapshot))
    }

    @Test
    fun `a like YouTube had and has now lost is taken back`() {
        val local = likes("a", "b", "c")
        val snapshot = setOf("a", "b", "c")
        val remote = setOf("a", "c")
        assertEquals(setOf("b"), unlike(local, remote, snapshot = snapshot))
    }

    @Test
    fun `another account's first sync only adds`() {
        // Account A's snapshot exists, but the key is B's: the store hands back null for B.
        val local = likes("a1", "a2", "shared")
        assertEquals(emptySet<String>(), unlike(local, setOf("b1", "shared"), snapshot = null))
    }

    @Test
    fun `switching back to an account uses its own snapshot`() {
        // Likes gathered under B are not in A's snapshot, so they survive A's sync; A's own
        // unlike made on the web does land.
        val local = likes("a1", "a2", "b1", "b2")
        val snapshotA = setOf("a1", "a2")
        val remoteA = setOf("a1")
        assertEquals(setOf("a2"), unlike(local, remoteA, snapshot = snapshotA))
    }

    @Test
    fun `an incomplete read takes nothing back`() {
        val local = likes("a", "b", "c")
        val snapshot = setOf("a", "b", "c")
        assertEquals(emptySet<String>(), unlike(local, setOf("a"), complete = false, snapshot = snapshot))
    }

    @Test
    fun `an empty LM takes nothing back`() {
        val local = likes("a", "b")
        assertEquals(emptySet<String>(), unlike(local, emptySet(), snapshot = setOf("a", "b")))
    }

    @Test
    fun `keep all local content takes nothing back`() {
        val local = likes("a", "b")
        assertEquals(emptySet<String>(), unlike(local, setOf("a"), snapshot = setOf("a", "b"), addOnly = true))
    }

    @Test
    fun `a recent like is left alone even when it qualifies`() {
        val local = listOf(LocalLike("fresh", now.minusHours(5)), LocalLike("stale", old))
        val snapshot = setOf("fresh", "stale", "kept")
        assertEquals(setOf("stale"), unlike(local, setOf("kept"), snapshot = snapshot))
    }

    @Test
    fun `a like with no date is not protected by the grace period`() {
        val local = listOf(LocalLike("undated", null))
        assertEquals(setOf("undated"), unlike(local, setOf("x"), snapshot = setOf("undated", "x")))
    }

    @Test
    fun `a short read of a long LM does not look complete`() {
        // 1,500 liked, one page of 100 came back and nothing said there was more.
        assertFalse(LikedSync.readLooksComplete(readCount = 100, headerCount = 1500))
        assertFalse(LikedSync.readLooksComplete(readCount = 1200, headerCount = 1500))
    }

    @Test
    fun `a read a little short of the header count is complete`() {
        // Removed or unavailable videos are counted but never come back as rows.
        assertTrue(LikedSync.readLooksComplete(readCount = 1490, headerCount = 1500))
        assertTrue(LikedSync.readLooksComplete(readCount = 1350, headerCount = 1500))
        assertTrue(LikedSync.readLooksComplete(readCount = 16, headerCount = 20))
        assertTrue(LikedSync.readLooksComplete(readCount = 1500, headerCount = 1500))
        assertTrue(LikedSync.readLooksComplete(readCount = 1520, headerCount = 1500))
    }

    @Test
    fun `no header count raises no objection`() {
        assertTrue(LikedSync.readLooksComplete(readCount = 100, headerCount = null))
        assertTrue(LikedSync.readLooksComplete(readCount = 100, headerCount = 0))
    }

    @Test
    fun `song counts parse with their thousands separators`() {
        assertEquals(1234, LikedSync.parseSongCount("1,234 songs"))
        assertEquals(1234, LikedSync.parseSongCount("1.234 Titel"))
        assertEquals(1234, LikedSync.parseSongCount("1\u00A0234 titres"))
        assertEquals(1234, LikedSync.parseSongCount("1\u202F234 titres"))
        assertEquals(87, LikedSync.parseSongCount("87 songs"))
        assertEquals(12, LikedSync.parseSongCount("12 songs \u2022 45 minutes"))
        assertEquals(1, LikedSync.parseSongCount("1 song"))
        assertNull(LikedSync.parseSongCount("No songs"))
        assertNull(LikedSync.parseSongCount(null))
        assertNull(LikedSync.parseSongCount(""))
    }

    @Test
    fun `the account key prefers the data sync id and reads it as the app does`() {
        assertEquals("dsid:abc", LikedSync.accountKey("abc", "someone@example.com"))
        assertEquals("dsid:abc", LikedSync.accountKey("abc||", null))
        assertEquals("dsid:def", LikedSync.accountKey("abc||def", null))
        assertEquals("email:someone@example.com", LikedSync.accountKey("", " Someone@Example.com "))
        assertEquals("email:someone@example.com", LikedSync.accountKey("null", "someone@example.com"))
        assertNull(LikedSync.accountKey(null, null))
        assertNull(LikedSync.accountKey("", ""))
    }

    @Test
    fun `snapshots are kept per account and replaced whole`() {
        val store = LikedSnapshotStore(tmp.newFolder("snap"))
        assertNull(store.read("dsid:a"))

        store.write("dsid:a", setOf("x", "y"))
        store.write("dsid:b", setOf("z"))
        assertEquals(setOf("x", "y"), store.read("dsid:a"))
        assertEquals(setOf("z"), store.read("dsid:b"))

        store.write("dsid:a", setOf("y"))
        assertEquals(setOf("y"), store.read("dsid:a"))

        // A complete read of an empty LM is still a snapshot, just an empty one.
        store.write("dsid:b", emptySet())
        assertEquals(emptySet<String>(), store.read("dsid:b"))
    }

    @Test
    fun `the whole first sign-in scenario keeps every like and then tracks real unlikes`() {
        val store = LikedSnapshotStore(tmp.newFolder("flow"))
        val key = "dsid:me"
        val signedOutLikes = (1..300).map { LocalLike("s$it", old) }

        // First sync: no snapshot, so nothing is taken back, and LM is remembered.
        val lm1 = setOf("y1", "y2", "y3")
        assertEquals(emptySet<String>(), unlike(signedOutLikes, lm1, snapshot = store.read(key)))
        store.write(key, lm1)

        // The sync adds y1 to y3 locally. Later y2 is unliked on the web.
        val local2 = signedOutLikes + likes("y1", "y2", "y3")
        val lm2 = setOf("y1", "y3")
        assertEquals(setOf("y2"), unlike(local2, lm2, snapshot = store.read(key)))
    }
}

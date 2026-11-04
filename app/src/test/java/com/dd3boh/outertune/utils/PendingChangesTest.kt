/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The changes made in the app that the YouTube Music account has not had yet (his decision of
 * 4 Nov 2026): kept through a restart, sent at the next chance in the order they were made, each
 * forgotten once the account has it, and never sent to another account. The sends are a fake here,
 * standing for YouTube's answers; the file and the rules are the app's.
 */
class PendingChangesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val me = "dsid:me"
    private val other = "dsid:other"

    private fun store(file: File = File(tmp.root, "account_changes.json")) = PendingStore(file)

    /** A fake YouTube: answers each change with [answer] and notes what it was sent. */
    private class Fake(val answer: suspend (Pending) -> Sent = { Sent.Done() }) {
        val sent = mutableListOf<Change>()
        suspend fun send(p: Pending): Sent = answer(p).also { if (it !is Sent.NotNow) sent += p.change }
    }

    private fun PendingStore.flush(fake: Fake, account: String? = me) = runBlocking { send(account) { fake.send(it) } }
    private fun PendingStore.waiting(account: String? = me) = runBlocking { of(account) }.map { it.change }
    private fun PendingStore.keep(change: Change, account: String? = me) = runBlocking { put(account, change) }

    // ---- kept, in order, through a restart ----

    @Test
    fun `changes made with no connection are kept through a restart and sent in the order they were made`() {
        val file = File(tmp.root, "account_changes.json")
        val before = store(file)
        before.keep(Change.Add("PL1", mapId = 7, videoId = "a", duplicate = false))
        before.keep(Change.Like("b", liked = false))
        before.keep(Change.Move("PL1", 7, "a", null, 3, "c", "S3"))

        // The app is closed and opened again: a new store on the same file.
        val after = store(file)
        assertEquals(3, after.waiting().size)
        val fake = Fake { p -> if (p.change is Change.Add) Sent.Done(setVideoId = "S7") else Sent.Done() }
        assertEquals(PendingStore.Flush.DONE, after.flush(fake))
        assertEquals(
            listOf(
                Change.Add("PL1", 7, "a", false),
                Change.Like("b", false),
                // The move names the entry the add was answered with.
                Change.Move("PL1", 7, "a", "S7", 3, "c", "S3"),
            ),
            fake.sent,
        )
        assertTrue("forgotten once sent", after.waiting().isEmpty())
        assertFalse("no file is left once nothing waits", file.exists())
    }

    @Test
    fun `a change YouTube cannot take now stops the line there and keeps everything after it`() {
        val s = store()
        s.keep(Change.Like("a", liked = true))
        s.keep(Change.Like("b", liked = false))
        val offline = Fake { Sent.NotNow }
        assertEquals(PendingStore.Flush.LEFT, s.flush(offline))
        assertEquals(listOf(Change.Like("a", true), Change.Like("b", false)), s.waiting())
        // Back online: both go, in order.
        val online = Fake()
        assertEquals(PendingStore.Flush.DONE, s.flush(online))
        assertEquals(listOf(Change.Like("a", true), Change.Like("b", false)), online.sent)
    }

    @Test
    fun `a change YouTube refuses for good is forgotten and the rest still go`() {
        val s = store()
        s.keep(Change.Remove("PLgone", 1, "a", "S1"))
        s.keep(Change.Like("b", liked = true))
        val fake = Fake { p -> if (p.change is Change.Remove) Sent.Refused else Sent.Done() }
        assertEquals(PendingStore.Flush.DONE, s.flush(fake))
        assertEquals(listOf(Change.Remove("PLgone", 1, "a", "S1"), Change.Like("b", true)), fake.sent)
        assertTrue(s.waiting().isEmpty())
    }

    @Test
    fun `a change that fails for no clear reason is tried again, then given up, so it cannot hold up the rest for good`() {
        val s = store()
        s.keep(Change.Rename("PL1", "New name"))
        s.keep(Change.Like("b", liked = true))
        val failing = Fake { p -> if (p.change is Change.Rename) Sent.Failed else Sent.Done() }
        repeat(PendingStore.MOST_TRIES - 1) {
            assertEquals(PendingStore.Flush.LEFT, s.flush(failing))
            assertEquals(2, s.waiting().size)
        }
        assertEquals(PendingStore.Flush.DONE, s.flush(failing))
        assertTrue(s.waiting().isEmpty())
        assertEquals(Change.Like("b", true), failing.sent.last())
    }

    @Test
    fun `a send that throws is a failure like any other, not a crash`() {
        val s = store()
        s.keep(Change.Like("a", liked = true))
        val throwing = Fake { error("boom") }
        assertEquals(PendingStore.Flush.LEFT, s.flush(throwing))
        assertEquals(1, s.waiting().size)
    }

    // ---- whose account ----

    @Test
    fun `a change is only ever sent to the account it was made for`() {
        val s = store()
        s.keep(Change.Delete("PLmine"), account = me)
        s.keep(Change.Like("x", liked = false), account = other)
        val fake = Fake()
        assertEquals(PendingStore.Flush.DONE, s.flush(fake, account = me))
        assertEquals(listOf<Change>(Change.Delete("PLmine")), fake.sent)
        // The other account's unlike waits for that account, untouched.
        assertEquals(listOf<Change>(Change.Like("x", false)), s.waiting(other))
        assertEquals(PendingStore.Flush.DONE, s.flush(fake, account = me))
        assertEquals(1, fake.sent.size)
    }

    // ---- what goes in the line ----

    @Test
    fun `only the last word on a song's like is sent`() {
        val s = store()
        s.keep(Change.Like("a", liked = false))
        s.keep(Change.Like("b", liked = true))
        s.keep(Change.Like("a", liked = true))
        assertEquals(listOf(Change.Like("b", true), Change.Like("a", true)), s.waiting())
    }

    @Test
    fun `a playlist deleted here takes everything still waiting for it with it`() {
        val s = store()
        s.keep(Change.Add("PL1", 1, "a", false))
        s.keep(Change.Rename("PL1", "x"))
        s.keep(Change.Add("PL2", 2, "b", false))
        s.keep(Change.Delete("PL1"))
        assertEquals(listOf(Change.Add("PL2", 2, "b", false), Change.Delete("PL1")), s.waiting())
    }

    @Test
    fun `a playlist saved again from its page takes back its delete that has not gone out`() {
        val s = store()
        s.keep(Change.Delete("PL1"))
        s.keep(Change.SavePlaylist("PL1", saved = true))
        assertEquals(listOf<Change>(Change.SavePlaylist("PL1", true)), s.waiting())
        // Taken out of the library again, the delete is not brought back: an unsave is all that goes.
        s.keep(Change.SavePlaylist("PL1", saved = false))
        assertEquals(listOf<Change>(Change.SavePlaylist("PL1", false)), s.waiting())
    }

    // ---- finding 6: a song added from the phone, then taken out ----

    @Test
    fun `a song added and taken out again before the add went out is never sent at all`() {
        val s = store()
        s.keep(Change.Add("PL1", mapId = 5, videoId = "wrong", duplicate = false))
        s.keep(Change.Move("PL1", 5, "wrong", null, 4, "b", "S4"))
        s.keep(Change.Remove("PL1", mapId = 5, videoId = "wrong", setVideoId = null))
        // The account never had that row: nothing to add, move or take out.
        assertTrue(s.waiting().isEmpty())
        val fake = Fake()
        s.flush(fake)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `a song taken out while its add is on its way is taken out with the id the add is answered with`() {
        val s = store()
        s.keep(Change.Add("PL1", mapId = 5, videoId = "a", duplicate = false))
        val fake = Fake { p ->
            if (p.change is Change.Add) {
                // The person takes the song out again while YouTube is answering the add. The add
                // cannot be taken back now: the removal has to follow it, naming what it made.
                s.put(me, Change.Remove("PL1", mapId = 5, videoId = "a", setVideoId = null))
                Sent.Done(setVideoId = "S-new")
            } else Sent.Done()
        }
        assertEquals(PendingStore.Flush.DONE, s.flush(fake))
        assertEquals(
            listOf(Change.Add("PL1", 5, "a", false), Change.Remove("PL1", 5, "a", "S-new")),
            fake.sent,
        )
        assertTrue(s.waiting().isEmpty())
    }

    @Test
    fun `a row that does not know its entry stands for one of the same song that no other row holds`() {
        val remote = listOf("x" to "S1", "a" to "S2", "y" to "S3", "a" to "S4")
        // Two copies of a on YouTube, one of them the row still here: the other is the one taken out.
        assertEquals("S4", PendingRules.entryFor("a", remote, held = setOf("S1", "S2", "S3")))
        assertEquals("S2", PendingRules.entryFor("a", remote, held = emptySet()))
        // Every copy is another row's, or the song never got there: nothing to take out.
        assertEquals(null, PendingRules.entryFor("a", remote, held = setOf("S2", "S4")))
        assertEquals(null, PendingRules.entryFor("never-added", remote, held = emptySet()))
        // An entry YouTube gave no id is no entry to name.
        assertEquals(null, PendingRules.entryFor("z", listOf("z" to null), held = emptySet()))
    }

    @Test
    fun `an answer is only learned by the changes of the same account, playlist and row`() {
        val list = listOf(
            Pending(1, me, 0, Change.Remove("PL1", 9, "b", null)),
            Pending(2, other, 0, Change.Remove("PL1", 9, "b", null)),
            Pending(3, me, 0, Change.Remove("PL2", 9, "b", null)),
            Pending(4, me, 0, Change.Move("PL1", 8, "c", null, 9, "b", null)),
        )
        val learned = PendingRules.learned(list, me, "PL1", 9, "S9").map { it.change }
        assertEquals(Change.Remove("PL1", 9, "b", "S9"), learned[0])
        assertEquals(Change.Remove("PL1", 9, "b", null), learned[1])
        assertEquals(Change.Remove("PL2", 9, "b", null), learned[2])
        assertEquals(Change.Move("PL1", 8, "c", null, 9, "b", "S9"), learned[3])
    }

    // ---- finding 7: Remove from library ----

    @Test
    fun `remove from library sends the unlike it makes, for a liked song from YouTube only`() {
        assertEquals(Change.Like("a", liked = false, outOfLibrary = true), PendingRules.outOfLibrary("a", isLocal = false, liked = true))
        // Not liked: nothing to send (the app has no request for the library itself).
        assertEquals(null, PendingRules.outOfLibrary("a", isLocal = false, liked = false))
        // A file on the phone has no YouTube id.
        assertEquals(null, PendingRules.outOfLibrary("LSabcdefgh", isLocal = true, liked = true))
    }

    // ---- the file ----

    @Test
    fun `every kind of change reads back as it was written`() {
        val all = listOf(
            Change.Like("a", true), Change.Like("b", false, outOfLibrary = true),
            Change.SavePlaylist("PL1", false), Change.SaveAlbum("MPRE1", "OLAK1", true),
            Change.Follow("UC1", null, true), Change.Follow("UC2", "UCchan", false),
            Change.Rename("PL1", "Ünïcode \"quoted\" name"), Change.Delete("PL2"),
            Change.Add("PL1", 3, "c", true), Change.Remove("PL1", 4, "d", null), Change.Remove("PL1", 5, "e", "S5"),
            Change.Move("PL1", 6, "f", "S6", 7, "g", null),
        )
        val pending = all.mapIndexed { i, c -> Pending(i + 1L, if (i % 2 == 0) me else null, 1000L + i, c, tries = i % 3) }
        val text = PendingStore.encode(pending.size + 1L, pending)
        assertEquals(pending, PendingStore.decodeForTest(text))
    }

    @Test
    fun `a change the file cannot read is left out, not the others`() {
        val text = """{"version":1,"next":4,"changes":[
            {"seq":1,"account":"dsid:me","at":1,"kind":"like","video":"a","liked":true},
            {"seq":2,"account":"dsid:me","at":2,"kind":"somethingNewer","video":"b"},
            {"seq":3,"account":"dsid:me","at":3,"kind":"like","video":"c"}
        ]}"""
        assertEquals(listOf(Pending(1, me, 1, Change.Like("a", true))), PendingStore.decodeForTest(text))
        assertEquals(emptyList<Pending>(), PendingStore.decodeForTest("not json"))
        assertEquals(emptyList<Pending>(), PendingStore.decodeForTest(null))
    }

    @Test
    fun `the line goes on from the highest number kept, so a restart cannot reuse one`() {
        val file = File(tmp.root, "account_changes.json")
        val s = store(file)
        s.keep(Change.Like("a", true))
        s.keep(Change.Like("b", true))
        val again = store(file)
        again.keep(Change.Like("c", true))
        val seqs = runBlocking { again.all() }.map { it.seq }
        assertEquals(seqs.distinct(), seqs)
        assertEquals(seqs.sorted(), seqs)
    }

    @Test
    fun `an unlike the account has just had still stands for a few minutes, for a read that does not show it yet`() {
        val s = store()
        s.keep(Change.Like("a", liked = false))
        s.keep(Change.Add("PL1", 3, "b", false))
        s.flush(Fake())
        assertTrue(s.waiting().isEmpty())
        val now = System.currentTimeMillis()
        // Read straight after: the unlike still stands, the add does not (the playlist's next sync puts it right).
        val shortly = runBlocking { s.withRecent(me, now + 1_000) }.map { it.change }
        assertEquals(listOf<Change>(Change.Like("a", false)), shortly)
        assertEquals(false, Waiting(runBlocking { s.withRecent(me, now + 1_000) }).likeWanted("a"))
        // Not for another account, and not once the time is up.
        assertTrue(runBlocking { s.withRecent(other, now + 1_000) }.isEmpty())
        assertTrue(runBlocking { s.withRecent(me, now + PendingStore.RECENT_MS + 60_000) }.isEmpty())
    }

    @Test
    fun `what waits comes after what was sent, so the last word made here is the one that stands`() {
        val s = store()
        s.keep(Change.Like("a", liked = false))
        s.flush(Fake())
        s.keep(Change.Like("a", liked = true))
        val view = runBlocking { s.withRecent(me) }
        assertEquals(listOf<Change>(Change.Like("a", false), Change.Like("a", true)), view.map { it.change })
        assertEquals(true, Waiting(view).likeWanted("a"))
    }

    @Test
    fun `forgetting everything empties the line and the file`() {
        val file = File(tmp.root, "account_changes.json")
        val s = store(file)
        s.keep(Change.Like("a", true))
        assertTrue(file.exists())
        runBlocking { s.forgetAll() }
        assertTrue(s.waiting().isEmpty())
        assertFalse(file.exists())
    }
}

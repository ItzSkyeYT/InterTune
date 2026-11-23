/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The plays waiting for ListenBrainz (ListenOutbox): kept across a restart and a write cut short,
 * once each, at most a thousand, per account, and gone on disconnecting.
 */
class ListenOutboxTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, ListenOutbox.FILE)

    /** An outbox over the file, as the app makes one at every start. */
    private fun outbox() = ListenOutbox(file)

    private fun play(n: Int, user: String = "mel") =
        WaitingListen(user = user, json = """{"listened_at":${1795000000 + n},"track_metadata":{"artist_name":"A","track_name":"Song $n"}}""", keptAt = 1_000L + n)

    @Test
    fun `a play kept is still there after a restart, exactly as it was made`() = runBlocking<Unit> {
        outbox().keep(play(1))
        outbox().keep(play(2))
        // The app is closed and opened again: a new outbox over the same file.
        assertEquals(listOf(play(1), play(2)), outbox().of("mel"))
    }

    @Test
    fun `it is written beside and renamed, so no half-written file is left`() = runBlocking<Unit> {
        outbox().keep(play(1))
        assertTrue(file.exists())
        assertFalse(File(file.path + ".new").exists())
        assertEquals(listOf(play(1)), ListenOutbox.decode(file.readText()))
    }

    @Test
    fun `a write cut short leaves the list it had`() = runBlocking<Unit> {
        outbox().keep(play(1))
        // A phone that died while writing the next list leaves it beside, never in place.
        File(file.path + ".new").writeText("{\"v\":1,\"waiting\":[{\"user\":\"mel\",\"keptAt\":1")
        assertEquals(listOf(play(1)), outbox().of("mel"))
    }

    @Test
    fun `a play kept twice stays once, and the list is oldest first`() = runBlocking<Unit> {
        val box = outbox()
        box.keep(play(3))
        box.keep(play(1))
        box.keep(play(3))
        assertEquals(listOf(play(1), play(3)), box.of("mel"))
    }

    @Test
    fun `at most a thousand wait, and the oldest go first`() {
        val full = (1..ListenOutbox.MOST).map { play(it) }
        val after = ListenOutbox.kept(full, play(ListenOutbox.MOST + 1))
        assertEquals(ListenOutbox.MOST, after.size)
        assertEquals(play(2), after.first())
        assertEquals(play(ListenOutbox.MOST + 1), after.last())
    }

    @Test
    fun `what is sent or refused comes off, and nothing else`() = runBlocking<Unit> {
        val box = outbox()
        (1..4).forEach { box.keep(play(it)) }
        box.remove(listOf(play(2), play(4)))
        assertEquals(listOf(play(1), play(3)), outbox().of("mel"))
    }

    @Test
    fun `a token for another account drops what the one before played`() = runBlocking<Unit> {
        val box = outbox()
        box.keep(play(1, user = "before"))
        box.keep(play(2, user = "now"))
        assertEquals(listOf(play(1, user = "before")), box.of("before"))
        box.keepOnly("now")
        assertEquals(emptyList<WaitingListen>(), outbox().of("before"))
        assertEquals(listOf(play(2, user = "now")), outbox().of("now"))
    }

    @Test
    fun `disconnecting empties it, file and all`() = runBlocking<Unit> {
        val box = outbox()
        box.keep(play(1))
        box.clear()
        assertFalse(file.exists())
        assertTrue(outbox().of("mel").isEmpty())
        assertTrue(box.waiting.first().isEmpty())
    }

    @Test
    fun `the file goes once nothing waits, so its being there says something waits`() = runBlocking<Unit> {
        val box = outbox()
        box.keep(play(1))
        box.remove(listOf(play(1)))
        assertFalse(file.exists())
    }

    @Test
    fun `a file it cannot read starts over, without a throw`() = runBlocking<Unit> {
        file.writeText("not json at all")
        assertTrue(outbox().of("mel").isEmpty())
        file.writeText("""{"v":99,"waiting":[]}""")
        assertTrue(outbox().of("mel").isEmpty())
        // One entry it cannot read costs that entry only.
        file.writeText("""{"v":1,"waiting":[{"user":"mel","keptAt":1},{"user":"mel","keptAt":2,"listen":"{}"}]}""")
        assertEquals(listOf(WaitingListen("mel", "{}", 2)), outbox().of("mel"))
    }
}

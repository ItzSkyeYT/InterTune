/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest playing its own song can propose it to the one in control, with the queue its own settings
 * made after it (his ask of 17 Oct 2026). They are asked; on a yes it plays them from where the guest
 * is, and the guest is back with the room.
 */
class TogetherProposeTest {
    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()

    @Test
    fun `a proposal goes and comes back whole`() {
        val songs = listOf(
            TogetherMessage.Song("aaaaaaaaaaa", "First", "Someone", "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg"),
            TogetherMessage.Song("bbbbbbbbbbb", "Second, \"quoted\"", "Someone else", ""),
        )
        val propose = TogetherMessage.Propose(songs, positionMs = 61_250, by = "Phone B")
        assertEquals(propose, TogetherMessage.decode(TogetherMessage.encode(propose)))
    }

    @Test
    fun `the guest sends its own song and the queue after it, and the one in control is asked`() {
        val propose = session.substringAfter("fun propose()").substringBefore("\n    }\n")
        assertTrue(propose.contains("ownQueue()"))
        assertTrue(propose.contains("TogetherMessage.Propose("))
        val heard = session.substringAfter("is TogetherMessage.Propose ->").substringBefore("\n            is ")
        assertTrue("only the one who leads is asked", heard.contains("if (leading"))
        assertTrue(heard.contains("val proposal = Proposal("))
    }

    @Test
    fun `a yes plays the song now from where the guest is, and the guest is back with the room`() {
        assertTrue(session.substringAfter("fun acceptProposal(withQueue: Boolean = false)").substringBefore("\n    }\n").contains("ProposalLine.toPlay(proposal, withQueue)"))
        // The song alone unless the songs after it were chosen (#testers, 22 Nov 2026: "did it send the whole queue?"),
        // put in next with the room's queue kept after it, never in place of it (playNow, 26 Nov 2026).
        val accept = session.substringAfter("private fun playNow(").substringBefore("\n    }\n")
        assertTrue(accept.contains("service.enqueueNext(songs.map { it.toMediaItem() })"))
        assertFalse("the room's queue is never replaced", accept.contains("replace = true"))
        assertTrue("from the guest's spot, not the start", accept.contains("player.seekTo(index, ProposalLine.startAt("))
        assertTrue(session.contains("message.songId == proposedSongId"))
    }

    @Test
    fun `the one in control is asked wherever it is, and no guest goes its own way to propose any more`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        // Since 26 Nov 2026 (Spotify's Jam) a guest listens or leaves: the sheet's Own way, with its Propose, is gone. The
        // card still takes a proposal from a guest on an older build.
        assertFalse(sheet.contains("private fun OwnWay("))
        assertFalse(sheet.contains("room.propose()"))
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue(main.contains("TogetherProposalCard("))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            assertFalse("never \"the host\" for the one in control: $path", Regex("together_propos[a-z_]*\">[^<]*(host|hôte)").containsMatchIn(strings))
            for (name in listOf("together_proposal_title", "together_proposal_more", "together_proposal_play", "together_proposal_later")) {
                assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
            }
        }
    }
}

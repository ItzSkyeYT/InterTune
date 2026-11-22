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
    fun `a yes plays them from where the guest is, and the guest is back with the room`() {
        val accept = session.substringAfter("fun acceptProposal()").substringBefore("\n    }\n")
        assertTrue(accept.contains("ListQueue(items = songs)"))
        assertTrue("from the guest's spot, not the start", accept.contains("seekTo("))
        assertTrue(session.contains("message.songId == proposedSongId"))
    }

    @Test
    fun `the sheet offers it to a guest on its own, and the one in control is asked wherever it is`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        val ownWay = sheet.substringAfter("private fun OwnWay(").substringBefore("\n}\n")
        assertTrue(ownWay.contains("R.string.together_propose"))
        // Named for the one in control, who is the one asked, not "the host" (22 Nov 2026: dvd had control).
        assertTrue(ownWay.contains("R.string.together_propose_to, to") && ownWay.contains("R.string.together_proposed_to, to"))
        assertTrue(sheet.contains("to = status.leaderName,"))
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue(main.contains("TogetherProposalCard("))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            assertFalse("never \"the host\" for the one in control: $path", Regex("together_propos[a-z_]*\">[^<]*(host|hôte)").containsMatchIn(strings))
            for (name in listOf("together_propose", "together_proposed", "together_propose_to", "together_proposed_to", "together_proposal_title", "together_proposal_more", "together_proposal_play", "together_proposal_later")) {
                assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
            }
        }
    }
}

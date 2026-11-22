/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.together.TogetherSession.Proposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The songs guests propose to the phone in control wait for Play it or Not now (22 Nov 2026, #testers: dvd, in control,
 * was typing when a proposal came up as a dialog, tapped beside it and it was gone for good). A second guest's proposal
 * no longer takes the place of the first one's unseen, and nothing but the two answers or control moving on ends one.
 */
class ProposalLineTest {
    private fun proposal(from: String, song: String, at: Long = 0L) =
        Proposal(listOf(TogetherMessage.Song(song, "Title $song", "Artist", "")), positionMs = 10_000, by = "Guest $from", receivedAt = at, from = from)

    @Test
    fun `each guest's proposal waits, the oldest first`() {
        val niko = proposal("niko", "aaaaaaaaaaa")
        val tidie = proposal("tidie", "bbbbbbbbbbb")
        val line = ProposalLine.add(ProposalLine.add(emptyList(), niko), tidie)
        assertEquals(listOf(niko, tidie), line)
    }

    @Test
    fun `a guest's newer proposal takes the place of its older one, where it was`() {
        val first = proposal("niko", "aaaaaaaaaaa")
        val other = proposal("tidie", "bbbbbbbbbbb")
        val newer = proposal("niko", "ccccccccccc", at = 5_000)
        val line = ProposalLine.add(ProposalLine.add(ProposalLine.add(emptyList(), first), other), newer)
        assertEquals(listOf(newer, other), line)
    }

    @Test
    fun `proposals with no key are never merged`() {
        val one = proposal("", "aaaaaaaaaaa")
        val two = proposal("", "bbbbbbbbbbb")
        assertEquals(listOf(one, two), ProposalLine.add(ProposalLine.add(emptyList(), one), two))
    }

    @Test
    fun `an answer takes that one away and the next one is asked`() {
        val niko = proposal("niko", "aaaaaaaaaaa")
        val tidie = proposal("tidie", "bbbbbbbbbbb")
        val line = listOf(niko, tidie)
        assertEquals(listOf(tidie), ProposalLine.without(line, niko))
        assertEquals(emptyList<Proposal>(), ProposalLine.without(listOf(tidie), tidie))
    }

    @Test
    fun `a yes plays the song alone, and the songs after it only when chosen`() {
        // Every proposal carries the guest's queue after its song, an old guest's too: dvd's carried 29 songs.
        val queue = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").map { TogetherMessage.Song(it, "Title $it", "Artist", "") }
        val proposal = Proposal(queue, positionMs = 10_000, by = "dvd", receivedAt = 0L, from = "dvd")
        assertEquals(listOf("aaaaaaaaaaa"), ProposalLine.toPlay(proposal, withQueue = false).map { it.id })
        assertEquals(queue, ProposalLine.toPlay(proposal, withQueue = true))
        val alone = proposal.copy(songs = queue.take(1))
        assertEquals(queue.take(1), ProposalLine.toPlay(alone, withQueue = true))
    }

    @Test
    fun `a quick answer plays from where the guest is by now, a late one from the top`() {
        assertEquals(10_000L + 4_000L, ProposalLine.startAt(10_000, 4_000))
        assertEquals(10_000L + ProposalLine.QUICK_ANSWER_MS, ProposalLine.startAt(10_000, ProposalLine.QUICK_ANSWER_MS))
        // By then the guest is often past the song's end, where a seek would skip it for everyone.
        assertEquals(0L, ProposalLine.startAt(10_000, ProposalLine.QUICK_ANSWER_MS + 1))
        assertEquals(0L, ProposalLine.startAt(200_000, 10 * 60_000))
        // A clock that went back is no wait at all.
        assertEquals(0L, ProposalLine.startAt(10_000, -1))
    }

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()

    @Test
    fun `only Play it and Not now end a proposal, and control moving on`() {
        val heard = session.substringAfter("is TogetherMessage.Propose ->").substringBefore("\n            is ")
        assertTrue("it joins the line, never in place of another guest's", "ProposalLine.add(" in heard)
        for (answer in listOf("fun acceptProposal(withQueue: Boolean = false)", "fun dismissProposal()")) {
            assertTrue(answer, answer in session)
            val body = session.substringAfter(answer).substringBefore("\n    }\n")
            assertTrue("$answer answers the one asked", "proposals.firstOrNull()" in body && "ProposalLine.without(" in body)
        }
        // Proposals are for whoever is in control: the old one's can't play them, and a guest's Propose comes back.
        val lead = session.substringAfter("private fun lead(key: String)").substringBefore("\n    }\n")
        assertTrue("if (key != leaderKey) proposalsAfterLeadMoved()" in lead)
        val moved = session.substringAfter("private fun proposalsAfterLeadMoved()").substringBefore("\n    }\n")
        assertTrue("proposals = emptyList()" in moved && "proposed = false" in moved && "proposedSongId = null" in moved)
        // Nothing else empties the line.
        assertEquals(1, Regex("proposals = emptyList\\(\\)").findAll(session).count())
    }

    @Test
    fun `the card asks wherever the app is, and a tap beside it or Back never throws it away`() {
        val card = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherProposals.kt").readText()
        // Not a dialog: a dialog's tap outside and Back are its dismissal, and that was the proposal's end.
        assertFalse("AlertDialog(" in card || "Dialog(" in card || "onDismissRequest" in card || "BackHandler" in card)
        assertTrue("onClick = { if (armed) onNotNow() }" in card)
        assertTrue("delay(PROPOSAL_TAP_GUARD_MS)" in card)
        // The songs after it are a box to tick, off for each new proposal, and only when there are some.
        assertTrue("var withQueue by remember(proposal) { mutableStateOf(false) }" in card)
        assertTrue(card.indexOf("if (more > 0) {") in 0 until card.indexOf("Checkbox(checked = withQueue"))
        assertTrue("onPlay(withQueue)" in card)
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("on every screen, over the player too", "TogetherProposalCard(playerConnection, Modifier.align(Alignment.TopCenter))" in main)
        assertFalse("TogetherProposalDialog(" in main)
        // And in the room's sheet, which covers the card.
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertTrue("if (status.leading) status.proposals.firstOrNull()?.let {" in sheet)
    }
}

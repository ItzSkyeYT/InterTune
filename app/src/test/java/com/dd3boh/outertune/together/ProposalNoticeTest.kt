/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.together.ProposalLine.NoticeStep
import com.dd3boh.outertune.together.TogetherSession.Proposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one in control in another app gets a proposal as a notification, with Not now and Play it (his pick of
 * 22 Nov 2026, "Also a notification"), and a guest told Not now gets its Propose back instead of "Proposed" for good.
 */
class ProposalNoticeTest {
    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val notice = File("src/main/java/com/dd3boh/outertune/together/ProposalNotice.kt").readText()

    private fun body(of: String, text: String = session): String {
        assertTrue("$of is gone", of in text)
        return text.substringAfter(of).substringBefore("\n    }\n")
    }

    private fun proposal(from: String, at: Long) =
        Proposal(listOf(TogetherMessage.Song("dQw4w9WgXcQ", "A song", "Someone", "")), positionMs = 1_000, by = from, receivedAt = at, from = from)

    @Test
    fun `the notification shows the one asked while the app is not in front, and goes once it is answered`() {
        val niko = proposal("niko", 1L)
        val dvd = proposal("dvd", 2L)
        // A proposal comes while InterTune is not in front: it pops up.
        assertEquals(NoticeStep.SHOW, ProposalLine.noticeStep(first = niko, showing = null, inFront = false))
        // In front, the card asks instead.
        assertEquals(NoticeStep.HIDE, ProposalLine.noticeStep(first = niko, showing = null, inFront = true))
        // Another one behind it changes nothing.
        assertEquals(NoticeStep.KEEP, ProposalLine.noticeStep(first = niko, showing = niko, inFront = false))
        // Answered from the notification, the next one waiting takes its place; from the card, it goes.
        assertEquals(NoticeStep.SHOW, ProposalLine.noticeStep(first = dvd, showing = niko, inFront = false))
        assertEquals(NoticeStep.HIDE, ProposalLine.noticeStep(first = dvd, showing = niko, inFront = true))
        // Nothing left, control moved or the room is over: gone.
        assertEquals(NoticeStep.HIDE, ProposalLine.noticeStep(first = null, showing = niko, inFront = false))
    }

    @Test
    fun `the session keeps the notification with the line, wherever the answer comes from`() {
        assertTrue("the session no longer keeps the notification with the line", "private fun syncNotice()" in session)
        val heard = session.substringAfter("is TogetherMessage.Propose ->").substringBefore("\n            is ")
        assertTrue("syncNotice()" in heard)
        for (where in listOf("fun acceptProposal(withQueue: Boolean = false)", "fun dismissProposal()", "private fun proposalsAfterLeadMoved()")) {
            assertTrue("$where leaves the notification as it was", "syncNotice()" in body(where))
        }
        val sync = body("private fun syncNotice()")
        assertTrue("only while this phone is in control", "proposals.firstOrNull()?.takeIf { leading }" in sync)
        assertTrue("ProposalLine.noticeStep(first, notice.showing, notice.inFront())" in sync)
        // Its buttons reach the room's own receiver, and do what the card's do, to the one it showed alone.
        val receiver = session.substringAfter("private val noticeAnswer = object : BroadcastReceiver() {").substringBefore("\n    }\n")
        assertTrue("intent.getLongExtra(ProposalNotice.EXTRA_AT, -1L) != first.receivedAt" in receiver)
        assertTrue("ProposalNotice.PLAY -> acceptProposal()" in receiver && "ProposalNotice.NOT_NOW -> dismissProposal()" in receiver)
        val start = body("fun start()")
        assertTrue("ContextCompat.registerReceiver(service, noticeAnswer, IntentFilter(ProposalNotice.action(service)), ContextCompat.RECEIVER_NOT_EXPORTED)" in start)
        val stop = body("fun stop()")
        assertTrue("notice.hide()" in stop && "service.unregisterReceiver(noticeAnswer)" in stop)
    }

    @Test
    fun `no notification without Android's leave to post, and its buttons are the card's`() {
        val show = body("fun show(proposal: Proposal)", notice)
        val asked = show.indexOf("ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED")
        assertTrue("posted without asking whether it may", asked in 0 until show.indexOf(".notify(NOTIFICATION_ID"))
        assertTrue("its own channel, named in the app's language", "NotificationChannel(CHANNEL_ID, context.getString(R.string.together_proposals_channel)" in show)
        assertTrue("R.string.together_proposal_notice, proposal.by" in show)
        assertTrue("addAction(0, context.getString(R.string.together_proposal_later), answer(NOT_NOW, proposal))" in show)
        assertTrue("addAction(0, context.getString(R.string.together_proposal_play), answer(PLAY, proposal))" in show)
        val answer = notice.substringAfter("private fun answer(").substringBefore("\n    )\n")
        assertTrue("setPackage(context.packageName)" in answer && "PendingIntent.FLAG_IMMUTABLE" in answer)
        assertEquals("together_proposals", ProposalNotice.CHANNEL_ID)
    }

    @Test
    fun `Not now reaches the guest, whose Propose comes back, and an old build drops the word`() {
        val notNow = TogetherMessage.NotNow(to = "ab12cd34", songId = "dQw4w9WgXcQ")
        assertEquals(notNow, TogetherMessage.decode(TogetherMessage.encode(notNow)))
        // A build before this one reads any word it does not know as nothing, as it would read this one.
        assertNull(TogetherMessage.decode("""{"t":"somethingnew","to":"ab12cd34","song":"dQw4w9WgXcQ"}"""))
        // Sent by Not now alone, to the guest who proposed, about its song.
        assertTrue("TogetherMessage.NotNow(proposal.from, proposal.songs.first().id)" in body("fun dismissProposal()"))
        assertFalse("NotNow" in body("fun acceptProposal(withQueue: Boolean = false)"))
        // Taken only by that guest, from the one in control, for the song it proposed.
        val heard = session.substringAfter("is TogetherMessage.NotNow ->").substringBefore("\n            is ")
        assertTrue("message.to == me && from == leaderKey && message.songId == proposedSongId" in heard)
        assertTrue("proposedSongId = null" in heard && "proposed = false" in heard)
        assertTrue("R.string.together_proposal_not_now" in heard)
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("together_proposal_notice", "together_proposals_channel", "together_proposal_not_now")) {
                assertTrue("$name missing from $path", "name=\"$name\"" in strings)
            }
        }
    }
}

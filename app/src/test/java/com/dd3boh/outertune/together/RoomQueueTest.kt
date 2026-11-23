/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.ui.player.queueIsTheRooms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest moved a song to play next in the room's queue: "it was next for like 20 seconds and now its back to 3rd", "it
 * was only client side" (#testers, 22 Nov 2026). A guest that follows plays the queue of the person in control, and each
 * word of theirs puts their order back on the guest; a guest's drag or swipe only ever changed its own copy, and nothing
 * sent it anywhere. Now the queue offers a guest that follows no drag, no swipe and no Remove from queue, and says whose
 * it is. The person in control changes it for everyone, and a guest on its own changes its own, as before.
 */
class RoomQueueTest {

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
    private val events = session.substringAfter("override fun onEvents(player: Player, events: Player.Events) {").substringBefore("fun start()")
    private val onQueue = session.substringAfter("is TogetherMessage.Queue -> if (!leading) {").substringBefore("is TogetherMessage.Propose")

    @Test
    fun `the queue is the room's on a guest that follows, and only then`() {
        assertTrue(queueIsTheRooms(inRoom = true, leading = false, ownWay = false))
        // in control, the owner or a guest given control: its queue is everyone's, and it changes it
        assertFalse(queueIsTheRooms(inRoom = true, leading = true, ownWay = false))
        // on its own until Catch up: its queue is its own again
        assertFalse(queueIsTheRooms(inRoom = true, leading = false, ownWay = true))
        assertFalse(queueIsTheRooms(inRoom = false, leading = false, ownWay = false))
    }

    /** Why a guest's change came back, in the session's own words: the cause the queue's rule answers. */
    @Test
    fun `a follower's queue is put back to the leader's at each word of it, and its own changes go nowhere`() {
        assertTrue("if (!ownWay) {" in onQueue)
        assertTrue("matchQueue()" in onQueue.substringAfter("if (!ownWay) {"))
        val match = session.substringAfter("private fun matchQueue() {").substringBefore("private fun upcoming(")
        assertTrue("if (here == wanted) return" in match)
        assertTrue("replace = true," in match)
        // The leader's queue goes again every few beats, changed or not (15 of 2 s): "next for like 20 seconds".
        assertTrue("if (++beats % QUEUE_EVERY_BEATS == 0) sendQueue()" in session)
        // A follower's own changes are sent nowhere: its player's events are the room's only while it leads.
        assertTrue(events.trimStart().startsWith("if (!leading) return"))
    }

    @Test
    fun `the person in control's moves and removals go to everyone`() {
        // A drag moves the player's song (moveMediaItem), a swipe or Remove from queue takes it out (removeMediaItem):
        // either changes the player's timeline, and the queue goes out once the player is done (queueSoon, sendQueue).
        assertTrue("playerConnection.player.moveMediaItem(from, to)" in queue)
        assertTrue("if (loaded) playerConnection.player.removeMediaItem(swipedIndex)" in queue)
        assertTrue("if (loaded) playerConnection.player.removeMediaItem(place)" in queue)
        assertTrue("Player.EVENT_TIMELINE_CHANGED" in events.substringAfter(") sendState()").substringBefore(") queueSoon()"))
        assertTrue("sendQueue()" in session.substringAfter("private fun queueSoon() {").substringBefore("private fun sendQueue()"))
        // and each follower takes it from whoever leads
        assertTrue("if (from == leaderKey) {\n                    roomQueue = message" in onQueue)
    }

    @Test
    fun `a guest that follows gets no drag, no swipe and no Remove from queue, and a line over the songs`() {
        assertTrue(
            "derivedStateOf { roomStatus?.value.let { queueIsTheRooms(inRoom = it != null, leading = it?.leading == true, ownWay = it?.ownWay == true) } }" in queue
        )
        // the handle a song is dragged by, the songs' only one
        assertTrue("if (!lockQueue && !roomFixed && (!detachedHead || movesStoredQueue) && !isSearching) {" in queue)
        assertEquals(1, Regex("""\.draggableHandle\(\)""").findAll(queue.substringAfter("val songList:")).count())
        // the swipe that takes a song away, one box for every row (QueueSwipePickedTest)
        assertTrue("if (!lockQueue && !roomFixed && (!inSelectMode || Unreleased.QUEUE_SWIPE_PICKED) && (!detachedHead || movesStoredQueue)) {" in queue)
        // the selection's Remove from queue, and with it the swipe of the picked songs
        assertTrue("val removeSelected: (() -> Unit)? = if (lockQueue || roomReadOnly || (detachedHead && !movesStoredQueue)) null else {" in queue)
        // Read-only unless the host lets everyone change the queue and the person in control takes a guest's changes
        // (RoomArrangeTest); then the song the room plays alone stays put.
        assertTrue("val roomReadOnly = roomsQueue && !roomArranges" in queue)
        assertTrue("val roomFixed = roomReadOnly || (roomArranges && index == currentWindowIndex)" in queue)
        // the line, over the songs and out of the list, while the queue on show is the one that plays
        val list = queue.substringAfter("val songList: @Composable ColumnScope.(PaddingValues) -> Unit").substringBefore("LazyColumn(")
        assertTrue("val roomLine = roomsQueue && (!detachedHead || movesStoredQueue)" in list)
        assertTrue("leaderName = roomLeaderName," in list.substringAfter("if (roomLine) {"))
        assertTrue(list.indexOf("QueueRoomLine(") < list.indexOf(".movesListFromHeldRow(lazySongsListState)"))
    }

    /**
     * The room's status changes four times a second while a guest follows a room that plays (its drift, in follow): the
     * queue reads from it only whose queue it is and who chooses, through derivedStateOf, so it is composed again only
     * when one of them changes, never at each look.
     */
    @Test
    fun `the queue is composed again only when what it shows of the room changes`() {
        assertTrue("driftMs = target - local" in session.substringAfter("private fun follow() {").substringBefore("fun holdsFailed("))
        assertTrue("val roomStatus = room?.status?.collectAsState()\n" in queue)
        assertEquals("the status read three times, each in a derivedStateOf", 3, Regex("""roomStatus\?\.value""").findAll(queue).count())
        assertEquals(3, Regex("""derivedStateOf \{ roomStatus\?\.value""").findAll(queue).count())
        assertTrue("val roomsQueue by remember(roomStatus) {" in queue)
        assertTrue("val roomLeaderName by remember(roomStatus) { derivedStateOf { roomStatus?.value?.leaderName } }" in queue)
    }

    /** The line names the song menu's item as the menu says it, in English and in French. */
    @Test
    fun `the line says whose queue it is and how to add a song`() {
        fun string(file: String, name: String) =
            Regex("""<string name="$name">(.*?)</string>""").find(File(file).readText())?.groupValues?.get(1) ?: error("$name in $file")
        for ((ot, menu) in listOf(
            "src/main/res/values/strings-ot.xml" to "src/main/res/values/strings.xml",
            "src/main/res/values-fr/strings-ot.xml" to "src/main/res/values-fr/strings.xml",
        )) {
            val playNext = string(menu, "play_next")
            val theirs = string(ot, "together_queue_theirs")
            val host = string(ot, "together_queue_host")
            assertTrue(theirs, theirs.startsWith("%1\$s "))
            for (line in listOf(theirs, host)) {
                assertTrue("$line names $playNext", playNext in line)
                assertFalse(line, "—" in line)
            }
        }
    }
}

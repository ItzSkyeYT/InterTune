/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guests changing the room's queue, on the host's word (22 Nov 2026, "can guests rearrange the room's queue?": "guests
 * only, but they can be given perms to do so"). A guest's queue is the room's to look at (RoomQueueTest) unless the host
 * turns on Let everyone change the queue (RoomSettings.everyoneArranges). Then a guest moves and takes out songs as ever,
 * all but the one playing, and each change goes to the person in control as an Arrange, made there as its own drag or
 * swipe, so its next queue brings it to everyone. A build before this one drops an Arrange, so a guest changes the queue
 * only where the person in control said in its Here that it takes them.
 */
class RoomArrangeTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()
    private val protocol = File(src, "together/TogetherProtocol.kt").readText()
    private val queue = File(src, "ui/player/Queue.kt").readText()

    /** A guest following the room, whose host lets everyone change the queue, under a person in control who takes it. */
    private val guest = TogetherSession.Status(waitingForHost = false, settings = RoomSettings(everyoneArranges = true), leaderArranges = true)

    private fun roundTrip(message: TogetherMessage) = TogetherMessage.decodeFrom(TogetherMessage.encode(message, sender = "ab12"), "ab12")

    @Test
    fun `a guest's move and removal travel as they were made`() {
        val moved = TogetherMessage.Arrange("song-b", after = "song-a")
        assertEquals(moved, roundTrip(moved))
        val next = TogetherMessage.Arrange("song-b")
        assertEquals(next, roundTrip(next))
        assertNull((roundTrip(next) as TogetherMessage.Arrange).after)
        val removed = TogetherMessage.Arrange("song-b", remove = true)
        assertEquals(removed, roundTrip(removed))
        val o = Json.parseToJsonElement(TogetherMessage.encode(moved, sender = "ab12")).jsonObject
        assertEquals("arrange", o["t"]!!.jsonPrimitive.content)
        assertEquals("song-b", o["song"]!!.jsonPrimitive.content)
        assertEquals("song-a", o["after"]!!.jsonPrimitive.content)
        assertNull(o["remove"])
    }

    @Test
    fun `a build that does not know a word drops it, so an Arrange goes only where it is taken`() {
        // What every build reads a word with: a kind it does not know is nothing.
        assertNull(TogetherMessage.decodeFrom("""{"t":"rearrange","song":"song-b","k":"ab12"}""", "ab12"))
        assertTrue("                else -> null\n" in protocol.substringAfter("private fun decodeObject("))
        // Every Here of this build says it takes them; one from a build before says nothing, and is taken as no.
        assertTrue("arranges = true," in session.substringAfter("private fun sayHere() = send(").substringBefore("\n    )\n"))
        val here = TogetherMessage.Here("dvd", owner = false, arranges = true)
        assertTrue((roundTrip(here) as TogetherMessage.Here).arranges)
        val old = TogetherMessage.decodeFrom("""{"t":"here","name":"dvd","owner":false,"k":"ab12"}""", "ab12") as TogetherMessage.Here
        assertFalse(old.arranges)
        // Each phone's own word on it, kept by its key, and the one in control's read for the guest.
        assertTrue("if (message.arranges) arrangers += from else arrangers -= from" in session)
        assertTrue("leaderArranges = leaderKey.let { it != null && (it == me || it in arrangers) }," in session)
    }

    @Test
    fun `off by default, and off in a room whose host is on a build before the switch`() {
        assertFalse(RoomSettings().everyoneArranges)
        assertFalse(RoomSettings.of(JsonObject(emptyMap()))!!.everyoneArranges)
        assertTrue(RoomSettings.of(JsonObject(mapOf("arrange" to JsonPrimitive(true))))!!.everyoneArranges)
        val on = TogetherMessage.Here("Ti-Die", owner = true, lead = "ab12", settings = RoomSettings(everyoneArranges = true), arranges = true)
        assertEquals(on, roundTrip(on))
        assertFalse(guestArranges(guest.copy(settings = null)))
        assertFalse(guestArranges(guest.copy(settings = RoomSettings())))
    }

    @Test
    fun `a guest changes the queue only while it follows, the host lets it, and the one in control takes it`() {
        assertTrue(guestArranges(guest))
        assertTrue(guestArranges(guest.copy(pausedHere = true)))
        // The one in control on a build before this one.
        assertFalse(guestArranges(guest.copy(leaderArranges = false)))
        // In control, its queue is everyone's and its own to change; on its own way or casting, its own.
        assertFalse(guestArranges(guest.copy(leading = true)))
        assertFalse(guestArranges(guest.copy(ownWay = true)))
        assertFalse(guestArranges(guest.copy(casting = true)))
        assertFalse(guestArranges(guest.copy(waitingForHost = true)))
        // And it sends nothing otherwise.
        for (name in listOf("fun arrangeMoved(songId: String, after: String?) {", "fun arrangeRemoved(songId: String) {")) {
            val body = session.substringAfter(name).substringBefore("\n    }\n")
            assertTrue(name, body.trimStart().startsWith("if (!guestArranges(_status.value)) return"))
        }
    }

    @Test
    fun `the song is found after the song playing, never the song playing itself`() {
        val ids = listOf("a", "now", "b", "c", "b", "d")
        assertEquals(2, RoomArrange.placeOf(ids, "b", current = 1))
        assertNull(RoomArrange.placeOf(ids, "now", current = 1))
        // Played already, before the song playing: not the room's to change.
        assertNull(RoomArrange.placeOf(ids, "a", current = 1))
        assertNull(RoomArrange.placeOf(ids, "zz", current = 1))
    }

    @Test
    fun `a move lands right after the song it was put after, as moveMediaItem counts`() {
        val ids = listOf("now", "a", "b", "c")
        fun moved(from: Int, to: Int) = ids.toMutableList().apply { add(to, removeAt(from)) }
        // Down: c after a.
        assertEquals(listOf("now", "a", "c", "b"), moved(3, RoomArrange.moveTo(ids, from = 3, after = "a", current = 0)!!))
        // Up: a after c.
        assertEquals(listOf("now", "b", "c", "a"), moved(1, RoomArrange.moveTo(ids, from = 1, after = "c", current = 0)!!))
        // First after the song playing, by no song or by the song playing itself.
        assertEquals(listOf("now", "c", "a", "b"), moved(3, RoomArrange.moveTo(ids, from = 3, after = null, current = 0)!!))
        assertEquals(1, RoomArrange.moveTo(ids, from = 3, after = "now", current = 0))
        // A song not there, or only before the song playing: nothing moves.
        assertNull(RoomArrange.moveTo(ids, from = 3, after = "zz", current = 0))
        assertNull(RoomArrange.moveTo(listOf("a", "now", "b"), from = 2, after = "a", current = 1))
        // Where it already is.
        assertEquals(2, RoomArrange.moveTo(ids, from = 2, after = "a", current = 0))
    }

    @Test
    fun `the one in control makes it as its own drag or swipe, and only while the host lets everyone`() {
        assertTrue("is TogetherMessage.Arrange -> if (leading && _status.value.settings?.everyoneArranges == true) arranged(message, from)" in session)
        val made = session.substringAfter("private fun arranged(message: TogetherMessage.Arrange, from: String) {").substringBefore("\n    }\n")
        // The queue board and the player alike, as the queue sheet does (Queue.kt), so the next queue takes it to everyone.
        assertTrue("if (!service.queueBoard.removeCurrentQueueSong(at)) return\n            player.removeMediaItem(at)" in made)
        assertTrue("service.queueBoard.moveSong(at, to)\n            player.moveMediaItem(at, to)" in made)
        assertTrue("qb.moveSong(from, to)" in queue && "playerConnection.player.moveMediaItem(from, to)" in queue)
        assertTrue("val at = RoomArrange.placeOf(ids, message.songId, current) ?: return" in made)
        assertTrue("Toaster.show(service, service.getString(R.string.together_arranged, by))" in made)
        // The owner's setting, set once for its rooms as its name is, unset being off (RoomSettingsTest).
        assertTrue("everyoneArranges = prefs[TogetherEveryoneArrangesKey] == true" in session)
    }

    @Test
    fun `a guest's queue sends each change it makes, and never touches the song playing`() {
        assertTrue("val roomArranges by remember(roomStatus) { derivedStateOf { roomStatus?.value?.let(::guestArranges) == true } }" in queue)
        assertTrue("if (roomArranges) mutableSongs.getOrNull(to)?.id?.let { room?.arrangeMoved(it, after = mutableSongs.getOrNull(to - 1)?.id) }" in queue)
        assertTrue("if (roomArranges) room?.arrangeRemoved(window.id)" in queue)
        assertTrue("if (roomArranges) room?.arrangeRemoved(song.id)" in queue)
        // The song playing: no handle, no swipe, and left out of a Remove of the songs picked.
        assertTrue("val roomFixed = roomReadOnly || (roomArranges && index == currentWindowIndex)" in queue)
        assertTrue("if (roomArranges && place == currentWindowIndex) continue" in queue)
        // No Undo on a guest: the next queue from the person in control would take it back.
        assertTrue("if (!roomArranges) offerUndo(queueId, taken, notes)" in queue)
        assertTrue("if (!roomArranges) offerUndo(queueId, listOf(QueueUndo.Taken(swipedIndex, window)), notes)" in queue)
    }
}

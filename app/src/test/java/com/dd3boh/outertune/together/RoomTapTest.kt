/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeAlbumRadio
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.zionhuang.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tap in a listening room (Ti-Die, #testers 23 Nov 2026: "someone can click and it clears everything ... just make it
 * functionally an 'add next + skip'"): on the phone in control a song tapped plays next and now, and every song after
 * it stays where it was; a whole list or a radio keeps the songs put in by hand, right after its first song.
 */
class RoomTapTest {
    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 1, thumbnailUrl = null, genre = null)

    private val album = listOf("a1", "a2", "a3", "a4").map(::song)

    @Test
    fun `a song tapped is named by its start, a whole list or a radio asked for is not`() {
        // A row picked out of an album, a playlist, the library.
        assertEquals("a3", RoomTap.songOf(ListQueue(items = album, startIndex = 2, songPicked = true), radioAsked = false)?.id)
        // A song's own Play, a widget's card, a link: a list of one.
        assertEquals("s", RoomTap.songOf(ListQueue(items = listOf(song("s"))), radioAsked = false)?.id)
        // A Quick picks card or a search result: its radio, started from the song.
        assertEquals("q", RoomTap.songOf(YouTubeQueue.radio(song("q")), radioAsked = false)?.id)
        assertEquals("h", RoomTap.songOf(YouTubeQueue(WatchEndpoint(videoId = "h"), song("h")), radioAsked = false)?.id)
        // The same radio asked for with Start radio is a whole start.
        assertNull(RoomTap.songOf(YouTubeQueue.radio(song("q")), radioAsked = true))
        // Play and Shuffle on a whole album, playlist or folder, and the radios of a playlist, an album, an artist.
        assertNull(RoomTap.songOf(ListQueue(items = album), radioAsked = false))
        assertNull(RoomTap.songOf(ListQueue(items = album, startShuffled = true), radioAsked = false))
        assertNull(RoomTap.songOf(YouTubeQueue(WatchEndpoint(playlistId = "RDAMPLx")), radioAsked = false))
        assertNull(RoomTap.songOf(YouTubeAlbumRadio("OLAKx"), radioAsked = false))
        // A pick whose place is past the list is held to it, as the start holds it; an empty list names nothing.
        assertEquals("a4", RoomTap.songOf(ListQueue(items = album, startIndex = 9, songPicked = true), radioAsked = false)?.id)
        assertNull(RoomTap.songOf(ListQueue(items = emptyList(), songPicked = true), radioAsked = false))
    }

    @Test
    fun `a song not in the queue goes right after the song playing`() {
        assertEquals(RoomTap.Step.Insert(2), RoomTap.step(listOf("p0", "now", "g1", "g2"), current = 1, tapped = "t"))
        // At the end of the queue too.
        assertEquals(RoomTap.Step.Insert(1), RoomTap.step(listOf("now"), current = 0, tapped = "t"))
    }

    @Test
    fun `a song further on is moved next rather than played twice, and the song playing plays on`() {
        assertEquals(RoomTap.Step.Move(4, 2), RoomTap.step(listOf("p0", "now", "g1", "g2", "t", "g3"), current = 1, tapped = "t"))
        // Next already: no move, just there.
        assertEquals(RoomTap.Step.Move(2, 2), RoomTap.step(listOf("p0", "now", "t", "g1"), current = 1, tapped = "t"))
        // Played already, before the song playing: that one stays where it is, and the song goes in again.
        assertEquals(RoomTap.Step.Insert(2), RoomTap.step(listOf("t", "now", "g1"), current = 1, tapped = "t"))
        assertEquals(RoomTap.Step.Playing, RoomTap.step(listOf("p0", "now", "g1"), current = 1, tapped = "now"))
    }

    /** The queue as the service leaves it after [step], made the way it makes it: the board and Player.moveMediaItem alike. */
    private fun applied(ids: List<String>, step: RoomTap.Step, tapped: String): Pair<List<String>, Int?> {
        val q = ids.toMutableList()
        return when (step) {
            RoomTap.Step.Playing -> q to null
            is RoomTap.Step.Insert -> q.apply { add(step.at, tapped) } to step.at
            is RoomTap.Step.Move -> q.apply { add(step.to, removeAt(step.from)) } to step.to
        }
    }

    @Test
    fun `the songs the others added are all still there after a tap, in their order, after the song tapped`() {
        // The person in control plays "now"; guests added g1 and g2 next and g3 at the end; the radio had r1 and r2.
        val room = listOf("p0", "now", "g1", "g2", "r1", "r2", "g3")
        for (tapped in listOf("t", "r2", "g2")) {
            val (after, playing) = applied(room, RoomTap.step(room, current = 1, tapped = tapped), tapped)
            assertEquals(tapped, after[playing!!])
            assertEquals("right after the song that played", 2, playing)
            // Every song that was coming still comes, once, in the order it stood.
            val coming = room.drop(2).filter { it != tapped }
            assertEquals(tapped, coming, after.drop(3))
            assertEquals(listOf("p0", "now"), after.take(2))
        }
    }

    @Test
    fun `a whole start keeps the songs put in by hand after the song playing, each once`() {
        val order = listOf("p0", "g0", "now", "g1", "r1", "g2", "r2", "g1")
        val byHand = setOf("g0", "g1", "g2")
        // g0 has played; the second copy of g1 is the same song again.
        assertEquals(listOf("g1", "g2"), RoomTap.handAdded(order, current = 2, byHand = byHand) { it })
        assertEquals(emptyList<String>(), RoomTap.handAdded(order, current = 2, byHand = emptySet()) { it })
        // Nothing playing: the whole queue is ahead.
        assertEquals(listOf("g0", "g1", "g2"), RoomTap.handAdded(order, current = -1, byHand = byHand) { it })
    }

    @Test
    fun `what a start left out goes back, not what it kept, nor the song now playing`() {
        val g1 = song("g1")
        val g2 = song("g2")
        val g3 = song("g3")
        // A new queue: none of them is in it.
        assertEquals(listOf(g1, g2), RoomTap.toPutBack(listOf(g1, g2), listOf(song("b2"), song("b3")), "b1") { it.id })
        // The same album added to, not refilled: the very songs are still there, and go nowhere twice.
        assertEquals(listOf(g2), RoomTap.toPutBack(listOf(g1, g2), listOf(song("a2"), g1), "a1") { it.id })
        // Refilled: songs equal to them by name but not the very ones were put in by the list itself, and they go back.
        assertEquals(listOf(g1), RoomTap.toPutBack(listOf(g1), listOf(song("g1")), "a1") { it.id })
        // Start radio on a kept song: it is playing, and does not come again after itself.
        assertEquals(listOf(g1), RoomTap.toPutBack(listOf(g1, g3), emptyList(), "g3") { it.id })
    }

    @Test
    fun `the last of several starts, or a tap, puts back what all of them kept, from the queue the room played`() {
        val starts = RoomTap.Starts<String, MediaMetadata>()
        val g1 = song("g1")
        val g2 = song("g2")
        // One start alone.
        val one = starts.begin("room", listOf(g1))!!
        assertEquals("room" to listOf(g1), starts.end(one))
        assertNull("put back once", starts.end(one))
        // Start radio, then an album while the radio's answer is on its way: the radio's start puts nothing back, the
        // album's puts back what both kept, from the queue the room played before the radio's queue of one.
        val radio = starts.begin("room", listOf(g1))!!
        val albumStart = starts.begin("radio of one", listOf(g2))!!
        assertNull(starts.end(radio))
        assertEquals("room" to listOf(g1, g2), starts.end(albumStart))
        // A song tapped while a start waits ends it: the tap puts back what it kept.
        val waiting = starts.begin("room", listOf(g1, g1))!!
        assertEquals("room" to listOf(g1), starts.tap())
        assertNull(starts.end(waiting))
        // A start outside a room ends them too, keeping nothing.
        val before = starts.begin("room", listOf(g1))!!
        assertNull(starts.begin(null, null))
        assertNull(starts.end(before))
        assertEquals(null to emptyList<MediaMetadata>(), starts.tap())
    }

    @Test
    fun `the very objects count, not equal songs`() {
        val g1 = song("g1")
        val starts = RoomTap.Starts<String, MediaMetadata>()
        val n = starts.begin("room", listOf(g1, song("g1")))!!
        // Two copies of a song put in twice both go back.
        assertEquals(2, starts.end(n)!!.second.size)
        assertSame(g1, RoomTap.toPutBack(listOf(g1), listOf(g1.copy()), null) { it.id }.single())
    }

    // The wiring, read from the source as the other room tests do.

    private val root = File("src/main/java/com/dd3boh/outertune")
    private fun read(path: String) = File(root, path).readText()
    private val service = read("playback/MusicService.kt")

    private fun body(text: String, head: String): String {
        val start = text.indexOf(head)
        assertTrue("no $head", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        error("$head never closes")
    }

    @Test
    fun `playQueue plays a tap in a room before anything of a start is done, and keeps the songs around a whole start`() {
        val play = body(service, "fun playQueue(")
        val tap = play.indexOf("if (roomTap(queue, radioAsked, origin, playWhenReady)) return@launch")
        assertTrue("the tap is decided on the main thread, in the start's own launch", tap > play.indexOf("CoroutineScope(Dispatchers.Main).launch {"))
        // A tap keeps the queue playing: nothing that says a new queue started runs for it.
        for (line in listOf("queuePlaylistId = queue.playlistId", "radioStopped.value = null", "radioFromInterTune.value = null", "letGoOfBlockedStop()", "queueStart.run(")) {
            assertTrue("$line before the tap", play.indexOf(line) > tap)
        }
        val run = play.indexOf("queueStart.run(")
        assertTrue(play.indexOf("val roomStart = roomStartBegins()") in tap until run)
        assertTrue(play.indexOf("roomStart?.let(::roomStartEnds)") > run)
        // Try again on a radio that stopped asks for the radio again, in a room too.
        assertTrue("radioAsked = true," in play)
    }

    @Test
    fun `only the phone in control of a room plays a tap so, and a start still waiting is dropped for it`() {
        val tap = body(service, "private fun roomTap(")
        assertTrue("together.value?.following != false" in tap)
        assertTrue("RoomTap.songOf(queue, radioAsked)" in tap)
        assertTrue("queueStart.drop()" in tap)
        assertTrue("roomStarts.tap()" in tap)
        // The song tapped is the listener's: chosen, put in by hand, its listen from where it was tapped.
        assertTrue("noteByHand(q, listOf(song.id))" in tap)
        assertTrue("pendingOrigin = origin" in tap)
        assertTrue("fun drop()" in read("playback/QueueStart.kt"))
        // A start outside a room ends any wait for one in a room.
        assertTrue("roomStarts.begin(" in body(service, "private fun roomStartBegins("))
    }

    @Test
    fun `Start radio on a song is a whole start, everywhere it is offered`() {
        for (f in listOf("ui/menu/SongMenu.kt", "ui/menu/YouTubeSongMenu.kt", "ui/menu/PlayerMenu.kt")) {
            assertTrue(f, Regex("""YouTubeQueue\.radio\([^)]*\)\)?, isRadio = true, radioAsked = true\)""").containsMatchIn(read(f)))
        }
        assertTrue("radioAsked = true" in body(service, "fun toggleStartRadio("))
        val connection = body(read("playback/PlayerConnection.kt"), "fun playQueue(")
        assertTrue("radioAsked = radioAsked," in connection)
    }

    @Test
    fun `the line for the songs kept is a plural in English and French, read as one`() {
        for (file in listOf("res/values/strings-ot.xml", "res/values-fr/strings-ot.xml")) {
            val text = File("src/main/$file").readText()
            val block = text.substringAfter("<plurals name=\"together_kept_added\">", "").substringBefore("</plurals>")
            assertTrue(file, "quantity=\"one\"" in block && "quantity=\"other\"" in block)
            // Each says the number, so a language whose "one" covers more than 1 says it right.
            assertFalse(file, Regex("<item quantity=\"[a-z]+\">(?![^<]*%1\\\$d)").containsMatchIn(block))
            // Beside the room's other lines about added songs.
            assertTrue(file, text.indexOf("together_kept_added") > text.indexOf("<plurals name=\"together_from_guest_many\">"))
        }
        assertTrue("R.plurals.together_kept_added" in service)
    }
}

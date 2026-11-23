/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A play somebody starts asks YouTube once for its song, even a song the app set aside as gone;
 * what the app does by itself does not (23 Nov 2026: a tester in a listening room could not play a
 * song the others heard, and his phone never asked).
 */
class AskedForTest {

    @Test
    fun `a song asked for is asked once, and a song nobody asked for not at all`() {
        val asked = AskedFor()
        asked.asked("SONG0000001")

        assertFalse(asked.take("SONG0000002"))
        assertTrue(asked.take("SONG0000001"))
        assertFalse("once: the tries after it are the app's own", asked.take("SONG0000001"))
    }

    @Test
    fun `asked for twice before it plays, it is still one ask`() {
        val asked = AskedFor()
        asked.asked("SONG0000001")
        asked.asked("SONG0000001")

        assertTrue(asked.take("SONG0000001"))
        assertFalse(asked.take("SONG0000001"))
    }

    @Test
    fun `one start that loads its song twice gives one ask, and the next start another`() {
        // The song alone at the tap, then with the rest of its queue (QueueStart): the first load's
        // fetch has used the ask by the time the second comes.
        val asked = AskedFor()
        asked.asked("SONG0000001", start = 1_000L)
        assertTrue(asked.take("SONG0000001"))
        asked.asked("SONG0000001", start = 1_000L)
        assertFalse(asked.take("SONG0000001"))

        asked.asked("SONG0000001", start = 2_000L)
        assertTrue("tapped again", asked.take("SONG0000001"))
    }

    @Test
    fun `an ask waits for its song however many others play first`() {
        val asked = AskedFor()
        asked.asked(listOf("NEXT0000001", "NEXT0000002"))
        for (n in 1..50) {
            asked.asked("OTHER%06d".format(n))
            asked.take("OTHER%06d".format(n))
        }

        assertTrue(asked.take("NEXT0000001"))
        assertTrue(asked.take("NEXT0000002"))
    }

    @Test
    fun `past what it keeps the oldest go, and of songs put in together the first to play stay`() {
        val asked = AskedFor(max = 3)
        asked.asked("OLD00000001")
        asked.asked(listOf("NEXT0000001", "NEXT0000002", "NEXT0000003", "NEXT0000004"))

        assertFalse(asked.take("OLD00000001"))
        assertFalse(asked.take("NEXT0000004"))
        assertTrue(asked.take("NEXT0000001"))
        assertTrue(asked.take("NEXT0000002"))
        assertTrue(asked.take("NEXT0000003"))
    }

    // How the app gives the asks: read from the source, as the other wiring guards are. Each is given
    // before the player is told, so that no read of the song comes first.

    private fun code(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
    private val service get() = code("playback/MusicService.kt")
    private fun body(text: String, start: String) = text.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `the player's fetch takes the song's ask, the read ahead never does, and Play on the song it stopped on still asks`() {
        val fetch = service.substringAfter("private fun fetch(").substringBefore("val playbackData = played.data")
        assertTrue("val askedNow = !ahead && askedSongs.take(mediaId)" in fetch)
        assertTrue("val askedAgain = askedNow || (stoppedByError && stoppedOn == mediaId)" in fetch)
        assertTrue("standIns.playbackData(mediaId, askedAgain = askedAgain)" in fetch)
        // Both ways into fetch say which read it is.
        val resolve = service.substringAfter("fun resolve(mediaId: String, position: Long, length: Long, ahead: Boolean = false): ResolvedSong {")
            .substringBefore("private fun downloadNotOnPhone(")
        assertEquals(2, Regex("""fetch\(mediaId, position, staleQuality, partialCopy, said, ahead\)""").findAll(resolve).count())
        assertTrue("the read ahead is the resolver's ahead", "playerResolver.resolve(id, 0L, C.LENGTH_UNSET.toLong(), ahead = true)" in service)
    }

    @Test
    fun `a queue somebody starts gives its first song an ask, once for the start, before the player has it`() {
        // Every playQueue: a tap in a list, a radio started, the widget, a link, a room's new queue.
        val load = body(service, "override fun setCurrQueue(queue: MultiQueueObject?, shouldResume: Boolean) {")
        val ask = load.indexOf("askedSongs.asked(it.id, start = q.runId")
        assertTrue(load, ask >= 0)
        assertTrue("before the player is loaded", ask < load.indexOf("queueBoard.setCurrQueue(queue, shouldResume)"))
    }

    @Test
    fun `Play next and Add to queue give every song they put in an ask`() {
        for (name in listOf("fun enqueueNext(items: List<MediaItem>) {", "fun enqueueEnd(items: List<MediaItem>) {")) {
            val put = service.substringAfter(name)
            assertTrue(name, put.trimStart().let { it.substringAfter("\n").trimStart() }.startsWith("askedSongs.asked(items.map { it.mediaId })"))
        }
    }

    @Test
    fun `a tap in the queue sheet, Retry and the car's pick each give the song an ask`() {
        val connection = code("playback/PlayerConnection.kt")
        assertTrue("songId?.let(service::askedFor)" in body(connection, "fun markUserChoice(songId: String? = null) {"))
        assertTrue("player.currentMediaItem?.mediaId?.let(service::askedFor)" in body(connection, "fun askAgain() {"))

        val sheet = code("ui/player/Queue.kt")
        assertTrue("playerConnection.markUserChoice(window.id); playerConnection.player.seekToDefaultPosition(index)" in sheet)
        // A song picked in a queue that is not playing loads that queue.
        val other = sheet.substringAfter("detachedQueue?.setCurrentQueuePos(index)").substringBefore("} else {")
        assertTrue(other.indexOf("playerConnection.service.askedFor(window.id)") in 0 until other.indexOf("qb.setCurrQueue(detachedQueue"))

        val player = code("ui/player/Thumbnail.kt")
        assertTrue("retry = { playerConnection.askAgain(); playerConnection.player.playSafe() }," in player)

        val car = body(code("playback/MediaLibrarySessionCallback.kt"), "private fun startExternalQueue(")
        assertTrue("items.getOrNull(startIndex)?.mediaId?.let(service::askedFor)" in car)
    }

    @Test
    fun `the song a room moves to is asked, its one more try is this phone's own and is not`() {
        val session = code("together/TogetherSession.kt")
        val toSong = session.substringAfter("private fun toLeadersSong(").substringBefore("\n    private fun ")
        val inQueue = toSong.substringAfter("indexOf(player, state.songId)?.let { index ->").substringBefore("return\n")
        assertTrue(inQueue.indexOf("service.askedFor(state.songId)") in 0 until inQueue.indexOf("player.seekTo(index,"))
        val newQueue = toSong.substringAfter("loadingSong = state.songId\n        matchedQueue = null")
        assertTrue(newQueue.indexOf("service.askedFor(state.songId)") in 0 until newQueue.indexOf("service.playQueue("))

        val retry = session.substringAfter("if (refusal.staysOn(state.songId)) {").substringBefore("if (player.playbackState != Player.STATE_READY) return")
        assertTrue(retry, "player.prepare()" in retry)
        assertFalse(retry, "askedFor" in retry)
    }
}

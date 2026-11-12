/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeAlbumRadio
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.zionhuang.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** When the player passes over a song the listener chose not to play (DontPlaySkip), and how MusicService asks. */
class DontPlaySkipTest {

    private fun passes(reason: Int, chosen: Boolean = false, byHand: Boolean = false, casting: Boolean = false, startPicked: Boolean = true) =
        DontPlaySkip.passes(reason, chosen, byHand, casting, startPicked)

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    private val three = listOf(song("A"), song("B"), song("C"))

    @Test
    fun `where the player came to it by itself it is passed over`() {
        // The song before ended, or next or previous was pressed.
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK))
        // A receiver plays on by itself too.
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO, casting = true))
    }

    @Test
    fun `a song the listener picked plays`() {
        // The song a queue starts on: tapped in a list, a radio started from it, a link.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
        // Tapped in the queue sheet.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, chosen = true))
        // Put in by hand, with Play next or Add to queue, however the player comes to it.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO, byHand = true))
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, byHand = true))
        // The same song again under repeat one.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_REPEAT))
        // While casting no tap is known, so a move by hand may be one.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, casting = true))
    }

    @Test
    fun `the song a list's Play or Shuffle starts on is no pick, unless it was put in by hand`() {
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, startPicked = false))
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, startPicked = false, casting = true))
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, startPicked = false, byHand = true))
        // Only the start's own song change says so: the moves after it go as they always went.
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, chosen = true, startPicked = false))
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_REPEAT, startPicked = false))
    }

    @Test
    fun `a start is a pick when a song was tapped, a radio started from it, or it is the list's only song`() {
        assertTrue(DontPlaySkip.startIsPick(ListQueue(items = three, startIndex = 2, songPicked = true)))
        assertTrue(DontPlaySkip.startIsPick(YouTubeQueue.radio(song("A"))))
        // A recognised song, handed over as the song to start on.
        assertTrue(DontPlaySkip.startIsPick(YouTubeQueue(WatchEndpoint(videoId = "A"), song("A"))))
        // A one-song Play: a link, the widget, a song's own Play, a single.
        assertTrue(DontPlaySkip.startIsPick(ListQueue(items = listOf(song("A")))))
        assertTrue(DontPlaySkip.startIsPick(ListQueue(items = listOf(song("A")), startShuffled = true)))
    }

    @Test
    fun `a list or an album started whole begins on a song nobody picked`() {
        // Play, and Shuffle, on a list.
        assertFalse(DontPlaySkip.startIsPick(ListQueue(items = three)))
        assertFalse(DontPlaySkip.startIsPick(ListQueue(items = three, startShuffled = true)))
        // An album's Play from its card, and a playlist's radio, whose first song is YouTube's.
        assertFalse(DontPlaySkip.startIsPick(YouTubeAlbumRadio("OLAK5uy_album")))
        assertFalse(DontPlaySkip.startIsPick(YouTubeQueue(WatchEndpoint(playlistId = "RDAMPLPL1"))))
    }

    @Test
    fun `songs put in by hand into a queue not playing yet are by hand once it plays`() {
        // A queue started from Play next, or a pick that keeps the songs waiting after Play next:
        // they are noted in its run before it loads, and its first song makes that run current.
        val heard = AdaptiveQueue.Heard()
        heard.note(1L, "P", "Playing", runId = 10L)
        heard.putInByHand(2L, 20L, listOf("X", "Y"), radio = false)
        assertTrue("the run playing gains nothing", heard.byHand.isEmpty())
        heard.note(2L, "A", "Alpha", runId = 20L)
        assertEquals(setOf("X", "Y"), heard.byHand)
    }

    @Test
    fun `songs added with a menu's Add to queue are the listener's in the queue they go to, a new one made there too`() {
        // A queue made in the dialog: its songs are noted in the run startedByHand gives it, before
        // it loads, and its first song makes that run current. Only that first one used to play:
        // the player passed over a song not to play among the others when it came to it.
        val heard = AdaptiveQueue.Heard()
        heard.note(1L, "P", "Playing", runId = 10L)
        val made = MultiQueueObject(id = 2, title = "Mine", queue = three.toMutableList(), index = 0)
        made.startedByHand(PlayOrigin.QUEUE, now = 20L)
        heard.putInByHand(made.id, made.runId, made.queue.map { it.id }, radio = false)
        heard.note(made.id, "A", "A", runId = made.runId)
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO, byHand = "B" in heard.byHand))
        assertFalse(passes(MEDIA_ITEM_TRANSITION_REASON_SEEK, byHand = "C" in heard.byHand))
        // A list that said nothing of where it began takes a run of its own as the songs go in
        // (addQueue's startedByHand comes first): noted in that run, they are the listener's once
        // it plays. Noted in the run it had before, they were lost.
        val old = MultiQueueObject(id = 3, title = "Old", queue = mutableListOf(song("D")), index = 1)
        heard.putInByHand(old.id, old.runId, listOf("E"), radio = false)
        old.startedByHand(PlayOrigin.QUEUE, now = 30L)
        heard.putInByHand(old.id, old.runId, listOf("F"), radio = false)
        heard.note(old.id, "D", "D", runId = old.runId)
        assertEquals(setOf("F"), heard.byHand)
        assertTrue(passes(MEDIA_ITEM_TRANSITION_REASON_AUTO, byHand = "E" in heard.byHand))
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private val service = File(sources, "playback/MusicService.kt").readText()

    @Test
    fun `the Add to queue dialog's songs are noted in each list branch of addQueue and in the new queue, before they load`() {
        val board = File(sources, "playback/QueueBoard.kt").readText()
        val add = body(board, "fun addQueue(")
        // The extension queue that exists, the queue picked, and the queue picked that becomes one:
        // each notes the songs in the queue they go into, right before they go in.
        val lists = Regex("""addSongsToQueue\((\w+), Int\.MAX_VALUE""").findAll(add).toList()
        assertEquals(listOf("anyExts", "match", "match"), lists.map { it.groupValues[1] })
        for (m in lists) {
            assertEquals("noteAddedByHand(${m.groupValues[1]}, mediaList, origin)", add.substring(0, m.range.first).trimEnd().lines().last().trim())
        }
        // A new queue, in the run it has just been given.
        val made = add.substringAfter("val newQueue = MultiQueueObject(")
        val started = made.indexOf("newQueue.startedByHand(origin, System.currentTimeMillis())")
        val noted = made.indexOf("noteAddedByHand(newQueue, q, origin)")
        assertTrue(started in 0 until noted)
        assertTrue(noted < made.indexOf("return newQueue"))
        // A radio's go through addToQueue, which notes them itself (AddToQueueTest).
        assertFalse("noteAddedByHand(" in add.substringAfter("match.playlistId != null").substringBefore("} else if"))
        // Only for the dialog, whose callers all say so: playQueue and the car's lists pass no origin.
        assertTrue("if (origin == PlayOrigin.QUEUE) player.notePutInByHand(q, mediaList.mapNotNull { it?.id })" in body(board, "private fun noteAddedByHand("))
        val callers = sources.walkTopDown().filter { it.extension == "kt" && it.name != "QueueDialogs.kt" && "AddToQueueDialog(" in it.readText() }.toList()
        assertTrue("the dialog's callers were not found", callers.size >= 11)
        for (file in callers) {
            val onAdd = file.readText().substringAfter("AddToQueueDialog(").substringBefore("onDismiss")
            assertTrue(file.name, "addQueue(" in onAdd && "origin = PlayOrigin.QUEUE" in onAdd)
        }
        // The service's hook, beside addToQueue, under the flag as the rest of DontPlay.
        val hook = body(service, "fun notePutInByHand(")
        assertTrue("if (!Unreleased.ARTIST_BLOCK || ids.isEmpty()) return" in hook)
        assertTrue("runCatching { noteByHand(q, ids) }" in hook)
        assertTrue(service.indexOf("fun notePutInByHand(") in service.indexOf("fun addToQueue(") until service.indexOf("private fun noteByHand("))
    }

    /** The text of the function or block whose head is [head], braces matched. */
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
    fun `MusicService asks at every song change, beside a blocked artist's song, and at once for the song playing`() {
        assertTrue("val dontPlay = DontPlaySkip.passes(reason, chosen = chosen, byHand = id in heard.byHand, casting = isCasting, startPicked = startPicked)" in service)
        assertTrue("runCatching { passOverBlocked(back, dontPlay = dontPlay) }" in service)
        // Set not to play while it plays: passed over at once, the menu having said it all.
        assertTrue("runCatching { passOverBlocked(dontPlay = true, asked = true) }" in service)
        // Where it goes is never another song not to play, unless that one was put in by hand.
        assertTrue("block.hides(it) || (songs.covers(it) && it.id !in byHand)" in service)
        // Play pressed on one the music stopped on picks it.
        assertTrue("playWhenReady && (!ContentFilter.current.blocked.isEmpty || stoppedOnBlocked)" in service)
        // Each says why in its own words.
        assertEquals(1, service.split("R.string.dont_play_skipped").size - 1)
        assertTrue("if (blockedHere) R.string.blocked_songs_left else R.string.dont_play_songs_left" in service)
    }

    @Test
    fun `a queue the listener starts lets go of a stop in the queue before, which passes over only the song it stopped on`() {
        // playQueue, before its steps load anything.
        val play = body(service, "fun playQueue(")
        val run = play.indexOf("queueStart.run(")
        assertTrue(play.indexOf("letGoOfBlockedStop()") in 0 until run)
        assertTrue(body(service, "fun letGoOfBlockedStop(").contains("stoppedOnBlocked = false"))
        // The car's lists, which reach the player without playQueue.
        val car = body(File(sources, "playback/MediaLibrarySessionCallback.kt").readText(), "override fun onSetMediaItems(")
        assertTrue(car.indexOf("service.letGoOfBlockedStop()") in 0 until car.indexOf("resolvePlayRequest("))
        // Songs coming in behind pass over a song not to play only where the music stopped on it.
        val stop = body(service, "private fun passOverBlocked(")
        assertTrue(stop.indexOf("stoppedAt = playingAt()") > stop.indexOf("stoppedOnBlocked = stoppedOnBlocked || wasPlaying"))
        val grown = body(service, "override fun onTimelineChanged(")
        assertTrue("val same = stoppedAt != null && stoppedAt == playingAt()" in grown)
        assertTrue("passOverBlocked(quiet = true, dontPlay = same)" in grown)
        assertFalse("dontPlay = true" in grown)
        assertTrue("\"\${AdaptiveQueue.runKey(q.id, q.runId)} \$id\"" in body(service, "private fun playingAt("))
    }

    @Test
    fun `whether a start was a pick is read at that start's own song change`() {
        val play = body(service, "fun playQueue(")
        assertTrue(play.indexOf("unpickedStart = if (DontPlaySkip.startIsPick(queue)) 0L else runId") in 0 until play.indexOf("queueStart.run("))
        val transition = body(service, "override fun onMediaItemTransition(")
        assertTrue("val startPicked = reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || unpickedStart == 0L || q?.runId != unpickedStart" in transition)
        // Read once, whatever the flag or the lists say, so it never lands on a later load.
        val read = transition.indexOf("if (!startPicked) unpickedStart = 0L")
        assertTrue(read in 0 until transition.indexOf("if (Unreleased.ARTIST_BLOCK && mediaItem != null && (!ContentFilter.current.blocked.isEmpty"))
        // A start loaded under the song playing has no song change: that song goes on.
        val carried = body(service, "fun carriedOnInto(")
        assertTrue(carried.indexOf("if (q.runId == unpickedStart) unpickedStart = 0L") in carried.indexOf("if (heard.runKey == before) return") until carried.indexOf("choiceCarriedOn = true"))
    }

    @Test
    fun `songs put in by hand stay the listener's in the queue they reach`() {
        // Kept after a pick with Play next waiting. Under the flag, as the rest of DontPlay.
        assertTrue("if (Unreleased.ARTIST_BLOCK) noteByHand(queue, kept.map { it.id })" in body(service, "override fun keepPlayNext("))
        assertTrue("runCatching { noteByHand(queue, ids) }" in body(service, "override fun putInByHand("))
        // A queue Play next or Add to queue starts, with no queue to go to (QueueStartTest holds the step).
        assertTrue("byHand = Unreleased.ARTIST_BLOCK && playOrigin == PlayOrigin.QUEUE," in body(service, "fun playQueue("))
        for (name in listOf("fun enqueueNext(", "fun enqueueEnd(")) assertTrue(name, "origin = PlayOrigin.QUEUE," in body(service, name))
    }

    @Test
    fun `the adaptive queue never picks a song not to play, from a list read now or kept for the run`() {
        val pass = body(service, "private suspend fun computePass(")
        val sources = body(pass, "fun sources()")
        assertTrue("val dontPlay = ContentFilter.current.dontPlay" in sources)
        assertTrue("source.copy(songs = source.songs.filterNot(dontPlay::covers))" in sources)
        // Every read of the lists goes through it, the one after a page included.
        assertEquals(2, Regex("""input\(sources\(\)\)""").findAll(pass).count())
        assertEquals(1, Regex("""listFor\(""").findAll(pass).count())
    }
}

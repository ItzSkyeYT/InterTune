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
 * How the radio at the end of an album is wired into the service's song-change handler. No player
 * runs on the JVM, so these read the source, as AdaptiveWiringTest does, and each guards one rule
 * that a later change could quietly undo. The decision itself is EndlessQueueTest's.
 */
class EndlessQueueWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a rule is not taken for the rule. */
    private fun withoutComments(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /** From the marker the radio's top-up begins at to the pause point that follows it. */
    private val topUp: String = withoutComments(
        File(sources, "playback/MusicService.kt").readText()
            .substringAfter("// Auto load more songs")
            .substringBefore("\n        // The pause point")
    )

    @Test
    fun `the top-up is found, with the guards the radio always had`() {
        // Each of these keeps a radio from asking for a page nobody wants (a swiped mini player,
        // a repeated song, a page that arrives for another queue or an emptied player).
        assertTrue("mediaItem != null &&" in topUp)
        assertTrue("reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&" in topUp)
        assertTrue("activePlayer.mediaItemCount - activePlayer.currentMediaItemIndex <= 5 &&" in topUp)
        assertTrue("activePlayer.playbackState != STATE_IDLE &&" in topUp)
        assertTrue("activePlayer.mediaItemCount > 0 && qbInit.value &&" in topUp)
        assertTrue("queueBoard.getCurrentQueue()?.id == q.id &&" in topUp)
        assertTrue("scope.launch(SilentHandler) {" in topUp)
    }

    @Test
    fun `a radio's own seed comes first, so a radio is asked as it always was`() {
        assertTrue("val playlistId = q?.playlistId ?: endSeed" in topUp)
        // The end of an album is asked about only for a queue with no radio of its own.
        assertTrue("q.playlistId == null &&" in topUp.substringAfter("val endSeed = ").substringBefore("EndlessQueue.seed("))
        assertTrue("hasNextPage = false," in topUp)
    }

    @Test
    fun `the end of an album is asked about only behind its flag, with the setting on, repeat off and a network`() {
        val ask = topUp.substringAfter("val endSeed = ").substringBefore("val endRadio")
        assertTrue("Unreleased.ENDLESS_QUEUE &&" in ask)
        assertTrue("autoLoadMore = autoLoadMore," in ask)
        assertTrue("repeatOff = activePlayer.repeatMode == REPEAT_MODE_OFF," in ask)
        assertTrue("online = isNetworkConnected.value && !Throttle.isBlocked," in ask)
        assertTrue("asked = endRadioAsked," in ask)
        // And the main guard still has the setting in front of it.
        assertTrue("if (autoLoadMore &&" in topUp)
    }

    @Test
    fun `a queue is an album by its origin, and by nothing else`() {
        // The decision is handed the origin the queue was started with, which is saved with it.
        assertTrue("origin = q.origin," in topUp)
        val decision = withoutComments(File(sources, "playback/EndlessQueue.kt").readText())
        assertTrue("if (origin != PlayOrigin.ALBUM.code) return null" in decision)
        // Nothing else about the queue (its title, its songs, its id) says it is an album.
        assertFalse("q.title" in topUp.substringBefore("val endRadio"))
    }

    @Test
    fun `every way of starting an album gives it the album origin, before the first song change reads it`() {
        // The album page (Play, Shuffle and a tapped row), the play button on an album's tile in
        // two places, and Play in an online album's menu.
        val albumPage = File(sources, "ui/screens/AlbumScreen.kt").readText()
        assertEquals(3, Regex("""origin = PlayOrigin\.ALBUM""").findAll(albumPage).count())
        for (path in listOf("ui/component/items/AlbumItems.kt", "ui/component/items/Items.kt", "ui/menu/YouTubeAlbumMenu.kt")) {
            assertTrue("$path no longer starts an album as one", "PlayOrigin.ALBUM" in File(sources, path).readText())
        }
        // The queue is given the origin before it is loaded, so the first song change sees it.
        val start = withoutComments(File(sources, "playback/QueueStart.kt").readText())
        val tagged = start.indexOf("full?.origin = origin")
        val loaded = start.indexOf("service.setCurrQueue(full, shouldResume)")
        assertTrue("tagged $tagged, loaded $loaded", tagged in 0 until loaded)
    }

    @Test
    fun `the ask is remembered before it is made, so a second song change cannot make it twice`() {
        val remembered = topUp.indexOf("endRadioAsked = EndlessQueue.Asked(endRun, endSeed)")
        val launched = topUp.indexOf("scope.launch(SilentHandler) {")
        assertTrue(remembered > 0 && remembered < launched)
    }

    @Test
    fun `the page goes only into the album it was asked for, as it was asked`() {
        val wanted = topUp.substringAfter("val wanted = ").substringBefore("if (!wanted)")
        assertTrue("(!endRadio || (q.playlistId == null && q.getSize() == songCount && AdaptiveQueue.runKey(q.id, q.runId) == endRun))" in wanted)
        assertTrue("(songCount > 1 || endRadio)" in wanted)
    }

    @Test
    fun `an album becomes a radio only when the radio brought songs, and its own songs stay its listener's`() {
        assertTrue("if (!endRadio || joining.isNotEmpty()) q.playlistId = next.also { lastRadioSeed = it }" in topUp)
        val radio = topUp.indexOf("q.playlistId = next")
        val hand = topUp.indexOf("noteByHand(q, songs.map { it.id })")
        val joined = topUp.indexOf("queueBoard.enqueueEnd(joining)")
        // The run is a radio's before the songs are marked, and they are marked before anything
        // joins, so no pass ever sees a radio's songs without the listener's in front of them.
        assertTrue("radio $radio, by hand $hand, joined $joined", radio in 0 until hand && hand < joined)
        assertTrue("if (joining.isNotEmpty()) noteByHand(q, songs.map { it.id })" in topUp)
    }

    @Test
    fun `what a radio page brings is filtered the same way at the end of an album`() {
        assertTrue("RadioPage.fresh(mediaItems.drop(1), songs, heard.reached, heard.reachedVersions)" in topUp)
        assertTrue("AdaptivePass.pageKeeps(fresh, view.turnedDown, view.positiveArtists, tasteCache?.second)" in topUp)
        assertFalse("the page is never appended whole", "enqueueEnd(mediaItems" in topUp)
    }

    @Test
    fun `the car's code knows nothing of it, only the queue's songs change`() {
        // The owner's rule: the car screens do not change. Nothing under the car's own files is
        // told; an album the car starts says EXTERNAL, so it ends where it ends.
        val carFiles = listOf(
            "AutoArtProvider.kt", "AutoBrowse.kt", "AutoCar.kt", "AutoSettings.kt", "AutoSettingsLink.kt",
            "MediaLibrarySessionCallback.kt",
        ).map { File(sources, "playback/$it") }
        for (file in carFiles) {
            assertTrue("${file.name} is gone or moved", file.isFile)
            assertFalse("${file.name} mentions the endless queue", "EndlessQueue" in file.readText() || "ENDLESS_QUEUE" in file.readText())
        }
    }

    @Test
    fun `the flag is held back, on in debug and preview builds only`() {
        val flag = File(sources, "constants/Unreleased.kt").readText()
        assertEquals(1, Regex("""val ENDLESS_QUEUE = BuildConfig\.DEBUG""").findAll(flag).count())
    }

    @Test
    fun `the setting's words are as they were, they still say what it does`() {
        // "Automatically add more songs when the end of the queue is reached, if possible": a
        // playlist or a song on the phone has none to add, which is the if possible.
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue("Automatically add more songs when the end of the queue is reached, if possible" in strings)
    }
}

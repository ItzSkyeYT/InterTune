/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dislike (Unreleased.DISLIKE, #171) where it is wired: the song menus, the media controls, the call to
 * YouTube Music and the texts. What one tap does is DislikePlanTest's; this reads the source, as
 * DontPlayGreyTest does, since the menus are Compose and the service needs a phone.
 */
class DislikeWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The source of [path] with its comments taken out. */
    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `the flag is held back`() {
        assertTrue("val DISLIKE = BuildConfig.PREVIEW_FEATURES" in source("constants/Unreleased.kt"))
    }

    @Test
    fun `YouTube Music's thumbs down is its like dislike, behind the gate every account write passes`() {
        val innerTube = File("../innertube/src/main/java/com/zionhuang/innertube/InnerTube.kt").readText()
        val call = innerTube.substringAfter("suspend fun dislikeVideo(").substringBefore("suspend fun ")
        assertTrue("httpClient.post(\"like/dislike\")" in call)
        assertTrue("target = LikeBody.Target.VideoTarget(videoId)" in call)
        assertTrue("ytClient(client, setLogin = true)" in call)
        val youTube = File("../innertube/src/main/java/com/zionhuang/innertube/YouTube.kt").readText()
        assertTrue("suspend fun dislikeVideo(videoId: String) = runCatching {\n        requireAccountWrites()\n        innerTube.dislikeVideo(WEB_REMIX, videoId)" in youTube)
    }

    @Test
    fun `every song menu says Dislike for a song from YouTube Music, and hands the tap its like and the downloads`() {
        val menus = mapOf(
            "ui/menu/SongMenu.kt" to "dontPlayMenuItem(held, dislike = DislikePlan.shown(Unreleased.DISLIKE, song.song.isLocal)) {\n                toggleDontPlay(context, database, song.toMediaMetadata(), held, downloadUtil, liked = song.song.liked)",
            "ui/menu/YouTubeSongMenu.kt" to "dontPlayMenuItem(held, dislike = DislikePlan.shown(Unreleased.DISLIKE, local = false)) {\n                toggleDontPlay(context, database, song.toMediaMetadata(), held, downloadUtil, liked = librarySong?.song?.liked == true)",
            "ui/menu/PlayerMenu.kt" to "dontPlayMenuItem(held, dislike = DislikePlan.shown(Unreleased.DISLIKE, mediaMetadata.isLocal)) {\n                toggleDontPlay(context, database, mediaMetadata, held, downloadUtil, liked = librarySong?.song?.liked == true)",
        )
        for ((path, call) in menus) assertTrue("$path does not hand Dislike its choice", call in source(path))
    }

    @Test
    fun `the tap follows the plan, and a like is taken back here without a removelike beside the thumbs down`() {
        val dontPlay = source("ui/menu/DontPlaySong.kt")
        assertTrue("val plan = DislikePlan.of(Unreleased.DISLIKE, held, song.isLocal, liked, context.mayPushToYouTube(), context.isUserLoggedIn())" in dontPlay)
        val toggle = dontPlay.substringAfter("fun toggleDontPlay(")
        // localToggleLike sends nothing; toggleLike would send removelike, which could land after the thumbs down.
        assertTrue("songRow(id)?.takeIf { it.liked }?.localToggleLike()?.let { unliked ->" in toggle)
        assertFalse(".toggleLike()" in toggle)
        assertTrue("downloadUtil?.autoDownloadOnLike(unliked)" in toggle)
        // Signed out at once, with the row; with sync on read and write only once the thumbs down landed.
        assertTrue("if (plan.unlike == DislikePlan.Unlike.NOW) takeLikeBack(song.id, downloadUtil)" in toggle)
        assertTrue("if (sent && kept && plan.unlike == DislikePlan.Unlike.AFTER_DISLIKE) takeLikeBack(song.id, downloadUtil)" in toggle)
        // Dislike's words, its thumbs down, and Don't play this song's as they were.
        assertTrue("title = if (held) R.string.menu_dislike_lift else R.string.menu_dislike," in dontPlay)
        assertTrue("icon = if (held) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt," in dontPlay)
        assertTrue("title = if (held) R.string.menu_dont_play_lift else R.string.menu_dont_play_song," in dontPlay)
    }

    @Test
    fun `the toast says what YouTube Music did, once it has answered`() {
        val toggle = source("ui/menu/DontPlaySong.kt").substringAfter("fun toggleDontPlay(")
        // The thumbs down: sent after the row is written, so a fast failure reads this tap's row.
        val sent = toggle.indexOf("val sent = YouTube.dislikeVideo(song.id).isSuccess")
        assertTrue(sent > 0)
        assertTrue(toggle.indexOf("withTimeoutOrNull(10_000) { written.await() }") in 0 until sent)
        // Taken or not, said from the answer, and nothing said before it.
        val told = toggle.indexOf("Toaster.show(app, if (sent) R.string.dislike_done else R.string.dislike_not_sent, Toast.LENGTH_LONG)")
        assertTrue(told > sent)
        assertEquals(1, toggle.split("R.string.dislike_done").size - 1)
        // Remove dislike the same way.
        assertTrue("val sent = YouTube.likeVideo(song.id, false).isSuccess" in toggle)
        assertTrue("Toaster.show(app, if (sent) R.string.dont_play_lifted else R.string.dislike_lift_not_sent, Toast.LENGTH_LONG)" in toggle)
        // Lifted again before the answer: no word after its own, and a like still here goes back to YouTube Music.
        assertTrue("val stillHeld = database.transactionNow {" in toggle)
        assertTrue("if (sent && likedHere && app.mayPushToYouTube()) RatingWrites.send(song.id) { YouTube.likeVideo(song.id, true) }" in toggle)
        // Lifted on the queue the row was written on, so a quick Dislike after it cannot land first.
        assertTrue("database.transaction { liftDontPlay(song.id) }" in toggle)
        val dao = File(sources, "db/daos/ListenDao.kt").readText()
        assertTrue("@Query(\"SELECT EXISTS(SELECT 1 FROM recommendation_exclusion WHERE kind = 1 AND reason = 5 AND targetId = :songId)\")\n    fun isDontPlay(songId: String): Boolean" in dao)
    }

    @Test
    fun `Dislike is Don't play this song's entry for what a menu hides`() {
        assertEquals("menu_dont_play_song", MenuChoices.entryName("menu_dislike"))
        assertEquals("menu_dont_play_song", MenuChoices.entryName("menu_dislike_lift"))
    }

    @Test
    fun `the media controls offer Dislike, which does what the menus' does to the song playing`() {
        val service = source("playback/MusicService.kt")
        assertTrue("toggleDislike = ::toggleDislike" in service)
        val button = service.substringAfter("MediaControlButton.DISLIKE -> {").substringBefore(".build()")
        assertTrue(".setSessionCommand(CommandToggleDislike)" in button)
        assertTrue(".setEnabled(Unreleased.DISLIKE && song != null && !song.song.isLocal)" in button)
        val toggle = service.substringAfter("fun toggleDislike() {").substringBefore("\n    fun ")
        assertTrue("if (!Unreleased.DISLIKE) return" in toggle)
        assertTrue("toggleDontPlay(this, database, metadata, held, downloadUtil, liked)" in toggle)
        val callback = source("playback/MediaLibrarySessionCallback.kt")
        assertTrue(".add(MediaSessionConstants.CommandToggleDislike)" in callback)
        assertTrue("MediaSessionConstants.ACTION_TOGGLE_DISLIKE -> toggleDislike()" in callback)
        val dialog = source("ui/screens/settings/fragments/MediaControlButtonsFrag.kt")
        assertTrue("MediaControlButtons.offered.forEach { button ->" in dialog)
        assertTrue("MediaControlButton.DISLIKE -> R.string.menu_dislike" in dialog)
    }

    @Test
    fun `the media controls' Dislike is drawn again once the songs not to play change, never from the list before the tap`() {
        val service = source("playback/MusicService.kt")
        // The collector that passes over the song playing: every change redraws the buttons, before it
        // looks at what plays, so a lift from a menu or from Exclusions shows on the button too.
        val collector = service.substringAfter("dontPlaySongs.collect { now ->").substringBefore("val playing = ")
        assertTrue("if (::mediaSession.isInitialized) updateNotification()" in collector)
        // Drawn by the tap itself, it read the list as it was: the button kept its old look, and a
        // second tap did the opposite of what it showed.
        val toggle = service.substringAfter("fun toggleDislike() {").substringBefore("\n    fun ")
        assertFalse("updateNotification()" in toggle)
    }

    @Test
    fun `its words are held from translators, with French beside them`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("menu_dislike", "menu_dislike_lift", "dislike_done", "dislike_not_sent", "dislike_lift_not_sent")) {
            assertTrue("$name is not held in English", "<string name=\"$name\" translatable=\"false\">" in english)
            assertTrue("$name has no French", "<string name=\"$name\" translatable=\"false\">" in french)
        }
    }
}

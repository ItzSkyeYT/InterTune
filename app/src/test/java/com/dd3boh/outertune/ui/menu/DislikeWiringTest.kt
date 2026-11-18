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
        assertTrue("val plan = DislikePlan.of(Unreleased.DISLIKE, held, song.isLocal, liked, context.mayPushToYouTube())" in dontPlay)
        assertTrue("DislikePlan.Tell.DISLIKE -> CoroutineScope(syncCoroutine).launch { YouTube.dislikeVideo(song.id) }" in dontPlay)
        assertTrue("DislikePlan.Tell.REMOVE -> CoroutineScope(syncCoroutine).launch { YouTube.likeVideo(song.id, false) }" in dontPlay)
        val toggle = dontPlay.substringAfter("fun toggleDontPlay(")
        // localToggleLike sends nothing; toggleLike would send removelike, which could land after the thumbs down.
        assertTrue("songRow(song.id)?.takeIf { it.liked }?.localToggleLike()?.let { unliked ->" in toggle)
        assertFalse(".toggleLike()" in toggle)
        assertTrue("downloadUtil?.autoDownloadOnLike(unliked)" in toggle)
        // Dislike's words, its thumbs down, and Don't play this song's as they were.
        assertTrue("title = if (held) R.string.menu_dislike_lift else R.string.menu_dislike," in dontPlay)
        assertTrue("icon = if (held) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt," in dontPlay)
        assertTrue("title = if (held) R.string.menu_dont_play_lift else R.string.menu_dont_play_song," in dontPlay)
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
    fun `its words are held from translators, with French beside them`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("menu_dislike", "menu_dislike_lift", "dislike_done")) {
            assertTrue("$name is not held in English", "<string name=\"$name\" translatable=\"false\">" in english)
            assertTrue("$name has no French", "<string name=\"$name\" translatable=\"false\">" in french)
        }
    }
}

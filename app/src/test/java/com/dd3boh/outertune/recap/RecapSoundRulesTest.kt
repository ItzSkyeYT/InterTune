/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rules of the Recap's sound that live in how it is wired rather than in its plan
 * (RecapSoundPlanTest), read from the source as RecapPrivacyTest reads the Recap's: nothing plays
 * outside the Recap's own screen, it sounds as the app's playback does, and every way out of the
 * Recap tells its sound.
 */
class RecapSoundRulesTest {
    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val player = code("recap/RecapPlayer.kt")

    @Test
    fun `held, paused or away from the app, the sound waits`() {
        val screen = code("ui/screens/recap/RecapScreen.kt")
        assertTrue("LaunchedEffect(held, stopped, away) { if (held || stopped || away) sound.pause() else sound.resume() }" in screen)
        // Headphones out, or another app's sound for good, pause the story with it.
        assertTrue("sound.onInterrupted = { stopped = true }" in screen)
        // And the player plays only while nothing holds it.
        assertTrue("p.playWhenReady = plan.opened && !held && !focusLost" in player)
    }

    @Test
    fun `it has no session, notification or service of its own, so nothing of it plays outside the Recap`() {
        for (word in listOf("MediaSession", "MediaLibrarySession", "startForeground", "Notification", "WAKE_MODE")) {
            assertFalse("RecapPlayer uses $word", word in player)
        }
        // Its players are made at Open Recap and let go of at the close, before the listener's music is touched.
        assertEquals(2, Regex("""= player\(source\)""").findAll(player).count())
        val close = player.substringAfter("override fun close() {").substringBefore("\n    }\n")
        assertTrue(close.indexOf("release()") in 0 until close.indexOf("run(steps)"))
    }

    @Test
    fun `it sounds as the app's playback does, on the media stream, and shares audio focus when the app does`() {
        for (attribute in listOf("C.USAGE_MEDIA", "C.AUDIO_CONTENT_TYPE_MUSIC")) {
            assertTrue("RecapPlayer: $attribute", attribute in player)
            assertTrue("MusicService: $attribute", attribute in code("playback/MusicService.kt"))
        }
        assertTrue("AudioAttributesCompat.USAGE_MEDIA" in player && "AudioAttributesCompat.CONTENT_TYPE_MUSIC" in player)
        assertTrue("if (context.dataStore.get(ShareAudioFocusKey, false)) return" in player)
        // Headphones taken out stop it, as they stop the app's player.
        assertTrue(".setHandleAudioBecomingNoisy(true)" in player)
        // Each song at the app's own level: the levelling the song playing gets, one rule for both.
        assertTrue("volumes[song.id] ?: service.volumeFor(song.id, song.local)" in player)
        assertEquals(2, Regex("""val volume = volumeOf\(song\)""").findAll(player).count())
        val service = code("playback/MusicService.kt")
        assertTrue("normalizeFactor.value = levelOf(normalizeAudio, isLocal, format?.loudnessDb)" in service)
        assertTrue("val level = levelOf(dataStore.get(AudioNormalizationKey, true), isLocal, loudness)" in service)
    }

    @Test
    fun `it finds songs with the player's own resolver, kept apart from the player's stall watch`() {
        // The stream a Recap song was found on is the player's too: the last song goes on in the
        // queue at Done without being looked up again.
        assertTrue("val source = service.recapSource()" in player)
        val service = code("playback/MusicService.kt")
        assertTrue("fun recapSource(): DataSource.Factory = createDataSourceFactory(forPlayer = false, playersResolver = true)" in service)
        assertTrue("val resolver = if (playersResolver) playerResolver else SongResolver(streamAddresses)" in service)
        // The stall watch still hears only of the player's own reads.
        assertTrue("if (forPlayer) resolvingSongs.merge(mediaId, 1, Int::plus)" in service)
    }

    @Test
    fun `every way out of the Recap tells its sound first`() {
        val entry = code("ui/screens/recap/RecapEntry.kt")
        // Back, the cross, Done: all are the dialog's close, which closes the sound before the window.
        assertTrue("val close = remember(viewModel) { { viewModel.closeSound(); closed() } }" in entry)
        assertTrue("onDismissRequest = close," in entry)
        // And the one more way out, the year card's Open on a saved playlist: it closes first, so the sound is told, then opens it.
        assertTrue("RecapHost(year, close, viewModel, sound, onOpenPlaylist = { id -> close(); opening(id) })" in entry)
        val model = code("viewmodels/RecapViewModel.kt")
        assertTrue("sound?.close()" in model)
        // The window gone for good with the Recap open: the players let go, the listener's music left alone.
        assertTrue("override fun onCleared() {\n        sound?.release()" in model)
    }

    @Test
    fun `the last song goes into the queue as the song playing now, and the song it went in front of carries on from where it was`() {
        val service = code("playback/MusicService.kt")
        val slideIn = service.substringAfter("fun slideIn(songId: String, positionMs: Long) {").substringBefore("\n    }\n")
        // In front of the song that was current, by the listener's hand, that song set aside and not skipped.
        assertTrue("queueBoard.addSongsToQueue(q, at, listOf(song))" in slideIn)
        assertTrue("noteByHand(q, listOf(song.toMediaItem()))" in slideIn)
        assertTrue("userChoicePending = true" in slideIn)
        assertTrue("activePlayer.seekTo(at, positionMs)" in slideIn)
        assertTrue("resumeAfterSlideIn = current.mediaId to currentAt" in slideIn)
        // Back where it was when it comes round by itself, a seek that is not the listener's.
        assertTrue("if (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem?.mediaId == id && at > 0 && !isCasting) {" in service)
        assertTrue("if (quietSeek) {" in service)
    }
}

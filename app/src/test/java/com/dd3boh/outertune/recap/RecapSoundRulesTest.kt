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
        assertTrue(close.indexOf("release()") in 0 until close.indexOf("run(steps.filterNot { it is Step.SlideIn })"))
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
        assertTrue("normalizeFactor.value = Normalization.factor(format?.loudnessDb, isLocal, normalizeAudio)" in service)
        assertTrue("val level = Normalization.factor(loudness, isLocal, dataStore.get(AudioNormalizationKey, true))" in service)
    }

    @Test
    fun `it finds songs with the player's own resolver, kept apart from the player's stall watch`() {
        // The stream a Recap song was found on is the player's too: the last song goes on in the
        // queue at Done without being looked up again.
        assertTrue("val source = service.recapSource()" in player)
        val service = code("playback/MusicService.kt")
        // Every source reads through the player's one resolver on this line, on calls of its own.
        assertTrue("fun recapSource(): DataSource.Factory = createDataSourceFactory(forPlayer = false)" in service)
        assertTrue("val resolver = playerResolver" in service.substringAfter("private fun createDataSourceFactory(").substringBefore("\n    }\n"))
        assertTrue("val calls = if (forPlayer) playerCalls else StreamFamily.Calls(playerStreamClient)" in service)
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
        val slideIn = service.substringAfter("fun slideIn(songId: String, positionMs: Long, from: ExoPlayer? = null) {").substringBefore("\n    }\n")
        // In front of the song that was current, by the listener's hand, that song set aside and not skipped.
        assertTrue("queueBoard.addSongsToQueue(q, at, listOf(song))" in slideIn)
        assertTrue("noteByHand(q, listOf(song.id))" in slideIn)
        assertTrue("userChoicePending = true" in slideIn)
        assertTrue("activePlayer.seekTo(at, positionMs)" in slideIn)
        assertTrue("resumeAfterSlideIn = current.mediaId to currentAt" in slideIn)
        // Back where it was when it comes round by itself, a seek that is not the listener's.
        assertTrue("if (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem?.mediaId == id && at > 0 && !isCasting) {" in service)
        assertTrue("if (quietSeek) {" in service)
    }

    @Test
    fun `the last song goes on without a gap, and the Recap's player is always let go`() {
        // His "slight cut between when the recap ends and the end song plays", 22 Nov 2026: the player
        // sounding the last song is kept out of the release and handed to the service.
        val close = player.substringAfter("override fun close() {").substringBefore("\n    }\n")
        assertTrue("holding[it]?.first == slide.songId" in close)
        assertTrue(close.indexOf("active = null") in 0 until close.indexOf("release()"))
        assertTrue(close.indexOf("service.slideIn(slide.songId, slide.positionMs, from = keep)") > close.indexOf("release()"))
        val service = code("playback/MusicService.kt")
        val slideIn = service.substringAfter("fun slideIn(songId: String, positionMs: Long, from: ExoPlayer? = null) {")
            .substringBefore("\n    private fun startSlidIn(")
        // Every way out before the song starts lets it go.
        assertTrue("if (destroyed) {\n            from?.release()\n            return\n        }" in slideIn)
        assertTrue("if (!handed) from?.release()" in slideIn)
        assertEquals(2, Regex("""handed = true\n\s+startSlidIn\(songId, from\)""").findAll(slideIn).count())
        val start = service.substringAfter("private fun startSlidIn(songId: String, from: ExoPlayer?) {").substringBefore("\n    }\n")
        // This phone's own player only, and only from a Recap's player still sounding.
        assertTrue("if (from == null || !sounding || activePlayer !== p) {\n            from?.release()\n            activePlayer.playWhenReady = true" in start)
        // The queue's player waits, silent, until it is ready a little ahead of the Recap's, with a time limit.
        assertTrue(start.indexOf("p.playWhenReady = false") in 0 until start.indexOf("withTimeoutOrNull(HANDOVER_WAIT_MS)"))
        assertTrue("quietSeek = true\n                        p.seekTo(at)" in start)
        assertTrue(start.indexOf("p.playWhenReady = true") > start.indexOf("withTimeoutOrNull(HANDOVER_WAIT_MS)"))
        // Its volume is the service's again at the end, unless the service set it meanwhile.
        assertTrue("if (p.volume == set) {" in start)
        assertTrue("} finally {\n                from.release()\n            }" in start)
    }
}

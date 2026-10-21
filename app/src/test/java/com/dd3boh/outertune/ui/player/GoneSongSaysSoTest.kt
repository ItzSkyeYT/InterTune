/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.utils.YTPlayerUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * A song YouTube no longer serves says so in the player, in plain words.
 *
 * Every client turned the song down, no other copy of the recording was surely the same, and
 * the player stopped on "Source error (2000): Unexpected SongUnavailable: This video is not
 * available". Nothing in that says it is the one song and not the app, the network or the
 * account. It gets a sentence of its own, as a busy YouTube and an age gate have.
 */
class GoneSongSaysSoTest {

    private val gone get() = YTPlayerUtils.SongUnavailable("This video is not available")

    @Test
    fun `the failure is known for what it is, however it is wrapped`() {
        assertTrue(YTPlayerUtils.isUnavailableSong(gone))
        // As it reaches the player: ExoPlayer's source error round a loader's "unexpected" one.
        assertTrue(YTPlayerUtils.isUnavailableSong(RuntimeException("Source error", IllegalStateException("Unexpected SongUnavailable", gone))))
    }

    @Test
    fun `nothing else is taken for it`() {
        assertFalse(YTPlayerUtils.isUnavailableSong(null))
        assertFalse(YTPlayerUtils.isUnavailableSong(IOException("This video is not available")))
        assertFalse(YTPlayerUtils.isUnavailableSong(RuntimeException("Source error", IOException("403"))))
    }

    @Test
    fun `a chain that never ends is given up on`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(YTPlayerUtils.isUnavailableSong(a))
    }

    @Test
    fun `the player says it, after the two it already told apart`() {
        val box = File("src/main/java/com/dd3boh/outertune/ui/player/ThumbnailPlaybackError.kt").readText()
        val busy = box.indexOf("stringResource(R.string.err_youtube_busy)")
        val age = box.indexOf("stringResource(R.string.err_age_restricted)")
        val goneAt = box.indexOf("stringResource(R.string.err_song_unavailable)")
        val raw = box.indexOf("ErrorText.playerLine(")
        assertTrue("the player has no sentence for a song that is gone", goneAt >= 0)
        assertTrue("its place is after the busy line and the age gate, and before the raw text", busy in 0 until age && age < goneAt && goneAt < raw)
        assertTrue("YTPlayerUtils.isUnavailableSong(error)" in box)
    }

    private val service get() = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `when the player skips or stops on it, the toast says it in plain words, once for each song`() {
        // His phone, 21 Oct 2026: thirteen taps, each answered with "plr: Source error (2000):
        // Unexpected SongUnavailable: This video is not available".
        val onError = service.substringAfter("override fun onPlayerError(").substringBefore("override fun onPlayWhenReadyChanged(")
        assertTrue("no plain sentence for a gone song in onPlayerError", onError.contains("getString(R.string.err_song_unavailable)"))
        assertTrue("not told once for each song", onError.contains("goneSongs.tellOnce("))
    }

    @Test
    fun `the player's stand-ins know a song set aside as gone, and a run not believed takes back what was set aside at once`() {
        val standIns = service.substringAfter("private val standIns by lazy").substringBefore("private val goneSongs = GoneSongs(")
        val setAside = standIns.substringAfter("setAside = ", "").substringBefore(".also {")
        assertTrue("StandIns is not told which songs are set aside as gone", setAside.contains("REASON_GONE"))
        assertTrue(standIns.contains("it.onDoubted = goneSongs::doubted"))
    }

    @Test
    fun `the song the music stopped on, asked for again, is asked of YouTube whatever was set aside`() {
        // Retry on this screen, Play in the app or on the notification: the music stopped on the
        // song, and only the listener starts it again. Skipping onto it, or a radio reaching it,
        // ends the stop first (onMediaItemTransition), and those respect what was set aside.
        val resolve = service.substringAfter("fun resolve(mediaId: String, position: Long, length: Long): ResolvedSong {")
        assertTrue(resolve.contains("standIns.playbackData(mediaId, askedAgain = stoppedByError && stoppedOn == mediaId)"))
        assertEquals("the music is stopped on an error in one place, which names the song", 1, Regex("""stoppedByError = true""").findAll(service).count())
        val stopping = service.substringAfter("private fun stopsOnError()").substringBefore("\n    }")
        assertTrue(stopping.contains("stoppedOn = activePlayer.currentMediaItem?.mediaId"))
    }

    @Test
    fun `its words are in English and in French`() {
        for (folder in listOf("values", "values-fr")) {
            val text = Regex("<string name=\"err_song_unavailable\">(.*?)</string>")
                .find(File("src/main/res/$folder/strings-ot.xml").readText())?.groupValues?.get(1)
            assertTrue("no text in $folder", !text.isNullOrBlank())
            assertTrue("$folder: it names YouTube", "YouTube" in text!!)
            assertFalse("$folder: no dash", '—' in text)
        }
    }
}

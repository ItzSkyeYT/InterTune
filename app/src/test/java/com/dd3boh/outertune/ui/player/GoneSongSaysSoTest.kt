/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.utils.YTPlayerUtils
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

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.utils.YTPlayerUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Issue #82 (0.12.8, Galaxy S21 Ultra, 4 Nov 2026): the next song was fetched ahead, every client
 * called it "not available", and five minutes later, the screen off, the music stopped on it until
 * the next one was started by hand. A song YouTube calls gone is passed over, whatever "Auto skip
 * to next song when error occurs" says, and only that: see GoneSkip.
 */
class GoneSkipTest {

    /** As ExoPlayer hands the resolver's failure to onPlayerError: its source error round a loader's "unexpected" one. */
    private fun handedOver(cause: Throwable) =
        PlaybackException("Source error", RuntimeException("Unexpected", cause), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private val gone = handedOver(YTPlayerUtils.SongUnavailable("This video is not available"))

    /** Song "b" at place 1 of a queue a, b, c, failed before any of it played, nothing else wrong. */
    private fun target(
        error: Throwable = gone,
        failed: String? = "b",
        current: String? = "b",
        readyFor: String? = null,
        at: Int = 1,
        next: Int = 2,
        previous: Int = 0,
        back: Boolean = false,
        tooMany: Boolean = false,
    ) = GoneSkip.target(error, failed, current, readyFor, at, next, previous, back, tooMany)

    @Test
    fun `a song every client called gone is passed over to the next one`() {
        assertEquals(2, target())
        // Set aside as gone and looked for in vain lately: StandIns fails it as gone without asking.
        assertEquals(2, target(error = handedOver(YTPlayerUtils.SongUnavailable("Set aside as gone from YouTube, not asked again for now"))))
        // The account said so too.
        assertEquals(2, target(error = handedOver(YTPlayerUtils.SongUnavailable("This video is not available", byTheAccountToo = true))))
    }

    @Test
    fun `nothing that a retry, another route or the network can mend is passed over`() {
        // Every route refused: StreamCheck.refusalMessage's PlaybackException, no SongUnavailable.
        assertNull(target(error = handedOver(PlaybackException("Sign in to confirm you're not a bot (403)", null, PlaybackException.ERROR_CODE_REMOTE_ERROR))))
        assertNull(target(error = handedOver(SocketTimeoutException("timeout"))))
        assertNull(target(error = handedOver(IOException("unexpected end of stream"))))
        assertNull(target(error = PlaybackException("no network", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        // A run of gone songs StandIns no longer believes is the connection, not the songs (issue #28).
        assertNull(target(error = handedOver(YTPlayerUtils.ConnectionTurnedAway("This video is not available"))))
        // Words alone are not YouTube's verdict: only the type the walk throws is.
        assertNull(target(error = handedOver(IOException("This video is not available"))))
    }

    @Test
    fun `a song that played some of itself in this play is left to the setting`() {
        // YouTube served it a moment ago: "gone" partway through is more likely the connection.
        assertNull(target(readyFor = "b"))
        // Ready for the song before it says nothing of this one.
        assertEquals(2, target(readyFor = "a"))
    }

    @Test
    fun `only the song the player is on, and only when there is somewhere to go`() {
        assertNull(target(current = "a"))
        assertNull(target(failed = null))
        assertNull(target(next = -1))
        // Repeat all round a queue of one: the next song is the same one.
        assertNull(target(at = 0, next = 0, previous = 0))
        // The runaway guard: another error now would be too many in a row.
        assertNull(target(tooMany = true))
    }

    @Test
    fun `reached by going back, it keeps going back, and goes on only when nothing is behind`() {
        assertEquals(0, target(back = true))
        assertEquals(2, target(back = true, previous = -1))
        assertNull(target(back = true, previous = -1, next = -1))
    }

    private val service get() = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    @Test
    fun `the player passes a gone song over before the network wait and before skip on error is read`() {
        val onError = service.substringAfter("override fun onPlayerError(").substringBefore("override fun onPlayWhenReadyChanged(")
        val passOver = onError.indexOf("passOverGone(error, failedId)")
        assertTrue("onPlayerError does not pass a gone song over", passOver >= 0)
        assertTrue("before the network wait", passOver < onError.indexOf("NetworkRetryPolicy.waitsForNetwork("))
        assertTrue("before the quiet retry", passOver < onError.indexOf("Recovery.kind(error)"))
        assertTrue("before the setting is read", passOver < onError.indexOf("dataStore.get(SkipOnErrorKey, false)"))
        // After the bookkeeping every failure gets: the play's end, the address forgotten, the health line.
        assertTrue(onError.indexOf("plays.playerError(") < passOver)
        assertTrue(onError.indexOf("streamAddresses.playerFailedOn(failedId)") < passOver)
        assertTrue(onError.indexOf("PlayHealth.thisRun.failed()") < passOver)
    }

    @Test
    fun `the pass over is counted against a run of errors, is no skip of the listener's, and names the song`() {
        val pass = service.substringAfter("private fun passOverGone(").substringBefore("\n    }\n")
        assertTrue(pass.contains("GoneSkip.target("))
        assertTrue("the runaway guard", pass.contains("consecutivePlaybackErr + 2 > MAX_PLAYER_CONSECUTIVE_ERR") && pass.contains("consecutivePlaybackErr += 2"))
        assertTrue("the adaptive queue must not read it as a skip", pass.contains("skippingOnError = true"))
        assertTrue("Previous keeps going back", pass.contains("back = transitionWentBack"))
        assertTrue("which song, in plain words", pass.contains("R.string.gone_song_skipped"))
        assertFalse("never started for a listener who had paused", pass.contains(".play()"))
        // Ready to play the song since it became current: set by onEvents, cleared at every song change.
        assertTrue(service.substringAfter("override fun onEvents(").contains("readyFor = player.currentMediaItem?.mediaId"))
        assertTrue(service.substringAfter("override fun onMediaItemTransition(").substringBefore("noteTransition(").contains("readyFor = null"))
    }

    @Test
    fun `its words are in English and in French, name the song and YouTube, and have no dash`() {
        for (folder in listOf("values", "values-fr")) {
            val text = Regex("<string name=\"gone_song_skipped\">(.*?)</string>")
                .find(File("src/main/res/$folder/strings-ot.xml").readText())?.groupValues?.get(1)
            assertTrue("no text in $folder", !text.isNullOrBlank())
            assertTrue("$folder: names the song", "%1\$s" in text!!)
            assertTrue("$folder: names YouTube", "YouTube" in text)
            assertFalse("$folder: no dash", '—' in text || '–' in text)
            assertTrue("$folder: two short sentences at most", text.count { it == '.' } <= 2 && text.length <= 120)
            // A toast shows two lines, about 70 letters, and cuts the rest: on the emulator "Skipped
            // “Bohemian Rhapsody”. YouTube no longer has it, or does not serve it wh…" lost the end
            // of the why. The title goes last, so that a long one is what is cut, never the why.
            val before = text.substringBefore("%1\$s")
            assertTrue("$folder: the title comes last", text.substringAfter("%1\$s").trim().length <= 2)
            assertTrue("$folder: the words before the title leave room for it on two lines", before.length <= 50)
        }
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Another app's equalizer stays on the music through a pause (#133).
 *
 * An equalizer app puts its effects on the player's audio session when the player announces it
 * and takes them off when the player closes it. The session used to be closed at every pause and
 * announced again at every play, so with Precise Volume the first moment after Play was heard
 * without its equalizer and its lower volume. Now a pause closes nothing, and each play still
 * announces the session, so that an equalizer app opened after the music began is told at the
 * next play, as its users expect. The rule is tested as it is; a JVM test cannot play, so the
 * places in the service that follow it are read from the source, comments taken out.
 */
class EffectsSessionTest {

    private val sent = mutableListOf<String>()
    private val session = EffectsSession { open, id -> sent += "${if (open) "open" else "close"} $id" }

    private fun EffectsSession.player(playing: Boolean, id: Int = 7, barred: Boolean = false) = update(id, playing, barred)

    @Test
    fun `a pause never closes the session`() {
        session.player(playing = true)
        repeat(5) {
            session.player(playing = false)
            session.player(playing = true)
        }
        assertFalse("the session was closed around a pause: $sent", sent.any { it.startsWith("close") })
        assertEquals(7, session.announced)
    }

    @Test
    fun `each play announces the session, for an equalizer app opened since the last one`() {
        session.player(playing = true)
        assertEquals(listOf("open 7"), sent)
        session.player(playing = false)
        assertEquals("a pause says nothing", listOf("open 7"), sent)
        session.player(playing = true)
        assertEquals(listOf("open 7", "open 7"), sent)
    }

    @Test
    fun `music that goes on playing says nothing more`() {
        session.player(playing = true)
        // Loading turns to ready, a seek loads again, the next song comes: still playing.
        repeat(6) { session.player(playing = true) }
        assertEquals(listOf("open 7"), sent)
    }

    @Test
    fun `the end of the queue, a stop and an error do not close it, and the next start announces it`() {
        session.player(playing = true)
        // Ended, or idle after a stop or an error: not playing, twice over.
        session.player(playing = false)
        session.player(playing = false)
        assertEquals(listOf("open 7"), sent)
        // Loading again and meant to play, then ready.
        session.player(playing = true)
        session.player(playing = true)
        assertEquals(listOf("open 7", "open 7"), sent)
    }

    @Test
    fun `nothing is announced before music plays, and nothing is then closed`() {
        session.player(playing = false)
        session.close()
        assertEquals(emptyList<String>(), sent)
        assertNull(session.announced)
    }

    @Test
    fun `the service ending closes the session it announced, once`() {
        session.player(playing = true)
        session.player(playing = false)
        session.close()
        session.close()
        assertEquals(listOf("open 7", "close 7"), sent)
        assertNull(session.announced)
    }

    @Test
    fun `the upmix or a receiver takes the session back, and music on the phone announces it again`() {
        session.player(playing = true)
        session.player(playing = true, barred = true)
        assertEquals(listOf("open 7", "close 7"), sent)
        // Still barred: paused or playing, nothing is announced.
        session.player(playing = false, barred = true)
        session.player(playing = true, barred = true)
        assertEquals(2, sent.size)
        // Free again while the music plays on: announced, although no pause came in between.
        session.player(playing = true)
        assertEquals(listOf("open 7", "close 7", "open 7"), sent)
    }

    @Test
    fun `a session closed by hand while music plays is announced at the next look`() {
        // The hand-over to a receiver closes it itself, and the phone may be playing again
        // before any event of the receiver's has been heard.
        session.player(playing = true)
        session.close()
        session.player(playing = true)
        assertEquals(listOf("open 7", "close 7", "open 7"), sent)
    }

    @Test
    fun `the id not made yet is never announced, and the one made is, for the play that waited`() {
        // media3 1.8.0 answers 0 until its session id has been made: to Android, all the phone's sound.
        assertEquals(C.AUDIO_SESSION_ID_UNSET, EffectsSession.UNSET)
        session.player(playing = true, id = EffectsSession.UNSET)
        assertEquals(emptyList<String>(), sent)
        // The id arrives while that music still plays: no new play, and it is announced all the same.
        session.player(playing = true, id = 7)
        assertEquals(listOf("open 7"), sent)
        // And an id that goes back to unset while announced changes nothing.
        session.player(playing = true, id = EffectsSession.UNSET)
        assertEquals(listOf("open 7"), sent)
    }

    @Test
    fun `another session id closes the one announced and announces the new one, once`() {
        session.player(playing = true, id = 7)
        session.player(playing = true, id = 9)
        assertEquals(listOf("open 7", "close 7", "open 9"), sent)
        session.close()
        assertEquals("closed under the id it was announced with", "close 9", sent.last())
    }

    // ---- the service follows the rule

    private val service = stripComments(File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText())

    /** The body of the function whose head is [head], to its closing brace. */
    private fun body(head: String): String {
        val from = service.indexOf(head)
        assertTrue("no $head", from >= 0)
        var depth = 0
        var i = service.indexOf('{', from)
        val start = i
        while (i < service.length) {
            when (service[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return service.substring(start, i + 1)
            }
            i++
        }
        error("$head never closes")
    }

    @Test
    fun `the two broadcasts are sent from one place, by the rule`() {
        assertEquals(1, Regex("""ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION""").findAll(service).count())
        assertEquals(1, Regex("""ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION""").findAll(service).count())
        val sender = service.substringAfter("private val effectsSession = EffectsSession {").substringBefore("var consecutivePlaybackErr")
        assertTrue("ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION" in sender && "ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION" in sender)
        // The id the rule hands over, which for a close is the one announced: not the player's of the moment.
        assertTrue("putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)" in sender)
        assertFalse("exoPlayer.audioSessionId" in sender)
    }

    @Test
    fun `the player's events ask the rule and never close the session themselves`() {
        val events = body("override fun onEvents(player: Player, events: Player.Events)")
        assertTrue("syncEffectsSession(isBufferingOrReady && player.playWhenReady)" in events)
        assertFalse("effectsSession.close()" in events)
        val sync = body("private fun syncEffectsSession(")
        assertTrue("effectsSession.update(exoPlayer.audioSessionId, playing, barred = isCasting || spatialUpmixProcessor.enabled)" in sync)
        // The id made a moment after the player is built, and the upmix switched on or off.
        assertTrue("syncEffectsSession()" in body("override fun onAudioSessionIdChanged(audioSessionId: Int)"))
        val upmix = service.substringAfter("spatialUpmixProcessor.enabled = wantSurround").substringBefore("exoPlayer.stop()")
        assertTrue("the upmix is switched without asking the rule", "syncEffectsSession()" in upmix)
    }

    @Test
    fun `the session is closed when the service ends and when the sound goes to a receiver, nowhere else`() {
        assertEquals(2, Regex("""effectsSession\.close\(\)""").findAll(service).count())
        assertTrue("effectsSession.close()" in body("internal fun handOverTo("))
        val end = body("override fun onDestroy()")
        val close = end.indexOf("effectsSession.close()")
        assertTrue("the service ends without closing the session", close >= 0)
        // While the player still exists, and before the stop that used to do it by the way.
        assertTrue(close < end.indexOf("mediaSession.player.stop()"))
        assertTrue(close < end.indexOf("mediaSession.player.release()"))
    }

    /** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
}

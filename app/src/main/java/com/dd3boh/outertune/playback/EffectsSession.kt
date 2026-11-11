/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

/**
 * What the phone's equalizer apps have been told of the player's audio session, and when (#133).
 *
 * An equalizer app (the phone's own, Wavelet, Precise Volume) puts its effects on a player's audio
 * session when the player announces it (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) and
 * takes them all off when the player closes it (ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION). Up to
 * 0.12.10 the session was closed at every pause and announced again at every play, at the moment
 * the sound came back: the effects left with each pause and returned a moment into the music,
 * which played without them, louder and unequalized, until they did.
 *
 * So the session is announced once, when music first plays on the phone, and stays announced
 * through pauses, the end of the queue, stops and errors. It is closed, under the id it was
 * announced with, only when the service ends, when the sound goes to a receiver, or when the upmix
 * comes on (MusicService.syncEffectsSession says why those two).
 */
class EffectsSession(private val send: (open: Boolean, sessionId: Int) -> Unit) {

    /** The session id the equalizer apps hold, null while they hold none. */
    var announced: Int? = null
        private set

    /**
     * Brings what is announced in line with the player. [sessionId] is the player's audio session,
     * [UNSET] until media3 has made one; [playing] says the phone's own player is loading or
     * playing and meant to play; [barred] that no effects may sit on the session now.
     */
    fun update(sessionId: Int, playing: Boolean, barred: Boolean) {
        val held = announced
        when {
            barred -> close()
            sessionId == UNSET -> Unit
            held == null -> if (playing) open(sessionId)
            // media3 keeps one id for the player's life; were it ever given another, the effects follow.
            held != sessionId -> {
                close()
                open(sessionId)
            }
        }
    }

    /** The equalizer apps let go of the session: the service ends, or the sound leaves the phone. */
    fun close() {
        val held = announced ?: return
        announced = null
        send(false, held)
    }

    private fun open(sessionId: Int) {
        announced = sessionId
        send(true, sessionId)
    }

    companion object {
        /**
         * C.AUDIO_SESSION_ID_UNSET, which media3 1.8.0's player answers until its session id has
         * been made on another thread. To Android it is the id of all the phone's sound, so it is
         * never announced as this player's.
         */
        const val UNSET = 0
    }
}

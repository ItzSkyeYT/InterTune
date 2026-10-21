/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient

/**
 * The text of the last few player answers that can be streamed over SABR: what a song is started
 * over SABR from (the app's playback/sabr). Their SABR fields, the streaming address and the
 * ustreamer config, are not in our model, and keeping the text saves asking YouTube a second time
 * for them.
 *
 * Bounded, and one answer per song and client as it was asked: VISIONOS's other identities each
 * keep their own, since a stream is asked for as the identity the answer went to. The three shared
 * one place, and the newest took it even when it was a refusal and an older one's was OK. An answer
 * that cannot be streamed over SABR, a refusal above all, is not kept at all: it took that place,
 * and the room of answers that could be. Each keeps when it came ([Answer.at]), so that a start is
 * made only from an answer of the walk that wants one: a VISIONOS answer hours old, its streaming
 * address dead, could be started from after a walk that never got one.
 */
class PlayerAnswers(private val capacity: Int = 8) {
    /** [at]: when it came, wall clock, as the end of the addresses in it is counted. */
    class Answer(val videoId: String, val client: YouTubeClient, val text: String, val at: Long = System.currentTimeMillis())

    /** Oldest first, in the order they were kept. */
    private val answers = ArrayList<Answer>()

    /** Keeps [answer] when it can be streamed over SABR, in the place of the last of its song and client. */
    @Synchronized
    fun put(answer: Answer) {
        if (!streamsOverSabr(answer.text)) return
        answers.removeAll { it.videoId == answer.videoId && it.client == answer.client }
        answers.add(answer)
        while (answers.size > capacity) answers.removeAt(0)
    }

    /**
     * The answers kept for [videoId] from clients called [clientName], every identity of it, newest
     * first, and none that came before [since].
     */
    @Synchronized
    fun of(videoId: String, clientName: String, since: Long = Long.MIN_VALUE): List<Answer> =
        answers.asReversed().filter { it.videoId == videoId && it.client.clientName == clientName && it.at >= since }.sortedByDescending { it.at }

    /** The newest of [of]. */
    fun latest(videoId: String, clientName: String, since: Long = Long.MIN_VALUE): Answer? = of(videoId, clientName, since).firstOrNull()

    companion object {
        /** Whether an answer's text names a SABR streaming address, read without decoding it. */
        fun streamsOverSabr(text: String): Boolean = "\"serverAbrStreamingUrl\"" in text
    }
}

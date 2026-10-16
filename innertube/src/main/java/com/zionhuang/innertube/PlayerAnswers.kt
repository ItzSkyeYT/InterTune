/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient

/**
 * The text of the last few player answers, newest first: what a song is started over SABR from
 * (the app's playback/sabr). Their SABR fields, the streaming address and the ustreamer config, are
 * not in our model, and keeping the text saves asking YouTube a second time for them. Bounded, and
 * one answer per song and client.
 */
class PlayerAnswers(private val capacity: Int = 8) {
    class Answer(val videoId: String, val client: YouTubeClient, val text: String)

    private val answers = ArrayDeque<Answer>()

    @Synchronized
    fun put(answer: Answer) {
        answers.removeAll { it.videoId == answer.videoId && it.client.clientName == answer.client.clientName }
        answers.addFirst(answer)
        while (answers.size > capacity) answers.removeLast()
    }

    /** The newest answer for [videoId] from a client called [clientName], or from any client. */
    @Synchronized
    fun latest(videoId: String, clientName: String? = null): Answer? =
        answers.firstOrNull { it.videoId == videoId && (clientName == null || it.client.clientName == clientName) }
}

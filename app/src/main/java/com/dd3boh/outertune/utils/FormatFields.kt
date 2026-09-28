/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.response.PlayerResponse

/**
 * The codecs token out of a format's mimeType, e.g. "opus" from
 * `audio/webm; codecs="opus"`, quoted or not, and whatever mime parameters follow it. A format
 * with no codecs parameter at all used to crash this with an IndexOutOfBoundsException from
 * `mimeType.split("codecs=")[1]`, on Room's query executor thread, which nothing catches an
 * exception from (MusicService's and DownloadUtil's database.query blocks). Blank rather than
 * throwing when the parameter is simply not there.
 */
fun PlayerResponse.StreamingData.Format.codecsOrEmpty(): String =
    mimeType.substringAfter("codecs=", "").substringBefore(';').trim().removeSurrounding("\"")

/**
 * contentLength is Long? in the model, so YouTube leaving it out is expected. Forcing it threw on
 * Room's query executor, where nothing catches, and closed the app. 0 stands in for it; the song
 * details sheet shows a streamed song's 0 as unknown.
 */
fun PlayerResponse.StreamingData.Format.contentLengthOrZero(): Long = contentLength ?: 0L

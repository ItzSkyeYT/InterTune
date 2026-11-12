/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.response.PlayerResponse
import kotlin.io.encoding.Base64

/**
 * Of the audio streams YouTube offers for a video, the ones in the language it was made in (#151).
 *
 * A video can come with its sound in several languages: the original, dubs its channel uploaded and
 * dubs YouTube makes by machine. Each is offered as every stream the video has, so one podcast
 * episode came as 105 audio streams, five for each of 21 languages (8e3RC1L5V_Y, asked as VISIONOS
 * on 12 Nov 2026). The app took the largest of them all, and the largest is whichever language
 * happened to need the most bits: Italian for that episode, Filipino for another, Indonesian for a
 * third, never the one the people in it speak.
 *
 * What says which is which, in those answers: each stream's `audioTrack` (an id such as "en-US.4"
 * or "it.10", a name, and whether it is the default) and its `xtags`, a small protobuf map whose
 * `acont` is "original", "dubbed" or "dubbed-auto". The name ends in "original" too, in the
 * language the answer was asked in.
 *
 * So, before any stream is chosen for its quality ([AudioPick.choose]):
 * - a video in one language, no stream with an `audioTrack`: every stream, as before;
 * - the streams called original, when there are any;
 * - with none called original, the dubs are set aside and the default language is taken of what is
 *   left, or all of what is left when none is the default;
 * - with nothing but dubs, or nothing marked at all: every stream, as before, so the video plays.
 *
 * The streams of one language come in the same five sizes as a video's in one language do, so the
 * quality tiers choose among them as they always have.
 */
internal object OriginalAudio {
    private const val ORIGINAL = "original"
    private const val DUBBED = "dubbed"

    /** Longer than any real one by far (the longest seen is 50 characters): not worth decoding. */
    private const val MAX_XTAGS = 1024

    /** The streams to choose among: see [OriginalAudio]. */
    fun of(formats: List<PlayerResponse.StreamingData.Format>): List<PlayerResponse.StreamingData.Format> {
        if (formats.none { it.audioTrack != null }) return formats
        val original = formats.filter { isOriginal(it) }
        if (original.isNotEmpty()) return original
        val notDubbed = formats.filter { !isDub(it) }.ifEmpty { formats }
        return notDubbed.filter { it.audioTrack?.audioIsDefault == true }.ifEmpty { notDubbed }
    }

    /**
     * YouTube's word for what a stream's sound is, "original", "dubbed" or "dubbed-auto": the
     * `acont` of its xtags. Null for a stream without one, as every stream of a video in one
     * language is.
     */
    fun contentOf(format: PlayerResponse.StreamingData.Format): String? = xtagsOf(format.xtags)["acont"]

    /** Called original by its xtags, or with none to go by, by its name. */
    fun isOriginal(format: PlayerResponse.StreamingData.Format): Boolean = when (val content = contentOf(format)) {
        null -> format.audioTrack?.displayName?.contains(ORIGINAL, ignoreCase = true) == true
        else -> content == ORIGINAL
    }

    /** A dub, uploaded ("dubbed") or made by machine ("dubbed-auto"). */
    fun isDub(format: PlayerResponse.StreamingData.Format): Boolean = contentOf(format)?.startsWith(DUBBED) == true

    /**
     * For the log, when a video comes in several languages: which one [taken] is, of how many, and
     * why. Null for a video in one language, which is nearly every song. [kept]: taken because the
     * player has bytes of it already (mid-song), not chosen.
     */
    fun said(offered: List<PlayerResponse.StreamingData.Format>, taken: PlayerResponse.StreamingData.Format?, kept: Boolean = false): String? {
        val languages = offered.mapNotNull { it.audioTrack?.id }.distinct().size
        if (languages == 0 || taken == null) return null
        val track = taken.audioTrack
        val which = "${track?.id ?: "no id"} (${track?.displayName ?: "no name"})"
        val why = when {
            kept -> "the stream the player has bytes of"
            isOriginal(taken) -> "the original"
            isDub(taken) -> "a dub, none is called the original"
            track?.audioIsDefault == true -> "the default, none is called the original"
            else -> "none is called the original"
        }
        return "audio in $languages languages: took $which, $why"
    }

    /**
     * A stream's xtags as a map. It is base64url of a protobuf message whose field 1, repeated, is
     * an entry of a key (1) and a value (2): "acont" to "original" and "lang" to "en-US" for the
     * original, "drc" to "1" for a stable volume stream. Empty for none, and for one that does not
     * read as that.
     */
    fun xtagsOf(xtags: String?): Map<String, String> {
        if (xtags.isNullOrEmpty() || xtags.length > MAX_XTAGS) return emptyMap()
        return runCatching {
            val entries = LinkedHashMap<String, String>()
            // YouTube leaves the padding off, and the decoder wants it.
            val unpadded = xtags.trimEnd('=')
            val message = Reader(Base64.UrlSafe.decode(unpadded + "=".repeat((4 - unpadded.length % 4) % 4)))
            while (message.more) {
                val (field, bytes) = message.field() ?: break
                if (field != 1) continue
                val entry = Reader(bytes)
                var key: String? = null
                var value: String? = null
                while (entry.more) {
                    val (part, text) = entry.field() ?: break
                    when (part) {
                        1 -> key = text.decodeToString()
                        2 -> value = text.decodeToString()
                    }
                }
                if (key != null && value != null) entries[key] = value
            }
            entries
        }.getOrDefault(emptyMap())
    }

    /** As much of protobuf as xtags need: fields that carry bytes. Anything else ends the reading. */
    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        val more: Boolean get() = at < bytes.size

        /** The next field's number and its bytes, or null when it is not one that carries bytes. */
        fun field(): Pair<Int, ByteArray>? {
            val tag = varint()
            if (tag and 7 != LENGTH_DELIMITED) return null
            val size = varint()
            require(size >= 0 && size <= bytes.size - at)
            return (tag ushr 3) to bytes.copyOfRange(at, at + size).also { at += size }
        }

        private fun varint(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val byte = bytes[at++].toInt()
                value = value or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
                require(shift < 32)
            }
        }

        private companion object {
            const val LENGTH_DELIMITED = 2
        }
    }
}

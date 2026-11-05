/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

/** How raw audio is wrapped: bare samples as audio/L16, or the same samples in a WAV file. */
enum class PcmContainer { L16, WAV }

/**
 * Raw audio as the cast server sends it to a speaker that plays nothing else: 16-bit samples at
 * [rate] Hz in [channels] channels, either bare as audio/L16, big-endian as RFC 2586 says, or in a
 * WAV file, little-endian after a 44-byte header. [contentType] is the type it is served under, in
 * the renderer's own words where it listed them.
 *
 * Sixteen-bit samples make every frame the same size, so a byte of the file is a time in the song:
 * a renderer's range request is answered by decoding from that time, and the length is known from
 * the song's duration alone.
 */
data class PcmForm(
    val container: PcmContainer,
    val rate: Int,
    val channels: Int,
    val contentType: String = defaultType(container, rate, channels),
) {
    /** Bytes in one frame: one 16-bit sample for each channel. */
    val frameBytes: Int get() = channels * 2

    /** Bytes before the first sample: WAV's header, nothing for L16. */
    val headerBytes: Int get() = if (container == PcmContainer.WAV) WAV_HEADER_BYTES else 0

    /** About 1.4 Mbit/s for 44.1 kHz stereo, 1.5 for 48 kHz. */
    val bytesPerSecond: Int get() = rate * frameBytes

    /** Big-endian for L16 (RFC 2586), little-endian for WAV. */
    val bigEndian: Boolean get() = container == PcmContainer.L16

    /** What its url says in place of `s` or `aac`: `l16-44100-2` or `wav-48000-2`. */
    val key: String get() = "${container.name.lowercase()}-$rate-$channels"

    /** The extension its url ends in, for renderers that look at it. */
    val extension: String get() = if (container == PcmContainer.WAV) "wav" else "pcm"

    /** The whole file's length for [frames] frames, or -1 when the number is not known. */
    fun length(frames: Long): Long = if (frames < 0) -1 else headerBytes + frames * frameBytes

    /** The frame byte [position] falls in, counted from the first sample; the header's bytes are before frame 0. */
    fun frameAt(position: Long): Long = if (position < headerBytes) 0 else (position - headerBytes) / frameBytes

    /** How many bytes into its frame byte [position] is (0 within the header, which is served as it is). */
    fun offsetInFrame(position: Long): Int = if (position < headerBytes) 0 else ((position - headerBytes) % frameBytes).toInt()

    /** The time in microseconds where [frame] starts. */
    fun timeUs(frame: Long): Long = frame * 1_000_000L / rate

    /**
     * The 44-byte header of a WAV file of [frames] frames: RIFF, a PCM fmt chunk, and the data
     * chunk's size. A size that is not known, or too large for 32 bits, is written as the largest
     * there is, which is how a stream of unknown length is written.
     */
    fun wavHeader(frames: Long): ByteArray {
        val dataBytes = if (frames < 0) -1L else frames * frameBytes
        val data = if (dataBytes < 0 || dataBytes > MAX_U32 - 36) MAX_U32 else dataBytes
        val riff = if (data == MAX_U32) MAX_U32 else data + 36
        val out = ByteArray(WAV_HEADER_BYTES)
        var at = 0
        fun ascii(text: String) = text.forEach { out[at++] = it.code.toByte() }
        fun u32(value: Long) = repeat(4) { out[at++] = (value shr (8 * it) and 0xFF).toByte() }
        fun u16(value: Int) = repeat(2) { out[at++] = (value shr (8 * it) and 0xFF).toByte() }
        ascii("RIFF"); u32(riff); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(channels); u32(rate.toLong()); u32(bytesPerSecond.toLong()); u16(frameBytes); u16(16)
        ascii("data"); u32(data)
        return out
    }

    companion object {
        const val WAV_HEADER_BYTES = 44
        private const val MAX_U32 = 0xFFFFFFFFL

        /** Rates and channels a url may name: anything a renderer could list, nothing absurd. */
        val RATES = 8_000..192_000
        val CHANNELS = 1..2

        /** `audio/L16;rate=44100;channels=2`, as DLNA and RFC 2586 write it; `audio/wav` for WAV. */
        fun defaultType(container: PcmContainer, rate: Int, channels: Int): String =
            if (container == PcmContainer.WAV) "audio/wav" else "audio/L16;rate=$rate;channels=$channels"

        /** The form a url's kind names (`l16-44100-2`), or null for anything else. */
        fun fromKey(key: String): PcmForm? {
            val parts = key.split('-')
            if (parts.size != 3) return null
            val container = when (parts[0]) {
                "l16" -> PcmContainer.L16
                "wav" -> PcmContainer.WAV
                else -> return null
            }
            if (!parts[1].all { it in '0'..'9' } || !parts[2].all { it in '0'..'9' }) return null
            val rate = parts[1].toIntOrNull()?.takeIf { it in RATES } ?: return null
            val channels = parts[2].toIntOrNull()?.takeIf { it in CHANNELS } ?: return null
            return PcmForm(container, rate, channels)
        }
    }
}

/**
 * One audio/L16 entry a renderer listed: its words for the type, and the rate and channels it
 * names, each null when it names none. Its DLNA profile, LPCM for most, when it gave one.
 */
data class PcmOffer(val contentType: String, val rate: Int? = null, val channels: Int? = null, val profile: String? = null)

/**
 * The raw audio a renderer takes: its audio/L16 entries, and its word for WAV when it lists WAV.
 * L8 is never among them: eight bits sound worse, and every renderer that lists L8 lists L16.
 */
data class PcmTarget(val l16: List<PcmOffer>, val wav: String? = null) {
    val isEmpty: Boolean get() = l16.isEmpty() && wav == null

    companion object {
        /** What DLNA's LPCM profile promises: 44.1 or 48 kHz in stereo. For a test without a renderer. */
        val LPCM = PcmTarget(
            listOf(
                PcmOffer("audio/L16;rate=44100;channels=2", 44_100, 2, "LPCM"),
                PcmOffer("audio/L16;rate=48000;channels=2", 48_000, 2, "LPCM"),
            ),
        )
    }
}

/** Which raw audio goes to which renderer, kept apart from decoding so it can be tested. */
object PcmFormats {
    const val L16 = "audio/l16"

    /** The rates DLNA's LPCM profile allows. */
    val LPCM_RATES = listOf(44_100, 48_000)

    /**
     * The rate an L16 entry that names none is sent at: CD audio's, which every renderer that plays
     * L16 plays. What such a renderer does with any other rate cannot be known.
     */
    const val CD_RATE = 44_100

    private val wavTypes = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")

    /** Whether [mimeType] is audio/L16, whatever its parameters. */
    fun isL16(mimeType: String): Boolean = CastMime.bare(mimeType) == L16

    /**
     * An audio/L16 entry of a renderer's protocolInfo, read: [contentFormat] is its third field
     * (`audio/L16;rate=44100;channels=2`), [additionalInfo] its fourth. Null for any other type,
     * L8 and L24 included, and for one whose rate or channels are nonsense.
     */
    fun offer(contentFormat: String, additionalInfo: String = "*"): PcmOffer? {
        if (!isL16(contentFormat)) return null
        val params = parameters(contentFormat)
        val rate = params["rate"]?.let { it.toIntOrNull()?.takeIf { rate -> rate in PcmForm.RATES } ?: return null }
        val channels = params["channels"]?.let { it.toIntOrNull()?.takeIf { count -> count >= 1 } ?: return null }
        val profile = additionalInfo.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("DLNA.ORG_PN=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
        return PcmOffer(contentFormat.trim(), rate, channels, profile)
    }

    /** A content type's parameters, names in lower case: `rate` and `channels` for L16. */
    fun parameters(contentType: String): Map<String, String> =
        contentType.split(';').drop(1).mapNotNull { part ->
            val equals = part.indexOf('=')
            if (equals <= 0) null else part.substring(0, equals).trim().lowercase() to part.substring(equals + 1).trim().trim('"')
        }.toMap()

    /**
     * The raw audio a renderer that said [support] takes, or null when it takes none. A renderer
     * listed as only a set of types, with audio/L16 among them, is taken to list bare audio/L16.
     */
    fun target(support: FormatSupport): PcmTarget? {
        val listed = support as? FormatSupport.Listed ?: return null
        val l16 = listed.pcm.ifEmpty { if (L16 in listed.mimeTypes) listOf(PcmOffer("audio/L16")) else emptyList() }
        val wav = listed.mimeTypes.firstOrNull { it in wavTypes }
        return PcmTarget(l16, wav).takeUnless { it.isEmpty }
    }

    /**
     * The form a song decoding at [sourceRate] Hz in [sourceChannels] channels goes in to a
     * renderer that takes [target].
     *
     * L16 where it is listed. Its rate is the song's own when the renderer lists it, so nothing
     * is resampled: Opus decodes at 48 kHz, AAC mostly at 44.1. Otherwise the listed rate nearest
     * the song's among those of CD quality or better, and failing that the highest one listed. An
     * entry that names no rate is taken at CD quality only ([CD_RATE]).
     * Stereo where it is listed, since every renderer that lists both plays stereo, and mono
     * otherwise. The type is the renderer's own entry when that entry names exactly this rate
     * and these channels.
     *
     * WAV only where L16 is not listed: 44.1 or 48 kHz, whichever is the song's family, so a file
     * of an old renderer's favourite rates; the song's channels, two at most.
     */
    fun pick(target: PcmTarget, sourceRate: Int, sourceChannels: Int): PcmForm {
        require(!target.isEmpty) { "Nothing raw is listed" }
        if (target.l16.isEmpty()) {
            val rate = when {
                sourceRate == 44_100 || sourceRate == 48_000 -> sourceRate
                sourceRate % 11_025 == 0 -> 44_100
                else -> 48_000
            }
            return PcmForm(PcmContainer.WAV, rate, sourceChannels.coerceIn(1, 2), target.wav ?: "audio/wav")
        }
        return l16Forms(target, sourceRate, sourceChannels, bareRates = listOf(CD_RATE)).first()
    }

    /**
     * Every L16 form [target] takes, best first, one for each rate: the order and the channels of
     * [pick]. An entry that names no rate stands for [bareRates]: CD audio's alone for a single
     * pick, both of the LPCM profile's where the cast can fall back to the next (CastLadder).
     */
    fun l16Forms(target: PcmTarget, sourceRate: Int, sourceChannels: Int, bareRates: List<Int>): List<PcmForm> {
        val choices = target.l16.flatMap { offer ->
            val rates = offer.rate?.let(::listOf) ?: bareRates
            val channels = offer.channels?.let(::listOf) ?: listOf(1, 2)
            rates.flatMap { rate -> channels.filter { it in PcmForm.CHANNELS }.map { Triple(rate, it, offer) } }
        }
        require(choices.isNotEmpty()) { "No L16 entry names one or two channels" }
        return choices.map { it.first }.distinct().sortedWith(compareBy({ penalty(it, sourceRate) }, { it })).map { rate ->
            val atRate = choices.filter { it.first == rate }
            val channels = if (atRate.any { it.second == 2 }) 2 else 1
            val exact = atRate.firstOrNull { it.second == channels && it.third.rate == rate && it.third.channels == channels }?.third
            PcmForm(PcmContainer.L16, rate, channels, exact?.contentType ?: PcmForm.defaultType(PcmContainer.L16, rate, channels))
        }
    }

    /** How far [rate] is from [source]: none for the same, then rates of CD quality or better by distance, then lower ones, highest first. */
    private fun penalty(rate: Int, source: Int): Double = when {
        rate == source -> 0.0
        rate >= 44_100 -> 1.0 + kotlin.math.abs(rate - source) / 1e6
        else -> 10.0 + (44_100 - rate) / 1e6
    }

    /** Frames in a song of [durationUs] at [rate], rounded; -1 when the duration is not known. */
    fun frames(durationUs: Long, rate: Int): Long =
        if (durationUs <= 0) -1 else (durationUs * rate + 500_000L) / 1_000_000L

    /**
     * The DLNA profile of [mimeType]: LPCM for 16-bit audio at 44.1 or 48 kHz in one or two
     * channels, which is what the profile is; null for anything else.
     */
    fun profile(mimeType: String): String? {
        if (!isL16(mimeType)) return null
        val params = parameters(mimeType)
        val rate = params["rate"]?.toIntOrNull()
        val channels = params["channels"]?.toIntOrNull() ?: 1
        return if (rate != null && rate in LPCM_RATES && channels in PcmForm.CHANNELS) "LPCM" else null
    }
}

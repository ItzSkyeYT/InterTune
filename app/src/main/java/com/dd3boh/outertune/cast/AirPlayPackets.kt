/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * The bytes of AirPlay 1's packets, kept apart from the sockets so that every field can be tested.
 *
 * Audio goes as RTP (RFC 3550) with payload type 96 to the speaker's server port, the marker bit
 * on the first packet after RECORD or a FLUSH. The control port carries sync packets (type 84)
 * from the phone and resend requests (85) from the speaker, answered with the packet again (86);
 * the timing port carries the speaker's clock questions (82) and the phone's answers (83). Every
 * multi-byte field is big-endian.
 */
object AirPlayRtp {
    const val HEADER_BYTES = 12
    const val SYNC_BYTES = 20
    const val TIMING_BYTES = 32
    const val RESEND_REQUEST_BYTES = 8

    /** Byte 1 of each packet kind, the marker bit set as the speakers send and expect it. */
    const val TYPE_TIMING_REQUEST = 0xD2
    const val TYPE_TIMING_REPLY = 0xD3
    const val TYPE_SYNC = 0xD4
    const val TYPE_RESEND_REQUEST = 0xD5
    const val TYPE_RESEND_REPLY = 0xD6

    /**
     * An audio packet's header into [out] at [at]: version 2, payload type 96, the marker bit on
     * the [first] packet after RECORD or a FLUSH, then the sequence number, the RTP time of its
     * first frame, and the session's SSRC.
     */
    fun audioHeader(out: ByteArray, at: Int, seq: Int, timestamp: Long, ssrc: Int, first: Boolean) {
        out[at] = 0x80.toByte()
        out[at + 1] = (AirPlay.PAYLOAD_TYPE or if (first) 0x80 else 0).toByte()
        put16(out, at + 2, seq)
        put32(out, at + 4, timestamp)
        put32(out, at + 8, ssrc.toLong())
    }

    /**
     * A sync packet: at [ntp] (the phone's clock as NTP), the frame at RTP time [now] is being sent,
     * and so the one at [now] less [latency] is being played. The extension bit marks the [first]
     * after RECORD or a FLUSH. The sequence field is 7, as every sender has it.
     */
    fun sync(now: Long, latency: Int, ntp: Long, first: Boolean): ByteArray {
        val out = ByteArray(SYNC_BYTES)
        out[0] = (if (first) 0x90 else 0x80).toByte()
        out[1] = TYPE_SYNC.toByte()
        put16(out, 2, 7)
        put32(out, 4, now - latency)
        put64(out, 8, ntp)
        put32(out, 16, now)
        return out
    }

    /** Whether [packet] ([length] bytes) is a speaker's question about the clock. */
    fun isTimingRequest(packet: ByteArray, length: Int): Boolean =
        length >= TIMING_BYTES && packet[1].toInt() and 0xFF == TYPE_TIMING_REQUEST

    /**
     * The answer to a timing [request]: its sequence field as it came, its transmit time as the
     * origin, then when the phone [received] it and when it [sent] the answer, both NTP.
     */
    fun timingReply(request: ByteArray, received: Long, sent: Long): ByteArray {
        val out = ByteArray(TIMING_BYTES)
        out[0] = 0x80.toByte()
        out[1] = TYPE_TIMING_REPLY.toByte()
        out[2] = request[2]
        out[3] = request[3]
        System.arraycopy(request, 24, out, 8, 8)
        put64(out, 16, received)
        put64(out, 24, sent)
        return out
    }

    /** A speaker's resend request: the first lost sequence number and how many follow it, or null for anything else. */
    fun resendRequest(packet: ByteArray, length: Int): Pair<Int, Int>? {
        if (length < RESEND_REQUEST_BYTES || packet[1].toInt() and 0xFF != TYPE_RESEND_REQUEST) return null
        return get16(packet, 4) to get16(packet, 6)
    }

    /**
     * A lost packet sent again: type 86, a sequence field of 1 (a receiver takes 0 there for a
     * request and drops it), then the audio packet whole, header and all.
     */
    fun resendReply(packet: ByteArray, length: Int): ByteArray {
        val out = ByteArray(4 + length)
        out[0] = 0x80.toByte()
        out[1] = TYPE_RESEND_REPLY.toByte()
        put16(out, 2, 1)
        System.arraycopy(packet, 0, out, 4, length)
        return out
    }

    fun put16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value shr 8).toByte()
        out[at + 1] = value.toByte()
    }

    fun put32(out: ByteArray, at: Int, value: Long) {
        out[at] = (value shr 24).toByte()
        out[at + 1] = (value shr 16).toByte()
        out[at + 2] = (value shr 8).toByte()
        out[at + 3] = value.toByte()
    }

    fun put64(out: ByteArray, at: Int, value: Long) {
        put32(out, at, value ushr 32)
        put32(out, at + 4, value and 0xFFFFFFFFL)
    }

    fun get16(packet: ByteArray, at: Int): Int = (packet[at].toInt() and 0xFF shl 8) or (packet[at + 1].toInt() and 0xFF)

    fun get32(packet: ByteArray, at: Int): Long =
        (get16(packet, at).toLong() shl 16) or get16(packet, at + 2).toLong()

    fun get64(packet: ByteArray, at: Int): Long = (get32(packet, at) shl 32) or get32(packet, at + 4)
}

/**
 * The audio of one packet in the form the speaker was announced: an Apple Lossless frame that
 * carries its samples uncompressed, or bare L16.
 *
 * Apple Lossless has an escape for audio it does not compress, which needs no encoder: a frame
 * that says so and then holds every sample as it is. As the ALAC decoders read it (Apple's,
 * FFmpeg's alacdec, David Hammerton's in shairport), one channel pair element is, bit by bit:
 * 3 bits element type (1, a channel pair; 0 for one channel), 4 bits element instance (0), 12
 * unused bits, 1 bit "has size" (0: the frame is the 352 frames the SDP says), 2 bits of shift
 * (0), 1 bit "not compressed" (1), then each frame's samples, left then right, 16 bits each,
 * most significant first, then a 3-bit end tag (7) and zeros to the byte. Checked against FFmpeg's
 * own decoder (experiments/features/airplay).
 */
object AirPlayAudio {
    /** Bytes of one uncompressed Apple Lossless frame: 23 header bits, the samples, the 3-bit end tag, rounded up. */
    fun alacBytes(frames: Int, channels: Int): Int = (23 + frames * channels * 16 + 3 + 7) / 8

    fun pcmBytes(frames: Int, channels: Int): Int = frames * channels * 2

    /**
     * [frames] frames of interleaved 16-bit [samples] from index [from], as one uncompressed Apple
     * Lossless frame into [out] at [at]. Returns the bytes written, [alacBytes].
     */
    fun alac(samples: ShortArray, from: Int, frames: Int, channels: Int, out: ByteArray, at: Int): Int {
        var pos = at
        var acc = 0L
        var bits = 0
        fun write(value: Int, count: Int) {
            acc = (acc shl count) or (value.toLong() and ((1L shl count) - 1))
            bits += count
            while (bits >= 8) {
                bits -= 8
                out[pos++] = (acc shr bits).toByte()
            }
            acc = acc and ((1L shl bits) - 1)
        }
        write(if (channels == 2) 1 else 0, 3) // a channel pair element, or a single channel
        write(0, 4) // element instance
        write(0, 12) // unused
        write(0, 1) // has size: no, the frame is the length the SDP says
        write(0, 2) // shift
        write(1, 1) // not compressed
        val count = frames * channels
        for (index in from until from + count) write(samples[index].toInt(), 16)
        write(7, 3) // the end of the frame
        if (bits > 0) out[pos++] = (acc shl (8 - bits)).toByte()
        return pos - at
    }

    /** The same samples as bare L16, big-endian, into [out] at [at]. Returns the bytes written. */
    fun pcm(samples: ShortArray, from: Int, frames: Int, channels: Int, out: ByteArray, at: Int): Int {
        var pos = at
        for (index in from until from + frames * channels) {
            val sample = samples[index].toInt()
            out[pos++] = (sample shr 8).toByte()
            out[pos++] = sample.toByte()
        }
        return pos - at
    }
}

/** The phone's clock as NTP time: 64 bits, seconds since 1900 above, fractions of a second below. */
class AirPlayClock(
    private val nanoTime: () -> Long = System::nanoTime,
    wallMillis: Long = System.currentTimeMillis(),
) {
    private val baseNanos = nanoTime()
    private val baseNtp = ntpOfMillis(wallMillis)

    /** Now, on the clock that times everything else ([nanoTime]). */
    fun nanos(): Long = nanoTime()

    /** [nanos] as NTP time. */
    fun ntp(nanos: Long = nanoTime()): Long = baseNtp + nanosToNtp(nanos - baseNanos)

    companion object {
        /** Seconds from 1900, NTP's start, to 1970. */
        const val NTP_EPOCH_OFFSET = 2_208_988_800L

        fun ntpOfMillis(millis: Long): Long {
            val seconds = Math.floorDiv(millis, 1000L) + NTP_EPOCH_OFFSET
            val fraction = (Math.floorMod(millis, 1000L) shl 32) / 1000L
            return (seconds shl 32) or fraction
        }

        /** A span of [nanos] in NTP's units, 2^-32 of a second. */
        fun nanosToNtp(nanos: Long): Long {
            val seconds = Math.floorDiv(nanos, 1_000_000_000L)
            val rest = Math.floorMod(nanos, 1_000_000_000L)
            return (seconds shl 32) + (rest shl 32) / 1_000_000_000L
        }
    }
}

/** The session description sent with ANNOUNCE, and the volume in the speaker's units. */
object AirPlaySdp {
    /**
     * The SDP: who announces ([sessionId] from [local]), to whom ([remote]), and the audio: Apple
     * Lossless with its parameters (frames a packet, compatible version, sample size, the rice
     * coder's history, initial and limit, channels, the longest run, the largest frame and the
     * average bit rate, both 0 for not said, and the rate), or bare L16. With RSA, the AES key
     * sealed for the speaker and the IV, both base64 without padding.
     */
    fun announce(
        sessionId: Long,
        local: InetAddress,
        remote: InetAddress,
        codec: AirPlayCodec,
        rsaKey: String? = null,
        iv: String? = null,
    ): String = buildString {
        append("v=0\r\n")
        append("o=iTunes ").append(sessionId).append(" 0 IN ").append(family(local)).append(' ').append(address(local)).append("\r\n")
        append("s=iTunes\r\n")
        append("c=IN ").append(family(remote)).append(' ').append(address(remote)).append("\r\n")
        append("t=0 0\r\n")
        append("m=audio 0 RTP/AVP 96\r\n")
        when (codec) {
            AirPlayCodec.ALAC -> append("a=rtpmap:96 AppleLossless\r\n")
            AirPlayCodec.PCM -> append("a=rtpmap:96 L16/${AirPlay.RATE}/${AirPlay.CHANNELS}\r\n")
        }
        append("a=fmtp:96 ${AirPlay.FRAMES_PER_PACKET} 0 16 40 10 14 ${AirPlay.CHANNELS} 255 0 0 ${AirPlay.RATE}\r\n")
        if (rsaKey != null && iv != null) {
            append("a=rsaaeskey:").append(rsaKey).append("\r\n")
            append("a=aesiv:").append(iv).append("\r\n")
        }
    }

    private fun family(address: InetAddress) = if (address is Inet6Address) "IP6" else "IP4"

    private fun address(address: InetAddress) = (address.hostAddress ?: address.toString()).substringBefore('%')

    /** Muted, as the speakers take it. */
    const val MUTED_DB = -144.0

    /**
     * A volume from 0 to 1 in the speaker's decibels: -30 at the quietest step, 0 at the top,
     * evenly in between, and -144 for silence, as iTunes, pyatv and libraop send it.
     */
    fun volumeDb(level: Float): Double = if (level <= 0f) MUTED_DB else -30.0 + 30.0 * level.coerceAtMost(1f)

    /** The body of a SET_PARAMETER for [db], with a point whatever the phone's language. */
    fun volumeBody(db: Double): String = String.format(Locale.ROOT, "volume: %.6f\r\n", db)
}

/**
 * What a speaker with a screen is told about the song (SET_PARAMETER): its title, artist and album
 * as DAAP tags (md 0), and where it stands as RTP times (md 2). Sent for speakers that list them.
 */
object AirPlayMetadata {
    /** An `mlit` item holding `minm` (title), `asar` (artist) and `asal` (album), each left out when there is none. */
    fun daap(title: String?, artist: String?, album: String?): ByteArray {
        val items = listOfNotNull(
            title?.takeIf { it.isNotBlank() }?.let { tag("minm", it.toByteArray(Charsets.UTF_8)) },
            artist?.takeIf { it.isNotBlank() }?.let { tag("asar", it.toByteArray(Charsets.UTF_8)) },
            album?.takeIf { it.isNotBlank() }?.let { tag("asal", it.toByteArray(Charsets.UTF_8)) },
        )
        return tag("mlit", items.fold(ByteArray(0)) { all, item -> all + item })
    }

    /** `progress: start/now/end`, RTP times of the song's first frame, the frame now, and the end. */
    fun progress(start: Long, now: Long, end: Long): String =
        "progress: ${start and 0xFFFFFFFFL}/${now and 0xFFFFFFFFL}/${end and 0xFFFFFFFFL}\r\n"

    private fun tag(code: String, value: ByteArray): ByteArray {
        val out = ByteArray(8 + value.size)
        code.forEachIndexed { index, char -> out[index] = char.code.toByte() }
        AirPlayRtp.put32(out, 4, value.size.toLong())
        System.arraycopy(value, 0, out, 8, value.size)
        return out
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.locks.LockSupport
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What one packet carries, filled in by an [AirPlayStream.Feed]: [frames] frames of song [load]
 * from [songFrame] on, the rest silence. [at] is the packet's place on the session's clock, which
 * the stream sets before asking.
 */
class AirPlayPacket {
    var at: Long = 0
    var load: Int = 0
    var songFrame: Long = 0
    var frames: Int = 0

    fun silence() {
        load = 0
        songFrame = 0
        frames = 0
    }
}

/**
 * The AES half of AirPlay's RSA mode (et 1, the AirPort Express): each packet's audio encrypted
 * with AES-128 in CBC from the same IV, its whole 16-byte blocks only, the few bytes after them
 * left as they are, as the speaker decrypts it.
 */
class AirPlayAes(key: ByteArray, iv: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val ivSpec = IvParameterSpec(iv)
    private val cipher: Cipher = Cipher.getInstance("AES/CBC/NoPadding")

    fun encrypt(data: ByteArray, at: Int, length: Int) {
        val whole = length / 16 * 16
        if (whole == 0) return
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        cipher.doFinal(data, at, whole, data, at)
    }
}

/**
 * The audio half of an AirPlay session, over UDP: the packets in real time, sync packets every
 * second, answers to the speaker's clock questions, and lost packets sent again from the last few
 * seconds kept for it.
 *
 * Time is one clock for the whole session: RTP time is the frames since it began, at 44.1 kHz,
 * from a random start, so a frame's time says when it is due whether or not anything was sent in
 * between. Sending runs in stretches ([start] to [stop]): each starts with the marker bit and a
 * sync packet that says so, as the speakers expect after RECORD or a FLUSH, and from the clock's
 * time then; a packet goes out [leadNanos] before it is due, and the speaker plays it
 * [latencyFrames] after. What each packet holds comes from [feed]; with nothing to give, it is
 * silence, so the speaker's clock never has a hole in it.
 *
 * Where the speaker is listening is worked out from the same clock: what was sent at a frame's time
 * is heard [latencyFrames] plus the speaker's own [outputLatencyFrames] later. As that point moves
 * from silence into a song or out of one, [events] hears of it, which is how the link knows what
 * the speaker is playing without asking.
 */
class AirPlayStream(
    private val clock: AirPlayClock,
    private val feed: Feed,
    private val events: Events,
    private val codec: AirPlayCodec = AirPlayCodec.ALAC,
    private val aes: AirPlayAes? = null,
    private val latencyFrames: Int = AirPlay.LATENCY_FRAMES,
    private val leadNanos: Long = LEAD_NANOS,
    bind: (DatagramSocket) -> Unit = {},
    rtpStart: Long = (Math.random() * 0xFFFFFFFFL).toLong(),
    private val ssrc: Int = (Math.random() * Int.MAX_VALUE).toInt(),
    firstSeq: Int = (Math.random() * 0xFFFF).toInt(),
    /** Every packet's audio, for a check that what arrives is what was sent; debug builds only. */
    private val sentAudio: ((rtpTime: Long, samples: ShortArray, frames: Int, first: Boolean) -> Unit)? = null,
) : Closeable {
    /** Fills one packet: up to [AirPlay.FRAMES_PER_PACKET] frames into [samples], and what they are into [packet]. */
    fun interface Feed {
        fun fill(samples: ShortArray, packet: AirPlayPacket)
    }

    interface Events {
        /** The speaker began to play song [load] at [songFrame], at [atNanos] on the clock. */
        fun heard(load: Int, songFrame: Long, atNanos: Long)

        /** The speaker ran out of song [load] at [songFrame] and plays silence, from [atNanos]. */
        fun gap(load: Int, songFrame: Long, atNanos: Long)

        /** Sending failed for good: the network is gone. */
        fun failed(cause: IOException) {}
    }

    private val control = udp(bind)
    private val timing = udp(bind)
    private val audio = udp(bind)

    /** A UDP socket on a port of the system's choosing, bound to the Wi-Fi network first by [prepare]. */
    private fun udp(prepare: (DatagramSocket) -> Unit): DatagramSocket {
        val socket = DatagramSocket(null as java.net.SocketAddress?)
        try {
            prepare(socket)
            socket.bind(InetSocketAddress(0))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    /** The ports the speaker sends its resend requests and clock questions to (SETUP). */
    val controlPort: Int get() = control.localPort
    val timingPort: Int get() = timing.localPort

    private val sessionStart = clock.nanos()
    private val rtpBase = rtpStart and 0xFFFFFFFFL

    @Volatile
    private var remote: InetAddress? = null
    private var serverPort = 0
    private var remoteControlPort = 0

    /** The speaker's own delay after ours, which it says in answer to RECORD (Audio-Latency). */
    @Volatile
    var outputLatencyFrames: Int = 0

    private val lock = Object()
    private var running = false
    private var closed = false
    private var seq = firstSeq and 0xFFFF

    /** The frame, on the session's clock, of the next packet. */
    private var nextFrame = 0L
    private var firstPacket = false
    private var firstSync = false
    private var lastSyncAt = 0L
    private var sender: Thread? = null

    /** When the speaker last asked about the clock, so a speaker gone silent can be told apart. */
    @Volatile
    var lastTimingRequestAt: Long = 0L
        private set

    @Volatile
    var timingRequests: Int = 0
        private set

    @Volatile
    var resent: Int = 0
        private set

    @Volatile
    var resendMisses: Int = 0
        private set

    // The last packets sent, by sequence number, for resend requests.
    private val backlog = Array(BACKLOG) { ByteArray(AirPlayRtp.HEADER_BYTES + AirPlayAudio.alacBytes(AirPlay.FRAMES_PER_PACKET, AirPlay.CHANNELS)) }
    private val backlogLength = IntArray(BACKLOG)
    private val backlogSeq = IntArray(BACKLOG) { -1 }

    // What was sent where, for working out what the speaker is playing.
    private val spans = ArrayDeque<Span>()
    private var heardLoad = 0
    private var heardSong = false

    /** The song and frame where the last song span let go of before the speaker's point ended. */
    private var lastSongEnd: Pair<Int, Long>? = null

    private class Span(val start: Long, var end: Long, val load: Int, val songFrame: Long) {
        val song: Boolean get() = load != 0
    }

    init {
        Thread({ answerTiming() }, "AirPlay-timing").apply { isDaemon = true; start() }
        Thread({ answerControl() }, "AirPlay-control").apply { isDaemon = true; start() }
    }

    /** Where the audio and the sync packets go, once SETUP has said. */
    fun connect(host: InetAddress, serverPort: Int, controlPort: Int) {
        synchronized(lock) {
            remote = host
            this.serverPort = serverPort
            this.remoteControlPort = controlPort
        }
    }

    /** The session's frame at [nanos]. */
    fun frameAt(nanos: Long): Long = Math.floorDiv((nanos - sessionStart) * AirPlay.RATE, 1_000_000_000L)

    /** The RTP time of a session frame. */
    fun rtpTime(frame: Long): Long = (rtpBase + frame) and 0xFFFFFFFFL

    /** The clock time a session frame is due. */
    fun nanosOf(frame: Long): Long = sessionStart + Math.floorDiv(frame * 1_000_000_000L, AirPlay.RATE.toLong())

    /** The frame the speaker plays now: the clock less both delays. */
    fun heardFrame(nanos: Long): Long = frameAt(nanos) - latencyFrames - outputLatencyFrames

    /** Whether a stretch of sending is under way. */
    val isRunning: Boolean get() = synchronized(lock) { running }

    /** The sequence number of the next packet, for RECORD's RTP-Info. */
    val nextSeq: Int get() = synchronized(lock) { seq }

    /**
     * Where the speaker stands at [nanos]: the song and its frame it plays, or, in silence after a
     * song, where that song stopped. Null when nothing of a song has been played in this stretch.
     */
    fun position(nanos: Long): Pair<Int, Long>? {
        val heard = heardFrame(nanos)
        synchronized(lock) {
            var last = lastSongEnd
            for (span in spans) {
                if (span.start > heard) break
                if (span.song) last = span.load to (span.songFrame + (minOf(heard, span.end) - span.start))
            }
            return last
        }
    }

    /**
     * Starts a stretch of sending, at the clock's time now, or at [notBefore] when that is later
     * (a FLUSH just said to drop everything before it), or exactly at [at], the frame RECORD
     * named. Returns the sequence number and RTP time of its first packet.
     */
    fun start(notBefore: Long = Long.MIN_VALUE, at: Long? = null): Pair<Int, Long> {
        synchronized(lock) {
            check(!closed) { "Closed" }
            if (running) return seq to rtpTime(nextFrame)
            nextFrame = at ?: maxOf(frameAt(clock.nanos()), notBefore)
            firstPacket = true
            firstSync = true
            running = true
            spans.clear()
            heardSong = false
            heardLoad = 0
            lastSongEnd = null
            sender = Thread({ sendLoop() }, "AirPlay-send").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
            return seq to rtpTime(nextFrame)
        }
    }

    /**
     * Ends the stretch: nothing more is sent. Returns the sequence number and the session frame of
     * the packet that would have come next, for FLUSH's RTP-Info and the next [start].
     */
    fun stop(): Pair<Int, Long> {
        val thread: Thread?
        val point: Pair<Int, Long>
        synchronized(lock) {
            running = false
            thread = sender
            sender = null
            point = seq to nextFrame
            lock.notifyAll()
        }
        if (thread != null && thread !== Thread.currentThread()) thread.join(1_000)
        return point
    }

    private fun sendLoop() {
        val samples = ShortArray(AirPlay.FRAMES_PER_PACKET * AirPlay.CHANNELS)
        val packet = AirPlayPacket()
        val leadFrames = leadNanos * AirPlay.RATE / 1_000_000_000L
        // Packets go a few at a time, every 20 ms, rather than one every 8 ms: a fifth of the wake-ups.
        val batchFrames = BATCH_NANOS * AirPlay.RATE / 1_000_000_000L
        var failures = 0
        while (true) {
            val now = clock.nanos()
            val frame = frameAt(now)
            try {
                synchronized(lock) {
                    if (!running) return
                    val target = remote ?: return@synchronized
                    if (frame - nextFrame > latencyFrames / 2) {
                        // The phone stalled for longer than the speaker can wait: its packets would
                        // all come too late. Start again from now, as after a FLUSH.
                        Log.w(TAG, "Sending fell ${(frame - nextFrame) * 1000 / AirPlay.RATE} ms behind; starting again from now")
                        nextFrame = frame
                        firstPacket = true
                        firstSync = true
                    }
                    if (firstSync || now - lastSyncAt >= SYNC_EVERY_NANOS) {
                        val sync = AirPlayRtp.sync(rtpTime(frame), latencyFrames, clock.ntp(now), firstSync)
                        control.send(DatagramPacket(sync, sync.size, target, remoteControlPort))
                        firstSync = false
                        lastSyncAt = now
                    }
                    var burst = 0
                    while (nextFrame <= frame + leadFrames + batchFrames && burst < MAX_BURST) {
                        packet.at = nextFrame
                        feed.fill(samples, packet)
                        // What the feed did not fill is silence, never what the last packet held.
                        val audible = if (packet.load == 0) 0 else packet.frames.coerceIn(0, AirPlay.FRAMES_PER_PACKET)
                        if (audible < AirPlay.FRAMES_PER_PACKET) samples.fill(0, audible * AirPlay.CHANNELS, samples.size)
                        sendPacket(target, samples, packet)
                        burst++
                    }
                }
                failures = 0
            } catch (e: IOException) {
                // A network that is changing hands can refuse a send or two.
                if (++failures >= MAX_SEND_FAILURES) {
                    Log.w(TAG, "Sending to the speaker failed: ${e.message}")
                    synchronized(lock) { running = false }
                    events.failed(e)
                    return
                }
            }
            listen(frame)
            // Until the next batch is due: 20 ms, the batch's own length.
            val wait = (nanosOf(nextFrame - leadFrames - batchFrames) - clock.nanos() + BATCH_NANOS).coerceIn(500_000L, BATCH_NANOS)
            synchronized(lock) {
                if (!running) return
            }
            LockSupport.parkNanos(wait)
        }
    }

    /** One packet: its header and audio, encrypted for RSA speakers, sent and kept for resends. Holding [lock]. */
    private fun sendPacket(target: InetAddress, samples: ShortArray, packet: AirPlayPacket) {
        val slot = seq and (BACKLOG - 1)
        val bytes = backlog[slot]
        AirPlayRtp.audioHeader(bytes, 0, seq, rtpTime(nextFrame), ssrc, firstPacket)
        val length = when (codec) {
            AirPlayCodec.ALAC -> AirPlayAudio.alac(samples, 0, AirPlay.FRAMES_PER_PACKET, AirPlay.CHANNELS, bytes, AirPlayRtp.HEADER_BYTES)
            AirPlayCodec.PCM -> AirPlayAudio.pcm(samples, 0, AirPlay.FRAMES_PER_PACKET, AirPlay.CHANNELS, bytes, AirPlayRtp.HEADER_BYTES)
        }
        aes?.encrypt(bytes, AirPlayRtp.HEADER_BYTES, length)
        val total = AirPlayRtp.HEADER_BYTES + length
        backlogLength[slot] = total
        backlogSeq[slot] = seq
        sentAudio?.invoke(rtpTime(nextFrame), samples, AirPlay.FRAMES_PER_PACKET, firstPacket)
        record(nextFrame, packet)
        audio.send(DatagramPacket(bytes, total, target, serverPort))
        firstPacket = false
        seq = (seq + 1) and 0xFFFF
        nextFrame += AirPlay.FRAMES_PER_PACKET
    }

    /** Notes what the packet at [frame] holds, joined to the stretch before it when it carries on from it. */
    private fun record(frame: Long, packet: AirPlayPacket) {
        val songFrames = if (packet.load == 0) 0 else packet.frames
        val end = frame + AirPlay.FRAMES_PER_PACKET
        if (songFrames > 0) {
            val last = spans.lastOrNull()
            if (last != null && last.song && last.load == packet.load && last.end == frame &&
                last.songFrame + (last.end - last.start) == packet.songFrame
            ) {
                last.end = frame + songFrames
            } else {
                spans.addLast(Span(frame, frame + songFrames, packet.load, packet.songFrame))
            }
            if (songFrames < AirPlay.FRAMES_PER_PACKET) spans.addLast(Span(frame + songFrames, end, 0, 0))
        } else {
            val last = spans.lastOrNull()
            if (last != null && !last.song && last.end == frame) last.end = end else spans.addLast(Span(frame, end, 0, 0))
        }
        // Only what the speaker has yet to play is needed, with room for a stall.
        while (spans.size > MAX_SPANS) spans.removeFirst()
    }

    /** Where the speaker is listening at [frame] (the clock's): tells [events] when that moves into a song or out of one. */
    private fun listen(frame: Long) {
        val heard = frame - latencyFrames - outputLatencyFrames
        var news: Triple<Int, Long, Boolean>? = null
        var at = 0L
        synchronized(lock) {
            while (spans.size > 1 && spans.first().end <= heard) {
                val gone = spans.removeFirst()
                if (gone.song) lastSongEnd = gone.load to gone.songFrame + (gone.end - gone.start)
            }
            val span = spans.firstOrNull()?.takeIf { it.start <= heard && heard < it.end } ?: return
            // Said as of the moment the speaker reached the span, which the loop notices a few
            // milliseconds later: the song's frame there, and when that was.
            at = nanosOf(span.start + latencyFrames + outputLatencyFrames)
            if (span.song) {
                if (!heardSong || heardLoad != span.load) {
                    heardSong = true
                    heardLoad = span.load
                    news = Triple(span.load, span.songFrame, true)
                }
            } else if (heardSong) {
                heardSong = false
                news = Triple(heardLoad, -1L, false)
            }
        }
        val (load, songFrame, song) = news ?: return
        if (song) events.heard(load, songFrame, at) else events.gap(load, songFrame, at)
    }

    /** Answers the speaker's clock questions: when each came and when the answer left, as NTP. */
    private fun answerTiming() {
        val buffer = ByteArray(128)
        while (!timing.isClosed) {
            try {
                val question = DatagramPacket(buffer, buffer.size)
                timing.receive(question)
                answer(timing, question)
            } catch (e: SocketException) {
                if (timing.isClosed) return
            } catch (e: IOException) {
                Log.d(TAG, "Timing: ${e.message}")
            }
        }
    }

    /** Resend requests on the control port: each lost packet still kept goes again, to whoever asked. */
    private fun answerControl() {
        val buffer = ByteArray(128)
        while (!control.isClosed) {
            try {
                val request = DatagramPacket(buffer, buffer.size)
                control.receive(request)
                // A speaker reached through NAT (the emulator's) can only be answered where it wrote from.
                if (AirPlayRtp.isTimingRequest(buffer, request.length)) {
                    answer(control, request)
                    continue
                }
                val (first, count) = AirPlayRtp.resendRequest(buffer, request.length) ?: continue
                resend(first, count, request.address, request.port)
            } catch (e: SocketException) {
                if (control.isClosed) return
            } catch (e: IOException) {
                Log.d(TAG, "Control: ${e.message}")
            }
        }
    }

    private fun answer(socket: DatagramSocket, question: DatagramPacket) {
        val received = clock.ntp()
        if (!AirPlayRtp.isTimingRequest(question.data, question.length)) return
        lastTimingRequestAt = clock.nanos()
        timingRequests++
        val reply = AirPlayRtp.timingReply(question.data.copyOf(question.length), received, clock.ntp())
        socket.send(DatagramPacket(reply, reply.size, question.address, question.port))
    }

    private fun resend(first: Int, count: Int, to: InetAddress, port: Int) {
        for (offset in 0 until count.coerceAtMost(BACKLOG)) {
            val wanted = (first + offset) and 0xFFFF
            val slot = wanted and (BACKLOG - 1)
            val reply = synchronized(lock) {
                if (backlogSeq[slot] != wanted) null else AirPlayRtp.resendReply(backlog[slot], backlogLength[slot])
            }
            if (reply == null) {
                resendMisses++
                continue
            }
            control.send(DatagramPacket(reply, reply.size, to, port))
            resent++
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        control.close()
        timing.close()
        audio.close()
    }

    companion object {
        private const val TAG = "AirPlayStream"

        /** How long before it is due a packet goes: a cushion for a phone that wakes late. */
        const val LEAD_NANOS = 150_000_000L
        const val SYNC_EVERY_NANOS = 1_000_000_000L

        /** Packets kept for resends: a power of two, about 12 seconds. */
        const val BACKLOG = 1024
        private const val MAX_BURST = 64
        private const val MAX_SPANS = 4096
        private const val BATCH_NANOS = 20_000_000L
        private const val MAX_SEND_FAILURES = 50
    }
}

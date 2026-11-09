/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import android.util.Log
import java.io.Closeable
import java.io.IOException
import kotlin.math.roundToInt

/**
 * What a song decodes to: its rate, its channels, how long it lasts in microseconds (-1 when nothing
 * says), and [offsetUs], how far a whole packet's time stamp runs ahead of where its first sample
 * falls in a decode from the start (PcmAnchor; 7.5 ms for YouTube's Opus, 0 for exact stamps).
 */
data class PcmSource(val rate: Int, val channels: Int, val durationUs: Long, val offsetUs: Long = 0)

/**
 * Decoded audio: [frames] frames of interleaved 16-bit samples in [samples] from index 0, at [rate]
 * Hz in [channels] channels, the first one at [timeUs] in the song. Valid until the next piece is
 * asked for: a decoder may reuse the array.
 */
class DecodedPiece(val timeUs: Long, val rate: Int, val channels: Int, val samples: ShortArray, val frames: Int)

/** A song's audio as it decodes, piece by piece, from where it was opened. */
interface DecodedAudio : Closeable {
    /**
     * The next piece, or null at the end of the song. A song that cannot be read on, the network
     * gone for one, throws an IOException: an end is never made up, or the renderer would hear the
     * rest of the song as silence.
     */
    fun next(): DecodedPiece?
}

/**
 * Decodes the songs the server sends as raw audio. The app's decodes with MediaExtractor and
 * MediaCodec (playback/MediaCodecPcmDecoder); the tests' make their samples up.
 */
interface PcmDecoder {
    /** What [song] decodes to. Blocking, and it may go to the network. */
    fun probe(song: CastSong): PcmSource

    /** [song]'s audio from [fromUs], or from a little before it; each piece says where it is. Blocking. */
    fun open(song: CastSong, fromUs: Long): DecodedAudio
}

/**
 * Where a decode's samples fall in the song, which a decoder's time stamps alone do not say.
 *
 * A decoder trims its first packet at the start of every decode, from the start of the song or
 * from a seek (Opus: its pre-skip, 312 samples), and a file's stamps can sit off the sample count:
 * YouTube's Opus files stamp the first packet 0 and every later one 1 ms after the count (0, 21,
 * 41 ms...). Placed by its own first stamp, a decode started mid-song came out 1 ms late against
 * one from the start (the Pixel 5, 4 Nov 2026): a renderer that asked twice for the same bytes got
 * two different sets. So a decode's first piece is placed by its second, a whole packet: that one
 * falls at its stamp less the song's [PcmSource.offsetUs], which a decode from the start measures
 * once ([offsetUs]), and the first piece just before it.
 */
object PcmAnchor {
    /**
     * From the first two pieces of a decode from the start of a song: how far a whole packet's stamp
     * is ahead of where its first sample falls, in microseconds; 0 when there are not two pieces.
     */
    fun offsetUs(fromStart: DecodedAudio): Long {
        val first = fromStart.next() ?: return 0
        val firstUs = first.frames * 1_000_000L / first.rate
        val second = fromStart.next() ?: return 0
        return second.timeUs - firstUs
    }
}

/** [raw] with its first piece placed by its second ([PcmAnchor]), everything else as it comes. */
class AnchoredAudio(private val raw: DecodedAudio, private val offsetUs: Long) : DecodedAudio {
    private var started = false
    private var waiting: DecodedPiece? = null

    override fun next(): DecodedPiece? {
        waiting?.let {
            waiting = null
            return it
        }
        if (started) return raw.next()
        started = true
        val piece = raw.next() ?: return null
        // A copy: the decoder may give the next piece in the same array.
        val first = DecodedPiece(piece.timeUs, piece.rate, piece.channels, piece.samples.copyOf(piece.frames * piece.channels), piece.frames)
        val second = raw.next() ?: return first
        waiting = second
        val firstUs = second.timeUs - offsetUs - first.frames * 1_000_000L / first.rate
        return DecodedPiece(firstUs, first.rate, first.channels, first.samples, first.frames)
    }

    override fun close() = raw.close()
}

/**
 * Decoded pieces in, the frames of [form] out from [startFrame] on, without a gap: channels
 * mapped to the form's, the rate changed only when the form's is not the song's.
 *
 * Where the decode starts is read from the first piece's time; the pieces after it follow on
 * from it. Frames before [startFrame] are dropped, and silence fills in for any the decode
 * started too late to give.
 */
class PcmConverter(private val form: PcmForm, startFrame: Long) {
    /** The next frame of the form this gives. */
    var nextFrame: Long = startFrame
        private set

    private var inRate = 0
    private var inChannels = 0
    private var resampler: PcmResampler? = null

    /** The input frame, in the song's own rate, that the next piece starts at. */
    private var nextInput = 0L
    private var mapped = ShortArray(0)
    private val out = ShortArray(OUT_FRAMES * form.channels)

    /** Takes [piece] and hands [emit] the frames it makes, in order: samples, the first sample's index, frames. */
    fun push(piece: DecodedPiece, emit: (ShortArray, Int, Int) -> Unit) {
        if (piece.frames <= 0) return
        if (piece.rate != inRate || piece.channels != inChannels) restart(piece, emit)
        val frames = map(piece)
        val resampler = resampler
        if (resampler != null) {
            resampler.push(mapped, 0, frames)
            drain(resampler, emit)
            return
        }
        // The same rate: input frame i is output frame i.
        if (nextInput > nextFrame) silence(nextInput - nextFrame, emit)
        val skip = (nextFrame - nextInput).coerceIn(0L, frames.toLong()).toInt()
        if (frames > skip) {
            emit(mapped, skip * form.channels, frames - skip)
            nextFrame += frames - skip
        }
        nextInput += frames
    }

    /** The decode has ended: the rest of what the resampler holds. */
    fun finish(emit: (ShortArray, Int, Int) -> Unit) {
        resampler?.let {
            it.finish()
            drain(it, emit)
        }
    }

    private fun restart(piece: DecodedPiece, emit: (ShortArray, Int, Int) -> Unit) {
        // A change of rate or channels in the middle, which an HE-AAC stream can make: what is
        // left of the old one goes out first, and the new one starts where its piece says.
        finish(emit)
        inRate = piece.rate
        inChannels = piece.channels
        nextInput = Math.floorDiv(piece.timeUs * piece.rate + 500_000L, 1_000_000L)
        Log.i(TAG, "Decoded audio starts at ${piece.timeUs} us (input frame $nextInput, ${piece.rate} Hz); serving from frame $nextFrame at ${form.rate} Hz")
        resampler = if (piece.rate == form.rate) {
            null
        } else {
            PcmResampler(piece.rate, form.rate, form.channels).also { it.start(nextInput, nextFrame) }
        }
    }

    private fun drain(resampler: PcmResampler, emit: (ShortArray, Int, Int) -> Unit) {
        while (true) {
            val count = resampler.pull(out, 0, OUT_FRAMES)
            if (count == 0) return
            emit(out, 0, count)
            nextFrame += count
        }
    }

    private fun silence(frames: Long, emit: (ShortArray, Int, Int) -> Unit) {
        out.fill(0)
        var left = frames
        while (left > 0) {
            val count = minOf(left, OUT_FRAMES.toLong()).toInt()
            emit(out, 0, count)
            nextFrame += count
            left -= count
        }
    }

    /**
     * The piece in the form's channels, into [mapped]: one channel copied to both, two averaged
     * into one, and more than two (5.1) folded into two with the centre and the surrounds at -3 dB.
     */
    private fun map(piece: DecodedPiece): Int {
        val frames = piece.frames
        val from = piece.channels
        val to = form.channels
        if (mapped.size < frames * to) mapped = ShortArray(frames * to)
        val source = piece.samples
        when {
            from == to -> System.arraycopy(source, 0, mapped, 0, frames * to)
            from == 1 -> for (frame in 0 until frames) {
                val sample = source[frame]
                mapped[frame * 2] = sample
                mapped[frame * 2 + 1] = sample
            }
            to == 1 -> for (frame in 0 until frames) {
                val at = frame * from
                mapped[frame] = Math.floorDiv(source[at] + source[at + 1] + 1, 2).toShort()
            }
            else -> for (frame in 0 until frames) {
                val at = frame * from
                val centre = if (from >= 3) source[at + 2] * FOLD else 0f
                val left = source[at] + centre + (if (from >= 5) source[at + 4] * FOLD else 0f)
                val right = source[at + 1] + centre + (if (from >= 6) source[at + 5] * FOLD else 0f)
                mapped[frame * 2] = clamp(left)
                mapped[frame * 2 + 1] = clamp(right)
            }
        }
        return frames
    }

    private fun clamp(value: Float): Short =
        value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private companion object {
        const val TAG = "PcmConverter"
        const val OUT_FRAMES = 4096
        const val FOLD = 0.70710677f
    }
}

/**
 * Decoders kept for a moment after the range they sent ended, for a renderer that fetches a song
 * in pieces: its next request, which starts exactly where the last one stopped, carries on with
 * the same decode, so the join is seamless. A new decode would start from a seek, which with Opus
 * can land a few milliseconds off and click.
 *
 * Shared by all of one server's songs: [capacity] decoders at most, each for [idleMs] at most,
 * the oldest let go first; [shut] lets go of all of them and keeps none after.
 */
class PcmParking(
    private val capacity: Int = 2,
    private val idleMs: Long = 30_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** Something kept that holds a decoder. */
    fun interface Parked {
        fun release()
    }

    private class Spot(val owner: Any, val position: Long, val parked: Parked, val at: Long)

    private val spots = ArrayList<Spot>()
    private var shut = false

    /** How many are kept now. */
    val size: Int get() = synchronized(spots) { spots.size }

    /** Keeps [parked], which carries on [owner]'s bytes from [position]. */
    fun park(owner: Any, position: Long, parked: Parked) {
        val gone = ArrayList<Parked>()
        synchronized(spots) {
            if (shut) {
                gone += parked
            } else {
                expire(gone)
                spots.removeAll { spot -> (spot.owner === owner && spot.position == position).also { if (it) gone += spot.parked } }
                spots += Spot(owner, position, parked, now())
                while (spots.size > capacity) gone += spots.removeAt(0).parked
            }
        }
        gone.forEach { runCatching { it.release() } }
    }

    /** What carries on [owner]'s bytes from exactly [position], taken out, or null. */
    fun take(owner: Any, position: Long): Parked? {
        val gone = ArrayList<Parked>()
        val found = synchronized(spots) {
            expire(gone)
            val index = spots.indexOfFirst { it.owner === owner && it.position == position }
            if (index >= 0) spots.removeAt(index).parked else null
        }
        gone.forEach { runCatching { it.release() } }
        return found
    }

    /** Lets go of everything kept for [owner]. */
    fun forget(owner: Any) {
        val gone = ArrayList<Parked>()
        synchronized(spots) { spots.removeAll { spot -> (spot.owner === owner).also { if (it) gone += spot.parked } } }
        gone.forEach { runCatching { it.release() } }
    }

    /** Lets go of everything, and of anything parked from now on. */
    fun shut() {
        val gone: List<Parked>
        synchronized(spots) {
            shut = true
            gone = spots.map { it.parked }
            spots.clear()
        }
        gone.forEach { runCatching { it.release() } }
    }

    private fun expire(gone: MutableList<Parked>) {
        val limit = now() - idleMs
        spots.removeAll { spot -> (spot.at < limit).also { if (it) gone += spot.parked } }
    }
}

/**
 * A song served as raw audio in [form], decoded from the song as it is ([asIs]: the cached copy,
 * the download, the file or the stream) by [decoder], for a renderer that plays nothing else.
 *
 * Its length comes from the song's duration ([source]): frames = duration x rate, rounded, so
 * a byte is a time and a range request starts decoding from that time, 200 ms early so the
 * decoder has settled, with samples dropped to the exact frame, and to the exact byte when a
 * renderer asks from the middle of a frame. Where the decode's samples fall is set by PcmAnchor,
 * so the same bytes come back for the same place whichever request reached it. A decode that ends short is made up to the length
 * with silence, one that runs over is cut, and one that fails is an error, so the renderer asks
 * again rather than playing silence.
 *
 * Decoding happens in [CastReader.read], on the server's thread for that connection, so the
 * renderer's reading paces it: a renderer that stops reading stops the decoder, and closing the
 * connection, which the end of the cast does, releases it.
 *
 * The app's sound ([sound]: the equalizer, the song's levelling) goes on the samples as they are
 * made, one stage per decode, so a request that carries on a parked decode carries on its filters
 * too. A band moved is in the next bytes made; the renderer hears them once it has played what it
 * holds.
 */
class PcmCastSong(
    private val asIs: CastSong,
    val form: PcmForm,
    val source: PcmSource,
    private val decoder: PcmDecoder,
    private val parking: PcmParking? = null,
    /** The song's id in the queue, which its level is looked up by. */
    private val mediaId: String = "",
    /** The app's sound, or null to serve the samples as they were decoded. */
    private val sound: PcmSound? = null,
) : CastSong, Closeable {
    /** Frames served, or -1 when the duration is not known. */
    val frames: Long = PcmFormats.frames(source.durationUs, form.rate)

    override val mimeType: String get() = form.contentType
    override val length: Long = form.length(frames)
    override val converted: Boolean get() = true

    /** How long what is served lasts, by its frames: what a renderer works out its byte seeks from. */
    val durationMs: Long = if (frames < 0) -1 else frames * 1000 / form.rate

    override fun openAt(position: Long): CastReader {
        require(position >= 0) { "Negative position" }
        return parking?.take(this, position) as? Reader ?: Reader(position)
    }

    /** Lets go of the decoders kept for this song. */
    override fun close() {
        parking?.forget(this)
    }

    private inner class Reader(private var position: Long) : CastReader, PcmParking.Parked {
        private val header: ByteArray? = if (position < form.headerBytes) form.wavHeader(frames) else null
        private val startFrame = form.frameAt(position)

        /** Bytes of the first frame to drop, when the read starts in the middle of one. */
        private var drop = form.offsetInFrame(position)
        private var audio: DecodedAudio? = null
        private var converter: PcmConverter? = null
        private var decodeEnded = false
        private var failed = false
        private var released = false
        private var bytes = ByteArray(0)
        private var bytesStart = 0
        private var bytesEnd = 0

        /** The app's sound for this decode, and the samples it gives back, kept from one piece to the next. */
        private val stage: PcmStage? = sound?.stage(form.rate, form.channels)
        private var shaped = ShortArray(0)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (released) throw IOException("Read after it was let go")
            val end = this@PcmCastSong.length
            if (end >= 0 && position >= end) return -1
            val wanted = if (end >= 0) minOf(length.toLong(), end - position).toInt() else length
            if (header != null && position < form.headerBytes) {
                val count = minOf(wanted, (form.headerBytes - position).toInt())
                System.arraycopy(header, position.toInt(), buffer, offset, count)
                position += count
                return count
            }
            if (bytesEnd == bytesStart) {
                val more = try {
                    fill()
                } catch (e: IOException) {
                    failed = true
                    throw e
                } catch (e: RuntimeException) {
                    failed = true
                    throw IOException("The song could not be decoded: ${e.message}", e)
                }
                if (!more) {
                    if (end < 0) return -1
                    // The decode gave a little less than the duration said: silence to the length.
                    buffer.fill(0, offset, offset + wanted)
                    position += wanted
                    return wanted
                }
            }
            val count = minOf(wanted, bytesEnd - bytesStart)
            System.arraycopy(bytes, bytesStart, buffer, offset, count)
            bytesStart += count
            position += count
            return count
        }

        /** Decodes until some bytes are ready; false once the song has given all it has. */
        private fun fill(): Boolean {
            if (decodeEnded) return false
            val converter = converter ?: PcmConverter(form, startFrame).also { converter = it }
            val audio = audio ?: AnchoredAudio(decoder.open(asIs, (form.timeUs(startFrame) - PREROLL_US).coerceAtLeast(0)), source.offsetUs)
                .also { audio = it }
            bytesStart = 0
            bytesEnd = 0
            while (bytesEnd == bytesStart) {
                val piece = audio.next()
                if (piece == null) {
                    converter.finish(::append)
                    decodeEnded = true
                    return bytesEnd > bytesStart
                }
                converter.push(piece, ::append)
            }
            return true
        }

        /** [frames] frames of [samples] from index [from], as bytes in the form's order, after those waiting. */
        private fun append(samples: ShortArray, from: Int, frames: Int) {
            val count = frames * form.channels
            var source = samples
            var first = from
            if (stage != null) {
                // Into an array of its own: the converter hands the same silence over more than once.
                if (shaped.size < count) shaped = ShortArray(count)
                stage.shape(mediaId, samples, from, shaped, 0, frames)
                source = shaped
                first = 0
            }
            if (bytes.size < bytesEnd + count * 2) bytes = bytes.copyOf(maxOf(bytesEnd + count * 2, bytes.size * 2))
            var at = bytesEnd
            if (form.bigEndian) {
                for (index in first until first + count) {
                    val sample = source[index].toInt()
                    bytes[at++] = (sample shr 8).toByte()
                    bytes[at++] = sample.toByte()
                }
            } else {
                for (index in first until first + count) {
                    val sample = source[index].toInt()
                    bytes[at++] = sample.toByte()
                    bytes[at++] = (sample shr 8).toByte()
                }
            }
            bytesEnd = at
            if (drop > 0) {
                val skip = minOf(drop, bytesEnd - bytesStart)
                bytesStart += skip
                drop -= skip
            }
        }

        /**
         * The connection is done with it. A decode that is under way and sound is kept a moment for
         * a request that carries on from here; anything else is let go.
         */
        override fun close() {
            val end = this@PcmCastSong.length
            val worthKeeping = parking != null && audio != null && !failed && !decodeEnded && (end < 0 || position < end)
            if (worthKeeping) parking!!.park(this@PcmCastSong, position, this) else release()
        }

        override fun release() {
            released = true
            val audio = audio ?: return
            this.audio = null
            runCatching { audio.close() }
        }
    }

    companion object {
        /** How far before the asked time the decode starts, for the decoder to settle (Opus wants 80 ms). */
        const val PREROLL_US = 200_000L
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.downloadManager

import com.dd3boh.outertune.cast.CastReader
import com.dd3boh.outertune.cast.CastSong
import com.dd3boh.outertune.cast.PcmDecoder
import com.dd3boh.outertune.constants.DownloadBitrate
import com.dd3boh.outertune.constants.DownloadFormat
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * A song converted to another format (Unreleased.DOWNLOAD_FORMATS): a complete temporary file, to be
 * copied into the folder and then deleted ([close]). The folder is not touched until it is whole.
 */
internal class Converted(
    val file: File,
    val extension: String,
    /** The bitrate it was encoded at, or null for FLAC and WAV. */
    val kbps: Int?,
    /** How much audio it holds, in milliseconds. */
    val audioMs: Long,
) : Closeable {
    fun copyTo(out: OutputStream) {
        file.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    override fun close() {
        file.delete()
    }
}

/**
 * Decodes a song and encodes it in another format (Unreleased.DOWNLOAD_FORMATS): the song is read
 * through MediaExtractor and MediaCodec (the decoder the cast server's raw audio uses), a piece at
 * a time, and each piece goes straight to the encoder of the format, so that no more than a piece is
 * ever in memory. The result is a temporary file under [workDir], and only a whole one is handed out:
 * a conversion that fails or is stopped deletes its file and throws.
 *
 * Refused, and thrown as an IOException with the reason: audio that is neither mono nor stereo, one
 * that changes its rate halfway, one that holds nothing, and one that decodes to less than the song
 * is long (a decoder that stopped early must not leave a short song under the song's name). One
 * conversion at a time in the whole app, since each one takes a core for as long as the song lasts.
 */
internal class SongConverter(
    private val workDir: File,
    private val decoder: PcmDecoder,
    /** Keeps the app up for as long as a song is converted, so that Android does not stop it halfway: see [ConvertHold]. */
    private val hold: ConvertHold? = null,
    private val log: (String) -> Unit = {},
) {
    /**
     * [song] encoded as [plan] with [tags] in it. [expectedUs] is how long the song is when that is
     * known (0 when it is not). [cancelled] is asked between pieces. Blocking: never on the main thread.
     */
    fun convert(
        song: CastSong,
        tags: SongTags,
        plan: ConvertPlan.Encode,
        expectedUs: Long,
        cancelled: () -> Boolean = { false },
    ): Converted = synchronized(ONE_AT_A_TIME) {
        require(plan.format.encoder != DownloadFormat.Encoder.NONE) { "Original is not converted" }
        hold?.begin(ConvertNotice.label(plan.format))
        try {
            encode(song, tags, plan, expectedUs, cancelled)
        } finally {
            hold?.end()
        }
    }

    private fun encode(
        song: CastSong,
        tags: SongTags,
        plan: ConvertPlan.Encode,
        expectedUs: Long,
        cancelled: () -> Boolean,
    ): Converted {
        if (!workDir.isDirectory && !workDir.mkdirs()) throw IOException("no place to convert in: $workDir")
        val target = File.createTempFile("convert-", PART, workDir)
        // The clock that goes on while the phone sleeps and while Android holds the app's work back: a conversion
        // that waited says so in its time.
        val began = android.os.SystemClock.elapsedRealtimeNanos()
        var encoder: FileEncoder? = null
        // A conversion takes a core for as long as the song lasts: never at the expense of what plays. Lowered
        // here, where nothing suspends, so the thread that encodes is the one lowered and the one put back.
        val priority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
            var frames = 0L
            var rate = 0
            var channels = 0
            // The time between two pieces is milliseconds. Seconds of it is the work standing still: Android holds
            // it back while the person is in another app, and a phone asleep does nothing. Added up, for the log.
            var stoodNs = 0L
            var lastPiece = began
            decoder.open(song, 0).use { audio ->
                while (true) {
                    if (cancelled()) throw CancellationException("the conversion was stopped")
                    val piece = audio.next() ?: break
                    val now = android.os.SystemClock.elapsedRealtimeNanos()
                    stoodNs += stoodStill(now - lastPiece)
                    lastPiece = now
                    if (piece.frames <= 0) continue
                    val current = encoder
                    if (current == null) {
                        rate = piece.rate
                        channels = piece.channels
                        encoder = encoderFor(plan, target, tags).also { it.start(rate, channels) }
                    } else if (piece.rate != rate || piece.channels != channels) {
                        throw IOException("the song changes from $rate Hz, $channels channels to ${piece.rate} Hz, ${piece.channels} channels")
                    }
                    encoder!!.add(piece.samples, piece.frames)
                    frames += piece.frames
                }
            }
            val made = encoder ?: throw IOException("the song holds no audio to convert")
            val decodedUs = frames * 1_000_000L / rate
            if (expectedUs > 0 && decodedUs < expectedUs - maxOf(MIN_SLACK_US, expectedUs / 50)) {
                throw IOException("the decoder gave ${decodedUs / 1000} ms of a song ${expectedUs / 1000} ms long")
            }
            made.finish()
            val kbps = (made as? Mp3FileEncoder)?.effectiveKbps ?: plan.kbps
            val seconds = (android.os.SystemClock.elapsedRealtimeNanos() - began) / 1e9
            log(
                "Converted to ${plan.format}${kbps?.let { " at $it kbps" } ?: ""}: ${decodedUs / 1000} ms of audio at $rate Hz, " +
                    "$channels channels, ${target.length()} bytes, in ${String.format(Locale.ROOT, "%.1f", seconds)} s" + stoodLine(stoodNs),
            )
            return Converted(target, plan.format.extension!!, kbps, decodedUs / 1000)
        } catch (e: Throwable) {
            runCatching { encoder?.close() }
            target.delete()
            File(target.path + ".frames").delete()
            throw e
        } finally {
            runCatching { encoder?.close() }
            android.os.Process.setThreadPriority(priority)
        }
    }

    private fun encoderFor(plan: ConvertPlan.Encode, target: File, tags: SongTags): FileEncoder = when (plan.format) {
        DownloadFormat.MP3 -> Mp3FileEncoder(target, tags, plan.kbps ?: throw IllegalArgumentException("an MP3 needs a bitrate"))
        DownloadFormat.M4A -> AacFileEncoder(target, tags, plan.kbps ?: throw IllegalArgumentException("an AAC needs a bitrate"))
        DownloadFormat.FLAC -> FlacFileEncoder(target, tags)
        DownloadFormat.WAV -> WavFileEncoder(target, tags)
        DownloadFormat.ORIGINAL -> throw IllegalArgumentException("Original is not converted")
    }

    /** Leftovers of conversions a killed app never finished: files of the work folder older than [olderThanMs]. */
    fun sweep(olderThanMs: Long = STALE_MS) {
        val now = System.currentTimeMillis()
        workDir.listFiles()?.filter { it.name.startsWith("convert-") && now - it.lastModified() > olderThanMs }?.forEach { it.delete() }
    }

    companion object {
        const val PART = ".part"

        /** A decode may end up to this much (or 2%) short of what the container says without being taken for a cut. */
        const val MIN_SLACK_US = 1_500_000L

        /** Six hours: no conversion lasts so long, and one that is running keeps touching its file. */
        const val STALE_MS = 6L * 60 * 60 * 1000

        /** A wait for the next piece of a song longer than this is not the decoder's: the work stood still. */
        const val STOOD_STILL_NS = 3_000_000_000L

        /** What a wait of [gapNs] between two pieces counts for as standing still: all of it past [STOOD_STILL_NS], else nothing. */
        fun stoodStill(gapNs: Long): Long = if (gapNs > STOOD_STILL_NS) gapNs else 0L

        /** The end of the log's line for a conversion that stood still for [stoodNs] in all, or nothing when it never did. */
        fun stoodLine(stoodNs: Long): String =
            if (stoodNs <= 0L) "" else ", ${String.format(Locale.ROOT, "%.0f", stoodNs / 1e9)} s of them standing still"

        private val ONE_AT_A_TIME = Any()
    }
}

/** The bytes media3 cached, read as a song the decoder can open: any position, nothing held in memory. */
internal class CachedBytesSong(private val bytes: CachedBytes, override val mimeType: String) : CastSong {
    override val length: Long get() = bytes.length

    override fun openAt(position: Long): CastReader {
        val reader = bytes.open(position)
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = reader.read(buffer, offset, length)
            override fun close() = reader.close()
        }
    }
}

/**
 * What a download is converted to, given to SongFiles.save: [convert] says what to make of the
 * stream it has just read, or null to save it as Original (DownloadFormats.plan: Original chosen, or
 * an AAC for M4A). Failures are thrown; SongFiles logs them and saves the stream as Original.
 */
internal class SongConversion(
    private val format: DownloadFormat,
    private val bitrate: DownloadBitrate,
    private val converter: SongConverter,
    private val cancelled: () -> Boolean = { false },
    /** Called when a song is about to be encoded, and not for one that is saved as Original: what the notice says "Converting" for. */
    private val onEncode: () -> Unit = {},
) : SongFiles.Converter {
    /** Why the conversion failed, when it did and the song was saved as Original instead; null otherwise. */
    @Volatile
    var failure: Exception? = null
        private set

    override fun convert(source: CachedBytes, stream: SongFiles.Stream, tags: SongTags): Converted? {
        val plan = DownloadFormats.plan(format, bitrate, stream.codec, stream.bitsPerSecond)
        if (plan !is ConvertPlan.Encode) return null
        onEncode()
        return converter.convert(CachedBytesSong(source, stream.mimeType), tags, plan, stream.durationUs, cancelled)
    }

    override fun failed(e: Exception) {
        failure = e
    }
}

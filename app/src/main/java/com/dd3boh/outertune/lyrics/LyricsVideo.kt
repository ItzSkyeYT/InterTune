/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dd3boh.outertune.playback.LevelAnalyser
import com.dd3boh.outertune.playback.MusicLevels
import com.dd3boh.outertune.ui.component.LyricsVideoFrames
import com.dd3boh.outertune.utils.SharedFileProvider
import com.dd3boh.outertune.utils.shareSheet
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Makes a lyrics video ([LyricsVideoPlan]) on the phone, with Media3's Transformer, in three steps:
 *
 * 1. The song's stretch is cut out of it, through the player's own way to the song's bytes (its
 *    downloads, its cache, or the stream), faded in and out, and measured as it goes for Living
 *    blur, a little from before the stretch too, so that the picture knows where the song is.
 * 2. The frames are drawn one after another ([LyricsVideoFrames]) over a black picture as long as
 *    the stretch, and encoded.
 * 3. The two are put together in one file as they are, without being encoded again.
 *
 * The second waits for the first because the picture moves with what the first measured.
 */
object LyricsVideo {
    private const val TAG = "LyricsVideo"


    /**
     * The levels are measured from this long before the stretch, so that Living blur starts out
     * knowing the song's shape, as it does in the player, which has been listening since it was
     * opened: how loud the song gets is a matter of most of a minute, a build-up of several seconds.
     */
    private const val HEARD_BEFORE_MS = 30_000L

    /** How much of the whole each step is, for the progress shown. */
    private const val SOUND_SHARE = 0.12f
    private const val JOIN_SHARE = 0.03f

    /**
     * The levels measured from the song, one set of [MusicLevels.VALUES] every
     * [MusicLevels.FRAME_SECONDS], from [beforeMs] before the video's start: the stretch's sound
     * and what was heard of the song before it.
     */
    class Heard(private val sets: List<FloatArray>, val beforeMs: Long = 0) {
        /**
         * What is heard at [ms] into the video, before its start too, as far back as it was
         * measured: null before that, and the last set measured after the end.
         */
        fun at(ms: Long): FloatArray? {
            if (sets.isEmpty()) return null
            val index = Math.floorDiv(ms + beforeMs, (MusicLevels.FRAME_SECONDS * 1000).roundToLong())
            return if (index < 0) null else sets[min(index, sets.lastIndex.toLong()).toInt()]
        }
    }

    /** The three steps of making a video, in order, for saying which one a failure stopped in. */
    enum class Step { SOUND, PICTURE, JOIN }

    /**
     * A video that could not be made: the [step] it stopped in and what that step [said], in words
     * a log and a report can carry (see [said]). The step's own exception is the cause.
     */
    class Failed(val step: Step, val said: String, cause: Throwable) : Exception("The ${step.name.lowercase()} step failed: $said", cause)

    /**
     * What a step's failure says: Transformer's error code and the codec it named (its name tells
     * a phone's hardware encoder from the software one), or the exception's own class and message.
     * Past reports of this video failing said nothing at all (a warning under tags the report left
     * out, 10 Nov 2026), so the one line that says it all is written at the error level.
     */
    internal fun said(failure: Throwable): String = when (failure) {
        is ExportException -> buildString {
            append(failure.errorCodeName)
            failure.codecInfo?.let { append(", ").append(if (it.isVideo) "video " else "audio ").append(if (it.isDecoder) "decoder " else "encoder ").append(it.name ?: "unnamed") }
        }
        else -> failure.javaClass.simpleName + (failure.message?.let { ": $it" } ?: "")
    }

    /** Runs a step, and names it if it fails. A cancelled step is not a failure. */
    private inline fun <T> inStep(step: Step, run: () -> T): T = try {
        run()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (named: Failed) {
        throw named
    } catch (failure: Throwable) {
        throw Failed(step, said(failure), failure)
    }

    /**
     * How the picture is encoded, in the order tried: as the phone offers it (its hardware encoder
     * where it has one) and, should that have stopped in the encoder or in what it handed over,
     * once more with Android's own software encoder. The hardware encoder is the one part of the
     * video that differs from phone to phone, so it is where a video failing for every song on one
     * phone would be (#general, 10 Nov 2026: a Xiaomi on Android 15, where the emulator's is fine).
     */
    internal enum class Encoding { HARDWARE, SOFTWARE }

    /** Whether a picture that failed is worth another try in software: not when the trouble is a file that could not be read or written. */
    internal fun another(failure: Throwable): Boolean =
        failure is ExportException && failure.errorCode !in ExportException.ERROR_CODE_IO_UNSPECIFIED..ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

    /** Whether a codec is Android's own, in software, and not the phone maker's. */
    internal fun softwareCodec(name: String) = name.startsWith("c2.android.") || name.startsWith("OMX.google.")

    private fun MediaCodecInfo.inSoftware() = softwareCodec(name) || (Build.VERSION.SDK_INT >= 29 && isSoftwareOnly)

    /** Only the phone's software encoders: what the second try of the picture is held to. */
    private val SOFTWARE_ENCODERS = EncoderSelector { mime -> ImmutableList.copyOf(EncoderUtil.getSupportedEncoders(mime).filter { it.inSoftware() }) }

    /** What the deepest cause of a failure says, for the line that names the failure: ": CodecException: Error 0xfffffc0e". */
    private fun root(failure: Throwable): String {
        var cause = failure
        for (depth in 0 until 10) cause = cause.cause ?: break
        return if (cause === failure) "" else ": ${said(cause)}"
    }

    /**
     * The video of [clip] from the song [mediaId], read through [source], with frames from
     * [frames] once the sound is measured. Saved in the cache, named after [title]. [progress] is
     * told how far it has got, 0 to 1, on the main thread.
     */
    suspend fun make(
        context: Context,
        source: DataSource.Factory,
        mediaId: String,
        clip: LyricsVideoPlan.Clip,
        title: String,
        frames: (Heard) -> LyricsVideoFrames,
        progress: (Float) -> Unit,
    ): File {
        val folder = withContext(Dispatchers.IO) {
            val all = File(context.cacheDir, FOLDER)
            all.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEPT_MS }?.forEach { it.deleteRecursively() }
            File(all, System.currentTimeMillis().toString()).apply { mkdirs() }
        }
        // The export outlives no screen, but it need not hold one either.
        val app = context.applicationContext
        // Forward only: a step's own count can start again below where the last one left the bar.
        var shown = 0f
        val forward = { at: Float -> if (at > shown) { shown = at; progress(at) } }
        try {
            val sound = File(folder, "sound.m4a")
            Log.i(TAG, "Making a %.1f s video of $mediaId: H.264 encoders ${encoders()}".format(clip.durationMs / 1000f))
            val heard = inStep(Step.SOUND) { cutSound(app, source, mediaId, clip, sound) { forward(it * SOUND_SHARE) } }
            val picture = File(folder, "picture.mp4")
            val painter = withContext(Dispatchers.Default) { frames(heard) }
            inStep(Step.PICTURE) { pictureOf(app, painter, clip, folder, picture) { forward(SOUND_SHARE + it * (1f - SOUND_SHARE - JOIN_SHARE)) } }
            val video = File(folder, LyricsShare.fileName(title, "mp4"))
            withContext(Dispatchers.IO) {
                inStep(Step.JOIN) { join(picture, sound, video) }
                sound.delete()
                picture.delete()
                File(folder, BLACK).delete()
                // its first frame beside it, at half the video's size, for the share sheet to
                // show (SharedFileProvider)
                runCatching {
                    val first = createBitmap(LyricsVideoPlan.OUT_WIDTH / 2, LyricsVideoPlan.OUT_HEIGHT / 2)
                    val canvas = Canvas(first).apply { scale(first.width / LyricsVideoFrames.WIDTH.toFloat(), first.height / LyricsVideoFrames.HEIGHT.toFloat()) }
                    painter.draw(canvas, 0)
                    File(folder, video.name + SharedFileProvider.STILL_SUFFIX).outputStream().use { first.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }.onFailure { Log.w(TAG, "No picture of the video's first frame", it) }
            }
            progress(1f)
            return video
        } catch (failure: Throwable) {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { folder.deleteRecursively() }
            // Once, here, with the whole trace and its causes: what Report a problem shows of it.
            if (failure !is CancellationException) Log.e(TAG, "The lyrics video could not be made: ${(failure as? Failed)?.let { "${it.step.name.lowercase()}, ${it.said}" } ?: said(failure)}", failure)
            throw failure
        }
    }

    /** Opens the share sheet with [video], from where the app's FileProvider hands it out. False when nothing could take it. */
    fun share(context: Context, video: File, title: String): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", video)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypes.VIDEO_MP4
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(context.shareSheet(send))
    }.onFailure { Log.e(TAG, "Could not share the lyrics video", it) }.isSuccess

    // 1. The sound

    private suspend fun cutSound(
        context: Context,
        source: DataSource.Factory,
        mediaId: String,
        clip: LyricsVideoPlan.Clip,
        into: File,
        progress: (Float) -> Unit,
    ): Heard {
        val heardFrom = max(0L, clip.startMs - HEARD_BEFORE_MS)
        // As the player asks for the song: its id is where it is, which the player's resolver
        // turns into a download, the cache or the stream.
        val song = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(mediaId)
            .setCustomCacheKey(mediaId)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(heardFrom)
                    .setEndPositionMs(clip.endMs)
                    .build()
            )
            .build()
        val cut = SoundCut(skipMs = clip.startMs - heardFrom, durationMs = clip.durationMs)
        val item = EditedMediaItem.Builder(song)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf<AudioProcessor>(cut), emptyList()))
            .build()
        export(context, item, into, progress) {
            setAssetLoaderFactory(
                DefaultAssetLoaderFactory(
                    context,
                    DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(),
                    Clock.DEFAULT,
                    DefaultMediaSourceFactory(source),
                    DataSourceBitmapLoader(context),
                )
            )
            setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(LyricsVideoPlan.SOUND_BITRATE).build())
                    .build()
            )
            setAudioMimeType(MimeTypes.AUDIO_AAC)
        }
        val sets = cut.heard()
        // What Living blur moves with. With nothing heard it drifts on its own, and every video
        // did until 2 Nov 2026: the levels were thrown away at Transformer's last flush.
        Log.i(TAG, "Heard ${sets.size} sets of levels (%.1f s, %.1f s of them before the stretch), the bass at most %.2f".format(
            sets.size * MusicLevels.FRAME_SECONDS, (clip.startMs - heardFrom) / 1000f, sets.maxOfOrNull { it[MusicLevels.BASS] } ?: 0f,
        ))
        return Heard(sets, beforeMs = clip.startMs - heardFrom)
    }

    /**
     * Passes on the sound from the stretch's start, faded in and out ([LyricsVideoPlan.sound]),
     * after the [skipMs] of song before it that are only measured. Measures all of it for Living
     * blur ([LevelAnalyser], as the player's own tap does).
     */
    internal class SoundCut(private val skipMs: Long, private val durationMs: Long) : BaseAudioProcessor() {
        private val sets = ArrayList<FloatArray>()
        private var analyser: LevelAnalyser? = null
        private var seen = 0L
        private var skip = 0L

        /**
         * The levels as they were when the sound ended. Transformer flushes its processors once
         * more after that, on its way out, and a flush starts the measuring over.
         */
        private var ended: List<FloatArray>? = null

        /** The levels of all that was heard, from the first sample. Asked once the export is over. */
        fun heard(): List<FloatArray> = synchronized(sets) { ended ?: sets.toList() }

        override fun onQueueEndOfStream() {
            synchronized(sets) { ended = sets.toList() }
        }

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            return inputAudioFormat
        }

        override fun onFlush() {
            val rate = inputAudioFormat.sampleRate
            synchronized(sets) {
                // what was heard before a flush is kept, should the end of the sound not have said so
                if (seen > 0 && ended == null) ended = sets.toList()
                sets.clear()
            }
            seen = 0L
            skip = skipMs * rate / 1000
            analyser = LevelAnalyser(rate) { _, levels -> synchronized(sets) { sets += levels.copyOf() } }
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val format = inputAudioFormat
            val channels = format.channelCount
            val frame = format.bytesPerFrame
            val frames = inputBuffer.remaining() / frame
            if (frames == 0) return
            val sixteen = format.encoding == C.ENCODING_PCM_16BIT
            analyser?.let { if (sixteen) it.pcm16(inputBuffer, channels) else it.pcmFloat(inputBuffer, channels) }

            val dropped = min(frames.toLong(), max(0L, skip - seen)).toInt()
            val kept = frames - dropped
            val output = replaceOutputBuffer(kept * frame)
            var at = inputBuffer.position() + dropped * frame
            var n = seen + dropped - skip
            val rate = format.sampleRate.toDouble()
            repeat(kept) {
                val gain = LyricsVideoPlan.sound(n * 1000.0 / rate, durationMs)
                repeat(channels) {
                    if (sixteen) {
                        output.putShort((inputBuffer.getShort(at) * gain).roundToInt().coerceIn(-32768, 32767).toShort())
                        at += 2
                    } else {
                        output.putFloat(inputBuffer.getFloat(at) * gain)
                        at += 4
                    }
                }
                n++
            }
            seen += frames
            inputBuffer.position(inputBuffer.limit())
            output.flip()
        }

        override fun onReset() {
            analyser = null
        }
    }

    // 2. The picture

    /**
     * The picture, as the phone offers to make it and, if the encoder or what it handed over was
     * what failed ([another]), once more with its software encoder. A failure of both says both.
     */
    private suspend fun pictureOf(
        context: Context,
        painter: LyricsVideoFrames,
        clip: LyricsVideoPlan.Clip,
        folder: File,
        into: File,
        progress: (Float) -> Unit,
    ) {
        try {
            drawPicture(context, painter, clip, folder, into, Encoding.HARDWARE, progress)
        } catch (first: Throwable) {
            if (first is CancellationException || !another(first)) throw first
            Log.w(TAG, "The picture's first try failed (${said(first)}${root(first)}): once more with the software encoder")
            withContext(Dispatchers.IO) { into.delete() }
            try {
                drawPicture(context, painter, clip, folder, into, Encoding.SOFTWARE, progress)
                Log.i(TAG, "The picture was made by the software encoder, after the first try failed")
            } catch (second: Throwable) {
                if (second is CancellationException) throw second
                second.addSuppressed(first)
                throw Failed(Step.PICTURE, "${said(first)}; second try, in software: ${said(second)}", second)
            }
        }
    }

    private suspend fun drawPicture(
        context: Context,
        painter: LyricsVideoFrames,
        clip: LyricsVideoPlan.Clip,
        folder: File,
        into: File,
        encoding: Encoding,
        progress: (Float) -> Unit,
    ) {
        // What the frames are drawn over, which they cover: a black picture as long as the video.
        val black = withContext(Dispatchers.IO) {
            File(folder, BLACK).also { file ->
                val bitmap = createBitmap(16, 16).apply { eraseColor(android.graphics.Color.BLACK) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val frames = Frames(painter)
        val image = MediaItem.Builder()
            .setUri(Uri.fromFile(black))
            .setMimeType(MimeTypes.IMAGE_PNG)
            .setImageDurationMs(clip.durationMs)
            .build()
        val item = EditedMediaItem.Builder(image)
            .setFrameRate(LyricsVideoPlan.FRAMES_A_SECOND)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(LyricsVideoPlan.OUT_WIDTH, LyricsVideoPlan.OUT_HEIGHT, Presentation.LAYOUT_STRETCH_TO_FIT),
                        OverlayEffect(listOf(frames)),
                    )
                )
            )
            .build()
        val started = System.nanoTime()
        // How far the picture is, by the frames drawn: drawing is the slow part, and Transformer's
        // own count, which follows the still picture fed in, ran far ahead of it and then waited.
        val durationUs = clip.durationMs * 1000f
        export(context, item, into, progress, measure = { frames.lastUs.coerceAtLeast(0L) / durationUs }) {
            setVideoMimeType(MimeTypes.VIDEO_H264)
            setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(LyricsVideoPlan.PICTURE_BITRATE)
                            // Held to it, so the size is known before it is made: left to vary, the
                            // emulator's encoder took 2 Mbit/s for 1.2 asked. Only where an encoder
                            // can hold a rate: asked of a phone with none, Transformer found no
                            // encoder at all and no video was made (the Android 7 emulator, 2 Nov 2026).
                            .apply { if (holdsConstantRate(encoding)) setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }
                            .build()
                    )
                    .apply { if (encoding == Encoding.SOFTWARE) setVideoEncoderSelector(SOFTWARE_ENCODERS) }
                    .build()
            )
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val each = max(1, frames.drawn) * 1e6
        Log.d(TAG, "${frames.drawn} frames in %.1f s, %.1f ms a frame of it drawing (%.1f ms of processor)".format(seconds, frames.drawingNanos / each, frames.drawingCpuNanos / each))
    }

    /**
     * The video's frames, as the overlay that covers the black picture: one bitmap the video's
     * size, drawn again for each frame at the card's size scaled down to it, so that lines and
     * edges are drawn sharp at the size they are seen. The overlay copies it on to be encoded
     * whenever its drawing has changed, and before it asks for the next frame.
     */
    private class Frames(private val painter: LyricsVideoFrames) : BitmapOverlay() {
        private val bitmap = createBitmap(LyricsVideoPlan.OUT_WIDTH, LyricsVideoPlan.OUT_HEIGHT)
        private val canvas = Canvas(bitmap).apply {
            scale(LyricsVideoPlan.OUT_WIDTH / LyricsVideoFrames.WIDTH.toFloat(), LyricsVideoPlan.OUT_HEIGHT / LyricsVideoFrames.HEIGHT.toFloat())
        }
        private var drawnUs = Long.MIN_VALUE

        /** The time of the last frame drawn, read from the main thread for the progress shown. */
        @Volatile
        var lastUs = -1L
            private set

        /** Frames drawn, and the time that took, for the log: what a slow phone spends its time on. */
        var drawn = 0
            private set
        var drawingNanos = 0L
            private set

        /** The same in time the thread drawing them had the processor, which a busy one shares. */
        var drawingCpuNanos = 0L
            private set

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            // drawn once for a frame, however often it is asked
            if (presentationTimeUs != drawnUs) {
                drawnUs = presentationTimeUs
                val from = System.nanoTime()
                val cpuFrom = Debug.threadCpuTimeNanos()
                painter.draw(canvas, presentationTimeUs / 1000)
                drawingCpuNanos += Debug.threadCpuTimeNanos() - cpuFrom
                drawingNanos += System.nanoTime() - from
                drawn++
                lastUs = presentationTimeUs
            }
            return bitmap
        }
    }

    // 3. The two together

    /** [picture]'s video and [sound]'s audio in one file, [into], as they are, in the order of their times. */
    private fun join(picture: File, sound: File, into: File) {
        val video = MediaExtractor().apply { setDataSource(picture.path) }
        val audio = MediaExtractor().apply { setDataSource(sound.path) }
        val muxer = MediaMuxer(into.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrack = video.trackOf("video/")
            val audioTrack = audio.trackOf("audio/")
            video.selectTrack(videoTrack)
            audio.selectTrack(audioTrack)
            val videoFormat = video.getTrackFormat(videoTrack)
            // A tall video is often encoded on its side, and said to be turned.
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            val videoOut = muxer.addTrack(videoFormat)
            val audioOut = muxer.addTrack(audio.getTrackFormat(audioTrack))
            muxer.start()
            val buffer = ByteBuffer.allocate(4 shl 20)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            while (!videoDone || !audioDone) {
                val takeVideo = !videoDone && (audioDone || video.sampleTime <= audio.sampleTime)
                val from = if (takeVideo) video else audio
                val size = from.readSampleData(buffer, 0)
                if (size < 0) {
                    if (takeVideo) videoDone = true else audioDone = true
                    continue
                }
                val flags = if (from.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, from.sampleTime, flags)
                muxer.writeSampleData(if (takeVideo) videoOut else audioOut, buffer, info)
                from.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            video.release()
            audio.release()
        }
    }

    private fun MediaExtractor.trackOf(kind: String): Int =
        (0 until trackCount).firstOrNull { getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true }
            ?: error("No $kind track")

    // Transformer

    /** Exports [item] to [into] with a Transformer made as [setUp] says, on the main thread, where it must be driven from. [progress] follows [measure] where one is given, Transformer's own count where not. */
    private suspend fun export(
        context: Context,
        item: EditedMediaItem,
        into: File,
        progress: (Float) -> Unit,
        measure: (() -> Float)? = null,
        setUp: Transformer.Builder.() -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { done ->
            val main = Handler(Looper.getMainLooper())
            val transformer = Transformer.Builder(context).apply(setUp).build()
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!done.isActive) return
                    when {
                        measure != null -> progress(measure().coerceIn(0f, 1f))
                        transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE -> progress(holder.progress / 100f)
                    }
                    main.postDelayed(this, POLL_MS)
                }
            }
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    main.removeCallbacks(poll)
                    if (done.isActive) done.resume(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    main.removeCallbacks(poll)
                    Log.w(TAG, "Export to ${into.name} failed: ${said(exportException)}")
                    if (done.isActive) done.resumeWithException(exportException)
                }
            })
            transformer.start(Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build(), into.path)
            main.post(poll)
            done.invokeOnCancellation {
                main.post {
                    main.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
        }
    }

    private fun h264Encoders(): List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) } }

    private fun MediaCodecInfo.holdsConstantRate() =
        getCapabilitiesForType(MimeTypes.VIDEO_H264).encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true

    /** The phone's H.264 encoders as a line for the log: each with whether it is hardware, so a failure can be placed. */
    private fun encoders(): String = runCatching {
        h264Encoders().joinToString { it.name + if (it.inSoftware()) " (software)" else " (hardware)" } +
            ", constant rate " + (if (constantRate) "held" else "not held")
    }.getOrElse { "not listed" }

    /** Whether one of the phone's H.264 encoders can hold a constant rate. */
    private val constantRate: Boolean by lazy { runCatching { h264Encoders().any { it.holdsConstantRate() } }.getOrDefault(false) }

    /** The same for the encoders [encoding] is held to: a second try in software has only the software ones. */
    private fun holdsConstantRate(encoding: Encoding): Boolean =
        if (encoding == Encoding.HARDWARE) constantRate else runCatching { h264Encoders().filter { it.inSoftware() }.any { it.holdsConstantRate() } }.getOrDefault(false)

    private const val FOLDER = "lyrics-videos"
    private const val BLACK = "black.png"
    private const val POLL_MS = 200L

    /** How long a video stays in the cache: the app it was sent to may still be reading it a while after. */
    private const val KEPT_MS = 60L * 60 * 1000
}

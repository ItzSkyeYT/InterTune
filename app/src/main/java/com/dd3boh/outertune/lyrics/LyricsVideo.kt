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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
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
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dd3boh.outertune.playback.LevelAnalyser
import com.dd3boh.outertune.playback.MusicLevels
import com.dd3boh.outertune.ui.component.LyricsVideoFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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


    /** The levels are measured from this long before the stretch, so that Living blur starts out knowing the song's shape, as it does in the player. */
    private const val HEARD_BEFORE_MS = 8_000L

    /** How much of the whole each step is, for the progress shown. */
    private const val SOUND_SHARE = 0.12f
    private const val JOIN_SHARE = 0.03f

    /** The levels measured from the stretch's sound, one set of [MusicLevels.VALUES] every [MusicLevels.FRAME_SECONDS], from its start. */
    class Heard(private val sets: List<FloatArray>) {
        /** What is heard at [ms] into the video, or null past what was measured. */
        fun at(ms: Long): FloatArray? {
            if (sets.isEmpty()) return null
            val index = (ms / (MusicLevels.FRAME_SECONDS * 1000)).toInt()
            return sets[index.coerceIn(0, sets.lastIndex)]
        }
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
        try {
            val sound = File(folder, "sound.m4a")
            val heard = cutSound(app, source, mediaId, clip, sound) { progress(it * SOUND_SHARE) }
            val picture = File(folder, "picture.mp4")
            val painter = withContext(Dispatchers.Default) { frames(heard) }
            drawPicture(app, painter, clip, folder, picture) { progress(SOUND_SHARE + it * (1f - SOUND_SHARE - JOIN_SHARE)) }
            val video = File(folder, LyricsShare.fileName(title, "mp4"))
            withContext(Dispatchers.IO) {
                join(picture, sound, video)
                sound.delete()
                picture.delete()
                File(folder, BLACK).delete()
            }
            progress(1f)
            return video
        } catch (failure: Throwable) {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { folder.deleteRecursively() }
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
        context.startActivity(Intent.createChooser(send, null))
    }.onFailure { Log.w(TAG, "Could not share the lyrics video", it) }.isSuccess

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
                    DefaultDecoderFactory.Builder(context).build(),
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
        return Heard(cut.heard())
    }

    /**
     * Passes on the sound from the stretch's start, faded in and out ([LyricsVideoPlan.sound]),
     * after the [skipMs] of song before it that are only measured. Measures all of it for Living
     * blur ([LevelAnalyser], as the player's own tap does).
     */
    private class SoundCut(private val skipMs: Long, private val durationMs: Long) : BaseAudioProcessor() {
        private val sets = ArrayList<FloatArray>()
        private var analyser: LevelAnalyser? = null
        private var seen = 0L
        private var skip = 0L

        /** The levels from the stretch's start. Asked once the export is over. */
        fun heard(): List<FloatArray> = synchronized(sets) { sets.toList() }

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            return inputAudioFormat
        }

        override fun onFlush() {
            val rate = inputAudioFormat.sampleRate
            seen = 0L
            skip = skipMs * rate / 1000
            synchronized(sets) { sets.clear() }
            analyser = LevelAnalyser(rate) { endsAt, levels ->
                if (endsAt > skip) synchronized(sets) { sets += levels.copyOf() }
            }
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

    private suspend fun drawPicture(
        context: Context,
        painter: LyricsVideoFrames,
        clip: LyricsVideoPlan.Clip,
        folder: File,
        into: File,
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
        export(context, item, into, progress) {
            setVideoMimeType(MimeTypes.VIDEO_H264)
            setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(LyricsVideoPlan.PICTURE_BITRATE)
                            // Held to it, so the size is known before it is made: left to vary, the
                            // emulator's encoder took 2 Mbit/s for 1.2 asked. One that cannot hold
                            // a rate falls back to its own way.
                            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                            .build()
                    )
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

    /** Exports [item] to [into] with a Transformer made as [setUp] says, on the main thread, where it must be driven from. */
    private suspend fun export(
        context: Context,
        item: EditedMediaItem,
        into: File,
        progress: (Float) -> Unit,
        setUp: Transformer.Builder.() -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { done ->
            val main = Handler(Looper.getMainLooper())
            val transformer = Transformer.Builder(context).apply(setUp).build()
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!done.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
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
                    Log.w(TAG, "Export to ${into.name} failed: ${exportException.errorCodeName}", exportException)
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

    private const val FOLDER = "lyrics-videos"
    private const val BLACK = "black.png"
    private const val POLL_MS = 200L

    /** How long a video stays in the cache: the app it was sent to may still be reading it a while after. */
    private const val KEPT_MS = 60L * 60 * 1000
}

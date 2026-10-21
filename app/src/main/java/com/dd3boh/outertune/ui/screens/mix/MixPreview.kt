/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * What the editor hears: the change as it will play, from a bar of the canvas, looping over the
 * canvas while [loop] is on. The editor hands it every edit as a whole spec ([update]), read
 * afresh for each block it plays, so a drag is heard without a restart.
 *
 * Positions are bars of the change, as on the canvas. [position] is what is being heard, for the
 * playhead, asked on each frame drawn while it plays.
 */
interface MixPreview {
    val playing: StateFlow<Boolean>

    /** The bar being heard now; meaningful while [playing]. */
    fun position(): Double

    /** The spec to play and the stretch of bars to loop over: every block reads the newest. */
    fun update(spec: TransitionSpec, from: Double, to: Double)

    fun play(fromU: Double)

    fun stop()

    var loop: Boolean

    /** A song alone with a click on each beat and a higher one on each 1 (Fix the beat), or null for the change. */
    fun click(side: Side?)

    /** Plays only [id] of its song, the lane's own level kept (Listen alone); null for everything. */
    fun solo(id: LaneId?)

    fun release()
}

/**
 * The preview until the engine's own is in: [PreviewMixer]'s sound, played on its own AudioTrack
 * with about 40 ms of buffer, on its own thread at audio priority, looping over the canvas with
 * a short fade at the seam.
 */
class CrossfadePreview(private val a: PcmWindow, private val b: PcmWindow) : MixPreview {

    private class Setup(val spec: TransitionSpec, val from: Double, val to: Double)

    private val rate = a.info.sampleRate
    private val mixer = PreviewMixer(a, b)
    private val startLevels = DoubleArray(2)
    private val endLevels = DoubleArray(2)
    private val setup = AtomicReference<Setup?>(null)
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing

    @Volatile override var loop: Boolean = true
    @Volatile private var seekTo: Double? = null
    @Volatile private var running = false
    @Volatile private var stopAsked = false
    private var thread: Thread? = null

    /** Which bar each recent block began on, by the frame count it began at, so the playhead follows what is heard. */
    private val marks = LongArray(MARKS)
    private val marksU = DoubleArray(MARKS)
    private val marksStep = DoubleArray(MARKS)
    @Volatile private var markCount = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var lastU = 0.0

    override fun position(): Double {
        val t = track ?: return lastU
        val played = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        synchronized(marks) {
            val n = markCount
            for (back in 0 until min(n, MARKS)) {
                val i = Math.floorMod(n - 1 - back, MARKS)
                if (marks[i] <= played) return marksU[i] + (played - marks[i]) * marksStep[i]
            }
        }
        return lastU
    }

    override fun update(spec: TransitionSpec, from: Double, to: Double) {
        setup.set(Setup(spec, from, to))
    }

    override fun play(fromU: Double) {
        seekTo = fromU
        lastU = fromU
        if (running) return
        stopAsked = false
        running = true
        _playing.value = true
        thread = Thread({ run() }, "MixPreview").also { it.start() }
    }

    override fun stop() {
        stopAsked = true
        thread?.let { runCatching { it.join(500) } }
        thread = null
        running = false
        _playing.value = false
    }

    override fun click(side: Side?) {
        mixer.click = side
    }

    override fun solo(id: LaneId?) {
        mixer.solo = id
    }

    override fun release() = stop()

    private fun mark(at: Long, u: Double, step: Double) = synchronized(marks) {
        val i = Math.floorMod(markCount, MARKS)
        marks[i] = at
        marksU[i] = u
        marksStep[i] = step
        markCount++
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val channels = 2
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = max(minBuffer, (rate * BUFFER_MS / 1000) * channels * 2)
        val out = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No track for the preview", e)
            running = false
            _playing.value = false
            return
        }
        track = out
        synchronized(marks) { markCount = 0 }
        val block = ShortArray(BLOCK * channels)
        var written = 0L
        var u = 0.0
        var fadeIn = 0
        var ended = false
        try {
            out.play()
            while (!stopAsked && !ended) {
                val s = setup.get()
                if (s == null) {
                    Thread.sleep(10)
                    continue
                }
                seekTo?.let {
                    u = it.coerceIn(s.from, s.to)
                    seekTo = null
                    fadeIn = FADE_FRAMES
                }
                val step = 1000.0 / rate / s.spec.b.barMs
                mark(written, u, step)
                // Levels at the block's two ends, drawn straight between: a lane's gain moves
                // over beats, and a cut's 10 ms is near enough a block.
                mixer.levels(s.spec, u, startLevels)
                mixer.levels(s.spec, u + BLOCK * step, endLevels)
                for (f in 0 until BLOCK) {
                    val t = f.toDouble() / BLOCK
                    mixer.outL = 0.0
                    mixer.outR = 0.0
                    if (u < s.to) mixer.frame(s.spec, u, startLevels[0] + (endLevels[0] - startLevels[0]) * t, startLevels[1] + (endLevels[1] - startLevels[1]) * t)
                    // A short fade at a seek and at the loop's seam, so neither clicks.
                    val toEnd = ((s.to - u) / step).toInt()
                    var g = 1.0
                    if (fadeIn > 0) {
                        g *= 1.0 - fadeIn.toDouble() / FADE_FRAMES
                        fadeIn--
                    }
                    if (toEnd < FADE_FRAMES) g *= max(0.0, toEnd.toDouble() / FADE_FRAMES)
                    block[2 * f] = pcm(mixer.outL * g)
                    block[2 * f + 1] = pcm(mixer.outR * g)
                    u += step
                    if (u >= s.to) {
                        if (loop) {
                            u = s.from
                            fadeIn = FADE_FRAMES
                            mark(written + f + 1, u, step)
                        } else {
                            ended = true
                        }
                    }
                }
                lastU = u
                var done = 0
                while (done < block.size && !stopAsked) {
                    val n = out.write(block, done, block.size - done)
                    if (n <= 0) break
                    done += n
                }
                written += BLOCK
            }
            if (stopAsked) out.pause() else out.stop()
        } catch (e: Exception) {
            Log.w(TAG, "The preview stopped", e)
        } finally {
            track = null
            runCatching { out.release() }
            running = false
            _playing.value = false
        }
    }

    private fun pcm(x: Double): Short = PreviewMixer.pcm(x)

    private companion object {
        const val TAG = "MixPreview"
        const val BUFFER_MS = 40
        const val BLOCK = 512
        const val MARKS = 64
        const val FADE_FRAMES = 480
    }
}

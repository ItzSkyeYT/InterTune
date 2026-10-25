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
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.edit.Key
import com.dd3boh.outertune.mix.edit.LaneCurve
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.Copy
import com.dd3boh.outertune.mix.engine.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The editor's preview: the change as the phone will play it, rendered by the engine's own lane
 * mixer from the editor's copies of the two songs (Preview.render), so what is edited is what is
 * heard, filters, echo, stretch and headroom alike. Each edit is rendered afresh on a thread of
 * its own, the newest spec first: the window playing goes on until the new one is ready, then
 * gives way to it at the same bar with a short crossfade, so nothing restarts.
 *
 * It plays on its own AudioTrack with about 40 ms of buffer, on its own thread at audio priority,
 * looping over the canvas with a short fade at the seam. A plain change is heard as it plays: A to
 * its end, then B from its first frame.
 */
class EnginePreview(a: Copy, b: Copy, aAnalysis: RegionAnalysis?) : MixPreview {

    private val sound = PreviewSound(a, b, aAnalysis)

    private val rate = a.sampleRate
    private val channels = a.channels
    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing

    @Volatile override var loop: Boolean = true
    @Volatile private var clickSide: Side? = null
    @Volatile private var soloLane: LaneId? = null

    /** The newest spec asked for, and the one rendered from it, which the audio thread plays. */
    private val wanted = AtomicReference<PreviewSetup?>(null)
    @Volatile private var heard: PreviewHeard? = null
    private val renderLock = Object()
    @Volatile private var released = false
    private val renderer = Thread({ renderLoop() }, "MixPreviewRender").apply {
        isDaemon = true
        start()
    }

    @Volatile private var seekTo: Double? = null
    @Volatile private var running = false
    @Volatile private var stopAsked = false
    private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var lastU = 0.0

    /** Which bar each recent block began on, by the frame count it began at, so the playhead follows what is heard. */
    private val marks = LongArray(MARKS)
    private val marksU = DoubleArray(MARKS)
    private val marksStep = DoubleArray(MARKS)
    @Volatile private var markCount = 0

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
        val previous = wanted.get()
        if (previous != null && previous.spec == spec && previous.from == from && previous.to == to && previous.solo == soloLane) return
        wanted.set(PreviewSetup(spec, from, to, soloLane))
        synchronized(renderLock) { renderLock.notifyAll() }
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
        clickSide = side
    }

    override fun solo(id: LaneId?) {
        if (soloLane == id) return
        soloLane = id
        wanted.get()?.let { update(it.spec, it.from, it.to) }
    }

    override fun release() {
        stop()
        released = true
        synchronized(renderLock) { renderLock.notifyAll() }
    }

    // ---- rendering

    private fun renderLoop() {
        // At the default priority: a render is heavy, and must not crowd out the page or the music.
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        var done: PreviewSetup? = null
        while (!released) {
            val next = wanted.get()
            if (next == null || next === done) {
                synchronized(renderLock) {
                    if (!released && (wanted.get() == null || wanted.get() === done)) renderLock.wait(500)
                }
                continue
            }
            done = next
            runCatching { sound.heardOf(next) }
                .onSuccess { heard = it }
                .onFailure { Log.w(TAG, "The preview could not render this change", it) }
        }
    }

    // ---- playing

    private fun mark(at: Long, u: Double, step: Double) = synchronized(marks) {
        val i = Math.floorMod(markCount, MARKS)
        marks[i] = at
        marksU[i] = u
        marksStep[i] = step
        markCount++
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = max(minBuffer, (rate * BUFFER_MS / 1000) * channels * 2)
        val out = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
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
        val playhead = PreviewPlayhead(rate, channels, sound)
        try {
            out.play()
            while (!stopAsked && !playhead.ended) {
                val filled = playhead.fill(block, heard, { seekTo?.also { seekTo = null } }, clickSide, loop, written) { at, u, step -> mark(at, u, step) }
                if (!filled) {
                    Thread.sleep(10)
                    continue
                }
                lastU = playhead.lastU
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

    internal companion object {
        const val TAG = "MixPreview"
        const val BUFFER_MS = 40
        const val BLOCK = 512
        const val MARKS = 64
        const val FADE_FRAMES = 480
        const val CLICK_MS = 25.0
    }
}

/** The spec to hear and the stretch of bars it is heard over, with the lane played alone, if any. */
internal class PreviewSetup(val spec: TransitionSpec, val from: Double, val to: Double, val solo: LaneId?)

/** What is heard over the canvas: [pcm] from bar [PreviewSetup.from], where bar u is frame [indexAt] (u). */
internal class PreviewHeard(val setup: PreviewSetup, val pcm: ShortArray, val frames: Int, val uAt: (Int) -> Double, val indexAt: (Double) -> Int)

/**
 * What the preview plays for a setup, from the editor's two copies: the engine's render of the
 * change, or a plain change, and the song alone with a click (Fix the beat). Pure, off the audio
 * thread, so it can be heard in a test.
 */
internal class PreviewSound(private val a: Copy, private val b: Copy, private val aAnalysis: RegionAnalysis?) {
    val rate = a.sampleRate
    val channels = a.channels

    /** What [s] sounds like over its canvas, from the engine's render, or as a plain change. */
    fun heardOf(s: PreviewSetup): PreviewHeard {
        val spec = s.spec
        if (spec.plain) return plainOf(s)
        // Played as the engine plays it: a change that asks for what it does not play yet (the
        // intro twice, a later start) is heard without it, the nearest the editor can give.
        val playable = spec.copy(introTwice = 0, bStartMs = 0.0)
        val alone = s.solo?.let { solo(playable, it, s.from, s.to) } ?: playable
        val window = Preview.render(alone, a, b, aAnalysis).getOrThrow()
        return PreviewHeard(s, window.pcm, window.frames, { i -> window.uAt(i) }, { u -> window.indexAt(u) })
    }

    /** [spec] with every lane but [id] silent across the canvas, [id] keeping its own move: Listen alone. */
    private fun solo(spec: TransitionSpec, id: LaneId, from: Double, to: Double): TransitionSpec {
        val silent = LaneCurve(gain = listOf(Key(from - 1.0, 0.0), Key(to + 1.0, 0.0)))
        return spec.copy(curves = spec.lanes.ids.filter { it != id }.associateWith { silent } + spec.curves.filterKeys { it == id })
    }

    /** A plain change: A at its own speed to its end, then B from its first frame, on A's bars. */
    private fun plainOf(s: PreviewSetup): PreviewHeard {
        val spec = s.spec
        val barMs = spec.b.barMs
        val aEndMs = a.startMs + a.frames * 1000.0 / rate
        val endU = (aEndMs - spec.a.atMs) / barMs
        val n = max(1, ((s.to - s.from) * barMs * rate / 1000.0).roundToInt())
        val pcm = ShortArray(n * channels)
        for (i in 0 until n) {
            val u = s.from + i * 1000.0 / rate / barMs
            val (copy, ms) = if (u < endU) a to (spec.a.atMs + u * barMs) else b to ((u - endU) * barMs)
            for (c in 0 until channels) pcm[i * channels + c] = sampleAt(copy, ms, c, nearest = true).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        val step = 1000.0 / rate / barMs
        return PreviewHeard(s, pcm, n, { i -> s.from + i * step }, { u -> ((u - s.from) / step).roundToInt().coerceIn(0, n) })
    }

    /**
     * [copy]'s channel [c] at [ms] of its song's own clock, read at the copy's own rate: a copy at
     * another rate than the preview's track (a 44.1 kHz file after a 48 kHz stream) is drawn
     * straight between its two nearest frames, so it is heard at its own speed and pitch. Read at
     * the track's rate it was 8.8% fast and 1.5 semitones sharp. At the track's own rate, the
     * copy's own sample: the [nearest] frame, or the one at or before [ms].
     */
    private fun sampleAt(copy: Copy, ms: Double, c: Int, nearest: Boolean): Double {
        val pos = (ms - copy.startMs) * copy.sampleRate / 1000.0
        val ch = min(c, copy.channels - 1)
        if (copy.sampleRate == rate) {
            val f = if (nearest) pos.roundToInt() else floor(pos).toInt()
            return if (f in 0 until copy.frames) copy.pcm[f * copy.channels + ch].toDouble() else 0.0
        }
        val f = floor(pos).toInt()
        if (f < 0 || f >= copy.frames) return 0.0
        val x0 = copy.pcm[f * copy.channels + ch].toDouble()
        val x1 = if (f + 1 < copy.frames) copy.pcm[(f + 1) * copy.channels + ch].toDouble() else x0
        return x0 + (x1 - x0) * (pos - f)
    }

    /** One song alone at its own speed with a click on each beat and a higher one on each 1 (Fix the beat), at bar [u]. */
    fun clickSample(spec: TransitionSpec, side: Side, u: Double, c: Int): Short {
        val anchor = if (side == Side.A) spec.a else spec.b
        val copy = if (side == Side.A) a else b
        val ms = anchor.atMs + u * anchor.barMs
        var v = sampleAt(copy, ms, c, nearest = false) * 0.7
        val beatMs = anchor.barMs / anchor.beatsPerBar
        val beats = (ms - anchor.atMs) / beatMs
        val k = floor(beats).toInt()
        val since = (beats - k) * beatMs
        if (since < EnginePreview.CLICK_MS) {
            val hz = if (Math.floorMod(k, anchor.beatsPerBar) == 0) 1_760.0 else 1_320.0
            v += sin(2 * PI * hz * since / 1000.0) * (1 - since / EnginePreview.CLICK_MS) * 9_000.0
        }
        return v.roundToInt().coerceIn(-32768, 32767).toShort()
    }
}

/**
 * Where the preview is in what it plays, block after block: the newest render taking over at the
 * bar being heard, a seek, the loop's seam and the click. Pure, on the audio thread, so a test can
 * play it block by block.
 */
internal class PreviewPlayhead(private val rate: Int, private val channels: Int, private val sound: PreviewSound) {
    private var playingNow: PreviewHeard? = null
    private var index = 0

    // What was heard before a newer render, a seek or the click took over, played on for a few ms
    // under what comes next, crossed: a window at its frame, or a song alone with its click.
    private var leaving: PreviewHeard? = null
    private var leavingIndex = 0
    private var leavingSide: Side? = null
    private var leavingSpec: TransitionSpec? = null
    private var leavingClickU = 0.0
    private var leavingStep = 0.0
    private var cross = 0
    private var fadeIn = 0
    private var clickU = 0.0
    private var lastSide: Side? = null

    /** Not looping, the end was reached. */
    var ended = false
        private set

    /** The bar heard at the end of the last block. */
    var lastU = 0.0
        private set

    /**
     * Fills [block] from [newest], the newest render; [takeSeek] gives the bar asked for, once.
     * [side] plays that song alone with a click; [mark] is told where each block and each loop
     * begins, for the playhead on screen. False, with nothing filled, while nothing is rendered.
     */
    fun fill(block: ShortArray, newest: PreviewHeard?, takeSeek: () -> Double?, side: Side?, loop: Boolean, written: Long, mark: (Long, Double, Double) -> Unit): Boolean {
        if (newest == null) return false
        val wasHeard = playingNow
        val wasIndex = index
        val wasSide = lastSide
        val wasClickU = clickU
        if (wasHeard == null) {
            playingNow = newest
            index = newest.indexAt(takeSeek() ?: newest.setup.from)
            fadeIn = EnginePreview.FADE_FRAMES
        } else if (newest !== wasHeard) {
            // The newer render takes over at the bar being heard.
            playingNow = newest
            index = newest.indexAt(wasHeard.uAt(wasIndex))
        }
        val h = playingNow!!
        val seek = takeSeek()
        if (seek != null) {
            index = h.indexAt(seek.coerceIn(h.setup.from, h.setup.to))
            clickU = seek
        }
        if (wasHeard != null && (newest !== wasHeard || seek != null || side != wasSide)) {
            // A newer render, a seek, or the click turned on or off: the block given out last
            // ended at full level, so what was heard goes on under the new place for a few ms,
            // crossed. Faded in from silence instead, every tap on the ruler clicked. With the
            // click on, what was heard is the song alone with its click, never the change's render.
            if (wasSide != null) {
                val was = wasHeard.setup.spec
                leaving = null
                leavingSide = wasSide
                leavingSpec = was
                leavingClickU = wasClickU
                leavingStep = 1000.0 / rate / (if (wasSide == Side.A) was.a.barMs else was.b.barMs)
            } else {
                leaving = wasHeard
                leavingIndex = wasIndex
                leavingSide = null
            }
            cross = EnginePreview.FADE_FRAMES
        }
        lastSide = side
        val spec = h.setup.spec
        // The click starts where the change is being heard.
        if (side == null) clickU = h.uAt(index)
        // With the click, the song plays alone at its own speed, so its own bars are counted.
        val step = 1000.0 / rate / (if (side == Side.A) spec.a.barMs else spec.b.barMs)
        val toIndex = h.indexAt(h.setup.to)
        val u0 = if (side != null) clickU else h.uAt(index)
        mark(written, u0, step)
        val frames = block.size / channels
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val x = if (side != null) sound.clickSample(spec, side, clickU, c) else at(h, index, c)
                var v = x.toDouble()
                if (cross > 0) {
                    val lv = leaving
                    val ls = leavingSide
                    val ld = leavingSpec
                    val old = when {
                        ls != null && ld != null -> sound.clickSample(ld, ls, leavingClickU, c).toDouble()
                        lv != null -> at(lv, leavingIndex, c).toDouble()
                        else -> null
                    }
                    val w = cross.toDouble() / EnginePreview.FADE_FRAMES
                    if (old != null) v = v * (1 - w) + old * w
                }
                val toEnd = if (side != null) ((h.setup.to - clickU) / step).toInt() else toIndex - index
                var g = 1.0
                if (fadeIn > 0) g *= 1.0 - fadeIn.toDouble() / EnginePreview.FADE_FRAMES
                if (toEnd < EnginePreview.FADE_FRAMES) g *= max(0.0, toEnd.toDouble() / EnginePreview.FADE_FRAMES)
                block[f * channels + c] = (v * g).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            if (fadeIn > 0) fadeIn--
            if (cross > 0) {
                cross--
                if (leavingSide != null) leavingClickU += leavingStep else leavingIndex++
                if (cross == 0) {
                    leaving = null
                    leavingSide = null
                }
            }
            if (side != null) clickU += step else index++
            val past = if (side != null) clickU >= h.setup.to else index >= toIndex
            if (past) {
                if (loop) {
                    index = h.indexAt(h.setup.from)
                    clickU = h.setup.from
                    fadeIn = EnginePreview.FADE_FRAMES
                    leaving = null
                    leavingSide = null
                    cross = 0
                    mark(written + f + 1, h.setup.from, step)
                } else {
                    ended = true
                }
            }
        }
        lastU = if (side != null) clickU else h.uAt(index)
        return true
    }

    private fun at(h: PreviewHeard, i: Int, c: Int): Short = if (i in 0 until h.frames) h.pcm[i * channels + c] else 0
}

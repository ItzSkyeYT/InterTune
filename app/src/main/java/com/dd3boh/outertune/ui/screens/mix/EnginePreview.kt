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
import java.util.concurrent.CancellationException
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
 * heard, filters, echo, stretch and headroom alike. Each edit is worked out afresh on a thread of
 * its own, the newest spec first, and heard at once (his ask of 9 Nov 2026: "as soon as a change
 * is made it should be hearable by the player and playhead"): it is streamed from the bar being
 * heard (Preview.stream), a chunk at a time, and the window playing gives way to it at that bar
 * with a short crossfade as soon as the frames there are ready, so nothing restarts and the
 * playhead stays where it was. The rest of the window follows, then the stretch before that bar.
 * Where an echo rings, a whole render takes over once it is ready, since a stream begun inside the
 * change hears an echo's earliest repeats short. A whole render took seconds before anything new
 * was heard, and one that follows a stream (12.5 s on a Pixel 5) held up the edit made while it ran
 * by what was left of it (2.9 to 11 s, seen 10 Nov 2026): it gives way to a newer edit within a few
 * ms, so every edit is heard in about the time the rest are.
 *
 * It plays on its own AudioTrack with about 40 ms of buffer, on its own thread at audio priority,
 * looping over the canvas with a short fade at the seam. A plain change is heard as it plays: A to
 * its end, then B from its first frame. Each song is heard at the level the phone plays it at:
 * [levelA] and [levelB] scale its samples as Mix's stage does (AutomixVolume.songAttenuation), and
 * [volume], the in-app volume, goes on the track as it goes on the player's.
 */
class EnginePreview(
    a: Copy, b: Copy, aAnalysis: RegionAnalysis?,
    levelA: Double = 1.0, levelB: Double = 1.0,
    private val volume: Float = 1f,
) : MixPreview {

    /** Edits streamed from the bar heard; off, each is rendered whole first, as before 9 Nov 2026 (the debug app's measuring switch). */
    @Volatile var streamEdits: Boolean = true

    private val sound = PreviewSound(a, b, aAnalysis, levelA, levelB)

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

    /** What the audio thread would play now, for a test. */
    internal val heardSetup: PreviewSetup? get() = heard?.setup
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

    /** How long the last stream took to its first chunk, for how far ahead of the bar heard the next one begins. */
    @Volatile private var firstChunkMs = FIRST_CHUNK_GUESS_MS

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
            // Streamed from the bar heard, heard at once; then, where an echo rings, the whole render.
            val streamed = if (!streamEdits) null else runCatching { stream(next) }
                .onFailure { Log.w(TAG, "The preview could not stream this change", it) }
                .getOrNull()
            if (streamed == Streamed.DONE || streamed == Streamed.SUPERSEDED) {
                continue
            }
            if (wanted.get() !== next || released) continue
            val began = System.nanoTime()
            // Worked on only for as long as it is the edit wanted: a newer one stops it within a few ms
            // (LaneMixer.CANCEL_FRAMES), and is streamed at once, not after what is left of this.
            val cancelled = { wanted.get() !== next || released }
            runCatching { sound.heardOf(next, cancelled) }
                .onSuccess {
                    val now = System.nanoTime()
                    // One that finished as a newer edit came is not what is meant any more: its stream is on its way.
                    if (wanted.get() !== next || released) {
                        Log.i(LATENCY, "rendered the change whole in %.0f ms, but a newer edit had come: not heard".format((now - began) / 1e6))
                        return@onSuccess
                    }
                    heard = it
                    Log.i(LATENCY, "rendered %.1f s of the change whole in %.0f ms, began %.0f ms after the edit".format(
                        it.frames.toDouble() / rate, (now - began) / 1e6, (began - next.askedNanos) / 1e6,
                    ))
                }
                .onFailure {
                    if (it is CancellationException) {
                        Log.i(LATENCY, "the whole render gave way to a newer edit after %.0f ms".format((System.nanoTime() - began) / 1e6))
                    } else {
                        Log.w(TAG, "The preview could not render this change", it)
                    }
                }
        }
    }

    private enum class Streamed {
        /** Every frame streamed, and nothing more to do. */
        DONE,

        /** A newer edit came first: this one is dropped. */
        SUPERSEDED,

        /** Streamed, but an echo rings in it: the whole render follows. */
        WHOLE_TO_FOLLOW,

        /** Not streamed (a plain change): rendered whole, which is quick. */
        WHOLE,
    }

    /**
     * The edit [next] streamed from the bar being heard, a little ahead of it, chunk by chunk to the
     * window's end, then from its start; a chunk the playhead waits for (after a seek, or at the
     * loop's seam) first. The playhead takes it over as soon as the frames it is at are there.
     */
    private fun stream(next: PreviewSetup): Streamed {
        val began = System.nanoTime()
        val s = sound.streamOf(next) ?: return Streamed.WHOLE
        val frames = s.frames
        if (frames <= 0) return Streamed.WHOLE
        val pcm = ShortArray(frames * channels)
        val chunks = (frames + CHUNK - 1) / CHUNK
        val progress = PreviewProgress(chunks)
        // From where the playhead will be once the first chunk is ready; where play starts when stopped.
        val leadFrames = if (running) ((firstChunkMs * LEAD_SHARE + BUFFER_MS) * rate / 1000).toInt() else 0
        val from = (s.indexAt(if (running) position() else lastU) + leadFrames).coerceIn(0, frames - 1)
        var c = from / CHUNK
        val streamed = PreviewHeard(next, pcm, frames, { i -> s.uAt(i) }, { u -> s.indexAt(u) }, progress)
        var st: com.dd3boh.outertune.mix.engine.ChangeStream? = null
        var streamAt = -1
        var left = chunks
        var done = 0
        while (left > 0) {
            if (released) return Streamed.SUPERSEDED
            // A newer edit takes over, but while playing only once this one has enough worked out
            // to be heard on its own until the newer one is there: a drag is heard as it goes.
            if (wanted.get() !== next && (done >= KEEP_CHUNKS || !running)) return Streamed.SUPERSEDED
            val want = progress.want
            if (want >= 0) {
                progress.want = -1
                if (!progress.done(want)) c = want
            }
            if (progress.done(c)) {
                c = progress.nextUndone(c)
                if (c < 0) break
            }
            val now = (if (st == null || streamAt != c) s.from(c * CHUNK) else st).also { st = it }
            val n = min(CHUNK, frames - c * CHUNK)
            now.next(pcm, c * CHUNK, n)
            progress.markDone(c)
            streamAt = c + 1
            left--
            done++
            if (done == 1) {
                // Heard from here: the playhead takes it over on the bar it is at once that bar is there.
                heard = streamed
                val t = System.nanoTime()
                firstChunkMs = (t - began) / 1e6
                Log.i(LATENCY, "streamed its first %.0f ms from bar %.2f in %.0f ms with its plan, %.0f ms after the edit".format(
                    n * 1000.0 / rate, s.uAt(c * CHUNK), (t - began) / 1e6, (t - next.askedNanos) / 1e6,
                ))
            }
            c = if (c + 1 < chunks) c + 1 else 0
        }
        progress.complete = true
        Log.i(LATENCY, "streamed all %.1f s of the window in %.0f ms".format(frames.toDouble() / rate, (System.nanoTime() - began) / 1e6))
        return if (s.echoes) Streamed.WHOLE_TO_FOLLOW else Streamed.DONE
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
        // Under 100%, the in-app volume is the player's own volume, on top of the stage's levels.
        out.setVolume(volume.coerceIn(0f, 1f))
        track = out
        synchronized(marks) { markCount = 0 }
        val block = ShortArray(BLOCK * channels)
        var written = 0L
        val playhead = PreviewPlayhead(rate, channels, sound)
        var taken: PreviewHeard? = null
        var told: PreviewSetup? = null
        try {
            out.play()
            while (!stopAsked && !playhead.ended) {
                val filled = playhead.fill(block, heard, { seekTo?.also { seekTo = null } }, clickSide, loop, written) { at, u, step -> mark(at, u, step) }
                if (!filled) {
                    Thread.sleep(10)
                    continue
                }
                val now = playhead.current
                if (now !== taken) {
                    // An edit's render taking over: how long after the edit its first block goes to
                    // the track, and how much is queued ahead of it there, which it waits behind.
                    if (taken != null && now != null && now.setup !== told) {
                        told = now.setup
                        val head = runCatching { out.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(written)
                        val queuedMs = (written - head).coerceAtLeast(0) * 1000.0 / rate
                        val sinceMs = (System.nanoTime() - now.setup.askedNanos) / 1e6
                        Log.i(LATENCY, "edit heard: its first block written %.0f ms after the edit, %.0f ms queued ahead of it: about %.0f ms to the ear".format(sinceMs, queuedMs, sinceMs + queuedMs))
                    }
                    taken = now
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

        /** The log of how long an edit takes to be heard (9 Nov 2026), for measuring it on a phone. */
        const val LATENCY = "MixEditLatency"
        const val BUFFER_MS = 40
        const val BLOCK = 512
        const val MARKS = 64
        const val FADE_FRAMES = 480
        const val CLICK_MS = 25.0

        /** A streamed window is worked out in chunks of this many frames. */
        const val CHUNK = 4096

        /** A first guess at how long a stream takes to its first chunk, until one was timed. */
        const val FIRST_CHUNK_GUESS_MS = 150.0

        /** How far ahead of the bar heard a stream begins, in its first chunk's time. */
        const val LEAD_SHARE = 1.25

        /** While playing, a stream gives way to a newer edit only with this many chunks done, about 0.6 s. */
        const val KEEP_CHUNKS = 7
    }
}

/** The spec to hear and the stretch of bars it is heard over, with the lane played alone, if any; [askedNanos] when the edit asked for it. */
internal class PreviewSetup(val spec: TransitionSpec, val from: Double, val to: Double, val solo: LaneId?, val askedNanos: Long = System.nanoTime())

/**
 * What is heard over the canvas: [pcm] from bar [PreviewSetup.from], where bar u is frame [indexAt]
 * (u). Rendered whole, or streamed ([progress]): then a frame is there once its chunk is.
 */
internal class PreviewHeard(
    val setup: PreviewSetup,
    val pcm: ShortArray,
    val frames: Int,
    val uAt: (Int) -> Double,
    val indexAt: (Double) -> Int,
    val progress: PreviewProgress? = null,
) {
    /** Whether frames [index] to [index] + [n] are there to be played, up to the window's end. */
    fun readyAt(index: Int, n: Int): Boolean {
        val p = progress ?: return true
        if (p.complete) return true
        val last = min(frames - 1, index + n - 1)
        if (index < 0 || index > last) return index >= frames
        for (c in index / EnginePreview.CHUNK..last / EnginePreview.CHUNK) if (!p.done(c)) return false
        return true
    }

    /** Asks the stream for the frames at [index] first. */
    fun want(index: Int) {
        progress?.let { if (!it.complete) it.want = (index.coerceIn(0, max(0, frames - 1))) / EnginePreview.CHUNK }
    }
}

/**
 * Which chunks of a streamed window are there: written on the render thread, the chunk's samples
 * first and then its mark, read on the audio thread, the mark first ([version] orders the two).
 */
internal class PreviewProgress(private val chunks: Int) {
    private val marks = BooleanArray(chunks)
    @Volatile private var version = 0
    @Volatile var complete = false

    /** A chunk the playhead waits for, -1 for none. */
    @Volatile var want = -1

    fun done(c: Int): Boolean {
        if (c !in 0 until chunks) return true
        // The volatile read first: what was written before the mark is then seen.
        return version >= 0 && marks[c]
    }

    fun markDone(c: Int) {
        marks[c] = true
        version++
    }

    /** The next chunk after [c] not there yet, round to the window's start; -1 when all are. */
    fun nextUndone(c: Int): Int {
        for (k in 1..chunks) {
            val i = (c + k) % chunks
            if (!marks[i]) return i
        }
        return -1
    }
}

/**
 * What the preview plays for a setup, from the editor's two copies: the engine's render of the
 * change, or a plain change, and the song alone with a click (Fix the beat). Pure, off the audio
 * thread, so it can be heard in a test.
 */
internal class PreviewSound(
    private val a: Copy,
    private val b: Copy,
    private val aAnalysis: RegionAnalysis?,
    /** Each song's level as the phone plays it: what the stage scales its samples by. */
    private val levelA: Double = 1.0,
    private val levelB: Double = 1.0,
) {
    val rate = a.sampleRate
    val channels = a.channels

    /**
     * What [s] sounds like over its canvas, from the engine's render, or as a plain change. The
     * render stops, with a CancellationException, soon after [cancelled] says yes.
     */
    fun heardOf(s: PreviewSetup, cancelled: () -> Boolean = { false }): PreviewHeard {
        val spec = s.spec
        if (spec.plain) return plainOf(s)
        // Played as the engine plays it, loops in front of B and the intro twice included: a change
        // that asks for what it does not play yet (a later start) is heard without it.
        val playable = spec.copy(bStartMs = 0.0)
        val alone = s.solo?.let { solo(playable, it, s.from, s.to) } ?: playable
        val window = Preview.render(alone, a, b, aAnalysis, levelA, levelB, cancelled).getOrThrow()
        return PreviewHeard(s, window.pcm, window.frames, { i -> window.uAt(i) }, { u -> window.indexAt(u) })
    }

    /** [s] ready to be worked out from any bar ([Preview.stream]); null for a plain change, which is rendered whole at once. */
    fun streamOf(s: PreviewSetup): com.dd3boh.outertune.mix.engine.PreviewStream? {
        val spec = s.spec
        if (spec.plain) return null
        val playable = spec.copy(bStartMs = 0.0)
        val alone = s.solo?.let { solo(playable, it, s.from, s.to) } ?: playable
        return Preview.stream(alone, a, b, aAnalysis, levelA, levelB).getOrThrow()
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
            val level = if (copy === a) levelA else levelB
            for (c in 0 until channels) pcm[i * channels + c] = (sampleAt(copy, ms, c, nearest = true) * level).roundToInt().coerceIn(-32768, 32767).toShort()
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
        var v = sampleAt(copy, ms, c, nearest = false) * 0.7 * (if (side == Side.A) levelA else levelB)
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

    /** The last render played whole, to play while a streamed one is not there yet. */
    private var previous: PreviewHeard? = null

    /** A seek that came while nothing could be played. */
    private var heldSeek: Double? = null

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

    /** The render being played. */
    val current: PreviewHeard? get() = playingNow

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
        val frames = block.size / channels
        // A seek asked for while nothing could be played yet is kept for the block that plays.
        val seek = heldSeek ?: takeSeek()
        heldSeek = null
        val wasHeard = playingNow
        val wasIndex = index
        val wasSide = lastSide
        val wasClickU = clickU
        // With the click on, the song alone is heard: the change need not be there yet.
        val needs = side == null
        var positioned = false
        if (wasHeard == null) {
            val at = newest.indexAt(seek ?: newest.setup.from)
            if (needs && !newest.readyAt(at, frames)) {
                newest.want(at)
                heldSeek = seek
                return false
            }
            playingNow = newest
            index = at
            positioned = true
            fadeIn = EnginePreview.FADE_FRAMES
        } else if (newest !== wasHeard) {
            // The newer render takes over at the bar being heard, as soon as its frames there are:
            // a streamed one is worked out from a little ahead of it, and taken over when it is.
            val at = newest.indexAt(wasHeard.uAt(wasIndex))
            if (!needs || newest.readyAt(at, frames + TAKE_MARGIN)) {
                if (wasHeard.progress?.complete != false) previous = wasHeard
                playingNow = newest
                index = at
            }
        }
        var h = playingNow!!
        if (seek != null && !positioned) {
            index = h.indexAt(seek.coerceIn(h.setup.from, h.setup.to))
            clickU = seek
        }
        // A streamed render not there yet where it is to play (it fell behind, or a seek went past
        // it): it is asked for those frames first, and meanwhile the render before it plays at the
        // same bar, or, with none, the track waits a moment, coming back faded in.
        var lost = false
        if (needs && !h.readyAt(index, frames)) {
            h.want(index)
            val p = previous
            val at = p?.let { it.indexAt(h.uAt(index)) }
            if (p != null && p !== h && at != null && p.readyAt(at, frames)) {
                playingNow = p
                index = at
                h = p
                lost = true
                fadeIn = EnginePreview.FADE_FRAMES
            } else {
                fadeIn = EnginePreview.FADE_FRAMES
                return false
            }
        }
        // Coming up to the loop's seam, the stream is asked for the window's start in good time.
        if (loop && needs && h.progress?.complete == false && h.indexAt(h.setup.to) - index < rate && !h.readyAt(h.indexAt(h.setup.from), EnginePreview.CHUNK)) {
            h.want(h.indexAt(h.setup.from))
        }
        val switched = h !== wasHeard
        if (wasHeard != null && !lost && (switched || seek != null || side != wasSide)) {
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
                cross = EnginePreview.FADE_FRAMES
            } else if (wasHeard.readyAt(wasIndex, EnginePreview.FADE_FRAMES)) {
                leaving = wasHeard
                leavingIndex = wasIndex
                leavingSide = null
                cross = EnginePreview.FADE_FRAMES
            } else {
                fadeIn = EnginePreview.FADE_FRAMES
            }
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

    private companion object {
        /** A streamed render is taken over with this many frames past the block there too. */
        const val TAKE_MARGIN = 1024
    }
}

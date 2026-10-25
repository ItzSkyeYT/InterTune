/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.dd3boh.outertune.mix.HeldTail
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.RenderedIncoming
import com.dd3boh.outertune.mix.RenderedOutgoing
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AutoMix's stage of the audio chain: the last one, after Sonic, around a [HeldTail].
 *
 * The sink drains the whole chain at every change of song and flushes it after, so only the last
 * stage can carry the end of one song into the next, and only by holding on to it. This one takes
 * the outgoing song's end into memory faster than it plays it, keeps the sink's drain open while
 * it plays that end, and lets the drain close on the frame where the next song is due, then lays
 * the rest of the end over the next song as the plan has it. Without a plan it plays the held
 * frames out as they came, which is the change of today, sample for sample.
 *
 * Inactive, so out of the chain altogether, unless AutoMix is on ([wanted]) or an end is held.
 * That is decided as each stream is configured, so switching AutoMix on or off takes effect at the
 * next song. While it is in the chain it also applies each song's loudness normalisation to the
 * song's own samples, from its first frame, so the two songs of a blend each keep their level
 * (see AutomixVolume).
 *
 * Everything here runs on the player's own thread but for what is marked: the fields the
 * controller sets, and [offer], which the worker calls. Nothing waits and nothing locks. Every
 * entry point catches, says so once, and falls back to passing the stream through.
 */
class AutomixAudioProcessor(
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val nanoTime: () -> Long = System::nanoTime,
) : BaseAudioProcessor() {

    /** What the stage tells its controller, on the player's thread: each call must be quick. */
    interface Events {
        /** The end of the song began to be held, at [tailStartUs] in the sink's time, the sink having played up to [positionUs]. */
        fun armed(id: Long, sampleRate: Int, channels: Int, tailStartUs: Long, positionUs: Long)

        /** The stream ended with its end held: [held] holds [frames] frames of [ring], complete; frames below [frames] never change. */
        fun tailHeld(id: Long, held: HeldTail, ring: ShortArray, frames: Long, songChange: Boolean, formatMatches: Boolean)

        /** The held end said something: a plan taken or refused, a fall back, an abort. */
        fun note(id: Long, text: String)

        /** The next song started, laid under the held end ([mixing]) or after all of it. */
        fun songChanged(id: Long, mixing: Boolean)

        /** The blend is over, the outgoing song gone: [clipped] samples held at full scale, [peak] the loudest sample. */
        fun blendDone(id: Long, clipped: Long, peak: Int)

        /** The incoming song's frames from its frame [fromFrame], as they reached the mixer, for the check that it lines up. */
        fun lockFrames(id: Long, frames: ShortArray, fromFrame: Long)

        /** The stage is, or is no longer, in the chain for the stream that starts now. */
        fun activeChanged(active: Boolean)

        /**
         * A flush from the renderer let go of the end held for [id]: a seek, a song chosen by hand,
         * or the player starting the song over from where it is heard, which it does when what
         * comes next changes after the next song began to be read.
         */
        fun dropped(id: Long)
    }

    /** One song's loudness normalisation, as the mixer multiplies its samples: see AutomixVolume.songAttenuation. */
    class Level(val id: String?, val attenuation: Double) {
        companion object {
            val NONE = Level(null, 1.0)
        }
    }

    /** The song playing now and the one after it, as the controller last saw them. */
    class Levels(val now: Level, val next: Level) {
        companion object {
            val NONE = Levels(Level.NONE, Level.NONE)
        }
    }

    /**
     * A request to start holding the end, with the ring made for it off this thread. Taken at the
     * next buffer, and only within a second of [atNanos]: a request that waited for a stream the
     * stage is not part of must not start holding some later song in its middle.
     */
    class Arm(val id: Long, val ring: ShortArray, val sampleRate: Int, val channels: Int, val atNanos: Long)

    private class Abort(val id: Long, val why: String, val fadeFrames: Long)

    // ---- set from the main thread

    /** Whether AutoMix is on: the stage joins each stream configured from now on. */
    @Volatile
    var wanted = false

    @Volatile
    var levels: Levels = Levels.NONE

    @Volatile
    var events: Events? = null

    private val armRequest = AtomicReference<Arm?>(null)
    private val abortRequest = AtomicReference<Abort?>(null)

    fun requestArm(arm: Arm) = armRequest.set(arm)

    fun cancelArm() = armRequest.set(null)

    /** Gives up on transition [id]: gapless while nothing planned is heard, then a fade over [fadeFrames]. */
    fun requestAbort(id: Long, why: String, fadeFrames: Long) = abortRequest.set(Abort(id, why, fadeFrames))

    /**
     * Hands a plan to the held end, from any thread; it is checked and taken on the next call. With
     * [incoming], the next song's part is played from its copy too; one that [replaces] may take the
     * place of a plan handed over before it, while nothing planned has been heard.
     */
    fun offer(held: HeldTail, plan: MixPlan, rendered: RenderedOutgoing, incoming: RenderedIncoming? = null, replaces: Boolean = false) {
        if (held === this.held) held.offer(plan, rendered, incoming, replaces)
    }

    // ---- read from other threads

    /** Whether the stage is in the chain for the stream playing through it, and so normalises its samples. */
    @Volatile
    var activeForStream = false
        private set

    /** Output frames given out since the end began to be held: what the plan's first change must be ahead of. */
    @Volatile
    var readMark = 0L
        private set

    @Volatile
    var state = HeldTail.State.PASS
        private set

    /** The sink's position, as the renderer last asked for it, in the sink's time. */
    @Volatile
    var sinkPositionUs = C.TIME_UNSET
        private set

    // ---- told by the sink around it (AutomixAudioSink), on the player's thread

    /** True for the length of a flush the renderer asked for: a seek, a stop, a song chosen by hand. */
    internal var rendererFlushing = false

    private var sinkPlaying = false

    /** The time of the last buffer the sink was handed. */
    private var sinkInputUs = C.TIME_UNSET

    internal fun sinkPosition(positionUs: Long) {
        sinkPositionUs = positionUs
    }

    internal fun sinkPlaying(playing: Boolean) {
        sinkPlaying = playing
    }

    internal fun sinkInput(presentationTimeUs: Long) {
        sinkInputUs = presentationTimeUs
        buffersSinceRendererFlush++
    }

    /** Buffers the sink was handed since the renderer last flushed it: none yet for a stream started by hand, a seek or a first start. */
    private var buffersSinceRendererFlush = 0L

    // ---- the player's thread only

    private var held: HeldTail? = null
    private var armId = -1L
    private var configureSeen = false
    private var endOfStreamSeen = false
    private var formatMatches = false

    /** An end of stream that reached the stage in the middle of a blend: a drain inside the incoming song, which ends at once. */
    private var drainInBlend = false

    private var sinceRendererFlush = false

    /** After a song change by itself, the song whose level the frames now take until the controller has caught up with it. */
    private var pinnedId: String? = null
    private var pinnedFrames = 0L

    private var lastNote = ""
    private var failed = false
    private var inShorts = ShortArray(0)
    private var outShorts = ShortArray(0)

    private var mixClippedBefore = 0L
    private var mixPeak = 0
    private var lockFrom = 0L
    private var lockTo = 0L
    private var lockBuffer: ShortArray? = null

    private val pacer = Pacer()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configureSeen = true
        val current = this.inputAudioFormat
        formatMatches = inputAudioFormat.encoding == current.encoding &&
            inputAudioFormat.sampleRate == current.sampleRate &&
            inputAudioFormat.channelCount == current.channelCount
        val holding = held?.state?.let { it != HeldTail.State.PASS } == true
        // In the chain, the stage levels each song, and the volume takes that over only as the
        // player names the next song, a moment after its first frames are heard. So it stays in
        // for a song that comes by itself, levelling it, and steps out at a song started by hand
        // or after a stop, where both change at once: out at the change by itself, the next
        // song's first frames went out unlevelled while the volume still left that to the stage.
        val stays = activeForStream && buffersSinceRendererFlush > 0
        // 16 bit only: the chain runs in 16 bit (MusicService.highPrecisionAudio), and a stage that
        // cannot read a stream steps out of it rather than mangling it.
        val readable = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount in 1..8
        return if (readable && (wanted || holding || stays)) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        try {
            if (failed) passPlain(inputBuffer) else process(inputBuffer)
        } catch (e: Throwable) {
            fail("processing", e)
            passPlain(inputBuffer)
        }
    }

    private fun process(input: ByteBuffer) {
        val held = this.held
        takeRequests(held)
        val format = inputAudioFormat
        val frameBytes = format.bytesPerFrame
        val frames = input.remaining() / frameBytes
        if (held == null) {
            passPlain(input)
            return
        }
        applyLevels(held, frames)
        if (held.isPlain) {
            // The usual case, and the only one with AutoMix on and nothing held: through as it is.
            passPlain(input)
            return
        }
        val channels = format.channelCount
        if (inShorts.size < frames * channels) inShorts = ShortArray(frames * channels)
        input.asShortBuffer().get(inShorts, 0, frames * channels)
        val maxOut = allowance(held, frames)
        if (outShorts.size < maxOut * channels) outShorts = ShortArray(max(maxOut, OUT_MAX) * channels)
        val before = held.state
        val incomingBefore = held.incomingFrames
        val produced = held.process(inShorts, 0, frames, outShorts, 0, maxOut)
        val consumed = held.lastConsumed
        input.position(input.position() + consumed * frameBytes)
        if (before == HeldTail.State.MIX) {
            keepLockFrames(incomingBefore, consumed, channels)
            for (i in 0 until produced * channels) mixPeak = max(mixPeak, abs(outShorts[i].toInt()))
        }
        if (produced > 0) {
            val out = replaceOutputBuffer(produced * frameBytes)
            out.asShortBuffer().put(outShorts, 0, produced * channels)
            out.position(produced * frameBytes)
            out.flip()
        }
        after(held, before)
    }

    /**
     * The stream through as it is, scaled by the song's level: the stage normalises every song
     * while it is in the chain, and goes on doing so if it fails, or the music would get louder.
     */
    private fun passPlain(input: ByteBuffer) {
        val bytes = input.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val attenuation = if (failed) levels.now.attenuation else currentAttenuation
        if (attenuation == 1.0) {
            out.put(input)
        } else {
            while (input.remaining() >= 2) out.putShort(scale(input.getShort(), attenuation))
            input.position(input.limit())
        }
        out.flip()
    }

    /** What the held end was last told the song playing through it is to be scaled by. */
    private var currentAttenuation = 1.0

    /**
     * Each song's level from its first frame: after a song change by itself the frames are the
     * next song's while the player still names the last one, for the quarter of a second until it
     * is heard, so the level of the song that came next is held until the controller says it is
     * playing. Anything else, a seek, a song chosen, a first start, follows the song the player
     * names.
     */
    private fun applyLevels(held: HeldTail, frames: Int) {
        val l = levels
        val pin = pinnedId
        val now = if (pin == null) {
            l.now.attenuation
        } else if (l.now.id == pin || pinnedFrames > PIN_LIMIT_SECONDS * held.sampleRate) {
            pinnedId = null
            l.now.attenuation
        } else {
            pinnedFrames += frames
            if (l.next.id == pin) l.next.attenuation else currentAttenuation
        }
        currentAttenuation = now
        held.setAttenuation(now)
        held.setNextAttenuation(l.next.attenuation)
    }

    /** How much may go out on this call. */
    private fun allowance(held: HeldTail, frames: Int): Int = when (held.state) {
        HeldTail.State.PASS, HeldTail.State.MIX -> frames
        HeldTail.State.DRAIN -> OUT_MAX
        HeldTail.State.CAPTURE -> {
            val paced = pacer.allowance(held.read, held.sampleRate)
            // With input it cannot take, its memory full, it has to give, or the sink would spin.
            val forced = if (frames > 0 && !held.canTakeInput()) FORCED_OUT else 0
            max(paced, forced).coerceIn(0, OUT_MAX)
        }
    }

    private fun takeRequests(held: HeldTail?) {
        abortRequest.getAndSet(null)?.let { a ->
            if (held != null && a.id == armId && held.state != HeldTail.State.PASS) held.abort(a.why, a.fadeFrames)
        }
        val arm = armRequest.getAndSet(null) ?: return
        val why = when {
            nanoTime() - arm.atNanos > ARM_WAIT_NANOS -> "the request came too long ago"
            held == null -> "the stage has no stream"
            !activeForStream -> "AutoMix was not on as this song started"
            held.state != HeldTail.State.PASS -> "an end is held already"
            arm.sampleRate != held.sampleRate || arm.channels != held.channels -> "the song's format changed"
            sinkPositionUs == C.TIME_UNSET -> "the output has no position yet"
            !held.arm(arm.ring) -> held.note.ifEmpty { "the end could not be held" }
            else -> null
        }
        if (why != null) {
            tell { note(arm.id, "not holding the end: $why") }
            return
        }
        armId = arm.id
        lastNote = held!!.note
        pacer.start(sinkPositionUs, nanoTime())
        state = held.state
        readMark = 0
        tell { armed(arm.id, held.sampleRate, held.channels, sinkInputUs, sinkPositionUs) }
    }

    /** What changed with the last call, said once. */
    private fun after(held: HeldTail, before: HeldTail.State) {
        readMark = held.read
        val now = held.state
        if (now != before) state = now
        if (before == HeldTail.State.MIX && now == HeldTail.State.PASS) {
            tell { blendDone(armId, held.clipped - mixClippedBefore, mixPeak) }
            lockBuffer = null
        }
        sayNote(held)
    }

    private fun sayNote(held: HeldTail) {
        val text = held.note
        if (text != lastNote) {
            lastNote = text
            if (text.isNotEmpty()) tell { note(armId, text) }
        }
    }

    private fun keepLockFrames(incomingBefore: Long, consumed: Int, channels: Int) {
        val buffer = lockBuffer ?: return
        val from = max(incomingBefore, lockFrom)
        val to = min(incomingBefore + consumed, lockTo)
        if (to <= from) return
        System.arraycopy(inShorts, ((from - incomingBefore) * channels).toInt(), buffer, ((from - lockFrom) * channels).toInt(), ((to - from) * channels).toInt())
        if (to == lockTo) {
            tell { lockFrames(armId, buffer, lockFrom) }
            lockBuffer = null
        }
    }

    override fun onQueueEndOfStream() {
        endOfStreamSeen = true
        if (failed) return
        val held = held ?: return
        try {
            when (held.state) {
                HeldTail.State.CAPTURE -> {
                    held.endOfStream(configureSeen, formatMatches)
                    state = held.state
                    readMark = held.read
                    held.heldTail()?.let { ring -> tell { tailHeld(armId, held, ring, held.written, configureSeen, formatMatches) } }
                    sayNote(held)
                }
                // The sink drains its chain inside a song too: once more just after every change of
                // song, as the renderer reports the new stream, and for new playback parameters. The
                // incoming song goes on, so the blend does, and the drain ends at once.
                HeldTail.State.MIX -> drainInBlend = true
                HeldTail.State.DRAIN -> Unit
                HeldTail.State.PASS -> held.endOfStream(configureSeen, formatMatches)
            }
        } catch (e: Throwable) {
            fail("end of stream", e)
        }
    }

    override fun isEnded(): Boolean {
        if (!super.isEnded()) return false
        if (failed || drainInBlend) return true
        return try {
            held?.isEnded() ?: true
        } catch (e: Throwable) {
            fail("draining", e)
            true
        }
    }

    override fun onFlush() {
        try {
            flushHeld()
        } catch (e: Throwable) {
            fail("flushing", e)
        }
        configureSeen = false
        endOfStreamSeen = false
        drainInBlend = false
        val active = isActive
        if (active != activeForStream) {
            // Back in the chain after songs it was out of: the level it held is long gone, and the
            // song's own is taken on its first buffer, not drawn out from there.
            if (active) held?.takeNextAttenuationAtOnce()
            activeForStream = active
            tell { activeChanged(active) }
        }
    }

    private fun flushHeld() {
        val kind = when {
            rendererFlushing -> HeldTail.Flush.RENDERER
            configureSeen && endOfStreamSeen -> HeldTail.Flush.SONG_CHANGE
            else -> HeldTail.Flush.PARAMETERS
        }
        if (kind == HeldTail.Flush.RENDERER) {
            // A seek or a song chosen by hand, which the next stream follows. Nothing held survives
            // it, not even a request to hold that has not been taken yet, and the output has no
            // position until the new stream's first buffer is in: a request taken before then
            // would pace the new stream by where the old one had got to.
            pinnedId = null
            sinceRendererFlush = true
            buffersSinceRendererFlush = 0
            armRequest.set(null)
            sinkPositionUs = C.TIME_UNSET
        }
        if (failed && kind != HeldTail.Flush.PARAMETERS) {
            // Whatever the stage failed on is behind it with the stream it failed in: the next one
            // starts with a fresh held end, as if nothing had happened.
            failed = false
            held = null
            lastNote = ""
        }
        var held = held
        val wasHolding = held != null && held.state != HeldTail.State.PASS
        if (wasHolding && kind == HeldTail.Flush.RENDERER) tell { dropped(armId) }
        val blendGoesOn = held != null && held.state == HeldTail.State.MIX && drainInBlend && kind == HeldTail.Flush.PARAMETERS
        if (held != null && !blendGoesOn) {
            val before = held.state
            held.flush(kind)
            state = held.state
            if (kind == HeldTail.Flush.SONG_CHANGE) {
                if (sinceRendererFlush) {
                    // The first stream after a seek: the player names the song it went to.
                    pinnedId = null
                } else {
                    pinnedId = levels.next.id
                    pinnedFrames = 0
                }
                sinceRendererFlush = false
                if (before == HeldTail.State.DRAIN) {
                    val mixing = held.state == HeldTail.State.MIX
                    if (mixing) startMix(held)
                    tell { songChanged(armId, mixing) }
                }
            } else if (kind == HeldTail.Flush.PARAMETERS) {
                sinceRendererFlush = false
            }
            if (wasHolding && held.state == HeldTail.State.PASS) lockBuffer = null
            sayNote(held)
        }
        // A held end of another format never meets a stream of another format: it is let go above.
        val format = inputAudioFormat
        if (format.encoding == C.ENCODING_PCM_16BIT && (held == null || held.state == HeldTail.State.PASS) &&
            (held == null || held.sampleRate != format.sampleRate || held.channels != format.channelCount)
        ) {
            // Its first level is the one the next buffer brings, taken at once.
            held = HeldTail(format.channelCount, format.sampleRate, AutomixSchedule.capacityFrames(format.sampleRate))
            this.held = held
            lastNote = ""
        }
        readMark = held?.read ?: 0
    }

    private fun startMix(held: HeldTail) {
        mixClippedBefore = held.clipped
        mixPeak = 0
        val plan = held.takenPlan
        lockBuffer = null
        if (plan != null && plan.lockTo > plan.lockFrom) {
            lockFrom = plan.lockFrom
            lockTo = plan.lockTo
            lockBuffer = try {
                ShortArray(((lockTo - lockFrom) * held.channels).toInt())
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }

    override fun onReset() {
        held = null
        armId = -1
        configureSeen = false
        endOfStreamSeen = false
        drainInBlend = false
        pinnedId = null
        failed = false
        lockBuffer = null
        state = HeldTail.State.PASS
        readMark = 0
        activeForStream = false
        sinceRendererFlush = false
        buffersSinceRendererFlush = 0
    }

    /** Once per stream: what failed goes in the log, and the stream passes through as it is until the next one. */
    private fun fail(where: String, e: Throwable) {
        if (!failed) Log.w(TAG, "AutoMix: failed while $where, this song passes through as it is", e)
        failed = true
        state = HeldTail.State.PASS
        lockBuffer = null
        tell { note(armId, "failed while $where: ${e.javaClass.simpleName}") }
    }

    /** Tells the controller, which only posts what it hears to its own threads; nothing it does may stop the sound. */
    private inline fun tell(block: Events.() -> Unit) {
        val e = events ?: return
        try {
            e.block()
        } catch (t: Throwable) {
            Log.w(TAG, "AutoMix: the controller failed to take an event", t)
        }
    }

    /**
     * How far ahead of the output the held end may give: what the sink has played since the end
     * began to be held, less a margin, so the AudioTrack stays as full as it was and the sink always
     * has room for what comes out. Output it has no room for would stay pending, and the sink takes
     * no input while output is pending, which would bring the capture down to the speed of sound.
     *
     * If the output stops moving while the sink plays, the track has run dry, which happens when it
     * was not full as the end began to be held: then a little more is let out each time, until it
     * plays again.
     */
    private inner class Pacer {
        private var startUs = C.TIME_UNSET
        private var lastUs = C.TIME_UNSET
        private var lastMovedNanos = 0L
        private var credit = 0L

        fun start(positionUs: Long, nanos: Long) {
            startUs = positionUs
            lastUs = positionUs
            lastMovedNanos = nanos
            credit = 0
        }

        fun allowance(read: Long, sampleRate: Int): Int {
            val position = sinkPositionUs
            if (position == C.TIME_UNSET || startUs == C.TIME_UNSET) return 0
            val played = ((position - startUs) * sampleRate / 1_000_000L).coerceAtLeast(0)
            val now = nanoTime()
            if (position != lastUs) {
                lastUs = position
                lastMovedNanos = now
            }
            var allowed = played - read - MARGIN_FRAMES + credit
            if (allowed <= 0 && sinkPlaying && now - lastMovedNanos > STALL_NANOS) {
                credit += sampleRate / STEPS_A_SECOND
                lastMovedNanos = now
                allowed = played - read - MARGIN_FRAMES + credit
            }
            return allowed.coerceIn(0, OUT_MAX.toLong()).toInt()
        }
    }

    companion object {
        private const val TAG = "AutoMix"

        /** The most frames one call gives out while the end is held. */
        const val OUT_MAX = 4096

        /** What a call gives out at the least when it cannot take its input. */
        private const val FORCED_OUT = 1024

        private const val MARGIN_FRAMES = 1024L

        /** What a dry track is given at a time, in parts of a second. */
        private const val STEPS_A_SECOND = 10
        private const val STALL_NANOS = 150_000_000L
        private const val ARM_WAIT_NANOS = 1_000_000_000L

        /** How long a song's level is held for it after a song change by itself, if the controller never names it. */
        private const val PIN_LIMIT_SECONDS = 1.5

        /** A sample scaled by a song's level, rounded as the held end rounds. */
        internal fun scale(x: Short, attenuation: Double): Short {
            val r = Math.floor(x * attenuation + 0.5)
            return when {
                r > 32767.0 -> 32767
                r < -32768.0 -> -32768
                else -> r.toInt().toShort()
            }
        }
    }
}

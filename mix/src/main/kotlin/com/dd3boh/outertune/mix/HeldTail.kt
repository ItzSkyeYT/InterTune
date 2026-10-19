/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import kotlin.math.max
import kotlin.math.min

/**
 * The mixer at the end of the audio chain, as a state machine with no Android in it.
 *
 * The audio sink drains its whole chain at every song change and flushes it after, so the only
 * stage that can carry sound from one song into the next is the last one, and only if it holds
 * on to the end of the outgoing song itself. This is that stage:
 *
 * - [State.PASS]: frames pass through, scaled by the current song's attenuation.
 * - [State.CAPTURE]: armed about 40 s before the end. Input is taken into memory as fast as it
 *   comes, and output is the held frames, in order, at the pace the caller asks for: the same
 *   samples the chain would have played anyway.
 * - [State.DRAIN]: the stream has ended and the next song is waiting. The held tail plays out,
 *   raw at first, then as the plan has it, and the drain is held open until the plan's frame for
 *   the next song's start, or to the tail's last frame without a plan: today's gapless change.
 * - [State.MIX]: the next song's frames come in from its real start, and for each one exactly one
 *   frame goes out: the next song shaped by the plan, plus the matching frame of the outgoing song.
 *
 * Its invariants are the design's: every call that is given input or asked for output while
 * there is some consumes or produces something, so the sink's loop never spins; before the song
 * change every output frame is the next frame of the outgoing song as planned; after it, exactly
 * one output frame for every frame of the incoming song. Anything uncertain falls back to playing
 * the held frames as they are, which is the gapless change, sample for sample.
 *
 * One thread calls everything but [offer], which may come from a worker and is picked up on the
 * next call.
 */
class HeldTail(val channels: Int, val sampleRate: Int, private val capacityFrames: Int) {

    enum class State { PASS, CAPTURE, DRAIN, MIX }

    /** What a flush of the chain means. */
    enum class Flush {
        /** From the renderer: a seek, a stop, a song chosen by hand. Everything held is dropped. */
        RENDERER,

        /** The sink's own, after the drain at a song change: the next song starts. */
        SONG_CHANGE,

        /** The sink's own, after a drain for new playback parameters: the same stream goes on. */
        PARAMETERS,
    }

    /** A plan and the outgoing song's part rendered for it, handed over together. */
    class Offer(val plan: MixPlan, val rendered: RenderedOutgoing)

    var state = State.PASS
        private set

    /** Why the last transition fell back or ended, for the logs. */
    var note: String = ""
        private set

    /** The attenuation applied to the song playing, frame by frame, on its way to [targetAttenuation]. */
    private var attenuation = 1.0
    private var targetAttenuation = 1.0
    private var rampLeft = 0
    private var rampStep = 0.0
    private val rampFrames = max(1, sampleRate / RAMP_PARTS)

    /** Whether the next attenuation handed over is taken at once, as it is for a song started afresh. */
    private var snapAttenuation = true
    private var nextAttenuation = 1.0

    /** The outgoing song's level through the blend, against the level its part was rendered at. */
    private var outgoingScale = 1.0

    private var ring: ShortArray? = null

    /** Tail frames held so far. */
    var written = 0L
        private set

    /** Output frames given out since the tail was armed: the read mark. */
    var read = 0L
        private set

    private var inputEnded = false
    private var songChange = false
    private var formatMatches = false
    private var overflowed = false

    /** The transition was given up: no plan is taken for it any more, whenever one arrives. */
    private var aborted = false

    @Volatile
    private var offered: Offer? = null
    private var plan: MixPlan? = null

    /** The plan taken for this transition, if one was; read on the thread that calls [process]. */
    val takenPlan: MixPlan? get() = plan
    private var rendered: RenderedOutgoing? = null
    private var shaper: IncomingShaper? = null
    private val mixer = Mixer()
    private var shaped = DoubleArray(0)
    private val incomingLevels = DoubleArray(MIX_BLOCK)

    /** Frames of the incoming song given out since the song change. */
    var incomingFrames = 0L
        private set

    private var fadeFrom = -1L
    private var fadeLength = 0L

    /** Where an abort began to cross from the planned part back to the held end as it was. */
    private var backFrom = -1L

    /** Samples the mix had to clamp at full scale in the last transition: expected to stay at zero. */
    val clipped: Long get() = mixer.clipped

    /** Consumed by the last [process] call. */
    var lastConsumed = 0
        private set

    /**
     * The attenuation of the song playing now, applied to its samples. A change within a song is
     * drawn out over 25 ms, as the AudioTrack draws out a change of its own volume, so a volume
     * dragged or a level put right while the song plays never steps at the edge of a buffer. The
     * first one after a flush from the renderer, a song started afresh, is taken at once.
     */
    fun setAttenuation(value: Double) {
        if (snapAttenuation) {
            snapAttenuation = false
            attenuation = value
            targetAttenuation = value
            rampLeft = 0
            return
        }
        if (value == targetAttenuation) return
        targetAttenuation = value
        rampLeft = rampFrames
        rampStep = (value - attenuation) / rampFrames
    }

    /** The next attenuation handed over is taken at once, as for a song started afresh: the stage joining the chain again. */
    fun takeNextAttenuationAtOnce() {
        snapAttenuation = true
    }

    /** One frame further along a change of attenuation, if one is under way. */
    private fun stepAttenuation() {
        if (rampLeft == 0) return
        rampLeft--
        attenuation = if (rampLeft == 0) targetAttenuation else attenuation + rampStep
    }

    /** The attenuation of the song that comes next, applied from its first frame. */
    fun setNextAttenuation(value: Double) {
        nextAttenuation = value
    }

    /** Whether frames pass untouched: nothing held, the song at full level, and no change of level under way. */
    val isPlain: Boolean get() = state == State.PASS && attenuation == 1.0 && rampLeft == 0

    /** How many frames the held end can take: what a [arm] ring must have room for. */
    val capacity: Int get() = capacityFrames

    /**
     * Starts holding the outgoing song's end. Only from [State.PASS], between calls; false when
     * the memory cannot be had, in which case nothing changes.
     *
     * [preallocated] is a ring made elsewhere, so that the audio thread does not have to find
     * several megabytes at once: [capacity] frames of [channels], all silence. Silence matters,
     * since the stretcher reads past the last held frame as silence.
     */
    fun arm(preallocated: ShortArray? = null): Boolean {
        if (state != State.PASS || inputEnded) return false
        if (preallocated != null && preallocated.size != capacityFrames * channels) {
            note = "the ring made for the end is the wrong size"
            return false
        }
        ring = preallocated ?: try {
            ShortArray(capacityFrames * channels)
        } catch (e: OutOfMemoryError) {
            note = "no memory to hold the end"
            return false
        }
        written = 0
        read = 0
        overflowed = false
        aborted = false
        plan = null
        rendered = null
        offered = null
        fadeFrom = -1
        fadeLength = 0
        backFrom = -1
        state = State.CAPTURE
        note = ""
        return true
    }

    /** The held tail so far, for the worker: frames 0 until [written] never change once held. */
    fun heldTail(): ShortArray? = ring

    /**
     * Hands over a plan and its rendered part, from any thread. Taken on the next call, and only if
     * the tail it was made from is complete and its first change is at least [leadFrames] ahead of
     * what has already gone out; otherwise refused, and the change stays gapless.
     */
    fun offer(plan: MixPlan, rendered: RenderedOutgoing) {
        offered = Offer(plan, rendered)
    }

    private fun takeOffer() {
        val o = offered ?: return
        offered = null
        if (plan != null) return
        if (aborted) {
            // Worked out while the change was being given up: a sleep timer set, AutoMix switched
            // off. It arrives in time, but the change it was for is not coming.
            note = "refused: the change was given up"
            return
        }
        if (state != State.DRAIN) {
            note = "refused: the plan came after the change"
            return
        }
        val p = o.plan
        when {
            p.isGapless -> note = "gapless: ${p.reason}"
            !songChange -> note = "refused: no song change to mix into"
            !formatMatches -> note = "refused: the next song's format differs"
            p.tailFrames != written -> note = "refused: planned for ${p.tailFrames} frames, ${written} held"
            p.firstChange < read + leadFrames -> note = "refused: the plan came ${(read + leadFrames - p.firstChange) * 1000 / sampleRate} ms late"
            p.bStart < read -> note = "refused: the next song would already have started"
            o.rendered.firstFrame > p.bStart || o.rendered.endFrame < p.aGone -> note = "refused: the rendered part does not cover the blend"
            else -> {
                plan = p
                rendered = o.rendered
                note = "planned: ${p.describe()}"
            }
        }
    }

    /** How far ahead of the read mark a plan's first change must be when it arrives. */
    var leadFrames: Long = sampleRate.toLong()

    /**
     * Takes up to [inFrames] frames from [input] at frame [inOffset] and writes up to [maxOut]
     * frames to [output] at frame [outOffset]. Returns the frames written; [lastConsumed] says how
     * many were taken. In [State.PASS] and [State.MIX] the two are always equal.
     */
    fun process(input: ShortArray, inOffset: Int, inFrames: Int, output: ShortArray, outOffset: Int, maxOut: Int): Int {
        takeOffer()
        lastConsumed = 0
        return when (state) {
            State.PASS -> pass(input, inOffset, min(inFrames, maxOut), output, outOffset)
            State.CAPTURE -> capture(input, inOffset, inFrames, output, outOffset, maxOut)
            State.DRAIN -> drain(output, outOffset, maxOut)
            State.MIX -> mix(input, inOffset, min(inFrames, maxOut), output, outOffset)
        }
    }

    private fun pass(input: ShortArray, inOffset: Int, frames: Int, output: ShortArray, outOffset: Int): Int {
        val from = inOffset * channels
        val to = outOffset * channels
        if (rampLeft == 0) {
            val n = frames * channels
            if (attenuation == 1.0) System.arraycopy(input, from, output, to, n)
            else for (i in 0 until n) output[to + i] = attenuate(input[from + i], attenuation)
        } else {
            for (f in 0 until frames) {
                stepAttenuation()
                val i = f * channels
                for (c in 0 until channels) output[to + i + c] = attenuate(input[from + i + c], attenuation)
            }
        }
        lastConsumed = frames
        return frames
    }

    private fun capture(input: ShortArray, inOffset: Int, inFrames: Int, output: ShortArray, outOffset: Int, maxOut: Int): Int {
        val ring = this.ring!!
        if (!overflowed) {
            val room = (capacityFrames - written).toInt()
            val n = min(inFrames, room)
            System.arraycopy(input, inOffset * channels, ring, (written * channels).toInt(), n * channels)
            written += n
            lastConsumed = n
            // Full, with more to come: hold no more, play out what is held, then let the rest
            // through. Every sample still comes out in order, so this is the gapless change.
            if (n < inFrames) {
                overflowed = true
                note = "fell back: the end did not fit in memory"
            }
        }
        val produced = playHeld(output, outOffset, maxOut, written)
        if (overflowed && read == written) {
            // All held frames are out: from here on the stream passes through.
            release()
            state = State.PASS
            val extra = if (produced < maxOut && lastConsumed < inFrames) {
                val taken = lastConsumed
                val p = pass(input, inOffset + taken, min(inFrames - taken, maxOut - produced), output, outOffset + produced)
                lastConsumed = taken + p
                p
            } else 0
            return produced + extra
        }
        return produced
    }

    /** Plays held frames from the read mark up to [limit], as planned. */
    private fun playHeld(output: ShortArray, outOffset: Int, maxOut: Int, limit: Long): Int {
        val ring = this.ring ?: return 0
        var n = 0
        while (n < maxOut && read < limit) {
            val o = read
            val to = (outOffset + n) * channels
            val from = (o * channels).toInt()
            stepAttenuation()
            val p = plan
            val r = rendered
            if (p == null || r == null || o < p.aFirstProcessed) {
                for (c in 0 until channels) output[to + c] = attenuate(ring[from + c], attenuation)
            } else {
                // The part rendered for the plan, at the level the song has now: the level it was
                // rendered at, unless the volume or the song's loudness moved since.
                val scale = if (p.attenuationA > 0.0) attenuation / p.attenuationA else 1.0
                val back = if (backFrom >= 0) ((o - backFrom + 1).toDouble() / BACK_FRAMES).coerceIn(0.0, 1.0) else 0.0
                for (c in 0 until channels) {
                    var v = r.at(o, c).toDouble()
                    if (scale != 1.0) v *= scale
                    if (fadeFrom >= 0) v *= fadeGain(o)
                    if (back > 0.0) v = v * (1.0 - back) + ring[from + c] * attenuation * back
                    output[to + c] = toPcm16(v)
                }
                if (backFrom >= 0 && o + 1 >= backFrom + BACK_FRAMES) {
                    // Back on the end as it was held: the rest of it plays as it is, then the next song.
                    plan = null
                    rendered = null
                    backFrom = -1
                }
            }
            read++
            n++
        }
        return n
    }

    private fun fadeGain(o: Long): Double = if (fadeFrom < 0 || o < fadeFrom) 1.0 else (1.0 - (o - fadeFrom).toDouble() / max(1, fadeLength)).coerceIn(0.0, 1.0)

    /**
     * Where the drain may close: the next song's planned start, or the end of a fade-out that ends
     * before it, or the tail's end. A fade that would end after the next song's start runs on over
     * the next song.
     */
    private fun drainLimit(): Long {
        val p = plan
        return when {
            p == null || backFrom >= 0 -> written
            fadeFrom >= 0 -> min(p.bStart, fadeFrom + fadeLength).coerceAtLeast(read)
            else -> p.bStart
        }
    }

    private fun drain(output: ShortArray, outOffset: Int, maxOut: Int): Int = playHeld(output, outOffset, maxOut, drainLimit())

    private fun mix(input: ShortArray, inOffset: Int, frames: Int, output: ShortArray, outOffset: Int): Int {
        val p = plan!!
        val r = rendered!!
        val shaper = this.shaper!!
        lastConsumed = frames
        var done = 0
        while (done < frames) {
            val o = p.bStart + incomingFrames
            val end = if (fadeFrom >= 0) fadeFrom + fadeLength else p.shapedUntil
            if (o >= end) {
                // The blend is over: the outgoing song is gone, and the incoming one passes.
                release()
                state = State.PASS
                val rest = pass(input, inOffset + done, frames - done, output, outOffset + done)
                lastConsumed = frames
                return done + rest
            }
            val n = min(frames - done, min((end - o).toInt(), MIX_BLOCK))
            if (shaped.size < n * channels) shaped = DoubleArray(MIX_BLOCK * channels)
            // The incoming song at its own level frame by frame, so that a change of it is drawn
            // out here too and the song meets its plain self where the shaping ends.
            for (i in 0 until n) {
                stepAttenuation()
                incomingLevels[i] = attenuation
            }
            shaper.shape(input, inOffset + done, n, incomingFrames, shaped, 0, incomingLevels)
            if (fadeFrom >= 0) {
                // Aborted: the outgoing song falls away over one beat and the incoming one comes up
                // to its plain self over the same beat.
                for (i in 0 until n) {
                    val g = fadeGain(o + i)
                    for (c in 0 until channels) {
                        val plain = input[(inOffset + done + i) * channels + c] * incomingLevels[i]
                        shaped[i * channels + c] = shaped[i * channels + c] * g + plain * (1 - g) + r.at(o + i, c) * outgoingScale * (g - 1)
                    }
                }
            }
            mixer.mix(r, o, shaped, 0, n, output, outOffset + done, outgoingScale)
            incomingFrames += n
            done += n
        }
        return done
    }

    /**
     * The stream has ended. [songChangePending] says a configuration for the next stream came
     * before it, so the next song is waiting; [formatMatches] that it has this song's rate and
     * channels, so it can be mixed into.
     */
    fun endOfStream(songChangePending: Boolean, formatMatches: Boolean) {
        inputEnded = true
        songChange = songChangePending
        this.formatMatches = formatMatches
        when (state) {
            State.CAPTURE -> {
                state = State.DRAIN
                if (!songChangePending) note = "the stream ended with no song after it: the whole end plays out"
                else if (!formatMatches) note = "the next song's format differs: the whole end plays out"
            }
            State.MIX -> {
                // The incoming song's stream ended inside the blend, which the guards keep from
                // happening: nothing is left to mix into, so the blend ends here.
                release()
                state = State.PASS
                note = "the next song ended inside the blend"
            }
            else -> Unit
        }
    }

    /** Whether nothing more will come out after the end of stream. */
    fun isEnded(): Boolean {
        if (!inputEnded) return false
        takeOffer()
        return when (state) {
            State.DRAIN -> read >= drainLimit()
            else -> true
        }
    }

    /** Whether a call with input would take any of it: false while the held end is full or being let go. */
    fun canTakeInput(): Boolean = when (state) {
        State.CAPTURE -> !overflowed && written < capacityFrames
        State.DRAIN -> false
        else -> true
    }

    /** Whether output is waiting to be given out without any more input. */
    fun hasHeldOutput(): Boolean = when (state) {
        State.CAPTURE -> read < written
        State.DRAIN -> read < drainLimit()
        else -> false
    }

    fun flush(kind: Flush) {
        takeOffer()
        val wasEnded = inputEnded
        inputEnded = false
        when (kind) {
            Flush.RENDERER -> {
                if (state != State.PASS) note = "dropped: ${state.name.lowercase()} interrupted"
                release()
                state = State.PASS
                snapAttenuation = true
            }
            Flush.SONG_CHANGE -> {
                val p = plan
                val ready = state == State.DRAIN && wasEnded && songChange && p != null && backFrom < 0 && read == p.bStart
                // Through the blend the outgoing song keeps the level it had as the drain closed.
                outgoingScale = if (p != null && p.attenuationA > 0.0) attenuation / p.attenuationA else 1.0
                attenuation = nextAttenuation
                targetAttenuation = nextAttenuation
                rampLeft = 0
                if (ready) {
                    // Into the blend, with a fade begun as the drain closed carried on over it.
                    state = State.MIX
                    incomingFrames = 0
                    shaper = IncomingShaper(p!!, rendered!!.headroom)
                } else {
                    release()
                    state = State.PASS
                }
                songChange = false
            }
            Flush.PARAMETERS -> {
                if (state != State.PASS) note = "dropped: playback parameters changed"
                release()
                state = State.PASS
            }
        }
    }

    /**
     * Gives up on the transition, for good: a plan that arrives after this is refused. Before
     * anything planned is heard, the held end plays out as it is, so the change stays gapless.
     * Once the planned part is playing but the outgoing song still runs at its own speed, it
     * crosses back to the held end as it was over a few milliseconds, and that plays out in full
     * before the next song. Where the outgoing song is already stretched, or the incoming one has
     * started, the outgoing one fades out over [fadeFrames] (a beat) and the incoming one goes on
     * alone.
     */
    fun abort(why: String, fadeFrames: Long = sampleRate / 2L) {
        takeOffer()
        aborted = true
        val p = plan
        when (state) {
            State.CAPTURE -> {
                overflowed = true
                if (p == null) note = "aborted: $why"
            }
            State.DRAIN -> when {
                p == null -> note = "aborted: $why"
                read < p.aFirstProcessed -> {
                    plan = null
                    rendered = null
                    note = "aborted before it was heard: $why"
                }
                fadeFrom >= 0 || backFrom >= 0 -> Unit
                inStep(p, read) -> {
                    backFrom = read
                    note = "aborted, back to the end as it was: $why"
                }
                else -> {
                    fadeFrom = read
                    fadeLength = fadeFrames
                    note = "aborted, fading out: $why"
                }
            }
            State.MIX -> if (fadeFrom < 0) {
                fadeFrom = p!!.bStart + incomingFrames
                fadeLength = fadeFrames
                note = "aborted in the blend, fading out: $why"
            }
            State.PASS -> Unit
        }
    }

    /**
     * Whether the planned part still lines up with the held end, frame for frame, from output frame
     * [o] to past the crossing back: everywhere for a plan without a change of tempo, and before
     * the stretch begins for one with it, the stretcher copying the end there.
     */
    private fun inStep(p: MixPlan, o: Long): Boolean =
        p.aMap.isIdentity || o + BACK_FRAMES + Wsola.FRAME <= p.aMap.rampStart

    private fun release() {
        ring = null
        plan = null
        rendered = null
        shaper = null
        offered = null
        written = 0
        read = 0
        overflowed = false
        fadeFrom = -1
        fadeLength = 0
        backFrom = -1
        outgoingScale = 1.0
    }

    private companion object {
        const val MIX_BLOCK = 1024

        /** A change of level within a song takes a fortieth of a second: 25 ms. */
        const val RAMP_PARTS = 40

        /** How long an abort takes to cross back to the held end as it was: 10 ms at 44.1 kHz. */
        const val BACK_FRAMES = 441
    }
}

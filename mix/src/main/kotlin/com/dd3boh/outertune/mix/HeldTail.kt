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
 * - [State.CAPTURE]: armed up to about 108 s before the end. Input is taken into memory as fast as
 *   it comes, and output is the held frames, in order, at the pace the caller asks for: the same
 *   samples the chain would have played anyway.
 * - [State.DRAIN]: the stream has ended and the next song is waiting. The held tail plays out,
 *   raw at first, then as the plan has it, and the drain is held open until the plan's frame for
 *   the next song's start, or to the tail's last frame without a plan: today's gapless change.
 * - [State.MIX]: the next song's frames come in from its real start, or from the frame it was
 *   armed to come in on ([entry]), and for each one exactly one frame goes out: the next song
 *   shaped by the plan, plus the matching frame of the outgoing song.
 *
 * A next song that comes in after its first frame never brings its earlier frames here: the sink
 * in front of the chain skips them as they are decoded, while the held end plays on, and tells
 * this stage when the next frame in is the entry ([entrySkipped]). A plan for such a change is
 * taken only for that entry, and is let go before any of it is heard if the frames before it are
 * not all skipped by then: the held end then plays out as it is, and the next song comes in at
 * its entry after it.
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

    /**
     * A plan and the outgoing song's part rendered for it, handed over together, with the incoming
     * song's part where it was rendered from a copy too. One that [replaces] may take the place of
     * a plan already taken, as long as nothing planned has been heard yet.
     */
    class Offer(
        val plan: MixPlan,
        val rendered: RenderedOutgoing,
        val incoming: RenderedIncoming? = null,
        val replaces: Boolean = false,
        /** The held end it was planned on, where it says: taken only while that is the end held, never by one held after a jump. */
        val ring: ShortArray? = null,
    )

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

    /** How many frames the ring armed now holds: [capacity] at most, less for an end held from nearer the end. */
    private var ringFrames = capacityFrames

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

    /**
     * The incoming song's own frame this change brings it in on, given as the end began to be
     * held: 0 for its first frame. Its frames before it are skipped before they reach this stage.
     */
    var entry = 0L
        private set

    /** Whether every frame of the incoming song before [entry] has been skipped, so the next one in is the entry. */
    var entryReady = true
        private set

    /** Whether any frame before [entry] has been skipped: from then on the next song comes in at its entry, mixed or not. */
    private var entryStarted = false

    /**
     * Whether the incoming song's entry is still the change's to say: an end held after a jump
     * into the change, which is joined where it can still be heard, B coming in where it would have
     * been by then. Until a plan is taken or the entry is decided ([decideEntry]), its first frames
     * wait in front of the sink ([ENTRY_WAIT]) while the held end drains on; given up, it comes in
     * from its first frame.
     */
    var entryOpen = false
        private set

    /** The entry decided from another thread for an open one, with the held end it was decided for. */
    private class EntryDecision(val ring: ShortArray, val frames: Long)

    @Volatile
    private var entryDecision: EntryDecision? = null

    /**
     * A blend of both songs' parts let go by a jump inside the incoming song (a flush from the
     * renderer while it played), kept for the first buffer after it: where that is the incoming
     * song's music still inside the blend, the blend is taken up again there ([resume]).
     */
    private class Paused(val plan: MixPlan, val rendered: RenderedOutgoing, val incoming: RenderedIncoming, val outgoingScale: Double)

    private var paused: Paused? = null

    /** Whether a blend let go by a jump inside the incoming song may be taken up again ([resume]). */
    val resumable: Boolean get() = paused != null

    /** The incoming song's own frame its part was heard from as the blend began, for where a jump inside it went. */
    val pausedEntry: Long get() = paused?.plan?.bEntry ?: 0L

    @Volatile
    private var offered: Offer? = null
    private var plan: MixPlan? = null

    /** The plan taken for this transition, if one was; read on the thread that calls [process]. */
    val takenPlan: MixPlan? get() = plan
    private var rendered: RenderedOutgoing? = null

    /** The incoming song's part, where the change plays both songs from copies. */
    private var incoming: RenderedIncoming? = null

    // The check that the incoming song as it plays is the copy its part was rendered from: what
    // was compared so far, and where the change crossed over to the song as it plays when it was not.
    private var joinSignal = 0.0
    private var joinError = 0.0
    private var joinDone = false

    /** The incoming frame the check against the copy starts from: its first, or a moment after a blend taken up again ([resume]). */
    private var joinFrom = 0L
    private var liveFrom = -1L
    private var laneClipped = 0L
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
    val clipped: Long get() = mixer.clipped + laneClipped

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
     * several megabytes at once: up to [capacity] frames of [channels], all silence. Silence
     * matters, since the stretcher reads past the last held frame as silence. [entry] is the
     * incoming song's frame the change brings it in on, 0 for its first.
     */
    @JvmOverloads
    fun arm(preallocated: ShortArray? = null, entry: Long = 0, entryOpen: Boolean = false): Boolean {
        if (state != State.PASS || inputEnded) return false
        paused = null
        if (preallocated != null && (preallocated.size > capacityFrames * channels || preallocated.size % channels != 0 || preallocated.isEmpty())) {
            note = "the ring made for the end is the wrong size"
            return false
        }
        ring = preallocated ?: try {
            ShortArray(capacityFrames * channels)
        } catch (e: OutOfMemoryError) {
            note = "no memory to hold the end"
            return false
        }
        ringFrames = ring!!.size / channels
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
        this.entryOpen = entryOpen
        this.entry = if (entryOpen) 0L else entry.coerceAtLeast(0)
        entryReady = this.entry == 0L
        entryStarted = false
        entryDecision = null
        state = State.CAPTURE
        note = ""
        return true
    }

    /**
     * How many of the incoming song's first frames are to be skipped before it reaches this stage:
     * the armed [entry] while the change is still coming and has not been given up, 0 otherwise.
     * Asked as the next song's first frames are decoded; once skipping has begun it goes on to the
     * entry whatever happens to the plan.
     */
    fun entryToSkip(): Long {
        takeEntryDecision()
        if (entryOpen) {
            if ((state == State.CAPTURE || state == State.DRAIN) && !aborted && !overflowed) return ENTRY_WAIT
            // Given up before the change said where: the next song from its first frame.
            closeEntry(0L)
        }
        return when {
            entry <= 0L -> 0L
            state != State.CAPTURE && state != State.DRAIN -> 0L
            entryStarted -> entry
            aborted || overflowed -> 0L
            else -> entry
        }
    }

    /**
     * Says where the incoming song comes in for an entry still open ([entryOpen]), from any thread:
     * on its frame [frames], for the end held in [ring] only. Taken on the next call; a plan for
     * it must then bring it in there.
     */
    fun decideEntry(frames: Long, ring: ShortArray) {
        entryDecision = EntryDecision(ring, frames.coerceAtLeast(0))
    }

    private fun takeEntryDecision() {
        val d = entryDecision ?: return
        entryDecision = null
        if (entryOpen && d.ring === ring) closeEntry(d.frames)
    }

    private fun closeEntry(frames: Long) {
        entryOpen = false
        entry = frames
        entryReady = frames == 0L
    }

    /** The sink has skipped the incoming song's first frames up to its entry ([done]), or begun to. */
    fun entrySkipped(done: Boolean) {
        if (entry <= 0L) return
        entryStarted = true
        if (done) entryReady = true
    }

    /** The held tail so far, for the worker: frames 0 until [written] never change once held. */
    fun heldTail(): ShortArray? = ring

    /**
     * Hands over a plan and its rendered part, from any thread. Taken on the next call, and only if
     * the tail it was made from is complete and its first change is at least [leadFrames] ahead of
     * what has already gone out; otherwise refused, and the change stays gapless.
     */
    @JvmOverloads
    fun offer(plan: MixPlan, rendered: RenderedOutgoing, incoming: RenderedIncoming? = null, replaces: Boolean = false, ring: ShortArray? = null) {
        offered = Offer(plan, rendered, incoming, replaces, ring)
    }

    private fun takeOffer() {
        takeEntryDecision()
        val o = offered ?: return
        offered = null
        if (o.ring != null && o.ring !== ring) {
            // Planned on an end held before a jump: that music is not the one held now.
            note = "refused: planned on an end held before"
            return
        }
        val current = plan
        if (current != null) {
            // A better plan for the same change, worked out after a first one was handed over so
            // that one was in place in time: taken only while nothing planned has been heard.
            if (!o.replaces) return
            if (aborted || state != State.DRAIN || backFrom >= 0 || fadeFrom >= 0) return
            if (read + leadFrames > current.firstChange) {
                note = "kept the first plan: the second came ${(read + leadFrames - current.firstChange) * 1000 / sampleRate} ms too late"
                return
            }
            if (o.plan.isGapless) {
                // The second look found no clean change: the first plan is let go before any of
                // it is heard, and the held end plays out as it is, today's change.
                note = "gapless after all: ${o.plan.reason}"
                plan = null
                rendered = null
                incoming = null
                return
            }
        }
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
            p.isGapless -> {
                note = "gapless: ${p.reason}"
                // The change will not say where the next song comes in: from where the plan has it.
                if (entryOpen) closeEntry(p.bEntry)
            }
            !entryOpen && p.bEntry != entry -> note = "refused: planned for the next song from its frame ${p.bEntry}, held for its frame $entry"
            o.incoming != null && o.incoming.entry != p.bEntry -> note = "refused: the incoming part starts elsewhere in the song"
            !songChange -> note = "refused: no song change to mix into"
            !formatMatches -> note = "refused: the next song's format differs"
            p.tailFrames != written -> note = "refused: planned for ${p.tailFrames} frames, ${written} held"
            p.firstChange < read + leadFrames -> note = "refused: the plan came ${(read + leadFrames - p.firstChange) * 1000 / sampleRate} ms late"
            p.bStart < read -> note = "refused: the next song would already have started"
            o.rendered.firstFrame > p.bStart || o.rendered.endFrame < p.aGone -> note = "refused: the rendered part does not cover the blend"
            o.incoming != null && (o.incoming.firstFrame != p.bStart || o.incoming.endFrame < p.shapedUntil || o.incoming.channels != channels) ->
                note = "refused: the incoming part does not cover the blend"
            else -> {
                note = (if (current != null) "planned again: " else "planned: ") + p.describe() +
                    (if (p.crossIn > 0) ", joined part-way" else "")
                plan = p
                rendered = o.rendered
                incoming = o.incoming
                if (entryOpen) closeEntry(p.bEntry)
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
        if (state == State.DRAIN) checkEntry(maxOut)
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
            val room = (ringFrames - written).toInt()
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
                // Joined part-way: from the end as held into the change, which there is other music.
                val into = if (p.crossIn > 0 && o < p.aFirstProcessed + p.crossIn) (o - p.aFirstProcessed + 1).toDouble() / p.crossIn else 1.0
                for (c in 0 until channels) {
                    var v = r.at(o, c).toDouble()
                    if (scale != 1.0) v *= scale
                    if (into < 1.0) v = v * into + (if (o < written) ring[from + c] else 0) * attenuation * (1.0 - into)
                    if (fadeFrom >= 0) v *= fadeGain(o)
                    // Back on what was held: past the held end (a change that runs on through a
                    // loop of the next song) there is nothing held to go back to.
                    if (back > 0.0) v = v * (1.0 - back) + (if (o < written) ring[from + c] else 0) * attenuation * back
                    output[to + c] = toPcm16(v)
                }
                if (backFrom >= 0 && o + 1 >= backFrom + BACK_FRAMES) {
                    // Back on the end as it was held: the rest of it plays as it is, then the next song.
                    plan = null
                    rendered = null
                    incoming = null
                    backFrom = -1
                }
            }
            read++
            n++
        }
        return n
    }

    /**
     * A plan that brings the next song in after its first frame, whose earlier frames are not all
     * skipped yet with its first change coming up within this call and a margin: let go before any
     * of it is heard, so the held end plays out as it is, today's change, and the next song comes
     * in at its entry after it.
     */
    private fun checkEntry(maxOut: Int) {
        val p = plan ?: return
        if (p.bEntry <= 0L || entryReady || backFrom >= 0 || fadeFrom >= 0) return
        if (read + maxOut + sampleRate / ENTRY_MARGIN_PARTS < p.firstChange) return
        if (read >= p.aFirstProcessed) {
            // Never so, since a plan is taken a second ahead of its first change and this is
            // looked at on every call; were it so, the change gives up the way an abort does.
            abort("the next song's frames before its entry were not all skipped in time")
            return
        }
        plan = null
        rendered = null
        incoming = null
        aborted = true
        note = "let go before it was heard: the next song's frames before its entry were not all skipped in time"
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
        if (incoming != null) return mixParts(input, inOffset, frames, output, outOffset)
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
     * Both songs' parts rendered ahead, added: one output frame for each frame of the incoming
     * song, as in [mix]. The song itself is only held against the copy its part was rendered from,
     * since what plays is that part; where the two differ, the change crosses over to the song as
     * it plays at once, and the outgoing song fades over a beat. An abort fades the outgoing song
     * over its beat and brings the incoming one to itself over the same beat: the part and the song
     * are the same music, so the crossing cannot be heard. Where the part plays the incoming song at
     * the outgoing one's tempo, they are the same music only from [MixPlan.incomingOwnFrom]: the
     * part plays on until there, and the crossing is made there.
     */
    private fun mixParts(input: ShortArray, inOffset: Int, frames: Int, output: ShortArray, outOffset: Int): Int {
        val p = plan!!
        val r = rendered!!
        val b = incoming!!
        lastConsumed = frames
        var done = 0
        val own = p.incomingOwnFrom
        while (done < frames) {
            val o = p.bStart + incomingFrames
            // Given up before the incoming part is the song's own music, it plays on to there.
            val end = if (fadeFrom < 0) p.shapedUntil else min(max(fadeFrom + fadeLength, if (own > fadeFrom) own + BACK_FRAMES else 0L), b.endFrame)
            if (o >= end) {
                release()
                state = State.PASS
                val rest = pass(input, inOffset + done, frames - done, output, outOffset + done)
                lastConsumed = frames
                return done + rest
            }
            val n = min(frames - done, (end - o).toInt())
            for (i in 0 until n) {
                stepAttenuation()
                val at = o + i
                val inFrame = (inOffset + done + i) * channels
                if (!joinDone) join(input, inFrame, incomingFrames + i, at, b)
                val bScale = if (b.attenuation > 0.0) attenuation / b.attenuation else 1.0
                val g = fadeGain(at)
                // To the song as it plays only where the part is the same music: at once for a
                // part at the song's own speed, from its own samples on for one at the other's tempo.
                val crossFrom = if (liveFrom >= 0) max(liveFrom, own) else -1L
                val toLive = when {
                    fadeFrom < 0 -> 0.0
                    own <= fadeFrom -> 1.0 - g
                    else -> ((at - own + 1).toDouble() / BACK_FRAMES).coerceIn(0.0, 1.0)
                }
                val live = max(if (crossFrom >= 0) ((at - crossFrom + 1).toDouble() / BACK_FRAMES).coerceIn(0.0, 1.0) else 0.0, toLive)
                val to = (outOffset + done + i) * channels
                for (c in 0 until channels) {
                    var vb = b.at(at, c).toDouble()
                    if (bScale != 1.0) vb *= bScale
                    if (live > 0.0) vb = vb * (1.0 - live) + input[inFrame + c] * attenuation * live
                    var va = r.at(at, c).toDouble()
                    if (outgoingScale != 1.0) va *= outgoingScale
                    if (g != 1.0) va *= g
                    if (ChangeSum.wouldClip(va, vb)) laneClipped++
                    output[to + c] = ChangeSum.add(va, vb)
                }
            }
            incomingFrames += n
            done += n
        }
        return done
    }

    /**
     * Holds the incoming song's [n]th frame in, its own frame [RenderedIncoming.entry] + [n], at
     * [inFrame] of [input], against its copy. Once enough of it was heard to tell, they either
     * agree, and nothing is checked again, or they do not, and the change crosses over to the song
     * as it plays from output frame [at].
     */
    private fun join(input: ShortArray, inFrame: Int, n: Long, at: Long, b: RenderedIncoming) {
        if (n < joinFrom) return
        val bFrame = b.entry + n
        if (bFrame >= b.copyFrames || n - joinFrom >= JOIN_FRAMES_S * sampleRate) {
            joinDone = true
            return
        }
        val base = (bFrame * channels).toInt()
        for (c in 0 until channels) {
            val copy = b.copy[base + c].toDouble()
            val d = input[inFrame + c] - copy
            joinSignal += copy * copy
            joinError += d * d
        }
        if (joinSignal < JOIN_SIGNAL) return
        joinDone = true
        if (joinError <= joinSignal * JOIN_TOLERANCE) return
        liveFrom = at
        if (fadeFrom < 0) {
            fadeFrom = at
            fadeLength = max(1L, b.beatFrames)
        }
        note = "the next song plays differently from its copy (%.1f dB): crossing over to it".format(10 * kotlin.math.log10(joinError / joinSignal))
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
        State.CAPTURE -> !overflowed && written < ringFrames
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
                // A jump inside the incoming song while both songs' parts play: the blend is kept,
                // to be taken up again where the jump went if that is still inside it.
                val p = plan
                val r = rendered
                val b = incoming
                paused = if (state == State.MIX && p != null && r != null && b != null && fadeFrom < 0 && backFrom < 0 && liveFrom < 0) Paused(p, r, b, outgoingScale) else null
                if (state != State.PASS) note = "dropped: ${state.name.lowercase()} interrupted"
                release()
                state = State.PASS
                snapAttenuation = true
            }
            Flush.SONG_CHANGE -> {
                paused = null
                val p = plan
                // A plan for a later entry only once the frames before it are skipped, so the frame
                // that comes in next is the one the plan puts on its bar.
                val ready = state == State.DRAIN && wasEnded && songChange && p != null && backFrom < 0 && read == p.bStart && (p.bEntry == 0L || entryReady)
                // Through the blend the outgoing song keeps the level it had as the drain closed.
                outgoingScale = if (p != null && p.attenuationA > 0.0) attenuation / p.attenuationA else 1.0
                attenuation = nextAttenuation
                targetAttenuation = nextAttenuation
                rampLeft = 0
                if (ready) {
                    // Into the blend, with a fade begun as the drain closed carried on over it.
                    state = State.MIX
                    incomingFrames = 0
                    joinSignal = 0.0
                    joinError = 0.0
                    joinDone = false
                    joinFrom = 0
                    liveFrom = -1
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
     * The blend let go by a jump inside the incoming song taken up again on its first buffer
     * after the jump, [frames] frames of [live]: found in the copy its part was rendered from,
     * within [radius] frames of [guess] (where the sink's times put it), it plays on from there as
     * rendered, A's part and B's together, B's own music still held against its copy. False, and
     * the song plays on alone as before, where it is not found, or the jump went outside the blend.
     * Tried once; the player's thread.
     */
    fun resume(live: ShortArray, frames: Int, guess: Long, radius: Int): Boolean {
        val k = paused ?: return false
        paused = null
        if (state != State.PASS || frames <= 0) return false
        val b = k.incoming
        val p = k.plan
        val n = min(frames, RESUME_FRAMES)
        val at = findInCopy(live, n, b, guess, radius) ?: run {
            note = "not picked up again after a jump inside the next song: $resumeWhy"
            return false
        }
        val heard = at - p.bEntry
        val o = p.bStart + heard
        if (heard < 0 || o >= p.shapedUntil || o >= b.endFrame) {
            note = "not picked up again after a jump inside the next song: it went outside the blend"
            return false
        }
        plan = p
        rendered = k.rendered
        incoming = b
        outgoingScale = k.outgoingScale
        incomingFrames = heard
        joinSignal = 0.0
        joinError = 0.0
        joinDone = false
        // The decoder starts again at the jump, and its first frames may not be its copy's to the
        // bit: the song is held against its copy once it has settled.
        joinFrom = heard + (RESUME_SETTLE_S * sampleRate).toLong()
        liveFrom = -1
        fadeFrom = -1
        fadeLength = 0
        backFrom = -1
        shaper = null
        state = State.MIX
        note = "picked up again after a jump inside the next song, at its frame " + at + ", " + (at - guess) + " frames from where its times put it"
        return true
    }

    /**
     * Where in [b]'s copy the [n] frames of [live] are, within [radius] of [guess]: the place that
     * matches best, where it matches within [RESUME_MATCH] of the music's own level. The decoder
     * starts again at a jump, so its first frames are near its copy's, not equal to them. Never
     * silence, which would match anywhere. Its first frames at every place first, then all of them
     * at the few that matched best.
     */
    private fun findInCopy(live: ShortArray, n: Int, b: RenderedIncoming, guess: Long, radius: Int): Long? {
        val copy = b.copy
        val copyFrames = b.copyFrames.toLong()
        val samples = n * channels
        var energy = 0.0
        for (i in 0 until samples) energy += live[i].toDouble() * live[i]
        if (energy < RESUME_SIGNAL * samples) {
            resumeWhy = "too quiet there to find"
            return null
        }
        val quick = min(samples, RESUME_QUICK_FRAMES * channels)
        val keep = RESUME_CANDIDATES
        val bestAt = LongArray(keep) { Long.MIN_VALUE }
        val bestErr = DoubleArray(keep) { Double.MAX_VALUE }
        for (d in -radius..radius) {
            val f = guess + d
            if (f < 0 || f + n > copyFrames) continue
            val base = (f * channels).toInt()
            val worst = bestErr[keep - 1]
            var err = 0.0
            var i = 0
            while (i < quick) {
                val e = (copy[base + i] - live[i]).toDouble()
                err += e * e
                if (err >= worst) break
                i++
            }
            if (i < quick) continue
            // Into the few best, in order.
            var k = keep - 1
            while (k > 0 && bestErr[k - 1] > err) {
                bestErr[k] = bestErr[k - 1]
                bestAt[k] = bestAt[k - 1]
                k--
            }
            bestErr[k] = err
            bestAt[k] = f
        }
        var found = Long.MIN_VALUE
        var foundErr = Double.MAX_VALUE
        for (k in 0 until keep) {
            val f = bestAt[k]
            if (f == Long.MIN_VALUE) continue
            val base = (f * channels).toInt()
            var err = 0.0
            for (i in 0 until samples) {
                val e = (copy[base + i] - live[i]).toDouble()
                err += e * e
            }
            if (err < foundErr) {
                foundErr = err
                found = f
            }
        }
        if (found == Long.MIN_VALUE || foundErr > energy * RESUME_MATCH) {
            resumeWhy = if (found == Long.MIN_VALUE) "nothing of its copy is there" else
                "its music there was not found (%.0f dB off at best, %d frames from where its times put it)".format(10 * kotlin.math.log10(foundErr / energy), found - guess)
            return null
        }
        return found
    }

    /** Why the last blend was not taken up again, for the logs. */
    private var resumeWhy = ""

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
        if (entryOpen && !entryStarted) closeEntry(0L)
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
                    incoming = null
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
        o + BACK_FRAMES + Wsola.FRAME <= p.alignedUntil && (p.aMap.isIdentity || o + BACK_FRAMES + Wsola.FRAME <= p.aMap.rampStart)

    private fun release() {
        ring = null
        plan = null
        rendered = null
        incoming = null
        liveFrom = -1
        shaper = null
        offered = null
        written = 0
        read = 0
        overflowed = false
        fadeFrom = -1
        fadeLength = 0
        backFrom = -1
        outgoingScale = 1.0
        entry = 0
        entryReady = true
        entryStarted = false
        entryOpen = false
        entryDecision = null
    }

    companion object {
        /** What [entryToSkip] says while the change has not said yet where the incoming song comes in: its first frames wait. */
        const val ENTRY_WAIT = -1L

        private const val MIX_BLOCK = 1024

        /** A change of level within a song takes a fortieth of a second: 25 ms. */
        private const val RAMP_PARTS = 40

        /** How long an abort takes to cross back to the held end as it was: 10 ms at 44.1 kHz. */
        private const val BACK_FRAMES = 441

        /** A plan whose entry is not in hand is let go this long, a fifth of a second, before its first change. */
        private const val ENTRY_MARGIN_PARTS = 5

        /**
         * The incoming song is held against its copy over its first seconds, and judged once the
         * copy has had this much energy, in squared samples: a tenth of a second of stereo at
         * 44.1 kHz and -40 dBFS.
         */
        private const val JOIN_FRAMES_S = 4L
        private const val JOIN_SIGNAL = 327.68 * 327.68 * 2 * 4410

        /** The two may differ by a rounding here and there, a decoder's last bit, and no more: 40 dB under the song. */
        private const val JOIN_TOLERANCE = 1e-4

        /** How much of the first buffer after a jump inside the next song is looked for in its copy. */
        private const val RESUME_FRAMES = 2048

        /** The first frames held against the copy at every place, and how many places are then held against it whole. */
        private const val RESUME_QUICK_FRAMES = 256
        private const val RESUME_CANDIDATES = 8

        /** How near its copy the song after a jump must be, as a share of its own energy: 20 dB under it. */
        private const val RESUME_MATCH = 1e-2

        /** How long after a blend is taken up again the song is held against its copy: the decoder settles first. */
        private const val RESUME_SETTLE_S = 0.5

        /** The least mean squared sample that is music to look for, not silence: about -50 dBFS. */
        private const val RESUME_SIGNAL = 100.0 * 100.0
    }
}

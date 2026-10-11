/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.dd3boh.outertune.constants.Unreleased
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Puts the music in front of the listener instead of inside their head.
 *
 * Stereo on headphones is unnatural in a way people stop noticing: a hard-panned guitar arrives at
 * one ear and literally never reaches the other, which cannot happen in a room, so the brain gives
 * up on placing it and puts the whole mix on a line between the ears. This renders the same two
 * channels as two loudspeakers standing in front of you, deriving what each ear would actually
 * receive, including the shadowing of the head and the shape of the outer ear.
 *
 * The route taken, and why it is not the obvious one. The obvious way is a pair of head-related
 * impulse responses per virtual speaker, convolved directly. That works until you want to move the
 * soundstage, at which point you have to interpolate between measured directions, and these
 * responses are not minimum phase: the interaural delay is inside the impulse, so interpolating
 * coefficients comb filters. Encoding to ambisonics instead makes a horizontal rotation a pair of
 * multiplies per harmonic, exact at any angle, with nothing to interpolate and nothing to click.
 *
 * Order matters more than anything else here. A first-order field has a directional blur roughly a
 * hundred and twenty degrees across, so two virtual speakers sixty degrees apart sit inside one
 * blur and the whole mix arrives as a single broad object. Third order narrows that enough to hear
 * instruments as separate places, and costs ten convolutions a sample rather than three.
 *
 * What it is not. The recording has two channels and nothing recovers information that was never
 * recorded, so this is not Atmos and not a remaster. It is also not personalised: the responses
 * were measured on a Neumann KU100 dummy head, whose ears are not yours, so elevation will not
 * convince and front-back confusion is a known artefact of borrowed anatomy.
 *
 * No Android in here beyond the media3 interface, so the arithmetic is tested.
 */
class BinauralAudioProcessor : BaseAudioProcessor() {

    /** Whether to render. Volatile: flipped on the main thread, read on the audio thread. */
    @Volatile
    var enabled: Boolean = false

    /**
     * Third order rather than first. Sharper, and three times the arithmetic.
     *
     * Worth having as a choice rather than a constant because the cost is real: ten two hundred
     * and fifty six tap convolutions per sample is a hundred and twenty million multiplies a
     * second, which a current phone does not notice and a six year old tablet might.
     */
    @Volatile
    var thirdOrder: Boolean = true

    /**
     * How far the head has turned, in radians, anticlockwise, so plus is a turn to the left.
     *
     * The field counter-rotates by this, which is what keeps the stage where it was: turn ninety
     * degrees left and a source that was in front of you ends up on your right. That inversion is
     * already in [rotate], so whatever drives this passes head yaw straight in without negating it.
     *
     * Stepping it once per buffer is not enough. That is a step in the gain applied to most of the
     * harmonics, fifty times a second, which is a buzz rather than a soundstage, so [queueInput]
     * ramps it across the buffer instead.
     */
    @Volatile
    var headYawRadians: Float = 0f

    /**
     * The whole head orientation, as a quaternion in the tracker's own frame: X out the right
     * ear, Y out the nose, Z out the top of the head. Null falls back to [headYawRadians].
     *
     * Separate from the yaw because most of the time there is nothing to supply it, and a
     * renderer that only turns is both cheaper and, with a dummy head's ears rather than the
     * listener's, more convincing than one that also tilts.
     */
    @Volatile
    var headPose: FloatArray? = null

    /**
     * Carry the harmonics that only exist off the horizontal plane.
     *
     * Six of the sixteen are identically zero for anything at ear level, so a renderer that only
     * turns can drop them and does. The moment the head tilts, energy moves into exactly those
     * six, and without them a nod would quietly attenuate the soundstage instead of moving it.
     * Read when the format is configured, because it changes how much there is to convolve.
     */
    @Volatile
    var fullSphere: Boolean = false

    /**
     * How far apart the two virtual loudspeakers stand, in degrees either side of centre.
     *
     * Thirty is the angle a stereo mix is made for and the honest default. Wider is not more
     * correct, but it separates more, and at first order it was the only lever there was. At third
     * order the blur is narrow enough that thirty should already hold apart.
     */
    @Volatile
    var stageWidthDegrees: Float = DEFAULT_STAGE_WIDTH

    /**
     * How much of the rendering is heard, from 0 to 1. At 1 it is all there is, as it always was.
     * Below that the recording as it came is mixed in, held back by the time the rendering
     * takes and with its low notes turned as the rendering turns them ([turned]), so that the
     * two arrive together and in step. At 0 nothing is left but the recording, that much late.
     * For two channels in: a surround recording is its own speakers or it is nothing.
     */
    @Volatile
    var strength: Float = 1f

    /**
     * Leave the low notes as they came. Below [BASS_HZ] a head hardly shadows a sound, so there
     * is no direction to give a bass, and what the rendering does to it is only take weight off
     * it and spread one channel's bass over both ears. With this on the lows of the recording go
     * round the rendering, in time with it, and the rest goes through.
     */
    @Volatile
    var bassDirect: Boolean = false

    /**
     * How much of a room there is round the two speakers, from 0 to 1. At 0 there is none, as
     * there never was: two speakers in the open air, which no one has ever listened to. With a
     * room, each speaker is also heard off the wall beside it, off the wall across and off the
     * wall behind, a few thousandths of a second late and duller, from where those walls are.
     * That is most of what tells an ear a sound is out in front and not between the ears.
     * The first reflections only: there is no tail yet. For two channels in.
     */
    @Volatile
    var room: Float = 0f

    /**
     * How far out the air round a recording is moved, from 0 to 1. The recording is taken apart
     * ([DirectAmbientSplit]) into the sound itself, which stays with the two speakers, and what
     * its channels do not share, the hall and the reverb, which is given a speaker of its own on
     * each side: where the pair's own stand at next to nothing, at [AIR_OUT_DEGREES] at 1,
     * straight out at the listener's sides. At 0 nothing is taken apart and the frame is as
     * it always was; above it everything comes out one frame of the split late. For two channels in.
     */
    @Volatile
    var ambience: Float = 0f

    // The active harmonics, in the order they are stored. Rebuilt when the ambisonic order
    // changes. Only harmonics that are non-zero in the horizontal plane are carried at all: for a
    // source at zero elevation every harmonic with (n - |m|) odd evaluates to zero, which is six
    // of the sixteen at third order, and convolving those would be six multiplications of nothing.
    private var degree = IntArray(0)
    private var fromDifference = BooleanArray(0)
    private var factor = FloatArray(0)
    private var sourceChannel = IntArray(0)

    /**
     * What each harmonic is worth for each speaker with the head level.
     *
     * The level match and the peak check both have to be answered with the head straight, not
     * wherever it happens to be pointing: turning your head is not allowed to change the volume.
     * Derived from the same encoding the renderer uses rather than from a horizontal shortcut,
     * because the shortcut has no answer for the six harmonics that only exist off that plane.
     */
    private var identityLeft = FloatArray(0)
    private var identityRight = FloatArray(0)

    /** What each harmonic is worth for each speaker right now, and how it moves this buffer. */
    private var encodeLeft = FloatArray(0)
    private var encodeRight = FloatArray(0)
    private var stepLeft = FloatArray(0)
    private var stepRight = FloatArray(0)
    private var speakerAzimuth = 0f
    private val rotated = FloatArray(3)

    /**
     * Flattens the renderer's own tonal colouring, applied to the input before anything else.
     *
     * A virtualiser is supposed to add where a sound is, not change what it sounds like, and this
     * one was doing both: two speakers at thirty degrees summed to roughly three decibels up below
     * three hundred hertz and four and a half down between six and ten kilohertz. Eight decibels
     * of tilt is not a subtle colouration, it is a tone control nobody asked for, and it is why
     * switching this on sounded worse rather than wider.
     *
     * Only the broad shape is corrected. The response is smoothed by a third of an octave before
     * being inverted, so the narrow peaks and notches survive untouched: those are the pinna cues
     * that say where a sound is, and flattening them would remove the effect along with the
     * colouring.
     *
     * On the input rather than folded into each harmonic, because it is the same correction for
     * both ears and every harmonic. Two short convolutions instead of ten longer ones.
     */
    private var correction = FloatArray(0)
    private val leftHistory = FloatArray(CORRECTION_RING)
    private val rightHistory = FloatArray(CORRECTION_RING)
    private var historyIndex = 0
    private val targetLeft = FloatArray(16)
    private val targetRight = FloatArray(16)

    // The recording as it came, for [strength] and [bassDirect]: held back by [delaySamples], the
    // time a sound takes through the rendering, and split at [BASS_HZ] when the bass is to be
    // left alone. Three crossover filters of two sections each, for two channels: the lows and
    // the highs of the recording held back, and the highs of what goes into the rendering.
    private val dryLeft = FloatArray(RING)
    private val dryRight = FloatArray(RING)
    private var dryIndex = 0
    private var delaySamples = 0
    private var roomShare = 0f
    private var dryTurn = Float.NaN
    private val turnState = FloatArray(4)
    private var mixedBefore = false
    private var directBefore = false

    // What the buffer in hand is processed with: read once a buffer, used by every frame of it.
    private var frameShare = 1f
    private var frameDirect = false
    private var frameMixed = false
    private var frameAir = false

    /**
     * The two ears of the frame just made: where [render], [blend] and [decode] leave their
     * answer. Those three are ordinary functions and not inline ones on purpose. Inlined, each
     * call copied the whole of the convolution into [queueInput], six times over, and a method
     * that size is one the Java runtime the tests run on declines to compile at all: the suite's
     * renderer tests went from half a minute to five. One call a frame costs nothing beside the
     * two and a half thousand multiplications it leads to.
     */
    private val ears = FloatArray(2)
    private val lowPass = FloatArray(5)
    private val highPass = FloatArray(5)
    private var crossoverRate = -1
    private val lowState = FloatArray(8)
    private val highState = FloatArray(8)
    private val inputState = FloatArray(8)

    // The room: for each speaker its three reflections ([ROOM]), each a source of its own in the
    // field, fed from what the speaker played a little earlier. Walked across a buffer as the
    // speakers are, since they turn with the head as the speakers do. A wall is given the middle
    // of the sound only ([roomBand]), and that same middle is what the speakers themselves are
    // turned down in to make up for it ([roomKeep]): four filters, the walls' two channels and
    // the speakers' two, each a high-pass section and a pole.
    private val roomLeft = FloatArray(ROOM_RING)
    private val roomRight = FloatArray(ROOM_RING)
    private var roomIndex = 0
    private var roomKeep = 1f
    private var roomWeight = Float.NaN
    private var roomDull = 0f
    private val roomHigh = FloatArray(5)
    private val roomState = FloatArray(12)
    private var roomEncode = FloatArray(0)
    private var roomTarget = FloatArray(0)
    private var roomStep = FloatArray(0)
    private val roomDelay = IntArray(ROOM.size / 3)
    private var roomRate = -1

    // The air round a recording, moved out ([ambience]): the split, what it calls ambient in
    // this frame, the last of that for its own tone filter to run over, and where the air's two
    // speakers stand and what each harmonic is worth there, walked with the head as the pair is.
    private val apart = DirectAmbientSplit()
    private var airShare = 0f
    private var airLeft = 0f
    private var airRight = 0f
    private val airHeldLeft = FloatArray(CORRECTION_RING)
    private val airHeldRight = FloatArray(CORRECTION_RING)
    private val airState = FloatArray(8)
    private var airEncode = FloatArray(0)
    private var airTarget = FloatArray(0)
    private var airStep = FloatArray(0)
    private var airAzimuth = Float.NaN
    private var airTone = FloatArray(0)
    private var airIndex = 0

    // Surround in. A recording in 5.1 or 7.1 is its own loudspeakers: each channel is one more
    // source in the field, standing where its speaker would, and from the harmonics on the
    // renderer is the same. Kept beside the pair's own arrays and not in place of them, so that
    // two channels in go through exactly the arithmetic they always did.
    private var surroundChannels = 0
    private var surroundWidth = Float.NaN
    private var surroundAzimuth = FloatArray(0)
    private var surroundEncode = FloatArray(0)
    private var surroundTarget = FloatArray(0)
    private var surroundStep = FloatArray(0)
    private var surroundHistory = FloatArray(0)
    private val surroundFrame = FloatArray(SurroundLayout.MOST)
    private val surroundToned = FloatArray(SurroundLayout.MOST)
    private val surroundOne = FloatArray(16)
    private val poseBuffer = FloatArray(4)

    /**
     * One frame's harmonics, on their way into the rings.
     *
     * A field rather than a local because [queueInput] runs on the audio thread for every buffer
     * the sink pulls, fifty or more times a second, and a fresh array there was garbage made at the
     * audio rate for nothing: every other scratch array in here was already a field, and this one
     * had simply been missed. Sized in [buildLayout], the only place [active] changes, so it is
     * never shorter than the loop that fills it. Nothing carries over from one frame to the next,
     * because [render] writes every slot it reads before reading it.
     */
    private var harmonics = FloatArray(0)

    /** Indices into the active list for each rotating pair, and the degree it turns by. */
    private var pairSin = IntArray(0)
    private var pairCos = IntArray(0)
    private var pairDegree = IntArray(0)

    /** Filters and history, flattened: slot i tap t at i * taps + t, sample at i * RING + n. */
    private var filters = FloatArray(0)
    private var rings = FloatArray(0)
    private var active = 0
    private var taps = 0
    private var writeIndex = 0

    /**
     * How many frames have gone through, so the delay to the ear can be measured rather than
     * guessed.
     *
     * Everything this processor does happens before the sink's own buffer, the Bluetooth encoder
     * and the headphones, and the total of those is what the head tracking has to predict past.
     * Comparing this against the position the player reports, which is derived from what the
     * audio device says it has actually played, gives that total directly. Volatile because it is
     * written on the audio thread and read on the main one.
     */
    @Volatile
    var framesProcessed: Long = 0L
        private set

    /** Normalisation, worked out from the filters themselves. See [computeGain]. */
    private var gain = 1f

    private var appliedOrder = -1
    private var appliedSphere = false
    private var appliedWidth = Float.NaN
    private var appliedRate = -1

    /**
     * Where the rotation has actually reached, as opposed to where the head is.
     *
     * Audio thread only. Deliberately untouched by [clearTails]: a seek has to drop the
     * convolution tails, because the old audio must not follow the listener to the new position,
     * but the head has not moved, so the stage must not either.
     */
    private var rampYaw = 0f

    /**
     * Which harmonics exist in the horizontal plane, and what each one is worth.
     *
     * SN3D, the AmbiX convention these filters were made for, so the omnidirectional component
     * carries no attenuation. The constants are the spherical harmonics evaluated at zero
     * elevation; they were derived rather than typed, and the first-order row reproduces the plain
     * W, Y, X encoding exactly, which is what says the derivation is right.
     */
    private fun buildLayout(order: Int, sphere: Boolean) {
        if (order == appliedOrder && sphere == appliedSphere) return
        appliedOrder = order
        appliedSphere = sphere

        val table = when {
            order >= 3 && sphere -> ORDER_3_FULL_LAYOUT
            order >= 3 -> ORDER_3_LAYOUT
            else -> ORDER_1_LAYOUT
        }
        active = table.size / 3
        degree = IntArray(active)
        fromDifference = BooleanArray(active)
        factor = FloatArray(active)
        sourceChannel = IntArray(active)
        if (harmonics.size != active) harmonics = FloatArray(active)
        for (i in 0 until active) {
            val acn = table[i * 3].toInt()
            val m = table[i * 3 + 1].toInt()
            sourceChannel[i] = acn
            degree[i] = m
            // Mirroring the field about the median plane flips the sign of every harmonic with a
            // negative degree and leaves the rest alone, which is why one ear's filters are enough
            // for both, and it is also exactly the harmonics that are driven by the difference
            // between the channels rather than their sum.
            fromDifference[i] = m < 0
            factor[i] = table[i * 3 + 2]
        }

        // Harmonics pair up by degree: the sine and cosine of the same n and |m| rotate into each
        // other. Everything with degree zero is unchanged by a horizontal rotation.
        val sinIdx = ArrayList<Int>()
        val cosIdx = ArrayList<Int>()
        val degs = ArrayList<Int>()
        for (i in 0 until active) {
            if (degree[i] <= 0) continue
            val partner = (0 until active).firstOrNull {
                degree[it] == -degree[i] && sourceChannel[it] + degree[i] * 2 == sourceChannel[i]
            } ?: continue
            cosIdx.add(i)
            sinIdx.add(partner)
            degs.add(degree[i])
        }
        pairCos = cosIdx.toIntArray()
        pairSin = sinIdx.toIntArray()
        pairDegree = degs.toIntArray()

        appliedWidth = Float.NaN
        appliedRate = -1
    }

    /**
     * Move the virtual speakers, and renormalise so doing so is not also a volume change.
     *
     * Both speakers at once, so each harmonic sees the sum or the difference of the two channels
     * rather than each separately: the right speaker sits at minus the left one's angle, and
     * cosine does not care about that sign while sine flips with it.
     */
    private fun applyWidth(degrees: Float) {
        val clamped = degrees.coerceIn(MIN_STAGE_WIDTH, MAX_STAGE_WIDTH)
        val widthChanged = clamped != appliedWidth
        if (!widthChanged && identityLeft.size == active) return
        appliedWidth = clamped
        speakerAzimuth = clamped * PI.toFloat() / 180f
        identityLeft = FloatArray(active)
        identityRight = FloatArray(active)
        encodeLeft = FloatArray(active)
        encodeRight = FloatArray(active)
        stepLeft = FloatArray(active)
        stepRight = FloatArray(active)
        encodeSpeakers(IDENTITY, identityLeft, identityRight)
        System.arraycopy(identityLeft, 0, encodeLeft, 0, active)
        System.arraycopy(identityRight, 0, encodeRight, 0, active)
        // Only when the filters belong to this layout. onConfigure rebuilds the layout first and the
        // filters after, and applyWidth runs in between, so on a processor that has been configured
        // before, taps is still set while filters is still sized for the old channel count. Turning
        // on 3D head tracking during playback grows the layout from ten channels to sixteen, and
        // computeGain then read sixteen channels' worth out of an array holding ten:
        // ArrayIndexOutOfBoundsException on the audio thread. buildFilters computes the gain itself
        // once it has built filters of the right size, so skipping it here loses nothing.
        if (taps > 0 && filters.size == active * taps) {
            // The broad tone correction is the inverse of the mono path's response for the width
            // it was built at, taken from identityLeft/Right above. A width change alone never
            // touches buildFilters (the sample rate has not changed), so without this the listener
            // keeps hearing the new width through the old width's inverse until a format change or
            // a restart happens to rebuild it. Only on an actual change, since this runs on the
            // audio thread and a DFT over the filter length is not free to repeat every buffer.
            if (widthChanged) buildCorrection(appliedRate)
            gain = computeGain()
        }
    }

    /**
     * Where the two speakers end up, and what each harmonic is worth there.
     *
     * The whole of the head tracking, in one place. Rather than rotating the sound field, which
     * needs a different matrix for every order and is fiddly to get right, the two virtual
     * speakers are moved by the inverse of the head's rotation and re-encoded where they land.
     * The result is identical and exact at any angle, because there are only ever two sources and
     * moving two vectors is cheap, and it handles tilting for free: nothing about it assumes the
     * speakers stayed at ear level.
     */
    private fun encodeSpeakers(pose: FloatArray, intoLeft: FloatArray, intoRight: FloatArray) {
        // The field turns the opposite way to the head, so the conjugate. The tracker's axes are
        // not the ambisonic ones either: its Y is out of the nose where ambisonic X is the front,
        // and its X is out of the right ear where ambisonic Y is the left. Only up is shared.
        val w = pose[0]
        val rx = -pose[2]
        val ry = pose[1]
        val rz = -pose[3]

        val c = cos(speakerAzimuth)
        val s = sin(speakerAzimuth)
        rotate(w, rx, ry, rz, c, s, 0f, rotated)
        shInto(rotated, intoLeft)
        rotate(w, rx, ry, rz, c, -s, 0f, rotated)
        shInto(rotated, intoRight)
    }

    /** A unit vector through a quaternion, the long way round so no temporaries are allocated. */
    private fun rotate(w: Float, qx: Float, qy: Float, qz: Float, x: Float, y: Float, z: Float, into: FloatArray) {
        val tx = 2f * (qy * z - qz * y)
        val ty = 2f * (qz * x - qx * z)
        val tz = 2f * (qx * y - qy * x)
        into[0] = x + w * tx + (qy * tz - qz * ty)
        into[1] = y + w * ty + (qz * tx - qx * tz)
        into[2] = z + w * tz + (qx * ty - qy * tx)
    }

    /**
     * Real spherical harmonics to third order at one direction, SN3D, ambisonic axes.
     *
     * Written out rather than recursed. Sixteen expressions are easier to check against a
     * reference than a recurrence is, and at zero elevation they reduce to exactly the horizontal
     * table this used before, which is the check that matters.
     */
    private fun shInto(d: FloatArray, into: FloatArray) {
        val x = d[0]
        val y = d[1]
        val z = d[2]
        for (i in 0 until active) {
            into[i] = when (sourceChannel[i]) {
                0 -> 1f
                1 -> y
                2 -> z
                3 -> x
                4 -> SQRT3 * x * y
                5 -> SQRT3 * y * z
                6 -> (3f * z * z - 1f) * 0.5f
                7 -> SQRT3 * x * z
                8 -> SQRT3 * 0.5f * (x * x - y * y)
                9 -> SQRT58 * y * (3f * x * x - y * y)
                10 -> SQRT15 * x * y * z
                11 -> SQRT38 * y * (5f * z * z - 1f)
                12 -> z * (5f * z * z - 3f) * 0.5f
                13 -> SQRT38 * x * (5f * z * z - 1f)
                14 -> SQRT15 * 0.5f * z * (x * x - y * y)
                else -> SQRT58 * x * (x * x - 3f * y * y)
            }
        }
    }

    /**
     * How much to scale the output so switching this on is not also turning it up.
     *
     * The trap the upmix fell into: a rendering that is six decibels louder wins every comparison
     * it is given, whatever it does to the imaging. So the gain is derived rather than guessed. A
     * centred mono signal has no difference component, so only the harmonics driven by the sum
     * survive, and together they give one effective impulse response per ear; its energy is what
     * that signal's level gets multiplied by, so dividing by the square root of that energy sends
     * mono in and mono out at the same level, at any rate, order or width.
     */
    private fun computeGain(): Float {
        // Whatever sends this here has changed what the room and the air are weighed against.
        roomWeight = Float.NaN
        airTone = FloatArray(0)
        var energy = 0.0
        val mono = FloatArray(taps)
        for (t in 0 until taps) {
            var v = 0f
            for (i in 0 until active) {
                // Both speakers carrying the same signal. The harmonics of negative degree cancel
                // between them, which is why a centred source reaches both ears identically.
                v += (identityLeft[i] + identityRight[i]) * filters[i * taps + t]
            }
            mono[t] = v
        }
        // Through the tonal correction as well, since that is what the ear actually receives and
        // matching the level of something the listener never hears would be matching nothing.
        val effective = if (correction.isEmpty()) mono else FloatArray(taps + correction.size - 1).also { out ->
            for (a in mono.indices) for (b in correction.indices) out[a + b] += mono[a] * correction[b]
        }
        for (v in effective) energy += v.toDouble() * v
        // Where a centred sound comes out strongest is when it comes out: what the recording as
        // it came has to be held back by to arrive with it.
        var peak = 0
        for (t in effective.indices) if (abs(effective[t]) > abs(effective[peak])) peak = t
        delaySamples = peak.coerceAtMost(MASK)
        // The rendering does not only hold a sound back, it turns its low notes round: against
        // where its peak is, half a turn at the very bottom and a quarter by 150 Hz, which is
        // what a first-order all-pass turned over does. A recording held back by the peak alone
        // and mixed with the rendering therefore cancelled it down there: at half strength a
        // note of 40 Hz came out thirteen decibels down. So the recording is given the same
        // turn ([turned]): how far the rendering has turned a note of [BASS_HZ] says where
        // that all-pass has its corner.
        val rate = if (appliedRate > 0) appliedRate else 48000
        val w = 2.0 * PI * BASS_HZ / rate
        var re = 0.0
        var im = 0.0
        for (t in effective.indices) {
            re += effective[t] * cos(w * t)
            im -= effective[t] * sin(w * t)
        }
        val ahead = atan2(im, re) + w * peak
        val turn = atan2(sin(ahead), cos(ahead))
        dryTurn = if (turn > 0.2 && turn < PI - 0.05) {
            val corner = BASS_HZ / tan((PI - turn) / 2.0)
            val k = tan(PI * corner / rate)
            ((k - 1.0) / (k + 1.0)).toFloat()
        } else {
            // Not turned, or not in the way this can follow: the recording as it is.
            Float.NaN
        }
        if (energy <= 0.0) return 1f
        return (1.0 / sqrt(energy)).toFloat()
    }

    /**
     * Keep the rare peak under full scale without making everything quieter.
     *
     * Any head-related transfer function has a pronounced peak where the ear canal resonates,
     * around three to five kilohertz, and this set is no exception: a full scale centred sine
     * there comes out at nearly twice full scale. It is a real property of a real ear, not a bug,
     * and it is the same at first order and third, so it is not what the extra harmonics changed.
     *
     * The obvious fix, backing the gain off until nothing can ever exceed full scale, costs almost
     * six decibels to solve a problem that only occurs when the music is both loud and
     * concentrated at one frequency. That trade is wrong: it makes every track quieter to spare
     * the occasional one, and a renderer that is quieter than the signal it replaced loses every
     * comparison for the wrong reason.
     *
     * So the level match stands and the top is rounded off instead. Below the knee this is
     * arithmetically identity, so ordinary listening passes through untouched; above it the curve
     * approaches full scale and never reaches it. Vastly preferable to the hard clamp underneath,
     * which turns one loud moment into splatter across the whole spectrum.
     */
    private fun softClip(v: Float): Float {
        val a = abs(v)
        if (a <= KNEE) return v
        val over = (a - KNEE) / (1f - KNEE)
        val shaped = KNEE + (1f - KNEE) * (over / (1f + over))
        return if (v < 0f) -shaped else shaped
    }

    /**
     * The measured filters, moved to the rate the stream is actually running at.
     *
     * Necessary rather than nice: the set is measured at 48 kHz, which is what Opus decodes to,
     * but YouTube's AAC is 44.1 kHz and a local file is whatever it is. Refusing those would mean
     * a setting that works on some songs and silently does nothing on others, which is worse than
     * either working or being off. Windowed sinc interpolation, so this is band limited rather
     * than a nearest-sample smear; it runs once per format change, never per sample.
     */
    private fun buildFilters(rate: Int): Boolean {
        if (rate <= 0) return false
        if (rate == appliedRate) return true
        val source = if (appliedOrder >= 3) SadieHrir3.H else SadieHrir.H
        val sourceTaps = if (appliedOrder >= 3) SadieHrir3.TAPS else SadieHrir.TAPS
        val ratio = rate.toDouble() / SadieHrir.SAMPLE_RATE
        val length = ceil(sourceTaps * ratio).toInt()
        if (length < 8 || length > MAX_TAPS) return false

        appliedRate = rate
        taps = min(length, KEEP_TAPS)
        filters = FloatArray(active * taps)
        for (i in 0 until active) {
            resampleInto(source, sourceChannel[i] * sourceTaps, sourceTaps, ratio, i * taps)
        }
        rings = FloatArray(active * RING)
        buildCorrection(rate)
        gain = computeGain()
        clearTails()
        return true
    }

    /**
     * The inverse of the mono path's broad magnitude response, as a short symmetric filter.
     *
     * Direct transforms rather than an FFT. This runs once per format change, and again on the
     * audio thread whenever Stage width moves, at most once per buffer; over at most a hundred and
     * sixty taps the simple version is cheap enough for both, and there is no library to pull in.
     */
    private fun buildCorrection(sampleRate: Int) {
        if (taps <= 0 || active == 0) {
            correction = FloatArray(0)
            return
        }
        // The response of a centred signal, which is what the listener hears as the tone.
        val mono = FloatArray(taps)
        for (t in 0 until taps) {
            var v = 0f
            for (i in 0 until active) v += (identityLeft[i] + identityRight[i]) * filters[i * taps + t]
            mono[t] = v
        }
        val smoothed = smoothed(strengths(mono), sampleRate)

        // Invert, hold the extremes flat, normalise to unity on average and limit how far it may
        // reach. An unlimited inverse would try to undo a deep notch and produce a howl.
        val nyquist = sampleRate / 2.0
        val inverse = DoubleArray(CORRECTION_BINS)
        var logSum = 0.0
        for (k in 0 until CORRECTION_BINS) {
            inverse[k] = 1.0 / max(smoothed[heldFlat(k, nyquist)], 1e-9)
            logSum += ln(inverse[k])
        }
        val mean = exp(logSum / CORRECTION_BINS)
        val limit = 10.0.pow(CORRECTION_LIMIT_DB / 20.0)
        for (k in 0 until CORRECTION_BINS) {
            inverse[k] = (inverse[k] / mean).coerceIn(1.0 / limit, limit)
        }
        correction = shaped(inverse)
    }

    /**
     * How strong [response] is at each of [CORRECTION_BINS] frequencies, from none up to half the rate.
     *
     * Every angle here is a whole number of steps of [TURN], so the cosines come out of a table.
     * It matters because the air's tone is made from four of these each time its slider moves a
     * step, on the audio thread: worked out one cosine at a time that was a few hundredths of a
     * second a step, which is longer than the buffer it holds up.
     */
    private fun strengths(response: FloatArray): DoubleArray {
        val mag = DoubleArray(CORRECTION_BINS)
        for (k in 0 until CORRECTION_BINS) {
            var re = 0.0
            var im = 0.0
            for (t in response.indices) {
                val a = k * t
                re += response[t] * TURN[a and TURN_MASK]
                im -= response[t] * TURN[(a - CORRECTION_BINS / 2) and TURN_MASK]
            }
            mag[k] = sqrt(re * re + im * im)
        }
        return mag
    }

    /**
     * [mag] with a third of an octave either side run together, in log frequency, so that what
     * is made from it follows the tilt and not the fine structure.
     */
    private fun smoothed(mag: DoubleArray, sampleRate: Int): DoubleArray {
        val smoothed = DoubleArray(CORRECTION_BINS)
        // The rate is passed in rather than read from inputAudioFormat, which BaseAudioProcessor
        // does not populate until flush: during onConfigure it is still unset, so every frequency
        // worked out from it was nonsense and the correction was shaped against nothing.
        val nyquist = sampleRate / 2.0
        val log = DoubleArray(CORRECTION_BINS) { ln(max(mag[it], 1e-9)) }
        for (k in 0 until CORRECTION_BINS) {
            val f = k * nyquist / CORRECTION_BINS
            if (f <= 0.0) { smoothed[k] = mag.getOrElse(1) { mag[0] }; continue }
            val lo = f / SIXTH_OCTAVE
            val hi = f * SIXTH_OCTAVE
            var sum = 0.0
            var n = 0
            for (j in 0 until CORRECTION_BINS) {
                val fj = j * nyquist / CORRECTION_BINS
                if (fj in lo..hi) { sum += log[j]; n++ }
            }
            smoothed[k] = if (n > 0) exp(sum / n) else max(mag[k], 1e-9)
        }
        return smoothed
    }

    /** The frequency a correction takes its value from at [k]: its own, but nothing under or over the band it is trusted in. */
    private fun heldFlat(k: Int, nyquist: Double): Int {
        val f = k * nyquist / CORRECTION_BINS
        return when {
            f < CORRECTION_LOW_HZ -> (CORRECTION_LOW_HZ / nyquist * CORRECTION_BINS).toInt()
            f > CORRECTION_HIGH_HZ -> (CORRECTION_HIGH_HZ / nyquist * CORRECTION_BINS).toInt()
            else -> k
        }.coerceIn(0, CORRECTION_BINS - 1)
    }

    /** A short filter that is worth [gains] at each of [CORRECTION_BINS] frequencies. */
    private fun shaped(gains: DoubleArray): FloatArray {
        // Symmetric, so it delays both ears identically and the interaural timing is untouched.
        val half = CORRECTION_TAPS / 2
        val out = FloatArray(CORRECTION_TAPS)
        for (t in 0 until CORRECTION_TAPS) {
            val d = t - half
            var v = gains[0]
            for (k in 1 until CORRECTION_BINS) v += 2.0 * gains[k] * TURN[(k * d) and TURN_MASK]
            val window = 0.5 - 0.5 * cos(2.0 * PI * t / (CORRECTION_TAPS - 1))
            out[t] = (v / (2 * CORRECTION_BINS) * window).toFloat()
        }
        return out
    }

    /** Half a Hann over the last few taps, so a truncated filter does not end in a step. */
    private fun fade(t: Int): Float {
        val from = taps - FADE_TAPS
        if (t < from) return 1f
        return (0.5 * (1.0 + cos(PI * (t - from) / FADE_TAPS))).toFloat()
    }

    private fun resampleInto(source: ShortArray, offset: Int, sourceTaps: Int, ratio: Double, into: Int) {
        if (ratio == 1.0) {
            for (t in 0 until taps) filters[into + t] = source[offset + t] / 32768f * fade(t)
            return
        }
        // Below unity the output rate is the lower one, so the filter has to be band limited to
        // the new Nyquist. Scaling the amplitude by the same factor keeps the sinc's area at one.
        val cutoff = min(1.0, ratio)
        for (m in 0 until taps) {
            val at = m / ratio
            val centre = floor(at).toInt()
            var acc = 0.0
            for (k in centre - HALF_WIDTH + 1..centre + HALF_WIDTH) {
                if (k < 0 || k >= sourceTaps) continue
                val x = at - k
                acc += (source[offset + k] / 32768.0) * cutoff * sinc(cutoff * x) * window(x)
            }
            filters[into + m] = acc.toFloat() * fade(m)
        }
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    /** Hann, over the interpolation half width. Keeps the truncation from ringing. */
    private fun window(x: Double): Double {
        if (abs(x) >= HALF_WIDTH) return 0.0
        return 0.5 * (1.0 + cos(PI * x / HALF_WIDTH))
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!enabled) return AudioProcessor.AudioFormat.NOT_SET
        val channels = inputAudioFormat.channelCount
        if (channels != 2 && (!Unreleased.SURROUND_IN || SurroundLayout.azimuths(channels, DEFAULT_STAGE_WIDTH) == null)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        buildLayout(if (thirdOrder) 3 else 1, fullSphere)
        applyWidth(stageWidthDegrees)
        if (!buildFilters(inputAudioFormat.sampleRate)) return AudioProcessor.AudioFormat.NOT_SET
        applyWidth(stageWidthDegrees)
        // Once a recording, and the one line that says a surround recording was taken up and not
        // handed on to the phone to fold down.
        if (channels != 2) Log.d(TAG, "$channels channels in at ${inputAudioFormat.sampleRate} Hz: rendered as their own speakers, two ears out")
        // Stereo in, stereo out: unlike the upmix this changes no format, only the samples. Six or
        // eight channels in are a recording's own loudspeakers, and come out as the two ears.
        return if (channels == 2) inputAudioFormat
        else AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        val channels = format.channelCount
        // Two ears out, whatever came in.
        val out = replaceOutputBuffer(frames * (format.bytesPerFrame / channels) * 2)
        applyWidth(stageWidthDegrees)

        // One volatile read per buffer, and the whole head orientation resolved here rather than
        // per sample. Where the speakers have to be is a question about the head; where each
        // harmonic is worth what follows from that, and neither changes within a buffer.
        val pose = headPose
        if (pose != null && pose.size == 4) {
            poseBuffer[0] = pose[0]; poseBuffer[1] = pose[1]
            poseBuffer[2] = pose[2]; poseBuffer[3] = pose[3]
            rampYaw = 0f
        } else {
            // Yaw only: the same rotation expressed as a quaternion about the head's up axis, so
            // there is one encoding path rather than two.
            val target = headYawRadians
            val start = rampYaw
            var delta = wrapPi(target - start)
            // Nothing voluntary turns this fast. Anything that does is a dropped report or a
            // tracker that re-referenced itself, and spreading it over a buffer beats taking it
            // whole.
            val cap = MAX_YAW_RATE * frames / format.sampleRate
            delta = delta.coerceIn(-cap, cap)
            rampYaw = wrapPi(start + delta)
            val half = rampYaw * 0.5f
            poseBuffer[0] = cos(half); poseBuffer[1] = 0f
            poseBuffer[2] = 0f; poseBuffer[3] = sin(half)
        }

        if (channels != 2) {
            renderSurround(inputBuffer, out, frames, channels, format.encoding)
            inputBuffer.position(inputBuffer.limit())
            out.flip()
            framesProcessed += frames.toLong()
            return
        }

        // All of the rendering and nothing else is the frame as it always was. Anything less, or
        // the bass left alone, goes through [blend], which is that frame and the recording beside it.
        val share = strength.coerceIn(0f, 1f)
        val direct = bassDirect
        val mixed = share < 1f || direct
        if (direct) buildCrossover(format.sampleRate)
        // Brought back in the middle of a song, the recording beside the rendering starts from
        // nothing, as the room and the air do: not from what it held when it was last there.
        if (mixed && !mixedBefore) {
            Arrays.fill(dryLeft, 0f)
            Arrays.fill(dryRight, 0f)
            Arrays.fill(turnState, 0f)
        }
        if (direct && !directBefore) {
            Arrays.fill(lowState, 0f); Arrays.fill(highState, 0f); Arrays.fill(inputState, 0f); Arrays.fill(airState, 0f)
        }
        mixedBefore = mixed
        directBefore = direct

        // Walked across the buffer rather than stepped at the seam. A step here is a step in the
        // gain applied to every harmonic at once, fifty times a second, which is a buzz.
        encodeSpeakers(poseBuffer, targetLeft, targetRight)
        val inv = 1f / frames
        for (i in 0 until active) {
            stepLeft[i] = (targetLeft[i] - encodeLeft[i]) * inv
            stepRight[i] = (targetRight[i] - encodeRight[i]) * inv
        }
        val roomBefore = roomShare > 0f
        roomShare = room.coerceIn(0f, 1f)
        if (roomShare > 0f) {
            // Brought back in the middle of a song, the walls must not send back what the
            // speakers played when the room was last there.
            if (!roomBefore) {
                Arrays.fill(roomLeft, 0f)
                Arrays.fill(roomRight, 0f)
                Arrays.fill(roomState, 0f)
            }
            layOutRoom(format.sampleRate)
            if (roomWeight.isNaN()) roomWeight = weighRoom(format.sampleRate)
            roomKeep = 1f / sqrt(1f + roomShare * roomShare * roomWeight)
            encodeRoom(poseBuffer, roomTarget)
            for (k in roomStep.indices) roomStep[k] = (roomTarget[k] - roomEncode[k]) * inv
        }
        val airBefore = airShare > 0f
        airShare = ambience.coerceIn(0f, 1f)
        val air = airShare > 0f
        if (air) {
            // The same for the split, which holds a whole frame of whatever it was last given.
            if (!airBefore) {
                apart.reset()
                Arrays.fill(airHeldLeft, 0f)
                Arrays.fill(airHeldRight, 0f)
                Arrays.fill(airState, 0f)
            }
            layOutAir(format.sampleRate)
            encodeAir(poseBuffer, airTarget)
            for (k in airStep.indices) airStep[k] = (airTarget[k] - airEncode[k]) * inv
        }

        frameShare = share
        frameDirect = direct
        frameMixed = mixed
        frameAir = air
        when (format.encoding) {
            C.ENCODING_PCM_16BIT -> repeat(frames) {
                frame(inputBuffer.short / 32768f, inputBuffer.short / 32768f)
                out.putShort(toPcm16(ears[0]))
                out.putShort(toPcm16(ears[1]))
            }

            C.ENCODING_PCM_FLOAT -> repeat(frames) {
                frame(inputBuffer.float, inputBuffer.float)
                out.putFloat(ears[0])
                out.putFloat(ears[1])
            }
        }
        for (i in 0 until active) {
            encodeLeft[i] = targetLeft[i]
            encodeRight[i] = targetRight[i]
        }

        inputBuffer.position(inputBuffer.limit())
        out.flip()
        framesProcessed += frames.toLong()
    }

    /**
     * One frame of a stereo recording, by whatever this buffer's settings say: taken apart first
     * if the air is moved out, then mixed with the recording or rendered alone. The two ears are
     * left in [ears].
     */
    private fun frame(left: Float, right: Float) {
        var l = left
        var r = right
        if (frameAir) {
            apart.process(l, r)
            l = apart.directLeft; r = apart.directRight
            airLeft = apart.ambientLeft; airRight = apart.ambientRight
        }
        if (frameMixed) blend(l, r, frameShare, frameDirect) else render(l, r)
    }

    /**
     * The end of a song. With the air moved out, the split still holds a whole frame of the
     * recording that it has not given back: it comes out now. Without this the last 21
     * thousandths of a second of every song were lost, since the sink ends the stream and
     * starts the chain afresh at each change of song.
     */
    override fun onQueueEndOfStream() {
        val format = inputAudioFormat
        if (!frameAir || format.channelCount != 2) return
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        if (!float && format.encoding != C.ENCODING_PCM_16BIT) return
        // Nothing moves any more: the speakers, the walls and the air stay where the last buffer left them.
        Arrays.fill(stepLeft, 0f); Arrays.fill(stepRight, 0f)
        Arrays.fill(roomStep, 0f); Arrays.fill(airStep, 0f)
        val frames = DirectAmbientSplit.DELAY
        val out = replaceOutputBuffer(frames * format.bytesPerFrame)
        repeat(frames) {
            frame(0f, 0f)
            if (float) {
                out.putFloat(ears[0]); out.putFloat(ears[1])
            } else {
                out.putShort(toPcm16(ears[0])); out.putShort(toPcm16(ears[1]))
            }
        }
        out.flip()
        framesProcessed += frames.toLong()
    }

    /**
     * One frame: encode to B-format, rotate, convolve, decode to two ears.
     *
     * The decode exploits the symmetry the filters were measured for. Mirroring a field about the
     * median plane flips the sign of every harmonic of negative degree and leaves the rest alone,
     * so the two ears share everything else: the harmonics of degree zero or more sum to something
     * common to both, and the negative ones are added for the left ear and subtracted for the
     * right. One convolution per harmonic rather than two, for exactly the same result.
     * The two ears are left in [ears].
     */
    private fun render(rawL: Float, rawR: Float) {
        var l = rawL
        var r = rawR
        val tone = correction
        if (tone.isNotEmpty()) {
            val h = historyIndex
            leftHistory[h] = rawL
            rightHistory[h] = rawR
            var accL = 0f
            var accR = 0f
            for (t in tone.indices) {
                val k = (h - t) and CORRECTION_MASK
                accL += leftHistory[k] * tone[t]
                accR += rightHistory[k] * tone[t]
            }
            historyIndex = (h + 1) and CORRECTION_MASK
            l = accL
            r = accR
        }
        val room = roomShare > 0f
        if (room) {
            // The walls are given the recording as it came, not as it was toned for the pair: that
            // tone undoes what two speakers do to a sound between them, and a wall is one place.
            // Late by what the toning takes, so a reflection still comes when its wall says.
            var wallL = rawL
            var wallR = rawR
            if (tone.isNotEmpty()) {
                val then = (historyIndex - 1 - CORRECTION_TAPS / 2) and CORRECTION_MASK
                wallL = leftHistory[then]
                wallR = rightHistory[then]
            }
            val at = roomIndex
            roomLeft[at] = roomBand(0, wallL)
            roomRight[at] = roomBand(3, wallR)
            // And the speakers give up, in that same middle, what the walls add to it, so that a
            // room is not also louder. The low notes and the very top, which no wall was given,
            // are left exactly as they were.
            l -= (1f - roomKeep) * roomBand(6, l)
            r -= (1f - roomKeep) * roomBand(9, r)
        }
        for (i in 0 until active) {
            harmonics[i] = l * encodeLeft[i] + r * encodeRight[i]
            encodeLeft[i] += stepLeft[i]
            encodeRight[i] += stepRight[i]
        }
        if (room) reflect()
        if (airShare > 0f) {
            // The air has its own two speakers, and its own tone ([toneAir]) in place of the
            // pair's. That filter is as long as the pair's, so the two stay in step.
            val now = airIndex
            airHeldLeft[now] = airLeft
            airHeldRight[now] = airRight
            airIndex = (now + 1) and CORRECTION_MASK
            var aL = 0f
            var aR = 0f
            val shape = airTone
            for (t in shape.indices) {
                val then = (now - t) and CORRECTION_MASK
                aL += airHeldLeft[then] * shape[t]
                aR += airHeldRight[then] * shape[t]
            }
            var k = active
            for (i in 0 until active) {
                harmonics[i] += aL * airEncode[i] + aR * airEncode[k]
                airEncode[i] += airStep[i]
                airEncode[k] += airStep[k]
                k++
            }
        }
        decode()
    }

    /**
     * The middle of a sound, which is all a wall is given: nothing under [ROOM_FROM_HZ], where
     * three reflections a few thousandths of a second apart would add up at one note and cancel
     * at the next (measured: six decibels off everything between 40 and 80 Hz), and less and
     * less above [ROOM_DULL_HZ], as a wall takes the top off a sound. One high-pass section and
     * one pole, with the three numbers they remember at [from].
     */
    private fun roomBand(from: Int, x: Float): Float {
        val state = roomState
        val y = roomHigh[0] * x + state[from]
        state[from] = roomHigh[1] * x - roomHigh[3] * y + state[from + 1]
        state[from + 1] = roomHigh[2] * x - roomHigh[4] * y
        state[from + 2] += roomDull * (y - state[from + 2])
        return state[from + 2]
    }

    /**
     * The room's part of one frame, added to the harmonics the two speakers gave: what each
     * speaker played a few milliseconds ago, from where a wall would send it back.
     */
    private fun reflect() {
        val at = roomIndex
        roomIndex = (at + 1) and ROOM_MASK

        val walls = roomDelay.size
        for (wall in 0 until walls) {
            val level = ROOM[wall * 3 + 2] * roomShare * roomKeep
            val then = (at - roomDelay[wall]) and ROOM_MASK
            val fromLeft = roomLeft[then] * level
            val fromRight = roomRight[then] * level
            var k = wall * 2 * active
            for (i in 0 until active) {
                harmonics[i] += fromLeft * roomEncode[k] + fromRight * roomEncode[k + active]
                roomEncode[k] += roomStep[k]
                roomEncode[k + active] += roomStep[k + active]
                k++
            }
        }
    }

    /**
     * How strongly one ear hears a source that is worth [encode] in each harmonic: the energy of
     * its response between two frequencies, every octave alike, which is how music is spread and
     * not how an impulse is. The left ear, or with [far] the right; with [toned], through the
     * pair's tone correction as well. For weighing one thing against another when the layout
     * changes, never per buffer.
     */
    private fun heard(encode: FloatArray, toned: Boolean, far: Boolean, rate: Int, fromHz: Double, toHz: Double): Double {
        val response = responseOf(encode, toned, far)
        var sum = 0.0
        for (k in 0 until WEIGH_POINTS) {
            val w = 2.0 * PI * fromHz * (toHz / fromHz).pow(k / (WEIGH_POINTS - 1.0)) / rate
            var re = 0.0
            var im = 0.0
            for (t in response.indices) {
                re += response[t] * cos(w * t)
                im -= response[t] * sin(w * t)
            }
            sum += re * re + im * im
        }
        return sum
    }

    /**
     * What one ear is sent by a source that is worth [encode] in each harmonic: the left ear, or
     * with [far] the right; with [toned], through the pair's tone correction as well.
     */
    private fun responseOf(encode: FloatArray, toned: Boolean, far: Boolean): FloatArray {
        if (taps <= 0 || filters.size != active * taps) return FloatArray(0)
        val plain = FloatArray(taps)
        for (i in 0 until active) {
            val v = if (far && fromDifference[i]) -encode[i] else encode[i]
            if (v == 0f) continue
            for (t in 0 until taps) plain[t] += v * filters[i * taps + t]
        }
        if (!toned || correction.isEmpty()) return plain
        return FloatArray(taps + correction.size - 1).also { out ->
            for (a in 0 until taps) for (b in correction.indices) out[a + b] += plain[a] * correction[b]
        }
    }

    /** What each harmonic is worth for a source at [angle] radians to the left, with the head level. */
    private fun worthAt(angle: Float, into: FloatArray) {
        rotated[0] = cos(angle); rotated[1] = sin(angle); rotated[2] = 0f
        shInto(rotated, into)
    }

    /**
     * How much the walls add to a sound in the middle of the stage, beside what the two speakers
     * give it: what [roomKeep] has to make up for. Derived from the filters as the gain is, and
     * for the same reason: a room that is a decibel louder wins a comparison it has not earned.
     *
     * For each wall, the two speakers' reflections off it reach an ear together, so they are one
     * response; the walls come at different times, so their energies add. Weighed over the band
     * the walls are given. Once a change of rate, order or width.
     */
    private fun weighRoom(rate: Int): Float {
        val centred = FloatArray(active)
        for (i in 0 until active) centred[i] = identityLeft[i] + identityRight[i]
        val pair = heard(centred, toned = true, far = false, rate, ROOM_WEIGH_FROM_HZ, ROOM_WEIGH_TO_HZ)
        if (pair <= 0.0) return 0f

        var weight = 0.0
        for (wall in roomDelay.indices) {
            val angle = ROOM[wall * 3] * PI.toFloat() / 180f
            worthAt(angle, surroundOne)
            for (i in 0 until active) centred[i] = surroundOne[i]
            worthAt(-angle, surroundOne)
            for (i in 0 until active) centred[i] += surroundOne[i]
            weight += ROOM[wall * 3 + 2] * ROOM[wall * 3 + 2] * heard(centred, toned = false, far = false, rate, ROOM_WEIGH_FROM_HZ, ROOM_WEIGH_TO_HZ) / pair
        }
        return weight.toFloat()
    }

    /**
     * Where the air's two speakers stand for this much [ambience], their arrays, and the tone
     * the air is given there. Built when the angle, the layout or what it is toned against is
     * not what they were built for.
     */
    private fun layOutAir(rate: Int) {
        val out = AIR_OUT_DEGREES * PI.toFloat() / 180f
        val angle = speakerAzimuth + airShare * (out - speakerAzimuth)
        val sized = airEncode.size == 2 * active
        if (sized && angle == airAzimuth && airTone.isNotEmpty()) return
        airAzimuth = angle
        airTone = toneAir(rate)
        if (!sized) {
            airEncode = FloatArray(2 * active)
            airTarget = FloatArray(2 * active)
            airStep = FloatArray(2 * active)
            encodeAir(IDENTITY, airEncode)
        }
    }

    /**
     * The filter that makes the air sound, where it now stands, as it did from the pair: as
     * loud and of the same tone, with only its place changed.
     *
     * A sound from the side and behind reaches the ears much duller than one from in front (the
     * ear itself is in the way: measured, twelve decibels down at 4 kHz) and fuller lower down.
     * Left alone, moving the air out would be heard as a muffled reverb before it was heard as a
     * wider one, and the experiment would be judged on its tone. So at each frequency the air is
     * given back what the move takes: what both ears together get of one speaker of the pair,
     * toned as the pair is, over what they get of one speaker where the air's is. Between the
     * two ears nothing is evened out, and that difference is what says where a sound is.
     */
    private fun toneAir(rate: Int): FloatArray {
        val one = FloatArray(active)
        worthAt(speakerAzimuth, one)
        val pairNear = strengths(responseOf(one, toned = true, far = false))
        val pairFar = strengths(responseOf(one, toned = true, far = true))
        worthAt(airAzimuth, one)
        val airNear = strengths(responseOf(one, toned = false, far = false))
        val airFar = strengths(responseOf(one, toned = false, far = true))
        val pair = DoubleArray(CORRECTION_BINS) { sqrt(pairNear[it] * pairNear[it] + pairFar[it] * pairFar[it]) }
        val there = DoubleArray(CORRECTION_BINS) { sqrt(airNear[it] * airNear[it] + airFar[it] * airFar[it]) }
        val from = smoothed(pair, rate)
        val to = smoothed(there, rate)
        val nyquist = rate / 2.0
        val limit = 10.0.pow(CORRECTION_LIMIT_DB / 20.0)
        return shaped(DoubleArray(CORRECTION_BINS) {
            val k = heldFlat(it, nyquist)
            (from[k] / max(to[k], 1e-9)).coerceIn(1.0 / limit, limit)
        })
    }

    /** [encodeSpeakers] for the air's two: the left one and its mirror. */
    private fun encodeAir(pose: FloatArray, into: FloatArray) {
        val w = pose[0]
        val rx = -pose[2]
        val ry = pose[1]
        val rz = -pose[3]
        rotate(w, rx, ry, rz, cos(airAzimuth), sin(airAzimuth), 0f, rotated)
        shInto(rotated, surroundOne)
        System.arraycopy(surroundOne, 0, into, 0, active)
        rotate(w, rx, ry, rz, cos(airAzimuth), -sin(airAzimuth), 0f, rotated)
        shInto(rotated, surroundOne)
        System.arraycopy(surroundOne, 0, into, active, active)
    }

    /** The reflections' delays at this rate, and their arrays for this many harmonics. */
    private fun layOutRoom(rate: Int) {
        val size = roomDelay.size * 2 * active
        if (rate == roomRate && roomEncode.size == size) return
        if (rate != roomRate) {
            // A second-order Butterworth high-pass and one pole, for this rate.
            val w = 2.0 * PI * ROOM_FROM_HZ / rate
            val alpha = sin(w) / (2.0 * sqrt(0.5))
            val c = cos(w)
            val a0 = 1.0 + alpha
            roomHigh[0] = ((1.0 + c) / 2.0 / a0).toFloat(); roomHigh[1] = (-(1.0 + c) / a0).toFloat(); roomHigh[2] = roomHigh[0]
            roomHigh[3] = (-2.0 * c / a0).toFloat(); roomHigh[4] = ((1.0 - alpha) / a0).toFloat()
            roomDull = (1.0 - exp(-2.0 * PI * ROOM_DULL_HZ / rate)).toFloat()
            Arrays.fill(roomState, 0f)
        }
        roomRate = rate
        for (wall in roomDelay.indices) {
            roomDelay[wall] = (ROOM[wall * 3 + 1] * rate / 1000f).roundToInt().coerceIn(1, ROOM_MASK)
        }
        roomEncode = FloatArray(size)
        roomTarget = FloatArray(size)
        roomStep = FloatArray(size)
        encodeRoom(IDENTITY, roomEncode)
    }

    /** [encodeSpeakers] for the reflections: each wall's pair, the left speaker's and its mirror for the right. */
    private fun encodeRoom(pose: FloatArray, into: FloatArray) {
        val w = pose[0]
        val rx = -pose[2]
        val ry = pose[1]
        val rz = -pose[3]
        for (wall in roomDelay.indices) {
            val angle = ROOM[wall * 3] * PI.toFloat() / 180f
            rotate(w, rx, ry, rz, cos(angle), sin(angle), 0f, rotated)
            shInto(rotated, surroundOne)
            System.arraycopy(surroundOne, 0, into, wall * 2 * active, active)
            rotate(w, rx, ry, rz, cos(angle), -sin(angle), 0f, rotated)
            shInto(rotated, surroundOne)
            System.arraycopy(surroundOne, 0, into, wall * 2 * active + active, active)
        }
    }

    /**
     * One frame with the recording beside the rendering. [share] of what is heard is rendered and
     * the rest is the recording as it came, held back to arrive with it. With [direct] the lows
     * are the recording's whatever the share, and it is only the highs that are shared out:
     * the rendering is given the highs alone, so no bass comes through it to be heard twice.
     * The two ears are left in [ears].
     */
    private fun blend(rawL: Float, rawR: Float, share: Float, direct: Boolean) {
        val at = dryIndex
        // With the air taken out of what comes in here, the recording as it came is the two together.
        val air = airShare > 0f
        dryLeft[at] = if (air) rawL + airLeft else rawL
        dryRight[at] = if (air) rawR + airRight else rawR
        val then = (at - delaySamples) and MASK
        val heldL = turned(0, dryLeft[then])
        val heldR = turned(2, dryRight[then])
        dryIndex = (at + 1) and MASK

        if (direct) {
            if (air) {
                airLeft = section(highPass, airState, 0, airLeft)
                airRight = section(highPass, airState, 4, airRight)
            }
            render(section(highPass, inputState, 0, rawL), section(highPass, inputState, 4, rawR))
            val keep = 1f - share
            val left = softClip(section(lowPass, lowState, 0, heldL) + share * ears[0] + keep * section(highPass, highState, 0, heldL))
            val right = softClip(section(lowPass, lowState, 4, heldR) + share * ears[1] + keep * section(highPass, highState, 4, heldR))
            ears[0] = left
            ears[1] = right
        } else {
            // Two parts that each stay within full scale, in shares that add up to one: their sum
            // stays within it too, and rounding its top off again only took the top off the
            // recording, which at no strength at all came out at nine tenths of itself.
            render(rawL, rawR)
            ears[0] = share * ears[0] + (1f - share) * heldL
            ears[1] = share * ears[1] + (1f - share) * heldR
        }
    }

    /**
     * The recording, held back, with its low notes turned as the rendering turns its own: a
     * first-order all-pass turned over, its corner where [computeGain] found the rendering's.
     * Nothing is louder or quieter for it, and without it the two cancel below 200 Hz when mixed.
     * The two numbers it remembers for a channel are at [from].
     */
    private fun turned(from: Int, x: Float): Float {
        val a = dryTurn
        if (a.isNaN()) return x
        val y = a * x + turnState[from] - a * turnState[from + 1]
        turnState[from] = x
        turnState[from + 1] = y
        return -y
    }

    /**
     * The two halves of the crossover at [BASS_HZ] for this rate: a fourth-order Linkwitz-Riley,
     * which is one second-order Butterworth run twice. Its two halves add up to the whole at
     * every frequency, turned in phase and no more, which is what lets the lows go one way and
     * the highs another and meet again.
     */
    private fun buildCrossover(rate: Int) {
        if (rate == crossoverRate) return
        crossoverRate = rate
        val w = 2.0 * PI * BASS_HZ / rate
        val alpha = sin(w) / (2.0 * sqrt(0.5))
        val c = cos(w)
        val a0 = 1.0 + alpha
        lowPass[0] = ((1.0 - c) / 2.0 / a0).toFloat(); lowPass[1] = ((1.0 - c) / a0).toFloat(); lowPass[2] = lowPass[0]
        highPass[0] = ((1.0 + c) / 2.0 / a0).toFloat(); highPass[1] = (-(1.0 + c) / a0).toFloat(); highPass[2] = highPass[0]
        for (half in arrayOf(lowPass, highPass)) {
            half[3] = (-2.0 * c / a0).toFloat()
            half[4] = ((1.0 - alpha) / a0).toFloat()
        }
        Arrays.fill(lowState, 0f); Arrays.fill(highState, 0f); Arrays.fill(inputState, 0f); Arrays.fill(airState, 0f)
    }

    /** One half of the crossover on one sample: its section twice over, with the four numbers it remembers at [from]. */
    private fun section(half: FloatArray, state: FloatArray, from: Int, x: Float): Float {
        var v = x
        var k = from
        repeat(2) {
            val y = half[0] * v + state[k]
            state[k] = half[1] * v - half[3] * y + state[k + 1]
            state[k + 1] = half[2] * v - half[4] * y
            v = y
            k += 2
        }
        return v
    }

    /**
     * The harmonics of one frame, convolved and decoded to two ears, into [ears]: everything in
     * [render] after the encoding, which is all that differs between two channels in and six or eight.
     */
    private fun decode() {
        val idx = writeIndex
        for (i in 0 until active) rings[i * RING + idx] = harmonics[i]

        var common = 0f
        var side = 0f
        for (i in 0 until active) {
            val base = i * RING
            val fbase = i * taps
            var acc = 0f
            // Split at the wrap rather than masking every tap: two straight runs over contiguous
            // memory, which at sixteen harmonics and a hundred and sixty taps is the difference
            // between comfortable and not.
            val straight = min(taps, idx + 1)
            for (t in 0 until straight) acc += rings[base + idx - t] * filters[fbase + t]
            for (t in straight until taps) acc += rings[base + RING + idx - t] * filters[fbase + t]
            if (fromDifference[i]) side += acc else common += acc
        }
        writeIndex = (idx + 1) and MASK

        val c = common * gain
        val sd = side * gain
        ears[0] = softClip(c + sd)
        ears[1] = softClip(c - sd)
    }

    /**
     * The arrays for [channels] channels in, and where each of their speakers stands with the
     * front pair at the stage's width. Built when the count or the width is not the one they
     * were built for, which is once a recording and once a turn of the width's slider.
     */
    private fun layOutSurround(channels: Int) {
        val sized = surroundChannels == channels && surroundEncode.size == channels * active
        if (sized && surroundWidth == appliedWidth) return
        surroundAzimuth = SurroundLayout.azimuths(channels, appliedWidth) ?: return
        for (c in surroundAzimuth.indices) surroundAzimuth[c] = surroundAzimuth[c] * PI.toFloat() / 180f
        surroundWidth = appliedWidth
        if (!sized) {
            surroundChannels = channels
            surroundEncode = FloatArray(channels * active)
            surroundTarget = FloatArray(channels * active)
            surroundStep = FloatArray(channels * active)
            surroundHistory = FloatArray(channels * CORRECTION_RING)
            // Where they stand with the head level, so the first buffer does not sweep them in from nowhere.
            encodeSurround(IDENTITY, surroundEncode)
        }
    }

    /** [encodeSpeakers] for a recording's own speakers: each one moved against the head and encoded where it lands. */
    private fun encodeSurround(pose: FloatArray, into: FloatArray) {
        val w = pose[0]
        val rx = -pose[2]
        val ry = pose[1]
        val rz = -pose[3]
        for (c in surroundAzimuth.indices) {
            rotate(w, rx, ry, rz, cos(surroundAzimuth[c]), sin(surroundAzimuth[c]), 0f, rotated)
            shInto(rotated, surroundOne)
            System.arraycopy(surroundOne, 0, into, c * active, active)
        }
    }

    /**
     * A buffer of six or eight channels. As [render], with a sum over the channels where the pair
     * has its two terms: the same tone correction on each channel, the same walk of each speaker
     * across the buffer, the same gain. A recording's speakers each play at the level the pair's
     * do, as they would in a room, so what is only in the front pair comes out as it does from a
     * stereo recording.
     */
    private fun renderSurround(inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int, channels: Int, encoding: Int) {
        layOutSurround(channels)
        if (surroundChannels != channels) {
            // Not a count this was configured for. Nothing sensible to play: silence, not noise.
            repeat(frames * 2) { if (encoding == C.ENCODING_PCM_FLOAT) out.putFloat(0f) else out.putShort(0) }
            return
        }
        encodeSurround(poseBuffer, surroundTarget)
        val inv = 1f / frames
        for (k in surroundStep.indices) surroundStep[k] = (surroundTarget[k] - surroundEncode[k]) * inv

        val tone = correction
        repeat(frames) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (c in 0 until channels) surroundFrame[c] = inputBuffer.float
            } else {
                for (c in 0 until channels) surroundFrame[c] = inputBuffer.short / 32768f
            }
            val toned = if (tone.isEmpty()) surroundFrame else {
                val h = historyIndex
                for (c in 0 until channels) {
                    val base = c * CORRECTION_RING
                    surroundHistory[base + h] = surroundFrame[c]
                    var acc = 0f
                    for (t in tone.indices) acc += surroundHistory[base + ((h - t) and CORRECTION_MASK)] * tone[t]
                    surroundToned[c] = acc
                }
                historyIndex = (h + 1) and CORRECTION_MASK
                surroundToned
            }
            for (i in 0 until active) {
                var v = 0f
                var k = i
                for (c in 0 until channels) {
                    v += toned[c] * surroundEncode[k]
                    surroundEncode[k] += surroundStep[k]
                    k += active
                }
                harmonics[i] = v
            }
            decode()
            if (encoding == C.ENCODING_PCM_FLOAT) {
                out.putFloat(ears[0])
                out.putFloat(ears[1])
            } else {
                out.putShort(toPcm16(ears[0]))
                out.putShort(toPcm16(ears[1]))
            }
        }
        System.arraycopy(surroundTarget, 0, surroundEncode, 0, surroundEncode.size)
    }

    /** Radians into (-pi, pi], so a turn past the back of the head takes the short way. */
    private fun wrapPi(a: Float): Float = atan2(sin(a), cos(a))

    private fun toPcm16(v: Float): Short {
        val scaled = v * 32767f
        return when {
            scaled >= 32767f -> Short.MAX_VALUE
            scaled <= -32768f -> Short.MIN_VALUE
            else -> scaled.toInt().toShort()
        }
    }

    override fun onFlush() = clearTails()

    override fun onReset() = clearTails()

    /** A seek must not drag the tail of the old audio into the new position. */
    private fun clearTails() {
        Arrays.fill(rings, 0f)
        Arrays.fill(leftHistory, 0f)
        Arrays.fill(rightHistory, 0f)
        Arrays.fill(surroundHistory, 0f)
        Arrays.fill(roomLeft, 0f)
        Arrays.fill(roomRight, 0f)
        Arrays.fill(roomState, 0f)
        roomIndex = 0
        apart.reset()
        Arrays.fill(airHeldLeft, 0f)
        Arrays.fill(airHeldRight, 0f)
        Arrays.fill(airState, 0f)
        airIndex = 0
        airLeft = 0f
        airRight = 0f
        Arrays.fill(dryLeft, 0f)
        Arrays.fill(dryRight, 0f)
        Arrays.fill(lowState, 0f)
        Arrays.fill(highState, 0f)
        Arrays.fill(inputState, 0f)
        Arrays.fill(turnState, 0f)
        dryIndex = 0
        historyIndex = 0
        writeIndex = 0
        framesProcessed = 0L
    }

    companion object {
        private const val TAG = "BinauralAudio"

        /**
         * Below this the bass is the recording's own when it is left alone. A head starts to
         * shadow a sound somewhere above 200 Hz, so nothing under this had a direction to lose.
         */
        const val BASS_HZ = 120.0

        /**
         * The room, as the left speaker's three first reflections: where each comes from in
         * degrees (plus is left), how many milliseconds after the speaker itself, and how loud
         * beside it. The wall at its side, the wall across the room, the wall behind the
         * listener: a room of about four metres by five with the speakers two and a half metres
         * off. The right speaker's are these mirrored.
         */
        private val ROOM = floatArrayOf(
            65f, 6.5f, 0.45f,
            -55f, 11f, 0.32f,
            160f, 17f, 0.25f,
        )

        /** Room for the longest of them at 96 kHz. */
        private const val ROOM_RING = 2048
        private const val ROOM_MASK = ROOM_RING - 1

        /** The walls are given nothing under this, and less and less over the other. See [roomBand]. */
        private const val ROOM_FROM_HZ = 400.0
        private const val ROOM_DULL_HZ = 3000.0

        /** Where the room is weighed against the pair: the middle of the band the walls are given. */
        private const val ROOM_WEIGH_FROM_HZ = 500.0
        private const val ROOM_WEIGH_TO_HZ = 3000.0
        private const val WEIGH_POINTS = 48

        /**
         * Where the air's speakers stand with all of [ambience]: straight out at the listener's
         * sides. Not where a surround speaker stands, a little behind: measured, the two ears
         * are least alike with the air at the sides (which is what wide is), and from behind the
         * ear the air loses more of its top than its tone filter can give back.
         */
        const val AIR_OUT_DEGREES = 90f

        /** Power of two so the ring index is a mask rather than a modulo. */
        private const val RING = 1024
        private const val MASK = RING - 1

        /**
         * Half the ring, so a whole filter plus a whole filter's worth of history always fits.
         * Reached at 96 kHz; above that the stream passes through, which is the right way to fail
         * for a rate nothing here streams and a tap count that would cost real battery.
         */
        private const val MAX_TAPS = RING / 2

        /**
         * Length of the tonal correction, and the ring it walks.
         *
         * Sixty three taps flattens the worst band to about one decibel, which is inaudible, for
         * six tenths of a millisecond of delay. Longer buys tenths of a decibel nobody can hear.
         */
        const val CORRECTION_TAPS = 63
        private const val CORRECTION_RING = 128
        private const val CORRECTION_MASK = CORRECTION_RING - 1

        /** Frequency points the response is measured at when inverting it. */
        private const val CORRECTION_BINS = 256

        /** The cosine of every whole number of steps round a circle of twice [CORRECTION_BINS] steps. */
        private const val TURN_MASK = 2 * CORRECTION_BINS - 1
        private val TURN = DoubleArray(2 * CORRECTION_BINS) { cos(PI * it / CORRECTION_BINS) }

        /** A third of an octave, as the ratio to each side of centre. */
        private const val SIXTH_OCTAVE = 1.122462

        /** Held flat outside this, where there is no music and the inverse would run away. */
        private const val CORRECTION_LOW_HZ = 60.0
        private const val CORRECTION_HIGH_HZ = 17000.0

        /** How far the correction may reach, so a deep notch cannot turn into a howl. */
        private const val CORRECTION_LIMIT_DB = 12.0

        /** Taps either side of the interpolation point when moving the filters to a new rate. */
        private const val HALF_WIDTH = 16

        /**
         * How much of the measured response to keep.
         *
         * The tail past this is thirty six decibels down and carries three hundredths of a percent
         * of the energy, and dropping it is a third off the arithmetic, which at ten harmonics and
         * forty eight thousand samples a second is worth having. Faded out rather than cut, so the
         * truncation does not ripple across the spectrum.
         */
        private const val KEEP_TAPS = 160
        private const val FADE_TAPS = 16

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f)

        private const val SQRT3 = 1.7320508f
        private const val SQRT15 = 3.8729835f
        private const val SQRT38 = 0.6123724f
        private const val SQRT58 = 0.7905694f

        /** Where the output stops being left alone. Below this the soft clip is identity. */
        private const val KNEE = 0.85f

        /** Faster than any voluntary head turn, so only a glitch is ever clamped. 720 deg/s. */
        const val MAX_YAW_RATE = 12.566371f

        /** The angle a stereo mix is actually made for, and so the only defensible default. */
        const val DEFAULT_STAGE_WIDTH = 30f

        /** Narrower than this is pointless and wider stops being a soundstage. */
        const val MIN_STAGE_WIDTH = 15f
        const val MAX_STAGE_WIDTH = 90f

        /**
         * ACN index, degree, SN3D value at zero elevation, in threes.
         *
         * Every harmonic with (n - |m|) odd is identically zero in the horizontal plane and is
         * left out entirely. Ordered by degree so [rotate] can reach the multiple angles by
         * recurrence.
         */
        private val ORDER_1_LAYOUT = floatArrayOf(
            0f, 0f, 1f,             // W
            1f, -1f, 1f,            // Y
            3f, 1f, 1f,             // X
        )

        /**
         * All sixteen, for when the head may tilt.
         *
         * Same order: degree ascending, so the multiple angles the horizontal path reaches by
         * recurrence stay in runs. The six that the horizontal layout leaves out are the ones with
         * (n - |m|) odd, which vanish at ear level and carry everything above and below it.
         */
        private val ORDER_3_FULL_LAYOUT = floatArrayOf(
            0f, 0f, 1f,
            2f, 0f, 1f,
            6f, 0f, -0.5f,
            12f, 0f, 1f,
            1f, -1f, 1f,
            3f, 1f, 1f,
            5f, -1f, 1f,
            7f, 1f, 1f,
            11f, -1f, -0.6123724f,
            13f, 1f, -0.6123724f,
            4f, -2f, 0.8660254f,
            8f, 2f, 0.8660254f,
            10f, -2f, 1f,
            14f, 2f, 1f,
            9f, -3f, 0.7905694f,
            15f, 3f, 0.7905694f,
        )

        private val ORDER_3_LAYOUT = floatArrayOf(
            0f, 0f, 1f,
            6f, 0f, -0.5f,
            1f, -1f, 1f,
            3f, 1f, 1f,
            11f, -1f, -0.6123724f,
            13f, 1f, -0.6123724f,
            4f, -2f, 0.8660254f,
            8f, 2f, 0.8660254f,
            9f, -3f, 0.7905694f,
            15f, 3f, 0.7905694f,
        )
    }
}

/**
 * Where the loudspeakers of a surround recording stand round the listener, in the order Android
 * hands their channels over: left, right, centre, the low notes, then the pair behind, and for
 * 7.1 the pair at the sides after that.
 */
internal object SurroundLayout {
    /** The most channels a recording comes in here: the eight of 7.1. */
    const val MOST = 8

    /** The channel for the low notes, which has no place of its own. */
    const val LOW = 3

    /**
     * Each channel's angle in degrees, plus to the left, with the front pair [front] degrees
     * either side: where a stereo recording's two would stand, so the width chosen for those
     * holds for these. The rest are where the standards put them: the centre straight ahead, 5.1's
     * surrounds at 110, 7.1's at the sides and at 150 behind. The low notes come from straight
     * ahead, which for notes that low is as good as from everywhere. Null for a count that is
     * neither 5.1 nor 7.1.
     */
    fun azimuths(channels: Int, front: Float): FloatArray? = when (channels) {
        6 -> floatArrayOf(front, -front, 0f, 0f, 110f, -110f)
        8 -> floatArrayOf(front, -front, 0f, 0f, 150f, -150f, 90f, -90f)
        else -> null
    }
}

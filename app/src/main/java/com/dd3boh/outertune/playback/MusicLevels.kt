/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How loud the music is, moment by moment, in three ranges: bass, the middle and the top. It is
 * what the player's living background moves to.
 *
 * Each value runs from 0 to 1, where 1 is as loud as that range has been lately in this song. They
 * are measured against the song itself and not against full scale, so a quiet recording moves the
 * picture as much as a loud one, and turning the volume down does not flatten it (the samples are
 * read before the volume is applied).
 *
 * With them goes a fourth value, how much is going on ([MusicLevels.PRESENCE]): "lately" is a few
 * seconds, so a quiet passage becomes its own yardstick and by its levels alone moves the picture
 * as much as the loudest part of the song. The fourth value says how the passage compares with
 * the song at full tilt, and whoever draws takes that much of the levels.
 *
 * And three more say where the song is in its shape: building up, dropping, or driven on by a
 * drop ([MusicLevels.TENSION], [MusicLevels.DROP], [MusicLevels.DRIVE]). The levels alone know
 * nothing of that: bar for bar the part after the drop is no louder than the part before the
 * breakdown, and it is the part the song was made for.
 *
 * Nothing here knows about Android or the player: [LevelAnalyser] turns samples into levels,
 * [LevelTimeline] keeps a few seconds of them by time. The samples reach the output a good
 * fraction of a second after they are measured, so a level must be looked up by the time that is
 * being heard, never taken as it arrives.
 */
object MusicLevels {
    const val BASS = 0
    const val MID = 1
    const val HIGH = 2
    const val BANDS = 3

    /** After the three ranges: how much is going on, 0 to 1 ([presence]). */
    const val PRESENCE = 3

    /**
     * After that, where the song is in its shape, each 0 to 1 ([LevelAnalyser] finds them).
     * [TENSION]: a build-up, more of it the longer it has gone on. [DROP]: the moment the bass
     * comes back after one, as strong as the build-up was long, and gone again well within a
     * second. [DRIVE]: the part that follows a drop, for as long as it stays loud and keeps its bass.
     */
    const val TENSION = 4
    const val DROP = 5
    const val DRIVE = 6

    /** How many values a frame is: the three ranges, [PRESENCE], [TENSION], [DROP] and [DRIVE]. */
    const val VALUES = 7

    /** Where the bass ends and where the top begins, in Hz. */
    const val BASS_TOP_HZ = 140.0
    const val HIGH_BOTTOM_HZ = 2500.0

    /** One level every this many seconds. Long enough to hold a whole cycle of a 50 Hz bass note. */
    const val FRAME_SECONDS = 0.02

    /** How long the loudest moment stays the yardstick before a quieter passage becomes it, in seconds. */
    const val YARDSTICK_SECONDS = 8.0

    /**
     * Below this a range counts as silent and is never stretched up to 1: about -50 dB of full
     * scale. Without it the hiss before a song starts would be the loudest thing heard so far.
     */
    const val SILENCE = 0.003f

    /**
     * No range is measured against less than this share of the loudest range's yardstick (-18 dB).
     * A song with almost nothing at the top should show almost nothing there, not its tape hiss
     * at full swing.
     */
    const val LEAST_SHARE = 0.125f

    /**
     * How long the loudness of this moment is held, and how long the song's loudest stays what
     * "loud" means, in seconds. The first is short enough that a passage is known to be quiet a
     * second or two in and long enough to bridge the gaps between drum hits; the second is long
     * enough to outlast a breakdown, and short enough that a quiet song after a loud one is its
     * own measure within a minute or two.
     */
    const val NOW_SECONDS = 1.2
    const val SONG_SECONDS = 40.0

    /**
     * A passage this loud against the song's loudest, as amplitude, has hardly anything going on;
     * from [FULLY] up everything is. Hardly anything is still [LEAST_PRESENCE]: while there is
     * sound the picture is never quite still.
     */
    const val HARDLY = 0.15f
    const val FULLY = 0.65f
    const val LEAST_PRESENCE = 0.1f

    /**
     * How much is going on, 0 to 1, when the passage is as loud as [now] and the song gets as
     * loud as [song]. It was the levels alone that moved the picture, and they are measured
     * against the last few seconds: a lone voice in a quiet passage flashed as hard as the drop.
     */
    fun presence(now: Float, song: Float): Float {
        if (song <= 0f || now <= 0f) return 0f
        val t = ((now / song - HARDLY) / (FULLY - HARDLY)).coerceIn(0f, 1f)
        return LEAST_PRESENCE + (1f - LEAST_PRESENCE) * t * t * (3f - 2f * t)
    }

    /**
     * Bass counts as there in a frame when it is this much of everything in that frame, as
     * amplitude, and not under [BASS_FAINT] of the loudest the passage has been. It is set
     * against the frame and not against the song, so that a song fading out keeps its bass to the
     * end: there the kick gets quieter along with everything else, which is not the bass leaving.
     */
    const val BASS_THERE = 0.4f
    const val BASS_FAINT = 0.1f

    /**
     * The longest two kicks are apart in a beat that is simply slow, in seconds. Only music that
     * has gone without bass for longer is a build-up, and only bass that comes back after longer
     * can be a drop. A second of bass closes [BASS_CLOSES] seconds of gap, which is all of it for
     * a kick and little for one stray frame with something low in it: intros are full of those,
     * and each used to start the count again. The gap is not counted beyond [GAP_MOST_SECONDS].
     */
    const val KICK_GAP_SECONDS = 2f
    const val BASS_CLOSES = 25f
    const val GAP_MOST_SECONDS = 3f

    /** Tension is full when the bass has been away this much longer than that, in seconds. */
    const val TENSION_SECONDS = 8f

    /**
     * A second of bass takes back this many seconds of tension: a single note in a breakdown
     * takes some of it, and a beat that comes back without being a drop all of it within a few bars.
     */
    const val BASS_UNDOES = 16f

    /**
     * Music that has gone this long without bass is not building up to anything, it is music
     * without bass: from here the tension lets go, and by [GIVEN_UP_SECONDS] it is gone. A drop
     * that comes after all is as strong as ever.
     */
    const val GIVING_UP_SECONDS = 30f
    const val GIVEN_UP_SECONDS = 45f

    /** Silence this long ends whatever was building, in seconds. Shorter is the beat of silence before a drop, and spends nothing. */
    const val SILENCE_ENDS_SECONDS = 3f

    /**
     * A drop is the bass back at this much of the loudest the song's bass has been, with all of
     * the sound together at this much of the loudest the song has been, and above what is
     * remembered of the bass by as much as [KICK_GAP_SECONDS] without any make it
     * ([BASS_MEMORY_SECONDS]). From its first frame of bass it has this long to get there, in
     * seconds: a frame rarely begins where a kick does.
     */
    const val DROP_BASS = 0.5f
    const val DROP_LOUD = 0.7f
    const val DROP_WITHIN_SECONDS = 0.12f

    /** How strong a drop is that had no tension to spend, where one with all of it is 1. */
    const val LEAST_DROP = 0.35f

    /**
     * A drop is as strong as the bass was long away, and that is told two ways, whichever says
     * longer: by the tension, and by how far the drop's bass towers over what is remembered of
     * the bass, which fades over this many seconds. The second does not mind the murmur of bass
     * that many a build-up keeps, where the first is held down by it. And where that murmur has
     * left no tension at all, bass may still be a drop when it towers as it would after
     * [BASS_TOWERS_SECONDS] with none: four and a half times what is remembered, with these two.
     */
    const val BASS_MEMORY_SECONDS = 4f
    const val BASS_TOWERS_SECONDS = 6f

    /** [DROP] is down to a third after this long, in seconds: whoever draws sees it, however rarely it looks. */
    const val DROP_FADES_SECONDS = 0.15

    /**
     * The drive holds while the passage is this loud against the loudest the song has been and
     * the bass is never away for more than [KICK_GAP_SECONDS]. When either fails it goes down
     * over [DRIVE_FALL_SECONDS], and should the beat be back before it is gone, up again over
     * [DRIVE_RISE_SECONDS].
     *
     * It does not last for ever either. A song that never lets up after its intro would be
     * driven to its end, and that is no longer the part after the drop, it is how the song is:
     * [DRIVE_LASTS_SECONDS] after its drop a drive begins to wear off, and
     * [DRIVE_WEARS_SECONDS] later it has.
     */
    const val DRIVE_LOUD = 0.3f
    const val DRIVE_FALL_SECONDS = 1.5f
    const val DRIVE_RISE_SECONDS = 0.2f
    const val DRIVE_LASTS_SECONDS = 60f
    const val DRIVE_WEARS_SECONDS = 30f

    /** For how long the loudest the song and its bass have been stay what a drop is told by, in seconds. Longer than a breakdown lasts. */
    const val SHAPE_SECONDS = 90.0
}

/**
 * Turns PCM samples into [MusicLevels], one frame every [MusicLevels.FRAME_SECONDS].
 *
 * Three ranges from six one-pole filters rather than a Fourier transform: about twenty additions
 * and multiplications a sample, which is nothing next to decoding the audio. Every edge is two
 * poles steep, because the levels are measured against themselves: whatever leaks from one range
 * into an emptier one is stretched up with it, and with one pole a bass note alone came out three
 * quarters as loud in the middle range as in its own.
 *
 * It also finds the build-up and the drop as they go by ([shape]). In the music that has them the
 * bass leaves for the build-up, seconds of it while the rest carries on, and the drop is the bass
 * coming back at once with everything at the loudest the song gets. That is all it goes by: how
 * long the bass has been away, and how it comes back. It has to know a drop by its first kick,
 * since the picture can only land on a beat it knows of before the beat is heard, so it cannot
 * wait and see whether the bass stays: a single note as loud as the drop would be, after seconds
 * without bass, is taken for one, and the drive that follows a drop is what lets go again when
 * the note turns out to have been alone. A build-up that keeps its kick is not found at all.
 *
 * Not thread safe: one thread feeds it.
 */
class LevelAnalyser(private val sampleRate: Int, private val onFrame: (endsAtSample: Long, levels: FloatArray) -> Unit) {

    private val frameLength = max(1, (sampleRate * MusicLevels.FRAME_SECONDS).toInt())
    private val bassStep = step(MusicLevels.BASS_TOP_HZ)
    private val highStep = step(MusicLevels.HIGH_BOTTOM_HZ)
    private val yardstickKeeps = exp(-MusicLevels.FRAME_SECONDS / MusicLevels.YARDSTICK_SECONDS).toFloat()

    // filter memories, in the order sample() runs them
    private var bass1 = 0f
    private var bass2 = 0f
    private var underRest = 0f
    private var mid1 = 0f
    private var mid2 = 0f
    private var underHigh = 0f

    private val sum = DoubleArray(MusicLevels.BANDS)
    private var inFrame = 0
    private var samplesSeen = 0L

    /** The loudest each range has been lately, as an amplitude. Starts at an ordinary song's, so the first note is not the loudest ever. */
    private val yardstick = floatArrayOf(0.18f, 0.12f, 0.05f)
    private val levels = FloatArray(MusicLevels.VALUES)

    private val nowKeeps = exp(-MusicLevels.FRAME_SECONDS / MusicLevels.NOW_SECONDS).toFloat()
    private val songKeeps = exp(-MusicLevels.FRAME_SECONDS / MusicLevels.SONG_SECONDS).toFloat()

    /** How loud it is now, all ranges together, and the loudest the song has been: for [MusicLevels.presence]. The song starts as an ordinary one. */
    private var now = 0f
    private var song = sqrt(0.18f * 0.18f + 0.12f * 0.12f + 0.05f * 0.05f)

    private val frameSeconds = MusicLevels.FRAME_SECONDS.toFloat()
    private val shapeKeeps = exp(-MusicLevels.FRAME_SECONDS / MusicLevels.SHAPE_SECONDS).toFloat()
    private val dropKeeps = exp(-MusicLevels.FRAME_SECONDS / MusicLevels.DROP_FADES_SECONDS).toFloat()
    private val latelyKeeps = exp(-MusicLevels.FRAME_SECONDS / MusicLevels.BASS_MEMORY_SECONDS).toFloat()
    private val towers = exp(MusicLevels.BASS_TOWERS_SECONDS / MusicLevels.BASS_MEMORY_SECONDS)
    private val returns = exp(MusicLevels.KICK_GAP_SECONDS / MusicLevels.BASS_MEMORY_SECONDS)

    /**
     * The loudest this song has been, all ranges together, and the loudest its bass has been: what
     * a drop is told by. Unlike [song] they start from nothing and not from an ordinary song's, so
     * that a quiet recording drops like a loud one from its first bar.
     */
    private var top = 0f
    private var bassTop = 0f

    /** Seconds of music with no bass in it that no bass has closed since, and seconds of silence in a row. */
    private var gap = 0f
    private var silent = 0f

    /** Seconds of music since all this started over, or since the last drop: nothing can have been away for longer. */
    private var heard = 0f

    /**
     * What is remembered of the bass ([MusicLevels.BASS_MEMORY_SECONDS]), as it was before the
     * last few frames, which wait in [onset]: a drop's own first frames must not be what its bass
     * is measured against.
     */
    private var lately = 0f
    private val onset = FloatArray(max(1, Math.round(MusicLevels.DROP_WITHIN_SECONDS / MusicLevels.FRAME_SECONDS).toInt()))
    private var onsetAt = 0

    /**
     * Seconds the bass has been away for longer than the gap between two kicks, which is what the
     * tension is made of and a returning bass takes back; and the same seconds counted until the
     * bass is back for good, by which the tension gives up.
     */
    private var away = 0f
    private var spent = 0f

    /** Seconds the bass that has just come back still has to prove a drop, and how much tension there was when it came. */
    private var proving = 0f
    private var built = 0f

    private var drop = 0f
    private var drive = 0f
    private var driveTo = 0f

    /** Seconds since the drop the drive is from. */
    private var drivenFor = 0f

    /** How loud each range was in the frame just finished, against full scale. For whoever wants the measure before it is set against the song. */
    val raw = FloatArray(MusicLevels.BANDS)

    private fun step(cutoffHz: Double) = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()

    /** One sample, all channels already mixed into one, full scale being -1 to 1. */
    fun sample(x: Float) {
        // bass: what two low-pass stages let through
        bass1 += bassStep * (x - bass1)
        bass2 += bassStep * (bass1 - bass2)
        // the rest: the same two stages taken away
        val rest1 = x - bass1
        underRest += bassStep * (rest1 - underRest)
        val rest = rest1 - underRest
        // the middle is the low part of the rest, the top is what is left of it
        mid1 += highStep * (rest - mid1)
        mid2 += highStep * (mid1 - mid2)
        val high1 = rest - mid1
        underHigh += highStep * (high1 - underHigh)
        val high = high1 - underHigh

        sum[MusicLevels.BASS] += (bass2 * bass2).toDouble()
        sum[MusicLevels.MID] += (mid2 * mid2).toDouble()
        sum[MusicLevels.HIGH] += (high * high).toDouble()
        samplesSeen++
        if (++inFrame == frameLength) finishFrame()
    }

    private fun finishFrame() {
        var loudest = 0f
        // A float stream can carry a sample that is not a number, and a filter that has taken one
        // in gives nothing else ever after. Such a frame counts as silence and the filters start over.
        if (sum.any { it.isNaN() || it.isInfinite() }) {
            jump()
            raw.fill(0f)
            levels.fill(0f)
            onFrame(samplesSeen, levels)
            return
        }
        var power = 0.0
        for (band in 0 until MusicLevels.BANDS) {
            power += sum[band] / frameLength
            raw[band] = sqrt(sum[band] / frameLength).toFloat()
            sum[band] = 0.0
            yardstick[band] = max(yardstick[band] * yardstickKeeps, raw[band])
            loudest = max(loudest, yardstick[band])
        }
        val all = sqrt(power).toFloat()
        now = max(now * nowKeeps, all)
        song = max(song * songKeeps, all)
        levels[MusicLevels.PRESENCE] = if (now <= MusicLevels.SILENCE) 0f else MusicLevels.presence(now, song)
        shape(raw[MusicLevels.BASS], all)
        // A range with next to nothing in it is not stretched to full: its yardstick is never
        // taken to be less than a small part of the loudest range's.
        val least = max(MusicLevels.SILENCE, loudest * MusicLevels.LEAST_SHARE)
        for (band in 0 until MusicLevels.BANDS) {
            levels[band] = if (raw[band] <= MusicLevels.SILENCE) 0f else (raw[band] / max(yardstick[band], least)).coerceIn(0f, 1f)
        }
        inFrame = 0
        onFrame(samplesSeen, levels)
    }

    /**
     * Where the song is in its shape after a frame with this much [bass] in it and this much of
     * everything ([all]), both against full scale: a few comparisons, and everything in them is
     * one loudness against another, so a quiet recording gives what a loud one does.
     *
     * While there is music and no bass, [gap] runs. Once it has run for longer than two kicks are
     * ever apart, [away] runs with it, and that is the tension. Bass that comes while there is
     * tension has a few frames to prove a drop: hard for this song's bass, with everything near
     * the song's loudest. A drop is as strong as the bass was long away, and starts the drive,
     * which holds for as long as the passage stays loud and keeps its bass. Bass that proves
     * nothing takes the tension back bit by bit.
     */
    private fun shape(bass: Float, all: Float) {
        top = max(top * shapeKeeps, all)
        bassTop = max(bassTop * shapeKeeps, bass)
        drop *= dropKeeps
        if (drop < 0.01f) drop = 0f
        if (all <= MusicLevels.SILENCE) {
            silent += frameSeconds
            if (silent > MusicLevels.SILENCE_ENDS_SECONDS) startOver()
        } else {
            silent = 0f
            if (heard < MusicLevels.SHAPE_SECONDS) heard += frameSeconds
            lately = max(lately * latelyKeeps, onset[onsetAt])
            onset[onsetAt] = bass
            onsetAt = (onsetAt + 1) % onset.size
            if (bass >= MusicLevels.BASS_THERE * all && bass >= MusicLevels.BASS_FAINT * now) {
                // Bass, and tension left that it has not taken back yet: it may be the drop. Not
                // only the first bass after the gap, which is as often a fill or a note picked up
                // half a beat before. Or bass far above any there has been for a while.
                val towering = drop == 0f && heard >= MusicLevels.BASS_TOWERS_SECONDS && bass >= towers * lately
                if (away > 0f || towering) {
                    if (proving <= 0f) built = away / MusicLevels.TENSION_SECONDS
                    proving = MusicLevels.DROP_WITHIN_SECONDS
                }
                gap = max(0f, gap - MusicLevels.BASS_CLOSES * frameSeconds)
                away = max(0f, away - MusicLevels.BASS_UNDOES * frameSeconds)
                if (away == 0f) spent = 0f
            } else {
                gap = min(MusicLevels.GAP_MOST_SECONDS, gap + frameSeconds)
                if (gap > MusicLevels.KICK_GAP_SECONDS) {
                    away = min(MusicLevels.TENSION_SECONDS, away + frameSeconds)
                    spent += frameSeconds
                }
            }
            if (proving > 0f) {
                proving -= frameSeconds
                if (bass >= MusicLevels.DROP_BASS * bassTop && all >= MusicLevels.DROP_LOUD * top && bass >= returns * lately) dropped(bass)
            }
        }
        if (driveTo > 0f) {
            drivenFor += frameSeconds
            val worn = ((drivenFor - MusicLevels.DRIVE_LASTS_SECONDS) / MusicLevels.DRIVE_WEARS_SECONDS).coerceIn(0f, 1f)
            if (worn < 1f && gap < MusicLevels.KICK_GAP_SECONDS && now >= MusicLevels.DRIVE_LOUD * top) {
                drive = min(driveTo * (1f - worn), drive + frameSeconds / MusicLevels.DRIVE_RISE_SECONDS)
            } else {
                drive = max(0f, drive - frameSeconds / MusicLevels.DRIVE_FALL_SECONDS)
                if (drive == 0f) driveTo = 0f
            }
        }
        val givenUp = ((spent - MusicLevels.GIVING_UP_SECONDS) / (MusicLevels.GIVEN_UP_SECONDS - MusicLevels.GIVING_UP_SECONDS)).coerceIn(0f, 1f)
        levels[MusicLevels.TENSION] = away / MusicLevels.TENSION_SECONDS * (1f - givenUp)
        levels[MusicLevels.DROP] = drop
        levels[MusicLevels.DRIVE] = drive
    }

    /** This frame is a drop, with this much [bass] in it. */
    private fun dropped(bass: Float) {
        // how long the bass has been away going by its level: what is remembered of it has faded
        // for that long to be this far under it
        val byLevel = if (lately > 0f) MusicLevels.BASS_MEMORY_SECONDS * ln(bass / lately) else heard
        val waited = ((min(heard, byLevel) - MusicLevels.KICK_GAP_SECONDS) / MusicLevels.TENSION_SECONDS).coerceIn(0f, 1f)
        drop = MusicLevels.LEAST_DROP + (1f - MusicLevels.LEAST_DROP) * max(built, waited)
        // A smaller drop inside a part a bigger one drives is a burst and leaves the drive as it
        // is, with part of its time given back: all of it would keep a song with a bar's break
        // every so often driven for good.
        if (drop >= driveTo) {
            driveTo = drop
            drivenFor = 0f
        } else {
            drivenFor *= 1f - drop / driveTo
        }
        drive = max(drive, drop)
        proving = 0f
        gap = 0f
        away = 0f
        spent = 0f
        heard = 0f
    }

    /** Nothing building, nothing dropped, and no song to tell a drop by: as at the first sample. */
    private fun startOver() {
        top = 0f
        bassTop = 0f
        gap = 0f
        silent = 0f
        heard = 0f
        lately = 0f
        onset.fill(0f)
        drivenFor = 0f
        away = 0f
        spent = 0f
        proving = 0f
        built = 0f
        drop = 0f
        drive = 0f
        driveTo = 0f
    }

    /**
     * Nothing was measured for [seconds], because nobody was looking or nothing played. What is
     * remembered is aged as that much silence would have aged it, and the song's shape starts
     * over. The yardsticks only ever came down while frames were measured: opened on a quiet song
     * hours after a loud one, the picture sat nearly still for a minute, measuring the one
     * against the other.
     */
    fun aged(seconds: Double) {
        if (!(seconds > 0.0)) return
        val kept = exp(-seconds / MusicLevels.YARDSTICK_SECONDS).toFloat()
        for (band in 0 until MusicLevels.BANDS) yardstick[band] *= kept
        now *= exp(-seconds / MusicLevels.NOW_SECONDS).toFloat()
        song *= exp(-seconds / MusicLevels.SONG_SECONDS).toFloat()
        startOver()
    }

    /** 16 bit samples from [buffer]'s position to its limit, [channels] interleaved. The buffer is left as it was. */
    fun pcm16(buffer: ByteBuffer, channels: Int) {
        val from = buffer.position()
        val frames = (buffer.limit() - from) / (2 * channels)
        var at = from
        repeat(frames) {
            var mixed = 0
            repeat(channels) {
                mixed += buffer.getShort(at)
                at += 2
            }
            sample(mixed / (32768f * channels))
        }
    }

    /** The same for 32 bit float samples. */
    fun pcmFloat(buffer: ByteBuffer, channels: Int) {
        val from = buffer.position()
        val frames = (buffer.limit() - from) / (4 * channels)
        var at = from
        repeat(frames) {
            var mixed = 0f
            repeat(channels) {
                mixed += buffer.getFloat(at)
                at += 4
            }
            sample(mixed / channels)
        }
    }

    /**
     * The audio jumped (a seek, another song): what is left of a half measured frame is dropped,
     * and whatever was building or driven is over. The yardstick stays.
     */
    fun jump() {
        startOver()
        sum.fill(0.0)
        inFrame = 0
        bass1 = 0f
        bass2 = 0f
        underRest = 0f
        mid1 = 0f
        mid2 = 0f
        underHigh = 0f
    }
}

/**
 * A few seconds of levels by the time they are heard, written by the thread that plays and read by
 * the one that draws.
 *
 * Times are the player's own (microseconds, as the audio output counts them) and only ever grow
 * between two [clear]s.
 */
class LevelTimeline(private val capacity: Int = 512) {
    private val times = LongArray(capacity)
    private val values = FloatArray(capacity * MusicLevels.VALUES)
    private var next = 0
    private var held = 0

    @Synchronized
    fun add(timeUs: Long, levels: FloatArray) {
        times[next] = timeUs
        // levels with no word on how much is going on count as all there, and with none on the
        // song's shape as building nothing
        val at = next * MusicLevels.VALUES
        values.fill(0f, at, at + MusicLevels.VALUES)
        values[at + MusicLevels.PRESENCE] = 1f
        System.arraycopy(levels, 0, values, at, minOf(levels.size, MusicLevels.VALUES))
        next = (next + 1) % capacity
        if (held < capacity) held++
    }

    @Synchronized
    fun clear() {
        next = 0
        held = 0
    }

    /**
     * The levels being heard at [timeUs], into [into]: the three ranges, and as many of the
     * values after them as [into] has room for ([MusicLevels.VALUES] for all). False when nothing is known for that moment:
     * nothing measured yet, or the newest level before it is more than [staleUs] old.
     */
    @Synchronized
    fun read(timeUs: Long, into: FloatArray, staleUs: Long = 250_000): Boolean {
        for (back in 1..held) {
            val at = (next - back + capacity) % capacity
            if (times[at] <= timeUs) {
                if (timeUs - times[at] > staleUs) return false
                System.arraycopy(values, at * MusicLevels.VALUES, into, 0, minOf(into.size, MusicLevels.VALUES))
                return true
            }
        }
        return false
    }
}

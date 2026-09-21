/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything between a head tracker and [BinauralAudioProcessor.headYawRadians].
 *
 * The processor is left knowing nothing about head trackers: it is handed an angle and smooths it
 * to audio rate, and every question about why the angle is what it is stops here. Recentring,
 * stillness, dropout and the rate limit on the stage's own movements, in that order, which is the
 * order Android's own libheadtracking uses and the constants are taken from it.
 *
 * Single threaded on [handler]. Reports arrive there, timers run there, and the only thing that
 * crosses to the audio thread is one volatile float, which is the same arrangement the enabled
 * flag already uses and the reason neither needs a lock.
 *
 * Availability is the unusual part. The sensor is a dynamic one, published only while the
 * headphones are connected, restricted to system uids unless the restriction has been lifted, and
 * on most phones never published at all because the vendor did not load the sub-HAL that reads
 * the HID reports. So [isAvailable] is a real question with a different answer minute to minute,
 * not a capability check that can be cached at startup.
 */
class HeadTracking(
    context: Context,
    private val processor: BinauralAudioProcessor,
    private val handler: Handler,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)

    /**
     * How much of the gap between where the head is and where it will be to take.
     *
     * Volatile because it is set from the settings screen while the sensor thread is reading it.
     * Zero follows the head honestly and feels a beat behind; anything more trades a little
     * overshoot at the end of a turn for the whole thing feeling immediate.
     */
    @Volatile
    var predictFraction: Float = 0.5f

    /**
     * The most the guess may run ahead of the head, in radians.
     *
     * A safety net against one bad rate estimate throwing the soundstage across the room, and a
     * mistake when it is set tight: at two hundred degrees a second a quarter of a second of lead
     * is fifty degrees, so a fifteen degree cap silently threw away two thirds of the prediction
     * and left it feeling sluggish however high the fraction went.
     */
    @Volatile
    var predictClamp: Float = 0.52f

    /**
     * One pole on the rate estimate. Higher follows the turn sooner and is noisier.
     *
     * These headphones report no angular velocity, so the rate is differentiated from two poses
     * forty milliseconds apart, which is noisy enough to need smoothing and slow enough that the
     * smoothing costs real time at the start of a turn. Which side of that to err on is the whole
     * difference between the response settings.
     */
    @Volatile
    var rateSmoothing: Float = 0.35f

    /**
     * How far ahead to aim, in seconds, measured rather than assumed.
     *
     * Started as a constant and should not have been one. The delay between rendering a sample and
     * hearing it depends on the codec that got negotiated, what the sink decided to buffer and
     * what the headphones do with it, none of which an app can know in advance, and the numbers
     * involved differ by a factor of three across codecs. So it is measured from the player
     * itself and fed back in here.
     */
    @Volatile
    var lookaheadSeconds: Float = 0.26f

    /**
     * Pass tilting through as well as turning, rather than only the horizontal.
     *
     * Kept separate because everything this class corrects for is horizontal. Drift is a yaw
     * problem, since gravity pins the other two axes and nothing pins that one; recentring is a
     * yaw decision; and the prediction is about how fast the head is turning. Pitch and roll need
     * none of it and are passed through untouched, so the corrected yaw is put back into the
     * orientation and the rest is left as measured.
     */
    @Volatile
    var fullSphere: Boolean = false

    private val poseOut = FloatArray(4)
    private val swing = FloatArray(4)

    /** Whether a tracker is published to us right now. Cheap enough to ask each time. */
    val isAvailable: Boolean
        get() = runCatching { tracker() != null }.getOrDefault(false)

    private fun tracker(): Sensor? =
        sensors?.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER)?.firstOrNull()

    /** Whether the listener wants tracking, whether or not there is anything to track with. */
    private var wantRunning = false

    /** The sensor currently feeding the renderer, if any. */
    private var attached: Sensor? = null

    /** Whether a tracker is actually feeding the renderer. */
    val isRunning: Boolean
        get() = attached != null
    private var pendingRecentre = true

    /** Where the head was when the stage was last put in front of it, and when that was. */
    private var referenceYaw = 0f
    private var referenceNanos = 0L
    private var lastYaw = 0f

    /**
     * How fast the reference itself has to move to stay put, in radians per second.
     *
     * The tracker's yaw slides, measured at about a degree a second on this pair. Not a guess
     * about the hardware: these headphones do contain a magnetometer, whatever it is used for,
     * and the reports still drift. The HID profile says as much, describing its own reference
     * frame as arbitrary and possibly slowly drifting, so this is the documented behaviour rather
     * than a fault. Waiting for six seconds of stillness to
     * correct it works when someone is listening and fails exactly when they are moving about,
     * which is when they would notice. Bleeding the reference towards wherever they are looking
     * cancels drift without stillness, but cannot tell a slow deliberate turn from drift and so
     * always follows one.
     *
     * Measuring it avoids both. Thirty seconds looking straight ahead gives a slope, and a slope
     * can be subtracted forever after without ever following the listener. Zero until calibrated.
     */
    @Volatile
    var driftRate: Float = 0f

    /** Called with the measured drift in radians per second, so it can be kept. */
    var onCalibrated: ((Float) -> Unit)? = null

    private var calibrating = false
    private var calibrationStartNanos = 0L
    private var calibrationSamples = 0
    private var sumT = 0.0
    private var sumY = 0.0
    private var sumTT = 0.0
    private var sumTY = 0.0

    /** Progress through a calibration, 0 to 1, or null when none is running. */
    val calibrationProgress: Float?
        get() = if (!calibrating) null else
            ((lastYawNanos - calibrationStartNanos) / CALIBRATION_NANOS.toFloat()).coerceIn(0f, 1f)

    private var lastYawNanos = 0L

    /** The orientation exactly as reported, kept so the tilt can be passed through. */
    private val lastPose = floatArrayOf(1f, 0f, 0f, 0f)

    /** Where the stage has been told to sit, and where it is being walked towards. */
    private var commanded = 0f
    private var wanted = 0f
    private var lastAdvanceNanos = 0L

    /** Whether the stage is moving under its own steam rather than following the head. */
    private var settling = false

    private val stillness = StillYaw(AUTO_RECENTRE_WINDOW_NANOS, AUTO_RECENTRE_THRESHOLD)

    /** Yaw rate, in radians per second, smoothed. See the prediction in [onSensorChanged]. */
    private var yawRate = 0f
    private var lastReportNanos = 0L
    private var lastReportYaw = 0f

    fun start(): Boolean {
        if (wantRunning) return isRunning
        wantRunning = true
        // Dynamic sensors are destroyed and rebuilt, not reused. Headphones that drop for a
        // moment come back as a different sensor with a different handle, and a listener
        // registered against the old one simply stops being called: no error, no callback,
        // nothing to notice except that the soundstage has quietly stopped following anyone.
        // Recentring does not help, because the problem is that nothing is arriving at all.
        runCatching { sensors?.registerDynamicSensorCallback(dynamicSensors, handler) }
        handler.postDelayed(watchdog, WATCHDOG_MS)
        return attach(tracker())
    }

    private fun attach(sensor: Sensor?): Boolean {
        if (!wantRunning || attached != null) return attached != null
        val found = sensor ?: return false
        pendingRecentre = true
        settling = false
        stillness.reset()
        lastAdvanceNanos = 0L
        lastReportNanos = 0L
        lastYawNanos = 0L
        yawRate = 0f
        sensors?.registerListener(this, found, SensorManager.SENSOR_DELAY_GAME, handler)
        attached = found
        Log.d(TAG, "attached to ${found.name}")
        return true
    }

    private fun detach() {
        if (attached == null) return
        runCatching { sensors?.unregisterListener(this) }
        attached = null
    }

    private val dynamicSensors = object : SensorManager.DynamicSensorCallback() {
        override fun onDynamicSensorConnected(sensor: Sensor) {
            if (sensor.type != Sensor.TYPE_HEAD_TRACKER) return
            attach(sensor)
        }

        override fun onDynamicSensorDisconnected(sensor: Sensor) {
            if (sensor.type != Sensor.TYPE_HEAD_TRACKER) return
            detach()
            wanted = 0f
            settling = true
            handler.post(glide)
        }
    }

    /**
     * Second line of defence, because the callback is not guaranteed to arrive.
     *
     * A still head still reports, so silence for several seconds means the link is gone rather
     * than that nobody moved. Cheap to check and it costs nothing when everything is working.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!wantRunning) return
            val quiet = lastYawNanos != 0L &&
                SystemClock.elapsedRealtimeNanos() - lastYawNanos > REACQUIRE_NANOS
            if (attached == null || quiet) {
                if (quiet) Log.d(TAG, "no reports for a while, re-acquiring")
                detach()
                attach(tracker())
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    fun stop(glideHome: Boolean) {
        if (!wantRunning) return
        wantRunning = false
        runCatching { sensors?.unregisterDynamicSensorCallback(dynamicSensors) }
        handler.removeCallbacks(watchdog)
        detach()
        handler.removeCallbacks(stale)
        if (glideHome) {
            wanted = 0f
            settling = true
            handler.post(glide)
        } else {
            commanded = 0f
            wanted = 0f
            processor.headPose = null
            processor.headYawRadians = 0f
        }
    }

    /** Put the stage back in front of wherever the head is pointing now. */
    fun recentre() {
        referenceYaw = lastYaw
        referenceNanos = lastYawNanos
        stillness.reset()
        settling = true
    }

    /**
     * Start measuring the drift. The listener looks straight ahead and does not move for the
     * duration; everything the tracker reports in that time is, by definition, drift.
     */
    fun startCalibration() {
        calibrating = true
        calibrationStartNanos = 0L
        calibrationSamples = 0
        sumT = 0.0; sumY = 0.0; sumTT = 0.0; sumTY = 0.0
    }

    fun cancelCalibration() {
        calibrating = false
    }

    /**
     * A straight line through everything reported while the head was not moving.
     *
     * Least squares rather than the difference between the first and last sample, because the
     * tracker is noisy and two endpoints would inherit all of it. The slope is the drift; the
     * value at the end of the run is where the head really was, averaged over half a minute,
     * which makes a better reference than any single report.
     */
    private fun finishCalibration(): Boolean {
        calibrating = false
        if (calibrationSamples < CALIBRATION_MIN_SAMPLES) return false
        val n = calibrationSamples.toDouble()
        val denom = n * sumTT - sumT * sumT
        if (abs(denom) < 1e-9) return false
        val slope = (n * sumTY - sumT * sumY) / denom
        val intercept = (sumY - slope * sumT) / n
        val endT = (lastYawNanos - calibrationStartNanos) / 1e9
        driftRate = slope.toFloat()
        referenceYaw = (intercept + slope * endT).toFloat()
        referenceNanos = lastYawNanos
        stillness.reset()
        settling = true
        Log.d(TAG, "calibrated: drift ${"%.2f".format(Math.toDegrees(slope))} deg/s over $calibrationSamples samples")
        onCalibrated?.invoke(driftRate)
        return true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (attached == null) return
        // values[0..2] is an Euler vector: direction is the axis, length is the angle in radians.
        // Not the quaternion-tail form TYPE_ROTATION_VECTOR uses, so getRotationMatrixFromVector
        // cannot be fed this and the conversion is done by hand.
        quaternionOf(event.values[0], event.values[1], event.values[2], lastPose)
        val yaw = wrapPi(2f * atan2(lastPose[3], lastPose[0]))
        lastYaw = yaw

        // The tracker threw its own reference away, so ours means nothing either. Android does the
        // same thing here and lets the rate limit carry the stage home.
        // firstEventAfterDiscontinuity is API 33, same release that introduced the head tracker
        // itself, so in practice this only runs where it exists. Checked anyway: the field access
        // would throw rather than read false if it ever did not.
        val discontinuity = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            event.firstEventAfterDiscontinuity
        if (discontinuity || pendingRecentre) {
            pendingRecentre = false
            recentre()
        }

        // The sensor's own clock, not the arrival time. Reports arrive in bursts: two can land
        // seven milliseconds apart having been measured forty apart, and dividing a real change
        // by the wrong interval says the head is turning at a thousand degrees a second. It only
        // shows up while barely moving, where it pinned the lead to its clamp over nothing.
        val now = if (event.timestamp > 0L) event.timestamp else SystemClock.elapsedRealtimeNanos()
        lastYawNanos = now

        if (calibrating) {
            if (calibrationStartNanos == 0L) calibrationStartNanos = now
            val t = (now - calibrationStartNanos) / 1e9
            // Unwrapped against the running reference, so a calibration that happens to straddle
            // the wrap point does not fit a line through a discontinuity.
            val y = if (calibrationSamples == 0) yaw.toDouble()
                    else sumY / calibrationSamples + wrapPi((yaw - (sumY / calibrationSamples).toFloat())).toDouble()
            sumT += t; sumY += y; sumTT += t * t; sumTY += t * y
            calibrationSamples++
            if (now - calibrationStartNanos >= CALIBRATION_NANOS) finishCalibration()
        }

        stillness.add(now, yaw)
        // Not politeness: the tracker's yaw drifts about a degree a second, measured, so left
        // alone the stage wanders off to one side within a minute. Its own profile describes the
        // reference frame as arbitrary and possibly slowly drifting, so this is expected. The price is that holding a deliberate turn for six seconds makes the stage
        // follow, which is the same bargain every shipping implementation makes.
        if (stillness.isStill(now)) recentre()

        // Where the head will be by the time these samples are audible, rather than where it is.
        //
        // Unavoidable rather than clever: Bluetooth alone puts a fifth of a second between the
        // renderer and the ears, which is past the point most listeners notice the stage lagging
        // their head. The profile carries angular velocity for exactly this, and Android predicts
        // 120 ms with it, but these headphones send zeros in that field, so the rate is
        // differentiated from the poses instead and smoothed, because a difference of two samples
        // forty milliseconds apart is noisy.
        val wz = event.values.getOrElse(5) { 0f }
        if (wz != 0f) {
            yawRate = wz
        } else if (lastReportNanos != 0L) {
            val dt = (now - lastReportNanos) / 1e9f
            // A report interval either side. Outside that is a clock that jumped or a report that
            // was dropped, and a derivative across either is meaningless.
            if (dt > MIN_REPORT_INTERVAL && dt < MAX_REPORT_INTERVAL) {
                val measured = (wrapPi(yaw - lastReportYaw) / dt)
                    .coerceIn(-MAX_HEAD_RATE, MAX_HEAD_RATE)
                yawRate += (measured - yawRate) * rateSmoothing
            }
        }
        lastReportNanos = now
        lastReportYaw = yaw

        // Only part of the gap is taken. A head turn is bell shaped rather than steady, so full
        // extrapolation overshoots at the end of every movement, and an overshoot that swings back
        // is a worse artefact than the lag it removes.
        val lead = (predictFraction * lookaheadSeconds * yawRate)
            .coerceIn(-predictClamp, predictClamp)
        // The reference moves at the measured drift rate, so a tracker that slides a degree a
        // second stops taking the soundstage with it.
        val since = if (referenceNanos == 0L) 0f else (now - referenceNanos) / 1e9f
        wanted = wrapPi(yaw - (referenceYaw + driftRate * since) + lead)
        advance(now)

        if (TRACE) {
            Log.d(
                TAG,
                "yaw=%+7.1f rate=%+8.1f/s lead=%+6.1f wanted=%+7.1f commanded=%+7.1f settling=%s".format(
                    Math.toDegrees(yaw.toDouble()),
                    Math.toDegrees(yawRate.toDouble()),
                    Math.toDegrees(lead.toDouble()),
                    Math.toDegrees(wanted.toDouble()),
                    Math.toDegrees(commanded.toDouble()),
                    settling,
                ),
            )
        }

        handler.removeCallbacks(stale)
        handler.postDelayed(stale, STALE_MS)
    }

    /**
     * Move the stage to where it should be.
     *
     * Rate limited only while [settling], which is the distinction that matters. The limit exists
     * for the moments the stage has to move on its own, a recentre or a tracker that has just
     * thrown its reference away, where a snap would be jarring. Following a head is the opposite
     * case: people turn at two hundred degrees a second and the limit is forty six, so applying it
     * to ordinary tracking makes the soundstage crawl after the listener, which is exactly how it
     * feels. The smoothing that ordinary tracking does need happens per sample in the renderer.
     */
    /**
     * Hand the renderer the orientation, with our corrected yaw in place of the measured one.
     *
     * Swing-twist: any rotation splits into a turn about a chosen axis and whatever is left, and
     * here the axis is the head's own up. Take the twist out, substitute the yaw that recentring
     * and drift correction produced, and multiply the untouched remainder back on. Pitch and roll
     * survive exactly as measured and the yaw carries every correction.
     */
    private fun publish(commandedYaw: Float) {
        if (!fullSphere) {
            processor.headPose = null
            processor.headYawRadians = commandedYaw
            return
        }
        val q = lastPose
        val tw = q[0]
        val tz = q[3]
        val n = sqrt(tw * tw + tz * tz)
        if (n < 1e-6f) {
            // Head rotated a half turn about a horizontal axis, where the twist is undefined.
            // Vanishingly rare and not worth a branch anywhere else; hold the horizontal answer.
            processor.headPose = null
            processor.headYawRadians = commandedYaw
            return
        }
        // swing = q * conjugate(twist)
        val cw = tw / n
        val cz = -tz / n
        swing[0] = q[0] * cw - q[3] * cz
        swing[1] = q[1] * cw + q[2] * cz
        swing[2] = q[2] * cw - q[1] * cz
        swing[3] = q[3] * cw + q[0] * cz

        val half = commandedYaw * 0.5f
        val nw = cos(half)
        val nz = sin(half)
        poseOut[0] = swing[0] * nw - swing[3] * nz
        poseOut[1] = swing[1] * nw + swing[2] * nz
        poseOut[2] = swing[2] * nw - swing[1] * nz
        poseOut[3] = swing[3] * nw + swing[0] * nz
        processor.headPose = poseOut
    }

    private fun advance(nowNanos: Long) {
        val dt = if (lastAdvanceNanos == 0L) 0f else (nowNanos - lastAdvanceNanos) / 1e9f
        lastAdvanceNanos = nowNanos
        if (settling) {
            val cap = MAX_STAGE_RATE * dt
            val gap = wrapPi(wanted - commanded)
            if (abs(gap) <= cap) {
                commanded = wanted
                settling = false
            } else {
                commanded = wrapPi(commanded + gap.coerceIn(-cap, cap))
            }
        } else {
            commanded = wanted
        }
        publish(commanded)
    }

    /**
     * A still head still reports, so silence means the tracker is gone, not that nothing moved.
     * Never infer a dropout from the yaw having stopped changing.
     */
    private val stale = Runnable {
        wanted = 0f
        settling = true
        handler.post(glide)
    }

    /** Bring the stage back to centre at the rate limit rather than snapping it. */
    private val glide = object : Runnable {
        override fun run() {
            advance(SystemClock.elapsedRealtimeNanos())
            if (abs(commanded) > 1e-3f) {
                handler.postDelayed(this, 20L)
            } else {
                commanded = 0f
                processor.headPose = null
                processor.headYawRadians = 0f
                lastAdvanceNanos = 0L
            }
        }
    }

    companion object {
        private const val TAG = "HeadTracking"

        /** Prints every pose. For watching the pipeline rather than guessing at it. */
        private const val TRACE = false

        /** How often to check the link is still alive. */
        const val WATCHDOG_MS = 4_000L

        /** Silence longer than this means the sensor is gone, not that nobody moved. */
        const val REACQUIRE_NANOS = 3_000_000_000L

        /** Sanity bounds on the gap between two reports, either side of the profile's 20 to 40 ms. */
        const val MIN_REPORT_INTERVAL = 0.005f
        const val MAX_REPORT_INTERVAL = 0.5f

        /** Lindau measured a peak of 942 degrees a second across 22 listeners. Nothing beats it. */
        const val MAX_HEAD_RATE = 16.4f

        /** Long enough that a degree a second is a clear slope against the tracker's noise. */
        const val CALIBRATION_NANOS = 30_000_000_000L
        const val CALIBRATION_MIN_SAMPLES = 200

        /**
         * How far the head has turned about its own up axis.
         *
         * The head frame is not the usual Android one: X out the right ear, Y out the nose, Z out
         * the top of the head, which the sensor HAL flags explicitly as differing from every other
         * sensor type. So yaw is rotation about Z.
         *
         * Taken as the twist about that axis rather than the azimuth of the nose, because the two
         * agree everywhere a listener actually puts their head and disagree exactly where it
         * matters: with the nose near vertical, lying on your back with the phone overhead, the
         * azimuth form flips by 180 degrees over a couple of degrees of movement and the twist
         * does not. Wrapping also makes it immune to the quaternion double cover, so q and -q give
         * the same answer.
         *
         * Positive is a turn to the left, which is what [BinauralAudioProcessor.headYawRadians]
         * wants, so nothing is negated on the way through.
         */
        /**
         * The rotation vector the tracker reports, as a quaternion.
         *
         * Axis-angle: the direction is the axis, the length is the angle in radians. Halving that
         * for the quaternion leaves sin(t/2)/t, which is a half at the origin and not computable
         * there, and a head that has not moved reports zeroes every frame, so the common case is
         * the one that needs the series rather than the division.
         */
        fun quaternionOf(rx: Float, ry: Float, rz: Float, into: FloatArray) {
            val t2 = rx * rx + ry * ry + rz * rz
            val t = sqrt(t2)
            val k = if (t < 1e-4f) 0.5f - t2 / 48f else sin(t * 0.5f) / t
            into[0] = cos(t * 0.5f)
            into[1] = rx * k
            into[2] = ry * k
            into[3] = rz * k
        }

        fun yawOf(rx: Float, ry: Float, rz: Float): Float {
            val t2 = rx * rx + ry * ry + rz * rz
            val t = sqrt(t2)
            // sin(t/2)/t is a half at the origin and not computable there, and a head that has not
            // moved reports zero every frame, so the common case is the one that needs the series.
            val k = if (t < 1e-4f) 0.5f - t2 / 48f else sin(t * 0.5f) / t
            val qw = cos(t * 0.5f)
            val qz = rz * k
            return wrapPi(2f * atan2(qz, qw))
        }

        fun wrapPi(a: Float): Float = atan2(sin(a), cos(a))

        /** Android's kFreshnessTimeout is 120 ms against a 50 ms connection interval. */
        private const val STALE_MS = 250L

        /** Android's kMaxRotationalVelocity. A ninety degree offset comes home in two seconds. */
        const val MAX_STAGE_RATE = 0.8f

        /** Android's kAutoRecenterWindowDuration and kAutoRecenterRotationalThreshold. */
        const val AUTO_RECENTRE_WINDOW_NANOS = 6_000_000_000L
        const val AUTO_RECENTRE_THRESHOLD = 10.5f * Math.PI.toFloat() / 180f
    }
}

/**
 * Whether the head has genuinely stopped, rather than happening to be between two samples.
 *
 * Every sample in the window has to be near the newest one, not merely near its neighbour: a slow
 * steady turn passes a per-step test and fails this one, which is the whole point. Once motion is
 * seen it is held for a further window, because the test is approximate and would otherwise
 * chatter at the boundary, and [reset] after a recentre is what stops an automatic recentre from
 * immediately triggering another.
 */
class StillYaw(
    private val windowNanos: Long,
    private val thresholdRadians: Float,
) {
    private val at = LongArray(CAP)
    private val yaw = FloatArray(CAP)
    private var head = 0
    private var size = 0
    private var everFilled = false
    private var motionUntil = Long.MIN_VALUE

    fun reset() {
        head = 0
        size = 0
        everFilled = false
        motionUntil = Long.MIN_VALUE
    }

    fun add(nanos: Long, y: Float) {
        at[head] = nanos
        yaw[head] = y
        head = (head + 1) and MASK
        if (size < CAP) size++
    }

    fun isStill(nanos: Long): Boolean {
        var n = size
        while (n > 0 && nanos - at[(head - n) and MASK] > windowNanos) {
            n--
            everFilled = true
        }
        size = n
        // Not still until proven still: a window that has not filled yet says nothing.
        if (!everFilled) return false
        val newest = yaw[(head - 1) and MASK]
        for (i in 1..n) {
            val d = HeadTracking.wrapPi(yaw[(head - i) and MASK] - newest)
            if (abs(d) > thresholdRadians) {
                motionUntil = nanos + windowNanos
                return false
            }
        }
        return nanos >= motionUntil
    }

    companion object {
        /** Six seconds at the 100 Hz the profile allows, with room over. */
        const val CAP = 1024
        const val MASK = CAP - 1
    }
}

package com.dd3boh.outertune.together

import java.util.Random
import kotlin.math.abs

/** What a guest runs to follow the host: the follow loop's three parts, so the old and the new can be compared. */
interface Policy {
    fun state(state: TogetherMessage.State)
    fun pong(askedAtMs: Long, hostMs: Long, nowMs: Long)
    val known: Boolean
    fun target(state: TogetherMessage.State, nowMs: Long): Long
    fun decide(target: Long, local: Long, speed: Double, nowMs: Long): Follow
    fun sought(nowMs: Long)
    val locked: Boolean
}

/** The follow loop as the session runs it now. */
class NewPolicy(tolerance: Long = SyncLock.DEFAULT_TOLERANCE_MS) : Policy {
    private val gap = ClockGap()
    private val line = HostLine()
    private val lock = SyncLock(toleranceMs = tolerance)
    override fun state(state: TogetherMessage.State) { line.hear(state) }
    override fun pong(askedAtMs: Long, hostMs: Long, nowMs: Long) = gap.heard(askedAtMs, hostMs, nowMs)
    override val known get() = gap.known
    override fun target(state: TogetherMessage.State, nowMs: Long) = line.target(state, gap.hostNow(nowMs))
    override fun decide(target: Long, local: Long, speed: Double, nowMs: Long) = lock.decide(target, local, speed, nowMs)
    override fun sought(nowMs: Long) = lock.sought(nowMs)
    override val locked get() = lock.locked
}

/** The follow loop as it was on 26 Oct and on 14 Nov 2026 (0.12.x and the first 0.13 builds): kept to measure against. */
class OldPolicy(private val tolerance: Long = 60) : Policy {
    private var offset = 0L
    private var best = Long.MAX_VALUE
    override val known get() = best != Long.MAX_VALUE
    private var inStep = 0
    private var off = 0
    private var isLocked = false
    private val recent = ArrayDeque<Long>()
    override fun state(state: TogetherMessage.State) {}
    override fun pong(askedAtMs: Long, hostMs: Long, nowMs: Long) {
        val rt = nowMs - askedAtMs
        if (rt !in 0..10_000 || (known && rt > best + 50)) return
        if (rt <= best) best = rt
        offset = hostMs - (askedAtMs + nowMs) / 2
    }
    override fun target(state: TogetherMessage.State, nowMs: Long) = Follow.target(state, nowMs + offset)
    override val locked get() = isLocked
    override fun sought(nowMs: Long) = reset()
    private fun reset() { isLocked = false; inStep = 0; off = 0; recent.clear() }
    private fun middle(r: Collection<Long>): Double {
        val s = r.sorted(); val h = s.size / 2
        return if (s.size % 2 == 1) s[h].toDouble() else (s[h - 1] + s[h]) / 2.0
    }
    override fun decide(target: Long, local: Long, speed: Double, nowMs: Long): Follow {
        val drift = target - local
        val gapMs = abs(drift)
        if (gapMs > Follow.DRIFT_MS) { reset(); return Follow.Seek(target) }
        recent.addLast(drift)
        if (recent.size > 16) recent.removeFirst()
        if (isLocked) {
            off = if (gapMs > tolerance) off + 1 else 0
            val lagging = recent.size == 16 && abs(middle(recent)) > Follow.IN_STEP_MS
            if (off < 4 && !lagging) return Follow.Steady(speed)
            isLocked = false; off = 0; recent.clear()
        }
        inStep = if (gapMs <= Follow.IN_STEP_MS) inStep + 1 else 0
        if (inStep >= 8) isLocked = true
        return Follow.decide(target, local, speed, Follow.MAX_NUDGE)
    }
}

/**
 * A guest in a room, simulated: the host's clock seconds off the guest's, its word and the guest's
 * pings travelling through public relays (a round trip of 670 to 850 ms, as on 26 Oct 2026, one way
 * 250 to 450 ms), the host's player position wobbling by 45 ms from one word to the next, and the
 * guest's follow loop run as the session runs it, a look every 250 ms. Counts what the ears hear:
 * each speed change flushes the audio chain (DefaultAudioSink.setupAudioProcessors), each seek is a cut.
 */
class TogetherSyncSim(
    seed: Long,
    /** Where this guest starts against the host, ms: 0 in step, -2000 two seconds behind. */
    private val startOffsetMs: Long = 0,
    private val spikes: Boolean = false,
    private val policy: Policy,
    private val hostWobbleMs: Double = 45.0,
) {
    class Result(
        val speedChanges: Int,
        val seeks: Int,
        /** Seconds from start until the real gap stays within 100 ms for good, or -1 when it never did. */
        val backInStepAtS: Double,
        val seconds: Double,
        /** The real gap to the host, ms: the mean and the 95th percentile of its size, after the first seconds counted. */
        val meanGapMs: Double = 0.0,
        val p95GapMs: Double = 0.0,
    ) {
        val speedPerMin get() = speedChanges / (seconds / 60)
        val seeksPerMin get() = seeks / (seconds / 60)
    }

    private val rnd = Random(seed)
    private fun delay(): Long {
        val base = 250 + rnd.nextInt(200)
        val spike = if (spikes && rnd.nextInt(10) == 0) 400 + rnd.nextInt(800) else 0
        return (base + spike).toLong()
    }

    /** Runs [seconds] of the room and counts, after [skipS] seconds, what happened. */
    fun run(seconds: Int, skipS: Int = 0): Result {
        val hostOff = 3_700L + rnd.nextInt(2_000)
        fun hostClock(t: Long) = t + hostOff + (t * 30 / 1_000_000)      // 30 ppm of its own
        val song0 = 60_000L
        fun hostPos(t: Long) = song0 + t
        var pos = (song0 + startOffsetMs).toDouble()
        var speed = 1.0
        var settleUntil = 0L
        var lastState: TogetherMessage.State? = null
        class Msg(val arrive: Long, val run: () -> Unit)
        val inbox = ArrayList<Msg>()
        var nextPing = 500L
        var pings = 0
        var nextState = 0L
        var lastT = 0L
        var lastOutOfStepT = -1L
        val gaps = ArrayList<Double>()
        val end = seconds * 1000L
        var t = 0L
        var changes = 0
        var seeks = 0
        while (t <= end) {
            pos += (t - lastT) * speed
            lastT = t
            while (nextState <= t) {
                val sentAt = nextState
                val st = TogetherMessage.State(
                    songId = "s", title = "", artist = "", positionMs = hostPos(sentAt) + (rnd.nextGaussian() * hostWobbleMs).toLong(),
                    playing = true, speed = 1.0, atMs = hostClock(sentAt),
                )
                inbox += Msg(sentAt + delay()) {
                    if (lastState == null || st.atMs >= lastState!!.atMs) { lastState = st; policy.state(st) }
                }
                nextState += TogetherMessage.HEARTBEAT_MS
            }
            while (nextPing <= t) {
                val asked = nextPing
                val out = delay(); val back = delay()
                val hostMs = hostClock(asked + out)
                inbox += Msg(asked + out + back) { policy.pong(asked, hostMs, asked + out + back) }
                pings++
                nextPing += if (pings < 10) 1_000L else 15_000L
            }
            inbox.filter { it.arrive <= t }.sortedBy { it.arrive }.forEach { it.run() }
            inbox.removeAll { it.arrive <= t }
            val st = lastState
            if (st != null && policy.known && t >= settleUntil) {
                val target = policy.target(st, t)
                val local = pos.toLong() + (rnd.nextGaussian() * 8).toLong()
                var newSpeed = speed
                when (val decision = policy.decide(target, local, 1.0, t)) {
                    is Follow.Seek -> {
                        pos = decision.positionMs.toDouble() + rnd.nextGaussian() * 15
                        settleUntil = t + 500
                        policy.sought(t)
                        if (t >= skipS * 1000L) seeks++
                    }
                    is Follow.Nudge -> newSpeed = decision.speed
                    is Follow.Steady -> newSpeed = decision.speed
                }
                if (abs(newSpeed - speed) >= 0.001) {
                    if (t >= skipS * 1000L) changes++
                    speed = newSpeed
                }
            }
            if (abs(pos - hostPos(t)) > 100) lastOutOfStepT = t
            if (t >= skipS * 1000L) gaps += abs(pos - hostPos(t))
            t += 250
        }
        val back = if (lastOutOfStepT < 0) 0.0 else if (lastOutOfStepT >= end - 1_000) -1.0 else (lastOutOfStepT + 250) / 1000.0
        gaps.sort()
        return Result(changes, seeks, back, (seconds - skipS).toDouble(), gaps.average(), gaps[(gaps.size * 0.95).toInt().coerceAtMost(gaps.size - 1)])
    }
}

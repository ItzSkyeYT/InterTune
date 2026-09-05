/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.os.SystemClock
import android.util.Log
import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * One flag: YouTube is currently refusing this network.
 *
 * WHY THIS EXISTS. YouTube rate limits by public IP, not by account, client or song. Established by
 * direct probing, not guessed: four InnerTube clients (VISIONOS, IOS, ANDROID_VR, TVHTML5) returned
 * identical answers on the same videos; 1 of 6 videos played, and that one is the most requested
 * video on the platform and is almost certainly edge cached; rotating visitorData changed nothing;
 * moving the phone from Wi-Fi to mobile data fixed it instantly. It clears on its own. The only
 * thing this app can do wrong is keep asking, which is exactly what a few hundred queued downloads
 * do, and it does it faster when it is failing than when it is working, because no audio transfer
 * happens to slow the loop down.
 *
 * DESIGN RULES, each one a way this could have wedged:
 *  - In memory only. A "blocked until" written to DataStore is the one shape of this that can leave
 *    the app permanently refusing to work. A restart is always a full reset.
 *  - Bounded. The stop is never longer than [MAX_BACKOFF_MS], whatever happens.
 *  - Four independent ways out: the timer, any successful /player answer, any network change, and
 *    process death. Losing three of them still recovers.
 *  - It never refuses a request the user explicitly asked for. Tapping play still tries, because
 *    one request is the cheapest possible probe and doubles as the reset. What it stops is work
 *    nobody asked for: downloads, background sync, scans, history pings.
 */
object Throttle {
    private const val TAG = "Throttle"

    /** 5 minutes, then 10, then 20. Short enough that a false positive is barely felt. */
    private const val FIRST_BACKOFF_MS = 5 * 60 * 1000L
    private const val MAX_BACKOFF_MS = 30 * 60 * 1000L

    /** A clean stretch this long resets the escalation ladder. */
    private const val STRIKE_RESET_MS = 2 * 60 * 60 * 1000L

    /**
     * Consecutive whole-resolve failures before backing off on the count alone.
     *
     * 8, the same number and the same reasoning as LoudnessRepair.FAILURE_ABORT_THRESHOLD
     * (utils/LoudnessRepair.kt:242): a handful of failures is normal, a long unbroken run means
     * something systemic. This exists only so that a wording change on YouTube's side cannot leave
     * the mechanism blind.
     */
    private const val FAILURE_TRIP_THRESHOLD = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timer: Job? = null

    private val _blocked = MutableStateFlow(false)

    /** For UI. Read [isBlocked] in logic. */
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    val isBlocked: Boolean get() = _blocked.value

    /** Elapsed realtime, so a clock or timezone change can neither extend nor shorten the stop. */
    @Volatile
    private var until = 0L

    private val consecutiveFailures = AtomicInteger(0)

    @Volatile
    private var strikes = 0

    @Volatile
    private var lastTripAt = 0L

    /** Seconds left, for the developer row and for logs. 0 when not blocked. */
    val secondsRemaining: Long
        get() = if (!isBlocked) 0
        else ((until - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)

    /**
     * YouTube's own words for this condition.
     *
     * Matched on text because it is the only thing in the response that separates "this network is
     * in trouble" from "this song is not available", and getting that wrong in either direction is
     * bad: treating an unavailable song as a block would stop the whole library over one region
     * locked track, and treating a block as an unavailable song is what the app does today.
     *
     * playabilityStatus.status is deliberately not used on its own. ANDROID_VR answers
     * LOGIN_REQUIRED on a perfectly healthy network, which is documented at
     * YTPlayerUtils.kt:266-274 from a direct probe, so it is not a discriminator.
     */
    fun looksLikeBlock(reason: String?): Boolean {
        val r = reason?.lowercase() ?: return false
        // "not a bot" is the distinctive phrase. The looser "sign in to confirm" is kept only as a
        // hedge against a wording change, and must exclude the age gate: "Sign in to confirm your
        // age" is one restricted song, not a refused network, and treating it as one would stop
        // every download over a single track.
        return "not a bot" in r || ("sign in to confirm" in r && "age" !in r)
    }

    /** What one /player answer implies. Cheap; safe to call on every request. */
    fun note(status: String?, reason: String?) {
        when {
            looksLikeBlock(reason) -> trip("YouTube said: $reason")
            status == "OK" -> clear("a request succeeded")
            // Anything else is a per-song refusal: UNPLAYABLE, ERROR, age gate, region lock. Not a
            // verdict on the network, so it neither trips nor clears.
            else -> Unit
        }
    }

    /** A thrown /player failure. HTTP 429 is the same condition wearing its other face. */
    fun noteFailure(t: Throwable) {
        val code = (t as? ResponseException)?.response?.status?.value
        if (code == 429) {
            trip("HTTP 429 from /player")
            return
        }
        if (consecutiveFailures.incrementAndGet() >= FAILURE_TRIP_THRESHOLD) {
            trip("$FAILURE_TRIP_THRESHOLD /player failures in a row")
        }
    }

    fun trip(why: String) {
        // Already stopped means this is the same incident being reported again, not a new one.
        // Resolving one song asks three clients and every one of them reports the block, so without
        // this the ladder walked 5 to 10 to 20 minutes inside a second and the first two rungs were
        // never actually used. Escalation is meant to measure repeat incidents, and the timer
        // already covers the current one.
        if (isBlocked) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTripAt > STRIKE_RESET_MS) strikes = 0
        lastTripAt = now

        val backoff = (FIRST_BACKOFF_MS shl strikes.coerceAtMost(2)).coerceAtMost(MAX_BACKOFF_MS)
        if (strikes < 2) strikes++

        until = now + backoff
        consecutiveFailures.set(0)
        _blocked.value = true
        Log.w(TAG, "Backing off for ${backoff / 1000}s: $why")

        timer?.cancel()
        timer = scope.launch {
            while (true) {
                val left = until - SystemClock.elapsedRealtime()
                if (left <= 0) break
                delay(left)
            }
            clear("the back off expired")
        }
    }

    fun clear(why: String) {
        consecutiveFailures.set(0)
        if (!_blocked.value) return
        timer?.cancel()
        timer = null
        until = 0
        _blocked.value = false
        Log.i(TAG, "Resuming, $why")
    }

    /**
     * A different network almost certainly means a different public IP.
     *
     * This is the one that matters most: it is literally what fixed it by hand. Over-clearing is
     * safe and under-clearing is not, so this fires on every onAvailable rather than trying to work
     * out whether the IP really changed. The cost of being wrong is one extra request, which
     * immediately re-trips.
     */
    fun onNetworkChanged() = clear("the network changed")

    /** True if this playback failure is the block, wherever media3 buried it in the cause chain. */
    fun isBlock(error: Throwable?): Boolean {
        var t = error
        var depth = 0
        while (t != null && depth++ < 8) {
            if (looksLikeBlock(t.message)) return true
            t = t.cause
        }
        return false
    }
}

/** Records what a /player answer implies about the network and returns it untouched. */
fun Result<PlayerResponse>.noteThrottle(): Result<PlayerResponse> = also { r ->
    r.fold(
        onSuccess = { Throttle.note(it.playabilityStatus.status, it.playabilityStatus.reason) },
        onFailure = { Throttle.noteFailure(it) }
    )
}

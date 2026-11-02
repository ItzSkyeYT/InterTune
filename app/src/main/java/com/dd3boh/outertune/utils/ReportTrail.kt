/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.media3.common.Player
import kotlin.math.abs

/**
 * The last few minutes of what the person did, kept in memory for Report a problem: the screens
 * opened, the settings changed, a song started, skipped or sought, a playback error, the network
 * lost and back, the app sent to the background. His idea of 2 Nov 2026: a log says what the app
 * did, and a fault is often only understood next to what was done just before it.
 *
 * It is a small ring and nothing else. Nothing is written to a file and nothing leaves the phone
 * unless the person sends the report, which shows it first. A process that ends takes it along, so
 * the crash handler copies it into the crash it keeps ([CrashLog]).
 *
 * What it may hold is decided here and nowhere else. Every step is a fixed phrase with at most a
 * number or a setting's name in it: no song title or id, no search words, no account name or email,
 * no folder, no address. Screens are named by the route's pattern, never by its arguments, and only
 * the settings that [ReportSettings] lists can be named. The text is scrubbed once more on the way
 * in ([ProblemLog.scrub]), and the test holds each way in to this.
 */
class ReportTrail(
    private val capacity: Int = CAPACITY,
    private val keepMs: Long = KEEP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Off, nothing is kept: a release build does not have the feature (Unreleased.REPORT_PLUS). */
    @Volatile
    var enabled: Boolean = false

    /**
     * One step: when, what, how many times in a row. [key] is what a step is merged with the one before it by.
     * [weight] is how much it tells: when there are more steps than the report shows, the lightest go first.
     */
    class Step(val at: Long, val text: String, val count: Int = 1, internal val key: String? = null, internal val weight: Int = TELLS) {
        override fun toString() = if (count > 1) "$text (x$count)" else text
    }

    private val steps = ArrayDeque<Step>()

    // ---- what can be written down ----

    /** The screen opened last, by its name; the problem's area may be read from it. */
    @Volatile
    var lastScreen: String? = null
        private set

    /**
     * A screen opened, by its route's pattern: "album/{albumId}?song={song}" is "album". The screen the
     * person is already on is no step: turning the phone makes the activity anew and reports it again.
     */
    fun screen(route: String?) {
        screenName(route)?.let {
            if (it == lastScreen) return
            if (enabled) lastScreen = it
            add("opened $it", weight = SCREEN)
        }
    }

    /** A setting changed, by the name and the value [ReportSettings] gives it. */
    fun setting(label: String, value: String) = add("set $label to $value")

    /** The signed-in state changed. */
    fun signedIn(now: Boolean) = add(if (now) "signed in" else "signed out")

    /** A song started, and how it came to be playing. */
    fun songStarted(reason: Int, chosen: Boolean, wentBack: Boolean, afterError: Boolean, casting: Boolean = false) {
        val how = songHow(reason, chosen, wentBack, afterError)
        add("song started ($how${if (casting) ", casting" else ""})")
    }

    /** The playhead moved inside a song, to [positionMs]. Seeks close together are one step. */
    fun seeked(positionMs: Long) = add("seeked to ${clockText(positionMs)}", key = "seek", weight = CONTEXT)

    fun paused(why: String? = null) = add(if (why == null) "paused" else "paused: $why", weight = CONTEXT)

    fun resumed() = add("resumed", weight = CONTEXT)

    /** The player failed: its code's name, and the HTTP status when the failure was one. */
    fun playbackError(codeName: String, httpStatus: Int? = null) {
        val code = codeName.removePrefix("ERROR_CODE_").take(MOST_CODE)
        add("playback error: $code${httpStatus?.let { " $it" }.orEmpty()}")
    }

    fun gaveUp(tries: Int) = add("gave up on the song after $tries tries")

    /** A download ended in failure; the song it was is not told. Those that finish are not steps: a batch would fill the trail. */
    fun downloadFailed() = add("a download failed")

    /** The player waited [ms] for data and played again. */
    fun waited(ms: Long) {
        if (ms >= LONG_WAIT_MS) add("waited ${waitText(ms)} for data")
    }

    fun network(event: Network) = add(
        when (event) {
            Network.LOST -> "network lost"
            Network.BACK -> "network back"
        },
        weight = CONTEXT,
    )

    /** The network in use changed to [kind] ("Wi-Fi", "mobile data"), when it is known. */
    fun networkChangedTo(kind: String?) = add(if (kind == null) "network changed" else "network changed to $kind", weight = CONTEXT)

    fun app(event: App) = add(
        when (event) {
            App.OPENED -> "app opened"
            App.BACK_IN_FRONT -> "app back in front"
            App.IN_BACKGROUND -> "app sent to the background"
        },
        weight = CONTEXT,
    )

    enum class App { OPENED, BACK_IN_FRONT, IN_BACKGROUND }

    enum class Network { LOST, BACK }

    // ---- keeping it ----

    private fun add(text: String, key: String? = null, weight: Int = TELLS) {
        if (!enabled) return
        val at = clock()
        val clean = ProblemLog.scrub(text).replace(WHITESPACE, " ").trim().take(MOST_TEXT)
        if (clean.isEmpty()) return
        synchronized(this) {
            val last = steps.lastOrNull()
            when {
                // The same step told twice at once: two observers of the network, one of each.
                last != null && last.text == clean && last.key == null && at - last.at <= SAME_MS -> return
                // A slider dragged or a button held: one step, the latest, with how many.
                last != null && key != null && last.key == key && at - last.at <= MERGE_MS -> {
                    steps.removeLast()
                    steps.addLast(Step(at, clean, last.count + 1, key, weight))
                }
                else -> steps.addLast(Step(at, clean, 1, key, weight))
            }
            while (steps.size > capacity) steps.removeFirst()
            while (steps.isNotEmpty() && at - steps.first().at > keepMs) steps.removeFirst()
        }
    }

    /** Every step kept, the oldest first. */
    @Synchronized
    fun steps(): List<Step> = steps.toList()

    @Synchronized
    fun clear() {
        steps.clear()
        lastScreen = null
    }

    // ---- showing it ----

    /**
     * The steps around a problem, as lines for the report. [faultAt] is when the last fault was
     * written (epoch milliseconds), or null when none was found: the steps are then those before
     * [now]. A fault older than the steps kept has none around it, and the report falls back to
     * [now] with the header saying so. For a crash ([crash]), which is now, the steps before it.
     */
    fun view(faultAt: Long?, now: Long = clock(), crash: Boolean = false): View? {
        val all = steps()
        val aroundFault = !crash && faultAt != null && all.any { it.at in (faultAt - BEFORE_MS)..(faultAt + AFTER_MS) }
        val anchor = if (aroundFault) faultAt!! else now
        val inWindow = all.filter { it.at in (anchor - BEFORE_MS)..(anchor + AFTER_MS) }
        val before = inWindow.filter { it.at <= anchor }
        val after = inWindow.filter { it.at > anchor }.take(MAX_AFTER)
        if (before.isEmpty() && after.isEmpty()) return null
        val shown = pick(before, MAX_BEFORE)
        val lines = buildList {
            val left = before.size - shown.size
            if (left > 0) add("(${if (left == 1) "1 step" else "$left steps"} left out)")
            shown.forEach { add("${relative(it.at - anchor)} $it") }
            add("${relative(0)} ${if (aroundFault || crash) ">>> here" else ">>> now"}")
            after.forEach { add("${relative(it.at - anchor)} $it") }
        }
        return View(if (crash) HEADER_CRASH else if (aroundFault) HEADER_FAULT else HEADER_NOW, lines)
    }

    /**
     * [steps] cut to [max]: the ones that tell least go first (screens opened, then pauses, seeks and the network,
     * before settings changed, songs started and errors), the oldest first among equals, and the screen opened
     * last stays, since what was changed is read against where. What is kept stays in its order.
     */
    private fun pick(steps: List<Step>, max: Int): List<Step> {
        if (steps.size <= max) return steps
        val keepScreen = steps.lastOrNull { it.weight == SCREEN }
        val gone = steps.withIndex()
            .filter { it.value !== keepScreen }
            .sortedWith(compareBy({ it.value.weight }, { it.index }))
            .take(steps.size - max)
            .map { it.index }
            .toSet()
        return steps.filterIndexed { i, _ -> i !in gone }
    }

    /** The steps as the report carries them: [header], then a line each. */
    class View(val header: String, val lines: List<String>) {
        fun text(): String = (listOf(header) + lines).joinToString("\n")
    }

    companion object {
        /** Steps kept, and for how long: the report shows the last few minutes of them. */
        const val CAPACITY = 200
        const val KEEP_MS = 30 * 60_000L

        /** How far around the problem the report looks, and the most it shows of each side. */
        const val BEFORE_MS = 4 * 60_000L
        const val AFTER_MS = 45_000L
        const val MAX_BEFORE = 14
        const val MAX_AFTER = 4

        /** How much a step tells, for what to keep when there are too many: see [pick]. */
        internal const val SCREEN = 0
        internal const val CONTEXT = 1
        internal const val TELLS = 2

        /** The same phrase twice within this is one step. */
        private const val SAME_MS = 1_500L

        /** Steps of a kind with a key, within this of each other, are one step. */
        private const val MERGE_MS = 4_000L

        /** A wait for data shorter than this is every song's start, not a fault. */
        const val LONG_WAIT_MS = 3_000L

        private const val MOST_TEXT = 90
        private const val MOST_CODE = 40
        private val WHITESPACE = Regex("""\s+""")

        /** Both headers begin with this: [ProblemLog.fitted] finds the block by it. */
        const val HEADER_START = "Steps before the "
        const val HEADER_FAULT = HEADER_START + "fault (no song, search or name):"
        const val HEADER_NOW = HEADER_START + "report (no song, search or name):"
        const val HEADER_CRASH = HEADER_START + "crash (no song, search or name):"

        /** This run's. Switched on at start in a build that has the feature (App). */
        val thisRun = ReportTrail()

        /** "-2:48" before the problem, "+0:05" after it, "0:00" at it. Rounded to the second. */
        fun relative(deltaMs: Long): String {
            val seconds = (abs(deltaMs) + 500) / 1000
            val text = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
            return when {
                seconds == 0L -> text
                deltaMs < 0 -> "-$text"
                else -> "+$text"
            }
        }

        /** "1:12" for a position in a song, "1:02:05" past the hour. */
        fun clockText(ms: Long): String {
            val s = (ms.coerceAtLeast(0) + 500) / 1000
            return if (s >= 3600) "${s / 3600}:${(s % 3600 / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
            else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }

        /** "7 s" up to a minute, "2 min 5 s" past it. */
        fun waitText(ms: Long): String = if (ms < 60_000) "${(ms + 500) / 1000} s" else "${ms / 60_000} min ${ms % 60_000 / 1000} s"

        /**
         * A route's pattern as a screen's name, without anything a route is filled in with: the
         * arguments in braces and the query after a question mark go. "settings/player" stays,
         * "artist/{artistId}/songs" is "artist/songs", "search/{query}" is "search". Null for no route.
         */
        fun screenName(route: String?): String? = route
            ?.substringBefore('?')
            ?.replace(ARGUMENT, "")
            ?.split('/')
            // A pattern's words are lower case with underscores; anything else is something filled in (an id), and goes.
            ?.filter { WORD.matches(it) }
            ?.joinToString("/")
            ?.takeIf { it.isNotEmpty() && it.length <= MOST_TEXT }

        private val ARGUMENT = Regex("""/?\{[^}]*\}""")
        private val WORD = Regex("""[a-z][a-z_]*""")

        /** How a song came to be playing, in a word or two: the player's reason, and what the app knows of the pick. */
        fun songHow(reason: Int, chosen: Boolean, wentBack: Boolean, afterError: Boolean): String = when {
            chosen -> "picked"
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "the last one ended"
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeated"
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "new queue"
            afterError -> "skipped past an error"
            wentBack -> "went back"
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "skipped"
            else -> "reason $reason"
        }

        /**
         * When a log line was written, as epoch milliseconds, from its time of day ("14:02:11.345") and
         * the moment [now] the report is made: the last time that clock read it, so a line from before
         * midnight is yesterday's. Null for a time that is not one.
         */
        fun faultTime(timeOfDay: String, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
            val m = TIME.matchEntire(timeOfDay.trim()) ?: return null
            val (h, min, s, ms) = m.destructured
            val cal = java.util.Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, h.toInt())
                set(java.util.Calendar.MINUTE, min.toInt())
                set(java.util.Calendar.SECOND, s.toInt())
                set(java.util.Calendar.MILLISECOND, ms.padEnd(3, '0').take(3).toInt())
            }
            // A minute of slack for clocks that disagree; past that the time is yesterday's.
            if (cal.timeInMillis > now + 60_000L) cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            return cal.timeInMillis
        }

        /** Hours, minutes, seconds and the fraction, which may be missing. */
        private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})\.?(\d{0,6})""")
    }
}

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.os.Build
import com.dd3boh.outertune.BuildConfig

/**
 * What the app wrote to the phone's log since it was opened, cut down to the last thing that
 * went wrong and what led up to it: the part of a log worth pasting into a bug report.
 *
 * A fault with no message on screen made a report nobody could act on: "it skips at 30 seconds,
 * no error" (issue #20). The answer was in the log all along, a dozen lines of it, and the person
 * had no way to get at them without a computer. Android lets an app read its own lines, so this
 * reads them, finds the last one that says something failed, and keeps the lines before it.
 *
 * Nothing is kept anywhere and nothing is sent. The text is shown, and it leaves the phone when
 * the person copies it or opens the form themselves. Network addresses and the names of the
 * servers a song came from are taken out first: see [scrub].
 *
 * A release build has no debug lines (R8 takes Log.d and Log.v out), so what leads up to a fault
 * there is whatever was written as information, a warning or an error.
 */
object ProblemLog {
    /** Lines kept before the fault's first line, and after it: a trace with its causes is two dozen. */
    const val BEFORE = 40
    const val AFTER = 24

    /** With nothing that looks like a fault, the last lines as they are. */
    const val TAIL = 60

    private const val READ_AT_MOST = 5000

    class Line(val time: String, val level: Char, val tag: String, val message: String) {
        override fun toString() = "$time $level/$tag: $message"
    }

    /** [lines] to show, and where among them the fault begins: null when nothing looked like one. */
    class Excerpt(val lines: List<Line>, val faultAt: Int?)

    /** `logcat -v time`: "10-13 18:44:40.811 E/MediaCodecAudioRenderer( 6711): Audio sink error". */
    private val SHAPE = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d+) ([VDIWEF])/(.*?)\(\s*\d+\): ?(.*)$""")

    /**
     * Whose ordinary lines say what the app was doing with a song or with the network. Everything
     * else is kept only when it is a warning or an error: the views and the audio codec write
     * thousands of lines an hour that say nothing about a fault.
     */
    private val TELLING = setOf(
        "MusicService", "YTPlayerUtils", "QueueBoard", "StandIns", "Throttle", "DownloadUtil",
        "PlayerConnection", "MediaLibrarySessionCallback", "App", "MainActivity", "LibraryImport",
    )

    /**
     * Whose errors count as a fault: the app's own and the player's. Every phone has parts that
     * write errors of their own all day long, and the last of those is not what went wrong.
     */
    private val FAULTS_OF = TELLING + setOf(
        "ExoPlayerImplInternal", "MediaCodecAudioRenderer", "MediaCodecRenderer", "LoadTask", "DefaultAudioSink",
        "SimpleCache", "CacheDataSink", "AndroidRuntime",
    )

    /** Warnings every phone writes for every song, whatever happens next. */
    private val CHATTER = setOf("AudioTrack", "LoudnessCodecController", "MediaCodec", "CCodec", "Codec2Client", "BufferQueueProducer")
    private val CHATTER_WORDS = listOf("AIBinder_linkToDeath", "Failed to query component")

    fun parse(raw: String): Line? = SHAPE.find(raw)?.destructured?.let { (time, level, tag, message) ->
        Line(time, level[0], tag.trim(), message)
    }

    /** A line of a trace under the line that began it. */
    private fun continues(message: String) =
        message.isEmpty() || message[0].isWhitespace() || message.startsWith("at ") || message.startsWith("Caused by") || message.startsWith("...")

    /** Whether a line is worth showing: every warning and error but the chatter, and what [TELLING] says. */
    fun keep(line: Line): Boolean = when (line.level) {
        'W', 'E', 'F' -> CHATTER_WORDS.none { it in line.message } &&
            !(line.level == 'W' && line.tag in CHATTER) &&
            // printStackTrace writes a failed request's whole trace here, frame by frame.
            !(line.tag == "System.err" && continues(line.message))
        else -> line.tag in TELLING
    }

    /** The first line of something that failed: an error of the app's or the player's, not the middle of a trace. */
    fun beginsFault(line: Line): Boolean =
        (line.level == 'E' || line.level == 'F') && line.tag in FAULTS_OF && !continues(line.message)

    /** The last fault among [raw] with what led up to it, or the last lines when there is none. */
    fun excerpt(raw: List<String>): Excerpt {
        val kept = raw.mapNotNull(::parse).filter(::keep)
        val at = kept.indexOfLast(::beginsFault)
        if (at < 0) return Excerpt(kept.takeLast(TAIL), null)
        val from = (at - BEFORE).coerceAtLeast(0)
        return Excerpt(kept.subList(from, (at + 1 + AFTER).coerceAtMost(kept.size)), at - from)
    }

    private val STREAM_HOST = Regex("""[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.googlevideo\.com""")

    /**
     * Without what says where the phone is: network addresses ([ErrorText]) and the name of the
     * server a song came from, which names the provider and the town. Song ids stay: the fault is
     * often one song's, and the id is how it is found again.
     */
    fun scrub(text: String): String = ErrorText.withoutAddresses(text).replace(STREAM_HOST, "a stream host")

    /** What is copied: the build and the phone, then the lines. */
    fun report(excerpt: Excerpt, build: String = build(), device: String = device()): String = buildString {
        appendLine("InterTune $build")
        appendLine(device)
        appendLine(if (excerpt.faultAt == null) "Nothing that looks like a fault. The last lines:" else "The last fault and what led up to it:")
        appendLine()
        excerpt.lines.forEachIndexed { i, line ->
            if (i == excerpt.faultAt) appendLine(">>> here")
            appendLine(scrub(line.toString()))
        }
    }.trimEnd()

    /**
     * [report] in no more than [limit] characters: the build and the phone, then as much of the
     * end as fits, from the start of a line. The end is where the fault is.
     */
    fun fitted(report: String, limit: Int): String {
        if (report.length <= limit) return report
        val head = report.lineSequence().take(3).joinToString("\n") + "\n(the earlier lines are left out)\n"
        val tail = report.takeLast((limit - head.length).coerceAtLeast(0))
        return head + tail.substringAfter('\n', tail)
    }

    fun build(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE})"

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /**
     * This process's own lines, oldest first, the last [READ_AT_MOST] of them. Android hands an
     * app nobody else's, and needs no permission for its own. Empty when the log cannot be read.
     * Blocking: not for the main thread.
     *
     * The count is kept here and not asked of logcat: its "-t" counts the lines of every app on
     * the phone before it looks at whose they are, and of the last five thousand of those a
     * handful are this app's.
     */
    fun read(): List<String> = runCatching {
        val logcat = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        try {
            val last = ArrayDeque<String>()
            logcat.inputStream.bufferedReader().forEachLine { line ->
                last.addLast(line)
                if (last.size > READ_AT_MOST) last.removeFirst()
            }
            last.toList()
        } finally {
            logcat.destroy()
        }
    }.getOrDefault(emptyList())
}

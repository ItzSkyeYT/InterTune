/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import com.dd3boh.outertune.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log lines, kept one file per time the app runs, so Report a problem can offer an
 * earlier one: the run before an update, or yesterday's, when something went wrong.
 *
 * The phone's log forgets within the hour on a busy phone, and everything at a restart, so what
 * led up to a fault in the version before an update was gone by the time anyone came to report it
 * (asked for on 13 Oct 2026, built on 20 Oct). Each time the app's process starts, a logcat of that
 * process's lines runs beside it, the same lines Report a problem shows ([ProblemLog.TELLING] at
 * every level, everything else from warnings up), and a thread here writes each line to the run's
 * file as it comes. Not logcat's own "-f": it writes its file in blocks of 4 KB, and the block
 * under way is lost when the process is killed, which is where a fault usually is (seen on the
 * emulator, 20 Oct 2026). A crash is written here too, by [crashed], before the process goes.
 *
 * Nothing is sent: a file leaves the phone only through Report a problem, scrubbed like the rest.
 * The folder stays out of backups and transfers ([FOLDER] in backup_rules.xml and
 * data_extraction_rules.xml): it belongs to this phone, and Android's cloud backup takes the app's
 * files up to 25 MB in all or none of them.
 */
object RunLogs {
    /** The runs kept, this one among them. */
    const val KEEP = 15

    /** Kilobytes a run's file holds before logcat starts it again, keeping the earlier half as ".1". */
    const val ROTATE_KB = 512

    const val FOLDER = "logs"

    /** One run's file: when its process started, and the version that ran. */
    class Run(val file: File, val started: Date, val versionCode: Int, val versionName: String) {
        /** The build line of a report from this run: the version that wrote it, on this app's flavour. */
        val build: String get() = "$versionName ($versionCode, ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE})"
    }

    private const val STAMP = "yyyyMMdd-HHmmss"

    /** "20261020-141203_95_0.12.log": sorts by time, and says which version wrote it. */
    fun fileName(started: Date, versionCode: Int, versionName: String): String =
        "${SimpleDateFormat(STAMP, Locale.ROOT).format(started)}_${versionCode}_${versionName.replace('/', '-')}.log"

    private val NAME = Regex("""^(\d{8}-\d{6})_(\d+)_(.+)\.log$""")

    fun parse(file: File): Run? = NAME.find(file.name)?.destructured?.let { (stamp, code, name) ->
        val started = runCatching { SimpleDateFormat(STAMP, Locale.ROOT).parse(stamp) }.getOrNull() ?: return null
        Run(file, started, code.toIntOrNull() ?: return null, name)
    }

    /**
     * Which of [runs] to delete so that [keep] are left: the oldest first, and before them any run
     * that wrote nothing at all, which a start in the background with nothing to say leaves.
     */
    fun toForget(runs: List<Run>, keep: Int, isEmpty: (Run) -> Boolean): List<Run> {
        val (empty, written) = runs.partition(isEmpty)
        return empty + written.sortedByDescending { it.started }.drop(keep)
    }

    /** logcat's filter: the lines [ProblemLog] reads, so a run's file holds what a report can show. */
    fun filters(): List<String> =
        ProblemLog.TELLING.map { "$it:V" } + ProblemLog.TELLING_FROM_INFO.map { "$it:I" } + "*:W"

    /** This process's file once [start] has made it. */
    @Volatile
    var current: File? = null
        private set

    private fun folder(context: Context) = File(context.filesDir, FOLDER)

    /** Every run's file, this one too, newest first. */
    fun runs(context: Context): List<Run> = runsIn(folder(context))

    internal fun runsIn(dir: File): List<Run> = dir.listFiles()?.mapNotNull(::parse)?.sortedByDescending { it.started }.orEmpty()

    /**
     * This run's lines from its own file, or null when no file is being written (the phone's log is
     * read then). The file holds the run from its start, where the phone's log may have turned
     * over: under a minute on the emulator, about an hour on a busy phone.
     */
    fun now(): List<String>? = current?.let(::parse)?.let(::read)

    /** The runs before this one, newest first. */
    fun earlier(context: Context): List<Run> = runs(context).filter { it.file != current }

    /**
     * Starts this process's file, after forgetting the oldest. Blocking, for a background thread;
     * logcat hands over the process's lines from its start, so a late start loses none.
     */
    fun start(context: Context) {
        if (current != null) return
        runCatching {
            val dir = folder(context).apply { mkdirs() }
            stopStrayWriters()
            toForget(runs(context), KEEP - 1) { it.file.length() == 0L && !rotated(it.file).exists() }
                .forEach { it.file.delete(); rotated(it.file).delete() }
            val file = File(dir, fileName(Date(), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME))
            val logcat = ProcessBuilder(listOf("logcat", "-v", "time", "--pid=${android.os.Process.myPid()}") + filters())
                .redirectErrorStream(true)
                .start()
            writer = Writer(file)
            current = file
            Thread({
                runCatching { logcat.inputStream.bufferedReader().forEachLine { writer?.line(it) } }
            }, "RunLogs").apply { isDaemon = true }.start()
        }
    }

    @Volatile
    private var writer: Writer? = null

    /**
     * Writes a crash's trace to this run's file, as the log would have it: the process is going
     * down, and the runtime's own lines may not reach the thread above in time. Called by
     * [CrashLog] on the crashing thread; never throws.
     */
    fun crashed(thread: Thread, trace: String) {
        runCatching {
            val w = writer ?: return
            crashLines(Date(), android.os.Process.myPid(), thread.name, trace).forEach(w::line)
        }
    }

    /** A crash as the runtime logs it: "FATAL EXCEPTION" and the thread, then the trace, a line each. */
    internal fun crashLines(at: Date, pid: Int, threadName: String, trace: String): List<String> {
        val head = "${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(at)} E/AndroidRuntime($pid): "
        return listOf("${head}FATAL EXCEPTION: $threadName") + trace.lineSequence().filter { it.isNotBlank() }.map { head + it }
    }

    /** A run's file, a line at a time and straight to the disk; at [ROTATE_KB] the first half is set aside as ".1". */
    internal class Writer(private val file: File) {
        private var out = java.io.FileOutputStream(file, true)
        private var written = file.length()

        @Synchronized
        fun line(text: String) {
            val bytes = (text + "\n").toByteArray()
            out.write(bytes)
            written += bytes.size
            if (written > ROTATE_KB * 1024L) {
                out.close()
                rotated(file).delete()
                file.renameTo(rotated(file))
                out = java.io.FileOutputStream(file, true)
                written = 0
            }
        }
    }

    /** The lines of [run], oldest first: the half set aside at [ROTATE_KB], then the rest. */
    fun read(run: Run): List<String> = runCatching {
        listOf(rotated(run.file), run.file).filter { it.exists() }.flatMap { it.readLines() }
    }.getOrDefault(emptyList())

    private fun rotated(file: File) = File(file.path + ".1")

    /**
     * Ends any writer an earlier process left behind. Android ends a process's children with it, so
     * there should be none; one that outlived its process would wait forever on lines that never
     * come. Only this app's processes are visible here, so only its own logcat can match.
     */
    private fun stopStrayWriters() {
        val me = android.os.Process.myPid()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull()?.takeIf { it != me } ?: return@forEach
            val args = runCatching { File(dir, "cmdline").readText().split('\u0000') }.getOrNull() ?: return@forEach
            // A writer's filters name the app's own tags; Report a problem's own logcat ("-d") has none.
            if (args.firstOrNull()?.substringAfterLast('/') == "logcat" && filters().first() in args) {
                android.os.Process.killProcess(pid)
            }
        }
    }
}

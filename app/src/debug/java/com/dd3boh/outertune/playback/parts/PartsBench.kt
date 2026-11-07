/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.parts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSessionService
import com.dd3boh.outertune.R
import com.dd3boh.outertune.parts.BenchArgs
import com.dd3boh.outertune.parts.BenchSummary
import com.dd3boh.outertune.parts.OrtSpecModel
import com.dd3boh.outertune.parts.Scnet
import com.dd3boh.outertune.parts.ScnetPlan
import com.dd3boh.outertune.parts.ScnetSegment
import com.dd3boh.outertune.parts.SegmentTime
import com.dd3boh.outertune.parts.Wav
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Parts on this phone, timed (Unreleased.AUTOMIX_PARTS, debug app only): SCNet over the first N
 * 11 s segments of a WAV, as stemlib cuts them at the chosen overlap, by one session or several
 * side by side, the STFT, the model and the inverse timed apart. Started from MusicService's debug
 * hook (`parts`, then BenchArgs' words); the model is put in the app's files/parts with run-as and
 * the WAV in its external files/parts. The PC's side and the commands: experiments/features/
 * automix/parts/PARTS-NOW.md.
 *
 * Every line goes to the log under PartsBench and to external files/parts/bench.txt; each run gets
 * a folder in external files/parts/out with its report and the kept segment as float WAVs: the
 * input as the model got it and its four parts, comparable sample for sample with the PC's.
 *
 * A debuggable build runs our Kotlin slower than a release would; the model is native code and runs
 * the same. So each segment's two transforms are timed apart from the model.
 */
object PartsBench {
    private const val TAG = "PartsBench"
    private const val CHANNEL = "parts_bench"
    private const val NOTIFICATION = 0x9a27
    private val running = AtomicBoolean(false)

    /** Called on the main thread by MusicService's debug hook. Returns at once; the run has a thread of its own. */
    @JvmStatic
    fun start(service: Service, words: List<String>) {
        val args = try {
            BenchArgs.parse(words)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Parts bench: ${e.message}. Words: model in segments overlap threads sessions lowmem spin nice keep, each key=value.")
            return
        }
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Parts bench: a run is going on, this one is dropped")
            return
        }
        // Started from the shell as a foreground service with nothing playing, the service has ten
        // seconds to show a notification or Android ends the app. With music playing it already has one.
        val ownForeground = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            service.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE &&
            runCatching { showForeground(service) }.onFailure { Log.w(TAG, "Parts bench: no notification", it) }.isSuccess
        val lock = service.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intertune:parts-bench")
            .apply { acquire(2 * 60 * 60 * 1000L) }
        thread(name = "parts-bench") {
            try {
                Run(service.applicationContext, args).go()
            } catch (t: Throwable) {
                Log.e(TAG, "Parts bench failed", t)
                Report(service.applicationContext, null).line("FAILED $t")
            } finally {
                lock.release()
                if (ownForeground) Handler(Looper.getMainLooper()).post { leaveForeground(service) }
                running.set(false)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun showForeground(service: Service) {
        val manager = service.getSystemService(NotificationManager::class.java)
        // Debug app only: a developer's notification, never translated.
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Parts bench", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle("Timing the parts model")
            .setContentText("A few minutes; the phone may get warm")
            .setOngoing(true)
            .build()
        service.startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    /** Takes the bench's notification away, and the foreground with it unless music took the service over meanwhile. */
    private fun leaveForeground(service: Service) {
        runCatching {
            val playing = (service as? MediaSessionService)?.sessions.orEmpty()
                .any { it.player.isPlaying || it.player.playbackState == Player.STATE_BUFFERING }
            if (!playing) ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            service.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        }.onFailure { Log.w(TAG, "Parts bench: could not leave the foreground", it) }
    }

    /** Lines to the log, to bench.txt across runs and to the run's own report. */
    private class Report(context: Context, private val runDir: File?) {
        private val all = File(context.getExternalFilesDir(null), "parts/bench.txt")

        fun line(text: String) = synchronized(Report::class.java) {
            Log.i(TAG, text)
            runCatching {
                all.parentFile?.mkdirs()
                all.appendText(text + "\n")
                runDir?.let { File(it, "report.txt").appendText(text + "\n") }
            }
        }
    }

    private class Run(private val context: Context, private val args: BenchArgs) {
        private val external = File(context.getExternalFilesDir(null), "parts")
        private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        private val runDir = File(external, "out/$stamp").apply { mkdirs() }
        private val report = Report(context, runDir)

        fun go() {
            val modelFile = if ('/' in args.model) File(args.model) else File(context.filesDir, "parts/${args.model}")
            val inputFile = if ('/' in args.input) File(args.input) else File(external, args.input)
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            report.line("== parts bench $stamp")
            report.line(
                "device ${Build.MANUFACTURER} ${Build.MODEL} (${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE}), " +
                    "Android ${Build.VERSION.RELEASE}, ${Runtime.getRuntime().availableProcessors()} cores, ${Build.SUPPORTED_ABIS.firstOrNull()}, " +
                    "debuggable=$debuggable, Java heap limit ${Runtime.getRuntime().maxMemory() shr 20} MB",
            )
            report.line("args $args")
            require(modelFile.isFile) { "no model at $modelFile: put it there with run-as (PARTS-NOW.md)" }
            require(inputFile.isFile) { "no WAV at $inputFile: adb push it there (PARTS-NOW.md)" }
            report.line("model $modelFile, ${modelFile.length()} bytes")

            val wav = Wav.read(inputFile)
            require(wav.rate == Scnet.RATE) { "$inputFile is at ${wav.rate} Hz; the model wants ${Scnet.RATE}" }
            val frames = minOf(wav.frames, ScnetPlan.framesFor(args.segments, args.overlap))
            val plan = ScnetPlan(frames, args.overlap)
            val left = wav.left.copyOf(frames)
            val right = wav.right.copyOf(frames)
            val keep = args.keep.coerceIn(0, plan.count - 1)
            report.line(
                "input $inputFile: ${"%.1f".format(frames / 44100.0)} s of ${"%.1f".format(wav.frames / 44100.0)} s, " +
                    "${plan.count} segments at overlap ${args.overlap} (asked ${args.segments}), segment $keep kept " +
                    "(starts at frame ${plan.startInStretch(keep)} of the WAV)",
            )
            val heapNeed = args.sessions * 50L + (frames * 8L shr 20) + 20
            if (heapNeed > Runtime.getRuntime().maxMemory() shr 20) {
                report.line("warning: about $heapNeed MB of Java heap wanted, ${Runtime.getRuntime().maxMemory() shr 20} MB allowed")
            }
            report.line("start ${conditions()}")

            val memory = MemorySampler().apply { start() }
            val env = OrtSpecModel.environment()
            val loaded = CountDownLatch(args.sessions)
            val go = CountDownLatch(1)
            val next = AtomicInteger(0)
            val times = Collections.synchronizedList(mutableListOf<SegmentTime>())
            val loads = DoubleArray(args.sessions)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            var kept: Array<FloatArray>? = null
            var keptInput: Pair<FloatArray, FloatArray>? = null
            var startedAt = 0L
            val workers = (0 until args.sessions).map { w ->
                thread(name = "parts-bench-$w") {
                    var model: OrtSpecModel? = null
                    try {
                        // Before the session, so its pool of threads is made at this priority.
                        Process.setThreadPriority(args.nice)
                        val t0 = SystemClock.elapsedRealtimeNanos()
                        model = OrtSpecModel(
                            env, modelFile.path,
                            OrtSpecModel.Options(threads = args.threads, lowMemory = args.lowMemory, spinning = args.spinning),
                        )
                        loads[w] = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        loaded.countDown()
                    }
                    go.await()
                    val m = model ?: return@thread
                    try {
                        val segment = ScnetSegment(m)
                        val inLeft = FloatArray(Scnet.SEGMENT)
                        val inRight = FloatArray(Scnet.SEGMENT)
                        val parts = Array(8) { FloatArray(Scnet.SEGMENT) }
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= plan.count) break
                            val begin = SystemClock.elapsedRealtimeNanos() - startedAt
                            plan.input(i, left, right, inLeft, inRight)
                            segment.separate(inLeft, inRight, parts)
                            val time = SegmentTime(i, w, begin, segment.forwardNanos, segment.modelNanos, segment.inverseNanos)
                            times += time
                            report.line(
                                "segment $i on session $w: STFT %.2f s, model %.2f s, inverse %.2f s".format(
                                    time.forwardNanos / 1e9, time.modelNanos / 1e9, time.inverseNanos / 1e9,
                                ),
                            )
                            if (i == keep) {
                                kept = Array(8) { parts[it].copyOf() }
                                keptInput = inLeft.copyOf() to inRight.copyOf()
                            }
                        }
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        m.close()
                    }
                }
            }
            loaded.await()
            report.line("sessions made: ${loads.joinToString { "%.2f s".format(it) }}")
            startedAt = SystemClock.elapsedRealtimeNanos()
            go.countDown()
            workers.forEach { it.join() }
            val wall = SystemClock.elapsedRealtimeNanos() - startedAt
            memory.stop()
            failures.firstOrNull()?.let { throw it }

            val summary = BenchSummary(times.sortedBy { it.index }, wall, frames, args.sessions)
            report.line(
                "wall %.1f s for %.1f s of audio: %.2f s a second of audio, warm-ups included".format(
                    wall / 1e9, summary.audioSeconds, summary.wallPerAudioSecond,
                ),
            )
            report.line(
                "steady segment (warm-ups left out, %d of %d): STFT %.2f s, model %.2f s, inverse %.2f s; our Kotlin %.0f%% of it".format(
                    summary.steady.size, times.size, summary.meanSeconds { it.forwardNanos }, summary.meanSeconds { it.modelNanos },
                    summary.meanSeconds { it.inverseNanos }, summary.kotlinShare * 100,
                ),
            )
            report.line(
                "steady, %d session(s) side by side: %.2f s a second of audio at overlap %s (model alone %.2f); at 1.33 %.2f, at 4 %.2f".format(
                    args.sessions, summary.steadyPerAudioSecond(args.overlap), args.overlap,
                    summary.steadyPerAudioSecond(args.overlap, withoutKotlin = true),
                    summary.steadyPerAudioSecond(1.33), summary.steadyPerAudioSecond(4.0),
                ),
            )
            report.line("memory ${memory.describe()}")
            report.line("end ${conditions()}")

            val parts = kept
            val input = keptInput
            if (parts != null && input != null) {
                Wav.writeFloat(File(runDir, "seg$keep-mix.wav"), Scnet.RATE, input.first, input.second)
                for ((s, name) in Scnet.STEMS.withIndex()) {
                    Wav.writeFloat(File(runDir, "seg$keep-$name.wav"), Scnet.RATE, parts[2 * s], parts[2 * s + 1])
                }
                report.line("parts of segment $keep written to $runDir (float WAVs; it starts at frame ${plan.startInStretch(keep)} of the input)")
            }
            report.line("DONE")
        }

        private fun conditions(): String {
            val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10) / 10f
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val power = context.getSystemService(PowerManager::class.java)
            val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
            val headroom = if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(10) else -1f
            return "battery %.1f C %d%% plugged=%d, thermal status %d, headroom %.2f, screen %s".format(
                temperature, level, plugged, thermal, headroom, if (power.isInteractive) "on" else "off",
            )
        }
    }

    /**
     * The process's resident memory every 100 ms, its peak kept, beside the kernel's own peak
     * (VmHWM), which is reset at the start where the kernel lets the app (clear_refs 5).
     */
    private class MemorySampler {
        @Volatile
        private var on = true
        private var peakRss = 0L
        private var peakHeap = 0L
        private val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
        private val baseRss = rss()
        private val reset = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess
        private lateinit var sampler: Thread

        fun start() {
            sampler = thread(name = "parts-bench-memory") {
                while (on) {
                    peakRss = maxOf(peakRss, rss())
                    val runtime = Runtime.getRuntime()
                    peakHeap = maxOf(peakHeap, runtime.totalMemory() - runtime.freeMemory())
                    Thread.sleep(100)
                }
            }
        }

        fun stop() {
            on = false
            sampler.join()
        }

        fun describe(): String {
            val hwm = status("VmHWM")
            return "peak resident ${peakRss shr 20} MB (sampled; ${baseRss shr 20} MB before the run), kernel peak ${hwm shr 10} MB " +
                (if (reset) "(reset at the start)" else "(since the app started: no reset)") + ", Java heap peak ${peakHeap shr 20} MB"
        }

        /** Resident bytes, from /proc/self/statm's second field, in pages. */
        private fun rss(): Long = runCatching {
            File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * pageSize
        }.getOrDefault(0L)

        /** A /proc/self/status field in kB. */
        private fun status(field: String): Long = runCatching {
            File("/proc/self/status").readLines().first { it.startsWith("$field:") }.filter(Char::isDigit).toLong()
        }.getOrDefault(0L)
    }
}

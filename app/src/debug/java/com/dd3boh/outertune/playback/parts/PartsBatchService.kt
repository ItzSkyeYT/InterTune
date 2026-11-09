/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.parts

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.MixCode
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.ui.screens.mix.EditorCache
import com.dd3boh.outertune.ui.screens.mix.PairLoader
import com.dd3boh.outertune.ui.screens.mix.PartsBatch
import com.dd3boh.outertune.ui.screens.mix.PartsMaker
import com.dd3boh.outertune.ui.screens.mix.PartsStore
import com.dd3boh.outertune.ui.screens.mix.WindowLocks
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A playlist's parts made in one sitting ([PartsBatch], his ask of 9 Nov 2026): the debug app's
 * service that reads each window as the editor reads it ([PairLoader.window]) and splits it as the
 * editor does, [PartsBatch.jobs] windows at once, each on a thread below the music's priority (as a
 * download's conversion is, SongConverter) whose runtime makes its pool of threads at that
 * priority. Its notification counts the songs and can stop it. Bound to MusicService for the
 * player's way to the songs. The songs wait in a file ([PartsBatch.Queue]): killed, the service is
 * started again by Android and passes over every window split before; a split cut short leaves
 * only .part files, which the editor's cache clears (PartsStore.deleteLeftovers).
 */
@OptIn(UnstableApi::class)
class PartsBatchService : Service() {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private var connection: ServiceConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var shown: Notification? = null

    /** The player's way to the songs, once bound; and whether Android's time for the foreground ran out. */
    @Volatile private var source: DataSource.Factory? = null
    @Volatile private var timedOut = false

    /** From the debug hook, to time the batch: N splits at once in place of the phone's own count. */
    @Volatile private var askedJobs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Log.i(TAG, "Stopped from its notification")
            cancelled.set(true)
            PartsBatch.Queue.of(this).clear()
            if (worker == null) finish(null)
            return START_NOT_STICKY
        }
        intent?.getIntExtra(PartsBatch.EXTRA_JOBS, 0)?.takeIf { it > 0 }?.let { askedJobs = it }
        val inForeground = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION, shown ?: progress(0, 0, 0, 0),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { Log.w(TAG, "Could not go to the foreground: the songs wait for the next start", it) }.isSuccess
        if (!inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null && connection == null) bindMusic()
        return START_STICKY
    }

    /** The player's way to the songs, from MusicService, which the binding keeps while the batch runs. */
    private fun bindMusic() {
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val music = (binder as? MusicService.MusicBinder)?.service
                if (music == null) {
                    finish(null)
                    return
                }
                if (worker != null) return
                val way = music.songSource().also { source = it }
                worker = thread(name = "parts-batch") { work(way) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connection = c
        if (!bindService(Intent(this, MusicService::class.java), c, BIND_AUTO_CREATE)) {
            Log.w(TAG, "The player's service could not be bound")
            finish(null)
        }
    }

    private fun work(source: DataSource.Factory) {
        val started = System.nanoTime()
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intertune:parts-batch")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        var result: Result? = null
        try {
            result = runQueue(source)
        } catch (t: Throwable) {
            // Not tried again by itself: the songs are let go, and Split parts asks again.
            Log.e(TAG, "The batch failed", t)
            runCatching { PartsBatch.Queue.of(this).clear() }
        } finally {
            Log.i(TAG, String.format(Locale.ROOT, "Done in %.0f s: %s, the app's peak resident %d MB", (System.nanoTime() - started) / 1e9, result, peakResidentMb()))
            finish(result.takeUnless { cancelled.get() })
        }
    }

    /** What a run came to: songs whose two windows have parts, of those asked; windows split now and failed; no model; room short. */
    data class Result(val songsDone: Int, val songs: Int, val split: Int, val failed: Int, val noModel: Boolean, val roomShort: Boolean)

    private fun runQueue(source: DataSource.Factory): Result {
        val queue = PartsBatch.Queue.of(this)
        val cache = EditorCache(cacheDir, MixCode.ANALYSIS, MixCode.AUTO)
        val store = PartsStore(File(cacheDir, "mix"))
        val loader = PairLoader(source, cache)
        val split = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val noModel = AtomicBoolean(false)
        var roomShort = false
        var asked = emptyList<PartsBatch.Song>()
        while (!cancelled.get() && !noModel.get()) {
            val songs = queue.read()
            if (songs.isEmpty()) break
            asked = (asked + songs).distinctBy { it.id }
            val all = PartsBatch.windows(songs)
            val done: MutableSet<PartsBatch.Window> = ConcurrentHashMap.newKeySet()
            done += all.filter { PartsBatch.done(cache, store, it) }
            val left = all.filterNot { it in done }
            val todo = PartsBatch.fitting(left, cache.partsRoomLeft())
            if (todo.size < left.size) {
                roomShort = true
                Log.w(TAG, "Room for ${todo.size} of ${left.size} windows: more would push out the first")
            }
            val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val cores = Runtime.getRuntime().availableProcessors()
            val planned = PartsBatch.jobs(cores, memory.availMem, todo.size)
            val count = if (askedJobs > 0) askedJobs.coerceIn(1, todo.size.coerceAtLeast(1)) else planned.count
            val jobs = PartsBatch.Jobs(count, (cores / count).coerceIn(1, PartsBatch.MAX_THREADS))
            Log.i(TAG, "${songs.size} songs: ${done.size} of ${all.size} windows split before, ${todo.size} to split, ${jobs.count} at once of ${jobs.threads} threads ($cores cores, ${memory.availMem shr 20} MB free)")
            fun show() = notify(progress(PartsBatch.songsDone(songs, done), songs.size, done.size, all.size).also { shown = it })
            show()
            val next = AtomicInteger(0)
            val workers = (0 until jobs.count).map { k ->
                thread(name = "parts-batch-$k") {
                    // Below the music, as a download's conversion: set before the runtime's session,
                    // whose pool of threads is made at the priority of the thread that makes it.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    var maker: PartsMaker? = null
                    try {
                        while (!cancelled.get() && !noModel.get()) {
                            val i = next.getAndIncrement()
                            if (i >= todo.size) break
                            val w = todo[i]
                            val at = System.nanoTime()
                            try {
                                val made = WindowLocks.hold(w.song.id, w.end, cancelled::get) {
                                    val (window, _) = loader.window(PairLoader.SongInput(w.song.id, w.song.title, w.song.durationMs, null, BeatFix.NONE), w.end, cancelled::get)
                                    if (store.has(w.song.id, w.end, window.frames.toLong() * window.info.channels)) return@hold true
                                    val m = maker ?: PartsOnPhone.openForBatch(this, jobs.threads).also { maker = it }
                                    if (m == null) {
                                        noModel.set(true)
                                        return@hold false
                                    }
                                    m.split(window.samples(), window.info.sampleRate, window.info.channels, store.files(w.song.id, w.end), {}, cancelled::get).also {
                                        if (it) {
                                            split.incrementAndGet()
                                            Log.i(TAG, String.format(Locale.ROOT, "%s %s split in %.1f s, job %d", w.song.id, w.end, (System.nanoTime() - at) / 1e9, k))
                                        }
                                    }
                                }
                                if (made) done += w
                            } catch (e: CancellationException) {
                                break
                            } catch (t: Throwable) {
                                failed.incrementAndGet()
                                Log.w(TAG, "${w.song.id} ${w.end} could not be split", t)
                            }
                            show()
                        }
                    } finally {
                        maker?.close()
                    }
                }
            }
            workers.forEach { it.join() }
            // What was asked has been tried; songs added meanwhile are read on the next round.
            if (!cancelled.get()) queue.remove(songs.map { it.id }.toSet())
        }
        if (noModel.get()) queue.clear()
        return Result(
            asked.count { s -> PartsBatch.windows(listOf(s)).all { PartsBatch.done(cache, store, it) } }, asked.size,
            split.get(), failed.get(), noModel.get(), roomShort,
        )
    }

    private fun finish(result: Result?) {
        Handler(mainLooper).post {
            // Songs asked for as it ended, or after a Cancel while it was stopping: a run of their own.
            val way = source
            if (way != null && !timedOut && PartsBatch.Queue.of(this).read().isNotEmpty()) {
                cancelled.set(false)
                worker = thread(name = "parts-batch") { work(way) }
                return@post
            }
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            if (result != null && result.songs > 0) notify(DONE_NOTIFICATION, ended(result))
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15's six hours of data sync a day: the splits stop between segments, the songs left wait.
        Log.w(TAG, "Out of foreground time: stopping, the rest waits for the next start")
        timedOut = true
        cancelled.set(true)
    }

    override fun onDestroy() {
        cancelled.set(true)
        connection?.let { runCatching { unbindService(it) } }
        connection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mix_parts_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun progress(songsDone: Int, songs: Int, windowsDone: Int, windows: Int): Notification {
        channel()
        val stop = PendingIntent.getService(
            this, 0, Intent(this, PartsBatchService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.mix_parts_batch_title))
            .apply { if (songs > 0) setContentText(getString(R.string.mix_parts_batch_progress, songsDone, songs)) }
            .setProgress(windows, windowsDone, windows == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(android.R.string.cancel), stop)
            .build()
    }

    private fun ended(result: Result): Notification {
        channel()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(if (result.noModel) R.string.mix_parts_batch_no_model else R.string.mix_parts_batch_done))
            .apply {
                if (!result.noModel) setContentText(
                    getString(if (result.roomShort) R.string.mix_parts_batch_room else R.string.mix_parts_batch_progress, result.songsDone, result.songs),
                )
            }
            .setAutoCancel(true)
            .build()
    }

    private fun notify(notification: Notification) = notify(NOTIFICATION, notification)

    private fun notify(id: Int, notification: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, notification) }
    }

    /** The app's peak resident memory since it started, for the log. */
    private fun peakResidentMb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong() shr 10
    }.getOrDefault(-1)

    companion object {
        private const val TAG = "PartsBatch"
        private const val CHANNEL = "mix_parts"
        private const val NOTIFICATION = 0x9a28
        private const val DONE_NOTIFICATION = 0x9a29
        const val ACTION_CANCEL = "com.dd3boh.outertune.parts.CANCEL"
    }
}

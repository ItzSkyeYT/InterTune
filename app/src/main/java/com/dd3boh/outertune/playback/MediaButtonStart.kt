/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import com.dd3boh.outertune.R

/**
 * A service that a media button starts with nothing in the player meets Android's demand for the
 * foreground even when nothing comes of it (issue #98).
 *
 * A play press from a headset, a car or the widget with the app closed starts the service with
 * startForegroundService, and Android then waits for startForeground ([limitMs]). media3 calls it
 * once its notification is ready, after the saved queue is read and the player holds a song, and
 * never when there is no song: no saved queue after all, a read that failed, or one still running
 * when the time is up. Android then closed the app (a Galaxy A05s on Android 15). The service now
 * shows a plain notification under the id media3's notification takes over: at once before
 * Android 12, and from it only when the time is nearly up or there is nothing to play, when it is
 * shown and taken away at once and the service stops.
 *
 * A play press also reaches a service that is running, paused and out of the foreground, the same
 * way: from the widget and from the receiver. media3 then plays and shows at once, unless the song
 * does not start (Android refuses the audio focus during a call, say), so the guard looks at that
 * start too. The play button of media3's notification came this way as well until it became a
 * plain start, which owes Android nothing (PlainPlayNotifications) and is left alone ([EXTRA_PLAIN]).
 *
 * And when the app's process dies while Android still waits, Android brings the service back in
 * a new process, with no intent, and waits again there: issue #137, a trace with no caller in the
 * app, since the call was made by the process that died (MusicService.settleAfterRestart).
 */
internal object MediaButtonStart {

    /**
     * How long Android waits for startForeground after startForegroundService before it closes the
     * app: 5 s on Android 8, 10 s from Android 9 to 12L, 30 s from Android 13 (AOSP:
     * ActiveServices.SERVICE_START_FOREGROUND_TIMEOUT, then ActivityManagerConstants'
     * DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS). Issues #116 and #131 were Android 12.
     */
    fun limitMs(sdk: Int): Long = when {
        sdk >= Build.VERSION_CODES.TIRAMISU -> 30_000L
        sdk >= Build.VERSION_CODES.P -> 10_000L
        else -> 5_000L
    }

    /**
     * When the service looks again after a media button's start. [held], the plain notification
     * up since the press, it is 30 s, to give up on a start that came to nothing. Otherwise it is
     * well before Android's limit, 20 s of the 30 and half of the shorter ones: Android counts
     * from when it hands the start to the app, the service from when it sees the press, and the
     * service's own start lies between the two (2 s on a Pixel 5, debug build).
     */
    fun guardMs(sdk: Int, held: Boolean): Long = when {
        held -> 30_000L
        sdk >= Build.VERSION_CODES.TIRAMISU -> 20_000L
        sdk >= Build.VERSION_CODES.P -> 5_000L
        else -> 2_500L
    }

    /**
     * How long from now until the guard looks: [guardMs], as it always was, unless the part of the
     * start that Android counts took so long that this look would come less than
     * [LOOK_BEFORE_LIMIT_MS] before Android's limit. Then it comes that long before the limit, and
     * never sooner than [MIN_LOOK_MS] from now. [sinceStartMs] is how much of Android's time had
     * gone when the service saw the press ([sinceStart]), null when that cannot be told.
     *
     * On a slow phone the service's own start can take seconds of Android's time, and counted from
     * onStartCommand alone the look could come after the limit (#184, #195, #204, #208, #216 had no
     * caller in their trace). A start of up to 2 s (7 s from Android 13) is looked at exactly as
     * before: what the app does before it hands Android the start (Application.onCreate, the media
     * button receiver reading the saved queue) is not counted, since Android does not count it.
     * Counted from the process's start, that time pulled the look in, on Android 12 and 12L to as
     * little as 1 s after the press, and a saved queue not read by then was given up. Android 8,
     * with 5 s in all, holds the foreground at the press; its guard looks unheld only when that failed.
     */
    fun guardDelayMs(sdk: Int, held: Boolean, sinceStartMs: Long?): Long {
        val full = guardMs(sdk, held)
        if (held || sinceStartMs == null) return full
        val beforeLimit = limitMs(sdk) - LOOK_BEFORE_LIMIT_MS - sinceStartMs.coerceAtLeast(0L)
        return minOf(full, beforeLimit.coerceAtLeast(MIN_LOOK_MS))
    }

    /** The latest the guard looks after a slow start, before Android's limit ([guardDelayMs]). */
    const val LOOK_BEFORE_LIMIT_MS = 3_000L

    /** The guard's earliest look after a slow start, so media3 has a moment for the press. */
    const val MIN_LOOK_MS = 1_000L

    /**
     * Whether the foreground is held at once, at the press. Before Android 12 with nothing in the
     * player, as always: media3 shows nothing until the song is in. And on every Android when so
     * little of Android's time is left that the guard's look could come too late ([tooLateToWait]).
     */
    fun holdsAtOnce(sdk: Int, hasSong: Boolean, sinceStartMs: Long?): Boolean =
        (!hasSong && sdk < Build.VERSION_CODES.S) || tooLateToWait(sdk, sinceStartMs)

    /**
     * Whether less of Android's time is left than the guard's earliest look ([MIN_LOOK_MS]) and a
     * second for a busy main thread ([HOLD_AT_ONCE_UNDER_MS]): a start whose onCreate and earlier
     * work took that long was not met unless media3 was (C2 of experiments/triage-1121/FGS-AUDIT.md).
     * Held at once then, from Android 12 too, which goes against the rule of 7 Nov 2026 (nothing
     * held early there: once media3 lets the plain notification go, it may not come back into the
     * foreground from the background, and Android may stop the music later). His call of 21 Nov
     * 2026: a stop beats a crash. Null [sinceStartMs], a start that cannot be timed, is never late.
     */
    fun tooLateToWait(sdk: Int, sinceStartMs: Long?): Boolean =
        sinceStartMs != null && limitMs(sdk) - sinceStartMs.coerceAtLeast(0L) < HOLD_AT_ONCE_UNDER_MS

    /** Under this much of Android's time left at the press, the foreground is held at once ([tooLateToWait]). */
    const val HOLD_AT_ONCE_UNDER_MS = MIN_LOOK_MS + 1_000L

    /**
     * How much of Android's time for this start had gone by [now], when it can be told: from
     * [handedAt], when the app handed Android the start itself ([EXTRA_HANDED_AT]: the media button
     * receiver, and the widget with the player running), or else, for the first start of a
     * service created with its process, from the process's start ([sinceColdStart]): a start from
     * outside the app that brought the process up with it, which Android counts from when the new
     * process reports in. Null for any other start, which is counted from now. Times in uptime.
     */
    fun sinceStart(handedAt: Long?, firstStart: Boolean, processStartedAt: Long, serviceCreatedAt: Long, now: Long): Long? = when {
        handedAt != null -> (now - handedAt).coerceAtLeast(0L)
        firstStart -> sinceColdStart(processStartedAt, serviceCreatedAt, now)
        else -> null
    }

    /**
     * Put on a media button's start by the app as it hands the start to Android ([markHanded]):
     * the uptime of that moment, which is when Android's time for the start begins.
     */
    const val EXTRA_HANDED_AT = "media_button_handed_at"

    /** [intent], marked with now as the moment the app hands it to Android ([EXTRA_HANDED_AT]). */
    fun markHanded(intent: Intent): Intent = intent.putExtra(EXTRA_HANDED_AT, SystemClock.uptimeMillis())

    /**
     * When the app handed Android this start ([markHanded]); null for a start from outside the app.
     * Null as well for a start Android delivers again ([flags] START_FLAG_RETRY or
     * START_FLAG_REDELIVERY): the app's process died before the service had taken it, and Android
     * starts its time again when it hands the start to the new process, so the service counts from
     * that process's start instead ([sinceStart]).
     */
    fun handedAtOf(intent: Intent?, flags: Int): Long? {
        if (flags and (Service.START_FLAG_RETRY or Service.START_FLAG_REDELIVERY) != 0) return null
        return runCatching { intent?.takeIf { it.hasExtra(EXTRA_HANDED_AT) }?.getLongExtra(EXTRA_HANDED_AT, 0L) }.getOrNull()
    }

    /**
     * Put on the play button of the player's notification (PlainPlayNotifications), a plain start
     * that owes Android nothing: the guard leaves it alone. Looked at as a press Android waits on,
     * it held the foreground with the plain notification over a paused player whose song did not
     * start, and after a cold start with nothing loaded it could give up the resumption.
     */
    const val EXTRA_PLAIN = "media_button_plain"

    /** Whether [intent] is the notification's plain play press ([EXTRA_PLAIN]). */
    fun isPlain(intent: Intent?): Boolean = runCatching { intent?.getBooleanExtra(EXTRA_PLAIN, false) == true }.getOrDefault(false)

    /**
     * How long since the app's process started, for the guard of the first start of a service
     * created with its process ([sinceStart]); null when the service was not, or when this start
     * came long after its creation (a service a screen had bound and a press that came later).
     * All three times in uptime.
     */
    fun sinceColdStart(processStartedAt: Long, serviceCreatedAt: Long, now: Long): Long? =
        if (serviceCreatedAt - processStartedAt < COLD_START_MS && now - serviceCreatedAt < COLD_START_MS) now - processStartedAt
        else null

    /**
     * A service created this soon after its process started is taken to have started with it, and
     * a start this soon after the service's creation to be the one it was created for.
     */
    const val COLD_START_MS = 15_000L

    /** How long the plain notification stays over a player whose song never started, before it goes. */
    const val LET_GO_MS = 60_000L

    /**
     * Whether a media key asks to play: the keys that media3's receiver and the widget send with
     * startForegroundService (and that media3's notification sent so, before PlainPlayNotifications).
     */
    fun startsPlayback(keyCode: Int?): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK

    /**
     * Whether the service looks again after a media button's start that finds it out of the
     * foreground. With nothing in the player, always: nothing may come of it. With a song, media3
     * plays and shows at once, so only for a key that asks to play, the one that comes with
     * Android's demand, and from Android 8, where Android makes that demand and the service can
     * tell from Android's list whether media3 has met it ([listedInForeground]). Until 21 Nov 2026
     * only from Android 10: on Android 8 and 9 a play press whose song did not start was not looked
     * at, and Android closed the app 5 or 10 s later (C4 of experiments/triage-1121/FGS-AUDIT.md).
     */
    fun looksAgain(hasSong: Boolean, keyCode: Int?, sdk: Int): Boolean =
        !hasSong || (startsPlayback(keyCode) && sdk >= Build.VERSION_CODES.O)

    /**
     * Whether Android has [component], a service of this app, in the foreground now, from its list
     * of the app's own services (ActivityManager.getRunningServices, which from Android 8 lists the
     * caller's own and no other app's): the service record's isForeground, the flag Android decides
     * its demand by (ActiveServices.sendServiceArgsLocked). Null when the list cannot be read or does
     * not have it.
     *
     * For Android 8 to 13, where Service.foregroundServiceType cannot say: it came with Android 10,
     * and on 10 to 13 it keeps the type of the service's last startForeground for as long as Android keeps the service's record: after
     * stopForeground too, and in the new process after Android brought the service back. AOSP sets
     * ServiceRecord.foregroundServiceType in startForeground only up to android-13.0.0_r83, and puts
     * it back to 0 on leaving the foreground from android-14.0.0_r1. Read there, a player paused long
     * enough for media3 to leave the foreground was taken to be in it: a play press on it had no look,
     * and a service brought back after its process died did not meet what the dead process owed.
     */
    @Suppress("DEPRECATION")
    fun listedInForeground(context: Context, component: ComponentName): Boolean? = runCatching {
        context.getSystemService<ActivityManager>()?.getRunningServices(Int.MAX_VALUE)
            ?.firstOrNull { it.service == component }?.foreground
    }.getOrNull()

    /** The key of a media button's start, null when it carries none. */
    fun keyOf(intent: Intent?): Int? = runCatching {
        intent?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }?.keyCode
    }.getOrNull()

    /**
     * The plain notification, on media3's channel, made here as media3 makes it when it is not there
     * yet (DefaultMediaNotificationProvider: low importance, no badge before Android 9), so media3
     * finds it and keeps it.
     */
    fun placeholder(context: Context, channelId: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService<NotificationManager>()?.let { manager ->
                // Made each time: a channel that is there keeps what the listener set and takes the
                // name in the app's language now (ChannelNames).
                manager.createNotificationChannel(
                    NotificationChannel(channelId, context.getString(R.string.music_player), NotificationManager.IMPORTANCE_LOW).apply {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) setShowBadge(false)
                    }
                )
            }
        }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.media_button_resuming))
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }
}

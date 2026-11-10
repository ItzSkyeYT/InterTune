package com.dd3boh.outertune.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationManagerCompat
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SleepTimerDefaults
import java.util.Date

/**
 * The countdown for a running sleep timer, as its own notification.
 *
 * Separate from the media notification on purpose, and not by choice: a media notification draws
 * its own content view, and the platform refuses to promote anything that does. So the now playing
 * card can never be a Live Update, and a countdown, which is the one thing here that genuinely
 * changes minute by minute, can.
 *
 * A timer with a time counts down by itself: the notification carries the end time and asks the
 * system for a chronometer that counts down to it (and, as a Live Update on Android 16, the same in
 * the status bar chip), so nothing here redraws it as the minutes pass. It is posted again only
 * when something it says changes: the time, a setting, the song it names.
 *
 * Three buttons: +5 min, +15 min and Stop. They send a broadcast to the playback service, which
 * lives exactly as long as the timer does (see [broadcastAction]).
 *
 * Only posted while a timer is actually running, so it is not a second permanent notification.
 */
class SleepTimerNotification(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** Whether the last post asked the system to take it down at the time. See [show]. */
    private var timeoutArmed = false

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // IMPORTANCE_LOW: a countdown must never make a sound, least of all one intended to run
        // while somebody falls asleep.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sleep_timer),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /**
     * What the notification says.
     *
     * @param end what a timer without a time waits for, or null for one with a time
     * @param stopAtMs the time of a timer with a time, in epoch milliseconds
     * @param fadeSeconds the length of the fade, or null when the music is not faded
     * @param afterSong the music plays on to the end of the song after the time (Let the song finish)
     * @param songTitle that song, when it is the one playing now; null when it is a later one
     */
    data class Content(
        val end: SleepEnd?,
        val stopAtMs: Long?,
        val fadeSeconds: Int?,
        val afterSong: Boolean,
        val songTitle: String?,
    )

    fun show(content: Content, now: Long = System.currentTimeMillis()) {
        ensureChannel()
        val stopAt = content.stopAtMs.takeIf { content.end == null }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        // The channel-taking constructor is API 26. Channels do not exist below it, and the
        // deprecated one is what a pre-26 device wants anyway, so pick by version rather than
        // raising minSdk for a countdown.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.bedtime)
            .setContentTitle(titleOf(content, stopAt))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        lineOf(content)?.let { builder.setContentText(it) }

        if (stopAt != null) {
            // The countdown runs by itself: the system draws the time left to the end time, in the
            // notification and, where it makes a chip of it, in the status bar.
            builder
                .setWhen(stopAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }

        // The system takes it down at the time even when the phone slept through the time: the job
        // that hides it counts the time the phone is awake. Not with Let the song finish, where the
        // timer goes on after the time and posts again then.
        var timeout = false
        if (stopAt != null && !content.afterSong && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter((stopAt - now).coerceAtLeast(0L) + TIMEOUT_SLACK_MS)
            timeout = true
        }

        builder
            .addAction(extendAction(SleepTimerDefaults.EXTEND_MINUTES[0]))
            .addAction(extendAction(SleepTimerDefaults.EXTEND_MINUTES[1]))
            .addAction(
                action(
                    code = 0,
                    label = context.getString(R.string.sleep_notif_stop),
                    minutes = STOP,
                )
            )

        // Android 16 shows a Live Update in the status bar as a chip. With a time to count down, the
        // chip is that countdown, which is why no short text is set for it: a text is drawn as it
        // is and would have to be posted again to move. Without a time it is the icon.
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)

        if (manager.areNotificationsEnabled()) {
            // A timeout left over from an earlier post would take down the one that replaces it.
            if (timeoutArmed && !timeout) manager.cancel(NOTIFICATION_ID)
            timeoutArmed = timeout
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun hide() {
        timeoutArmed = false
        manager.cancel(NOTIFICATION_ID)
    }

    private fun titleOf(content: Content, stopAt: Long?): String = when {
        stopAt != null -> context.getString(R.string.sleep_notif_title_at, DateFormat.getTimeFormat(context).format(Date(stopAt)))
        content.end == SleepEnd.ALBUM -> context.getString(R.string.sleep_notif_title_album)
        content.end == SleepEnd.QUEUE -> context.getString(R.string.sleep_notif_title_queue)
        else -> context.getString(R.string.sleep_notif_title_song)
    }

    /** What applies beside the time: the fade, and the song the music plays on to. Null when nothing does. */
    private fun lineOf(content: Content): String? {
        val after = if (content.afterSong) content.songTitle ?: context.getString(R.string.sleep_the_song) else null
        return when {
            content.fadeSeconds != null && after != null ->
                context.getString(R.string.sleep_notif_line_fade_after, content.fadeSeconds, after)
            content.fadeSeconds != null -> context.getString(R.string.sleep_notif_line_fade, content.fadeSeconds)
            after != null -> context.getString(R.string.sleep_notif_line_after, after)
            else -> null
        }
    }

    private fun extendAction(minutes: Int): Notification.Action =
        action(minutes, context.getString(R.string.sleep_plus_minutes, minutes), minutes)

    private fun action(code: Int, label: String, minutes: Int): Notification.Action {
        // An explicit broadcast to ourselves. The receiver is the service's own, registered while
        // it runs, so a tap after the service has gone does nothing, which is right: so has the timer.
        val intent = Intent(broadcastAction(context)).setPackage(context.packageName).putExtra(EXTRA_MINUTES, minutes)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.bedtime), label, pending).build()
    }

    companion object {
        const val CHANNEL_ID = "sleep_timer"

        /** Must not collide with the media notification or the download one, which are 888 and 1. */
        const val NOTIFICATION_ID = 4242

        /** The extra of the broadcast a button sends: minutes to add, or [STOP]. */
        const val EXTRA_MINUTES = "minutes"

        /** The value of [EXTRA_MINUTES] for the Stop button. */
        const val STOP = 0

        private const val REQUEST_BASE = 4240

        /** The system takes the notification down this long after the time, in case nothing else did. */
        private const val TIMEOUT_SLACK_MS = 2_000L

        /** The broadcast action of the buttons, per build: the debug and preview apps have their own package. */
        fun broadcastAction(context: Context): String = context.packageName + ".SLEEP_TIMER"

        /**
         * `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`, spelled out because referencing the
         * constant needs API 36.1 and this compiles against 36.
         */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}

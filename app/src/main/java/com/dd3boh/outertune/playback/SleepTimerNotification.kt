package com.dd3boh.outertune.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import kotlin.math.ceil

/**
 * The countdown for a running sleep timer, as its own notification.
 *
 * Separate from the media notification on purpose, and not by choice: a media notification draws
 * its own content view, and the platform refuses to promote anything that does. So the now playing
 * card can never be a Live Update, and a countdown, which is the one thing here that genuinely
 * changes minute by minute, can.
 *
 * Only posted while a timer is actually running, so it is not a second permanent notification.
 * On Android 16 it takes the status bar chip and, where the manufacturer feeds one, the same
 * surface their own timers use. Below 16 it is an ordinary quiet ongoing notification, which is
 * what the sleep timer had before, which is to say nothing at all.
 */
class SleepTimerNotification(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

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
     * @param msRemaining time left, or null when the timer is set to stop at the end of the song
     *                    and therefore has no countdown to show.
     */
    fun show(msRemaining: Long?) {
        ensureChannel()
        val minutes = msRemaining?.let { ceil(it / 60000.0).toInt().coerceAtLeast(1) }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.bedtime)
            .setContentTitle(context.getString(R.string.sleep_timer))
            .setContentText(
                if (minutes == null) context.getString(R.string.sleep_timer_notif_end_of_song)
                else context.resources.getQuantityString(
                    R.plurals.sleep_timer_notif_minutes, minutes, minutes
                )
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        if (Build.VERSION.SDK_INT >= 36 && minutes != null) {
            // The status bar chip. Seven characters or so, cut without warning beyond that.
            builder.setShortCriticalText(
                context.getString(R.string.sleep_timer_notif_chip, minutes)
            )
        }

        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun hide() = manager.cancel(NOTIFICATION_ID)

    companion object {
        const val CHANNEL_ID = "sleep_timer"

        /** Must not collide with the media notification or the download one, which are 888 and 1. */
        const val NOTIFICATION_ID = 4242

        /**
         * `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`, spelled out because referencing the
         * constant needs API 36.1 and this compiles against 36.
         */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}

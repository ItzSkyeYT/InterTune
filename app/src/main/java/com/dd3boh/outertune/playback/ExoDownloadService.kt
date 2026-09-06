package com.dd3boh.outertune.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.dd3boh.outertune.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class ExoDownloadService : DownloadService(
    NOTIFICATION_ID,
    1000L,
    CHANNEL_ID,
    R.string.action_download,
    0
) {
    @Inject
    lateinit var downloadUtil: DownloadUtil

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == REMOVE_ALL_PENDING_DOWNLOADS) {
            downloadManager.currentDownloads.forEach { download ->
                downloadManager.removeDownload(download.request.id)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getDownloadManager() = downloadUtil.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        if (downloads.isEmpty()) {
            downloadUtil.downloadNotificationHelper.buildDownloadCompletedNotification(this,
                R.drawable.download,
                null,
                null
            )
        } else {
            Notification.Builder.recoverBuilder(
                this, downloadUtil.downloadNotificationHelper.buildProgressNotification(
                    this,
                    R.drawable.download,
                    null,
                    if (downloads.size == 1) Util.fromUtf8Bytes(downloads[0].request.data)
                    else resources.getQuantityString(R.plurals.n_song, downloads.size, downloads.size),
                    downloads,
                    notMetRequirements
                )
            ).addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.close),
                    getString(android.R.string.cancel),
                    PendingIntent.getService(
                        this,
                        0,
                        Intent(this, ExoDownloadService::class.java).setAction(REMOVE_ALL_PENDING_DOWNLOADS),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            ).let(::asLiveUpdate).build()
        }

    /**
     * Opts the download notification into Android 16's Live Updates, which is what Samsung's Now
     * Bar and the status bar chip show.
     *
     * Only this notification qualifies. Playback cannot: a media notification is explicitly
     * excluded from promotion, because it uses its own content view, so "InterTune in the Now Bar
     * while a song plays" is not something an app can ask for. Downloads are the real fit anyway,
     * being the thing with a start, an end and a number in between.
     *
     * The promotion is requested through the extra rather than setRequestPromotedOngoing, which
     * needs API 36.1 to compile against while this builds on 36. Setting an extra the platform does
     * not know is harmless, so no version check is needed for that half.
     */
    private fun asLiveUpdate(builder: Notification.Builder): Notification.Builder {
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        if (Build.VERSION.SDK_INT >= 36) {
            // The status bar chip, roughly seven characters. Anything longer is cut without asking.
            builder.setShortCriticalText(getString(R.string.action_download))
        }
        return builder
    }


    /**
     * `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`, spelled out because the constant needs API
     * 36.1 to reference and this compiles against 36.
     */
    private val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /**
     * This helper will outlive the lifespan of a single instance of [ExoDownloadService]
     */
    class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int,
    ) : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (download.state == Download.STATE_FAILED) {
                val notification = notificationHelper.buildDownloadFailedNotification(
                    context,
                    R.drawable.error,
                    null,
                    Util.fromUtf8Bytes(download.request.data)
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"
    }
}
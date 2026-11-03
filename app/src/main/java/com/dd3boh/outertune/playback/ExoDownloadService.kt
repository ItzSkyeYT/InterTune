package com.dd3boh.outertune.playback

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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


/**
 * The download service.
 *
 * Before Android 12 media3 makes it a foreground service, as it always did. From Android 12 this
 * class does, because Android refuses startForeground to an app in the background and media3
 * calls it where nothing can catch the refusal, which closed the app (issue #71). See
 * [ForegroundPromotion], and the foreground part below.
 */
@AndroidEntryPoint
class ExoDownloadService : DownloadService(
    FOREGROUND_ID,
    UPDATE_INTERVAL_MS,
    CHANNEL_ID,
    R.string.action_download,
    0
) {
    @Inject
    lateinit var downloadUtil: DownloadUtil

    /** From Android 12 this class makes itself a foreground service, media3 does not: [ForegroundPromotion]. */
    private val selfPromoting = ForegroundPromotion.selfPromotes(Build.VERSION.SDK_INT)
    private val promotion = ForegroundPromotion { Log.w(TAG, it) }
    private val handler = Handler(Looper.getMainLooper())

    /** The notification is built again every second, from the first song that downloads until the last is done. */
    private var ticking = false

    override fun onCreate() {
        super.onCreate()
        if (!selfPromoting) return
        // Given no notification id media3 makes no channel, and Android drops a notification posted to a
        // channel that is not there.
        NotificationUtil.createNotificationChannel(this, CHANNEL_ID, R.string.action_download, 0, NotificationUtil.IMPORTANCE_LOW)
        downloadManager.addListener(downloadListener)
        application.registerActivityLifecycleCallbacks(inFront)
        // Songs that are downloading already, when the service starts again after Android ended it: no
        // change is coming for them.
        refresh()
    }

    override fun onDestroy() {
        if (selfPromoting) {
            handler.removeCallbacksAndMessages(null)
            downloadManager.removeListener(downloadListener)
            application.unregisterActivityLifecycleCallbacks(inFront)
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == REMOVE_ALL_PENDING_DOWNLOADS) {
            downloadManager.currentDownloads.forEach { download ->
                downloadManager.removeDownload(download.request.id)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /*
     * The foreground part, from Android 12. This is what media3's own updater did when it made the
     * service a foreground service, done here so that Android's refusal can be caught
     * (ForegroundPromotion): Service.startForeground is final and media3 calls it from callbacks on
     * the main looper, so neither the service nor the app could catch it there.
     *
     * Where the app may not start a foreground service (it is in the background: a queued download
     * that finds Wi-Fi again, downloads resumed by a restart, a like from the media notification),
     * the refusal is one line in the log and the downloads go on as a background service, without a
     * notification. It is asked again at every change of a download and whenever a screen of the app
     * comes to the front, which is when Android allows it.
     */

    /** The downloads, as media3's service listened to them: a change is a reason to look again. */
    private val downloadListener = object : DownloadManager.Listener {
        override fun onInitialized(downloadManager: DownloadManager) = refresh()

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()

        override fun onIdle(downloadManager: DownloadManager) {
            ticking = false
            handler.removeCallbacksAndMessages(null)
        }
    }

    /** The app's screens: one that comes to the front is the moment a refused promotion is allowed. */
    private val inFront = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (promotion.owed) refresh()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /**
     * While a song downloads or is removed the service is a foreground service and its notification
     * is built again every second; at any other change the notification follows, if it is up.
     */
    private fun refresh() {
        val current = downloadManager.currentDownloads
        val running = current.any { it.needsForeground() }
        if (running) ticking = true
        if (running || promotion.promoted) update(current)
    }

    private fun update(current: MutableList<Download>) {
        val notification = getForegroundNotification(current, downloadManager.notMetRequirements)
        if (promotion.promoted) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } else {
            promotion.attempt {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        handler.removeCallbacksAndMessages(null)
        // Only once it is a foreground service. A refusal is asked again at the next change, not every second.
        if (ticking && promotion.promoted) {
            handler.postDelayed({ update(downloadManager.currentDownloads) }, UPDATE_INTERVAL_MS)
        }
    }

    /** media3's needsStartedService: the states in which the service has to stay up in the foreground. */
    private fun Download.needsForeground() =
        state == Download.STATE_DOWNLOADING || state == Download.STATE_REMOVING || state == Download.STATE_RESTARTING

    override fun getDownloadManager() = downloadUtil.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    /** A tap on the notification opens Downloaded songs. Made once for the service. */
    private val openDownloads by lazy { DownloadsShortcut.pendingIntent(this) }

    /**
     * Built again once a second while songs download (UPDATE_INTERVAL_MS) and at each song's change
     * of state, never for every byte that arrives.
     *
     * The bar and the count are the run's (DownloadRun), from its first song to its last. media3's
     * bar was the average of the few songs downloading at that moment and never counted one done,
     * so a long run read near nothing all the way through. media3's notification stays underneath
     * for its titles: waiting for Wi-Fi or a network, with no bar, and removing.
     */
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        if (downloads.isEmpty()) {
            return downloadUtil.downloadNotificationHelper.buildDownloadCompletedNotification(
                this,
                R.drawable.download,
                openDownloads,
                null
            )
        }
        val bar = DownloadRun.bar(
            downloadUtil.downloadRun.endedCount,
            downloads.map { DownloadRun.Item(it.state, it.percentDownloaded) },
        )
        // The run's bar while it runs and nothing holds it back, between two songs as well: none is
        // downloading for a moment then, and media3's notification would have no title.
        val runsNow = bar.running && notMetRequirements == 0
        val message = when {
            bar.total == 1 && downloads.size == 1 -> Util.fromUtf8Bytes(downloads[0].request.data)
            bar.running -> resources.getQuantityString(R.plurals.download_progress_songs, bar.total, bar.done, bar.total)
            else -> resources.getQuantityString(R.plurals.n_song, downloads.size, downloads.size)
        }
        return Notification.Builder.recoverBuilder(
            this, downloadUtil.downloadNotificationHelper.buildProgressNotification(
                this,
                R.drawable.download,
                openDownloads,
                message,
                downloads,
                notMetRequirements
            )
        ).apply {
            if (runsNow) {
                setContentTitle(getString(androidx.media3.exoplayer.R.string.exo_download_downloading))
                setProgress(bar.max, bar.position, bar.indeterminate)
            }
        }.addAction(
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
        ).let(::liveUpdate).build()
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
    /**
     * Opts the download notification into Android 16's Live Updates.
     *
     * This is the AOSP feature only: the platform grants PROMOTED_ONGOING, which is real and shows
     * on stock Android 16. It does NOT put the notification in Samsung's Now Bar. That is a
     * separate, closed thing, gated by an app list inside NotificationManagerService
     * (isOngoingActivityAllowed, loadDefaultOngoingActivitySupportAppList), which InterTune is not
     * on and cannot join: the manifest opt-in Maps and Perplexity declare changes nothing, and the
     * ONGOING_ACTIVITY_PACKAGE_ADD broadcast has no receiver for us. Checked on One UI 8.5.
     *
     * Deliberately does NOT set Notification.ProgressStyle. It qualifies for promotion and reads
     * like the right thing, but One UI 8.5 does not render it, so the download progress bar
     * disappeared entirely where media3's ordinary one had drawn. The ordinary bar stays, with the
     * run's numbers in it (getForegroundNotification).
     *
     * The promotion is requested through the extra rather than setRequestPromotedOngoing, which
     * needs API 36.1 to compile against while this builds on 36.
     */
    private fun liveUpdate(builder: Notification.Builder): Notification.Builder {
        builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        if (Build.VERSION.SDK_INT >= 36) {
            // The status bar chip. About seven characters, cut without asking if longer.
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
                // A tap opens the downloads as the running notification's does, and clears this
                // one: it has been read. Before, it did nothing.
                val notification = Notification.Builder.recoverBuilder(
                    context,
                    notificationHelper.buildDownloadFailedNotification(
                        context,
                        R.drawable.error,
                        DownloadsShortcut.pendingIntent(context),
                        Util.fromUtf8Bytes(download.request.data)
                    )
                ).setAutoCancel(true).build()
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"

        private const val TAG = "ExoDownloadService"

        /** How often the notification is built again while songs download. */
        private const val UPDATE_INTERVAL_MS = 1000L

        /**
         * The notification id media3 is given. None from Android 12, which is media3's documented
         * "run the service only in the background": it then never calls startForeground, and the
         * service does (ForegroundPromotion).
         */
        private val FOREGROUND_ID =
            if (ForegroundPromotion.selfPromotes(Build.VERSION.SDK_INT)) DownloadService.FOREGROUND_NOTIFICATION_ID_NONE else NOTIFICATION_ID
    }
}
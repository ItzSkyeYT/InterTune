package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.LikedAutoDownloadKey
import com.dd3boh.outertune.constants.LikedAutodownloadMode
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_DOWNLOADING
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_INVALID
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.codecsOrEmpty
import com.dd3boh.outertune.utils.contentLengthOrZero
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.InvalidAudioFileException
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.dd3boh.outertune.utils.Throttle
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    val TAG = DownloadUtil::class.simpleName.toString()

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    // Concurrent, because media3 runs each download on its own thread and this is read and written
    // from all of them. An unsynchronised HashMap can corrupt its table under concurrent put.
    private val songUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Serialises the player requests that downloads make. See the gate in the resolver. */
    private val resolveGate = Any()
    private var lastResolveAt = 0L
    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        .build()
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        val length = if (dataSpec.length >= 0) dataSpec.length else 1
        var staleCopy = false
        if (dataSpec.position == 0L) {
            // From the start, the player's copy is read through only when it holds the whole
            // song. A part of one used to be read through as soon as its first byte was there,
            // and the rest came from whatever stream was resolved below, so a song cached on
            // mobile data and downloaded on Wi-Fi joined two formats in one file. The part goes,
            // and the download takes the whole song from the new stream.
            if (playerCache.holdsWhole(mediaId)) {
                // Checked before the shortcut takes it, or a download made after raising the
                // audio quality would copy the old cached bytes straight in and stay at the old
                // bitrate for good. A copy fetched at a lower setting is replaced, but only once
                // the new stream is in hand, as MusicService does for playback: if the fetch fails
                // or the stream is not checked, the download takes the old copy as before rather
                // than losing the one copy that plays offline.
                val storedTier = runCatching {
                    runBlocking(Dispatchers.IO) { database.format(mediaId).first() }
                }.getOrNull()?.qualityTier
                if (!isStaleQualityTier(storedTier, audioQuality)) return@Factory dataSpec
                staleCopy = true
            }
            if (playerCache.holdsPartFromStart(mediaId)) {
                runCatching { playerCache.removeResource(mediaId) }
                    .onFailure { Log.w(TAG, "Could not drop the partial copy of $mediaId", it) }
            }
        } else if (playerCache.isCached(mediaId, dataSpec.position, length)) {
            return@Factory dataSpec
        }

        // Not for a stale copy: the cache serves by the song's id whatever address comes back, so
        // a remembered url would still read the old bytes.
        songUrlCache[mediaId]?.takeIf { !staleCopy && it.second > System.currentTimeMillis() }?.let {
            return@Factory dataSpec.withUri(it.first.toUri())
        }

        // Paced here rather than at the enqueue loop, because this is the only point a download's
        // player request actually passes through. Chunking the enqueue paces the loop; media3 then
        // drains it at whatever rate it likes.
        //
        // 700ms, derived from the loudness scan's 350ms rather than invented. A download resolve
        // costs two player requests on the healthy path, so this lands on the same requests per
        // second as the scan we already decided was polite.
        //
        // The asymmetry is the point. On a healthy network the gap is invisible, because the audio
        // transfer that follows takes seconds. On a refused network nothing transfers and every
        // resolve fails in about a second, which is exactly when the app would otherwise hammer.
        synchronized(resolveGate) {
            val wait = RESOLVE_GAP_MS - (SystemClock.elapsedRealtime() - lastResolveAt)
            if (wait > 0) Thread.sleep(wait)
            lastResolveAt = SystemClock.elapsedRealtime()
        }

        val playbackData = runBlocking(Dispatchers.IO) {
            YTPlayerUtils.playerResponseForPlayback(
                mediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            )
        }.getOrElse {
            if (staleCopy) return@Factory dataSpec
            throw it
        }
        val format = playbackData.format
        // The lower-quality copy goes now that the new stream is in hand, and only for a stream
        // that answered its status check: the last fallback client's is taken unchecked, and one
        // of those failing partway would cost the copy. Either early return leaves the format row
        // at the old tier, so the next download tries again.
        if (staleCopy) {
            if (!playbackData.validated) return@Factory dataSpec
            runCatching { playerCache.removeResource(mediaId) }
                .onFailure { Log.w(TAG, "Could not drop the lower-quality copy of $mediaId", it) }
        }

        database.query {
            upsertFormatKeepingLoudness(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.codecsOrEmpty(),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLengthOrZero(),
                    loudnessDb = playbackData.audioConfig?.effectiveLoudnessDb,
                    qualityTier = audioQuality.name,
                    playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                )
            )
        }

        val streamUrl = playbackData.streamUrl.let {
            // Specify range to avoid YouTube's throttling
            "${it}&range=0-${format.contentLength ?: 10000000}"
        }

        songUrlCache[mediaId] = streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
        dataSpec.withUri(streamUrl.toUri())
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            maxParallelDownloads = 3
            requirements = downloadRequirements(context.dataStore.get(DownloadOnWifiOnlyKey, false))
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
                )
            )
        }
    val downloads = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())

    /**
     * ids whose entry in [downloads] the download listener or deleteSong has changed since a
     * rescan last merged them. A rescan keeps the live value for these instead of its snapshot's.
     * Concurrent, because media3 calls the listener on its own threads and deleteSong runs on
     * dlCoroutine.
     */
    private val scanTouchedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    var localMgr = DownloadDirectoryManagerOt(
        context,
        context.dataStore.get(DownloadPathKey, "").toUri(),
        uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
    )
    val downloadMgr = DownloadManagerOt(localMgr)
    var isProcessingDownloads = MutableStateFlow(false)

    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }

    fun download(songs: List<MediaMetadata>) {
        if (songs.any { downloads.value[it.id] == null }) notifyIfWaitingForWifi()
        songs.forEach { song -> downloadSong(song.id, song.title) }
    }

    fun download(song: MediaMetadata) {
        if (downloads.value[song.id] == null) notifyIfWaitingForWifi()
        downloadSong(song.id, song.title)
    }

    fun download(song: SongEntity) {
        if (downloads.value[song.id] == null) notifyIfWaitingForWifi()
        downloadSong(song.id, song.title)
    }

    /**
     * Network requirement for downloads. Unmetered rather than "wifi" specifically, so an unmetered
     * ethernet or hotspot connection also counts, and a metered wifi network correctly does not.
     */
    private fun downloadRequirements(wifiOnly: Boolean) =
        if (wifiOnly) Requirements(Requirements.NETWORK_UNMETERED) else Requirements(Requirements.NETWORK)

    /**
     * Applies the requirement immediately, including to downloads already queued or running. media3
     * pauses anything that no longer meets it and resumes automatically once it does, so nothing is
     * lost by toggling this mid-download.
     */
    fun setDownloadRequirements(wifiOnly: Boolean) {
        DownloadService.sendSetRequirements(
            context,
            ExoDownloadService::class.java,
            downloadRequirements(wifiOnly),
            false
        )
    }

    /**
     * Downloads requested on a metered network while this is on are queued silently, with no visible
     * progress, until an unmetered network appears. Without a hint that reads as the download having
     * failed.
     */
    private fun notifyIfWaitingForWifi() {
        if (context.dataStore.get(DownloadOnWifiOnlyKey, false) && connectivityManager.isActiveNetworkMetered) {
            // Main.immediate, so the existing UI callers still post inline exactly as before while
            // the auto-download paths stop throwing. Those run on Room's executors and on
            // dlCoroutine, which have no Looper, and Toast kills the process without one. It would
            // only have fired for someone with wifi-only downloads on a metered network, which is
            // precisely the person this feature is for.
            CoroutineScope(Dispatchers.Main.immediate).launch {
                Toast.makeText(context, R.string.download_waiting_for_wifi, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val likedAutodownload by enumPreference(context, LikedAutoDownloadKey, LikedAutodownloadMode.OFF)

    /**
     * Wi-Fi only gates whether we enqueue at all. It deliberately never touches media3's
     * Requirements: those are service-wide, and the user already owns that single lever through the
     * "Download on Wi-Fi only" switch. A second writer would silently make manual downloads
     * Wi-Fi-only too, and the two settings would overwrite each other.
     */
    private fun autodownloadAllowedNow(mode: LikedAutodownloadMode) = when (mode) {
        LikedAutodownloadMode.OFF -> false
        LikedAutodownloadMode.ON -> true
        LikedAutodownloadMode.WIFI_ONLY -> !connectivityManager.isActiveNetworkMetered
    }

    /**
     * Call right after a like has been written to the database.
     *
     * Every guard lives here, so a call site is one line and cannot forget one. It no-ops on
     * un-like, on local files, on anything already downloaded or in flight, when the setting is off,
     * and when Wi-Fi only is set on a metered connection.
     *
     * Fires on the like itself, never on a download-state change. That is what makes deleting a
     * download of a song you still like stick, instead of it silently coming back.
     */
    fun autoDownloadOnLike(song: SongEntity) = autoDownloadOnLike(listOf(song))

    /** Batch variant, so liking 500 songs at once fires one Wi-Fi warning rather than 500. */
    fun autoDownloadOnLike(songs: List<SongEntity>) {
        val mode = likedAutodownload
        if (!autodownloadAllowedNow(mode)) return
        if (Throttle.isBlocked) return
        val eligible = songs.filter {
            it.liked && !it.isLocal && it.localPath == null && isNotDownloaded(it.id)
        }
        if (eligible.isEmpty()) return
        notifyIfWaitingForWifi()
        eligible.forEach { downloadSong(it.id, it.title) }
    }

    private val _likedDownloadState = MutableStateFlow<LikedDownloadState>(LikedDownloadState.Idle)

    /** Progress of the liked-songs catch up, for the settings row. Mirrors LoudnessRepair. */
    val likedDownloadState: StateFlow<LikedDownloadState> = _likedDownloadState.asStateFlow()

    sealed interface LikedDownloadState {
        data object Idle : LikedDownloadState

        /** [done] is how many have landed and [failed] how many media3 gave up on. */
        data class Running(val done: Int, val failed: Int, val total: Int) : LikedDownloadState

        /**
         * [done] and [failed] need not add up to [total]: a run stopped early cancels the rest, and
         * a song taken off the queue by hand during the run is neither. [stoppedEarly] covers that
         * second case too, since the rest were cancelled either way, whether from the row, from the
         * download notification or from a song's own menu.
         */
        data class Finished(
            val done: Int,
            val failed: Int,
            val total: Int,
            val stoppedEarly: Boolean,
        ) : LikedDownloadState
        data object NeedsWifi : LikedDownloadState
        data object NothingToDo : LikedDownloadState

        /** YouTube is refusing this network. Not a failure, and not worth retrying now. */
        data object Blocked : LikedDownloadState
    }

    private var likedJob: Job? = null

    /**
     * The song ids the current catch up enqueued.
     *
     * Kept so cancelling can drop this run's downloads and only this run's. The map it would
     * otherwise have to filter holds everything media3 is doing, including an album the listener
     * started by hand, and those are not ours to remove.
     */
    @Volatile
    private var likedBatch: Set<String> = emptySet()

    /**
     * How the current catch up's songs ended, as the listener saw it. See [LikedCatchUp]: a failed
     * song leaves nothing in the map, so this is the only place the run can find out about it.
     */
    private val likedEnds = MutableStateFlow<Map<String, LikedCatchUp.End>>(emptyMap())

    /**
     * Still working, unwinding included. isActive would go false the moment cancel returns while
     * the coroutine is still enqueuing, which is long enough for a second tap to start a second
     * run over the top of the first. Same trap as LoudnessRepair.isRunning.
     */
    val isDownloadingLiked: Boolean get() = likedJob?.isCompleted == false

    /**
     * Queues every liked song that is missing, then reports how many have actually landed and how
     * many failed.
     *
     * Progress follows the downloads themselves rather than the enqueue loop. Handing 300 requests
     * to media3 takes a moment; waiting for 300 songs to arrive is the part worth watching.
     */
    fun startLikedDownloads(mode: LikedAutodownloadMode = likedAutodownload) {
        if (isDownloadingLiked) return
        likedJob = CoroutineScope(dlCoroutine).launch {
            if (mode == LikedAutodownloadMode.OFF) {
                _likedDownloadState.value = LikedDownloadState.NothingToDo
                return@launch
            }
            if (!autodownloadAllowedNow(mode)) {
                _likedDownloadState.value = LikedDownloadState.NeedsWifi
                return@launch
            }
            // Never race a scan: it clears every dateDownload first, so until it has finished
            // re-registering them, every song reads as not downloaded.
            if (isProcessingDownloads.value) {
                _likedDownloadState.value = LikedDownloadState.NothingToDo
                return@launch
            }
            // Queueing hundreds of songs at a network YouTube is already refusing achieves nothing
            // except keeping it refused. Wait it out; it clears by itself.
            if (Throttle.isBlocked) {
                _likedDownloadState.value = LikedDownloadState.Blocked
                return@launch
            }

            val pending = database.likedSongsNotDownloaded().first()
                .filter { isNotDownloaded(it.id) }
            if (pending.isEmpty()) {
                _likedDownloadState.value = LikedDownloadState.NothingToDo
                return@launch
            }

            val batch = pending.map { it.id }.toSet()
            // A fresh record for this run. A late update for the last run's songs can still land
            // in it before the new batch is published, but any of those this run queues again gets
            // a fresh update from the enqueue below, and that clears it.
            likedEnds.value = emptyMap()
            likedBatch = batch
            val total = batch.size
            _likedDownloadState.value = LikedDownloadState.Running(done = 0, failed = 0, total = total)
            notifyIfWaitingForWifi()

            // Chunked with a yield between, so cancelling stays responsive on a large library
            // instead of having to wait out the whole enqueue.
            pending.chunked(ENQUEUE_CHUNK).forEach { chunk ->
                if (!currentCoroutineContext().isActive) return@launch
                chunk.forEach { song ->
                    if (!downloadSong(song.id, song.title)) {
                        likedEnds.update { LikedCatchUp.neverSent(it, song.id) }
                    }
                }
                yield()
            }

            // Follow the batch until every song has ended. first {} is what actually ends the
            // collection: returning from a collect lambda only ends that one emission, so the
            // earlier version kept collecting forever. The job must also stay owned by the
            // coroutine until it really finishes, or isDownloadingLiked reads false while this is
            // still running and tapping the row starts a second run on top of the first.
            //
            // Ended, not landed. Waiting for every song to land held the row at 299 of 300
            // whenever one download failed, since a failed song never lands, and the run only
            // ended when somebody stopped it by hand. The counting lives in LikedCatchUp.
            //
            // The endings are read fresh rather than taken from combine. combine collects each
            // flow in a coroutine of its own on a pool of threads, so it can deliver the listener's
            // map change before the ending change the listener wrote just ahead of it. A song
            // queued again after failing would then pair the new STATE_DOWNLOADING with the old
            // failure, count as ended, and could finish the run while it was still downloading.
            // Reading the endings after the map always gets the ones written with it or later.
            val tally = combine(downloads, likedEnds) { map, _ -> LikedCatchUp.tally(batch, map, likedEnds.value) }
                .distinctUntilChanged()
                .onEach {
                    _likedDownloadState.value =
                        LikedDownloadState.Running(done = it.landed, failed = it.failed, total = total)
                }
                .first { it.settled }

            // Not stopped from the row, but songs can still have been cancelled from elsewhere, and
            // then the row says so rather than reading as though the run got through its list.
            _likedDownloadState.value = LikedDownloadState.Finished(
                done = tally.landed,
                failed = tally.failed,
                total = total,
                stoppedEarly = tally.cutShort,
            )
        }
    }

    /**
     * Stops the catch up and drops what has not arrived yet.
     *
     * Removes rather than pauses: a paused download reads as STATE_INVALID, which this feature
     * treats as missing, so pausing would leave the row offering to start the same work again.
     * Songs already downloaded are kept.
     */
    fun cancelLikedDownloads() {
        val state = _likedDownloadState.value
        likedJob?.cancel()

        if (state is LikedDownloadState.Running) {
            // One look at where the run stands, map first and endings second for the same reason
            // as in the run itself.
            val batch = likedBatch
            val map = downloads.value
            val ends = likedEnds.value
            runCatching {
                // The queued ones, scoped to this run. It used to filter for STATE_INVALID,
                // which is the sentinel for failed and removed, so stopping did the opposite of
                // both things it promised: not one of the batch's queued downloads matched, so
                // media3 carried on with all of them, while songs that had failed at some
                // unrelated earlier point did match and were removed.
                LikedCatchUp.toRemoveOnStop(batch, map, ends).forEach { id ->
                    DownloadService.sendRemoveDownload(
                        context, ExoDownloadService::class.java, id, false
                    )
                }
            }.onFailure { Log.w(TAG, "Could not clear queued downloads on cancel", it) }
            // The counts at the moment of the stop, taken here rather than from the last progress
            // shown. A stop during the enqueue loop comes before the run has published any
            // progress, and the songs the download service refused by then were reported as
            // cancelled instead of failed. The removals just sent come back as removing, not
            // failed, and the run has stopped counting by then anyway, so cancelling can never
            // make it look as though downloads went wrong.
            val tally = LikedCatchUp.tally(batch, map, ends)
            _likedDownloadState.value = LikedDownloadState.Finished(
                done = tally.landed,
                failed = tally.failed,
                total = state.total,
                stoppedEarly = true,
            )
        } else {
            _likedDownloadState.value = LikedDownloadState.Idle
        }
    }

    /** Clears a finished result so the settings row goes back to resting. */
    fun acknowledgeLikedDownloads() {
        if (!isDownloadingLiked) _likedDownloadState.value = LikedDownloadState.Idle
    }

    /**
     * One shot: enqueue every liked song that is not already downloaded.
     *
     * Taking a single snapshot rather than collecting the flow is deliberate. onDownloadChanged
     * writes a null dateDownload for every non-completed state, so an enqueued song never leaves
     * that query and each state change would re-emit it: collecting it is a self-feeding loop. One
     * snapshot cannot loop, and each call only shrinks the pending set.
     *
     * @param mode passed in rather than read back, because the preference setter writes
     *   asynchronously and calling this straight after choosing a value would read the old one.
     * @return how many were queued, or -1 if the Wi-Fi rule blocked the whole run.
     */
    suspend fun downloadLikedSongs(mode: LikedAutodownloadMode = likedAutodownload): Int {
        if (mode == LikedAutodownloadMode.OFF) return 0
        if (!autodownloadAllowedNow(mode)) return -1
        if (Throttle.isBlocked) return 0
        // Never race a scan: it clears every dateDownload first, so until it has finished
        // re-registering them, every song reads as not downloaded.
        if (isProcessingDownloads.value) return 0
        val pending = database.likedSongsNotDownloaded().first().filter { isNotDownloaded(it.id) }
        if (pending.isEmpty()) return 0
        notifyIfWaitingForWifi()
        pending.forEach { downloadSong(it.id, it.title) }
        return pending.size
    }

    /**
     * A song with no download, or one whose download failed.
     *
     * The plain null check treated STATE_INVALID (epoch 0) as downloaded, so a song that failed
     * once and then went through any scan became invisible to the download button as well as to
     * auto-download.
     */
    private fun isNotDownloaded(id: String): Boolean =
        downloads.value[id].let { it == null || it == STATE_INVALID }

    /**
     * @return false only when the request never reached media3, so no update will ever come back
     *   for it. The liked-songs catch up needs to know, or it would wait for that song for good.
     */
    private fun downloadSong(id: String, title: String): Boolean {
        // Already landed or in flight, which the map shows without any help from here.
        if (!isNotDownloaded(id)) return true
        val downloadRequest = DownloadRequest.Builder(id, id.toUri())
            .setCustomCacheKey(id)
            .setData(title.toByteArray())
            .build()
        return try {
            DownloadService.sendAddDownload(
                context,
                ExoDownloadService::class.java,
                downloadRequest,
                false
            )
            true
        } catch (e: IllegalStateException) {
            // foreground = false is a bare startService. Every UI caller is in the foreground, but
            // liking is reachable from the media notification with no Activity alive, which Android
            // rejects. Losing one auto-download is not worth killing the process, and the backfill
            // picks it up.
            Log.w(TAG, "Could not enqueue download for $id from the background", e)
            false
        }
    }

    /**
     * Removes every download kept inside the app, the way removing one does: media3 drops each
     * from its index and its files, and the listener clears the database and the map as each goes.
     * Songs in a download folder are left alone.
     */
    fun removeAllInternalDownloads() {
        DownloadService.sendRemoveAllDownloads(context, ExoDownloadService::class.java, false)
    }

    fun resumeDownloadsOnStart() {
        DownloadService.sendResumeDownloads(
            context,
            ExoDownloadService::class.java,
            false
        )
    }


    /**
     * Removes a song's download wherever it is kept: its file in a download folder, and media3's
     * copy inside the app. Every Remove download goes through here. Only the song's own menu used
     * to look at the download folders; the player's, album, playlist and selection menus told
     * media3 alone, which knows nothing of them, so for a song downloaded there they did nothing.
     * media3 ignores a song it has no download for.
     */
    fun removeDownload(id: String) {
        deleteSong(id)
        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
    }

// Deletes from custom dl

    fun delete(song: PlaylistSong) = deleteSong(song.song.id)

    fun delete(song: SongItem) = deleteSong(song.id)

    fun delete(song: Song) = deleteSong(song.song.id)

    fun delete(song: SongEntity) = deleteSong(song.id)

    fun delete(song: MediaMetadata) = deleteSong(song.id)

    // The delete itself is a storage-provider binder call (DocumentsContract.deleteDocument), one
    // per file. It used to run straight on the caller's thread, which for every bulk Remove
    // download menu is a Compose onClick handler: a few hundred songs kept in a download folder
    // froze the UI for the whole loop and could show an ANR. Fired on dlCoroutine instead, so the
    // caller returns at once.
    private fun deleteSong(id: String) {
        CoroutineScope(dlCoroutine).launch {
            val deleted = localMgr.deleteFile(id)
            if (!deleted) return@launch
            // Recorded before the map update, so a rescan walking the folder concurrently (which
            // may still list the file this just deleted, or may already have missed it) keeps
            // this removal instead of the merge putting the song back from its snapshot.
            scanTouchedIds.add(id)
            downloads.update { map ->
                map.toMutableMap().apply {
                    remove(id)
                }
            }

            // Both columns. This used to build a copy of the song without its path and throw it
            // away, so the row went on pointing at the deleted file.
            database.query { removeDownloadSong(id) }
        }
    }

    /**
     * Retrieve song from cache, and delete it from cache afterwards
     */
    fun getFromCache(cache: SimpleCache, mediaId: String): ByteArray? {
        val spans: Set<CacheSpan> = cache.getCachedSpans(mediaId)
        if (spans.isEmpty()) return null

        val output = ByteArrayOutputStream()
        try {
            for (span in spans) {
                val file: File? = span.file
                FileInputStream(file).use { fis ->
                    fis.copyTo(output)
                }
            }
            return output.toByteArray()
        } catch (e: IOException) {
            reportException(e)
        } finally {
            output.close()
        }
        return null
    }

    /**
     * Migrated existing downloads from the download cache to the new system in external storage
     */
    suspend fun migrateDownloads() = scanLock.withLock {
        isProcessingDownloads.value = true

        var runs = 0
        try {
            // "skeleton" of old download manager to access old download data
            val dataSourceFactory = ResolvingDataSource.Factory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            OkHttpClient.Builder()
                                .proxy(YouTube.proxy)
                                .build()
                        )
                    )
            ) { dataSpec ->
                return@Factory dataSpec
            }

            val downloadManager: DownloadManager = DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                Executor(Runnable::run)
            ).apply {
                maxParallelDownloads = 3
            }

            // actual migration code
            val downloadedSongs = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                downloadedSongs[cursor.download.request.id] = cursor.download
            }

            // copy all completed downloads
            val toMigrate = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED }
            val migratedIndex = DefaultDownloadIndex(databaseProvider)
            toMigrate.forEach { s ->
                if (runs++ % 10 == 0) {
                    Log.d(TAG, "Migrating download: $runs/${toMigrate.size}")
                    if (runs % 20 == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "$runs/${toMigrate.size}", LENGTH_SHORT).show()
                        }
                    }
                }
                val songFromCache = getFromCache(downloadCache, s.key)
                if (songFromCache != null) {
                    // The file is written, and checked, before anything of the old copy goes. The
                    // cache used to be emptied first and a failed write swallowed, so a folder
                    // that refused the file cost the song outright.
                    val displayName = database.song(s.key).first()?.title ?: ""
                    val saved = localMgr.getFilePathIfExists(s.key) ?: runCatching {
                        localMgr.saveFile(s.key, songFromCache.inputStream(), displayName)
                    }.onFailure { reportException(it) }.getOrNull()
                    if (saved == null) {
                        Log.w(TAG, "Could not migrate ${s.key}, its download stays in the app")
                        return@forEach
                    }
                    // The index entry goes as well. Left behind it still said completed, and each
                    // later scan registered the song as downloaded again, even once its file in the
                    // folder had been deleted.
                    runCatching { migratedIndex.removeDownload(s.key) }
                        .onFailure { Log.w(TAG, "Could not drop the index entry of ${s.key}", it) }
                    downloadCache.removeResource(s.key)
                }
            }
            // The scan is what registers the copied files, so the songs play from them. Called
            // through scanDownloads() it returned at once, since this had already set the flag it
            // checks, and the migrated songs would not play offline until some later scan.
            scanDownloadsLocked()
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isProcessingDownloads.value = false
        }
    }


    fun cd() {
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        )
    }

    /**
     * One pass over the downloads at a time, and a second waits for the first to finish rather
     * than being dropped. The startup scan used to be skipped whenever it landed while init's
     * rescan was still walking the download folders, and a scan after changing the folders the
     * same way, because each checked a flag the running pass had set and returned.
     */
    private val scanLock = Mutex()

    /**
     * Rescan download directory and updates songs
     */
    suspend fun rescanDownloads() = scanLock.withLock { rescanDownloadsLocked() }

    private suspend fun rescanDownloadsLocked() {
        Log.i(TAG, "+rescanDownloads()")
        isProcessingDownloads.value = true
        // What scans before this version stored for failed and queued downloads. See DownloadSql.
        database.clearDownloadSentinels()
        val dbDownloads = database.downloadedOrQueuedSongs().first()
        val result = mutableMapOf<String, LocalDateTime>()

        // get missing files not in custom downloads or in internal downloads, remove them
        val missingFiles =
            localMgr.getMissingFiles(dbDownloads.filterNot { it.song.dateDownload == null }).toMutableList()
        Log.d(TAG, "Found ${missingFiles.size}/${dbDownloads.size} songs not in custom download directories")
        // Downloads media3 still has queued or running show as downloading. The database records
        // only finished downloads, so these come from the index, as the scan's sentinel used to
        // bring them.
        val inFlight = mutableMapOf<String, LocalDateTime>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                missingFiles.removeIf { it.id == download.request.id }
                if (stateToLocalDateTime(download) == STATE_DOWNLOADING) {
                    inFlight[download.request.id] = STATE_DOWNLOADING
                }
            }
        }
        Log.d(
            TAG,
            "Found ${missingFiles.size}/${dbDownloads.size} song not in custom download directories + internal cache. Removing these files now"
        )

        database.transaction {
            missingFiles.forEach {
                Log.v(TAG, "Shedding: [${it.id}] ${it.song.title}")
                removeDownloadSong(it.song.id)
            }
        }

        // new files
        val availableDownloads = dbDownloads.minus(missingFiles)
        availableDownloads.forEach { s ->
            result[s.song.id] = s.song.dateDownload!! // sql should cover our butts
        }
        inFlight.forEach { (id, state) -> result.putIfAbsent(id, state) }

        // The walk above takes a few seconds on a folder with enough files, and the listener goes
        // on writing to `downloads` the whole time (a download finishing or being removed). A
        // plain `downloads.value = result` used to throw all of that away with this stale
        // snapshot, so a song finishing mid-walk dropped out until the next launch, and one
        // removed mid-walk (Clear all downloads included) came back with its old date. Every id
        // the listener or deleteSong touched keeps its live value instead of the snapshot's.
        applyRescanResult(downloads, result, scanTouchedIds)
        isProcessingDownloads.value = false
        Log.i(TAG, "-rescanDownloads()")
    }


    /**
     * Scan and import downloaded songs from main and extra directories.
     *
     * This is intended for re-importing existing songs (ex. songs get moved, after restoring app backup), thus all
     * songs will already need to exist in the database.
     */
    suspend fun scanDownloads() = scanLock.withLock { scanDownloadsLocked() }

    private suspend fun scanDownloadsLocked() {
        Log.i(TAG, "+scanDownloads()")
        isProcessingDownloads.value = true

//            val scanner = LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, SCANNER_OWNER_DL)
        database.removeAllDownloadedSongs()
        val timeNow = LocalDateTime.now()

        // add custom downloads. Written before going on, here and below: queued on the database's
        // executor, the writes could still be pending when the map is rebuilt from the database at
        // the end, and the songs then read as not downloaded until the next rebuild.
        val availableFiles = localMgr.getAvailableFiles(false)
        database.transactionNow {
            availableFiles.forEach { f ->
                try {
                    val file = fileFromUri(context, f.value)
                    if (file == null) throw (InvalidAudioFileException("Hello darkness my old friend"))
                    // TODO: validate files in download folder
//                        val format: FormatEntity? = scanner.advancedScan(f.value).format
//                        if (format != null) {
//                            database.upsert(format)
//                        }
                    registerDownloadSong(f.key, timeNow, file.absolutePath)

                } catch (e: InvalidAudioFileException) {
                    reportException(e)
                }
            }
        }
//            LocalMediaScanner.destroyScanner(SCANNER_OWNER_DL)
        Log.d(TAG, "Registered ${availableFiles.size} files from custom downloads")

        // add internal downloads. Finished ones only, as the listener writes them: failed, stopped
        // and queued ones used to be stored as the sentinels 0 and 1, which every downloaded list
        // and count took for downloads. See DownloadSql.
        var count = 0
        database.transactionNow {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    val completedAt = completedDownloadTime(download.state, download.updateTimeMs) ?: continue
                    updateDownloadStatus(download.request.id, completedAt)
                    count++
                }
            }
        }
        Log.d(TAG, "Registered $count files from internal downloads")
        isProcessingDownloads.value = false
        Log.d(TAG, "Database registration complete, triggering map registry rebuild")
        rescanDownloadsLocked()
        Log.i(TAG, "-scanDownloads()")
    }

    companion object {
        val STATE_DOWNLOADING: LocalDateTime = Instant.ofEpochMilli(1).atZone(ZoneOffset.UTC).toLocalDateTime()
        val STATE_INVALID: LocalDateTime = Instant.ofEpochMilli(0).atZone(ZoneOffset.UTC).toLocalDateTime()

        /** Enqueue in bites, so cancelling a large catch up responds quickly. */
        private const val ENQUEUE_CHUNK = 25

        /** Minimum gap between the player requests downloads make. See the gate in the resolver. */
        private const val RESOLVE_GAP_MS = 700L
    }


    init {
        Log.i(TAG, "DownloadUtil init")
        // TODO: make sure db is update when download is queued
        CoroutineScope(dlCoroutine).launch {
            rescanDownloads()
        }

        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    // The map's removal below is all it ever shows of a failure, which looks the
                    // same as a song media3 has not been handed yet. The catch up needs the
                    // difference, so the ending is recorded for it here.
                    //
                    // Before the map, not after. LikedCatchUp.tally trusts an ending over the map,
                    // so with the map first a song queued again after failing would briefly show
                    // as downloading next to its old failure and count as ended. This way round a
                    // new start clears the ending before the map shows it, and an ending is in
                    // place before the map changes.
                    val batch = likedBatch
                    likedEnds.update {
                        LikedCatchUp.afterUpdate(it, batch, download.request.id, download.state)
                    }

                    // Before the map changes, so a rescan merging at the same moment keeps this
                    // value. See scanTouchedIds.
                    scanTouchedIds.add(download.request.id)
                    downloads.update { map ->
                        map.toMutableMap().apply {
                            val state = stateToLocalDateTime(download)
                            if (state == STATE_INVALID) {
                                Log.w(TAG, "Invalid download state for ${download.request.id}. Removing download")
                                remove(download.request.id)
                            } else {
                                set(download.request.id, state)
                            }
                        }
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        if (download.state == Download.STATE_COMPLETED) {
                            val updateTime =
                                Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
                            database.updateDownloadStatus(download.request.id, updateTime)
                        } else {
                            database.updateDownloadStatus(download.request.id, null)
                        }
                    }
                }
            }
        )
    }
}

fun stateToLocalDateTime(download: Download): LocalDateTime =
    stateToLocalDateTime(download.state, download.updateTimeMs)

/**
 * What a download scan stores in dateDownload for one of media3's downloads: when it finished, or
 * nothing for one that has not. The same as the download listener writes.
 */
fun completedDownloadTime(state: Int, updateTimeMs: Long): LocalDateTime? =
    if (state == Download.STATE_COMPLETED) stateToLocalDateTime(state, updateTimeMs) else null

/** The same rule taking plain values, so a test can feed it without building a media3 Download. */
fun stateToLocalDateTime(state: Int, updateTimeMs: Long): LocalDateTime {
    return when (state) {
        Download.STATE_COMPLETED -> {
            Instant.ofEpochMilli(updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
        }

        Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> STATE_DOWNLOADING
        else -> STATE_INVALID
    }
}

/**
 * Where a setting sits relative to the others, for deciding what counts as an upgrade. The one
 * rank table: the download resolver and MusicService.shouldUpgradeCached both go through it, via
 * isStaleQualityTier.
 *
 * Auto and High share a rank because on an unmetered connection they resolve to the same stream,
 * so treating a move between them as an upgrade would re-fetch for nothing.
 */
private fun AudioQuality.upgradeRank() = when (this) {
    AudioQuality.LOW -> 0
    AudioQuality.AUTO -> 1
    AudioQuality.HIGH -> 1
    AudioQuality.MAX -> 2
}

/**
 * Whether a copy recorded at [storedTier] ranks below [current], the quality setting now in
 * force. A plain function of two values, with no cache or database in it, so the rank rule is
 * tested without either.
 *
 * Null, or a tier the enum no longer has a case for, means the copy predates this field, or came
 * from before quality tiers were recorded at all; treated as no upgrade, for playback and
 * downloads alike, rather than re-fetching someone's whole cache the first time they open the app
 * after an update.
 */
fun isStaleQualityTier(storedTier: String?, current: AudioQuality): Boolean {
    val stored = storedTier?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: return false
    return current.upgradeRank() > stored.upgradeRank()
}

/**
 * What a rescan writes to the live downloads map: [snapshot], the result of the folder walk and
 * database read, except for [touchedIds], which keep whatever [live] (the map as the download
 * listener and deleteSong have been updating it in the meantime) has for them right now, gone
 * entirely if they removed them. A plain function of three maps, so the merge is tested without a walk,
 * a listener or a StateFlow.
 */
fun mergeRescanResult(
    snapshot: Map<String, LocalDateTime>,
    live: Map<String, LocalDateTime>,
    touchedIds: Set<String>
): Map<String, LocalDateTime> {
    val merged = snapshot.toMutableMap()
    for (id in touchedIds) {
        val liveValue = live[id]
        if (liveValue != null) merged[id] = liveValue else merged.remove(id)
    }
    return merged
}

/**
 * Writes a rescan's [snapshot] to [downloads] through mergeRescanResult, reading [touched] inside
 * the update. The listener and deleteSong add an id before they change the map, so a change racing
 * this merge either makes the update retry, and the retry reads the id, or lands on the merged
 * map. Only the ids the winning attempt merged are retired; one added later waits for the next
 * rescan.
 */
fun applyRescanResult(
    downloads: MutableStateFlow<Map<String, LocalDateTime>>,
    snapshot: Map<String, LocalDateTime>,
    touched: MutableSet<String>
) {
    var used = emptySet<String>()
    downloads.update { live ->
        used = touched.toSet()
        mergeRescanResult(snapshot, live, used)
    }
    touched.removeAll(used)
}
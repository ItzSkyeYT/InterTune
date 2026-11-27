package com.dd3boh.outertune.playback

import com.dd3boh.outertune.ui.player.ART_PREVIEW_PX
import com.dd3boh.outertune.utils.DownloadCovers
import com.dd3boh.outertune.ui.utils.resize
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
import com.dd3boh.outertune.playback.sabr.SabrOrHttp
import com.dd3boh.outertune.playback.sabr.SabrOver
import com.dd3boh.outertune.playback.sabr.SabrStarts
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.DownloadBitrate
import com.dd3boh.outertune.constants.DownloadBitrateKey
import com.dd3boh.outertune.constants.DownloadFormat
import com.dd3boh.outertune.constants.DownloadFormatKey
import com.dd3boh.outertune.constants.DownloadQuality
import com.dd3boh.outertune.constants.DownloadQualityKey
import com.dd3boh.outertune.utils.FailureLog
import com.dd3boh.outertune.utils.ReportTrail
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import kotlinx.coroutines.flow.drop
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.LikedAutoDownloadKey
import com.dd3boh.outertune.constants.LikedAutoDownloadedKey
import com.dd3boh.outertune.constants.LikedDownloadSyncKey
import androidx.datastore.preferences.core.edit
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
import com.dd3boh.outertune.playback.downloadManager.ConvertNotice
import com.dd3boh.outertune.playback.downloadManager.ConvertPlan
import com.dd3boh.outertune.playback.downloadManager.DocumentCastSong
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadFolderState
import com.dd3boh.outertune.playback.downloadManager.DownloadFormats
import com.dd3boh.outertune.playback.downloadManager.DownloadSays
import com.dd3boh.outertune.playback.downloadManager.ExportItem
import com.dd3boh.outertune.playback.downloadManager.ExportResult
import com.dd3boh.outertune.playback.downloadManager.ExportSkip
import com.dd3boh.outertune.playback.downloadManager.ExportSources
import com.dd3boh.outertune.playback.downloadManager.FileRemoval
import com.dd3boh.outertune.playback.downloadManager.OnceInAWhile
import com.dd3boh.outertune.playback.downloadManager.HeldWhilePlaying
import com.dd3boh.outertune.playback.downloadManager.MoveNotice
import com.dd3boh.outertune.playback.downloadManager.SkipReason
import com.dd3boh.outertune.playback.downloadManager.SourceCodec
import com.dd3boh.outertune.playback.downloadManager.SourceProbe
import com.dd3boh.outertune.playback.downloadManager.SongFileNames
import com.dd3boh.outertune.playback.downloadManager.exportLine
import com.dd3boh.outertune.playback.downloadManager.hasFile
import com.dd3boh.outertune.playback.downloadManager.writeInto
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.playback.downloadManager.SongConversion
import com.dd3boh.outertune.playback.downloadManager.SongConverter
import com.dd3boh.outertune.playback.downloadManager.SongFileCover
import com.dd3boh.outertune.playback.downloadManager.SongFiles
import com.dd3boh.outertune.playback.downloadManager.SongTags
import com.dd3boh.outertune.playback.downloadManager.knownPaths
import com.dd3boh.outertune.utils.StreamFamily
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.codecsOrEmpty
import com.dd3boh.outertune.utils.contentLengthOrZero
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.formatFileSize
import com.dd3boh.outertune.utils.preference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.ScanMerge
import com.dd3boh.outertune.utils.scanners.absoluteFilePathFromUri
import com.dd3boh.outertune.utils.scanners.FolderNesting
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.dd3boh.outertune.utils.Throttle
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import com.dd3boh.outertune.utils.Toaster

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

    /** Whether the connection is metered now, as Auto's choice of stream reads it (AudioPick); metered when it cannot be told. */
    private fun metered(): Boolean = runCatching { connectivityManager.isActiveNetworkMetered }.getOrDefault(true)
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val downloadQuality by enumPreference(context, DownloadQualityKey, DownloadQuality.SAME)

    // The file a download in the folder is saved as and the bitrate of an MP3 or an M4A (Unreleased.DOWNLOAD_FORMATS),
    // read for each song as it is saved, so that a change is for the next one.
    private val downloadFormat by enumPreference(context, DownloadFormatKey, DownloadFormat.ORIGINAL)
    private val downloadBitrate by enumPreference(context, DownloadBitrateKey, DownloadBitrate.AUTO)

    /**
     * Converts downloads to the format chosen, in the app's own files until a file is whole (SongConverter): not in the
     * cache, which Android empties when the phone is short of space, in the middle of a conversion if it comes to that.
     */
    private val converter by lazy {
        // The app stays up for as long as it converts (ConvertHold): the download service has stopped by then.
        val hold = com.dd3boh.outertune.playback.downloadManager.ConvertHoldService.hold(context) { Log.i(TAG, it) }
        SongConverter(File(context.filesDir, "convert"), MediaCodecPcmDecoder(), hold) { Log.i(TAG, it) }
    }

    /**
     * What a download asks for now, the Download quality or Audio quality for Same as playback (see
     * [qualityForDownload]), and what the player plays, the Audio quality, read in the same moment:
     * a download of the song the player still has open past its wait asks for that (waitWhileOpen).
     */
    private fun qualitiesNow(): Pair<AudioQuality, AudioQuality> = audioQuality.let { played -> qualityForDownload(downloadQuality, played) to played }

    // Concurrent, because media3 runs each download on its own thread and this is read and written
    // from all of them. An unsynchronised HashMap can corrupt its table under concurrent put.
    private val songUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Serialises the player requests that downloads make. See the gate in the resolver. */
    private val resolveGate = Any()

    /** The songs called gone in a row while downloading: see [GoneRun]. Apart from the player's. */
    private val goneRun = GoneRun()

    /**
     * The songs the player has open, whose files are not moved or replaced under it, and the moves
     * held for them. Made before the downloads' resolver below, which media3 may call as soon as
     * the manager is built.
     */
    private val inPlay = HeldWhilePlaying()
    private var lastResolveAt = 0L
    private val dataSourceFactory = ResolvingDataSource.Factory(
        // The song cache is read here, never written: a song it holds whole is copied from it (see
        // below), and nothing a download fetches goes into it. Without a writer of its own media3
        // writes every byte read into the cache, and each download was kept twice, in the
        // download cache and in the song cache: for good on Unlimited, and with a size it pushed
        // out the songs kept from streaming. A download is kept once, where downloads live (KeptOnce).
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(
                // A sabr: address to the SABR reader, any other over the family the address was
                // issued to, and a web client's with its page's headers, as its check was made:
                // see StreamFamily.Calls. Read in ranges of a megabyte: YouTube serves one request
                // for a whole stream at about twice real time, ranges at full speed (PiecewiseDataSource).
                SabrOrHttp.Factory(
                    PiecewiseDataSource.Factory(
                        OkHttpDataSource.Factory(
                            StreamFamily.Calls(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    .build()
                            )
                        )
                    )
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        val length = if (dataSpec.length >= 0) dataSpec.length else 1
        // What this download asks for (Unreleased.DOWNLOAD_QUALITY): the Download quality, or Audio
        // quality for Same as playback, and what the player plays. Read once, so that the cached copy
        // is judged by it, the stream is picked by it and the copy is recorded at it, even if a
        // setting changes meanwhile.
        val (asked, playing) = qualitiesNow()
        var staleCopy = false
        // The song the player still has open past the wait (waitWhileOpen) is asked for as the player
        // plays it, as every download was before 0.13: the stream it reads, never another.
        var asPlayed = false
        if (dataSpec.position == 0L) {
            // From the start, the player's copy is read through only when it holds the whole
            // song. A part of one used to be read through as soon as its first byte was there,
            // and the rest came from whatever stream was resolved below, so a song cached on
            // mobile data and downloaded on Wi-Fi joined two formats in one file. The part goes,
            // and the download takes the whole song from the new stream.
            if (playerCache.holdsWhole(mediaId)) {
                // Checked before the shortcut takes it, or a download made after raising the
                // audio quality, or asking for a higher Download quality than the copy was fetched
                // at, would copy the old cached bytes straight in and stay at the old bitrate for
                // good. A copy fetched at a lower setting is replaced, but only once the new stream
                // is in hand, as MusicService does for playback: if the fetch fails or the stream is
                // not checked, the download takes the old copy as before rather than losing the one
                // copy that plays offline. A copy above what the download asks for is taken as it
                // is: fetching to make it worse would spend data to lose quality.
                //
                // A database that does not answer is taken as a copy of unknown quality, and the
                // download takes it as it is. Given up: the better copy for this download, where
                // the wait used to hold one of the three download threads for good.
                val stored = runCatching { database.readOrNull { formatRow(mediaId) } }.getOrNull()
                if (!isStaleQualityTier(stored?.qualityTier, asked, metered = metered(), storedBitrate = stored?.bitrate)) return@Factory dataSpec
                // The copy goes below, and the player may be reading it: not while it has the song
                // open. Still open past the wait, the copy being read is taken as it is.
                if (!waitWhileOpen(mediaId)) return@Factory dataSpec
                staleCopy = true
            }
            // A Download quality of its own fetches another stream than the one the player reads,
            // which the player would read on from the download at its next seek: for the song the
            // player has open, not until it lets it go (HeldWhilePlaying). Still open past the wait,
            // the download asks for what the player plays. A copy taken as it is, above, is the very
            // bytes the player reads, and waits for nothing.
            if (asked != playing && !waitWhileOpen(mediaId)) asPlayed = true
            if (playerCache.holdsPartFromStart(mediaId)) {
                runCatching { playerCache.removeResource(mediaId) }
                    .onFailure { Log.w(TAG, "Could not drop the partial copy of $mediaId", it) }
            }
        } else if (playerCache.isCached(mediaId, dataSpec.position, length) && sameRecordedStream(recordedLength(playerCache, mediaId), recordedLength(downloadCache, mediaId))) {
            // A later range read from the player's copy only when both copies are the same stream: the
            // player's may be another quality than the part already downloaded, and the file would be both.
            return@Factory dataSpec
        }
        val quality = if (asPlayed) playing else asked

        // Not for a stale copy: the cache serves by the song's id whatever address comes back, so
        // a remembered url would still read the old bytes. Nor for a download asked for as the
        // player plays it: a remembered url was resolved at the Download quality.
        songUrlCache[mediaId]?.takeIf { !staleCopy && !asPlayed && stillUsable(it.first, it.second) }?.let {
            return@Factory dataSpec.withUri(it.first.toUri())
        }

        // Paced here rather than at the enqueue loop, because this is the only point a download's
        // player request actually passes through. Chunking the enqueue paces the loop; media3 then
        // drains it at whatever rate it likes.
        //
        // 700ms, derived from the loudness scan's 350ms rather than invented. A download resolve
        // cost two player requests on the healthy path, so this landed on the same requests per
        // second as the scan we already decided was polite. It costs one now that the client which
        // served the last song is asked first (StreamOrder), and the gap was left as it is: half
        // the requests in the same time is nothing to take back.
        //
        // The asymmetry is the point. On a healthy network the gap is invisible, because the audio
        // transfer that follows takes seconds. On a refused network nothing transfers and every
        // resolve fails in about a second, which is exactly when the app would otherwise hammer.
        //
        // Taken for each id asked: a song that is gone is asked for under up to three more
        // (StandIns), and those requests are a download's as much as the first.
        fun paced() = synchronized(resolveGate) {
            val wait = RESOLVE_GAP_MS - (SystemClock.elapsedRealtime() - lastResolveAt)
            if (wait > 0) Thread.sleep(wait)
            lastResolveAt = SystemClock.elapsedRealtime()
        }

        // A song whose own id YouTube no longer serves is downloaded from the id the same
        // recording goes by now, as the player plays it: see StandIns. Downloads keep a run of
        // their own, so a batch of them cannot end the player's (GoneRun), and report nothing.
        val resolveBegan = System.currentTimeMillis()
        val played = runBlocking(Dispatchers.IO) {
            StandIns(
                resolve = { id ->
                    paced()
                    YTPlayerUtils.playerResponseForPlayback(id, audioQuality = quality, connectivityManager = connectivityManager, forDownload = true)
                },
                wanted = { id ->
                    database.readOrNull { songRow(id) }?.let { row ->
                        StandIn.Wanted(row.title, database.readOrNull { artistNamesOf(id) }.orEmpty(), row.duration.takeIf { it > 0 })
                    }
                },
                search = { query ->
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>()
                },
                run = goneRun,
            ).playbackData(mediaId)
        }.getOrElse {
            if (staleCopy) return@Factory dataSpec
            throw it
        }
        val playbackData = played.data
        val format = playbackData.format
        // A download taken up partway whose first part is another upload's (the song's own id
        // has died since and this stream is a stand-in's, or the other way round) cannot be
        // finished: the file would be the start of one and the rest of the other. What is there
        // goes and this try fails, so that the next one takes the song from its start.
        // The same for a part of another quality: Auto takes one stream on Wi-Fi and another on mobile data,
        // and Audio quality or Download quality may change between a start and its resume. Judged by the length
        // the download's own cache recorded as it began; the format row is the player's as much as the download's.
        if (dataSpec.position > 0L) {
            val part = runCatching { database.readOrNull { formatRow(mediaId) } }.getOrNull()
            val another = partIsAnotherStream(recordedLength(downloadCache, mediaId), format.contentLengthOrZero())
            if (another || anotherUpload(part?.itag, part?.contentLength, format.itag, format.contentLengthOrZero())) {
                runCatching { downloadCache.removeResource(mediaId) }
                    .onFailure { Log.w(TAG, "Could not drop the part of $mediaId that is another stream's", it) }
                Log.i(TAG, "The part of $mediaId already downloaded is another stream's (${if (another) "its length" else "another upload"}): starting again")
                throw IOException("the part of this song already downloaded is another stream's")
            }
        }
        // A whole copy of the song itself is not given up for a stand-in's stream.
        if (staleCopy && played.from != mediaId) return@Factory dataSpec
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
                    qualityTier = cacheTier(quality, metered()),
                    playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                )
            )
            // A song saved from a list that gave no length (a search shelf, 6 Nov 2026) kept none
            // until it was played: the download has it now. Only the song's own stream, since a
            // stand-in's can be another cut of it, and only where none is known.
            if (played.from == mediaId) {
                playbackData.videoDetails?.lengthSeconds?.toIntOrNull()?.takeIf { it > 0 }
                    ?.let { fillSongDuration(mediaId, it) }
            }
        }

        // The developer's "Stream over SABR only", as the player has it (MusicService): the same
        // answer over SABR, so that a download goes over it too.
        val streamed = if (BuildConfig.PREVIEW_FEATURES && Unreleased.SABR && YTPlayerUtils.sabrOnly) {
            SabrOver.over(played.from, playbackData, since = resolveBegan, forDownload = true) ?: playbackData
        } else {
            playbackData
        }
        val streamUrl = downloadAddress(streamed.streamUrl, format.contentLength)

        songUrlCache[mediaId] = streamUrl to System.currentTimeMillis() + (streamed.streamExpiresInSeconds * 1000L)
        dataSpec.withUri(streamUrl.toUri())
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    /** The run of downloads the notification counts through, from its first song to its last. */
    internal val downloadRun = DownloadRun()
    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            maxParallelDownloads = 3
            // Download on Wi-Fi only as it is set, and always while Battery use is on Saver.
            requirements = downloadRequirements(wifiOnlyNow())
            // Before the download service's own listener, which builds the notification as each
            // song ends: the song is counted by then.
            addListener(downloadRun)
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    firstNotificationId = ExoDownloadService.FAILED_NOTIFICATION_ID_FIRST
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

    /**
     * The downloads the database records, shown until the download folders have been listed since
     * the start: [downloads] is empty until then, and every downloaded song showed the plain
     * Download arrow, the song the player came back to after the app was closed included (his
     * report of 27 Nov 2026). For the screens only ([getDownload]): what downloads, plays or is
     * removed still goes by [downloads] alone.
     */
    private val recordedUntilListed = RecordedUntilListed()

    var localMgr = DownloadDirectoryManagerOt(
        context,
        context.dataStore.get(DownloadPathKey, "").toUri(),
        uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
    )
    val downloadMgr = DownloadManagerOt(localMgr)
    var isProcessingDownloads = MutableStateFlow(false)

    /** Songs whose download finished since the last move into the folder, on the download manager's thread: see its onIdle. */
    private val finishedSinceMove = mutableSetOf<String>()

    /**
     * Where Remove download asks whether a song's kept cover can go, one at a time: a bulk removal
     * asks once a song, each a read of the whole song table, and in parallel they took every
     * database connection the lists read through.
     */
    private val coverChecks = Dispatchers.IO.limitedParallelism(1)

    /** The download of [songId] as the screens show it: see [recordedUntilListed]. */
    fun getDownload(songId: String): Flow<LocalDateTime?> =
        combine(downloads, recordedUntilListed.shown) { live, recorded -> DownloadShown.of(songId, live, recorded, scanTouchedIds) }

    /** Whether [id] has landed, in the app or in a download folder. A song on its way is not yet. */
    fun isDownloaded(id: String): Boolean = hasLanded(downloads.value[id])

    /**
     * The player has the songs [ids] open now (MusicService, on each change of song, queue or
     * state; none once it stops). The moves held for songs it let go of are made now, quietly, as
     * the move after a download is.
     */
    fun playerHas(ids: Set<String>) {
        val free = inPlay.playerHas(ids)
        if (free.isEmpty()) return
        Log.i(TAG, "${free.size} downloads held while they played go into the folder now")
        CoroutineScope(Dispatchers.IO).launch {
            if (context.dataStore.get(DownloadPathKey, "").isEmpty()) return@launch
            migrateDownloads(quiet = true, only = free)
            says.whenDone(free) { localMgr.getFilePathIfExists(it) != null }
        }
    }

    /**
     * Waits on the download's own thread while the player has [id] open, [OPEN_SONG_WAIT_MS] at
     * most: what the download does next would replace the bytes it reads (HeldWhilePlaying). True
     * once the player has let the song go; false when it still has it then (paused for long, the
     * queue ended on it, a long song), and the download goes on without replacing the copy being
     * read. A download stopped meanwhile ends the wait, media3 interrupts its thread.
     */
    private fun waitWhileOpen(id: String): Boolean {
        if (!inPlay.isOpen(id)) return true
        Log.i(TAG, "The download of $id waits, ${OPEN_SONG_WAIT_MS / 60_000} minutes at most, until the player lets the song go: it would replace the copy being played")
        val letGo = inPlay.awaitLetGo(id, OPEN_SONG_WAIT_MS, OPEN_SONG_POLL_MS, now = { SystemClock.elapsedRealtime() }, sleep = { Thread.sleep(it) })
        if (!letGo) Log.i(TAG, "The player still has $id open: its download takes what the player plays and replaces nothing")
        return letGo
    }

    /**
     * Opens once the download folders have been listed since the start (the rescan in init): until
     * then the folders' list is empty, and a song with its file in a folder is not found in it.
     */
    private val firstListing = java.util.concurrent.CountDownLatch(1)

    /**
     * The file of [id] in a download folder. Asked for a song whose row says its file is in one:
     * while the folders have not been listed since the start, it waits for that, at most [waitMs].
     * Until then the song was streamed, and offline it failed, unless the song cache happened to
     * hold a copy; a download is no longer copied there (KeptOnce). It is the case when the player
     * resumes as the app starts: a headset's button, Bluetooth in the car, Android Auto.
     */
    fun folderFileOf(id: String, waitMs: Long): androidx.documentfile.provider.DocumentFile? =
        afterFirstListing(firstListing, waitMs) { localMgr.isExists(id) }

    /**
     * Drops the song cache's copy of [id] once its download holds the song whole: a song played
     * before it was downloaded was kept in both, and the download was copied from the song cache.
     * The player reads a download first, from the download cache or the folder, so the copy only
     * took space. Not while the player has the song open: its next read would come from the
     * download, which in a folder is another file than the one it is reading (HeldWhilePlaying).
     * The sweep at the next start drops it then (dropSongCacheCopiesOfDownloads).
     */
    private fun dropSongCacheCopy(id: String, reason: String) {
        if (inPlay.isOpen(id)) {
            Log.d(TAG, "The song cache's copy of $id stays while the player has the song open")
            return
        }
        runCatching { playerCache.removeResource(id) }
            .onSuccess { Log.d(TAG, "The song cache's copy of $id goes, $reason") }
            .onFailure { Log.w(TAG, "Could not drop the song cache's copy of $id", it) }
    }

    /** [dropSongCacheCopy] for a download that just landed, off the manager's thread, if the download cache holds it whole. */
    private fun dropSongCacheCopyOnceDownloaded(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (runCatching { downloadCache.holdsWhole(id) }.getOrDefault(false)) dropSongCacheCopy(id, "it is downloaded")
        }
    }

    /**
     * The song cache's copies of songs the downloads hold whole, dropped: the ones every download
     * left there before this version, and any a song got by being played before its download. After
     * the rescan, so that the folders are listed and the map is whole.
     */
    private fun dropSongCacheCopiesOfDownloads() {
        val keys = runCatching { playerCache.keys.toList() }.getOrDefault(emptyList())
        if (keys.isEmpty()) return
        val inFolders = runCatching { localMgr.getAvailableFiles().keys }.getOrDefault(emptySet())
        val drop = KeptOnce.copiesToDrop(keys, downloads.value, inFolders) { id ->
            runCatching { downloadCache.holdsWhole(id) }.getOrDefault(false)
        }
        if (drop.isEmpty()) return
        Log.i(TAG, "Dropping ${drop.size} songs from the song cache, the downloads hold them")
        drop.forEach { dropSongCacheCopy(it, "its download holds it") }
    }

    // Downloads asked for by hand: the liked catch up no longer leaves these songs out
    // (LikedCatchUpMemory), whatever the person removed or what failed before.

    fun download(songs: List<MediaMetadata>) {
        wantedAgain(songs.map { it.id })
        if (songs.any { downloads.value[it.id] == null }) notifyIfWaitingForWifi()
        rememberAskedByHand(songs.map { it.id })
        songs.forEach { song -> downloadSong(song.id, song.title) }
    }

    fun download(song: MediaMetadata) {
        wantedAgain(listOf(song.id))
        if (downloads.value[song.id] == null) notifyIfWaitingForWifi()
        rememberAskedByHand(listOf(song.id))
        downloadSong(song.id, song.title)
    }

    fun download(song: SongEntity) {
        wantedAgain(listOf(song.id))
        if (downloads.value[song.id] == null) notifyIfWaitingForWifi()
        rememberAskedByHand(listOf(song.id))
        downloadSong(song.id, song.title)
    }

    /**
     * The simpler page (DownloadsSimpleLook.C): where the songs asked for by hand went, said once
     * they are there. Nothing where the page is today's.
     */
    private val says = DownloadSays(context)

    /** Not a song already downloaded: no download lands for it, and its id waited for good. */
    private fun rememberAskedByHand(ids: List<String>) = says.asked(ids.filterNot { isDownloaded(it) })

    /**
     * Where what the liked catch up remembers is read and changed: one thing at a time, in the
     * order they come, so that a failed download's removal is read after its failure
     * (LikedCatchUpMemory.afterChange).
     */
    private val catchUpWork = Dispatchers.IO.limitedParallelism(1)
    private val catchUpScope = CoroutineScope(catchUpWork + CoroutineExceptionHandler { _, e ->
        Log.w(TAG, "What the liked catch up remembers could not be changed", e)
    })
    private val catchUpFile get() = File(context.filesDir, LIKED_CATCH_UP_FILE)

    /** Read from its file the first time it is needed, on [catchUpWork]. */
    private val catchUpMemory by lazy { LikedCatchUpMemory.load(catchUpFile) }

    /** A write of [catchUpMemory] is on its way. On [catchUpWork]. */
    private var catchUpSaveDue = false

    /** Changes what the liked catch up remembers; written a moment later, so a bulk removal is one write. */
    private fun rememberForCatchUp(change: LikedCatchUpMemory.() -> Boolean) {
        catchUpScope.launch { if (catchUpMemory.change()) saveCatchUpSoon() }
    }

    /** On [catchUpWork]. */
    private suspend fun saveCatchUpSoon() {
        if (catchUpSaveDue) return
        catchUpSaveDue = true
        delay(CATCH_UP_SAVE_DELAY_MS)
        catchUpSaveDue = false
        runCatching { LikedCatchUpMemory.save(catchUpMemory, catchUpFile) }
            .onFailure { Log.w(TAG, "Could not save what the liked catch up remembers", it) }
    }

    /**
     * The person wants these songs again: downloaded by hand, liked anew here or on YouTube, or
     * every liked song asked for in Settings. A removal or a run of failures the liked catch up
     * remembered for them no longer holds it back (LikedCatchUpMemory).
     */
    fun wantedAgain(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val asked = ids.toList()
        rememberForCatchUp { this.wantedAgain(asked) }
    }

    /**
     * Network requirement for downloads. Unmetered rather than "wifi" specifically, so an unmetered
     * ethernet or hotspot connection also counts, and a metered wifi network correctly does not.
     */
    private fun downloadRequirements(wifiOnly: Boolean) =
        if (wifiOnly) Requirements(Requirements.NETWORK_UNMETERED) else Requirements(Requirements.NETWORK)

    /**
     * Whether downloads wait for Wi-Fi now: the switch as it is set, or Battery use on Saver,
     * which never writes the switch (BatteryUse.downloadsOnWifiOnly). Blocking, as the reads here
     * always were; the two rows that change it push the answer with [setDownloadRequirements].
     */
    private fun wifiOnlyNow(): Boolean = BatteryUse.downloadsOnWifiOnly(
        context.dataStore.get(DownloadOnWifiOnlyKey, false),
        context.batteryModeNow(),
    )

    /**
     * Applies the requirement immediately, including to downloads already queued or running. media3
     * pauses anything that no longer meets it and resumes automatically once it does, so nothing is
     * lost by toggling this mid-download.
     */
    fun setDownloadRequirements(wifiOnly: Boolean) {
        sends.attempt("setting the requirements") {
            DownloadService.sendSetRequirements(
                context,
                ExoDownloadService::class.java,
                downloadRequirements(wifiOnly),
                false
            )
        }
    }

    /**
     * Downloads requested on a metered network while this is on are queued silently, with no visible
     * progress, until an unmetered network appears. Without a hint that reads as the download having
     * failed.
     */
    private fun notifyIfWaitingForWifi() {
        if (wifiOnlyNow() && connectivityManager.isActiveNetworkMetered) {
            // Main.immediate, so the existing UI callers still post inline exactly as before while
            // the auto-download paths stop throwing. Those run on Room's executors and on
            // dlCoroutine, which have no Looper, and Toast kills the process without one. It would
            // only have fired for someone with wifi-only downloads on a metered network, which is
            // precisely the person this feature is for.
            CoroutineScope(Dispatchers.Main.immediate).launch {
                Toaster.show(context, R.string.download_waiting_for_wifi, Toast.LENGTH_SHORT)
            }
        }
    }

    private val likedAutodownload by enumPreference(context, LikedAutoDownloadKey, LikedAutodownloadMode.OFF)
    private val likedSync by preference(context, LikedDownloadSyncKey, false)

    /** A song the liked-songs setting downloaded itself (LikedDownloads), remembered so that an unlike can take it back. */
    private fun rememberLikedDownload(id: String) {
        if (!Unreleased.LIKED_SYNC) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { context.dataStore.edit { it[LikedAutoDownloadedKey] = it[LikedAutoDownloadedKey].orEmpty() + id } }
        }
    }

    /** A song whose download is gone, by any means: a later download of it by hand is never the setting's. */
    private fun forgetLikedDownload(id: String) {
        if (!Unreleased.LIKED_SYNC) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { context.dataStore.edit { prefs -> prefs[LikedAutoDownloadedKey]?.let { if (id in it) prefs[LikedAutoDownloadedKey] = it - id } } }
        }
    }

    /** Takes back the downloads the setting made for songs no longer liked, when its switch says so (LikedDownloads, issue #64). */
    private fun unlikeRemovesDownloads(unliked: List<SongEntity>) {
        if (!Unreleased.LIKED_SYNC || unliked.isEmpty() || likedAutodownload == LikedAutodownloadMode.OFF || !likedSync) return
        CoroutineScope(Dispatchers.IO).launch {
            val made = context.dataStore[LikedAutoDownloadedKey].orEmpty()
            LikedDownloads.toRemove(syncOn = true, made = made, songs = unliked).forEach { removeDownload(it) }
        }
    }

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
     * Call right after a like has been written to the database, given or taken back.
     *
     * Every guard lives here, so a call site is one line and cannot forget one. A like downloads; it
     * no-ops on local files, on anything already downloaded or in flight, when the setting is off,
     * and when Wi-Fi only is set on a metered connection. An unlike removes the download only where
     * the setting made it and its switch "Remove the download with the like" is on (LikedDownloads).
     *
     * Fires on the like itself, never on a download-state change. That is what makes deleting a
     * download of a song you still like stick, instead of it silently coming back.
     */
    fun autoDownloadOnLike(song: SongEntity) = autoDownloadOnLike(listOf(song))

    /** Batch variant, so liking 500 songs at once fires one Wi-Fi warning rather than 500. */
    fun autoDownloadOnLike(songs: List<SongEntity>) {
        unlikeRemovesDownloads(songs.filter { !it.liked })
        // A like given anew: a download of the song removed earlier, or one that kept failing, is
        // wanted again, even when the setting or Wi-Fi only holds it back now and the catch up
        // fetches it later.
        wantedAgain(songs.filter { it.liked }.map { it.id })
        val mode = likedAutodownload
        if (!autodownloadAllowedNow(mode)) return
        if (Throttle.isBlocked) return
        val eligible = songs.filter {
            it.liked && !it.isLocal && it.localPath == null && isNotDownloaded(it.id)
        }
        if (eligible.isEmpty()) return
        notifyIfWaitingForWifi()
        eligible.forEach { downloadSong(it.id, it.title, byLikes = true) }
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
            // Never race a scan: until it has written what it found, a song it is about to record
            // still reads as not downloaded.
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
                // A song whose file is in the download folder already is recorded, not fetched
                // again: a scan that lost track of them all queued every one (keepFolderFile).
                .filterNot { keepFolderFile(it.id, it.title, look = true) }
            if (pending.isEmpty()) {
                _likedDownloadState.value = LikedDownloadState.NothingToDo
                return@launch
            }

            val batch = pending.map { it.id }.toSet()
            // Asked for by the person, from Settings: songs whose download they removed come back,
            // as the row says, and those that kept failing are tried. The sync's catch up leaves
            // them out (downloadLikedSongs).
            wantedAgain(batch)
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
                    if (!downloadSong(song.id, song.title, byLikes = true)) {
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
                    forgetLikedDownload(id)
                    sends.attempt("removing $id") {
                        DownloadService.sendRemoveDownload(
                            context, ExoDownloadService::class.java, id, false
                        )
                    }
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
     * The liked sync's own, at every sync. It leaves out a song whose download the person removed
     * and one whose download keeps failing until its wait is over (LikedCatchUpMemory): the first
     * came back at the next sync, the second was queued and failed again, a new "Download failed"
     * each time. Downloads the setting makes, so taking back the like takes them too, with its
     * switch on (LikedDownloads).
     *
     * @param mode passed in rather than read back, because the preference setter writes
     *   asynchronously and calling this straight after choosing a value would read the old one.
     * @return how many were queued, or -1 if the Wi-Fi rule blocked the whole run.
     */
    suspend fun downloadLikedSongs(mode: LikedAutodownloadMode = likedAutodownload): Int {
        if (mode == LikedAutodownloadMode.OFF) return 0
        if (!autodownloadAllowedNow(mode)) return -1
        if (Throttle.isBlocked) return 0
        // Never race a scan: until it has written what it found, a song it is about to record
        // still reads as not downloaded.
        if (isProcessingDownloads.value) return 0
        val missing = database.likedSongsNotDownloaded().first().filter { isNotDownloaded(it.id) }.let { notDownloaded ->
            // Recorded, not fetched again, when its file is in the download folder already (keepFolderFile).
            withContext(Dispatchers.IO) { notDownloaded.filterNot { keepFolderFile(it.id, it.title, look = true) } }
        }
        val now = System.currentTimeMillis()
        val pending = withContext(catchUpWork) {
            forgetSongsNoLongerLiked(missing.mapTo(HashSet()) { it.id })
            missing.filter { catchUpMemory.mayCatchUp(it.id, now) }
        }
        if (pending.isEmpty()) return 0
        notifyIfWaitingForWifi()
        pending.forEach { downloadSong(it.id, it.title, byLikes = true) }
        return pending.size
    }

    /**
     * What the liked catch up remembers of songs no longer liked goes. Asked of the database only
     * when it holds a song outside [missing], the liked songs without a download, which is rare
     * once it is tidy. On [catchUpWork].
     */
    private fun forgetSongsNoLongerLiked(missing: Set<String>) {
        if (catchUpMemory.ids.all { it in missing }) return
        val liked = runCatching { database.likedSongIds().toHashSet() }
            .onFailure { Log.w(TAG, "Could not read the liked songs to tidy the catch up's memory", it) }
            .getOrNull() ?: return
        if (catchUpMemory.keepOnly(liked)) catchUpScope.launch { saveCatchUpSoon() }
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
     * Whether [id] has a file of its own in a download folder already, which is then recorded as its
     * download instead of being fetched again: in the folder's list, or, with [look], looked at in
     * the main folder under the name a download of it has always had ("<title> [<id>].mka"), which
     * asks the folder and so is for a background thread only.
     *
     * A song the app had forgotten while its file stayed in the folder was fetched again by the next
     * liked catch up, and the move after it wrote a second copy beside the first: every one of dvd's
     * 397 liked songs got a "(1)" (20 Nov 2026). Recorded here as the scan records a listed file.
     */
    private fun keepFolderFile(id: String, title: String?, look: Boolean): Boolean {
        val uri = runCatching {
            localMgr.getFilePathIfExists(id) ?: if (look) localMgr.fileAlreadyThere(id, title, null) else null
        }.getOrNull() ?: return false
        val path = runCatching { fileFromUri(context, uri)?.absolutePath }.getOrNull()
        val now = LocalDateTime.now()
        // Before the map changes, so a rescan merging at the same moment keeps this value. See scanTouchedIds.
        scanTouchedIds.add(id)
        downloads.update { it + (id to now) }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { if (path != null) database.registerDownloadSong(id, now, path) else database.updateDownloadStatus(id, now) }
                .onFailure { Log.w(TAG, "Could not record the file of $id in the download folder", it) }
        }
        Log.i(TAG, "$id has its own file in a download folder already: recorded, not fetched again")
        return true
    }

    /**
     * @return false only when the request never reached media3, so no update will ever come back
     *   for it. The liked-songs catch up needs to know, or it would wait for that song for good.
     */
    private fun downloadSong(id: String, title: String, byLikes: Boolean = false): Boolean {
        // Already landed or in flight, which the map shows without any help from here. Not the
        // liked-songs setting's then, even when it asks: it was there before it. Asked for by hand
        // (a playlist's or an album's Download), it is the person's from now on, even one the
        // setting fetched: taking back its like keeps it, as the switch's line says (26 Nov 2026).
        if (!isNotDownloaded(id)) {
            if (!byLikes) forgetLikedDownload(id)
            return true
        }
        // A file of the song in a download folder already: recorded, not fetched again and written
        // a second time beside itself. The folder's list only, read from memory: this runs on the
        // main thread for a button (keepFolderFile).
        if (keepFolderFile(id, title, look = false)) {
            if (!byLikes) forgetLikedDownload(id)
            return true
        }
        val downloadRequest = DownloadRequest.Builder(id, id.toUri())
            .setCustomCacheKey(id)
            .setData(title.toByteArray())
            .build()
        // foreground = false is a bare startService. Every UI caller is in the foreground, but
        // liking is reachable from the media notification and the widget with no Activity alive,
        // which Android rejects. Losing one auto-download is not worth killing the process, and
        // the backfill picks it up. The false is what tells the catch up that the request never
        // got through.
        val sent = sends.attempt("adding $id") {
            DownloadService.sendAddDownload(
                context,
                ExoDownloadService::class.java,
                downloadRequest,
                false
            )
        }
        // The setting's own, or the person's: a song the setting downloaded once, whose download
        // then failed or was cancelled, is downloaded again by hand, and taking back its like
        // must not take that download (LikedDownloads, issue #64).
        if (sent) {
            if (byLikes) rememberLikedDownload(id) else forgetLikedDownload(id)
        }
        return sent
    }

    /**
     * Removes every download kept inside the app, the way removing one does: media3 drops each
     * from its index and its files, and the listener clears the database and the map as each goes.
     * Songs in a download folder are left alone.
     */
    fun removeAllInternalDownloads() {
        sends.attempt("removing all downloads") {
            DownloadService.sendRemoveAllDownloads(context, ExoDownloadService::class.java, false)
        }
    }

    /**
     * Starts the download service so that it resumes the downloads media3 holds paused. Sent from
     * the automatic scan, a worker coroutine that can still be running after the person has left,
     * and Android refuses to start a service for an app in the background. The refusal is logged,
     * and the resume is sent again when the app is next in front: [resumeOwed], MainActivity.onResume
     * (issue #65, where it closed the app during an update).
     */
    fun resumeDownloadsOnStart() = sends.resume {
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
        forgetLikedDownload(id)
        // The liked catch up leaves it removed (LikedCatchUpMemory). Here as well as at the
        // download's removal in the listener: a song in a download folder has no download in
        // media3 to remove.
        val now = System.currentTimeMillis()
        rememberForCatchUp { removedByPerson(id, now) }
        // Its kept covers go once no download shows them. An album's songs share one cover, and
        // removing one song's download took it from the others, which then showed none offline.
        // Asked once this song's removal has reached the map, so that removing a whole album
        // leaves the cover to its last song, which takes it (coverUnusedOnceRemoved).
        CoroutineScope(coverChecks).launch {
            val url = runCatching { database.songRow(id)?.thumbnailUrl }.getOrNull()?.takeIf { it.startsWith("http") } ?: return@launch
            if (!coverUnusedOnceRemoved(id, downloads, COVER_WAIT_MS) { database.songsWithCover(url) }) return@launch
            for (model in listOf(url, url.resize(ART_PREVIEW_PX, ART_PREVIEW_PX))) DownloadCovers.remove(context.filesDir, model)
        }
        deleteSong(id)
        sends.attempt("removing $id") {
            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
        }
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
    private val readOnlyNote = OnceInAWhile(10_000)

    private fun deleteSong(id: String) {
        CoroutineScope(dlCoroutine).launch {
            val removal = localMgr.deleteFile(id)
            // A file the folder would not delete stays, and its song with it: nothing else on
            // screen changes, so the person is told why. A toast is made on the main thread, which
            // dlCoroutine is not (ToastOnMainThreadTest).
            if (removal == FileRemoval.REFUSED) {
                withContext(Dispatchers.Main) { Toaster.show(context, R.string.remove_download_refused, Toast.LENGTH_LONG) }
            }
            // A file in an extra import folder stays, as that folder is only read: said once for a
            // whole album's removal, with where to remove it instead.
            if (removal == FileRemoval.READ_ONLY && readOnlyNote.due(System.currentTimeMillis())) {
                withContext(Dispatchers.Main) { Toaster.show(context, R.string.remove_download_read_only, Toast.LENGTH_LONG) }
            }
            // A file gone already (deleted in a file manager) ends the song's download as a deleted
            // one does. It used to give up here and leave the song listed with its download state
            // until the next rescan (dvd, 11 Nov 2026: Remove all did nothing for it).
            if (!removal.over) return@launch
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
     * A song as media3 cached it, read from its files as one stream (cachedPieces), never whole into
     * memory (#67). Null when the cache holds none of it, or its first file cannot be opened. The
     * caller closes it.
     */
    fun openFromCache(cache: SimpleCache, mediaId: String): InputStream? {
        val files = cache.getCachedSpans(mediaId).mapNotNull { span: CacheSpan -> span.file }
        if (files.isEmpty()) return null
        return try {
            cachedPieces(files)
        } catch (e: IOException) {
            reportException(e)
            null
        }
    }

    /**
     * A downloaded song's cover, kept now as a file in the app's own storage (DownloadCovers), so it shows
     * with no network even if the song never played online and after Android empties the image cache (dvd, 27 Oct 2026: covers came only from playing). Two
     * addresses, the ones that are the same on every phone: the plain one lists ask for, and the
     * player's small first cover (ART_PREVIEW_PX). The player's sharp cover depends on the screen;
     * offline it falls back to the small one under it.
     */
    /** Every download's covers kept as files, for the downloads made before they were (0.12.6). A kept one costs nothing. */
    private suspend fun keepMissingCovers() = withContext(Dispatchers.IO) {
        for (id in downloads.value.keys) {
            val url = runCatching { database.song(id).first()?.thumbnailUrl }.getOrNull()
            if (url.isNullOrBlank() || !url.startsWith("http")) continue
            for (model in listOf(url, url.resize(ART_PREVIEW_PX, ART_PREVIEW_PX))) DownloadCovers.save(context.filesDir, model)
        }
    }

    private fun keepCoverForOffline(id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val url = runCatching { database.song(id).first()?.thumbnailUrl }.getOrNull()
            if (url.isNullOrBlank() || !url.startsWith("http")) return@launch
            for (model in listOf(url, url.resize(ART_PREVIEW_PX, ART_PREVIEW_PX))) {
                DownloadCovers.save(context.filesDir, model)
            }
        }
    }

    /**
     * Settings > Migrate. On a scope of its own, as the liked catch up is: it ran on the Storage
     * page's, and leaving the page cancelled it halfway, before the scan that registered the songs
     * moved so far.
     */
    fun startMigrate() {
        CoroutineScope(dlCoroutine).launch { migrateDownloads() }
    }

    /**
     * Moves the downloads kept inside the app into the download folder: all of them when asked
     * from Settings (Migrate, or the move offered once a folder is chosen), with a notification
     * that says how far it is and how it ended (MoveNotice), or [only] these songs, just
     * downloaded, without a word ([quiet]).
     */
    suspend fun migrateDownloads(quiet: Boolean = false, only: Set<String>? = null) = scanLock.withLock {
        // Nothing is converted, copied or dropped for a folder that does not open: the downloads stay
        // inside the app, which is where the folder's refusal of each file left them, after minutes
        // of conversion thrown away and a notice that said they were saved as they came. Said: once
        // a start after a download (notifyIfFolderCannotOpen), and at once to a move asked for.
        if (localMgr.folderState.value != DownloadFolderState.OPEN) {
            Log.w(TAG, "The download folder does not open (${localMgr.folderState.value}): nothing moves, the downloads stay inside the app")
            if (!quiet) withContext(Dispatchers.Main) { Toaster.show(context, R.string.dl_folder_cannot_open_notice, Toast.LENGTH_LONG) }
            return@withLock
        }
        isProcessingDownloads.value = true

        var runs = 0
        var notice: ConvertNotice? = null
        var moveNotice: MoveNotice? = null
        var movedCount = 0
        // The songs left for later because the player has them open (HeldWhilePlaying): a move asked
        // for says they come after, rather than that it is done.
        var held = 0
        try {
            // What media3 holds, read from its index. A second DownloadManager over it, as this
            // used to make for the same list, started a thread of its own that was never let go.
            val migratedIndex = DefaultDownloadIndex(databaseProvider)
            val downloadedSongs = mutableMapOf<String, Download>()
            migratedIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) downloadedSongs[cursor.download.request.id] = cursor.download
            }

            // copy all completed downloads
            val completed = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED && (only == null || it.key in only) }
            // Not a song the player has open: its file would change under it. It goes once it is let go (playerHas).
            val movable = inPlay.movableNow(completed.keys)
            held = completed.size - movable.size
            if (held > 0) Log.i(TAG, "$held downloads wait until they no longer play to move")
            val toMigrate = completed.filterKeys { it in movable }
            // Songs made another format (Unreleased.DOWNLOAD_FORMATS) take a while each: the phone says so.
            val format = DownloadFormats.effective(downloadFormat, Unreleased.DOWNLOAD_FORMATS)
            if (Unreleased.DOWNLOAD_FILES && format != DownloadFormat.ORIGINAL && toMigrate.isNotEmpty()) {
                notice = ConvertNotice(context, format, toMigrate.size)
                converter.sweep()
            }
            // The move asked for in Settings (Migrate, or the move offered once a folder is
            // chosen) shows how far it is in a notification, and how it ended; the quiet move
            // after a download does not. Where no notification can be seen, the toast below stays.
            if (!quiet && toMigrate.isNotEmpty() && MoveNotice.canShow(context)) {
                moveNotice = MoveNotice(context, toMigrate.size)
            }
            var position = 0
            toMigrate.forEach { s ->
                moveNotice?.starting(runs)
                if (runs++ % 10 == 0) {
                    Log.d(TAG, "Migrating download: $runs/${toMigrate.size}")
                }
                // One way of telling: the toast only where the notification cannot show. It sat
                // inside the block above, where the count is 1, 11, 21..., and so never came.
                if (MoveNotice.toastDue(runs, quiet, noticeShown = moveNotice != null)) {
                    withContext(Dispatchers.Main) {
                        Toaster.show(context, "$runs/${toMigrate.size}", LENGTH_SHORT)
                    }
                }
                // Copied from the cache's files as the new file is written: read whole into memory
                // first, a long mix ran the app out of memory (#67).
                val fromCache = openFromCache(downloadCache, s.key) ?: return@forEach
                // The notice says Converting only for a song that is encoded, not for one saved as it came.
                val index = position++
                val title = if (notice != null) runCatching { database.song(s.key).first()?.title }.getOrNull() else null
                // The file written and checked, the song's row pointing to it, and only then the
                // copy in the app gone, one song at a time (moveIntoFolder). The cache used to be
                // emptied first and a failed write swallowed, so a folder that refused the file cost
                // the song outright; then the copy went before the row knew the file, and the song
                // played from the network until the scan after the whole batch.
                val moved = moveIntoFolder(
                    save = {
                        fromCache.use { stream ->
                            val song = database.song(s.key).first()
                            val displayName = song?.title ?: ""
                            localMgr.getFilePathIfExists(s.key)
                                // The song's own file in the folder already, from an earlier download, at
                                // its recorded path or under its download name: kept, never doubled. Each
                                // "Title [id].mka (1)" in dvd's folder was written beside its own first file.
                                ?: localMgr.fileAlreadyThere(s.key, displayName, song?.song?.localPath)
                                // A file other players open, with the song's tags and cover inside; the
                                // stream as it is when the song cannot be one (saveSongFile).
                                ?: (if (Unreleased.DOWNLOAD_FILES) saveSongFile(s.key, notice) { notice?.starting(index, title) } else null)
                                ?: runCatching {
                                    localMgr.saveFile(s.key, stream, displayName)
                                }.onFailure { reportException(it) }.getOrNull()
                        }
                    },
                    pathOf = { uri -> runCatching { fileFromUri(context, uri)?.absolutePath }.getOrNull() },
                    // The date the download landed, as the download listener wrote it.
                    record = { path -> database.registerDownloadSong(s.key, stateToLocalDateTime(s.value), path) },
                    dropCopy = {
                        // The index entry goes as well. Left behind it still said completed, and each
                        // later scan registered the song as downloaded again, even once its file in the
                        // folder had been deleted.
                        runCatching { migratedIndex.removeDownload(s.key) }
                            .onFailure { Log.w(TAG, "Could not drop the index entry of ${s.key}", it) }
                        downloadCache.removeResource(s.key)
                    },
                )
                when (moved) {
                    FolderMove.MOVED -> movedCount++
                    FolderMove.NOT_WRITTEN -> Log.w(TAG, "Could not migrate ${s.key}, its download stays in the app")
                    FolderMove.NO_PATH -> Log.w(TAG, "The folder's file of ${s.key} has no path on the phone, its download stays in the app as well")
                    FolderMove.NOT_RECORDED -> Log.w(TAG, "Could not record the folder's file of ${s.key}, its download stays in the app as well")
                }
            }
            // Each song's row was written as it moved; the scan still walks the folder once and
            // brings the folder's list and the map up to date. Called through scanDownloads() it
            // returned at once, since this had already set the flag it checks.
            scanDownloadsLocked()
        } catch (e: Exception) {
            reportException(e)
        } finally {
            notice?.finish()
            // Done, or how many stay inside the app: a song with nothing in the cache to copy, one
            // the folder refused, or the rest of a move that failed halfway; and the songs that
            // move once they stop playing.
            moveNotice?.finish(movedCount, held)
            // A move asked for with no notification to say it in (none can show, or every song it
            // found plays): the songs that come after are said in a toast.
            if (!quiet && held > 0 && moveNotice == null) {
                val later = context.resources.getQuantityString(R.plurals.dl_move_notice_held, held, held)
                CoroutineScope(Dispatchers.Main.immediate).launch { Toaster.show(context, later, Toast.LENGTH_LONG) }
            }
            isProcessingDownloads.value = false
        }
    }


    /**
     * The song media3 cached as [id] saved into the download folder as a song file other players
     * open (Unreleased.DOWNLOAD_FILES, SongFiles): Opus as .opus, AAC as .m4a, the same packets, its
     * title, artists, album, year, track and cover inside, named "Artist - Title". Null when it
     * cannot be one, and then nothing was written.
     *
     * With a File format other than Original (Unreleased.DOWNLOAD_FORMATS) the song is converted
     * first, off the main thread and below the music's priority, into a temporary file, and put in
     * the folder only when whole; a conversion that fails is saved as Original, and [notice] counts
     * it. A conversion that is stopped (the coroutine is cancelled) stops the move.
     */
    private suspend fun saveSongFile(id: String, notice: ConvertNotice? = null, onEncode: () -> Unit = {}): android.net.Uri? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val format = DownloadFormats.effective(downloadFormat, Unreleased.DOWNLOAD_FORMATS)
        // Below the music's priority while it encodes: SongConverter lowers its own thread.
        val conversion = if (format == DownloadFormat.ORIGINAL) null else SongConversion(format, downloadBitrate, converter, { job?.isActive == false }, onEncode)
        runCatching {
            val files = downloadCache.getCachedSpans(id).mapNotNull { span: CacheSpan -> span.file }
            val song = database.song(id).first()
            if (files.isEmpty() || song == null) return@runCatching null
            SongFiles.save(files, songTags(song), conversion) { name, write -> localMgr.saveSongFile(id, name, write) }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Could not save $id as a song file, it is saved as the stream", it)
        }.getOrNull().also { saved ->
            // Said as saved as it came only when it was: a folder that refused the converted file
            // refuses the song as it came too, and then nothing was saved at all (the song stays
            // inside the app, which the move's own notice counts).
            if (conversion?.failure != null && saved != null) notice?.fellBack()
        }
    }


    // ---- Export to folder (Unreleased.DOWNLOAD_FORMATS)

    /** How an export stands, for the sheet that started it. */
    sealed interface ExportState {
        data object Idle : ExportState
        data class Running(val done: Int, val total: Int, val title: String?) : ExportState
        data class Finished(val result: ExportResult) : ExportState
    }

    val exportState = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Stops the export between two songs, or two pieces of one: what it has done is kept, nothing half-written is. */
    private val exportStop = AtomicBoolean(false)

    /** How many sheets show the export now: a result nobody is looking at is told in a toast instead. */
    val exportWatchers = AtomicInteger(0)

    /**
     * Exports [items] to the folder [target] (a tree the person picked, or the download folder) as
     * [format], at [bitrate] for an MP3 or an M4A. Songs not on the phone are skipped and named,
     * never fetched. Runs on its own thread whatever screen is up; the state tells how far it is.
     */
    fun startExport(items: List<ExportItem>, format: DownloadFormat, bitrate: DownloadBitrate, target: Uri) {
        if (exportState.value is ExportState.Running || items.isEmpty()) return
        exportStop.set(false)
        exportState.value = ExportState.Running(0, items.size, null)
        CoroutineScope(dlCoroutine).launch {
            val result = runCatching { exportSongs(items, format, bitrate, target) }
                .onFailure {
                    Log.w(TAG, "The export failed", it)
                    FailureLog.record("Exporting the songs to a folder", it)
                }
                .getOrElse { ExportResult(0, items.map { ExportSkip(it, SkipReason.FAILED) }) }
            exportState.value = ExportState.Finished(result)
            if (exportWatchers.get() == 0) {
                withContext(Dispatchers.Main) { Toaster.show(context, exportLine(context, result), Toast.LENGTH_LONG) }
            }
        }
    }

    fun cancelExport() = exportStop.set(true)

    /** The result has been read: the sheet goes back to the choices for the next export. */
    fun acknowledgeExport() {
        if (exportState.value is ExportState.Finished) exportState.value = ExportState.Idle
    }

    private suspend fun exportSongs(items: List<ExportItem>, format: DownloadFormat, bitrate: DownloadBitrate, target: Uri): ExportResult =
        withContext(Dispatchers.IO) {
            val dir = runCatching { DocumentFile.fromTreeUri(context, target) }.getOrNull()
            if (dir == null || !dir.isDirectory || !dir.canWrite()) {
                Log.w(TAG, "Export: nothing can be written to $target")
                return@withContext ExportResult(0, items.map { ExportSkip(it, SkipReason.FAILED) })
            }
            // Original into a download folder: the song's file is there already, and a copy would be a second one.
            val inDownloads = localMgr.allDirs.any { FolderNesting.isSameOrInside(target.toString(), it.uri.toString()) }
            val notice = if (format != DownloadFormat.ORIGINAL) ConvertNotice(context, format, items.size) else null
            converter.sweep()
            var exported = 0
            val skipped = mutableListOf<ExportSkip>()
            var stopped = false
            try {
                for ((index, item) in items.withIndex()) {
                    if (exportStop.get()) { stopped = true; break }
                    exportState.value = ExportState.Running(index, items.size, item.title)
                    notice?.starting(index, item.title)
                    val left = try {
                        exportOne(item, format, bitrate, dir, inDownloads) { exportStop.get() }
                    } catch (e: CancellationException) {
                        stopped = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not export ${item.id}", e)
                        SkipReason.FAILED
                    }
                    if (left == null) exported++ else skipped += ExportSkip(item, left)
                }
            } finally {
                notice?.finish()
            }
            ExportResult(exported, skipped, stopped)
        }

    /** One song exported: null when it was, or why it was left out. */
    private suspend fun exportOne(
        item: ExportItem,
        format: DownloadFormat,
        bitrate: DownloadBitrate,
        dir: DocumentFile,
        inDownloads: Boolean,
        cancelled: () -> Boolean,
    ): SkipReason? {
        if (item.isLocal) return SkipReason.LOCAL
        val folderFile = localMgr.isExists(item.id)?.takeIf { it.exists() }
        val source = ExportSources.pick(
            isLocal = false,
            inFolder = folderFile != null,
            inDownloadCache = runCatching { downloadCache.holdsWhole(item.id) }.getOrDefault(false),
            inSongCache = runCatching { playerCache.holdsWhole(item.id) }.getOrDefault(false),
        )
        if (source == ExportSources.Source.NOWHERE) return SkipReason.NOT_ON_PHONE
        // Original into a download folder for a song still inside the app: a copy would be a second, unlinked one (INSIDE_APP).
        if (format == DownloadFormat.ORIGINAL && inDownloads && source != ExportSources.Source.FOLDER_FILE) return SkipReason.INSIDE_APP
        val song = database.song(item.id).first() ?: return SkipReason.NOT_ON_PHONE
        // An exported copy carries no link: if it is exported into a download folder, the app must not take it for the song's download.
        val tags = songTags(song).copy(keepLink = false)
        // A file of that name in the folder is this song from an export before, or a song named the same: it stays as it is,
        // and nothing is converted for it (see hasFile).
        if (format != DownloadFormat.ORIGINAL && hasFile(dir, SongFileNames.of(tags, format.extension ?: return SkipReason.FAILED))) return SkipReason.ALREADY_THERE
        return when (source) {
            ExportSources.Source.FOLDER_FILE -> exportFolderFile(item, folderFile!!, tags, format, bitrate, dir, inDownloads, cancelled)
            ExportSources.Source.DOWNLOAD_CACHE, ExportSources.Source.SONG_CACHE -> {
                val cache = if (source == ExportSources.Source.DOWNLOAD_CACHE) downloadCache else playerCache
                val files = cache.getCachedSpans(item.id).mapNotNull { span: CacheSpan -> span.file }
                if (files.isEmpty()) return SkipReason.NOT_ON_PHONE
                val conversion = if (format == DownloadFormat.ORIGINAL) null else SongConversion(format, bitrate, converter, cancelled)
                var there = false
                val saved = SongFiles.save(files, tags, conversion, fallBack = false) { name, write ->
                    if (hasFile(dir, name)) { there = true; null } else writeInto(context, dir, name, write)
                }
                if (saved != null) null else if (there) SkipReason.ALREADY_THERE else SkipReason.FAILED
            }
            else -> SkipReason.NOT_ON_PHONE
        }
    }

    /** A song whose file is in a download folder: copied as it is when it is in the format asked for, else decoded and encoded. */
    private suspend fun exportFolderFile(
        item: ExportItem,
        file: DocumentFile,
        tags: SongTags,
        format: DownloadFormat,
        bitrate: DownloadBitrate,
        dir: DocumentFile,
        inDownloads: Boolean,
        cancelled: () -> Boolean,
    ): SkipReason? {
        val name = file.name ?: return SkipReason.FAILED
        val extension = name.substringAfterLast('.', "").lowercase()
        val song = DocumentCastSong(context, file.uri, file.length(), file.type ?: "application/octet-stream")
        val probe = runCatching { SourceProbe.of(song) }.getOrNull()
        val codec = DownloadFormats.sourceOfExtension(extension) ?: probe?.codec ?: return SkipReason.FAILED
        // Auto follows what YouTube sent: the file's own rate when the file is that stream, else the rate its format row recorded.
        val measured = probe?.durationUs?.takeIf { it > 0 && (codec == SourceCodec.OPUS || codec == SourceCodec.AAC) }?.let { file.length() * 8_000_000L / it }
        val bps = measured ?: runCatching { database.format(item.id).first()?.bitrate?.toLong() }.getOrNull()
        return when (val plan = DownloadFormats.plan(format, bitrate, codec, bps)) {
            ConvertPlan.AsIs -> {
                if (inDownloads) return SkipReason.ALREADY_THERE
                val target = SongFileNames.of(tags, extension.ifEmpty { "bin" })
                if (hasFile(dir, target)) return SkipReason.ALREADY_THERE
                val copy = writeInto(context, dir, target) { out ->
                    (context.contentResolver.openInputStream(file.uri) ?: throw IOException("Could not read ${file.uri}")).use { it.copyTo(out, 64 * 1024) }
                }
                if (copy != null) null else SkipReason.FAILED
            }
            is ConvertPlan.Encode -> {
                val converted = converter.convert(song, tags, plan, probe?.durationUs ?: 0L, cancelled)
                try {
                    val made = writeInto(context, dir, SongFileNames.of(tags, converted.extension)) { out -> converted.copyTo(out) }
                    if (made != null) null else SkipReason.FAILED
                } finally {
                    converted.close()
                }
            }
        }
    }

    /** What a song file says of [song]: see SongTags. The track number only from an album page seen. */
    private fun songTags(song: Song): SongTags {
        val album = song.album
        val track = song.song.trackNumber?.takeIf { it > 0 }
            ?: album?.takeIf { it.playlistId != null }
                ?.let { runCatching { database.albumIndexOf(song.id, it.id) }.getOrNull() }
                ?.let { it + 1 }
        return SongTags(
            videoId = song.id,
            title = song.song.title,
            artists = song.artists.map { it.name },
            album = album?.title ?: song.song.albumName,
            year = song.song.year ?: album?.year,
            track = track,
            cover = SongFileCover.of(song.song.thumbnailUrl, context.filesDir),
        )
    }

    fun cd() {
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        )
    }

    /**
     * How many downloads are still inside the app, counted as the scan counts them: the finished
     * entries of media3's index (a move into the folder takes a song's entry out). Reads the
     * index's database: off the main thread.
     */
    fun downloadsInsideApp(): Int {
        var count = 0
        runCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    if (completedDownloadTime(download.state, download.updateTimeMs) != null) count++
                }
            }
        }.onFailure { Log.w(TAG, "Could not count the downloads inside the app", it) }
        return count
    }

    /** Whether this start of the app has said already that the download folder does not open. */
    private val folderNoticeGiven = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * A download finished while the folder saved in Settings does not open (DownloadFolderState):
     * it stays inside the app, and the move after it would fail without a word, as it did on his
     * phone for days (11 Nov 2026). Said once a start, the way the wait for Wi-Fi is said; the
     * Storage page has the way to choose the folder again.
     */
    private fun notifyIfFolderCannotOpen() {
        if (localMgr.folderState.value != DownloadFolderState.CANNOT_OPEN) return
        if (!folderNoticeGiven.compareAndSet(false, true)) return
        Log.i(TAG, "The download folder does not open, the download stays inside the app: said once")
        CoroutineScope(Dispatchers.Main.immediate).launch {
            Toaster.show(context, R.string.dl_folder_cannot_open_notice, Toast.LENGTH_LONG)
        }
    }

    /**
     * One pass over the downloads at a time, and a second waits for the first to finish rather
     * than being dropped. The startup scan used to be skipped whenever it landed while init's
     * rescan was still walking the download folders, and a scan after changing the folders the
     * same way, because each checked a flag the running pass had set and returned.
     */
    private val scanLock = Mutex()

    /**
     * The extra copies of downloads a name clash left in the download folder, looked for once a start
     * and removed on the person's tap (DownloadCopiesCleanup, Unreleased.DOWNLOAD_COPIES_CLEANUP).
     * Before init: its first look is made from there, on another thread.
     */
    val copies by lazy {
        DownloadCopiesCleanup(context, database, { localMgr }, scanLock, inPlay::isOpen) { id, path -> recordKeptCopy(id, path) }
    }

    /** The card's Remove: the copies go in the background, whatever screen comes next, and a toast says how it ended. */
    fun removeCopies() {
        CoroutineScope(Dispatchers.IO).launch {
            val removed = runCatching { copies.remove() }.onFailure { reportException(it) }.getOrNull() ?: return@launch
            val said = context.resources.getQuantityString(R.plurals.dl_copies_removed, removed.files, removed.files, formatFileSize(context, removed.bytes))
            withContext(Dispatchers.Main) { Toaster.show(context, said, Toast.LENGTH_LONG) }
        }
    }

    /**
     * The one file a song kept of its extra copies, at [path], recorded as its download, as a scan
     * records a listed file (keepFolderFile): the map first, after scanTouchedIds, the date it had
     * kept. On the cleanup's thread.
     */
    private fun recordKeptCopy(id: String, path: String) {
        val date = downloads.value[id]?.takeIf { hasLanded(it) } ?: LocalDateTime.now()
        scanTouchedIds.add(id)
        downloads.update { it + (id to date) }
        runCatching { database.registerDownloadSong(id, date, path) }
            .onFailure { Log.w(TAG, "Could not record $path as the file of $id", it) }
    }

    /**
     * Rescan download directory and updates songs
     */
    suspend fun rescanDownloads() = scanLock.withLock { rescanDownloadsLocked() }

    /**
     * The songs, out of [idToLocalPath], under a download folder the last full listing could not
     * read and whose permission this install holds: left alone by a scan (idsSparedByUnlistedFolders,
     * #278). Each folder's path comes from absoluteFilePathFromUri, not fileFromUri: these are tree
     * addresses with no document part, which fileFromUri throws on from API 30. A saved address that
     * is not a tree makes even this throw; such a root reads as unknown (null), which spares every
     * song: the safe side, while its permission is held. Permissions that cannot be read spare
     * under every folder, as before.
     */
    private fun sparedUnderUnlistedFolders(idToLocalPath: Map<String, String?>): Set<String> {
        val unlisted = localMgr.unlistedDirs
        if (unlisted.isEmpty()) return emptySet()
        val held = runCatching { context.contentResolver.persistedUriPermissions.map { it.uri.toString() } }
            .onFailure { Log.w(TAG, "Could not read the folder permissions: every folder not listed spares its songs", it) }
            .getOrNull()
        val folders = unlisted.associate { it.toString() to runCatching { absoluteFilePathFromUri(context, it) }.getOrNull() }
        val notHeld = held?.let { h -> folders.keys.count { address -> h.none { sameFolder(it, address) } } } ?: 0
        if (notHeld > 0) Log.i(
            TAG,
            "$notHeld of ${folders.size} folders not listed have no permission here, as one from another phone: " +
                "their songs are looked at as any other's"
        )
        return idsSparedByUnlistedFolders(idToLocalPath, folders, held)
    }

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
        // A folder that could not be listed at all (an SD card not mounted) reads as empty, and
        // every song under it would otherwise look deleted here. Left alone instead, by the rule the
        // library scanner applies to its own folders, while this install holds its permission: a
        // folder from another phone, come with a backup, has none and is never listed (#278).
        val spared = sparedUnderUnlistedFolders(dbDownloads.associate { it.song.id to it.song.localPath })
        missingFiles.removeIf { it.song.id in spared }
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
        // A song the listing did not bring is looked at by its own file before it goes: a listing can
        // come back short, and this runs at every start (DownloadScanPlan, dvd 20 Nov 2026).
        val checked = localMgr.lookAgain(missingFiles.mapNotNull { s -> s.song.localPath?.let { s.song.id to it } }.toMap())
        missingFiles.removeIf { !DownloadScanPlan.goes(checked[it.song.id]) }
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
        // The map says it all now: the database's record is no longer shown (recordedUntilListed).
        // Here and not only after the first rescan in init: a scan asked for at the start
        // (scanDownloads) can take the lock first, and its own rescan is the first listing then.
        recordedUntilListed.listed()
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
        try {
            // Everything read first and the decision made from it, then written in one go
            // (DownloadScanPlan). It used to clear every song's download and record again only the
            // files it could list and give a path: a listing that came back short, or a file with no
            // path, cost every one of those songs its download, and the liked catch up fetched them
            // all again with a second copy of each in the folder (dvd, 20 Nov 2026: 397 songs).
            // Song files are known by the file the database has for each song (knownPaths), their
            // names holding no id.
            val dbDownloads = database.downloadedOrQueuedSongs().first()
            val availableFiles = localMgr.getAvailableFiles(false, knownPaths(dbDownloads))
            val recorded = dbDownloads.associate { it.song.id to it.song.localPath }
            // Each file's path on the phone; null for one on another app's storage, whose song is
            // recorded all the same, without one (it threw "Hello darkness my old friend" and lost
            // its download).
            val listed = availableFiles.mapValues { (_, uri) -> runCatching { fileFromUri(context, uri)?.absolutePath }.getOrNull() }
            // A folder that could not be listed at all reads as empty: its songs are left alone, while
            // this install holds its permission (#278).
            val spared = sparedUnderUnlistedFolders(recorded)
            // The finished downloads inside the app, as the listener writes them: failed, stopped
            // and queued ones used to be stored as the sentinels 0 and 1, which every downloaded
            // list and count took for downloads. See DownloadSql.
            val inside = mutableMapOf<String, LocalDateTime>()
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    completedDownloadTime(download.state, download.updateTimeMs)?.let { inside[download.request.id] = it }
                }
            }
            // A song the listing did not bring is looked at by its own file before it goes.
            val checked = localMgr.lookAgain(DownloadScanPlan.toLookAt(recorded, listed.keys, inside.keys, spared))
            val plan = DownloadScanPlan.of(recorded, listed, inside.keys, spared, checked)
            val timeNow = LocalDateTime.now()
            // transactionNow, not transaction: written before going on, or the map rebuilt from the
            // database at the end could read the songs before their writes. One transaction: a
            // write that fails leaves every song as it was, rather than half of them forgotten.
            database.transactionNow {
                plan.remove.forEach { removeDownloadSong(it) }
                plan.register.forEach { (id, path) ->
                    if (path != null) registerDownloadSong(id, timeNow, path) else updateDownloadStatus(id, timeNow)
                }
                inside.forEach { (id, completedAt) -> updateDownloadStatus(id, completedAt) }
            }
            Log.i(
                TAG,
                "Scan: ${availableFiles.size} files listed, ${plan.register.size} songs recorded anew, ${plan.remove.size} gone, " +
                    "${inside.size} inside the app, ${spared.size} under a folder not listed, " +
                    "${checked.count { it.value != FolderFileCheck.GONE }} kept by a look at their own file"
            )
        } finally {
            isProcessingDownloads.value = false
        }
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

        /** How often a download held for the song the player has open asks whether it is let go (waitWhileOpen). */
        private const val OPEN_SONG_POLL_MS = 1_000L

        /**
         * How long a download waits for the player to let go of the song it has open (waitWhileOpen):
         * about what is left of a song heard to its end. Past it the download takes what the player
         * plays, so a song paused or ended holds no download thread, no move and no foreground time.
         */
        private const val OPEN_SONG_WAIT_MS = 3 * 60_000L

        /**
         * How long Remove download waits for the song's removal to reach the map before it asks
         * whether the cover is still shown: a bulk removal of a few hundred songs in the download
         * folder deletes their files one at a time. Past it the song counts as still downloaded,
         * and the cover stays.
         */
        private const val COVER_WAIT_MS = 120_000L

        /** What the liked catch up remembers between syncs, in the app's own files: LikedCatchUpMemory. */
        private const val LIKED_CATCH_UP_FILE = "liked-catch-up.txt"

        /** How long a change of it waits before it is written, so that a removal of hundreds of songs is one write. */
        private const val CATCH_UP_SAVE_DELAY_MS = 1_000L

        /** Every message to the download service goes out through this: see DownloadServiceSends. */
        private val sends = DownloadServiceSends { Log.w("DownloadUtil", it) }

        /**
         * A resume of the downloads that Android refused is still owed. Asked of the class rather
         * than the instance, so that MainActivity can ask at every launch without building the
         * downloads (StartupSingletonsTest); when the answer is yes the instance is there already.
         */
        val resumeOwed: Boolean get() = sends.resumeOwed
    }


    init {
        Log.i(TAG, "DownloadUtil init")
        // A "Converting" notice an earlier run left up when Android ended the app: nothing converts at the start.
        ConvertNotice.clearStale(context)
        // ... and nor does a conversion's temporary file belong to anything: one left by an app that was ended mid-song goes.
        CoroutineScope(Dispatchers.IO).launch { runCatching { converter.sweep(olderThanMs = 0) } }
        // TODO: make sure db is update when download is queued
        CoroutineScope(dlCoroutine).launch {
            try {
                // What the database records, shown until the listing below has landed: before, the
                // screens showed every download as not downloaded for as long (recordedUntilListed).
                runCatching { recordedUntilListed.offer(DownloadShown.recorded(database.recordedDownloads().map { it.id to it.dateDownload })) }
                    .onFailure { Log.w(TAG, "Could not read the downloads the database records", it) }
                rescanDownloads()
            } finally {
                // Shown from the map alone from now on, whatever happened to the listing.
                recordedUntilListed.listed()
                // Open whatever happened: a player waiting on it gives up at once from now on.
                firstListing.countDown()
            }
            // The extra copies a name clash left in the folder: the card on Downloaded songs says so (DownloadCopiesCleanup).
            if (Unreleased.DOWNLOAD_COPIES_CLEANUP) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { copies.find() }.onFailure { Log.w(TAG, "Could not look for extra copies of downloads", it) }
                }
            }
            dropSongCacheCopiesOfDownloads()
            // Downloads made before their covers were kept as files get theirs now, one at a time;
            // with no network nothing comes and the next start tries again (DownloadCovers).
            keepMissingCovers()
        }
        // The phone's battery saver can turn Battery use's Saver on and off at any time
        // (PhoneBatterySaver), and Saver downloads on Wi-Fi only: the manager is told at once.
        // Straight to the manager, on the thread it was made on, rather than through the download
        // service, which an intent would start from the background.
        CoroutineScope(Dispatchers.Main).launch {
            PhoneBatterySaver.on.drop(1).collect {
                runCatching { downloadManager.requirements = downloadRequirements(wifiOnlyNow()) }
                    .onFailure { Log.w(TAG, "Could not apply the download requirement", it) }
            }
        }

        downloadManager.addListener(
            object : DownloadManager.Listener {
                /**
                 * Every download done for now: with a folder of the person's own set, the songs
                 * just finished go into it, the way Migrate moves earlier ones. A download is
                 * always made inside the app first, and only Migrate ever took songs out, so a
                 * folder set in Settings never got a new download (dvd on Discord, 26 Oct 2026).
                 * Songs downloaded before the folder was set stay where they are: Migrate's call.
                 */
                override fun onIdle(downloadManager: DownloadManager) {
                    if (finishedSinceMove.isEmpty()) return
                    val just = finishedSinceMove.toSet()
                    finishedSinceMove.clear()
                    if (context.dataStore.get(DownloadPathKey, "").isEmpty()) {
                        says.whenDone(just) { false }
                        return
                    }
                    notifyIfFolderCannotOpen()
                    CoroutineScope(Dispatchers.IO).launch {
                        migrateDownloads(quiet = true, only = just)
                        // Once the songs are where they stay (DownloadsSimpleLook): how many are in the folder.
                        // A song held while it plays is said once it has moved (playerHas).
                        says.whenDone(just.filterTo(mutableSetOf()) { !inPlay.isHeld(it) }) { localMgr.getFilePathIfExists(it) != null }
                    }
                }

                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    if (download.state == Download.STATE_COMPLETED) {
                        finishedSinceMove += download.request.id
                        keepCoverForOffline(download.request.id)
                        // The song is kept once: a copy in the song cache, from playing it before,
                        // goes once the download cache holds the song whole (KeptOnce).
                        dropSongCacheCopyOnceDownloaded(download.request.id)
                        // Said in the log: a download that landed left no line at all (landedLine).
                        Log.i(TAG, landedLine(download.request.id, download.contentLength, download.startTimeMs, download.updateTimeMs))
                    }
                    // For Report a problem's trail: that one failed, never which.
                    if (download.state == Download.STATE_FAILED) ReportTrail.thisRun.downloadFailed()
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

                    // What the sync's liked catch up remembers between syncs: a download the
                    // person removed or cancelled, one that failed, one that landed. Queued in the
                    // order the changes come, so a failed download's removal below is read as the
                    // app's, not the person's (LikedCatchUpMemory).
                    val changedAt = System.currentTimeMillis()
                    rememberForCatchUp { afterChange(download.request.id, download.state, changedAt) }
                    // A download of the setting's that failed or went is no longer the setting's: a
                    // later one by hand, or a file imported into the folder, must not go with the
                    // like (LikedDownloads, issue #64).
                    if (download.state == Download.STATE_FAILED || download.state == Download.STATE_REMOVING) {
                        forgetLikedDownload(download.request.id)
                        // Nor is it waited for to be said where it went (DownloadSays).
                        says.forget(download.request.id)
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

                    // A download media3 has given up on kept the bytes it had, in its index, for a
                    // retry nothing asks for: the song reads as not downloaded, so no Remove download
                    // is offered, and only Clear all downloads freed them. Removed now, as Remove
                    // download removes a finished one. Here, in the change itself, on the thread the
                    // manager lives on: a download of the song asked for again afterwards is queued
                    // after this removal, never taken by it. The run's count and the liked catch up
                    // keep the song as failed through the removal (DownloadRun, LikedCatchUp).
                    if (download.state == Download.STATE_FAILED) {
                        runCatching { downloadManager.removeDownload(download.request.id) }
                            .onFailure { Log.w(TAG, "Could not free what the failed download of ${download.request.id} had", it) }
                    }
                }
            }
        )
    }
}

fun stateToLocalDateTime(download: Download): LocalDateTime =
    stateToLocalDateTime(download.state, download.updateTimeMs)

/** Whether a date from the downloads map is a download that has landed: a real date, not the mark of one on its way. */
fun hasLanded(date: LocalDateTime?): Boolean = date != null && date > DownloadUtil.STATE_DOWNLOADING

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

/** A copy fetched under Auto on metered data: Auto took a middle stream there (AudioPick). */
const val AUTO_METERED = "AUTO_METERED"

/** A copy fetched under Auto on a connection that is not metered: the best stream, as High takes. */
const val AUTO_UNMETERED = "AUTO_UNMETERED"

/**
 * Below this a copy recorded as plain "AUTO", before the network was recorded too, is taken for one
 * Auto fetched on mobile data: about 55 kbps (Opus 249, or AAC 139) in 0.12, about 75 (Opus 250)
 * since #88, where the best streams are 128 kbps and up.
 */
const val OLD_AUTO_SMALL_BPS = 100_000

/**
 * The tier a copy is recorded at: the setting, and for Auto the kind of connection it was fetched
 * on, since Auto takes a middle stream on metered data and the best one elsewhere (AudioPick).
 * Plain "AUTO" was recorded either way until 9 Nov 2026, so a song first played on mobile data
 * stayed small on Wi-Fi for as long as it was cached.
 */
fun cacheTier(quality: AudioQuality, metered: Boolean): String = when {
    quality != AudioQuality.AUTO -> quality.name
    metered -> AUTO_METERED
    else -> AUTO_UNMETERED
}

/**
 * Where a recorded tier sits, for deciding what counts as an upgrade: Low, then Auto on metered
 * data, then Auto elsewhere and High (the same stream), then Highest. The one rank table: the
 * download resolver and MusicService.shouldUpgradeCached both go through it, via isStaleQualityTier.
 * Null for a tier that is not one of these.
 *
 * A plain "AUTO" from before the network was recorded ranks as Auto on metered data when its
 * stream was a small one ([storedBitrate] under [OLD_AUTO_SMALL_BPS]), and as Auto elsewhere
 * otherwise or when its bitrate is not known. Fetched again, it is recorded with its network, so a
 * song whose best stream is a small one is fetched again once, not at every play.
 */
private fun recordedRank(tier: String, storedBitrate: Int?): Int? = when (tier) {
    AudioQuality.LOW.name -> 0
    AUTO_METERED -> 1
    AudioQuality.AUTO.name -> if (storedBitrate != null && storedBitrate in 1 until OLD_AUTO_SMALL_BPS) 1 else 2
    AUTO_UNMETERED, AudioQuality.HIGH.name -> 2
    AudioQuality.MAX.name -> 3
    else -> null
}

/**
 * Whether a copy recorded at [storedTier] (with its stream's [storedBitrate]) ranks below what
 * [current], the quality setting now in force, takes on this connection ([metered]). A plain
 * function of values, with no cache or database in it, so the rank rule is tested without either.
 *
 * Null, or a tier that is not one of the recorded ones, means the copy predates this field, or came
 * from before quality tiers were recorded at all; treated as no upgrade, for playback and
 * downloads alike, rather than re-fetching someone's whole cache the first time they open the app
 * after an update.
 */
fun isStaleQualityTier(storedTier: String?, current: AudioQuality, metered: Boolean = false, storedBitrate: Int? = null): Boolean {
    val stored = storedTier?.let { recordedRank(it, storedBitrate) } ?: return false
    val now = recordedRank(cacheTier(current, metered), null) ?: return false
    return now > stored
}

/**
 * The quality a download asks for (Unreleased.DOWNLOAD_QUALITY): the one the Download quality
 * [setting] names, or [playback], the Audio quality setting, for Same as playback, which is what
 * every download asked for before the setting. Without the flag ([offered]) always [playback],
 * whatever a restored backup has stored. A plain function of values, so it is tested without a
 * download.
 *
 * The resolver reads it once for each song and judges the song cache's copy by it
 * ([isStaleQualityTier]), picks the stream by it and records the copy at it ([cacheTier]). So a
 * download at Highest does not take a copy cached at Low, and one at Low takes a better copy that
 * is cached rather than fetch a worse one. Never Auto unless Same as playback is on Auto.
 */
fun qualityForDownload(setting: DownloadQuality, playback: AudioQuality, offered: Boolean = Unreleased.DOWNLOAD_QUALITY): AudioQuality =
    if (offered) setting.tier ?: playback else playback

/**
 * The ids, out of [idToLocalPath], whose recorded folder was not among the ones the last scan
 * could list: not missing, just not looked at, so a rescan or a full scan must leave them alone
 * rather than clearing their download registration. A plain function of two maps/lists, so it is
 * tested without a database, a DocumentFile or a Uri.
 *
 * An id with no recorded local path (an internal, non-folder download) is never covered: there is
 * nothing to compare it against.
 */
fun idsCoveredByUnlistedRoot(idToLocalPath: Map<String, String?>, unlistedRoots: List<String?>): Set<String> {
    if (unlistedRoots.isEmpty()) return emptySet()
    return idToLocalPath.filterValues { it != null && ScanMerge.coveredByUnlistedRoot(it, unlistedRoots) }.keys
}

/**
 * The ids, out of [idToLocalPath], a scan leaves alone because their folder could not be listed
 * (idsCoveredByUnlistedRoot), counting only the folders this install holds a permission for.
 * [unlisted]: each folder the last full listing could not read, its address to its path on the
 * phone (null when not known, which covers every song); [held]: the addresses of this install's
 * persisted permissions (contentResolver.persistedUriPermissions), or null when they could not be
 * read, which counts every folder, as before. A plain function of maps and lists, so it is tested
 * without a phone.
 *
 * A folder picked on this phone keeps its permission while its SD card is out, and its songs wait
 * for it. One picked on another phone and come with a backup has none here and can never be
 * listed: spared, its songs stayed downloaded for ever, their files on the old phone's card, and
 * failed to play offline (#278). They are looked at as any other's now, and read as not
 * downloaded; no file is touched. A song kept inside the app has no path and is never spared here:
 * media3's index keeps it, as before.
 */
fun idsSparedByUnlistedFolders(idToLocalPath: Map<String, String?>, unlisted: Map<String, String?>, held: List<String>?): Set<String> {
    val sparing = unlisted.filterKeys { address -> held == null || held.any { sameFolder(it, address) } }
    return idsCoveredByUnlistedRoot(idToLocalPath, sparing.values.toList())
}

/**
 * Whether the addresses [a] and [b] are the same folder: the same text, or the same tree of the
 * same provider. A permission is held on a tree's address, "content://<provider>/tree/<id>"; a
 * folder that opened and could not be listed is known by its top document's,
 * ".../tree/<id>/document/<id>"; and an id can be written "%3A" or ":".
 */
fun sameFolder(a: String, b: String): Boolean {
    if (a == b) return true
    val treeA = runCatching { FolderNesting.treeDocumentId(a) }.getOrNull() ?: return false
    val treeB = runCatching { FolderNesting.treeDocumentId(b) }.getOrNull() ?: return false
    fun provider(address: String) = address.substringAfter("://").substringBefore('/')
    return treeA == treeB && provider(a) == provider(b)
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

/**
 * Whether the covers kept for [id]'s download can go: once its removal has reached [downloads], or
 * [waitMs] has passed, none of the songs that show the same cover ([sharing], read then, [id] among
 * them) is downloaded or on its way. Asked after the removal, so that a whole album removed at once,
 * every song asking before any removal has landed, leaves the cover to its last song, which takes
 * it. A removal that never lands (a file in an import folder, a service Android refused) and songs
 * that cannot be read keep the cover. Plain values in, so it is tested without a database.
 */
internal suspend fun coverUnusedOnceRemoved(
    id: String,
    downloads: StateFlow<Map<String, *>>,
    waitMs: Long,
    sharing: suspend () -> Collection<String>?,
): Boolean {
    withTimeoutOrNull(waitMs) { downloads.first { id !in it } }
    val songs = runCatching { sharing() }.getOrNull() ?: return false
    val now = downloads.value
    return songs.none { it in now }
}

/**
 * What [find] answers, asked again while the download folders have not been listed since the start
 * ([listed] not open yet): every [stepMs], and once more when they have been, for at most [waitMs].
 * Asked again on the way as well as at the end, because a full scan at start can fill the list
 * before the rescan that opens [listed] gets its turn. None of it once they have been listed. Plain
 * values in, so it is tested without a folder.
 */
internal fun <T : Any> afterFirstListing(
    listed: java.util.concurrent.CountDownLatch,
    waitMs: Long,
    stepMs: Long = 250L,
    find: () -> T?,
): T? {
    var answer = find()
    val until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(waitMs)
    while (answer == null && listed.count > 0L) {
        val left = until - System.nanoTime()
        if (left <= 0L) break
        try {
            listed.await(minOf(left, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(stepMs)), java.util.concurrent.TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
            // The player cancelled the load: it is told, and nothing more is waited for.
            Thread.currentThread().interrupt()
            break
        }
        answer = find()
    }
    return answer
}

/**
 * Whether an address remembered for a download, kept until [expiresAtMs], can still be read: a
 * sabr: one only while its start is kept. One SABR fault, in the download's read or the player's
 * of the same song, forgets every start of the song (SabrStarts.forget), and the remembered
 * address then failed every retry at once, for as long as it was remembered. A new walk gives a
 * new start.
 */
internal fun stillUsable(address: String, expiresAtMs: Long, nowMs: Long = System.currentTimeMillis(), starts: SabrStarts = SabrStarts): Boolean =
    expiresAtMs > nowMs && (!address.startsWith(SabrStarts.SCHEME + "://") || starts.forAddress(address, nowMs) != null)

/**
 * The address a download reads: a direct one asks for the whole file as one range, which YouTube
 * throttles less; a sabr: one is the exact text its start was kept under (SabrStarts) and gets
 * nothing added, or the reader would not find it and the download would fail, as one over SABR's
 * last try did before 16 Oct 2026.
 */
internal fun downloadAddress(streamUrl: String, contentLength: Long?): String =
    if (streamUrl.startsWith(SabrStarts.SCHEME + "://")) streamUrl
    else "$streamUrl&range=0-${contentLength ?: 10000000}"

/** The length [cache] recorded for [mediaId]'s stream, C.LENGTH_UNSET when it has none (media3's content metadata). */
internal fun recordedLength(cache: androidx.media3.datasource.cache.Cache, mediaId: String): Long =
    runCatching { androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(mediaId)) }
        .getOrDefault(androidx.media3.common.C.LENGTH_UNSET.toLong())

/**
 * The log's line for a download whose bytes have all landed: how much, and how long after it was asked
 * for, by media3's own times. A download that landed left no line at all, and a report of slow downloads
 * (a tester, 12 Nov 2026) could not say whether the bytes were slow or the conversion that follows them
 * (SongConverter says its own time). Plain values in, so it is tested without a download.
 */
internal fun landedLine(id: String, bytes: Long, askedAtMs: Long, landedAtMs: Long): String {
    val size = if (bytes > 0) String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1e6) else "its size not known"
    val seconds = String.format(java.util.Locale.ROOT, "%.1f", (landedAtMs - askedAtMs).coerceAtLeast(0L) / 1000.0)
    return "Download of $id landed: $size, $seconds s after it was asked for"
}

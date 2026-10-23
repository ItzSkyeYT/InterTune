/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.ContextChipKey
import com.dd3boh.outertune.db.entities.RecommendationExclusion
import com.dd3boh.outertune.constants.RestSongsISkipKey
import com.dd3boh.outertune.constants.RelatedRefreshCountKey
import com.dd3boh.outertune.constants.RelatedRefreshDayKey
import com.dd3boh.outertune.engine.DatabaseBackfillIo
import com.dd3boh.outertune.engine.LegacyBackfill
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioDecoderKey
import com.dd3boh.outertune.constants.AudioGaplessOffloadKey
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.AudioOffloadKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.cast.CastFormats
import com.dd3boh.outertune.cast.CastNetwork
import com.dd3boh.outertune.cast.CastServer
import com.dd3boh.outertune.cast.ReceiverLink
import com.dd3boh.outertune.cast.ReceiverStatus
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.MAX_PLAYER_CONSECUTIVE_ERR
import com.dd3boh.outertune.constants.RELATED_RETRY_COOLDOWN_MS
import com.dd3boh.outertune.constants.MaxQueuesKey
import com.dd3boh.outertune.constants.MediaControlButtonsKey
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleLibrary
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleLike
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleShuffle
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleStartRadio
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.LastFmCaughtUpAtKey
import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.constants.PauseRemoteListenHistoryKey
import com.dd3boh.outertune.constants.HeadTracking3dKey
import com.dd3boh.outertune.constants.HeadTrackingCalibrateKey
import com.dd3boh.outertune.constants.HeadTrackingDriftKey
import com.dd3boh.outertune.constants.HeadTrackingKey
import com.dd3boh.outertune.constants.HeadTrackingLeadKey
import com.dd3boh.outertune.constants.HeadTrackingResponse
import com.dd3boh.outertune.constants.HeadTrackingResponseKey
import com.dd3boh.outertune.constants.StageWidthKey
import com.dd3boh.outertune.constants.SpatialAmbienceKey
import com.dd3boh.outertune.constants.SpatialBassDirectKey
import com.dd3boh.outertune.constants.SpatialStrengthKey
import com.dd3boh.outertune.constants.SpatialRoomKey
import com.dd3boh.outertune.constants.SpatialAudioKey
import com.dd3boh.outertune.constants.SpatialAudioMode
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.engine.EngineLoader
import com.dd3boh.outertune.engine.TagFit
import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.constants.AdaptiveQueueModeKey
import com.dd3boh.outertune.constants.EqualizerEnabledKey
import com.dd3boh.outertune.constants.EqualizerGainsKey
import com.dd3boh.outertune.constants.AdaptiveQueueMode
import com.dd3boh.outertune.constants.isOn
import com.dd3boh.outertune.constants.PlaybackAuthModeKey
import com.dd3boh.outertune.constants.OwnVisionosRefusedKey
import com.dd3boh.outertune.constants.VisionosRefusedKey
import com.dd3boh.outertune.constants.WebClientFirstKey
import com.dd3boh.outertune.constants.SabrOnlyKey
import com.dd3boh.outertune.constants.EveryDirectRefusedKey
import com.dd3boh.outertune.constants.PlaybackAuthMode
import com.dd3boh.outertune.constants.ResumeOnBluetoothKey
import com.dd3boh.outertune.constants.ResumePlaybackOnLaunchKey
import com.dd3boh.outertune.constants.PlayerVolumeKey
import com.dd3boh.outertune.constants.ProximityVolumeKey
import com.dd3boh.outertune.constants.RepeatModeKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SleepScheduleKey
import com.dd3boh.outertune.constants.SleepScheduleLastNightKey
import com.dd3boh.outertune.constants.SleepScheduleLengthKey
import com.dd3boh.outertune.constants.SleepScheduleStartKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeDurationKey
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import com.dd3boh.outertune.constants.ShareAudioFocusKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.TransitionFadeKey
import com.dd3boh.outertune.constants.TransitionFadeSecondsKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.SongVersionMap
import com.dd3boh.outertune.db.entities.ListenSignal
import com.dd3boh.outertune.constants.SignalKind
import com.dd3boh.outertune.db.entities.Listen
import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.extensions.SilentHandler
import com.dd3boh.outertune.extensions.collect
import com.dd3boh.outertune.extensions.collectLatest
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.findNextMediaItemById
import com.dd3boh.outertune.extensions.isUserLoggedIn
import com.dd3boh.outertune.extensions.joinWithin
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.setOffloadEnabled
import com.dd3boh.outertune.lyrics.LyricsHelper
import com.dd3boh.outertune.models.ContentFilter
import com.dd3boh.outertune.models.HybridCacheDataSinkFactory
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.sabr.SabrClock
import com.dd3boh.outertune.playback.sabr.SabrOrHttp
import com.dd3boh.outertune.playback.sabr.SabrOver
import com.dd3boh.outertune.playback.ListenReporting.AddressFrom
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.utils.SessionBitmapLoader
import com.dd3boh.outertune.utils.ErrorText
import com.dd3boh.outertune.utils.LoudnessRepair
import com.dd3boh.outertune.utils.NetworkConnectivityObserver
import com.dd3boh.outertune.utils.Scrobbler
import com.dd3boh.outertune.utils.LastFmSimilar
import com.dd3boh.outertune.utils.similarSourceOf
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.FailureMemo
import com.dd3boh.outertune.utils.Throttle
import com.dd3boh.outertune.utils.codecsOrEmpty
import com.dd3boh.outertune.utils.contentLengthOrZero
import com.dd3boh.outertune.utils.SongVersions
import com.dd3boh.outertune.utils.RemoteRoutes
import com.dd3boh.outertune.utils.Warmup
import com.zionhuang.innertube.NewPipeUtils
import com.dd3boh.outertune.utils.RouteTimes
import com.dd3boh.outertune.utils.StepLog
import com.dd3boh.outertune.utils.StepTimedOut
import com.dd3boh.outertune.utils.StreamFamily
import com.dd3boh.outertune.utils.withinResolveTime
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.playerCoroutine
import com.dd3boh.outertune.utils.coilCoroutine
import com.dd3boh.outertune.utils.reportException
import com.google.common.util.concurrent.MoreExecutors
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import com.zionhuang.innertube.models.response.PlayerResponse
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.client.plugins.ResponseException
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import com.dd3boh.outertune.widget.WidgetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.plus
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
/** Silence longer than this starts a new listening session. */
private const val SESSION_GAP_MS = 30L * 60 * 1000
/** How often an open listen row records how far it got, so a death loses at most this much. */
private const val CHECKPOINT_MS = 60_000L

/**
 * Whether some MusicService instance in this process has already closed the listens an earlier
 * process left open. A top-level field, not one on the class: the gate has to outlive a single
 * instance (a newer one can replace one still tearing down), so it is shared by every instance
 * this process ever creates, for as long as the process lives.
 */
private val orphanedListensClosedThisProcess = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * How long teardown waits for the queue to be saved. The save took 3 to 134 ms over 27 teardowns
 * in the logs kept from the phone and the emulator, so this is far outside a save that works, and
 * it is under the five seconds Android gives an app to take a key press or a tap before it says
 * the app is not responding.
 */
private const val QUEUE_SAVE_WAIT_MS = 4_000L

/**
 * How long the radios' memory waits after a change before it is written (AdaptiveMemory): long
 * enough that a burst of skips or a drag writes once, short enough that a force close a moment
 * later still finds the song before it.
 */
private const val ADAPTIVE_MEMORY_WAIT_MS = 2_000L

/** How long a pass waits after the last ask: a burst of presses makes one pass. */
private const val PASS_WAIT_MS = 1_000L

/** How long the adaptive queue keeps the taste it read, as Home keeps the engine's input. */
private const val TASTE_TTL_MS = 5L * 60 * 1000

/** At most one radio page this often in a run, for the adaptive queue's picks: about a song, never faster than songs play. */
private const val PAGE_EVERY_MS = 3L * 60 * 1000

/** A related list older than this is fetched again, within the daily budget. */
private const val RELATED_STALE_MS = 90L * 24 * 60 * 60 * 1000
private const val RELATED_REFRESH_PER_DAY = 10

@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    val TAG = MusicService::class.simpleName.toString()

    @Inject
    lateinit var database: MusicDatabase

    /**
     * Lives exactly as long as this instance, and onDestroy cancels it. Every settings observer
     * onCreate starts runs here, so while nothing cancelled it, each destroyed instance stayed
     * reachable and went on answering setting changes meant for the one after it.
     *
     * A SupervisorJob, because three jobs here swallow their errors with SilentHandler, and under a
     * plain Job the one that failed still cancelled the whole scope: a radio top-up with nothing to
     * seed from took every observer and the sleep timer with it, without a word.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Outlives the service on purpose, for what has to land after onDestroy: telling the widget it
     * has stopped, settings writes still on their way, and the last song's scrobble, which media3
     * only reports once the player has been released.
     */
    private val lastingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Never cancelled either: the last song's listen row is written here, from that same report. */
    private val offloadScope = CoroutineScope(playerCoroutine)

    // Critical player components
    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var loudnessRepair: LoudnessRepair

    /** Kept so onDestroy only clears the provider if it is still the one this instance installed. */
    private var installedNowPlayingProvider: (() -> String?)? = null

    private val binder = MusicBinder()
    private lateinit var connectivityManager: ConnectivityManager

    private val castLock = Any()

    /** The cast server while a casting session has one. Only ever set with Unreleased.CASTING on. */
    private var castServer: CastServer? = null
    private var castNetworkWatch: ConnectivityManager.NetworkCallback? = null

    /** Casting driven from adb, for tests on the emulator. Only with Unreleased.CASTING on. */
    private var castProbe: CastProbe? = null

    /** Choosing a receiver, handing playback to it and back. Only with Unreleased.CASTING on. */
    var castSession: CastSession? = null
        private set

    /**
     * True while a receiver plays. This service then listens to the [RemotePlayer] rather than to
     * [exoPlayer], so the queue, the notification, the widget, radio and the error handling follow
     * the receiver, and what only makes sense for sound coming out of the phone is left alone: the
     * listening history, audio effects, head tracking and proximity volume. Main thread.
     */
    var isCasting = false
        private set

    val qbInit = MutableStateFlow(false)
    var queueBoard = QueueBoard(this, maxQueues = 1)
    var queuePlaylistId: String? = null

    /** Set at the top of onDestroy; see the checks in playQueue's resolve coroutine below. */
    @Volatile private var destroyed = false

    /**
     * The save deInitQueue launched instead of waiting for, when it was reached from a swipe of
     * the mini player rather than teardown. initQueue joins this before it re-reads the saved
     * queues, or it would read the rows this save is still in the middle of writing. Written on
     * Main (deInitQueue) and read inside initQueue, which callers reach from IO (PlayerSettings,
     * a local scan) as well as Main: @Volatile so a write on one thread is visible to a read on
     * another.
     */
    @Volatile private var pendingQueueSave: kotlinx.coroutines.Job? = null

    /**
     * Held for the whole of initQueue. Two callers must never build boards at once, because each
     * QueueBoard clears the shared masterQueues it is handed: a second build would drop a queue
     * the first caller has just added and is about to set current. A caller that only needs a
     * board waits here for one in flight instead of repeating it.
     */
    private val initQueueMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Completed by the very first initQueue() of this instance, and unlike qbInit, never reset by
     * deInitQueue: a session request only needs to know that the saved queues have been read at
     * least once, not whether the board is initialised right now.
     */
    val queuesLoadedOnce = kotlinx.coroutines.CompletableDeferred<Unit>()

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    /**
     * The player everything talks to. With Unreleased.CASTING off it is [exoPlayer] itself, and
     * playback is exactly what it was before casting existed. With it on it is a [RoutedPlayer]
     * around [exoPlayer], which a cast sends to a [RemotePlayer] instead.
     */
    lateinit var player: Player

    /**
     * The phone's own player, for what only it has: the audio session and its effects, the output
     * format, audio focus, offload, skip silence and the volume the gain stages set. Those stay
     * with the phone's sound while casting, and must never reach a receiver.
     */
    lateinit var exoPlayer: ExoPlayer
    private var routedPlayer: RoutedPlayer? = null

    /**
     * The player this service and the queue board drive and read themselves: [exoPlayer], or the
     * remote player while casting. Never the routed [player]. It learns what happened inside the
     * player it forwards to only once that player has called all its listeners, and most of this
     * service runs inside those very calls, where the routed player would still describe the song
     * before: the queue position saved on a song change would be the previous song's. With
     * Unreleased.CASTING off this is [player] itself.
     */
    val activePlayer: Player get() = routedPlayer?.target ?: exoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    /** The session as the system knows it: what the car is handed to draw its own player from. */
    val platformToken: android.media.session.MediaSession.Token get() = mediaSession.platformToken

    /** Whether a car is showing the player: its row of buttons is then laid out for a driver. */
    private var inCar = false
    private val carType by lazy { androidx.car.app.connection.CarConnection(this).type }
    private val carWatch = androidx.lifecycle.Observer<Int> { type ->
        val now = type != androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        if (now != inCar) {
            inCar = now
            if (::mediaSession.isInitialized) updateNotification()
        }
    }

    // Player components
    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var scrobbler: Scrobbler

    @Inject
    lateinit var lastFmSimilar: LastFmSimilar

    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private var networkRetryJob: Job? = null
    private var networkRetryAttempt = 0

    /**
     * Settings the transition path asks about, held rather than fetched.
     *
     * `dataStore.get` is `runBlocking(Dispatchers.IO) { data.first() }`, so every one of these was
     * parking the player's thread for a dispatch, a flow collection and a dispatch back. Three of
     * them ran on the application looper on every single song change, which is what made spamming
     * next or previous feel heavy: each press paid for all three again, one after another, and the
     * presses queued up behind each other. They are all settings that change about once a year, so
     * they are read once at start and kept current by the observers in onCreate.
     */
    /**
     * The app stopped the music because a song failed, rather than the listener stopping it.
     *
     * The two look identical to the player, both being playWhenReady false, and they are not the
     * same thing at all: someone who paused wants it paused, and someone whose song would not load
     * wants the next one to play the moment they reach for the skip button.
     */
    @Volatile private var stoppedByError = false

    /**
     * The song the music stopped on, while [stoppedByError]. Asked for again then, by Retry on
     * the player's error, Play in the app or on the notification, it is the listener asking, and
     * YouTube is asked even for a song set aside as gone (StandIns). Skipping onto a song, or a
     * radio reaching it, clears the stop first and leaves what was set aside as it is.
     */
    @Volatile private var stoppedOn: String? = null

    /** Where each streamed song is fetched from, until the player fails on it: see [StreamAddresses]. */
    private val streamAddresses = StreamAddresses()

    // Keeping the music going on its own: see Recovery, SongRoutes and StallWatch.
    private val songRoutes = SongRoutes()
    private val loadFailures = Recovery.LoadFailures()
    private val recoveryBudget = Recovery.Budget()

    /** When the network in use last changed, elapsed time: a refusal just after is the new address's. */
    @Volatile private var lastNetworkChangeAt = 0L

    /** Route changes made while the buffer still plays: see [changeRoute]. */
    private val routeChanges = Recovery.Budget()

    /**
     * An address played before its check answered, and refused when the answer came: the
     * player's next open of the song asks afresh, and the route is asked last for it. Only when
     * that address is still the one kept: the song may have moved to a route that plays. See
     * StreamCheck.playsUnchecked.
     */
    private val lateRefusal: (String, String, String) -> Unit = { id, client, address ->
        if (streamAddresses.refused(id, address)) songRoutes.mark(id, client, SystemClock.elapsedRealtime())
        castResolver?.forgetRefused(id, address)
    }

    /** The cast server's resolver, while one was made: a late refusal reaches its addresses too. */
    @Volatile private var castResolver: SongResolver? = null

    /** The song the stall watch gave up, and when: the player's own error on it afterwards is the same fault, already told. */
    private var stallGaveUp: Pair<String, Long>? = null
    private val stallWatch = StallWatch()
    private val stallHandler by lazy { Handler(Looper.getMainLooper()) }
    private var stallWatching = false
    private val stallTick = object : Runnable {
        override fun run() {
            if (!stallWatching) return
            watchStall()
            if (stallWatching) stallHandler.postDelayed(this, 1_000L)
        }
    }
    private var quietRetryJob: Job? = null

    /** Songs whose "cached part stale" has had its one quiet retry. */
    private val staleCopyRetried = HashSet<String>()

    /** Throws out a cached copy of a song that cannot be read: see [BadCachedCopy]. */
    private val badCachedCopy = BadCachedCopy(
        isCached = { id -> runCatching { playerCache.getCachedSpans(id).isNotEmpty() }.getOrDefault(false) },
        online = { isNetworkConnected.value },
        drop = { id ->
            Log.w(TAG, "PLAYING: the cached copy of $id could not be read, it is thrown out and the song read whole")
            runCatching { playerCache.removeResource(id) }.onFailure { Log.w(TAG, "Could not drop the cached copy of $id", it) }
        },
    )

    /**
     * Asks YouTube for a song's stream, and for the same recording under another id when the
     * song's own is gone: see [StandIns]. The song as the queue has it, or as its own row and its
     * artists say when it is not in the queue; where the queue has it without a length the row's
     * is taken, since without one nothing can pass as the same recording. A database that does
     * not answer leaves nothing to search for, and the song fails as it would have.
     */
    private val standIns by lazy {
        StandIns(
            resolve = { id ->
                YTPlayerUtils.playerResponseForPlayback(id, audioQuality = audioQualityNow(), connectivityManager = connectivityManager)
            },
            wanted = { id ->
                queueBoard.getCurrentQueue()?.findSong(id)?.let { queued ->
                    StandIn.Wanted(
                        queued.title,
                        queued.artists.map { it.name },
                        queued.duration.takeIf { it > 0 } ?: database.readOrNull { songRow(id) }?.duration?.takeIf { it > 0 },
                        album = queued.album?.title,
                    )
                } ?: database.readOrNull { songRow(id) }?.let { row ->
                    StandIn.Wanted(
                        row.title,
                        database.readOrNull { artistNamesOf(id) }.orEmpty(),
                        row.duration.takeIf { it > 0 },
                    )
                }
            },
            search = { query ->
                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>()
            },
            // Set aside as gone from YouTube, as the Exclusions page lists it: read each time, so
            // that a Lift there counts at the next tap. A database that does not answer sets
            // nothing aside, and the song is asked for as any other.
            setAside = { id ->
                val now = System.currentTimeMillis()
                database.readOrNull { exclusion(ExclusionsViewModel.KIND_SONG, id) }
                    ?.let { it.reason == ExclusionsViewModel.REASON_GONE && (it.expiresAt == null || it.expiresAt > now) } == true
            },
        ).also {
            it.onGone = goneSongs::gone
            it.onPlays = goneSongs::plays
            it.onDoubted = goneSongs::doubted
        }
    }

    /**
     * The songs no copy of which plays, for the recommendation rows to leave out: see [GoneSongs].
     * Only a song the library has a row for, since the Exclusions page names it by its title, and
     * only where nothing in force already keeps it out: a ban or a rest stays as it is, and a row
     * that has lapsed, this kind or another, gives way. Every write is caught: an exception on
     * the database's own threads ends the app.
     */
    private val goneSongs = GoneSongs(
        load = { at -> database.readOrNull { goneSongIds(at) } },
        mark = { id, at ->
            database.query {
                runCatching {
                    val title = songRow(id)?.title
                    val there = exclusion(ExclusionsViewModel.KIND_SONG, id)
                    val inForce = there != null && (there.expiresAt == null || there.expiresAt > at)
                    if (title != null && !inForce) {
                        insertExclusion(
                            RecommendationExclusion(
                                kind = ExclusionsViewModel.KIND_SONG, targetId = id, label = title,
                                reason = ExclusionsViewModel.REASON_GONE, createdAt = at, expiresAt = at + ExclusionsViewModel.GONE_MS,
                            )
                        )
                    }
                }.onFailure { Log.w(TAG, "Could not note a song as gone from YouTube", it) }
            }
        },
        lift = { ids ->
            database.query {
                runCatching { deleteGone(ids) }.onFailure { Log.w(TAG, "Could not take a song off the gone list", it) }
            }
        },
    )

    @Volatile private var listenHistoryPaused = false
    /** Similar songs come from Both or Last.fm only, so a song played is asked about on Last.fm too. */
    @Volatile private var asksLastFm = false
    @Volatile private var autoLoadMore = true
    @Volatile private var adaptiveQueueMode = AdaptiveQueueMode.AUTOPLAY_ONLY

    /**
     * True while the queue sheet is on screen, set by the UI.
     *
     * Nothing moves while somebody is looking at it. A list that rearranges under the finger is
     * worse than a list with a few wrong songs further down, and the whole feature is only
     * acceptable because it happens out of sight.
     *
     * The pass that was turned away while it was open is asked for again the moment it closes,
     * which includes the screen going off over it (computeQueueSheetOpen): a sheet left open
     * when the phone was locked used to keep every pass away for the rest of the evening.
     */
    @Volatile var queueSheetOpen = false
        set(value) {
            val closed = field && !value
            field = value
            if (closed) scope.launch { askAgainAfterSheet() }
        }

    /**
     * Whether the queue's far end is being chosen rather than fixed, for the queue view to say so.
     *
     * The thing this fixes is not a bug in the planning, which has been working: it dropped
     * eighteen of forty six upcoming songs in one pass and the list still looked like forty six
     * songs anyone could scroll. A queue that quietly rewrites itself while presenting as a
     * fixed list is worse than one that does not rewrite itself at all, because the listener is
     * reading something that is not true.
     */
    val adaptiveTailActive = MutableStateFlow(false)

    /** Bumped whenever the tail is replanned, so the queue view can redraw its summary. */
    val hiddenTailChanged = MutableStateFlow(0)

    /** What has played in the current queue, in the order it was heard. See [AdaptiveQueue.Heard]. */
    private val heard = AdaptiveQueue.Heard()

    /** Whether this process has read what the last one saved of the radios (AdaptiveMemory). */
    private var adaptiveMemoryRead = false

    /** The write of AdaptiveMemory waiting out its two seconds, so a burst of skips writes once. Main thread. */
    private var adaptiveMemoryWrite: Job? = null

    /** One write of the file at a time, in the order they were asked for. */
    private val adaptiveMemoryDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * The songs that have been current in the current queue, for the queue view: everything up to
     * the furthest of them stays on show, however far back the listener went.
     */
    val reachedInQueue = MutableStateFlow<Set<String>>(emptySet())

    /** Whether the song that just started was reached by going back. Set in noteTransition. */
    private var transitionWentBack = false

    /**
     * Whether the song that just started was chosen, a tap in the queue sheet: the song left for it
     * was not skipped, it was set aside. Set in noteTransition.
     */
    private var transitionChosen = false

    /**
     * How far into it each play had really got when it was left: where it started plus what was
     * heard, so a song scrubbed to its end and then left is not read as heard (AdaptiveQueue.leave).
     */
    private val lastKnownHeard = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Bumped when songs go into a queue while the player holds nothing, which is how Play next and
     * Add to queue work before play after a restart. The artwork strip and the queue sheet read
     * that queue from the queue board then, and nothing the player reports tells them it changed.
     */
    val unloadedQueueChanged = MutableStateFlow(0)
    @Volatile private var contextChip = 0
    @Volatile var persistentQueue = true
        private set
    /** The two buttons picked for the phone's media controls. Read and written on the main thread. */
    private var mediaControlButtons = MediaControlButtons.DEFAULT
    private val isNetworkConnected = MutableStateFlow(true)

    lateinit var sleepTimer: SleepTimer

    /** Whether music is playing, which is all the sleep timer on a schedule waits on. */
    private val playingNow = MutableStateFlow(false)

    /** Counts the song changes: the schedule looks again at each one, see lookAtSleepSchedule. */
    private val songChanges = MutableStateFlow(0)

    /** The day the schedule last served (ScheduledSleep.Look.night). Read and written on the main thread. */
    private var sleepScheduleServed: LocalDate? = null

    /** Fade between tracks. Off unless asked for; one more factor in the volume combine. */
    private lateinit var transitionFade: TransitionFade

    private val sleepTimerNotification by lazy { SleepTimerNotification(this) }

    /**
     * Measures the music for the player's living background, on its way into the audio output. It
     * looks at nothing unless that background is on screen (LevelTap.wanted).
     */
    val levelTap = LevelTap()

    /**
     * Applies gain on the PCM stream, which is the only way to exceed unity: player.volume is
     * clamped to [0,1]. Shared by both sink builders below so there is one instance whichever
     * decoder path is in use.
     */
    val gainProcessor = GainAudioProcessor()

    /** The equalizer in the app: untouched audio unless Unreleased.EQUALIZER is on and its curve is not flat. */
    val equalizerProcessor = EqualizerAudioProcessor()

    // Player vars
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)

    // Off the main thread as before, but ended with the service. On offloadScope a Lazily shared
    // flow never stopped, so every destroyed instance stayed subscribed to the song table through
    // the database, which holds on to its observers, and ran its query again on every write.
    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(scope + playerCoroutine, SharingStarted.Lazily, null)

    /**
     * Format row for the current song, paired with whether that song is a local file.
     *
     * Paired inside the flatMapLatest rather than combined downstream on purpose. This flow is
     * derived from currentMediaMetadata, so adding the metadata as a separate combine input would
     * emit the new song's flag against the previous song's format for one round, and apply the
     * wrong gain for the opening moments of every single track change.
     */
    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id).map { format ->
            format to (mediaMetadata?.isLocal == true)
        }
    }

    private val normalizeFactor = MutableStateFlow(1f)

    private val audioDecoder = dataStore.get(AudioDecoderKey, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

    /**
     * 32 bit float through the processor chain. Off for now, whatever the stored setting says.
     *
     * media3 1.8.0 cannot do it. With float output on, DefaultAudioSink.configure builds the
     * pipeline from trimming, channel mapping and the float conversion and jumps past
     * audioProcessorChain.getAudioProcessors() (checked in the 1.8.0 bytecode, 25 Sep), and it
     * stops applying speed and pitch through processors as well. So the switch did the opposite of
     * its name: volume normalisation, spatial audio, the upmix, skip silence and tempo all stopped,
     * for every song whose decoder took the float request. The media3 sources in `media/` keep the
     * chain on float output, so this can come back with a media3 that does. The row is hidden in
     * PlayerSettings until then, and the key is kept so nothing has to be migrated.
     */
    private val highPrecisionAudio = false

    /**
     * Stereo to 5.1, so the platform spatialiser has something it will act on. Off by default.
     *
     * One instance, held here, because the switch has to reach the chain that is already running.
     */
    val spatialUpmixProcessor = StereoUpmixAudioProcessor()

    /**
     * Stereo rendered as two loudspeakers in front of the listener. Off by default.
     *
     * Held for the same reason as [spatialUpmixProcessor], and mutually exclusive with it: one
     * hands six channels to the phone, the other hands back the two it was given, and doing both
     * would mean spatialising an already spatialised signal.
     */
    val binauralProcessor = BinauralAudioProcessor()

    /**
     * Head tracking, where the phone is willing to publish a tracker at all.
     *
     * Lazy because it touches SensorManager, and created whether or not anything is available:
     * asking it to start is how you find out, and the answer changes when headphones connect.
     */
    val headTracking: HeadTracking by lazy {
        HeadTracking(this, binauralProcessor, Handler(Looper.getMainLooper())).apply {
            onCalibrated = { radiansPerSecond ->
                lastingScope.launch {
                    dataStore.edit { prefs ->
                        prefs[HeadTrackingDriftKey] = Math.toDegrees(radiansPerSecond.toDouble()).toFloat()
                    }
                }
            }
        }
    }

    /** Whether the listener asked for head tracking, independent of whether it is possible. */
    private var headTrackingWanted = false

    /**
     * Measure how long it takes a rendered sample to reach the ear, and tell the head tracking.
     *
     * The renderer counts what it has put out; the player reports where the audio device says it
     * actually is. The difference is everything in between: the sink's buffer, the Bluetooth
     * encoder, the link and the headphones. That total is what the prediction has to cover, and
     * it is not a number an app can assume, because it depends on a codec negotiation that
     * happens at connection time and varies by a factor of three.
     *
     * Smoothed hard and clamped to what is physically plausible, because the player's position
     * jumps around a seek and the frame count restarts with it.
     */
    private fun measureOutputLatency() {
        val processed = binauralProcessor.framesProcessed
        if (processed <= 0L) return
        val rate = exoPlayer.audioFormat?.sampleRate ?: return
        if (rate <= 0) return
        val speed = exoPlayer.playbackParameters.speed.takeIf { it > 0.01f } ?: 1f
        val renderedMs = processed * 1000.0 / rate / speed
        val heardMs = exoPlayer.currentPosition - (currentTrackStartMs ?: return)
        val latency = ((renderedMs - heardMs) / 1000.0).toFloat()
        if (latency < MIN_PLAUSIBLE_LATENCY || latency > MAX_PLAUSIBLE_LATENCY) return
        val now = headTracking.lookaheadSeconds
        headTracking.lookaheadSeconds = now + (latency - now) * LATENCY_SMOOTHING
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "output latency ${(latency * 1000).toInt()} ms, using ${(headTracking.lookaheadSeconds * 1000).toInt()} ms")
        }
    }

    /** Media position where the current render run began, so the two clocks share an origin. */
    private var currentTrackStartMs: Long? = null

    /**
     * Whether to trade buffer headroom for latency, read once at construction like the other
     * things that decide how the sink is built.
     *
     * media3 floors its PCM buffer at 250 ms, so a quarter of a second of already rendered audio
     * sits in front of the DAC before Bluetooth adds its own 150 to 250. That is inaudible for
     * ordinary playback and is most of the perceived lag once the soundstage is supposed to follow
     * a head turn, which is the only case this shortens it for. Underruns are the cost, so it is
     * not done for anyone who is not head tracking.
     */
    private val lowLatencyAudio = dataStore.get(HeadTrackingKey, false)
    private val isGaplessOffloadAllowed = dataStore.get(AudioGaplessOffloadKey, false)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    /**
     * Quieter the further the listener gets from the phone. One unless it is switched on.
     *
     * Lazy, and not by preference: a field initialiser on a Service runs during the constructor,
     * before the base context is attached, so anything that asks for a system service there gets
     * a null Context and takes the whole app down on launch.
     */
    val proximityVolume: ProximityVolume by lazy { ProximityVolume(this) }
    private var proximityWanted = false

    /**
     * Resume when Bluetooth connects: why the music stopped last, and the Bluetooth outputs the
     * phone has, kept by [BluetoothResume]. Main thread only: the player reports there, and so do
     * the audio manager's callback below and the setting's observer.
     */
    private val bluetoothResume = BluetoothResume()

    /** Whether Resume when Bluetooth connects is on, which is when the outputs are watched. */
    private var resumeOnBluetooth = false

    /** The music starting again, a moment after a Bluetooth output connected. */
    private var bluetoothResumeJob: Job? = null

    private val bluetoothOutputs = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            bluetoothOutputsAdded(addedDevices.musicOutputs())
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            bluetoothOutputsRemoved(removedDevices.musicOutputs())
        }
    }

    /**
     * Debug and preview builds only: a Bluetooth output that comes and goes on command, for a
     * phone or an emulator with nothing to connect. It takes the path the audio manager's reports
     * take, and does nothing while the setting is off, as the watch it stands in for is not
     * running then. From a computer, with `connected` or `gone`:
     *
     *     adb shell am broadcast -p <package> -a <package>.FAKE_BLUETOOTH_OUTPUT --es state connected
     *
     * Nothing on the phone can send it: the sender has to hold DUMP, which adb's shell has and no
     * app can.
     */
    private val fakeBluetoothOutput = object : android.content.BroadcastReceiver() {
        /** Below every id the audio manager hands out, so a fake is never taken for a real one. */
        private var next = -1
        private val connected = ArrayList<Int>()

        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra("state")
            Log.i(TAG, "A fake Bluetooth output: $state${if (resumeOnBluetooth) "" else ", ignored with the setting off"}")
            if (!resumeOnBluetooth) return
            when (state) {
                "connected" -> {
                    val id = next--
                    connected += id
                    bluetoothOutputsAdded(listOf(id))
                }

                "gone" -> {
                    bluetoothOutputsRemoved(connected.toList())
                    connected.clear()
                }
            }
        }
    }
    private var fakeBluetoothOutputRegistered = false

    /**
     * One instance, shared by the builder below and by the observer that keeps the focus flag in
     * step with the setting. ExoPlayer rebuilds the AudioTrack only when the new attributes differ
     * from the old, so handing it the same object flips the flag alone: no gap in the sound and no
     * new audio session id, which is what the equaliser is attached to.
     */
    private val musicAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private var isAudioEffectSessionOpened = false

    var consecutivePlaybackErr = 0

    override fun onCreate() {
        Log.i(TAG, "Starting MusicService")
        super.onCreate()
        isRunning = true
        // media3 writes this under its own name only, which Report a problem leaves out, and it is
        // the line that explains a song stopping a minute after the screen goes off (issue #26).
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w(TAG, StopReasons.NOT_IN_FOREGROUND)
                StopReasons.keep(this@MusicService, StopReasons.NOT_IN_FOREGROUND)
            }
        })
        screenOffRegistered = runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "Could not listen for the screen going off", it) }.isSuccess

        // The repair scan must never rewrite the loudness of the song that is playing, because
        // after the unknown-loudness fallback an unrepaired track sits at heavy attenuation and
        // filling in its real value would make it jump louder mid-song.
        //
        // Owned here rather than by the settings screen for two reasons. The scan runs on a
        // singleton scope that outlives any screen, so a composable installing the provider means
        // navigating away silently switches the guarantee off. And the scan runs on Dispatchers.IO,
        // where touching ExoPlayer throws IllegalStateException; currentMediaMetadata is a
        // StateFlow, so reading .value is safe from any thread. It is also the same flow
        // currentFormat is derived from, so the guard cannot disagree with what it guards.
        val nowPlaying = currentMediaMetadata
        val provider: () -> String? = { nowPlaying.value?.id }
        installedNowPlayingProvider = provider
        loudnessRepair.nowPlayingIdProvider = provider

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                musicAudioAttributes,
                // handleAudioFocus. On, InterTune claims audio focus, which is what silences
                // whatever else was playing, and it also pauses InterTune when something else
                // claims it. Off does both halves of what upstream #1255 asks for: it stops
                // interrupting others and stops being interrupted.
                //
                // This is the starting value only; the observer in onCreate keeps it current. It
                // has to, because the player is built once per service and the service outlives
                // every visit to the settings screen: it is created when the interface starts and
                // then held up by its own MediaController, so nothing short of the process dying
                // ever builds another one.
                !dataStore.get(ShareAudioFocusKey, false)
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                // listeners
                addListener(this@MusicService)
                // The sleep timer pauses whatever plays, so with casting possible it follows the
                // routed player below instead, and pauses a receiver too.
                if (!Unreleased.CASTING) {
                    sleepTimer = SleepTimer(scope, this)
                    addListener(sleepTimer)
                }
                transitionFade = TransitionFade(scope, this)
                addListener(transitionFade)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                // A song whose cached copy cannot be read is given up, so a retry reads it whole. See BadCachedCopy.
                addAnalyticsListener(object : AnalyticsListener {
                    /** When each song's last failed load was written down: the player retries every few seconds. */
                    private val failedLoadNoted = HashMap<String, Long>()

                    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                        if (wasCanceled) return
                        val id = loadEventInfo.dataSpec.key
                        badCachedCopy.loadFailed(id, error)
                        // A route that keeps failing, or refuses the address, is changed while the
                        // buffer still plays; a load that failed for want of a network is not the
                        // route's. See Recovery.LoadFailures.
                        if (loadFailures.decide(id, error, SystemClock.elapsedRealtime(), online = isNetworkConnected.value) == Recovery.Decision.CHANGE_ROUTE && id != null) {
                            val status = Recovery.status(error)
                            changeRoute(id, if (status != null) "load refused (HTTP $status)" else "load failed twice in 30 s", refused = status == 403 || status == 410)
                        }
                        // A load that fails is retried without a word, and nothing was written until the
                        // player gave up: a song that stopped said "no error" (issue #20), and Report a
                        // problem had nothing to show. One line a minute a song at most, with its causes.
                        val now = SystemClock.elapsedRealtime()
                        val last = failedLoadNoted[id.orEmpty()]
                        if (last == null || now - last >= 60_000L) {
                            failedLoadNoted[id.orEmpty()] = now
                            val causes = generateSequence(error as Throwable) { it.cause }.take(4)
                                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.take(120)}" }
                            Log.w(TAG, "PLAYING: a load of $id failed, the player tries again: ${ErrorText.withoutAddresses(causes)}")
                        }
                    }

                    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                        badCachedCopy.loadCompleted(loadEventInfo.dataSpec.key)
                    }
                })

                // misc
                setOffloadEnabled(dataStore.get(AudioOffloadKey, false))
            }
        player = exoPlayer
        if (Unreleased.CASTING) {
            val routed = RoutedPlayer(exoPlayer)
            routedPlayer = routed
            player = routed
            sleepTimer = SleepTimer(scope, routed)
            routed.addListener(sleepTimer)
        }

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }

        // The session is first handed a smaller cover that is on the phone, so the lock screen is
        // never without a picture while the large one downloads, and a plain stand-in (never the
        // app's logo) when no cover can be had. When something better arrives the session has to
        // be made to ask again, which only its player can do: see SessionPlayer. The loader's tries
        // run on this service's scope and end with it.
        val covers = SessionBitmapLoader(this, CoroutineScope(scope.coroutineContext + coilCoroutine))
        val sessionPlayer = SessionPlayer(player)
        covers.sharper = { stored, sharp ->
            scope.launch { sessionPlayer.coverArrived(stored.toUri(), sharp.toUri()) }
        }
        // A cover that could not be had for want of a connection is tried for as soon as the
        // connection is back. Nothing else would ask before the next song: the lock screen asks
        // once a song.
        isNetworkConnected.collect(scope) { online ->
            if (online) covers.tryAgain()
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // TODO: do i even want to have smaller art for media notification
            .setBitmapLoader(covers)
            // Media3 otherwise sends the position every 3 seconds while playing, to every connected
            // controller and to the platform session, which is a binder call into system_server
            // that fans out to System UI, Bluetooth and anything else listening. None of them need
            // it: a platform PlaybackState carries the position, the time it was taken and the
            // speed, and its readers extrapolate from that, as a media3 controller does from its
            // own. Play, pause, seek, speed and track changes still update everyone at once. The
            // app's own screens read the player directly and never saw these.
            .setPeriodicPositionUpdateEnabled(false)
            .build()

        // In a car the player's row of buttons is laid out for a driver (MediaControlButtons.CAR).
        if (Unreleased.AUTO_REWORK) carType.observeForever(carWatch)

        activePlayer.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Released the moment it connects, which the inherited version never did, and that is the
        // difference between a service that can stop and one that cannot.
        //
        // The token is built from a ComponentName, so it is TYPE_SESSION_SERVICE, and media3
        // connects that kind by binding the service: MediaControllerImplBase.requestConnectToService
        // calls Context.bindService with BIND_AUTO_CREATE. Holding it forever means the service
        // holds a binding on itself, and a bound service does not go away for stopSelf. So every
        // teardown path is a no-op: onTaskRemoved, media3's pauseAllPlayersAndStopSelf, all of it.
        // onDestroy is the only place that releases the player, the session, the sensors and the
        // volume receiver, and it was never reached. That is what turns a few minutes of audio into
        // a service measured in hours, and the session staying alive is what keeps the system's
        // media and Bluetooth clients talking to us all day.
        //
        // The comment it replaces said this was needed for the notification. It is not, at least
        // not on media3 1.8.0: MediaNotificationManager builds its own controller from the session
        // token, which connects in process without binding anything. Kept and released rather than
        // deleted outright because it is inherited from upstream and may still do something useful
        // at cold start; released immediately, the binding lasts milliseconds instead of hours.
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            { MediaController.releaseFuture(controllerFuture) },
            MoreExecutors.directExecutor(),
        )

        connectivityManager = getSystemService()!!
        if (Unreleased.CASTING) {
            castProbe = CastProbe(this, connectivityManager).also { it.register() }
            castSession = CastSession(this, connectivityManager)
        }

        if (BuildConfig.DEBUG) {
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    this, fakeBluetoothOutput, android.content.IntentFilter("$packageName.FAKE_BLUETOOTH_OUTPUT"),
                    android.Manifest.permission.DUMP, null, androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
                )
                fakeBluetoothOutputRegistered = true
            }.onFailure { Log.w(TAG, "Could not listen for a fake Bluetooth output", it) }
        }

        // Read before the first layout goes out, so a controller that connects now gets the picked
        // buttons rather than the default and then a correction. Then followed, because the
        // service outlives every visit to settings and the change should show while the
        // notification is up, not at the next start.
        mediaControlButtons = MediaControlButtons.parse(dataStore[MediaControlButtonsKey])
        dataStore.data.map { MediaControlButtons.parse(it[MediaControlButtonsKey]) }.distinctUntilChanged()
            .collect(scope) { buttons ->
                if (buttons == mediaControlButtons) return@collect
                mediaControlButtons = buttons
                updateNotification()
            }

        currentSong.collect(scope) {
            updateNotification()
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this@MusicService,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )

        // lateinit tasks
        offloadScope.launch {
            Log.i(TAG, "Launching MusicService offloadScope tasks")
            // The legacy play log becomes listens, once; nothing to do after the first run. On its
            // own, because it still walks related_song_map on every start, and everything below
            // used to wait for it.
            offloadScope.launch {
                runCatching { LegacyBackfill(DatabaseBackfillIo(database)).run() }
                    .onFailure { Log.w(TAG, "Could not backfill the play log", it) }
            }

            combine(
                playerVolume,
                normalizeFactor,
                sleepTimer.fadeFactor,
                proximityVolume.factor,
                transitionFade.factor,
            ) { playerVolume, normalizeFactor, fadeFactor, proximityFactor, transitionFactor ->
                playerVolume * normalizeFactor * fadeFactor * proximityFactor * transitionFactor
            }.collectLatest(scope) { _ ->
                // Signal order is still decode -> normalise -> amplify -> soft clip. What changed
                // is WHERE each stage runs, and the split is by what survives audio offload rather
                // than by what the gain means.
                //
                // Everything at or below unity goes to player.volume, because that is the only
                // gain stage offload cannot skip: DefaultAudioSink.setVolumeInternal is guarded
                // only by "is there an AudioTrack", it never looks at outputMode. Offload skips
                // the processor chain entirely, since DefaultAudioSink.configure builds an empty
                // AudioProcessingPipeline for any sampleMimeType other than audio/raw.
                //
                // Normalisation only ever attenuates, so it is ALWAYS in that half. That is the
                // whole point: loudness normalisation now behaves identically with offload on or
                // off, and there is no setting for anyone to get wrong.
                //
                // Only gain above unity goes to the processor, which is where the soft clip lives.
                // min(total, 1f) is exactly 1f whenever total > 1, so the two stages are disjoint
                // and their product is exact, with no division to reconstruct.
                val total = normalizeFactor.value * playerVolume.value
                gainProcessor.gain = total
                withContext(Dispatchers.Main) {
                    // Proximity belongs on this side of the split for the same reason
                    // normalisation does: it only ever attenuates, so it goes where offload
                    // cannot skip it. So do both fades, the sleep timer's and the one between
                    // songs. The combine above only decides when to recompute, so anything left
                    // out of this line does nothing at all.
                    exoPlayer.volume = min(total, 1f) * sleepTimer.fadeFactor.value *
                        proximityVolume.factor.value * transitionFade.factor.value
                }
            }

            playerVolume.debounce(1000).collect(scope) { volume ->
                dataStore.edit { settings ->
                    settings[PlayerVolumeKey] = volume
                }
            }

            dataStore.data
                .map { it[SkipSilenceKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) {
                    withContext(Dispatchers.Main) {
                        exoPlayer.skipSilenceEnabled = it
                    }
                }

            // The four the song-change path used to read blocking. Nothing here touches the
            // player, so they stay off the main thread; the fields are volatile because the
            // readers are the application looper and Room's executors.
            dataStore.data.map { it[PauseListenHistoryKey] ?: false }.distinctUntilChanged()
                .collectLatest(scope) { listenHistoryPaused = it }
            // Found asking Last.fm, or switched to it, it unsettles the songs this process settled
            // without asking: the service can outlive the switch by days, and a song resumed at
            // launch can settle before this first read. Switched to Both or Last.fm only while
            // running, it also catches up on the songs rows are built around, and it does so once
            // for a listener who said yes but has never caught up. Switched back to YouTube only,
            // the catch-up stops.
            var similarRead = false
            dataStore.data.map { similarSourceOf(it) }.distinctUntilChanged()
                .collectLatest(scope) { mode ->
                    val on = mode != SimilarSource.YOUTUBE
                    val switchedOn = on && similarRead && !asksLastFm
                    if (on && !asksLastFm) recoverySettled.clear()
                    similarRead = true
                    asksLastFm = on
                    if (on) withContext(Dispatchers.IO) {
                        try {
                            val never = dataStore.data.first()[LastFmCaughtUpAtKey] == null
                            if ((switchedOn || never) && lastFmSimilar.catchUp())
                                dataStore.edit { it[LastFmCaughtUpAtKey] = System.currentTimeMillis() }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "Last.fm catch-up failed", e)
                        }
                    }
                }
            dataStore.data.map { it[AutoLoadMoreKey] ?: true }.distinctUntilChanged()
                .collectLatest(scope) { autoLoadMore = it }
            dataStore.data.map { it[ContextChipKey] ?: 0 }.distinctUntilChanged()
                .collectLatest(scope) { contextChip = it }
            dataStore.data.map { it[PersistentQueueKey] ?: true }.distinctUntilChanged()
                .collectLatest(scope) { persistentQueue = it }
            // Flipping the flag is not enough: a sink already configured for two channels keeps
            // producing two channels until something makes it reconfigure, and the only thing
            // that does is the track being re-prepared. So the current one is restarted where it
            // stands, which costs a short gap and is the price of the setting working when it is
            // pressed rather than whenever the app is next killed.
            dataStore.data.map { it[StageWidthKey] ?: BinauralAudioProcessor.DEFAULT_STAGE_WIDTH.toInt() }
                .distinctUntilChanged()
                .collectLatest(scope) { binauralProcessor.stageWidthDegrees = it.toFloat() }

            // Read once a buffer as well: a band is heard moving as its slider moves.
            dataStore.data.map { prefs ->
                EqualizerAudioProcessor.Settings(
                    Unreleased.EQUALIZER && (prefs[EqualizerEnabledKey] ?: false),
                    EqualizerPreset.parse(prefs[EqualizerGainsKey]),
                )
            }
                .distinctUntilChanged()
                .collectLatest(scope) { equalizerProcessor.settings = it }

            // Both read by the renderer once a buffer, so they are heard as the slider moves.
            dataStore.data.map { it[SpatialStrengthKey] ?: 100 }
                .distinctUntilChanged()
                .collectLatest(scope) { binauralProcessor.strength = if (Unreleased.SPATIAL_TUNING) it / 100f else 1f }
            dataStore.data.map { it[SpatialBassDirectKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) { binauralProcessor.bassDirect = Unreleased.SPATIAL_TUNING && it }
            dataStore.data.map { it[SpatialRoomKey] ?: 0 }
                .distinctUntilChanged()
                .collectLatest(scope) { binauralProcessor.room = if (Unreleased.SPATIAL_TUNING) it / 100f else 0f }
            dataStore.data.map { it[SpatialAmbienceKey] ?: 0 }
                .distinctUntilChanged()
                .collectLatest(scope) { binauralProcessor.ambience = if (Unreleased.DIRECT_AMBIENT) it / 100f else 0f }

            dataStore.data.map {
                it[HeadTrackingResponseKey]?.let { name ->
                    runCatching { HeadTrackingResponse.valueOf(name) }.getOrNull()
                } ?: HeadTrackingResponse.BALANCED
            }.distinctUntilChanged()
                .collectLatest(scope) { response ->
                    // Fraction, how far the lead may run, and how fast the rate estimate reacts.
                    // All three together, because turning one up and leaving the others is what
                    // made Quick still feel slow: the clamp threw most of the lead away.
                    headTracking.predictFraction = when (response) {
                        HeadTrackingResponse.SMOOTH -> 0f
                        HeadTrackingResponse.BALANCED -> 0.5f
                        HeadTrackingResponse.QUICK -> 0.95f
                        HeadTrackingResponse.INSTANT -> 1.15f
                    }
                    headTracking.predictClamp = when (response) {
                        HeadTrackingResponse.SMOOTH -> 0.26f
                        HeadTrackingResponse.BALANCED -> 0.52f
                        HeadTrackingResponse.QUICK -> 1.05f      // 60 degrees
                        HeadTrackingResponse.INSTANT -> 1.57f    // 90 degrees
                    }
                    headTracking.rateSmoothing = when (response) {
                        HeadTrackingResponse.SMOOTH -> 0.25f
                        HeadTrackingResponse.BALANCED -> 0.35f
                        HeadTrackingResponse.QUICK -> 0.6f
                        HeadTrackingResponse.INSTANT -> 0.85f
                    }
                }

            // Tuned by ear, because the delay is mostly the Bluetooth codec and the platform
            // reports no latency for this route.
            // Changes how many harmonics there are to convolve, so the sink has to be told, and
            // the only thing that tells it is the track being re-prepared.
            dataStore.data.map { it[ProximityVolumeKey] ?: false }.distinctUntilChanged()
                .collectLatest(scope) { want ->
                    proximityWanted = want
                    withContext(Dispatchers.Main) {
                        // The phone's own player: the scan turns down sound coming out of the phone,
                        // so it never runs for a receiver.
                        if (ProximityVolume.shouldScan(want && !isCasting, exoPlayer.playWhenReady, exoPlayer.playbackState)) {
                            proximityVolume.start()
                        } else {
                            proximityVolume.stop()
                        }
                    }
                }

            // Resume when Bluetooth connects. The outputs are watched only while it is on.
            dataStore.data.map { it[ResumeOnBluetoothKey] ?: false }.distinctUntilChanged()
                .collectLatest(scope) { on ->
                    withContext(Dispatchers.Main) { watchBluetoothOutputs(on) }
                }

            dataStore.data.map { it[HeadTracking3dKey] ?: false }.distinctUntilChanged()
                .collectLatest(scope) { want ->
                    if (binauralProcessor.fullSphere == want) return@collectLatest
                    binauralProcessor.fullSphere = want
                    headTracking.fullSphere = want
                    withContext(Dispatchers.Main) {
                        if (exoPlayer.currentMediaItem != null && binauralProcessor.enabled) {
                            val at = exoPlayer.currentPosition
                            val wasPlaying = exoPlayer.playWhenReady
                            exoPlayer.stop()
                            exoPlayer.prepare()
                            exoPlayer.seekTo(at)
                            exoPlayer.playWhenReady = wasPlaying
                        }
                    }
                }

            dataStore.data.map { it[HeadTrackingLeadKey] ?: DEFAULT_LEAD_MS }.distinctUntilChanged()
                .collectLatest(scope) { headTracking.lookaheadSeconds = it / 1000f }

            // Measured once and kept, so a tracker that slides a degree a second stops taking the
            // soundstage with it.
            dataStore.data.map { it[HeadTrackingDriftKey] ?: 0f }.distinctUntilChanged()
                .collectLatest(scope) { headTracking.driftRate = Math.toRadians(it.toDouble()).toFloat() }

            // A request, not a value: the button is in the settings and the sensor is here.
            dataStore.data.map { it[HeadTrackingCalibrateKey] ?: 0L }.distinctUntilChanged()
                .collectLatest(scope) { at ->
                    // Only a request made just now. The stored time outlives it (the settings row
                    // counts down from it), and DataStore hands it back first thing on every start,
                    // so one calibration restarted the tracker on every later launch, whatever
                    // the head tracking switch said, and measured the drift again behind the
                    // listener's back, likely while they were moving.
                    val window = HeadTracking.CALIBRATION_NANOS / 1_000_000L
                    if (at == 0L || System.currentTimeMillis() - at > window) return@collectLatest
                    withContext(Dispatchers.Main) {
                        if (headTracking.start()) headTracking.startCalibration()
                    }
                    // A tracker started only to calibrate goes off again once that is over,
                    // rather than holding the sensor until the next play and pause.
                    delay(window + 5_000L)
                    withContext(Dispatchers.Main) {
                        if (!(headTrackingWanted && binauralProcessor.enabled && exoPlayer.isPlaying)) headTracking.stop(glideHome = false)
                    }
                }

            dataStore.data.map { it[HeadTrackingKey] ?: false }.distinctUntilChanged()
                .collectLatest(scope) { want ->
                    headTrackingWanted = want
                    withContext(Dispatchers.Main) {
                        if (want && binauralProcessor.enabled && exoPlayer.isPlaying) headTracking.start()
                        else if (!want) headTracking.stop(glideHome = true)
                    }
                }

            dataStore.data.map {
                it[SpatialAudioKey]?.let { name ->
                    runCatching { SpatialAudioMode.valueOf(name) }.getOrNull()
                } ?: SpatialAudioMode.OFF
            }.distinctUntilChanged()
                .collectLatest(scope) { mode ->
                    val wantSurround = mode == SpatialAudioMode.SURROUND
                    val wantHeadphones = mode == SpatialAudioMode.HEADPHONES
                    if (spatialUpmixProcessor.enabled == wantSurround &&
                        binauralProcessor.enabled == wantHeadphones
                    ) {
                        return@collectLatest
                    }
                    spatialUpmixProcessor.enabled = wantSurround
                    binauralProcessor.enabled = wantHeadphones
                    withContext(Dispatchers.Main) {
                        // Rotating a field nobody is rendering does nothing, and the sensor costs
                        // battery, so tracking follows the renderer rather than being independent.
                        if (!wantHeadphones) headTracking.stop(glideHome = false)
                        else if (headTrackingWanted && exoPlayer.isPlaying) headTracking.start()
                        // Offload hands the stream to the phone's low power audio path, which
                        // skips the processor chain entirely. Leaving it on would mean the
                        // setting appears to do nothing for anyone who turned offload on, with
                        // no way to tell why, so spatial audio takes it off itself.
                        applyOffload()
                        if (exoPlayer.currentMediaItem != null) {
                            val at = exoPlayer.currentPosition
                            val wasPlaying = exoPlayer.playWhenReady
                            exoPlayer.stop()
                            exoPlayer.prepare()
                            exoPlayer.seekTo(at)
                            exoPlayer.playWhenReady = wasPlaying
                        }
                    }
                }

            dataStore.data.map {
                it[AdaptiveQueueModeKey]?.let { name ->
                    runCatching { AdaptiveQueueMode.valueOf(name) }.getOrNull()
                } ?: AdaptiveQueueMode.AUTOPLAY_ONLY
            }.distinctUntilChanged()
                .collectLatest(scope) { mode ->
                    adaptiveQueueMode = mode
                    refreshAdaptiveTail()
                    // Switched off: nothing waiting, held back or being worked out changes a radio after that.
                    if (!mode.isOn) dropPasses()
                }
            // NewPipe's player script fetched a few seconds after the start, on a network that
            // costs nothing: see Warmup.
            scope.launch(Dispatchers.IO + SilentHandler) {
                delay(Warmup.AFTER_MS)
                val id = withContext(Dispatchers.Main) { activePlayer.currentMediaItem?.mediaId }
                val network = connectivityManager.activeNetwork
                val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
                val warm = Warmup.newPipe(
                    validated = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                    dataSaver = connectivityManager.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                    unmetered = !connectivityManager.isActiveNetworkMetered,
                    alreadyWarmed = Warmup.warmedInProcess,
                )
                if (warm && id != null) {
                    Warmup.warmedInProcess = true
                    val started = SystemClock.elapsedRealtime()
                    val done = NewPipeUtils.warm(id)
                    Log.i(TAG, "NewPipe's player script ready ahead of the first song in ${StepLog.seconds(SystemClock.elapsedRealtime() - started)}: ${if (done.isSuccess) "ok" else "failed"}")
                }
            }
            // An address played before its check answered, and refused when the answer came: the
            // player's next open of the song asks afresh, and the route is asked last for it. See
            // StreamCheck.playsUnchecked.
            // The cast server's resolver keeps addresses of its own, and is told as well.
            YTPlayerUtils.onLateRefusal = lateRefusal
            // Read here rather than in YTPlayerUtils, which runs on every song and would have
            // to block on the datastore to find out.
            dataStore.data.map {
                it[PlaybackAuthModeKey]?.let { name ->
                    runCatching { PlaybackAuthMode.valueOf(name) }.getOrNull()
                } ?: PlaybackAuthMode.WHEN_REFUSED
            }.distinctUntilChanged()
                .collectLatest(scope) { YTPlayerUtils.authMode = it }
            // The experiment behind Unreleased.WEB_CLIENT_FIRST, read here for the same reason.
            if (Unreleased.WEB_CLIENT_FIRST) {
                dataStore.data.map { it[WebClientFirstKey] ?: false }
                    .distinctUntilChanged()
                    .collectLatest(scope) { YTPlayerUtils.askWebClientFirst = it }
            }
            // A test tool like the switches below: never read in a release.
            if (BuildConfig.DEBUG && Unreleased.SABR) {
                dataStore.data.map { it[SabrOnlyKey] ?: false }
                    .distinctUntilChanged()
                    .collectLatest(scope) { YTPlayerUtils.sabrOnly = it }
                dataStore.data.map { it[EveryDirectRefusedKey] ?: false }
                    .distinctUntilChanged()
                    .collectLatest(scope) { YTPlayerUtils.everyDirectRefused = it }
            }
            // SABR's pacing, in every build: where the player is, four times a second while a song
            // plays or buffers, once a second otherwise, and only while a SABR read paced by the
            // player is open. Paused or buffering too: after a seek the player sits at the new place
            // waiting for bytes, and a clock left at the old place had the stream wait for the
            // player. Asleep on an ordinary day, when no song is read over SABR. See SabrClock.
            SabrClock.reading.collectLatest(scope) { reading ->
                if (!reading) return@collectLatest
                while (true) {
                    SabrClock.update(activePlayer.currentMediaItem?.mediaId, activePlayer.currentPosition)
                    delay(if (activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_BUFFERING) 250 else 1000)
                }
            }
            // And the switches that take VISIONOS for refused. They are a test tool, so they are
            // read only where the developer options have their row, in a debug or preview build,
            // whatever the two flags say. A restored backup can bring a switch that is on into a
            // release, which has no row to turn it off with.
            if (BuildConfig.DEBUG && Unreleased.WEB_FALLBACK) {
                dataStore.data.map { it[VisionosRefusedKey] ?: false }
                    .distinctUntilChanged()
                    .collectLatest(scope) { YTPlayerUtils.takeVisionosForRefused(it) }
            }
            if (BuildConfig.DEBUG && Unreleased.VISIONOS_IDENTITIES) {
                dataStore.data.map { it[OwnVisionosRefusedKey] ?: false }
                    .distinctUntilChanged()
                    .collectLatest(scope) { YTPlayerUtils.takeVisionosForRefused(it, onlyAsSaidHere = true) }
            }

            // The switch has to reach the player already running, not some later one. Without this
            // it sat inert until the process was killed, which on One UI happens often enough, and
            // unpredictably enough, to look like the setting works sometimes and not others.
            //
            // Applied at once rather than at the next song, because the listener has just asked
            // for it and a queue can run for an hour. Turning it on abandons focus immediately, so
            // anything we were ducking or waiting out comes straight back at full volume. Turning
            // it off requests focus immediately, the same request a fresh play makes, so if
            // another app holds it the music stops now rather than pretending otherwise.
            dataStore.data
                .map { it[ShareAudioFocusKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) { share ->
                    withContext(Dispatchers.Main) {
                        exoPlayer.setAudioAttributes(musicAudioAttributes, !share)
                    }
                }

            // Same problem as the switch above: applyOffload() only ran as a side effect of the
            // spatial audio and fade observers reacting to their own keys, so toggling Audio
            // offload itself never reached the already running player. It needs its own observer.
            dataStore.data
                .map { it[AudioOffloadKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) {
                    withContext(Dispatchers.Main) {
                        applyOffload()
                    }
                }

            // The sleep timer's own notification, and the only thing in this app that can be a
            // Live Update: the media notification draws a custom view, which the platform refuses
            // to promote.
            //
            // A countdown has to be redrawn as it counts, so the armed case is a timer and always
            // was. What was wrong is the other case. This polled every five seconds while the
            // timer was idle and every thirty while it was running, which is backwards twice over:
            // the frequent poll was the one doing nothing, and idle is where this lives almost all
            // of the time. On the service scope, for the whole life of the service, that is 720
            // wakeups an hour to read a boolean and go back to sleep.
            //
            // isActive is derived from triggerTime and pauseWhenSongEnd, both Compose state, so it
            // can simply be watched. Idle now costs nothing at all and the countdown still ticks at
            // the resolution its text shows.
            scope.launch {
                var shown = false
                snapshotFlow { sleepTimer.isActive }.collectLatest { armed ->
                    if (!armed) {
                        if (shown) {
                            sleepTimerNotification.hide()
                            shown = false
                        }
                        return@collectLatest
                    }
                    while (isActive) {
                        val trigger = sleepTimer.triggerTime
                        sleepTimerNotification.show(
                            if (trigger == -1L) null
                            else (trigger - System.currentTimeMillis()).coerceAtLeast(0L)
                        )
                        shown = true
                        delay(SLEEP_TIMER_NOTIF_TICK_MS)
                    }
                }
            }

            combine(
                dataStore.data
                    .map { it[SleepTimerFadeKey] ?: SleepTimerDefaults.FADE_ENABLED }
                    .distinctUntilChanged(),
                dataStore.data
                    .map { it[SleepTimerFadeDurationKey] ?: SleepTimerDefaults.FADE_DURATION_SECONDS }
                    .distinctUntilChanged()
            ) { fade, seconds -> fade to seconds }
                .collectLatest(scope) { (fade, seconds) ->
                    sleepTimer.fadeEnabled = fade
                    sleepTimer.fadeDurationMs = seconds * 1000L
                }

            // The sleep timer on a schedule (ScheduledSleep). It looks only while music plays: as
            // the music starts, at each song, as a timer starts or stops, as a setting changes,
            // and when the window opens. Paused, nothing here runs at all. It starts this
            // service's one sleep timer, so the fade and the stop are the ones set above.
            //
            // The song changes are there for the wait. A delay counts the time the processor is
            // awake, and with audio offload on it sleeps through most of a song, so the wait for
            // the window can run long; a song change always happens awake.
            scope.launch {
                sleepScheduleServed = dataStore.data.first()[SleepScheduleLastNightKey]
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                combine(
                    dataStore.data
                        .map {
                            ScheduledSleep.Settings(
                                on = it[SleepScheduleKey] ?: false,
                                start = ScheduledSleep.startOf(it[SleepScheduleStartKey]),
                                length = ScheduledSleep.lengthOf(it[SleepScheduleLengthKey]),
                            )
                        }
                        .distinctUntilChanged(),
                    playingNow,
                    snapshotFlow { sleepTimer.isActive },
                    songChanges,
                ) { settings, playing, _, _ -> settings.takeIf { it.on && playing } }
                    .collectLatest { settings ->
                        if (settings == null) return@collectLatest
                        while (isActive) {
                            lookAtSleepSchedule(settings)
                            delay(ScheduledSleep.untilNextLook(ZonedDateTime.now(), settings.start).toMillis())
                        }
                    }
            }

            // On the service scope, which is the player's thread: both setters read the player.
            // The length goes in first, so switching on with a new length starts on its own curve.
            combine(
                dataStore.data
                    .map { it[TransitionFadeKey] ?: false }
                    .distinctUntilChanged(),
                dataStore.data
                    .map { it[TransitionFadeSecondsKey] ?: TransitionFadeEnvelope.DEFAULT_SECONDS }
                    .distinctUntilChanged()
            ) { fade, seconds -> fade to seconds }
                .collectLatest(scope) { (fade, seconds) ->
                    transitionFade.fadeMs = TransitionFadeEnvelope.fadeMsFor(seconds)
                    if (transitionFade.enabled == fade) return@collectLatest
                    transitionFade.enabled = fade
                    // The fade steps the volume from this thread. Under offload the player sleeps
                    // while the hardware plays out a long buffer and lets go of its wake lock, so
                    // the steps stop with the CPU and a fade can stay low or jump. Offload goes
                    // off while this is on, as it does for spatial audio.
                    applyOffload()
                }

            combine(
                currentFormat,
                dataStore.data
                    .map { it[AudioNormalizationKey] ?: true }
                    .distinctUntilChanged()
            ) { formatAndLocal, normalizeAudio ->
                formatAndLocal to normalizeAudio
            }.collectLatest(scope) { (formatAndLocal, normalizeAudio) ->
                val (format, isLocal) = formatAndLocal
                // Reject impossible values rather than trusting the column. The observed range over
                // 3178 real rows is -16.5 to +12.6, so this only ever catches corruption.
                val loudnessDb = format?.loudnessDb
                    ?.takeIf { it.isFinite() && it in -60.0..40.0 }
                    ?.toFloat()

                normalizeFactor.value = when {
                    // Off is the only case allowed to leave the signal at unity.
                    !normalizeAudio -> 1f

                    // A local file has no YouTube loudness and never will. The repair scan excludes
                    // local rows deliberately, and nothing else writes the column for them, so
                    // falling through to the presumed value would attenuate every local file by
                    // 13 dB permanently with no way to undo it from inside the app. Levelling local
                    // files properly needs ReplayGain or R128 tags read at scan time, which does
                    // not exist yet, so until then they are left alone.
                    isLocal -> 1f

                    // loudnessDb is how far above YouTube's -14 LKFS target this track sits.
                    // Everything above the reference is pulled down to it; everything below is left
                    // alone, because setVolume cannot exceed 1 and so cannot boost.
                    //
                    // The reference is set low on purpose. Normalising to YouTube's own target left
                    // nearly half the library untouched and still varying; going lower trades
                    // absolute loudness, which the device volume can recover, for consistency,
                    // which it cannot.
                    loudnessDb != null ->
                        min(10f.pow(-(loudnessDb - NORMALIZATION_REFERENCE_DB) / 20), 1f)

                    // Loudness unknown. This used to be 1f, meaning no attenuation, which is the
                    // LOUDEST possible answer to "I do not know". Since every track that does have
                    // a value is pulled down 6 to 10 dB, an unknown one stood out by up to 16 dB.
                    // That is the entire "some songs are much louder" complaint.
                    //
                    // Assume it is loud instead. Being wrong quietly is recoverable with the volume
                    // rocker; being wrong loudly is not.
                    else -> min(
                        10f.pow(-(UNKNOWN_LOUDNESS_DB - NORMALIZATION_REFERENCE_DB) / 20),
                        1f
                    )
                }
            }


            // network connectivity
            try {
                connectivityObserver.unregister()
            } catch (e: UninitializedPropertyAccessException) {
                // lol
            }
            // A new network is a new public address: the addresses kept name the old one, and a
            // pooled connection to a stream server is on the old link. See StreamAddresses.forgetAll.
            connectivityObserver = NetworkConnectivityObserver(this@MusicService) {
                lastNetworkChangeAt = SystemClock.elapsedRealtime()
                streamAddresses.forgetAll()
                runCatching { playerStreamClient.connectionPool.evictAll() }
                runCatching { SabrOver.forgetConnections() }
            }

            offloadScope.launch {
                connectivityObserver.networkStatus.collect { isConnected ->
                    isNetworkConnected.value = isConnected

                    if (isConnected && waitingForNetworkConnection.value) {
                        clearNetworkWait()
                        withContext(Dispatchers.Main) {
                            // Only resume something that actually stalled. A flag that outlived its
                            // fault used to mean music starting by itself out of a pocket at the
                            // next network change.
                            if (activePlayer.playbackState == STATE_IDLE) {
                                activePlayer.prepare()
                                activePlayer.play()
                            }
                        }
                    }
                }
            }

            // Last, once everything above is watching: a queue resumed here used to start before
            // any of it, so its first moments played at full volume with no normalisation, and
            // spatial audio then restarted it to switch the renderer on.
            if (!qbInit.value) {
                initQueue(onlyIfNeeded = true)
                resumeOnLaunchIfAsked()
            }
        }
    }


// Library functions

    /**
     * Songs whose related lookup has failed, and when to allow another try.
     *
     * hasRelatedSongs is a COUNT over related_song_map, so a failed lookup writes nothing and
     * leaves the song looking exactly like one that has never been tried. Under a block that meant
     * two requests per play, on every play, forever, against the endpoint that is already
     * refusing us. Remembering the failure for a while is what stops it. In memory on purpose:
     * this is a "do not hammer it right now" note, not a fact about the song, and it should not
     * outlive the process.
     */
    private val relatedLookupFailures = FailureMemo(RELATED_RETRY_COOLDOWN_MS)

    /**
     * What each play knew about itself when it started: where its queue came from, how many songs
     * had autoplayed before it, and the wall clock. Read back when its stats arrive, because by then
     * the queue may already be a different one. Whether it sounded and how it is being left (an
     * error it stopped on, the transition that moved on from it) are kept on it too, see [PlayEnd].
     */
    private class StartInfo(
        val origin: Int,
        val originSlot: Int,
        val queueId: Long,
        val learn: Boolean,
        val autoplayDepth: Int,
        val runId: Long,
        val tappedAt: Long?,
    ) : PlayEnd() {
        /** When and where sound first came out, set when the row is opened, not when the item was loaded. */
        @Volatile var startedAt: Long = 0L
        @Volatile var startPositionMs: Long = 0L
        /** Running total written at each checkpoint; only ever grows, so a rewind cannot erase it. */
        @Volatile var accumulatedPlayedMs: Long = 0L
        /** Where the last checkpoint (or the last seek) left the position, to measure forward progress from. */
        @Volatile var lastCheckpointPositionMs: Long = 0L
        /** The open listen row for this play: 0 until its insert has run, and completed for whoever waits. */
        @Volatile var rowId: Long = 0L
        val rowReady = kotlinx.coroutines.CompletableDeferred<Long>()
    }
    /** The plays being followed, by song, and how each one is being left. */
    private val plays = PlayBook<StartInfo>()
    private fun currentStart(mediaId: String): StartInfo? = plays.current(mediaId)

    /** Set by anything that is the listener choosing a song, read and cleared by the next transition. */
    @Volatile var userChoicePending = false
        set(value) {
            field = value
            // A choice made now is one of its own, whatever loaded under the song playing before it.
            if (value) choiceCarriedOn = false
        }

    /**
     * Set when the queue a choice loaded went in under the song already playing (carriedOnInto),
     * which makes no transition: the choice's mark is still pending, and the transition that
     * follows only moves on from that song. The adaptive queue then reads that leave by its reason,
     * where a pending choice made it a tap on another song and a skip of it was lost. Cleared by the
     * next transition.
     */
    @Volatile private var choiceCarriedOn = false
    /** The tap that started the queue being set up, handed to the listen it produces. */
    @Volatile var pendingTap: Long? = null
    /** An origin for the next play that no queue carries: a system resumption, a play from outside. */
    @Volatile var pendingOrigin: PlayOrigin? = null
    private val lastKnownPosition = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var checkpointJob: kotlinx.coroutines.Job? = null
    private var volumeReceiverRegistered = false
    private var lastRepeatMode = Player.REPEAT_MODE_OFF

    /** What the last radio hop was seeded from, so two hops running never use the same song. */
    @Volatile private var lastRadioSeed: String? = null

    /** How far back to look for a finished song when seeding the next radio hop. */
    private val radioAnchorLookback = 10

    /**
     * Where in the queue the last transition landed, so that going backwards can be told from
     * going forwards. Both arrive as the same seek.
     */
    private var lastQueueIndex = -1
    private var autoplayRun = 0

    /**
     * Songs whose related lookup is running right now.
     *
     * recoverSong is launched on every resolve of the data source, and one play can resolve more
     * than once, so two lookups for the same song used to run side by side: both saw no related
     * rows, both fetched, both wrote. On a fresh install five plays left 224 rows of which 109 were
     * duplicates, each seed's list written twice in YouTube's slightly different second ordering.
     */
    private val relatedInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Songs recoverSong has nothing left to do for, for as long as this service lives.
     *
     * The data source resolves once per 512 KB chunk rather than once per song, and every resolve
     * launches recoverSong, so an hour of streaming runs it on the order of a hundred times. Only
     * the network half was guarded. Everything above it ran on every chunk: the song read through
     * a relation Flow (a Room observer added and dropped again each time), a hop to the main thread
     * to find it in the queue, and two more queries for its length and its related list, all to
     * get the same answers the first chunk already had, and all with the screen off.
     *
     * An id goes in only once both halves are really done: the length is known, and the related
     * list is there and not being refreshed, or a fetch has just written it. A song whose length is
     * still unknown, whose lookup failed, or whose lookup is still running for an earlier chunk is
     * left out, so the next chunk tries again exactly as it always did. A stale list that the day's
     * refresh budget or the throttle turned away counts as done: the refresh is rationed anyway,
     * and it waits for a later run of the service instead of asking again on every chunk. So does a
     * fetch that came back with no related songs at all: it writes no rows, so until now the next
     * chunk found none and made both requests to YouTube again. In memory on purpose, like the
     * failure memo: it is a note about work already done in this process, not a fact about the
     * song.
     */
    private val recoverySettled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun recoverSong(mediaId: String, playbackData: YTPlayerUtils.PlaybackData? = null) {
        if (mediaId in recoverySettled) return
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            activePlayer.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(StandInMemory.idFor(mediaId))
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            // Looked up again here: the listen log inserts a song the moment it starts playing,
            // with whatever length it had, and an insert is ignored if the row already exists.
            val existing = songDurationSec(mediaId)
            if (existing == null) insert(mediaMetadata.copy(duration = duration))
            else if (existing == -1 && duration != -1) setSongDuration(mediaId, duration)
        }
        // Last.fm's similar tracks, when similar songs come from Both or Last.fm only. Asked first
        // because YouTube's lookup below returns early when it fails, and the two do not depend on
        // each other. YouTube's is still fetched either way: Quick picks and the version links
        // come from it whichever source the engine reads.
        val similarDone = !asksLastFm || lastFmSimilar.ensure(
            mediaId,
            song?.song?.title ?: mediaMetadata.title,
            mediaMetadata.artists.firstOrNull()?.name,
        )
        // A list is fetched once, and fetched again after 90 days for at most ten seeds a day,
        // never while YouTube is throttling us. A legacy list of unknown age (fetchedAt 0) stays.
        val fetchedAt = database.relatedFetchedAt(mediaId)
        val refresh = fetchedAt != null && fetchedAt > 0 &&
            System.currentTimeMillis() - fetchedAt > RELATED_STALE_MS && !Throttle.isBlocked && takeRelatedRefreshBudget()
        var relatedDone = fetchedAt != null && !refresh
        if ((fetchedAt == null || refresh) && relatedLookupFailures.none(mediaId) &&
            relatedInFlight.add(mediaId)
        ) try {
            val relatedEndpoint = YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                ?: return relatedLookupFailures.note(mediaId)
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull()
                ?: return relatedLookupFailures.note(mediaId)
            // Only the page's related shelf (other performances are split off in YouTube.related),
            // and never a version of the seed itself, which that shelf still sometimes carries.
            // This graph is what Quick picks is built from, so anything let in here gets
            // recommended.
            val seedTitle = song?.song?.title ?: mediaMetadata.title
            val fetchedAt = System.currentTimeMillis()
            val related = relatedPage.songs
                .distinctBy { it.id }
                .filterNot { it.id == mediaId || SongVersions.isVersionOf(it.title, seedTitle) }
            database.transaction {
                // Checked again here, on the serial transaction executor, so two lookups that
                // raced past the check above still write the list once. The in-flight set stops
                // most of them fetching at all; this is what makes it exact.
                if (refresh) deleteRelated(mediaId) else if (hasRelatedSongs(mediaId)) return@transaction
                related
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                            fetchedAt = fetchedAt,
                        )
                    }
                    .forEach(::insert)
                // YouTube's own word on which ids are versions of this song. No songs are inserted
                // for them: the engine only needs to know they are not candidates.
                insertVersionMap(relatedPage.otherPerformances.map { SongVersionMap(songId = mediaId, versionId = it.id, fetchedAt = fetchedAt) })
            }
            relatedDone = true
        } finally {
            relatedInFlight.remove(mediaId)
        }
        // Both writes above are queued on Room's executors, not awaited. That is enough to call
        // the song settled: a queued write either lands or takes the process down with it, and
        // another chunk could only queue the same writes again.
        if (duration != -1 && relatedDone && similarDone) recoverySettled.add(mediaId)
    }

    /**
     * [shown] is the song on screen, from the player's own heart and library buttons. After a
     * cold start that is the saved queue's song, while the player, which currentSong follows,
     * holds nothing yet: the heart took the tap and changed nothing. Without it (the
     * notification's buttons, which exist only while the player holds a song) it is the
     * player's song as before. The row is read as it is now, so two quick taps are two toggles.
     */
    fun toggleLibrary(shown: String? = null) {
        database.query {
            (shown ?: currentSong.value?.song?.id)?.let { songRow(it) }?.let {
                update(it.toggleLibrary())
            }
        }
    }

    /** See [toggleLibrary] for [shown]. */
    fun toggleLike(shown: String? = null) {
        database.query {
            (shown ?: currentSong.value?.song?.id)?.let { songRow(it) }?.let {
                // toggleLike sends the like to YouTube itself. A likeSong here sent it again.
                val song = it.toggleLike()
                update(song)
                downloadUtil.autoDownloadOnLike(song)
                // A like is the clearest thing anyone says by pressing something, so the far end of
                // the queue gets the reach a skip gets, and two picks like the song. Taken back, it
                // puts nothing in, even in the second a pass waits.
                if (song.id == currentSong.value?.song?.id) {
                    val liked = song.liked
                    scope.launch {
                        if (liked) heard.liked(song.id) else heard.unliked(song.id)
                        adaptiveChanged()
                        if (liked) replanQueueTail(strong = true, like = true)
                    }
                }
            }
        }
    }

    fun toggleStartRadio() {
        val mediaMetadata = activePlayer.currentMetadata ?: return
        playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
    }


// Queue

    /**
     * Play a queue.
     *
     * @param queue Queue to play.
     * @param playWhenReady
     * @param shouldResume Set to true for the player should resume playing at the current song's last save position or
     * false to start from the beginning.
     * @param replace Replace media items instead of the underlying logic
     * @param title Title override for the queue. If this value us unspecified, this method takes the value from queue.
     * If both are unspecified, the title will default to "Queue".
     */
    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        shouldResume: Boolean = false,
        replace: Boolean = false,
        isRadio: Boolean = false,
        title: String? = null,
        origin: PlayOrigin = PlayOrigin.UNKNOWN,
        originSlot: Int = -1,
        tappedAt: Long? = null,
    ) {
        // Radio was already a flag; it is also an origin, and the more useful of the two.
        val playOrigin = if (origin == PlayOrigin.UNKNOWN && isRadio) PlayOrigin.RADIO else origin
        // A tap that starts a queue is the listener choosing, and it begins a run: one play of
        // one queue. Queue ids name a title and are reused, so the run is what learning keys on.
        userChoicePending = true
        pendingTap = tappedAt
        val runId = System.currentTimeMillis()

        queuePlaylistId = queue.playlistId
        // do not use scope.launch ... it breaks randomly... why is this bug back???
        CoroutineScope(Dispatchers.Main).launch {
            // This coroutine is not a child of anything onDestroy cancels, so it checks
            // `destroyed` itself before touching the queue board or the player: here, because the
            // body runs on a later turn of the main loop than the launch, and QueueStart does
            // after each point where it suspends.
            if (destroyed) return@launch
            Log.d(TAG, "playQueue: Resolving additional queue data...")
            try {
                queueStart.run(
                    queue = queue,
                    playWhenReady = playWhenReady,
                    shouldResume = shouldResume,
                    replace = replace,
                    isRadio = isRadio,
                    title = title,
                    origin = playOrigin.code,
                    originSlot = originSlot,
                    runId = runId,
                )
            } catch (e: Exception) {
                reportException(e)
                Toast.makeText(this@MusicService, ErrorText.withoutAddresses("plr: ${e.message}"), Toast.LENGTH_LONG)
                    .show()
            }
            // The steps stop where they are when the service is torn down under them.
            if (destroyed) return@launch

            Log.d(TAG, "playQueue: Queue additional data resolution complete")
        }
    }

    /** The queue board and the player as the steps of playQueue use them. See [QueueStart]. */
    private val queueStart = QueueStart(object : QueueStart.Target {
        override val destroyed get() = this@MusicService.destroyed

        override val untitled get() = getString(R.string.queue)

        override suspend fun boardReady() {
            if (!qbInit.value) initQueue(onlyIfNeeded = true)
        }

        override fun addQueue(
            title: String,
            items: List<MediaMetadata>,
            shuffled: Boolean,
            replace: Boolean,
            startIndex: Int,
            continuationEndpoint: String?,
        ) = queueBoard.addQueue(
            title,
            items,
            shuffled = shuffled,
            replace = replace,
            continuationEndpoint = continuationEndpoint,
            startIndex = startIndex,
        )

        override fun hasQueue(title: String) = queueBoard.masterQueues.any { it.title == title }

        override fun dropQueue(title: String) {
            queueBoard.masterQueues.firstOrNull { it.title == title }?.let { queueBoard.deleteQueue(it) }
        }

        override fun renameQueue(queue: MultiQueueObject, title: String) = queueBoard.renameQueue(queue, title)

        override fun setCurrQueue(queue: MultiQueueObject?, shouldResume: Boolean) {
            queueBoard.setCurrQueue(queue, shouldResume)
        }

        override fun start(playWhenReady: Boolean) {
            // The player a queue was just loaded into, on the phone or on a receiver: [player]
            // hears of it a turn later.
            activePlayer.prepare()
            activePlayer.playWhenReady = playWhenReady
        }

        override val wantsToPlay get() = QueueStart.wantsToPlay(activePlayer.playWhenReady, stoppedByError)

        override suspend fun storedRelated(seed: MediaMetadata): List<MediaMetadata> {
            // A stored song keeps none of YouTube's marks, so with explicit songs hidden there is
            // no telling which of these the switch would keep out. YouTube's page says, so that
            // listener is given that instead. The page only ever lists songs, never videos, so
            // the other switches lose nothing here.
            if (ContentFilter.current.explicit) return emptyList()
            return withContext(Dispatchers.IO) {
                runCatching { database.youtubeRelatedSongs(seed.id).map { it.toMediaMetadata() } }
                    .onFailure { Log.w(TAG, "Could not read the songs related to a radio's song", it) }
                    .getOrDefault(emptyList())
            }
        }

        override suspend fun fetchRelated(seed: MediaMetadata, endpoint: BrowseEndpoint?): List<MediaMetadata> {
            // Only by the answer's own link: an answer without one is what asking again gives as
            // well. And not while YouTube is refusing us, nor for a song whose lookup failed a
            // moment ago.
            if (endpoint == null || Throttle.isBlocked || !relatedLookupFailures.none(seed.id)) return emptyList()
            val page = withContext(Dispatchers.IO) { YouTube.related(endpoint).getOrNull() }
            if (page == null) {
                relatedLookupFailures.note(seed.id)
                return emptyList()
            }
            // Kept as recoverSong keeps the list it stores: never a version of the song itself.
            return ContentFilter.current.apply(page.songs)
                .filterNot { SongVersions.isVersionOf(it.title, seed.title) }
                .map { it.toMediaMetadata() }
        }
    })

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        scope.launch {
            if (!qbInit.value) {

                // when enqueuing next when player isn't active, play as a new song
                if (items.isNotEmpty()) {
                    playQueue(
                        ListQueue(
                            title = items.first().mediaMetadata.title.toString(),
                            items = items.mapNotNull { it.metadata }
                        ),
                        // The listener put these in the queue, so the queue is what they chose.
                        origin = PlayOrigin.QUEUE,
                    )
                }
            } else {
                // enqueue next
                queueBoard.getCurrentQueue()?.let { q ->
                    // The player's index means nothing while it holds nothing, which it does after
                    // a restart until play is pressed. See MultiQueueObject.playNextIndex.
                    val playerIndex = activePlayer.currentMediaItemIndex.takeIf { activePlayer.currentMediaItem != null }
                    noteByHand(q, items)
                    queueBoard.addSongsToQueue(q, q.playNextIndex(playerIndex), items.mapNotNull { it.metadata })
                }
            }
        }
    }

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        // With the queue board down, as it is once the mini player has been swiped away, the
        // songs play as a new queue, as they do from Play next. They used to go to the end of the
        // dismissed queue instead, where nothing showed them and nothing played them.
        if (!qbInit.value) {
            if (items.isNotEmpty()) {
                playQueue(
                    ListQueue(
                        title = items.first().mediaMetadata.title.toString(),
                        items = items.mapNotNull { it.metadata }
                    ),
                    origin = PlayOrigin.QUEUE,
                )
            }
            return
        }
        queueBoard.getCurrentQueue()?.let { noteByHand(it, items) }
        queueBoard.enqueueEnd(items.mapNotNull { it.metadata })
    }

    /**
     * [items] go into [q] by the listener's hand, not the radio's: the adaptive queue leaves them
     * where they are put. Before play after a restart that is the saved queue's run, which this
     * brings back first if the last process saved it. Main thread.
     */
    private fun noteByHand(q: MultiQueueObject, items: List<MediaItem>) {
        heard.putInByHand(q.id, q.runId, items.map { it.mediaId }, radio = q.playlistId != null)
        heard.takeRestored()?.let { Log.d(TAG, AdaptivePass.restoredLine(it)) }
        adaptiveChanged()
    }

    /** [song] was swiped out of the queue sheet. See AdaptiveQueue.Heard.removedByHand. Main thread. */
    fun noteRemovedByHand(song: MediaMetadata) {
        heard.removedByHand(song.id, song.title, song.artists.map { it.name })
        adaptiveChanged()
    }

    /** [id] was dragged to a new place in the queue sheet, which is the listener's from now on. Main thread. */
    fun noteMovedByHand(id: String) {
        heard.movedByHand(id)
        adaptiveChanged()
    }

    /**
     * [q] was loaded under the song already playing, which makes no transition (QueueBoard.setCurrQueue):
     * Start radio on the song playing, whose radio begins in a queue of one with that very song.
     * The song goes on in [q]'s run as its first song, liked there if it was liked, and nothing is
     * planned for it, as for any queue just loaded. Without this the run began at the radio's
     * second song: the song the radio was started from never seeded a pick, its leave was written
     * to the queue before, and a like on it went there too, where its pass was turned away and the
     * like was lost. The top-up, Play next and shuffle load the same queue this way, which changes
     * nothing here. Main thread.
     */
    fun carriedOnInto(q: MultiQueueObject) {
        val item = activePlayer.currentMediaItem ?: return
        val before = heard.runKey
        heard.carriedOn(
            q.id, item.mediaId, item.mediaMetadata.title?.toString(),
            item.metadata?.artists?.map { it.name }.orEmpty(), item.metadata?.duration ?: -1,
            runId = q.runId, radio = q.playlistId != null,
        )
        if (heard.runKey == before) return
        heard.takeRestored()?.let { Log.d(TAG, AdaptivePass.restoredLine(it)) }
        choiceCarriedOn = true
        dropPasses()
        adaptiveChanged()
    }

    fun triggerShuffle() {
        val oldIndex = activePlayer.currentMediaItemIndex
        queueBoard.setCurrQueuePosIndex(oldIndex)
        val currentQueue = queueBoard.getCurrentQueue() ?: return

        // shuffle and update player playlist
        if (!currentQueue.shuffled) {
            queueBoard.shuffleCurrent()
        } else {
            queueBoard.unShuffleCurrent()
        }
        queueBoard.setCurrQueue()

        updateNotification()
    }

    /**
     * Builds the queue board from the saved queues, one caller at a time.
     *
     * @param onlyIfNeeded True for callers that only need a board to exist (onCreate, playQueue, a
     * session play request): if another caller made one while this one waited for the lock, it is
     * used as it is. The default rebuilds from the database even over a live board, which is what
     * a new Max queues value and the reload after a scan rely on.
     */
    suspend fun initQueue(onlyIfNeeded: Boolean = false) {
        initQueueMutex.withLock {
            if (onlyIfNeeded && qbInit.value) return@withLock
            // Only a previous process can have left a row OPEN. initQueue() also runs mid-session
            // (Max queues, a local scan, the queue restarting after the mini player was swiped
            // away), and closing orphans there would close the row of the song still playing right
            // now.
            if (orphanedListensClosedThisProcess.compareAndSet(false, true)) closeOrphanedListens()
            Log.i(TAG, "+initQueue()")
            // A swipe-dismiss just before this queued its save on the service scope instead of
            // waiting for it (see deInitQueue); read after it lands, or this reads the rows it is
            // still in the middle of writing and treats a genuine save as if it had never happened.
            pendingQueueSave?.join()
            val persistQueue = dataStore.get(PersistentQueueKey, true)
            val maxQueues = dataStore.get(MaxQueuesKey, 19)
            if (persistQueue) {
                queueBoard = QueueBoard(this, queueBoard.masterQueues, database.readQueue().toMutableList(), maxQueues)
            } else {
                queueBoard = QueueBoard(this, queueBoard.masterQueues, maxQueues = maxQueues)
            }
            Log.d(TAG, "Queue with $maxQueues queue limit. Persist queue = $persistQueue. Queues loaded = ${queueBoard.masterQueues.size}")
            // What the radios had played, for when their queue plays again. Once a process: this
            // also runs mid-session (Max queues, a scan, the mini player swiped away), and the file
            // is older than anything the runs hold by then.
            if (persistQueue && !adaptiveMemoryRead) {
                adaptiveMemoryRead = true
                val saved = withContext(Dispatchers.IO) { readAdaptiveMemory() }
                withContext(Dispatchers.Main) {
                    heard.offer(saved)
                    if (saved.isNotEmpty()) Log.d(TAG, "Adaptive queue: ${saved.size} runs read back, waiting for their queue to play")
                }
            }
            qbInit.value = true
            queuesLoadedOnce.complete(Unit)
            Log.i(TAG, "-initQueue()")
        }
    }

    /**
     * Plays the restored queue on startup, when the user has asked for that.
     *
     * Upstream #1132: the reporter runs this on a car head unit with the app in autostart and has
     * to reach over and press play every time the device wakes. Everything needed already existed,
     * persistent queue restores it and initQueue has just finished, so all that was missing was the
     * decision to start.
     *
     * Audio focus is not bypassed. play() goes through the same request as any other start, so if
     * something else already holds focus this loses, which in a car is the right way round. It also
     * does nothing when the restored queue is empty, rather than starting silence.
     */
    private suspend fun resumeOnLaunchIfAsked() {
        if (!dataStore.get(ResumePlaybackOnLaunchKey, false)) return
        // All of it on the main thread, not just play(). initQueue runs on the offload scope and
        // ExoPlayer throws on any access from another thread, including reading mediaItemCount,
        // which took the whole service down on the first run of this.
        // All of it on the main thread. ExoPlayer means that about every access, not only the
        // mutating ones, and reading mediaItemCount from the offload scope took the whole service
        // down on the first attempt at this.
        withContext(Dispatchers.Main) {
            // initQueue restores QueueBoard and stops there. Nothing pushes the songs into the
            // player on a cold start, so waiting for them was the second wrong guess: fifteen
            // seconds passed and the player was still empty, because normally it is the UI that
            // asks for the queue when somebody presses play. Load it here instead.
            if (activePlayer.mediaItemCount == 0) {
                val loaded = queueBoard.setCurrQueue(shouldResume = true)
                if (loaded == null || activePlayer.mediaItemCount == 0) {
                    Log.i(TAG, "Resume on launch asked for, but there is no queue to restore")
                    return@withContext
                }
                Log.i(TAG, "Restored '${loaded.title}' for resume on launch")
            }

            // prepare() before play(). setMediaItems leaves ExoPlayer in IDLE and play() only
            // sets playWhenReady, so on its own it produced a player that looked like it was
            // playing, pause button and all, while never resolving a stream or opening an audio
            // track. Normally the UI's own play path does this, which is why nothing else needed it.
            Log.i(TAG, "Resuming playback on launch")
            activePlayer.prepare()
            activePlayer.play()
        }
    }

    /**
     * @param waitForSave True for onDestroy, where teardown continues right after and the save
     * has to have landed before the process can die. False for a swipe of the mini player, which
     * calls this on the UI thread: blocking it here for the same write would freeze the swipe
     * gesture, so the save is launched on the service's own scope instead, and initQueue joins it
     * before its next read.
     */
    fun deInitQueue(waitForSave: Boolean = true) {
        Log.i(TAG, "+deInitQueue()")
        val pos = activePlayer.currentPosition
        // Null when the player holds nothing, which is most runs that never pressed play.
        val playerSongId = activePlayer.currentMediaItem?.mediaId
        queueBoard.shutdown()
        if (dataStore.get(PersistentQueueKey, true)) {
            if (waitForSave) {
                // Waited for, but not for good. This is onDestroy, on the main thread, and the
                // save is a write: with the database not answering, as on 6 Oct 2026, the wait
                // had no end. The process then lives on with its main thread parked here, and
                // opening the app again shows its splash screen until it is force stopped. On
                // the scope that outlives the service, so a save that is only slow still lands.
                // Given up when it is not in by then: the place in the queue, if the process
                // dies before it is.
                val save = lastingScope.launch { saveQueueToDisk(pos, playerSongId) }
                if (!save.joinWithin(QUEUE_SAVE_WAIT_MS)) {
                    Log.w(TAG, "The queue save was not in after $QUEUE_SAVE_WAIT_MS ms, teardown goes on without it")
                }
            } else {
                pendingQueueSave = scope.launch(Dispatchers.IO) {
                    saveQueueToDisk(pos, playerSongId)
                }
            }
        }
        // do not replace the object. Can lead to entire queue being deleted even though it is supposed to be saved already
        qbInit.value = false
        Log.i(TAG, "-deInitQueue()")
    }

    suspend fun saveQueueToDisk(currentPosition: Long, playerSongId: String?) {
        val data = queueBoard.getAllQueues()
        // An empty board is ordinary: a fresh install that has been opened and browsed but never
        // played has one, and so does anybody who has deleted every saved queue from the sheet.
        // last() on it throws, and this ran inside deInitQueue's runBlocking during onDestroy, so
        // the throw took out the rest of teardown with it and leaked the session and the player.
        //
        // The early return rather than lastOrNull(), which also stops the throw but is a trap:
        // updateAllQueues ends in nukeAliens(ids), and DELETE ... WHERE id NOT IN () matches every
        // row, so writing an empty list wipes every saved queue. The service can be destroyed
        // before initQueue has read them back, and that window is exactly where the quiet version
        // of this bug would delete somebody's queues instead of crashing.
        if (data.isEmpty()) return
        // Only when the player is holding this queue on its current song. A run that never loaded
        // it has an empty player at 0, and writing that sent the next resume back to the start.
        val last = data.last()
        last.lastSongPos = ResumePoint.onStop(
            playerSongId, last.queue.getOrNull(last.queuePos)?.id, currentPosition, last.lastSongPos
        )
        database.updateAllQueues(data)
    }


// Audio playback

    /**
     * Audio offload as the developer setting has it, unless spatial audio or Fade between tracks
     * is on, neither of which works under it. Called on the main thread whenever any of the three
     * changes.
     */
    private fun applyOffload() {
        val needsOffloadOff = spatialUpmixProcessor.enabled || binauralProcessor.enabled || transitionFade.enabled
        exoPlayer.setOffloadEnabled(!needsOffloadOff && dataStore.get(AudioOffloadKey, false))
    }

    private fun openAudioEffectSession() {
        // Not while the upmix is on. Android disables spatialisation for any track that has
        // effects attached to its session, and this broadcast is the invitation for the system
        // equaliser, and on this phone One UI's own processing, to attach. With it sent, six
        // channels go out and nothing renders them: dumpsys reports channelMask 0x3f and
        // isSpatialized false, the system folds them straight back to stereo, and the round trip
        // works out at 0.93L + 0.07R, which is audibly nothing.
        //
        // So the two are mutually exclusive by the platform's rules, not by choice here: the
        // system equaliser, or a spatialiser that will actually render. Turning the upmix on
        // picks the second.
        if (spatialUpmixProcessor.enabled) return
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, exoPlayer.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, exoPlayer.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    /**
     * The player's own stream client and its calls, one for the service: a network change clears
     * its connections, and the stall watch cancels its calls. The cast server makes its own.
     */
    private val playerStreamClient by lazy { StreamFamily.streamClient(YouTube.proxy) }
    private val playerCalls by lazy { StreamFamily.Calls(playerStreamClient) }

    private fun createCacheDataSource(
        calls: StreamFamily.Calls = StreamFamily.Calls(StreamFamily.streamClient(YouTube.proxy)),
    ): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            // A connection that brings the song in slower than it plays is given
                            // up and asked again on a fresh one: see StreamPace.
                            PaceGuardDataSource.Factory(
                                // A sabr: address to the SABR reader, any other as before: over the
                                // family the address was issued to, and a web client's with its page's
                                // headers, as its check was made (see StreamFamily.Calls). Inside the
                                // guard, so that the stall watch hears of SABR's bytes and its wait for
                                // a first byte too (StreamProgress). The guard only meters a stream
                                // server's https address, never a sabr: one (StreamPace.isStream), so
                                // a SABR stream paced by the player is never cut for its pace. The
                                // stall watch's cut of these calls ends a SABR read of them too.
                                SabrOrHttp.Factory(OkHttpDataSource.Factory(calls), cutWith = calls),
                                // The player's own connections tell the stall watch how long each
                                // waits for its first byte: see StreamProgress.
                                firstBytes = calls === playerCalls,
                            )
                        )
                    )
                    .setCacheWriteDataSinkFactory(
                        HybridCacheDataSinkFactory(playerCache) { dataSpec ->
                            val isLocal = queueBoard.getCurrentQueue()?.findSong(dataSpec.key ?: "")?.isLocal == true
                            Log.d(TAG, "SONG CACHE: ${!isLocal}")
                            !isLocal
                        }
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * The way the player gets to a song's bytes from its id: its download, the cache, or the
     * stream. A lyrics video cuts its sound out of the song the same way (LyricsVideo).
     */
    fun songSource(): DataSource.Factory = createDataSourceFactory(forPlayer = false)

    /**
     * [forPlayer] false for a lyrics clip: its reads go through calls of their own, which the
     * stall watch never cuts, and its resolves are not taken for the player's, which pause the
     * watch. Shared, a clip's reads were cancelled by a stall of the song, and a clip resolving
     * the current song held the watch off.
     */
    private fun createDataSourceFactory(forPlayer: Boolean = true): DataSource.Factory {
        val resolver = SongResolver(streamAddresses)
        val calls = if (forPlayer) playerCalls else StreamFamily.Calls(playerStreamClient)
        return ResolvingDataSource.Factory(createCacheDataSource(calls)) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            // What the song was resolved to, and whether a resolve of it is out: see StallWatch.
            if (forPlayer) resolvingSongs.merge(mediaId, 1, Int::plus)
            val resolved = try {
                resolver.resolve(mediaId, dataSpec.position, dataSpec.length)
            } finally {
                if (forPlayer) resolvingSongs.merge(mediaId, -1) { a, b -> (a + b).takeIf { it > 0 } }
            }
            if (forPlayer) streamedSongs[mediaId] = resolved is ResolvedSong.KnownStream || resolved is ResolvedSong.NewStream
            when (resolved) {
                is ResolvedSong.LocalFile -> dataSpec.withUri(resolved.file.toUri())
                is ResolvedSong.Downloaded -> dataSpec.withUri(resolved.file.uri)
                ResolvedSong.Cached -> dataSpec
                is ResolvedSong.KnownStream -> dataSpec.withUri(resolved.url.toUri())
                // Asked for whole, from where the player is to the song's end, as an address already
                // known is one line up. It used to be the first 512 KB only. The player took those
                // for the whole file, and the song went on because the cut fell inside an audio frame:
                // the frame came up short, the load failed and was made again, and that second request
                // ran to the end. Where the cut fell exactly between two frames nothing was short, the
                // file simply ended, and so did the song, at about thirty seconds and without an error.
                // The same songs every time, about one in three hundred. See WholeStreamRequestTest.
                is ResolvedSong.NewStream -> dataSpec.withUri(resolved.data.streamUrl.toUri())
            }
        }
    }

    /**
     * Decides where a song's bytes come from: a local file, a download, the caches, or a stream
     * from YouTube, fetched when nothing nearer has the song.
     *
     * This was the body of the player's data source, and the player still asks it every time it
     * opens a piece of a song. It came out so that the cast server asks the very same questions
     * (ServiceCastSource): a song cast to a TV is found, upgraded, recovered and cached exactly as
     * one played on the phone.
     *
     * The player's resolver is given the service's own [streamAddresses], so an address the player
     * failed on is forgotten for it as before (onPlayerError). The cast server's keeps its own, so
     * its resolves never disturb the player's.
     */
    /**
     * For the stall watch, the player's songs only: whether each was last resolved to a stream
     * (not a local file, a download or the cache), and the ones whose resolve is out.
     */
    private val streamedSongs = ConcurrentHashMap<String, Boolean>()
    private val resolvingSongs = ConcurrentHashMap<String, Int>()

    inner class SongResolver(private val addresses: StreamAddresses = StreamAddresses()) {
        /**
         * What each address kept in [addresses] streams: a receiver is told the type before it
         * asks for a byte. Concurrent because the cast server resolves on several threads at once.
         */
        private val formats = ConcurrentHashMap<String, PlayerResponse.StreamingData.Format>()

        /** Drops the address kept for [mediaId], after it was refused, so the next resolve asks for one afresh. */
        fun forgetStream(mediaId: String) {
            addresses.playerFailedOn(mediaId)
        }

        /** Drops [address] if it is still the one kept for [mediaId]: see StreamAddresses.refused. */
        fun forgetRefused(mediaId: String, address: String): Boolean = addresses.refused(mediaId, address)

        /**
         * Where the bytes of [mediaId] come from for a read of [length] bytes (C.LENGTH_UNSET for
         * the rest of the song) from [position]. Blocking. Throws PlaybackException when the song
         * cannot be had.
         */
        fun resolve(mediaId: String, position: Long, length: Long): ResolvedSong {
            Log.i(TAG, "PLAYING: song id = $mediaId")

            val queued = queueBoard.getCurrentQueue()?.findSong(mediaId)
            // in the case of resumption, queueBoard may not be ready yet
            //
            // Then the song's own row says where its file is. A database that does not answer is
            // taken as having no row, and the song is played from the cache or streamed, as one
            // the library has never seen. Given up for that one load: a file in a download folder
            // is not looked for, and a local file, which is nowhere else, fails as an unknown
            // video does instead of holding the player at buffering for good.
            val stored = if (queued == null) database.readOrNull { songRow(mediaId) } else null
            val localPath = queued?.localPath ?: stored?.localPath
            val isLocal = queued?.isLocal ?: (stored?.isLocal == true)
            // local song
            if (localPath != null) {
                if (isLocal) {
                    Log.i(TAG, "PLAYING: local song")
                    val file = File(localPath)
                    if (!file.exists()) {
                        throw PlaybackException(
                            "File not found",
                            Throwable(),
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }

                    return ResolvedSong.LocalFile(file)
                } else {
                    // A file deleted outside the app is still in the list until the next scan, so
                    // it is checked, and a song whose file has gone is played from the cache or
                    // streamed instead of failing.
                    val isDownloadNew = downloadUtil.localMgr.isExists(mediaId)?.takeIf { it.exists() }
                    isDownloadNew?.let {
                        Log.i(TAG, "PLAYING: Custom downloaded song")
                        return ResolvedSong.Downloaded(it)
                    }
                }
            }

            val isDownload =
                downloadCache.isCached(mediaId, position, if (length >= 0) length else 1)
            val isCache = playerCache.isCached(mediaId, position, CHUNK_LENGTH)
            // A copy fetched at a lower setting is fetched again, once, at the higher one. Only
            // upwards: re-fetching to make something worse would spend data to lose quality, and
            // the bytes are already here. Downloads are left alone whatever the setting says,
            // because someone who downloaded a song asked for it to work offline, and quietly
            // streaming instead would break the one thing they wanted.
            //
            // Only from the start of the song. Mid-song, a new stream would fill the gaps of a
            // cache entry that holds the old one, two encodings in one file.
            //
            // And only with a network. Offline the fetch cannot succeed, and the copy here is the
            // only way the song plays at all.
            val staleQuality = isCache && !isDownload && position == 0L &&
                    isNetworkConnected.value && shouldUpgradeCached(mediaId)
            // Part of the song cached, met at its start while online. The stream is resolved before
            // the copy is used, and if it is not the stream the copy came from, the copy goes: the
            // rest of the song used to be fetched as whatever stream came back and written on from
            // where the copy stopped, two encodings in one file. Offline the copy plays as it is.
            val partialCopy = !isDownload && position == 0L &&
                    isNetworkConnected.value && playerCache.holdsPartFromStart(mediaId)
            if ((isDownload || isCache) && !staleQuality && !partialCopy) {
                Log.i(TAG, "PLAYING: remote song (cache = ${isCache}, download = ${isDownload})")
                offloadScope.launch { recoverSong(mediaId) }
                return ResolvedSong.Cached
            }

            // The address the song was given earlier, unless the player has failed on it since:
            // onPlayerError forgets it, and the try after that asks afresh below.
            addresses.of(mediaId, System.currentTimeMillis())?.takeIf { !staleQuality }?.let { address ->
                formats[mediaId]?.let { format ->
                    Log.i(TAG, "PLAYING: remote song (temp cache)")
                    offloadScope.launch { recoverSong(mediaId) }
                    return ResolvedSong.KnownStream(address, format)
                }
            }

            Log.i(TAG, "PLAYING: remote song (online fetch)")

            // The whole resolve within RouteTimes.RESOLVE_MS of waiting: each step has its own
            // limit, and they used to add up to minutes. Past it the song fails as a timeout,
            // which the player tries again quietly (see onPlayerError).
            // The routes the player failed on lately go last, and mid-song the stream the player
            // has bytes of is taken when offered: see YTPlayerUtils.RouteWish.
            val wish = YTPlayerUtils.RouteWish(
                mediaId,
                songRoutes.avoid(mediaId, SystemClock.elapsedRealtime()),
                if (position > 0L) runCatching { database.readOrNull { formatRow(mediaId) } }.getOrNull()
                    ?.let { row -> row.itag to row.contentLength } else null,
                // Refused in the player under the visitorData the app has now: see SongRoutes.refused.
                refusedByPlayer = songRoutes.refused(mediaId, SystemClock.elapsedRealtime(), YouTube.visitorData.orEmpty()),
            )
            // The answers of this walk come after this: the developer's "Stream over SABR only" starts from one of them.
            val resolveBegan = System.currentTimeMillis()
            val played = runBlocking(Dispatchers.IO) {
                try {
                    withinResolveTime(context = wish) { standIns.playbackData(mediaId, askedAgain = stoppedByError && stoppedOn == mediaId) }
                } catch (e: StepTimedOut) {
                    Log.w(TAG, "PLAYING: [$mediaId] no stream within ${StepLog.limit(e.ms)}, given up as a timeout")
                    Result.failure(e)
                }
            }.getOrElse { throwable ->
                // The upgrade could not be had (the network went, or the video is gone from
                // YouTube), but the copy it was meant to replace is still here. Play that rather
                // than fail a song that plays fine, and leave its row as it is, so the upgrade is
                // tried again next time.
                if (staleQuality || partialCopy) {
                    Log.i(TAG, "PLAYING: remote song (cache kept, the new stream could not be fetched)", ErrorText.forLog(throwable))
                    return ResolvedSong.Cached
                }
                val error = when (throwable) {
                    is PlaybackException -> throwable

                    is ConnectException, is UnknownHostException -> PlaybackException(
                        getString(R.string.error_no_internet),
                        throwable,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    )

                    is SocketTimeoutException -> PlaybackException(
                        getString(R.string.error_timeout),
                        throwable,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    )

                    else -> PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
                // Mid-song, a fetch that got no answer (a Wi-Fi blip, a lift, a timeout) goes to
                // the player as a failure media3 tries again on its own while the buffer plays,
                // so a short drop is never heard. See Recovery.resolveFailed.
                throw Recovery.resolveFailed(position, throwable, error)
            }
            val playbackData = played.data
            val format = playbackData.format
            // The stream is another upload's, a stand-in for a song whose own id is gone.
            val standIn = played.from != mediaId

            // Further into a song with parts of it cached from another upload: the player has
            // that upload's index in hand, and what would be fetched now is not what it points
            // into. Nothing can be carried on. The parts go and this load fails, so that the
            // next start of the song takes it from this stream alone. Only when the format row
            // says so beyond doubt, the same itag and another length: a row that cannot be read,
            // or another quality of the same upload, is left as it has always been.
            if (position > 0L) {
                val part = runCatching { database.readOrNull { formatRow(mediaId) } }.getOrNull()
                if (anotherUpload(part?.itag, part?.contentLength, format.itag, format.contentLengthOrZero())) {
                    Log.w(TAG, "PLAYING: remote song (what is cached of it is another upload's, dropped; this load fails)")
                    runCatching { playerCache.removeResource(mediaId) }
                        .onFailure { Log.w(TAG, "Could not drop the cached parts of $mediaId", it) }
                    throw PlaybackException(
                        getString(R.string.error_cached_part_stale),
                        null,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    )
                }
            }

            // The lower-quality copy goes now that the new stream is in hand, not before, so a
            // failed fetch never costs the copy that plays offline. It has to go at all because
            // the cache serves by the song's id whatever address comes back from here: the old
            // bytes kept playing while the row below was rewritten to the new quality, and the
            // song never counted as stale again.
            // And only for a stream that answered its status check: the last fallback client's is
            // taken unchecked, and one of those failing partway would have cost the offline copy.
            // Without the check the cached copy plays, as if the upgrade had never been asked
            // for, and nothing about the song is rewritten.
            // A copy that stops partway is not kept this way: carried on from with a stream other
            // than its own, it would hold two encodings. It goes below instead.
            // Nor is a whole copy of the song given up for a stand-in's stream, however sure the
            // match: the copy is the song, and the stand-in only very likely is.
            if (staleQuality && (!playbackData.validated || standIn) && !partialCopy) {
                Log.i(TAG, "PLAYING: remote song (cache kept, the new stream was ${if (standIn) "a stand-in's" else "not checked"})")
                return ResolvedSong.Cached
            }
            // The song from its start with nothing of it cached there, and parts further in: the
            // cache gives up a song's parts one at a time, the oldest first. They are held to the
            // test a part from the start is, or the song would be fetched from this stream as far
            // as them and carried on in theirs.
            val laterParts = !staleQuality && !partialCopy && position == 0L &&
                    runCatching { playerCache.getCachedSpans(mediaId).isNotEmpty() }.getOrDefault(false)
            if (staleQuality) {
                runCatching { playerCache.removeResource(mediaId) }
                    .onFailure { Log.w(TAG, "Could not drop the lower-quality copy of $mediaId", it) }
            } else if (partialCopy || laterParts) {
                // The format row was written by the fetch that filled the copy. A database that
                // does not answer is taken as a row that does not match: the part is given up and
                // the song fetched from its start, since nothing says the part is of this stream.
                // The same itag is not enough since a song can be played from a stand-in: that is
                // another upload, and its bytes are not the copy's. See sameStream.
                val copy = runCatching { database.readOrNull { formatRow(mediaId) } }.getOrNull()
                if (!sameStream(copy?.itag, copy?.contentLength, format.itag, format.contentLengthOrZero())) {
                    Log.i(TAG, "PLAYING: remote song (partial copy was itag ${copy?.itag}, ${copy?.contentLength} bytes, the stream is ${format.itag}, ${format.contentLengthOrZero()} bytes, starting over)")
                    runCatching { playerCache.removeResource(mediaId) }
                        .onFailure { Log.w(TAG, "Could not drop the partial copy of $mediaId", it) }
                }
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
                        qualityTier = audioQualityNow().name,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }
            offloadScope.launch { recoverSong(mediaId, playbackData) }

            // The developer's "Stream over SABR only": the same answer over SABR instead of its
            // direct address, the walk otherwise as it always is (playback/sabr/SabrOver).
            val streamed = if (BuildConfig.DEBUG && Unreleased.SABR && YTPlayerUtils.sabrOnly) {
                // From the answer for the upload played, paced by the song the player asked for.
                SabrOver.over(played.from, playbackData, since = resolveBegan, clockId = mediaId) ?: playbackData
            } else {
                playbackData
            }
            val streamUrl = streamed.streamUrl

            // Kept whether or not it answered its check. The rest of the song is fetched from it,
            // and the player failing on it is what takes it out again.
            // What is kept is the stream played, the SABR one when the switch made one.
            addresses.keep(
                mediaId, streamUrl, System.currentTimeMillis() + (streamed.streamExpiresInSeconds * 1000L),
                // Under its own id only: a stand-in's route is another song's.
                client = streamed.client.takeIf { !standIn },
                checked = streamed.validated,
            )
            formats[mediaId] = format
            return ResolvedSong.NewStream(streamed)
        }
    }

    /**
     * Starts the server a receiver fetches the queue's songs from, or returns the one already
     * running.
     *
     * It listens on the phone's address on Wi-Fi, serves only songs in the current queue, finds
     * them through a [SongResolver] of its own, and stops by itself when that Wi-Fi goes. Debug
     * builds try port 47800 first and also listen on loopback there, so one adb forward covers
     * every test run. Null with casting off, without Wi-Fi, or when no socket can be opened.
     */
    fun startCastServer(): CastServer? {
        if (!Unreleased.CASTING) return null
        synchronized(castLock) {
            castServer?.let { running ->
                if (running.isRunning) return running
                stopCastServerLocked()
            }
            val wifi = CastNetwork.wifi(connectivityManager)
            if (wifi == null) {
                Log.i(TAG, "Casting needs Wi-Fi")
                return null
            }
            val source = ServiceCastSource(
                resolver = SongResolver().also { castResolver = it },
                cacheSource = createCacheDataSource(),
                downloadCache = downloadCache,
                playerCache = playerCache,
                contentResolver = contentResolver,
                // The bounded read the player's own resolver uses: a database that does not answer is a row that is not there.
                storedFormat = { id -> database.readOrNull { formatRow(id) } },
                // As the player fetches a stream: over the family the address was issued to, and a
                // web client's with its page's headers. See StreamFamily.Calls.
                directSource = DefaultDataSource.Factory(
                    this,
                    SabrOrHttp.Factory(OkHttpDataSource.Factory(StreamFamily.Calls(StreamFamily.streamClient(YouTube.proxy)))),
                ),
                isLocalFile = { id -> queueBoard.getCurrentQueue()?.findSong(id)?.isLocal == true },
                fetchAac = { id ->
                    runBlocking(Dispatchers.IO) {
                        YTPlayerUtils.playerResponseForPlayback(
                            id,
                            audioQuality = audioQualityNow(),
                            connectivityManager = connectivityManager,
                            preferredMime = CastFormats.AAC_MIME,
                        )
                    }.getOrThrow()
                },
            )
            val server = CastServer(
                source,
                wifi.address,
                isServable = { id -> queueBoard.getCurrentQueue()?.findSong(id) != null },
                port = if (BuildConfig.DEBUG) CastServer.DEBUG_PORT else 0,
                alsoLoopback = BuildConfig.DEBUG,
            )
            try {
                server.start()
            } catch (e: IOException) {
                Log.w(TAG, "The cast server could not start", e)
                return null
            }
            castServer = server
            castNetworkWatch = CastNetwork.watch(connectivityManager, wifi) { stopCastServer(server) }
            return server
        }
    }

    /**
     * Stops the cast server, which ends every song it is sending. With [only], stops it only if it
     * is still that one, so a late word about an old network cannot stop a newer server.
     */
    fun stopCastServer(only: CastServer? = null) {
        synchronized(castLock) {
            if (only != null && castServer !== only) return
            stopCastServerLocked()
        }
    }

    private fun stopCastServerLocked() {
        castNetworkWatch?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        castNetworkWatch = null
        castServer?.stop()
        castServer = null
    }

    /**
     * Hands playback to a receiver. The queue, the song, where it is and whether it plays go to a new
     * [RemotePlayer]; the screens, the notification and the queue board follow it through [player];
     * and the phone's own player stops. It plays there if [play], or if the phone was playing when
     * [play] is null. Null with casting off. Main thread.
     *
     * The phone's player is stopped and emptied, not just paused. A player holding a song keeps the
     * rest of that song's cache locked, so the bytes the server reads for the receiver would not be
     * cached (seen on the emulator, 27 Sep). Emptying it also closes the play in progress there as it
     * stands, through the analytics listener that stays on it; with no transition behind it, it is
     * recorded as a stop.
     */
    internal fun handOverTo(link: ReceiverLink, server: CastServer, play: Boolean?, onLost: (ReceiverStatus) -> Unit): RemotePlayer? {
        val routed = routedPlayer ?: return null
        val exo = exoPlayer
        // After a cold start the saved queue waits unloaded until something plays, and a receiver
        // handed the empty player got nothing: no song there, and a replay button on the phone.
        // Loaded here instead, at the song and place it was left, as a play would load it.
        if (exo.mediaItemCount == 0) queueBoard.setCurrQueue()
        val remote = RemotePlayer(
            Looper.getMainLooper(),
            link,
            server,
            items = List(exo.mediaItemCount) { exo.getMediaItemAt(it) },
            startIndex = exo.currentMediaItemIndex,
            startMs = exo.currentPosition,
            playWhenReady = play ?: exo.playWhenReady,
            repeatMode = exo.repeatMode,
            shuffle = exo.shuffleModeEnabled,
            onLost = onLost,
        )
        exo.removeListener(this)
        isCasting = true
        checkpointJob?.cancel()
        // The play in progress on the phone is closed by its own stats when the player is emptied
        // below, as a stop: nothing has to be told to the play book here.
        closeAudioEffectSession()
        headTracking.stop(glideHome = false)
        proximityVolume.stop()
        routed.routeTo(remote)
        remote.addListener(this)
        exo.stop()
        exo.clearMediaItems()
        remote.prepare()
        return remote
    }

    /**
     * Takes playback back from [remote]: the phone's player gets the list as the cast left it (the
     * queue board's changes went to the receiver), the song and the position the receiver had
     * reached, and plays if [play]. Main thread.
     */
    internal fun takeBack(remote: RemotePlayer, play: Boolean) {
        val routed = routedPlayer ?: return
        val exo = exoPlayer
        val items = List(remote.mediaItemCount) { remote.getMediaItemAt(it) }
        val index = remote.currentMediaItemIndex
        val position = remote.positionNow
        remote.removeListener(this)
        // Loaded before anything is routed to it, so the screens and the session go straight from
        // the receiver's song to the same song here, never through an empty player.
        if (items.isNotEmpty()) {
            exo.setMediaItems(items, index.coerceIn(items.indices), position)
            exo.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(items.size))
            exo.prepare()
        }
        exo.repeatMode = remote.repeatMode
        exo.shuffleModeEnabled = remote.shuffleModeEnabled
        exo.playWhenReady = play
        isCasting = false
        routed.routeTo(exo)
        exo.addListener(this)
        remote.release()
        // This service missed the new list going in, so it starts on the song as a new queue would.
        noteTransition(exo.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        currentMediaMetadata.value = activePlayer.currentMetadata
        queueBoard.setCurrQueuePosIndex(activePlayer.currentMediaItemIndex)
        updateWidget()
        updateNotification()
    }

    /** What the setting says right now. Read live, because it can change between songs. */
    private fun audioQualityNow(): AudioQuality {
        val quality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
        return quality
    }

    /**
     * Whether the cached copy was fetched at a lower setting than the one now in force.
     *
     * Null means it was cached before any of this was recorded, which is treated as no and left
     * alone: re-fetching everybody's entire cache the first time they update is not a reasonable
     * thing to do to someone's data allowance.
     *
     * Delegates to isStaleQualityTier (DownloadUtil.kt), the same rank rule a download's own
     * resolver uses to decide whether to copy a cached file or fetch fresh.
     */
    private fun shouldUpgradeCached(mediaId: String): Boolean {
        // A database that does not answer is taken as a copy of unknown quality, so the copy plays
        // as it is. Given up: the better copy, until the song is next started with the database
        // answering.
        val stored = runCatching { database.readOrNull { formatRow(mediaId) } }.getOrNull()?.qualityTier
        return isStaleQualityTier(stored, audioQualityNow())
    }

    private fun createRenderersFactory(gaplessOffloadAllowed: Boolean): DefaultRenderersFactory {
        if (ENABLE_FFMETADATAEX) {
            return object : NextRenderersFactory(this@MusicService) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        // The parameter was being taken and thrown away, so the chain ran in 16
                        // bit whatever anyone asked for. GainAudioProcessor has handled
                        // ENCODING_PCM_FLOAT since it was written; it had simply never been given
                        // any, and every normalised track was rounded back to 16 bit after the
                        // multiply for no reason.
                        .setEnableFloatOutput(enableFloatOutput)
                        .setAudioTrackBufferSizeProvider(
                            DefaultAudioTrackBufferSizeProvider.Builder()
                                .setMinPcmBufferDurationUs(
                                    if (lowLatencyAudio) LOW_LATENCY_BUFFER_US else DEFAULT_BUFFER_US
                                )
                                .build()
                        )
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                // Upmix last: gain works on the two channels it was written for,
                                // and anything derived from them is derived from the gained
                                // result rather than being gained separately. Only one of the two
                                // spatial stages is ever on. The equalizer comes straight after the
                                // gain, so spatial audio is handed the curve the listener chose.
                                arrayOf(gainProcessor, equalizerProcessor, binauralProcessor, spatialUpmixProcessor),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                        .let { LevelTapAudioSink(it, levelTap) }
                }
            }
                .setEnableDecoderFallback(true)
                .setEnableAudioFloatOutput(highPrecisionAudio)
                .setExtensionRendererMode(audioDecoder)
        } else {
            return object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        // The parameter was being taken and thrown away, so the chain ran in 16
                        // bit whatever anyone asked for. GainAudioProcessor has handled
                        // ENCODING_PCM_FLOAT since it was written; it had simply never been given
                        // any, and every normalised track was rounded back to 16 bit after the
                        // multiply for no reason.
                        .setEnableFloatOutput(enableFloatOutput)
                        .setAudioTrackBufferSizeProvider(
                            DefaultAudioTrackBufferSizeProvider.Builder()
                                .setMinPcmBufferDurationUs(
                                    if (lowLatencyAudio) LOW_LATENCY_BUFFER_US else DEFAULT_BUFFER_US
                                )
                                .build()
                        )
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                // Upmix last: gain works on the two channels it was written for,
                                // and anything derived from them is derived from the gained
                                // result rather than being gained separately. Only one of the two
                                // spatial stages is ever on. The equalizer comes straight after the
                                // gain, so spatial audio is handed the curve the listener chose.
                                arrayOf(gainProcessor, equalizerProcessor, binauralProcessor, spatialUpmixProcessor),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                        .let { LevelTapAudioSink(it, levelTap) }
                }
            }.setEnableAudioFloatOutput(highPrecisionAudio)
        }
    }


// Misc

    /**
     * Whether the queue sheet folds the far end of the queue away, said again for the queue now
     * current: on for a radio or a mix with the setting on, off for anything else.
     *
     * Only the pass used to say, and no pass runs on a queue just loaded, so after a switch from a
     * radio to a playlist the playlist stayed folded until its second song, and a radio came back
     * unfolded. Called on every song change, when the setting changes and whenever a queue is
     * loaded into the player (QueueBoard.setCurrQueue). Main thread.
     */
    fun refreshAdaptiveTail() {
        adaptiveTailActive.value = adaptiveQueueMode.isOn && queueBoard.getCurrentQueue()?.playlistId != null
    }

    /**
     * Something in the current run changed: the queue sheet hears of it, and the file is written two
     * seconds after the last such change. Main thread.
     */
    private fun adaptiveChanged() {
        reachedInQueue.value = heard.reached.toSet()
        publishAdaptive()
        if (!persistentQueue) return
        adaptiveMemoryWrite?.cancel()
        adaptiveMemoryWrite = scope.launch {
            delay(ADAPTIVE_MEMORY_WAIT_MS)
            writeAdaptiveMemory()
        }
    }

    /**
     * Writes the radios' memory: the text made here, on the main thread that owns it, the file
     * written on the scope that outlives the service. Not while listening history is paused, for the
     * skips and keeps: what the queue played is the queue's own state and is written either way.
     */
    private fun writeAdaptiveMemory() {
        adaptiveMemoryWrite = null
        val text = AdaptiveMemory.encode(heard.saved(), withEvidence = !listenHistoryPaused)
        val file = File(filesDir, AdaptiveMemory.FILE)
        lastingScope.launch(adaptiveMemoryDispatcher) {
            runCatching {
                val atomic = AtomicFile(file)
                val out = atomic.startWrite()
                try {
                    out.write(text.toByteArray())
                    atomic.finishWrite(out)
                } catch (e: Exception) {
                    atomic.failWrite(out)
                    throw e
                }
            }.onFailure { Log.w(TAG, "Adaptive queue: could not save what the radios played", it) }
        }
    }

    /** What the last process saved of the radios, or nothing. Off the main thread. */
    private fun readAdaptiveMemory(): List<AdaptiveMemory.Run> = runCatching {
        val file = File(filesDir, AdaptiveMemory.FILE)
        if (!file.exists()) emptyList() else AdaptiveMemory.decode(String(AtomicFile(file).readFully()))
    }.onFailure { Log.w(TAG, "Adaptive queue: could not read what the radios played", it) }.getOrDefault(emptyList())

    /**
     * What a pass was asked for: [strong] reaches further, as after a seek forward or a like, for
     * the pass a release runs; [skip] next was pressed before half the song; [like] the song
     * playing was liked. [run] is the run it was asked in (AdaptiveQueue.Heard.runKey), and it
     * runs in no other: a queue switched to meanwhile has not been heard yet, and a pass carried
     * over to it judged the whole of it by its first song. [held] when the queue sheet held it
     * back: the songs it showed stay as they were seen, so the pass keeps the ordinary lock. Asks
     * for the same run that arrive within a pass's wait merge into one; one for another run takes
     * the place of the old.
     */
    private data class PassAsk(
        val run: String,
        val strong: Boolean,
        val skip: Boolean = false,
        val like: Boolean = false,
        val held: Boolean = false,
    ) {
        fun merge(other: PassAsk?) = if (other == null || other.run != run) this
            else PassAsk(run, strong || other.strong, skip || other.skip, like || other.like, held && other.held)
    }

    /** A pass turned away while the queue sheet was open. Main thread. */
    private var passWaiting: PassAsk? = null

    /** The queue sheet has closed: the pass it held back runs now, if its run is still the one playing. Main thread. */
    private fun askAgainAfterSheet() {
        if (queueSheetOpen) return
        val ask = passWaiting ?: return
        passWaiting = null
        if (ask.run != heard.runKey) {
            Log.d(TAG, "Adaptive queue: not now (the run changed)")
            return
        }
        replanQueueTail(strong = ask.strong, skip = ask.skip, like = ask.like, held = true)
    }

    /** Every pass waiting, held back by the sheet or being worked out, given up. Main thread. */
    private fun dropPasses() {
        passJob?.cancel()
        passJob = null
        passAsk = null
        passWaiting = null
    }

    /** Whether the song that just started was moved to by skip on error, which says nothing about the song left. Main thread. */
    private var transitionFromError = false

    /** Set by skipOnError just before it moves on, read and cleared by the transition that follows. */
    @Volatile private var skippingOnError = false

    /**
     * Drops songs further down the queue that no longer suit what is being played.
     *
     * Runs on every transition and almost always does nothing. See [AdaptiveQueue] for the rules;
     * the short version is that the next few songs are untouchable, order is never rearranged, and
     * at most a fraction of the tail can go at once.
     *
     * The session's character is read from what this queue has played, in the order it was heard
     * (AdaptiveQueue.Heard), rather than from the database: those songs are exactly "what you have
     * been listening to in this sitting", and reading them costs nothing on a thread that must not
     * block.
     *
     * With Unreleased.ADAPTIVE_PASS the pass that also puts fresh picks in runs instead, a second
     * after the last ask ([askPass]); without it, the one below, which only takes songs out, with
     * what it learns from skips, likes and plain runs.
     */
    /**
     * @param strong the listener just said something clear, so reach further and lock less.
     * @param skip next was pressed before half the song (AdaptiveQueue.Leave.SKIPPED).
     * @param like the song playing was just liked.
     * @param held the queue sheet held this back, and showed the songs it now locks as settled.
     */
    private fun replanQueueTail(strong: Boolean = false, skip: Boolean = false, like: Boolean = false, held: Boolean = false) {
        if (!adaptiveQueueMode.isOn) return
        val q = queueBoard.getCurrentQueue() ?: return
        // A radio tail is one the app chose; a playlist, an album or a list made by hand is one
        // the listener chose, and it plays as chosen whatever the setting says. Before 0.12 "Any
        // queue" planned those too.
        if (q.playlistId == null) {
            Log.d(TAG, "Adaptive queue: not now (not a radio)")
            return
        }
        // While casting the phone does not know where the receiver was in a song, so it cannot
        // tell a skip from a song heard: nothing is planned until the music is back here.
        if (isCasting) {
            Log.d(TAG, "Adaptive queue: not now (casting)")
            return
        }
        val ask = PassAsk(heard.runKey, strong, skip, like, held)
        // Not while somebody is reading it. A list that rearranges under a finger is the one
        // thing this must never do. Asked for again when the sheet closes.
        if (queueSheetOpen) {
            passWaiting = ask.merge(passWaiting)
            Log.d(TAG, "Adaptive queue: not now (sheet open)")
            return
        }
        if (Unreleased.ADAPTIVE_PASS) {
            askPass(ask)
            return
        }
        // A queue that is not the run being heard, or of which only the song it began on has
        // been, is not planned: see Heard.begun. A like is the listener speaking, and is answered.
        if (AdaptiveQueue.runKey(q.id, q.runId) != heard.runKey || (!like && !heard.begun)) return
        // Skip on error moving past a song that failed is not the listener reaching further.
        val reach = strong && (like || !transitionFromError)

        val current = activePlayer.currentMediaItemIndex
        val count = activePlayer.mediaItemCount
        // Held back by the open sheet, the four songs it showed as settled stay so after it closes.
        val locked = if (reach && !held) AdaptiveQueue.STRONG_LOCKED else AdaptiveQueue.LOCKED
        // From the furthest song this queue has reached, not from the one playing: after going
        // back, the songs in between were heard and the few after them seen. See settledThrough.
        val ids = (0 until count).map { i -> runCatching { activePlayer.getMediaItemAt(i).mediaId }.getOrNull() }
        val settled = AdaptiveQueue.settledThrough(current, ids, { it }, heard.reached)
        val start = AdaptiveQueue.tailStart(settled, count, locked) ?: return

        // What was heard last, newest first. The places before the song playing are that only
        // while playback moves forward: after going back to the first song they were nothing.
        val recent = heard.titles(TagFit.WINDOW).filterNotNull()
        val context = TagFit.context(recent.map { SongTags.of(it) })
        if (!context.known) return

        // Never a song the listener put in or moved by hand: that place is theirs.
        val tail = (start until count).mapNotNull { i ->
            runCatching {
                val item = activePlayer.getMediaItemAt(i)
                Triple(i, item.mediaMetadata.title?.toString(), item.metadata?.artists?.map { it.name }.orEmpty())
                    .takeIf { item.mediaId !in heard.byHand }
            }.getOrNull()
        }
        if (tail.isEmpty()) return

        val turnedDown = heard.turnedDown()
        val plainRun = AdaptiveQueue.plainRun(heard.titles(AdaptiveQueue.PLAIN_RUN))
        val plan = AdaptiveQueue.plan(
            tail,
            { it.second },
            context,
            dropShare = if (reach) AdaptiveQueue.STRONG_DROP_SHARE else AdaptiveQueue.MAX_DROP_SHARE,
            artists = { it.third },
            turnedDown = turnedDown,
            plainRun = plainRun,
        )
        if (!plan.changed) return

        // Descending, so each removal cannot shift the index of the next one.
        var removed = 0
        plan.dropped.sortedByDescending { it.first }.forEach { (index, _) ->
            if (queueBoard.removeCurrentQueueSong(index)) {
                runCatching { activePlayer.removeMediaItem(index) }
                removed++
            }
        }
        hiddenTailChanged.value = hiddenTailChanged.value + 1
        Log.d(TAG, "Adaptive queue: dropped ${plan.dropped.size} of ${tail.size} upcoming, strong=$reach" +
            ", turned down ${turnedDown.artists.size} artists and ${turnedDown.songs.size} songs, plain run=$plainRun")
        // The queue says what it took out, as the pass that also puts picks in does.
        if (removed > 0) {
            heard.note = AdaptiveNote.Note(AdaptiveNote.Kind.DROPPED, removed, setAt = heard.played)
            adaptiveChanged()
        }
    }

    // ---- The pass that puts fresh picks in (Unreleased.ADAPTIVE_PASS): asked for on the main
    // thread, read and planned on the adaptive queue's own thread, applied on the main thread
    // again after checking that nothing it was planned for has moved.

    /** The adaptive queue's reads and its planning, one pass at a time, never on the player's thread. */
    private val adaptiveDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The pass waiting out its second or being worked out, and what it was asked for. Main thread. */
    private var passJob: Job? = null
    private var passAsk: PassAsk? = null

    /**
     * The taste, read at most every few minutes (TASTE_TTL_MS). Written on the adaptive dispatcher
     * only; the radio's top-up reads it on the main thread to leave bans and lasting dislikes out of
     * a new page.
     */
    @Volatile private var tasteCache: Pair<Long, QueueFit.Taste>? = null

    /** The related lists read in the current run, by seed, and which run they are for. Adaptive dispatcher only. */
    private var relatedRun: String? = null
    private val relatedCache = HashMap<String, AdaptivePass.Source>()

    /** Radio pages asked for in each run, by seed, and when the last was. Adaptive dispatcher only. */
    private val pagesAsked = HashMap<String, MutableSet<String>>()
    private val lastPageAt = HashMap<String, Long>()

    /** This run's picks still ahead, for the queue sheet's marks. */
    val adaptivePicks = MutableStateFlow<Set<String>>(emptySet())

    /** What the queue sheet says about the last change, null for nothing. */
    val adaptiveNote = MutableStateFlow<AdaptiveNote.Note?>(null)

    /**
     * Asks for a pass a second from now. A newer ask before it is done takes its place, with both
     * reasons, so a burst of skips makes one pass, a skip pass; a cancelled pass never applies.
     */
    private fun askPass(ask: PassAsk) {
        val merged = ask.merge(passAsk)
        passJob?.cancel()
        passAsk = merged
        passJob = scope.launch {
            try {
                delay(PASS_WAIT_MS)
                runPass(merged)
            } finally {
                if (passAsk === merged) passAsk = null
            }
        }
    }

    /**
     * What a pass was planned on, taken on the main thread. [uids] are the board's songs', place by
     * place, [playerUids] the player's: see AdaptiveEdit for why the two can differ.
     */
    private class PassSnapshot(
        val ask: PassAsk,
        val kind: AdaptivePass.Kind,
        val queueId: Long,
        val runId: Long,
        val view: AdaptiveQueue.View,
        val current: Int,
        val locked: Int,
        val uids: List<Double>,
        val playerUids: List<Double>,
        val tail: List<AdaptivePass.Place>,
        val head: List<MediaMetadata>,
        val queue: List<MediaMetadata>,
        val online: Boolean,
        val explicitHidden: Boolean,
        val now: Long,
    )

    private suspend fun runPass(ask: PassAsk) {
        val snap = adaptiveSnapshot(ask) ?: return
        val result = try {
            withContext(adaptiveDispatcher) { computePass(snap) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Adaptive queue: pass failed, queue left as it was", e)
            return
        }
        // A newer ask cancelled this one while it was being worked out: it never applies.
        currentCoroutineContext().ensureActive()
        applyPass(snap, result)
    }

    /** The tail and the run as they are now, or null when no pass should run. Main thread. */
    private fun adaptiveSnapshot(ask: PassAsk): PassSnapshot? {
        fun notNow(why: String): PassSnapshot? { Log.d(TAG, "Adaptive queue: not now ($why)"); return null }
        if (!adaptiveQueueMode.isOn) return notNow("off")
        if (isCasting) return notNow("casting")
        // Asked in a run that has given way to another since: that queue has not been heard yet.
        if (ask.run != heard.runKey) return notNow("the run changed")
        if (queueSheetOpen) {
            passWaiting = ask.merge(passWaiting)
            return notNow("sheet open")
        }
        val q = queueBoard.getCurrentQueue() ?: return null
        if (q.playlistId == null) return notNow("not a radio")
        if (activePlayer.currentMediaItem == null) return notNow("the player holds nothing")
        // A queue loaded without a song change has not been heard as a run yet.
        if (AdaptiveQueue.runKey(q.id, q.runId) != heard.runKey) return notNow("the run has not started")
        val current = activePlayer.currentMediaItemIndex
        val count = activePlayer.mediaItemCount
        val items = (0 until count).map { i -> runCatching { activePlayer.getMediaItemAt(i) }.getOrNull() }
        val songs = items.map { it?.metadata }
        // The player must be holding this queue, song for song: a list a car started is not it.
        val board = q.getCurrentQueueShuffled()
        if (songs.any { it == null } || board.size != count || board.indices.any { board[it].id != songs[it]?.id }) {
            return notNow("the player holds another list")
        }
        val view = heard.view()
        val kind = when {
            ask.like && view.likePending != null -> AdaptivePass.Kind.LIKE
            ask.skip -> AdaptivePass.Kind.SKIP
            else -> AdaptivePass.Kind.ORDINARY
        }
        // Only the song a queue began on has been heard: nothing is planned on that alone, unless
        // the listener liked it.
        if (kind != AdaptivePass.Kind.LIKE && !heard.begun) return notNow("the run has not started")
        // A pass the open sheet held back keeps the four songs it showed as settled.
        val locked = if (kind == AdaptivePass.Kind.ORDINARY || ask.held) AdaptiveQueue.LOCKED else AdaptiveQueue.STRONG_LOCKED
        // From the furthest song this run has reached, as the queue sheet folds it.
        val ids = items.map { it?.mediaId }
        val settled = AdaptiveQueue.settledThrough(current, ids, { it }, heard.reached)
        val start = AdaptiveQueue.tailStart(settled, count, locked) ?: return notNow("nothing past the lock")
        val all = songs.filterNotNull()
        return PassSnapshot(
            ask = ask, kind = kind, queueId = q.id, runId = q.runId, view = view, current = current, locked = locked,
            uids = board.map { it.composeUidWorkaround },
            playerUids = all.map { it.composeUidWorkaround },
            tail = (start until count).map { i -> AdaptivePass.Place(i, all[i], all[i].id in view.byHand) },
            head = all.subList(current, start).toList(),
            queue = all,
            online = isNetworkConnected.value,
            explicitHidden = ContentFilter.current.explicit,
            now = System.currentTimeMillis(),
        )
    }

    /** The pass worked out: the taste, the related lists and maybe a radio page, then the plan. Adaptive dispatcher. */
    private suspend fun computePass(snap: PassSnapshot): AdaptivePass.Result {
        val taste = tasteFor(snap.now)
        val key = snap.view.key
        if (relatedRun != key) {
            relatedRun = key
            relatedCache.clear()
        }
        val liked = snap.view.likePending
        val seeds = (listOfNotNull(liked) + snap.view.seeds).distinct()
        fun sources() = seeds.mapNotNull { seed -> listFor(seed, snap.explicitHidden) }
        val skippedRelated = snap.view.lastSkipped?.takeIf { snap.ask.skip }?.let { id ->
            runCatching { database.youtubeRelatedSongs(id).mapTo(HashSet()) { it.id } }.getOrDefault(emptySet())
        }.orEmpty()
        fun input(sources: List<AdaptivePass.Source>) = AdaptivePass.Input(
            kind = snap.kind, tail = snap.tail, head = snap.head, queue = snap.queue, view = snap.view, taste = taste,
            sources = sources, skippedRelated = skippedRelated, offline = !snap.online,
            playsOffline = { s -> s.isLocal || downloadCache.holdsWhole(s.id) || playerCache.holdsWhole(s.id) },
        )
        var result = AdaptivePass.run(input(sources()))
        // Too few picks from what is stored, or nothing stored can be used because explicit songs
        // are hidden and a stored song does not say whether it is one: the radio page of the newest
        // seed not asked yet, which comes through the content filter.
        if (result.usable < result.asking) {
            pageFor(seeds, key, snap)?.let { result = AdaptivePass.run(input(sources())) }
        }
        return result
    }

    /** [seed]'s related list for this run: a radio page asked earlier, or the list stored on the phone. Adaptive dispatcher. */
    private fun listFor(seed: String, explicitHidden: Boolean): AdaptivePass.Source? {
        relatedCache[seed]?.let { return it.takeIf { !explicitHidden || it.page } }
        if (explicitHidden) return null
        val songs = try {
            database.youtubeRelatedSongs(seed).map { it.toMediaMetadata() }
        } catch (e: Exception) {
            Log.w(TAG, "Adaptive queue: could not read a song's related list", e)
            return null
        }
        // Kept only once there is one: a song that started seconds ago may not have its list yet.
        if (songs.isEmpty()) return null
        return AdaptivePass.Source(seed, songs).also { relatedCache[seed] = it }
    }

    /**
     * Asks for the radio page of the newest of [seeds] whose page this run has not asked for, as
     * the radio's own top-up does, and keeps it as that seed's list. Online only, never while
     * YouTube is refusing us, once per seed in a run and one page every three minutes at most.
     * Returns the page, or null when none was asked for. Adaptive dispatcher.
     */
    private suspend fun pageFor(seeds: List<String>, key: String, snap: PassSnapshot): AdaptivePass.Source? {
        fun noPage(why: String): AdaptivePass.Source? { Log.d(TAG, "Adaptive queue: no page ($why)"); return null }
        if (seeds.isEmpty()) return noPage("nothing kept yet")
        if (!snap.online) return noPage("offline")
        if (Throttle.isBlocked) return noPage("throttled")
        if (snap.now - (lastPageAt[key] ?: 0L) < PAGE_EVERY_MS) return noPage("too soon")
        val asked = pagesAsked.getOrPut(key) { HashSet() }
        val seed = seeds.firstOrNull { it !in asked } ?: return noPage("asked already")
        lastPageAt[key] = snap.now
        // Seen through even when a newer ask cancels this pass, and kept as that seed's list for
        // the run: a page given up halfway was lost with the seed's one page of the run, and the
        // three minutes before the next. A failure is an answer too, and is not asked again.
        return withContext(NonCancellable) {
            val page = try {
                YouTubeQueue(WatchEndpoint(seed)).nextPage().drop(1)
            } catch (e: Exception) {
                Log.w(TAG, "Adaptive queue: the radio page of a seed failed", e)
                emptyList()
            }
            asked += seed
            Log.d(TAG, "Adaptive queue: page from $seed, ${page.size} songs")
            if (page.isEmpty()) return@withContext null
            // Beside the stored list where there is one: both are that seed's.
            val stored = relatedCache[seed]?.takeIf { !it.page }?.songs.orEmpty()
            AdaptivePass.Source(seed, if (snap.explicitHidden) page else stored + page, page = true).also { relatedCache[seed] = it }
        }
    }

    /** The taste, read again once it is older than TASTE_TTL_MS; the priors and no habits when the read fails. Adaptive dispatcher. */
    private fun tasteFor(now: Long): QueueFit.Taste {
        tasteCache?.takeIf { now - it.first < TASTE_TTL_MS }?.let { return it.second }
        val started = SystemClock.elapsedRealtime()
        return try {
            QueueFit.Taste.of(EngineLoader.loadHeard(database, now)).also { taste ->
                tasteCache = now to taste
                Log.d(TAG, "Adaptive queue: taste read in ${SystemClock.elapsedRealtime() - started} ms, ${taste.listens} listens, " +
                    "${taste.songs.size} songs, keep share ${String.format(java.util.Locale.ROOT, "%.2f", taste.habits.base)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Adaptive queue: could not read the taste, going on without it", e)
            QueueFit.Taste.empty(now)
        }
    }

    /** The plan applied, when nothing it was made for has moved, and what it did noted. Main thread. */
    private fun applyPass(snap: PassSnapshot, result: AdaptivePass.Result) {
        fun abandon(why: String) { Log.d(TAG, "Adaptive queue: abandoned ($why)") }
        // Switched off while the pass was being worked out.
        if (!adaptiveQueueMode.isOn) return abandon("off")
        if (queueSheetOpen) {
            passWaiting = snap.ask.merge(passWaiting)
            return abandon("sheet opened")
        }
        if (isCasting) return abandon("casting")
        val q = queueBoard.getCurrentQueue()
        if (q == null || q.id != snap.queueId || q.runId != snap.runId || heard.runKey != snap.view.key) return abandon("queue changed")
        if (activePlayer.currentMediaItem == null) return abandon("player emptied")
        if (activePlayer.currentMediaItemIndex != snap.current) return abandon("song moved")
        val plan = result.plan
        // What the queue or the run has now, which the radio's top-up may have added to meanwhile:
        // a pick it already holds, by id or by version, would play twice.
        val live = q.getCurrentQueueShuffled()
        val liveIds = live.mapTo(HashSet()) { it.id }.apply { addAll(heard.reached) }
        val liveVersions = live.mapNotNullTo(HashSet()) { RadioPage.versionOf(it) }.apply { addAll(heard.reachedVersions) }
        fun stillNew(id: String): MediaMetadata? = result.songs[id]?.takeIf { s ->
            s.id !in liveIds && RadioPage.versionOf(s)?.let { it in liveVersions } != true
        }
        val edits = ArrayList<AdaptiveEdit>()
        for (change in plan.changes) when (change) {
            is QueuePlanner.Replace -> stillNew(change.pick.id)?.let {
                edits += AdaptiveEdit.Replace(change.slot.index, snap.uids[change.slot.index], it, snap.playerUids[change.slot.index])
            }
            is QueuePlanner.Drop -> edits += AdaptiveEdit.Drop(change.slot.index, snap.uids[change.slot.index], snap.playerUids[change.slot.index])
            is QueuePlanner.Keep -> {}
        }
        val inserts = plan.inserts.mapNotNull { pick -> stillNew(pick.id)?.let { pick to it } }
        if (inserts.isNotEmpty() && plan.insertAt > 0) {
            edits += AdaptiveEdit.Insert(plan.insertAt, snap.uids[plan.insertAt - 1], inserts.map { it.second }, snap.playerUids[plan.insertAt - 1])
        }
        val ids = (0 until activePlayer.mediaItemCount).map { i -> runCatching { activePlayer.getMediaItemAt(i).mediaId }.getOrNull() }
        val notBefore = AdaptiveQueue.settledThrough(activePlayer.currentMediaItemIndex, ids, { it }, heard.reached) + 1 + snap.locked
        val done = if (edits.isEmpty()) emptyList() else queueBoard.applyAdaptive(q, edits, notBefore)

        // What was done, as the plan named it.
        val doneReplace = done.filterIsInstance<AdaptiveEdit.Replace>().mapTo(HashSet()) { it.index }
        val doneDrop = done.filterIsInstance<AdaptiveEdit.Drop>().mapTo(HashSet()) { it.index }
        val doneInsert = done.filterIsInstance<AdaptiveEdit.Insert>().firstOrNull()
        val replaced = plan.replaced.filter { it.slot.index in doneReplace }
        val dropped = plan.dropped.filter { it.slot.index in doneDrop }
        val added = if (doneInsert == null) emptyList() else inserts.map { it.first }
        val taste = tasteCache?.second
        for (r in replaced) Log.d(TAG, AdaptivePass.replacedLine(r.slot, r.pick, taste?.heardBefore(r.pick.id) == true))
        for (d in dropped) Log.d(TAG, AdaptivePass.droppedLine(d.slot))
        for (k in plan.keptWithoutPick) Log.d(TAG, AdaptivePass.keptLine(k.slot))
        added.forEachIndexed { i, pick -> Log.d(TAG, AdaptivePass.addedLine(pick, plan.insertAt + i, taste?.heardBefore(pick.id) == true)) }

        val picked = LinkedHashMap<String, String>()
        replaced.forEach { picked[it.pick.id] = it.pick.seed }
        added.forEach { picked[it.id] = it.seed }
        if (picked.isNotEmpty()) heard.picked(picked)
        // A pick an earlier pass put in and this one took out is not picked again in this run.
        heard.dismiss((replaced.map { it.slot.id } + dropped.map { it.slot.id }).filter { it in snap.view.picks })
        val liked = snap.view.likePending
        if (liked != null && (added.isNotEmpty() || replaced.any { it.pick.seed == liked })) heard.likeAnswered(liked)
        val likedSong = liked?.let { id -> snap.queue.firstOrNull { it.id == id } }
        val note = AdaptiveNote.of(
            snap.kind, replaced, added, dropped.size, liked,
            likedSong?.let { SongVersions.plainTitle(it.title, it.artists.firstOrNull()?.name).ifBlank { it.title } },
            heard.played,
        )
        if (note != null) heard.note = note
        Log.d(TAG, AdaptivePass.summaryLine(result, replaced.size, dropped.size, added.size, plan.keptWithoutPick.size, note))
        if (done.isNotEmpty()) hiddenTailChanged.value = hiddenTailChanged.value + 1
        adaptiveChanged()
    }

    /**
     * The marks and the note for the queue sheet, from the run and the queue as they are now: the
     * picks still to come, after the song playing. A pick jumped over is not, and kept its mark
     * and the note that counted it for as long as the run lasted. Main thread.
     */
    private fun publishAdaptive() {
        val q = queueBoard.getCurrentQueue()
        val order = q?.getCurrentQueueShuffled().orEmpty()
        val current = if (activePlayer.currentMediaItem != null) activePlayer.currentMediaItemIndex
            else q?.takeIf { it.queuePos in it.queue.indices }?.getQueuePosShuffled() ?: -1
        val upcoming = order.drop(current + 1).mapTo(HashSet()) { it.id }
        val ahead = heard.unreachedPicks().filterTo(LinkedHashSet()) { it in upcoming }
        adaptivePicks.value = ahead
        heard.note?.let { if (!AdaptiveNote.stands(it, ahead, heard.played)) heard.note = null }
        adaptiveNote.value = heard.note
    }

    /**
     * Sends the buttons for the phone's media controls, the two picked in settings first.
     *
     * The system controls show two of these beside previous, play and next and drop the rest, so
     * the order decides what anyone sees. The rest still go out after them for controllers that
     * show more. See [MediaControlButtons].
     */
    fun updateNotification() {
        val buttons = if (inCar) MediaControlButtons.CAR else MediaControlButtons.layout(mediaControlButtons)
        mediaSession.setCustomLayout(buttons.map(::mediaControlButton))
    }

    private fun mediaControlButton(button: MediaControlButton): CommandButton {
        val song = currentSong.value
        // Semantic icons rather than our own drawables, for every one of these.
        //
        // A controller that redraws the media controls in its own style, which on Samsung is every
        // one of them, can render an icon it recognises by name. It is under no obligation to draw
        // an arbitrary drawable handed to it by an app, and One UI does not: back when shuffle and
        // repeat carried our own drawables, they were the two that went missing from the
        // notification while like and radio, already semantic, came through. media3 ships its own
        // drawables for these names, so nothing is lost where the custom ones did work.
        //
        // A button that is not enabled is left out of the system controls altogether, and the one
        // after it moves up into its place.
        return when (button) {
            // The label reads the same thing the icon does. It used to read the queue's shuffled
            // flag while the icon read the player's, and this button calls toggleShuffleMode,
            // which sets player.shuffleModeEnabled and never touches the queue. So pressing it
            // moved the icon and left the label behind, sometimes for good.
            MediaControlButton.SHUFFLE ->
                CommandButton.Builder(if (activePlayer.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                    .setDisplayName(getString(if (activePlayer.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setSessionCommand(CommandToggleShuffle)
                    .build()

            MediaControlButton.REPEAT ->
                CommandButton.Builder(
                    when (activePlayer.repeatMode) {
                        REPEAT_MODE_OFF -> CommandButton.ICON_REPEAT_OFF
                        REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                        REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                        else -> throw IllegalStateException()
                    }
                )
                    .setDisplayName(
                        getString(
                            when (activePlayer.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build()

            MediaControlButton.LIKE ->
                CommandButton.Builder(if (song?.song?.liked == true) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (song?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(song != null)
                    .build()

            MediaControlButton.RADIO ->
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(song != null)
                    .build()

            // A plus that turns into a tick, as the add button does in other players. Off for a
            // local file: it is in the library because it is on the phone, and the player's own
            // menu does not offer to take it out either.
            MediaControlButton.LIBRARY ->
                CommandButton.Builder(
                    if (song?.song?.inLibrary != null) CommandButton.ICON_CHECK_CIRCLE_FILLED else CommandButton.ICON_PLUS_CIRCLE_UNFILLED
                )
                    .setDisplayName(getString(if (song?.song?.inLibrary != null) R.string.remove_from_library else R.string.add_to_library))
                    .setSessionCommand(CommandToggleLibrary)
                    .setEnabled(song != null && !song.song.isLocal)
                    .build()
        }
    }

    /**
     * Hold the song and try it again on a timer, rather than only waiting for the network to return.
     *
     * What lands here is usually a single name resolution or connect miss thrown by our own
     * resolver, on a connection that never dropped. The observer only speaks when a network becomes
     * available, so on a healthy link nothing was ever going to release us and playback stayed dead
     * until the app was force closed. Retries run even when the phone claims to be offline, because
     * that claim has been wrong before and a retry costs one failed request.
     */
    fun waitOnNetworkError() {
        if (!waitingForNetworkConnection.value) {
            // One toast per outage. The retries stay quiet, or there is one every few seconds.
            Toast.makeText(this@MusicService, getString(R.string.wait_to_reconnect), Toast.LENGTH_LONG).show()
        }
        waitingForNetworkConnection.value = true
        scheduleNetworkRetry()
    }

    private fun scheduleNetworkRetry() {
        networkRetryJob?.cancel()
        val attempt = networkRetryAttempt + 1
        if (!NetworkRetryPolicy.shouldRetry(attempt)) {
            networkRetryJob = null
            // Out of tries. If the phone still believes it is online then no network event is
            // coming either, so stop pretending to wait and hand the error back to the ordinary
            // path, which honours the skip-on-error choice and leaves a player whose buttons work.
            // If it believes it is offline, keep the flag: resuming when the network returns is
            // exactly what the observer is for.
            if (isNetworkConnected.value) {
                clearNetworkWait()
                if (dataStore.get(SkipOnErrorKey, false)) {
                    skipOnError()
                } else {
                    stopOnError()
                }
            }
            return
        }
        networkRetryAttempt = attempt
        networkRetryJob = scope.launch(SilentHandler) {
            delay(NetworkRetryPolicy.delayMillis(attempt))
            // Anything that recovered, or that the listener touched, has already cleared the flag,
            // and re-preparing a player that is not idle would restart the song under them.
            if (!waitingForNetworkConnection.value || activePlayer.playbackState != STATE_IDLE) return@launch
            activePlayer.prepare()
            activePlayer.play()
        }
    }

    /** One place that ends the wait, so the retry job and the counter never outlive the flag. */
    private fun clearNetworkWait() {
        networkRetryJob?.cancel()
        networkRetryJob = null
        networkRetryAttempt = 0
        waitingForNetworkConnection.value = false
    }

    fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = activePlayer.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_PLAYER_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            // The transition this makes is a seek, as next is; the adaptive queue must not read it
            // as the listener skipping the song that failed.
            skippingOnError = true
            activePlayer.seekTo(nextWindowIndex, C.TIME_UNSET)
            activePlayer.prepare()
            activePlayer.play()

            Toast.makeText(this@MusicService, getString(R.string.err_play_next_on_error), Toast.LENGTH_SHORT).show()
            return
        }

        // Nothing after it to skip to is not "too many errors": the last song of a queue failed once.
        val tooMany = consecutivePlaybackErr > MAX_PLAYER_CONSECUTIVE_ERR
        consecutivePlaybackErr = 0
        if (!tooMany) {
            stopOnError()
            return
        }
        stopsOnError()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_too_many_errors), Toast.LENGTH_LONG).show()
    }

    fun stopOnError() {
        stopsOnError()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_error), Toast.LENGTH_LONG).show()
    }

    /** The music stops on the current song's error, and which song that is: see [stoppedOn]. */
    private fun stopsOnError() {
        stoppedByError = true
        stoppedOn = activePlayer.currentMediaItem?.mediaId
        activePlayer.pause()
    }


// Player overrides

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        // However this play is left from here (skip on error seeking past it, the listener moving
        // on, the service going), it ended in this error, unless it plays again first. The play is
        // the one of the item the error names, the current one when it names none. Before
        // skipOnError below, which makes the next song current.
        plays.playerError(PlayBook.itemOf(error, player.currentTimeline), player.currentMediaItem?.mediaId)

        // The stream address the song was being fetched from goes with the failure, so that the
        // next try asks for one afresh: Play, the retries of the wait below, the radio's answer
        // preparing the player. It used to be handed out again until it expired, hours later, and
        // a song that had failed on its address could only be skipped. Whatever the failure was:
        // asking again is one walk of the chain, most often a single request, and an address is
        // not worth more than that once the player has stopped on it. Here, before the wait for
        // the network, which returns, and before skipOnError, which makes the next song current.
        // See StreamAddresses.
        val failedId = PlayBook.itemOf(error, player.currentTimeline) ?: player.currentMediaItem?.mediaId
        // The route the song failed on, read before its address is forgotten: see SongRoutes.
        val failedRoute = streamAddresses.clientOf(failedId)
        val goOn = badCachedCopy.playerFailed(failedId, error)
        if (streamAddresses.playerFailedOn(failedId)) {
            Log.d(TAG, "PLAYING: the player failed on $failedId, its stream address will be asked for again")
        }
        // The stall watch already gave this song up, said so and stopped or skipped: the player's
        // own error on it is the same fault, and a second line and a second message only confused.
        stallGaveUp?.let { (id, at) ->
            if (id == failedId && !activePlayer.playWhenReady && SystemClock.elapsedRealtime() - at < STALL_ECHO_MS) {
                Log.d(TAG, "PLAYING: the player's own error on $failedId, already given up by the stall watch")
                return
            }
        }
        // What failed was the song's cached copy, and it is gone: the song goes on from where it
        // stopped, read from YouTube, instead of ending on an error that will not come again.
        // Once: see BadCachedCopy.
        if (goOn && failedId == player.currentMediaItem?.mediaId) {
            Log.i(TAG, "PLAYING: $failedId goes on from where it stopped, without its cached copy")
            player.prepare()
            return
        }

        // Wait for reconnection, but only where a network could help. See waitsForNetwork: a
        // local file that has gone missing used to wait here for good whenever the phone was
        // offline, instead of skipping or stopping as set.
        val causeCode = generateSequence(error.cause) { it.cause }
            .filterIsInstance<PlaybackException>()
            .firstOrNull()?.errorCode
        val localSong = activePlayer.currentMediaItem?.mediaId?.let { id ->
            queueBoard.getCurrentQueue()?.findSong(id)?.isLocal
        } == true
        if (NetworkRetryPolicy.waitsForNetwork(!isNetworkConnected.value, error.errorCode, causeCode, localSong)) {
            waitOnNetworkError()
            return
        }

        // A passing fault is tried again quietly, a moment later, from where the song stopped,
        // with the route it failed on asked last. A timeout used to stop the music here, while a
        // fresh try a second later played (20 Oct 2026). Within the budget: see Recovery.
        var gaveUp = false
        if (failedId != null && failedId == activePlayer.currentMediaItem?.mediaId && !isCasting && !localSong) {
            val staleCopy = generateSequence(error as Throwable) { it.cause }.take(8)
                .any { it is PlaybackException && it.message == getString(R.string.error_cached_part_stale) }
            val passing = if (staleCopy) staleCopyRetried.add(failedId) else Recovery.kind(error) == Recovery.Kind.PASSING
            if (passing) {
                val now = SystemClock.elapsedRealtime()
                val n = recoveryBudget.take(failedId, now)
                if (n != null) {
                    songRoutes.mark(failedId, failedRoute, now)
                    val delayMs = if (n == 1) RouteTimes.QUIET_RETRY_FIRST_MS else RouteTimes.QUIET_RETRY_LATER_MS
                    Log.w(TAG, StepLog.quietRetry(failedId, if (staleCopy) "cached part stale" else Recovery.what(error), delayMs, n, recoveryBudget.ofSong))
                    scheduleQuietRetry(failedId, delayMs)
                    return
                }
                gaveUp = true
                noteGivenUp(failedId, recoveryBudget.takenFor(failedId, now), "no answer from YouTube's servers")
            }
        }

        if (dataStore.get(SkipOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }

        // YouTube turning the connection away in the words it has for a song that is gone, song
        // after song (issue #28): said as what it is, in the listener's language, where every
        // song used to read "This video is not available".
        val turnedAway = YTPlayerUtils.isConnectionTurnedAway(error)
        if (turnedAway) Log.w(TAG, "PLAYING: every song in a row called gone, taken for the connection being turned away")
        // A song YouTube no longer serves, said in plain words and once for each song. It was the
        // raw "Unexpected SongUnavailable" at every tap (21 Oct 2026: thirteen taps on one song).
        val gone = !gaveUp && !turnedAway && YTPlayerUtils.isUnavailableSong(error)
        if (gone && failedId != null && !goneSongs.tellOnce(failedId)) return
        Toast.makeText(
            this@MusicService,
            if (gaveUp) getString(R.string.error_youtube_not_answering)
            else if (turnedAway) getString(R.string.error_connection_turned_away)
            else if (gone) getString(R.string.err_song_unavailable)
            else ErrorText.withoutAddresses("plr: ${error.message} (${error.errorCode}): ${error.cause?.message ?: ""} "),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Prepares the song again after [delayMs], if it is still the one that stopped, the player
     * still stopped and still wanted to play: the listener may have moved on, paused or pressed
     * play meanwhile, and nothing is restarted under them.
     */
    private fun scheduleQuietRetry(id: String, delayMs: Long) {
        quietRetryJob?.cancel()
        quietRetryJob = scope.launch(SilentHandler) {
            delay(delayMs)
            if (activePlayer.currentMediaItem?.mediaId != id) return@launch
            if (activePlayer.playbackState != STATE_IDLE || !activePlayer.playWhenReady) return@launch
            activePlayer.prepare()
        }
    }

    /**
     * The route [id] was being fetched over failed: its address is forgotten and the route asked
     * last for the song, so the player's next try resolves it anew elsewhere. A cached or local
     * song has no address, and nothing changes. A stand-in's address has no route of its own,
     * but it is forgotten all the same: kept, the player retried a refused address until the
     * buffer ran out. A refusal of an address that passed its check is the route's own doing.
     *
     * These changes are made while the buffer still plays, and have a budget of their own: on a
     * long flaky session they used to spend the one the stops need, and the next stop was given
     * up without a single try.
     */
    private fun changeRoute(id: String, why: String, refused: Boolean = false) {
        val route = streamAddresses.clientOf(id)
        val now = SystemClock.elapsedRealtime()
        if (route == null) {
            if (streamAddresses.playerFailedOn(id)) Log.w(TAG, StepLog.loadFailed(id, why, null))
            return
        }
        if (routeChanges.take(id, now) == null) {
            // The address goes all the same: a refused one never comes back, and asking afresh is one walk.
            streamAddresses.playerFailedOn(id)
            Log.w(TAG, "PLAYING: [$id] $why, no route change left: asked afresh on the same routes")
            return
        }
        // A refusal of an address that passed its check is the route's own doing: a strike, so
        // the next songs start elsewhere too. See StreamOrder.REST_MS. Not just after a change of
        // network, where the address names the old public address, nor near its end.
        val sinceNetworkChange = now - lastNetworkChangeAt
        val sameNetwork = lastNetworkChangeAt == 0L || sinceNetworkChange >= RouteTimes.NETWORK_CHANGE_GRACE_MS
        if (refused && streamAddresses.refusalIsTheRoutes(id, System.currentTimeMillis()) && sameNetwork) YTPlayerUtils.strike(route, why)
        // A refusal of an address played unchecked is no strike, its check never having
        // answered, but the song's next walk is told: it takes VISIONOS's silent check for the
        // same refusal and tries a new visitorData (issue #17), where the same refused address
        // used to go to the player again. See SongRoutes.refused.
        val refusedUnchecked = refused && sameNetwork && !streamAddresses.wasChecked(id)
        streamAddresses.playerFailedOn(id)
        songRoutes.mark(id, route, now, refusedUnder = if (refusedUnchecked) YouTube.visitorData.orEmpty() else null)
        Log.w(TAG, StepLog.loadFailed(id, why, route))
    }

    /** One line at level E, which Report a problem takes for the fault, and the same kept past the process. */
    private fun noteGivenUp(id: String, tries: Int, reason: String) {
        val line = StepLog.gaveUp(id, tries, reason)
        Log.e(TAG, line)
        StopReasons.keep(this, line)
    }

    /**
     * Starts or stops the stall watch: once a second, and only while the player buffers and wants
     * to play. No timer runs at any other time.
     */
    private fun updateStallWatch(player: Player) {
        val wanted = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && !isCasting
        if (wanted && !stallWatching) {
            stallWatching = true
            // A silence from before a pause or a quiet retry is not this one's.
            stallWatch.reset()
            stallHandler.postDelayed(stallTick, 1_000L)
        } else if (!wanted && stallWatching) {
            stallWatching = false
            stallHandler.removeCallbacks(stallTick)
        }
    }

    /** One second of the stall watch: see StallWatch. */
    private fun watchStall() {
        val id = activePlayer.currentMediaItem?.mediaId
        val now = SystemClock.elapsedRealtime()
        val action = stallWatch.tick(
            StallWatch.Tick(
                songId = id,
                wantsToPlay = activePlayer.playWhenReady,
                buffering = activePlayer.playbackState == Player.STATE_BUFFERING,
                streamed = id != null && streamedSongs[id] == true,
                resolving = id != null && (resolvingSongs[id] ?: 0) > 0,
                casting = isCasting,
                waitingForNetwork = waitingForNetworkConnection.value,
                online = isNetworkConnected.value,
                bytes = StreamProgress.bytes.get(),
                now = now,
                // The maintainer's off switch: see RemoteRoutes.
                allowed = RemoteRoutes.watchdog,
                // A connection being made is not silence: see StallWatch.
                connectingMs = id?.let { StreamProgress.waitingMs(it, System.nanoTime()) },
            )
        )
        if (id == null) return
        val silentMs = stallWatch.silentMs(now)
        when (action) {
            StallWatch.Action.NOTHING -> Unit
            StallWatch.Action.CUT_CONNECTION -> {
                // Media3's own retry then asks again at once, on a new connection, from the same byte.
                loadFailures.cutByUs(id, now)
                playerCalls.cancelInFlight()
                Log.w(TAG, StepLog.stallCut(id, silentMs, activePlayer.currentPosition, 1, 3))
            }
            StallWatch.Action.CHANGE_ROUTE -> {
                val route = streamAddresses.clientOf(id)
                if (recoveryBudget.take(id, now) != null) {
                    streamAddresses.playerFailedOn(id)
                    songRoutes.mark(id, route, now)
                    loadFailures.cutByUs(id, now)
                    playerCalls.cancelInFlight()
                    Log.w(TAG, StepLog.stallRoute(id, silentMs, activePlayer.currentPosition, route, 2, 3))
                } else {
                    stallGiveUp(id, now)
                }
            }
            StallWatch.Action.GIVE_UP -> stallGiveUp(id, now)
        }
    }

    private fun stallGiveUp(id: String, now: Long) {
        stallWatching = false
        stallHandler.removeCallbacks(stallTick)
        stallGaveUp = id to now
        noteGivenUp(id, recoveryBudget.takenFor(id, now) + 1, "no byte from YouTube's servers for ${StepLog.limit(stallWatch.silentMs(now))}")
        if (dataStore.get(SkipOnErrorKey, false)) skipOnError() else stopOnError()
        Toast.makeText(this@MusicService, getString(R.string.error_youtube_not_answering), Toast.LENGTH_LONG).show()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)
        // A pause nobody asked for says what did it, for Report a problem. A pause asked for (a
        // tap, a button, the sleep timer) is USER_REQUEST and writes nothing.
        if (!playWhenReady) {
            StopReasons.pausedLine(reason, screenOn(), inForeground())?.let {
                Log.i(TAG, it)
                StopReasons.keep(this, it)
            }
        }
        // Why the music stopped, for Resume when Bluetooth connects. Kept with the setting off as
        // well: it is three fields, and the setting can be turned on after the stop.
        bluetoothResume.playWhenReadyChanged(playWhenReady, reason, SystemClock.elapsedRealtime())
    }

    /**
     * Starts or stops watching the Bluetooth outputs, as Resume when Bluetooth connects is turned
     * on or off. What is connected as the watch begins is there already, not arriving.
     */
    private fun watchBluetoothOutputs(on: Boolean) {
        if (on == resumeOnBluetooth) return
        val audio = getSystemService<AudioManager>() ?: return
        resumeOnBluetooth = on
        if (on) {
            bluetoothResume.outputsThere(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).musicOutputs())
            audio.registerAudioDeviceCallback(bluetoothOutputs, Handler(Looper.getMainLooper()))
        } else {
            audio.unregisterAudioDeviceCallback(bluetoothOutputs)
            bluetoothResumeJob?.cancel()
        }
    }

    /** The ids of the Bluetooth outputs for music among [this]: see [BluetoothResume.isMusicOutput]. */
    private fun Array<out AudioDeviceInfo>?.musicOutputs(): List<Int> =
        orEmpty().filter { BluetoothResume.isMusicOutput(it.type, it.isSink) }.map { it.id }

    private fun bluetoothOutputsAdded(ids: List<Int>) {
        if (!bluetoothResume.outputsAdded(ids) || !bluetoothResumeWanted()) return
        bluetoothResumeJob?.cancel()
        bluetoothResumeJob = scope.launch {
            delay(BluetoothResume.SETTLE_MS)
            // Asked again: a car may have sent a play or a pause of its own meanwhile, or the
            // output may have gone again.
            if (!bluetoothResume.anyOutput || !bluetoothResumeWanted()) return@launch
            Log.i(TAG, "PLAYING: Bluetooth audio connected, and the music its output stopped plays again")
            player.play()
        }
    }

    private fun bluetoothOutputsRemoved(ids: List<Int>) {
        bluetoothResume.outputsRemoved(ids, SystemClock.elapsedRealtime())
    }

    private fun bluetoothResumeWanted(): Boolean = bluetoothResume.shouldResume(
        enabled = resumeOnBluetooth,
        now = SystemClock.elapsedRealtime(),
        playWhenReady = player.playWhenReady,
        playbackState = player.playbackState,
        hasSong = player.currentMediaItem != null,
    )

    /**
     * One look of the sleep timer on a schedule, while music plays: the timer started, or one
     * already running left to do the job, and the day written down either way. Main thread.
     */
    private fun lookAtSleepSchedule(settings: ScheduledSleep.Settings) {
        val look = ScheduledSleep.look(LocalDateTime.now(), settings.start, sleepScheduleServed, sleepTimer.isActive) ?: return
        if (look.startTimer) {
            sleepTimer.start(settings.length)
            Log.i(TAG, "The sleep timer on a schedule started: ${settings.length} min, for ${look.night}")
        } else {
            Log.i(TAG, "The sleep timer on a schedule found a timer running, which counts for ${look.night}")
        }
        // Held here at once, so that the next look cannot start a second timer before the store
        // has answered, and stored so that a restart of the app later that night does not either.
        sleepScheduleServed = look.night
        scope.launch { dataStore.edit { it[SleepScheduleLastNightKey] = look.night.toString() } }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playingNow.value = isPlaying
        updateWidget()
        // The stream is what costs battery, so it runs only while something is playing. Glide the
        // stage home on pause rather than dropping it, since the listener may well be mid-turn.
        // Both are about sound coming out of the phone, so neither starts for a receiver.
        if (isPlaying && headTrackingWanted && binauralProcessor.enabled && !isCasting) {
            headTracking.start()
        } else if (!isPlaying) {
            headTracking.stop(glideHome = true)
        }
        // The proximity scan follows onEvents instead; see ProximityVolume.shouldScan.
        if (isPlaying && !isCasting) {
            activePlayer.currentMediaItem?.mediaId?.let { id -> currentStart(id)?.takeIf { !it.opened }?.let { openListen(id, it) } }
        }
        if (!isPlaying) {
            val pos = activePlayer.currentPosition
            // A pause is where a stop will have happened if the app dies now, so the open row
            // learns the position at once rather than at the next minute mark.
            activePlayer.currentMediaItem?.mediaId?.let { id ->
                lastKnownPosition[id] = pos
                currentStart(id)?.let { checkpointListen(it, pos) }
            }
            // Only while the player holds a song. Emptied while playing, it reports the stop once
            // the song has gone and reads 0:00, which is not where the queue stopped.
            val q = queueBoard.getCurrentQueue()?.takeIf { activePlayer.currentMediaItem != null }
            q?.lastSongPos = pos
            // Written through at once, so a process the system kills while paused still comes back
            // where it was. Until now the position only reached the database in onDestroy.
            q?.let { mq -> database.query { runCatching { updateQueue(mq) }.onFailure { Log.w(TAG, "Could not save the paused position", it) } } }
        }
        super.onIsPlayingChanged(isPlaying)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        songChanges.value += 1
        // A cast play records nothing in the listening history for now. A row is closed by what the
        // phone's own player reports, and it reports nothing while casting, so a row opened here
        // would never be closed. Whether cast plays should count at all is still to be decided.
        if (!isCasting) noteTransition(mediaItem, reason)
        // Whatever the change, a queue just loaded included: see refreshAdaptiveTail.
        refreshAdaptiveTail()
        // The car's tabs each show what is playing across their top, and the car keeps what it
        // was handed: another song, and it is told.
        if (Unreleased.AUTO_REWORK && ::mediaSession.isInitialized) {
            AutoBrowse.TABS.forEach { mediaSession.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
        }
        // Every song change, not only when the queue itself changes. A skip is the strongest
        // opinion anyone gives without pressing anything, so it reaches further than a song that
        // simply ended.
        //
        // Once per transition. The plain pass that came before this one was left at the bottom of
        // this function when the skip reach was added, and nothing in between changes what it
        // reads, so every song change planned the same tail twice: a skip got its strong pass and
        // then an ordinary one over whatever survived, and a song that simply ended got two
        // ordinary ones, each taking its own share, where AdaptiveQueue promises never more than
        // one share at once. The radio top-up further down cannot be what the second pass was for
        // either: it is launched, not awaited, so its songs have not arrived by then.
        //
        // Going back is not a skip. It says nothing against the songs ahead, all of which were
        // either heard already or seen waiting, so it gets the ordinary pass.
        //
        // How the song just left ended is what the far end learns from (AdaptiveQueue.leave): left
        // before half by moving on is a skip, from half on or run out by itself a song kept. Not a
        // move made by skip on error, which seeks to the next song just as next does: a run of
        // failing streams used to turn down every artist it reached. Not a tap on another song
        // either, nor anything while casting, where the phone does not know where the receiver was.
        val fromError = skippingOnError
        skippingOnError = false
        transitionFromError = fromError
        // A choice whose queue was loaded under the song playing made no transition of its own
        // (carriedOnInto): this one only moves on from that song, and its leave is read by its reason.
        val chosen = transitionChosen && !choiceCarriedOn
        choiceCarriedOn = false
        var leftAs: AdaptiveQueue.Leave? = null
        heard.playing?.takeIf { it.id != mediaItem?.mediaId }?.let { left ->
            val leftAt = lastKnownPosition[left.id]
            val heardThrough = lastKnownHeard.remove(left.id)
            val how = when {
                fromError -> AdaptiveQueue.Leave.NEITHER
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> AdaptiveQueue.Leave.RAN_OUT
                isCasting -> AdaptiveQueue.Leave.NEITHER
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !transitionWentBack && !chosen ->
                    AdaptiveQueue.leave(leftAt, left.durationSec, heardThrough)
                else -> AdaptiveQueue.Leave.NEITHER
            }
            val pick = heard.isPick(left.id)
            heard.left(how)
            leftAs = heard.lastLeave
            Log.d(TAG, AdaptivePass.leftLine(left.id, how, leftAt, left.durationSec, pick))
        }
        // Repeat one: the song came round again, which is a song heard to its end.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) heard.left(AdaptiveQueue.Leave.RAN_OUT)
        mediaItem?.let {
            // By run, not by queue: a radio's queue of one and its full queue are one run. A run
            // saved by the last process comes back here, when its queue plays again.
            val q = queueBoard.getCurrentQueue()
            val runBefore = heard.runKey
            heard.note(
                q?.id ?: 0L, it.mediaId, it.mediaMetadata.title?.toString(),
                it.metadata?.artists?.map { a -> a.name }.orEmpty(), it.metadata?.duration ?: -1,
                runId = q?.runId ?: 0L, radio = q?.playlistId != null,
            )
            heard.takeRestored()?.let { Log.d(TAG, AdaptivePass.restoredLine(it)) }
            // A pass asked in the run before has nothing to plan here: none of this one is heard yet.
            if (heard.runKey != runBefore) dropPasses()
            adaptiveChanged()
        }
        // Not for a queue just put in the player, a new one or the one brought back when the app
        // opens again: nothing of it has been heard yet in this sitting, so the pass had only the
        // song now playing to go by. On a reopen it took 6 of 39 songs out of a queue nobody had
        // touched (emulator, 15 Oct 2026). The next song change plans it.
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            replanQueueTail(strong = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !transitionWentBack, skip = leftAs == AdaptiveQueue.Leave.SKIPPED)
        }
        // "Listening now" on Last.fm. Not a scrobble, not stored, and allowed to fail quietly.
        mediaItem?.metadata?.let { meta -> scope.launch { scrobbler.nowPlaying(meta) } }
        // +2 when and error happens, and -1 when transition. Thus when error, number increments by 1, else doesn't change
        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }

        // Skipping past a song that failed has to start the next one.
        //
        // This was gated on player.isPlaying, which means STATE_READY, and a song that errored
        // leaves the player idle. So next and previous moved the index and nothing else: the
        // listener skipped past the dead song and got silence, then skipped again and got more
        // silence, and the only way out was to force close the app. Idle after an error is
        // precisely when a skip most needs preparing.
        //
        // playWhenReady carries the intent. An error does not clear it, so someone who was
        // listening starts playing again at once; someone who had paused gets the new song loaded
        // and left paused, which is what they asked for. The runaway guard is not weakened,
        // because that counts automatic skips and this is a hand on a button.
        if (reason == MEDIA_ITEM_TRANSITION_REASON_SEEK &&
            (activePlayer.isPlaying || activePlayer.playbackState == STATE_IDLE)
        ) {
            val afterError = stoppedByError
            stoppedByError = false
            activePlayer.prepare()
            // playWhenReady carries the listener's intent, except after an error, where the app
            // cleared it on their behalf by pausing. Skipping then is them asking for the next
            // song, so it plays.
            if (activePlayer.playWhenReady || afterError) activePlayer.play()
        }

        // Auto load more songs
        val q = queueBoard.getCurrentQueue()
        val songCount = q?.getSize() ?: -1
        val playlistId = q?.playlistId
        // Not on a transition to no song at all. That is the player being emptied, by a swipe of
        // the mini player or a queue with nothing left in it, and an empty player reads as having
        // no songs left, so every swipe during a radio asked for another page.
        if (autoLoadMore &&
            mediaItem != null &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            activePlayer.mediaItemCount - activePlayer.currentMediaItemIndex <= 5 &&
            playlistId != null // aka "hasNext"
        ) {
            Log.d(TAG, "onMediaItemTransition: Triggering queue auto load more")
            scope.launch(SilentHandler) {
                val endpoint = playlistId // playlistId.substringBefore("\n")
                val continuation = null // playlistId.substringAfter("\n")
                val yq = YouTubeQueue(WatchEndpoint(endpoint, continuation))
                val mediaItems = yq.nextPage()
                Log.d(TAG, "onMediaItemTransition: Got ${mediaItems.size} songs from radio")
                // Only into the queue that asked for them. A page still on its way when a list from
                // the car became current went to the end of that list instead, and reloaded it.
                // And only while the player still holds something: a page that arrived after the
                // mini player was swiped away loaded the dismissed queue back into the player,
                // which then played again. A cleared player is ended, not idle, so the state check
                // let it through. Checked before the seed below moves on, since a page that is left
                // out should not move the queue's next page on either.
                val wanted = activePlayer.playbackState != STATE_IDLE &&
                    songCount > 1 && // initial radio loading is handled by playQueue()
                    activePlayer.mediaItemCount > 0 && qbInit.value &&
                    queueBoard.getCurrentQueue()?.id == q.id
                if (!wanted) return@launch
                // Seed the next page from something the listener actually finished.
                //
                // This used to be a random pick from the last four songs of the page just
                // fetched, which are songs nobody has heard yet. So page N+1 was seeded by a
                // guess out of page N, page N+2 by a guess out of that, and after half a dozen
                // hops radio is somewhere unrelated to anything that was ever chosen. Measured
                // over a week: radio runs at an 84 percent skip rate against 64 for Quick picks,
                // and it supplies half of all listening because the queue tops itself up from it.
                //
                // Anchoring each hop on a completed song keeps it tied to evidence. The old
                // behaviour remains the fallback, and is also used rather than seeding from the
                // same song twice running, because a seed that never moves gives a radio that
                // never moves.
                //
                // And only what is new to this run joins. The page is the radio of a song in this
                // queue, so much of it is the queue again, and appended whole it brought songs
                // back: in the first half of October 2026, 3.9 percent of the songs that autoplayed
                // had played earlier in the same run, and 74 percent of those were skipped. A page
                // with nothing new adds nothing, and the seed still moves on, so the next song
                // change asks somewhere else. See RadioPage.
                val anchor = lastCompletedId()?.takeIf { it != lastRadioSeed }
                val songs = q.getCurrentQueueShuffled().toList()
                val fresh = RadioPage.fresh(mediaItems.drop(1), songs, heard.reached, heard.reachedVersions)
                Log.d(TAG, "onMediaItemTransition: ${fresh.size} of them new to this run")
                // Nor, with the radio kept up to date, what this run turned down, banned songs or
                // lasting dislikes: the page joins five songs from now at most, inside the lock by
                // the next song change, where no pass looks (AdaptivePass.pageKeeps).
                val joining = if (!adaptiveQueueMode.isOn) fresh else {
                    val view = heard.view()
                    AdaptivePass.pageKeeps(fresh, view.turnedDown, view.positiveArtists, tasteCache?.second)
                }
                if (joining.size < fresh.size) Log.d(TAG, "Adaptive queue: the radio's next page left out ${fresh.size - joining.size} songs")
                q.playlistId = RadioPage.nextSeed(anchor, joining, songs, asked = playlistId)
                    .also { lastRadioSeed = it }
                if (joining.isNotEmpty()) queueBoard.enqueueEnd(joining)
            }
        }

        // The pause point belongs to the song before this one, and the save below writes it.
        // Both are skipped for an emptied player, whose index and position read 0 whatever the
        // queue was on: swiping the mini player away moved the queue back to its first song at
        // 0:00, and the dismissal then saved it that way.
        if (mediaItem != null) {
            q?.let { it.lastSongPos = ResumePoint.afterTransition(reason, activePlayer.currentPosition, it.lastSongPos) }
            queueBoard.setCurrQueuePosIndex(activePlayer.currentMediaItemIndex)
        }

        // reshuffle queue when shuffle AND repeat all are enabled
        // no, when repeat mode is on, player does not "STATE_ENDED"
        //
        // From three songs up, keeping the song that has just started. Two songs have nothing to
        // reshuffle, since repeat all already alternates them, and below three the reshuffle used
        // to let go of the playing song: half the time it made the other one current, and loading
        // that cut the new song off after 200 ms and started the other one again.
        if (activePlayer.mediaItemCount > 2 &&
            activePlayer.currentMediaItemIndex == activePlayer.mediaItemCount - 1 &&
            (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            activePlayer.shuffleModeEnabled && activePlayer.repeatMode == REPEAT_MODE_ALL
        ) {
            scope.launch(SilentHandler) {
                // or else race condition: Assertions.checkArgument(eventTime.realtimeMs >= currentPlaybackStateStartTimeMs) fails in updatePlaybackState()
                delay(200)
                queueBoard.shuffleCurrent()
                queueBoard.setCurrQueue()
            }
        }

        updateNotification() // also updates when queue changes
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == STATE_IDLE) {
            queuePlaylistId = null
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            // Music is coming out of the speaker again, so whatever stopped it is history.
            if (player.playbackState == Player.STATE_READY) stoppedByError = false
            if (!isCasting) plays.playbackState(player.currentMediaItem?.mediaId, player.playbackState)
            if (isBufferingOrReady && player.playWhenReady && !isCasting) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
            }
            // The proximity scan is decided here rather than in onIsPlayingChanged, which fires on
            // every seek and skip and not on a pause while buffering. Never while casting: this
            // player is then the receiver's, and the phone makes no sound to turn down.
            if (ProximityVolume.shouldScan(proximityWanted && !isCasting, player.playWhenReady, player.playbackState)) {
                proximityVolume.start()
            } else {
                proximityVolume.stop()
            }
            // The old clear only fired on a pause, which a player error never does, so the wait
            // outlived the fault whichever way it went: a spinner over music that had come back, or
            // a stale flag that could start playback by itself at the next network change.
            if (!NetworkRetryPolicy.stillWaiting(
                    waitingForNetworkConnection.value,
                    player.playbackState,
                    player.playWhenReady
                )
            ) {
                clearNetworkWait()
            }
            // Silence at the speaker while the song should play: see StallWatch.
            updateStallWatch(player)
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
            updateWidget()
        }
    }

    /**
     * The most recent song in this queue that the listener let finish.
     *
     * Read backwards from where playback is, over a short window: further back than that and it
     * stops being evidence about now. Null when everything recent was skipped, which is worth
     * respecting on its own, since seeding radio from a rejection is how it got lost.
     */
    private fun lastCompletedId(): String? {
        val current = activePlayer.currentMediaItemIndex
        for (i in current downTo maxOf(0, current - radioAnchorLookback)) {
            val id = runCatching { activePlayer.getMediaItemAt(i).mediaId }.getOrNull() ?: continue
            if (plays.leftAtItsEnd(id)) return id
        }
        return null
    }

    /**
     * The transition tells us how the play before it ended, and what the new one is starting as.
     *
     * The player never says "the previous song was skipped"; it says "we moved to this one, by
     * seeking". So the reason is recorded against the play that was in progress, to be picked up
     * when its playback stats arrive, and the run of consecutive autoplays is counted so a song that
     * played sixth in a radio queue is not weighed like one the listener chose.
     */
    private fun noteTransition(mediaItem: MediaItem?, reason: Int) {
        // Depth resets when the listener chose this song (a new queue, or a tap in the queue sheet,
        // both of which set the mark) and grows otherwise: a song reached by the next button was not
        // chosen, it was the one after the one rejected. A repeat is the same choice again, and a
        // playlist change with no mark is a queue restored at launch or switched to by hand.
        val chosen = userChoicePending
        userChoicePending = false
        val tap = pendingTap.takeIf { chosen }
        pendingTap = null
        val origin = pendingOrigin
        pendingOrigin = null
        // Going back is a choice, and it used to be counted as the opposite of one. See
        // AutoplayDepth, which holds the rule so a test can hold it to account.
        val wentBack = AutoplayDepth.wentBack(reason, lastQueueIndex, activePlayer.currentMediaItemIndex)
        transitionWentBack = wentBack
        transitionChosen = chosen
        autoplayRun = AutoplayDepth.next(autoplayRun, chosen, wentBack, reason)
        lastQueueIndex = activePlayer.currentMediaItemIndex
        checkpointJob?.cancel()
        val q = queueBoard.getCurrentQueue()
        val id = mediaItem?.mediaId ?: run { plays.transition(reason, null, null); return }
        val info = StartInfo(
            origin = origin?.code ?: q?.origin ?: PlayOrigin.UNKNOWN.code,
            originSlot = q?.originSlot ?: -1,
            queueId = q?.id ?: 0L,
            learn = q?.learn ?: true,
            autoplayDepth = autoplayRun,
            runId = q?.runId ?: 0L,
            tappedAt = tap,
        )
        // The play in progress was left for this reason, and this one begins.
        plays.transition(reason, id, info)
        if (!volumeReceiverRegistered) registerVolumeReceiver()
        // A loaded item is not a listen: the queue restored at launch sits here unplayed. The row
        // opens when sound starts, which is now if playback carried straight over, and otherwise
        // in onIsPlayingChanged.
        if (activePlayer.isPlaying) openListen(id, info)
        checkpointJob = offloadScope.launch {
            while (true) {
                delay(CHECKPOINT_MS)
                // Three outcomes, where there used to be two. The loop ends when the track changes,
                // as before, but a paused track is not a finished one: it used to keep writing the
                // same position once a minute for as long as it sat there, and each write
                // invalidates every Room flow watching that table, so a paused app was re-running
                // other people's queries once a minute forever. Now it simply waits.
                // The position is read and checkpointListen accumulates it in this one Main
                // dispatch, because a seek (onPositionDiscontinuity) and the pause checkpoint
                // (onIsPlayingChanged) change the same two fields on Main. Were the accumulate to
                // run on this offload thread instead, a seek could land between the read and the
                // write and have its reference point overwritten with a stale one.
                val finished = withContext(Dispatchers.Main) {
                    when {
                        activePlayer.currentMediaItem?.mediaId != id -> true
                        !activePlayer.isPlaying -> false
                        else -> { checkpointListen(info, activePlayer.currentPosition); false }
                    }
                }
                if (finished) break
            }
        }
    }

    /** One of the day's ten related refreshes, if any are left; the count starts over each day. */
    private suspend fun takeRelatedRefreshBudget(): Boolean {
        val today = System.currentTimeMillis() / 86_400_000L
        var taken = false
        dataStore.edit { prefs ->
            val used = if (prefs[RelatedRefreshDayKey] == today) prefs[RelatedRefreshCountKey] ?: 0 else 0
            if (used < RELATED_REFRESH_PER_DAY) {
                prefs[RelatedRefreshDayKey] = today
                prefs[RelatedRefreshCountKey] = used + 1
                taken = true
            }
        }
        return taken
    }

    /**
     * Opens the listen row the moment a song starts, so a play the app dies in the middle of still
     * leaves a usable record, and links it to an earlier fragment when this is the listener coming
     * back to where they left off: same song, resumed within five seconds of where it stopped, in
     * the last day. A stop is not a verdict; the chain is graded once, as one listen.
     */
    private fun openListen(mediaId: String, info: StartInfo) {
        info.opened = true
        info.startedAt = System.currentTimeMillis()
        info.startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
        info.lastCheckpointPositionMs = info.startPositionMs
        if (listenHistoryPaused) { info.rowReady.complete(0L); return }
        val metadata = activePlayer.currentMediaItem?.takeIf { it.mediaId == mediaId }?.metadata
        database.transaction {
            runCatching {
                // The row points at the song table, and recoverSong may still be fetching the
                // length before it writes the song; a song with no length yet is still a song.
                if (!songExists(mediaId)) metadata?.let { insert(it) }
                val previous = lastStoppedListen(mediaId)
                val continues = previous?.takeIf {
                    ListenProgress.continues(it.endReason, it.endPositionMs, it.startedAt, it.endedAt, info.startPositionMs, info.startedAt)
                }?.id
                val last = lastListen()
                // lastListen skips open rows, and the song that just ended can still be one: its
                // close waits behind other work while this insert runs at once. The newest open
                // row competes with the last closed one under the same gap rule.
                val open = openListens().firstOrNull()
                val sessionId = ListenProgress.sessionIdFor(
                    startedAt = info.startedAt,
                    openSessionId = open?.sessionId,
                    openLastKnownAt = open?.let { it.startedAt + it.playedMs },
                    lastEndedAt = last?.endedAt,
                    lastSessionId = last?.sessionId,
                    sessionGapMs = SESSION_GAP_MS,
                )
                val rowId = insert(
                    Listen(
                        songId = mediaId, startedAt = info.startedAt, endedAt = 0L,
                        tzOffsetMin = java.util.TimeZone.getDefault().getOffset(info.startedAt) / 60_000,
                        playedMs = 0L, durationMs = -1L, ratio = -1f, endReason = EndReason.OPEN,
                        origin = info.origin, originSlot = info.originSlot, queueId = info.queueId,
                        autoplayDepth = info.autoplayDepth, sessionId = sessionId, counted = false,
                        learn = info.learn, runId = info.runId, endPositionMs = -1L,
                        continuesListenId = continues,
                        contextChip = contextChip,
                        // The card's impression was marked with the same moment by the tap itself,
                        // on this same serial executor, so it is there to be found.
                        impressionId = info.tappedAt?.let { impressionIdByTap(it) },
                        tappedAt = info.tappedAt,
                    )
                )
                info.rowId = rowId
                info.rowReady.complete(rowId)
            }.onFailure { Log.w(TAG, "Could not open listen", it); info.rowReady.complete(0L) }
        }
    }

    /**
     * How far this play got, written to its open row: a death then loses at most a minute. An
     * accumulator, not a snapshot against the play's start: only the forward distance since the
     * last checkpoint (or the last seek) is added, so rewinding to relisten to an earlier part
     * cannot erase progress already made.
     */
    private fun checkpointListen(info: StartInfo, positionMs: Long) {
        val rowId = info.rowId.takeIf { it > 0 } ?: return
        val playedMs = ListenProgress.accumulate(info.accumulatedPlayedMs, info.lastCheckpointPositionMs, positionMs)
        info.accumulatedPlayedMs = playedMs
        info.lastCheckpointPositionMs = positionMs
        database.query {
            runCatching { checkpoint(rowId, playedMs, positionMs) }
                .onFailure { Log.w(TAG, "Could not checkpoint listen", it) }
        }
    }

    /**
     * Rows left open by a death are closed as stopped, from their last checkpoint, at the next
     * start. The length comes from the song table, so the ratio and the counted flag mean the same
     * as on a row the player closed itself.
     */
    private fun closeOrphanedListens() {
        val threshold = (dataStore.get(minPlaybackDurKey, 30).toFloat() / 100).coerceIn(0.01f, 0.99f)
        database.query {
            runCatching {
                openListens().forEach { open ->
                    val durationMs = songDurationSec(open.songId)?.takeIf { it > 0 }?.times(1000L) ?: -1L
                    val ratio = if (durationMs > 0) open.playedMs.toFloat() / durationMs else -1f
                    update(open.copy(
                        endReason = EndReason.STOPPED, endedAt = open.startedAt + open.playedMs,
                        durationMs = durationMs, ratio = ratio, counted = ratio >= threshold,
                    ))
                }
            }.onFailure { Log.w(TAG, "Could not close orphaned listens", it) }
        }
    }

    private fun noteSignal(mediaId: String, kind: Int, positionMs: Long = -1L, value: Float = 0f) {
        if (dataStore.get(PauseListenHistoryKey, false)) return
        val now = System.currentTimeMillis()
        val listenId = currentStart(mediaId)?.rowId?.takeIf { it > 0 }
        database.query {
            runCatching { insertSignal(ListenSignal(listenId = listenId, songId = mediaId, kind = kind, positionMs = positionMs, value = value, at = now)) }
                .onFailure { Log.w(TAG, "Could not record signal", it) }
        }
    }

    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return
            if (!activePlayer.isPlaying || isCasting) return
            val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val before = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (now < 0 || before < 0 || now == before) return
            val id = activePlayer.currentMediaItem?.mediaId ?: return
            noteSignal(id, if (now > before) SignalKind.VOLUME_UP else SignalKind.VOLUME_DOWN, activePlayer.currentPosition, (now - before).toFloat())
        }
    }

    private fun registerVolumeReceiver() {
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, volumeReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            volumeReceiverRegistered = true
        }.onFailure { Log.w(TAG, "Could not listen for volume changes", it) }
    }

    /**
     * Where the song that just ended was when it ended, and seeks inside the same song. Back is
     * "hear that again"; forward past a part is a mild no.
     */
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        if (isCasting) return
        val itemChanged = oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
            oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId ||
            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        if (itemChanged) {
            oldPosition.mediaItem?.mediaId?.let { id ->
                lastKnownPosition[id] = oldPosition.positionMs
                // Before the transition hands the play over: it is still this song's.
                currentStart(id)?.takeIf { it.opened }?.let { info ->
                    lastKnownHeard[id] = info.startPositionMs +
                        ListenProgress.accumulate(info.accumulatedPlayedMs, info.lastCheckpointPositionMs, oldPosition.positionMs)
                }
            }
            return
        }
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        val id = newPosition.mediaItem?.mediaId ?: return
        // Credit what was played since the last checkpoint, up to where the seek left from, then
        // measure from where it landed. The jump is not played time. A play not yet started
        // credits nothing; openListen sets its reference point.
        currentStart(id)?.let {
            it.accumulatedPlayedMs = ListenProgress.creditBeforeSeek(it.opened, it.accumulatedPlayedMs, it.lastCheckpointPositionMs, oldPosition.positionMs)
            it.lastCheckpointPositionMs = newPosition.positionMs
        }
        val delta = newPosition.positionMs - oldPosition.positionMs
        if (kotlin.math.abs(delta) < 3_000) return
        noteSignal(id, if (delta < 0) SignalKind.SEEK_BACK else SignalKind.SEEK_FORWARD, newPosition.positionMs, delta / 1000f)
    }

    /**
     * Writes the complete record of a stop, whatever fraction was heard, for a play [closed] has
     * already taken out of the book with how it ended (see [PlayBook.close]).
     */
    private suspend fun logListen(
        mediaId: String,
        closed: PlayBook.Closed<StartInfo>,
        playbackStats: PlaybackStats,
        durationSec: Int,
        ratio: Float,
        counted: Boolean,
    ): Long {
        val endedAt = System.currentTimeMillis()
        val info = closed.play
        val endReason = closed.endReason
        // Zero is "never opened", not a time: the start info exists before its listen is opened,
        // and one that closed without opening wrote its whole row dated 1970 (one in a real
        // library, a radio play on 19 Sep). The fallback covers it the same as a missing info.
        val startedAt = info?.startedAt?.takeIf { it > 0L }
            ?: (endedAt - playbackStats.totalPlayTimeMs - playbackStats.totalPausedTimeMs)
        val offsetMin = java.util.TimeZone.getDefault().getOffset(endedAt) / 60_000
        val durationMs = if (durationSec > 0) durationSec * 1000L else -1L
        val endPosition = lastKnownPosition.remove(mediaId) ?: if (endReason == EndReason.ENDED) durationMs else -1L
        // The row was opened when the song started; this closes it. Only a missing open row (the
        // insert failed) falls through to writing a whole row now.
        val openId = info?.takeIf { it.opened }?.let { kotlinx.coroutines.withTimeoutOrNull(2_000) { it.rowReady.await() } } ?: 0L
        if (openId > 0L) {
            database.transaction {
                runCatching {
                    val open = openListens().firstOrNull { it.id == openId } ?: return@transaction
                    update(open.copy(
                        endedAt = endedAt, playedMs = playbackStats.totalPlayTimeMs, durationMs = durationMs,
                        ratio = ratio, endReason = endReason, counted = counted, endPositionMs = endPosition,
                    ))
                }.onFailure { Log.w(TAG, "Could not close listen", it) }
            }
            return openId
        }
        val written = kotlinx.coroutines.CompletableDeferred<Long>()
        database.transaction {
            // A session is a run of listening with no gap over 30 minutes, measured from the end of
            // one play to the start of the next. The same boundary Flow and the ACT-R relistening
            // work use; Spotify's own is 20. Caught, because this runs on Room's executor where an
            // exception is fatal to the process, and a lost data point is the correct failure.
            runCatching {
            val last = lastListen()
            val sessionId = if (last == null || startedAt - last.endedAt > SESSION_GAP_MS) startedAt else last.sessionId
            val rowId = insert(
                Listen(
                    songId = mediaId,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    tzOffsetMin = offsetMin,
                    playedMs = playbackStats.totalPlayTimeMs,
                    durationMs = if (durationSec > 0) durationSec * 1000L else -1L,
                    ratio = ratio,
                    endReason = endReason,
                    origin = info?.origin ?: PlayOrigin.UNKNOWN.code,
                    originSlot = info?.originSlot ?: -1,
                    queueId = info?.queueId ?: 0L,
                    autoplayDepth = info?.autoplayDepth ?: 0,
                    sessionId = sessionId,
                    counted = counted,
                    learn = info?.learn ?: true,
                )
            )
            written.complete(rowId)
            }.onFailure { Log.w(TAG, "Could not log listen", it); written.complete(0L) }
        }
        return kotlinx.coroutines.withTimeoutOrNull(2_000) { written.await() } ?: 0L
    }

    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        offloadScope.launch {
            val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            var minPlaybackDur = (dataStore.get(minPlaybackDurKey, 30).toFloat() / 100)
            // ensure within bounds
            if (minPlaybackDur >= 1f) {
                minPlaybackDur = 0.99f // Ehhh 99 is good enough to avoid any rounding errors
            } else if (minPlaybackDur < 0.01f) {
                minPlaybackDur = 0.01f // Still want "spam skipping" to not count as plays
            }

            // A song tapped in search results reaches the player with duration -1, because the
            // Songs rows of a search response carry no length. Dividing by -1 made the ratio
            // negative, so a song played to the end was never counted, never got an event, and
            // never reached YouTube's history. recoverSong has already written the real length to
            // the database by the time playback ends, so ask there before giving up.
            val durationSec = mediaItem.metadata?.duration?.takeIf { it > 0 }
                ?: database.song(mediaItem.mediaId).first()?.song?.duration?.takeIf { it > 0 }
                ?: -1
            val playRatio =
                if (durationSec > 0) playbackStats.totalPlayTimeMs.toFloat() / (durationSec * 1000) else -1f
            Log.d(TAG, "Playback ratio: $playRatio Min threshold: $minPlaybackDur (duration ${durationSec}s)")
            val historyPaused = dataStore.get(PauseListenHistoryKey, false)
            val counted = playRatio >= minPlaybackDur
            var listenId = 0L
            // How this play ended, once its row is written: its own, never another play's.
            var endReason: Int? = null
            // The complete record, under the same privacy switch as the counted play. Every song
            // that actually sounded goes in, however briefly: a second of something before
            // skipping it is still a fact about what was played, and the row carries `counted` so
            // the engine and the play count can tell a glance from a listen. Only a song that
            // never produced any audio at all is left out, because that is the player settling
            // rather than anything the user did.
            if (playbackStats.totalPlayTimeMs <= 0L) {
                // A play that never sounded, which has no row, or a session Media3 opened for an
                // item it only loaded ahead, which is no play at all: see PlayBook.takeUnplayed.
                plays.takeUnplayed(mediaItem.mediaId)?.let { lastKnownPosition.remove(mediaItem.mediaId) }
            } else if (historyPaused) {
                plays.takeOldest(mediaItem.mediaId)
                lastKnownPosition.remove(mediaItem.mediaId)
            } else {
                listenId = runCatching {
                    val closed = plays.close(mediaItem.mediaId, endedByPlayer = playbackStats.endedCount > 0) { delay(300) }
                    endReason = closed.endReason
                    logListen(mediaItem.mediaId, closed, playbackStats, durationSec, playRatio, counted)
                }.onFailure { Log.w(TAG, "Could not log listen", it) }.getOrDefault(0L)
            }
            // Rest songs I skip: a skip before the middle of a liked or often-played song rests it
            // from the engine row for a week, so a mood does not become a verdict.
            if (!historyPaused && playRatio in 0f..0.5f && playbackStats.totalPlayTimeMs >= 30_000 &&
                endReason == EndReason.SKIPPED && dataStore.get(RestSongsISkipKey, false)
            ) {
                val title = mediaItem.metadata?.title ?: mediaItem.mediaId
                database.query {
                    runCatching {
                        val liked = isLiked(mediaItem.mediaId)
                        if (liked != null && (liked || playCountOf(mediaItem.mediaId) >= 3) && exclusion(1, mediaItem.mediaId) == null) {
                            val at = System.currentTimeMillis()
                            insertExclusion(RecommendationExclusion(kind = 1, targetId = mediaItem.mediaId, label = title, reason = 3, createdAt = at, expiresAt = at + 7L * 86_400_000))
                        }
                    }.onFailure { Log.w(TAG, "Could not rest a skipped song", it) }
                }
            }
            // A counted play: the play count, the event Most played and the other play lists read,
            // YouTube's history and Last.fm. History lists the listen written above once it has
            // been heard for two seconds (HistoryRule), so it needs nothing from here.
            if (counted && !historyPaused) {
                // One transaction: History lists an event no listen points at yet from the event
                // itself, so between two separate writes the play was listed twice.
                database.transaction {
                    incrementPlayCount(mediaItem.mediaId)
                    try {
                        val eventId = insert(
                            Event(
                                songId = mediaItem.mediaId,
                                timestamp = LocalDateTime.now(),
                                playTime = playbackStats.totalPlayTimeMs
                            )
                        )
                        // The listen and the legacy event are one play; the link keeps the
                        // backfill of old events from ever writing this one a second time.
                        if (listenId > 0L && eventId > 0L) setListenSource(listenId, eventId)
                    } catch (_: SQLException) {
                    }
                }

                // Last.fm, on the same condition the app uses for its own play count, and with
                // Last.fm's own rule applied inside the scrobbler on top. Fired after the local
                // write so a network stall can never delay the thing the user can actually see.
                // Not on the service scope: the song playing when the service goes is reported
                // here only after onDestroy has cancelled that.
                mediaItem.metadata?.let { meta ->
                    lastingScope.launch {
                        scrobbler.scrobble(
                            metadata = meta,
                            playedMs = playbackStats.totalPlayTimeMs,
                            // When it STARTED, which is what Last.fm orders history by. Using the
                            // finish time would shift every entry by the length of the song.
                            startedAtSeconds = (System.currentTimeMillis() -
                                    playbackStats.totalPlayTimeMs) / 1000,
                            // The recovered length, not the metadata's: a song started from
                            // search results still says -1 there, and never scrobbled.
                            durationSeconds = durationSec,
                        )
                    }
                }

                // TODO: support playlist id
                // Throttle names history pings as work to drop while blocked, and this one costs a
                // whole extra /player per finished song. Nobody asked for it and nobody sees it fail.
                val loggedIn = isUserLoggedIn()
                val ytHist = ListenReporting.pingsYouTubeHistory(
                    isLocal = mediaItem.metadata?.isLocal == true,
                    loggedIn = loggedIn,
                    remoteHistoryPaused = dataStore.get(PauseRemoteListenHistoryKey, false),
                    throttled = Throttle.isBlocked,
                )
                Log.d(TAG, "Trying to register remote history: $ytHist")
                if (ytHist) {
                    // The account's request first, for an address issued to the channel that is
                    // signed in, and the visitor's, the only one there used to be, when that gives
                    // none or has not answered in time.
                    val requests = ListenReporting.addressRequests(loggedIn = loggedIn, authMode = YTPlayerUtils.authMode)
                    var accountAnswer: YTPlayerUtils.AccountAnswer? = null
                    val found = ListenReporting.firstAddress(requests) { from ->
                        when (from) {
                            // By the id the song was played from: its own is gone when it has a
                            // stand-in, and YouTube gives no address for an id it does not serve.
                            AddressFrom.ACCOUNT ->
                                YTPlayerUtils.playerResponseAsAccount(StandInMemory.idFor(mediaItem.mediaId)).also { accountAnswer = it }.address
                            AddressFrom.VISITOR ->
                                YTPlayerUtils.addressIn(YTPlayerUtils.playerResponseForMetadata(StandInMemory.idFor(mediaItem.mediaId), null))
                        }
                    }
                    val playbackUrl = found?.second
                    // Whose request the address came from, said only when the account was asked.
                    val addressFrom = found?.first?.takeIf { AddressFrom.ACCOUNT in requests }
                    accountAnswer?.let { asked ->
                        val failure = asked.answer?.exceptionOrNull()
                        val line = ListenReporting.addressSourceLine(
                            used = found?.first,
                            accountStatus = asked.answer?.getOrNull()?.playabilityStatus?.status,
                            accountFailure = failure,
                            httpStatus = (failure as? ResponseException)?.response?.status?.value,
                            without = asked.without,
                            outOfTimeAt = asked.outOfTimeAt,
                        )
                        Log.d(TAG, line)
                    }
                    // Never the address itself, nor a failure's own words, which can quote it: its
                    // parameters name the video, the session and the player.
                    Log.d(TAG, "Got playback url: ${ListenReporting.trackingAddressForLog(playbackUrl)}")
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onSuccess { status ->
                                val line = ListenReporting.historyAnswerLine(status, addressFrom)
                                if (ListenReporting.historyReportTaken(status)) Log.d(TAG, line) else Log.w(TAG, line)
                            }
                            .onFailure { Log.w(TAG, ListenReporting.historyFailureLine(it)) }
                    }
                }
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        // Only repeat-one says something about this song; all and off say something about the queue.
        val kind = when {
            repeatMode == Player.REPEAT_MODE_ONE -> SignalKind.REPEAT_ONE_ON
            lastRepeatMode == Player.REPEAT_MODE_ONE -> SignalKind.REPEAT_ONE_OFF
            else -> null
        }
        lastRepeatMode = repeatMode
        if (kind != null && !isCasting) activePlayer.currentMediaItem?.mediaId?.let { id -> noteSignal(id, kind, activePlayer.currentPosition) }
        updateNotification()
        offloadScope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val q = queueBoard.getCurrentQueue()
        exoPlayer.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(exoPlayer.mediaItemCount))
        if (q == null || q.shuffled == shuffleModeEnabled) {
            // The button reads the player's flag, so it has to be redrawn even when the queue has
            // nothing to change, or it shows the old state after a resume sets the flag.
            updateNotification()
            return
        }
        triggerShuffle()
    }


    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // FG keep alive. Never skipped when media3 has to start the foreground: a service started
        // by a headset or the widget must reach startForeground, and a cold start is still
        // buffering, not playing, so skipping it here had Android kill the app ten seconds later.
        //
        // A cast keeps it in the foreground, paused too: the receiver fetches every song from the
        // server here, and a service out of the foreground loses the network in Doze.
        if (startInForegroundRequired || activePlayer.isPlaying || isCasting || !dataStore.get(KeepAliveKey, false)) {
            super.onUpdateNotification(session, startInForegroundRequired || isCasting)
        }
    }

    /**
     * What the home screen widget draws is a snapshot on disk, written here whenever the song or
     * the playback state changes. Off the main thread, never blocking playback, and when nobody
     * has added a widget it writes a line of text and fetches nothing.
     */
    private fun updateWidget(stopped: Boolean = false) {
        lastingScope.launch {
            runCatching {
                WidgetStore.setNowPlaying(this@MusicService) {
                    if (stopped) WidgetStore.NowState(currentMediaMetadata.value, false)
                    else withContext(Dispatchers.Main) { WidgetStore.NowState(currentMediaMetadata.value, activePlayer.isPlaying) }
                }
            }.onFailure { Log.w(TAG, "Could not update the widget", it) }
        }
    }

    override fun onDestroy() {
        // Checked by playQueue's resolve coroutine each time it resumes: that coroutine belongs to
        // no scope this method cancels (see the comment at its launch site), so this is the only
        // way it can find out the service is gone before touching the queue board or the player.
        destroyed = true
        // Back on the phone before anything is saved or released: the queue then saves the
        // receiver's song and position, and the release below reaches the phone's own player.
        castSession?.release()
        castSession = null
        if (Unreleased.AUTO_REWORK) carType.removeObserver(carWatch)
        headTracking.stop(glideHome = false)
        proximityVolume.stop()
        isRunning = false
        // The widget keeps the song and loses the pause: its play button then resumes the queue
        // through the same receiver a headset button uses. Read as stopped rather than from the
        // player, which is about to be released.
        updateWidget(stopped = true)
        if (volumeReceiverRegistered) runCatching { unregisterReceiver(volumeReceiver) }
        // The audio manager holds the callback, and through it this instance, until it is let go.
        if (resumeOnBluetooth) runCatching { getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(bluetoothOutputs) }
        if (fakeBluetoothOutputRegistered) runCatching { unregisterReceiver(fakeBluetoothOutput) }
        checkpointJob?.cancel()
        // The observers, the sleep timer and the retry and radio jobs end here. Nothing that has to
        // finish is on this scope: the last song's listen row and scrobble are reported when the
        // player below is released, after this returns, and go through offloadScope and
        // lastingScope. A volume change still inside its one-second debounce would die with the
        // observer waiting to save it, so it is saved now.
        val volume = playerVolume.value
        lastingScope.launch { dataStore.edit { it[PlayerVolumeKey] = volume } }
        // The radios' memory, now rather than after its wait, which dies with the scope.
        if (adaptiveMemoryWrite != null) writeAdaptiveMemory()
        scope.cancel()
        // The static would keep this instance, its addresses and routes, until the next service
        // replaced it; and the stall watch's tick is not left to the player's last events.
        if (YTPlayerUtils.onLateRefusal === lateRefusal) YTPlayerUtils.onLateRefusal = null
        stallWatching = false
        stallHandler.removeCallbacks(stallTick)
        // The timer ran on the scope, so its notification would stay up, frozen, for a timer that
        // no longer exists.
        sleepTimer.clear()
        sleepTimerNotification.hide()
        // ConnectivityManager holds the callback, and through the collector, this instance, for as
        // long as it stays registered. The unregister in onCreate cannot let go of it: it runs on
        // the next instance, whose field is still empty.
        if (::connectivityObserver.isInitialized) runCatching { connectivityObserver.unregister() }
        // A receiver left fetching from a service that is gone gets a closed connection and says
        // so, rather than waiting on a socket nothing answers.
        castProbe?.unregister()
        castProbe = null
        stopCastServer()
        if (screenOffRegistered) runCatching { unregisterReceiver(screenOff) }
        // Ending while the music plays is Android stopping the service, not the person: say so, and
        // keep it where Report a problem finds it after the process is gone too.
        if (activePlayer.isPlaying) {
            val line = StopReasons.endedLine(screenOn())
            Log.w(TAG, line)
            StopReasons.keep(this, line)
        }
        Log.i(TAG, "Terminating MusicService.")

        // Only clear it if it is still ours; a newer service instance may already have replaced it.
        if (loudnessRepair.nowPlayingIdProvider === installedNowPlayingProvider) {
            loudnessRepair.nowPlayingIdProvider = { null }
        }
        installedNowPlayingProvider = null
        deInitQueue()

        mediaSession.player.stop()
        mediaSession.release()
        mediaSession.player.release()
        super.onDestroy()
        Log.i(TAG, "Terminated MusicService.")
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    /**
     * The screen going off while music plays (issue #26): Android stops a service that is not in
     * the foreground about a minute after the app leaves the screen, and whether this one is in it
     * can only be read while it lives. A line each time, kept with the stops when it is not.
     */
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!activePlayer.isPlaying) return
            when (inForeground()) {
                false -> {
                    Log.w(TAG, StopReasons.SCREEN_OFF_OUT_OF_FOREGROUND)
                    StopReasons.keep(this@MusicService, StopReasons.SCREEN_OFF_OUT_OF_FOREGROUND)
                }
                true -> Log.i(TAG, "The screen went off while playing, the player's service in the foreground")
                null -> Unit
            }
        }
    }
    private var screenOffRegistered = false

    /** Whether the screen is on, for the lines that say why the music stopped. */
    private fun screenOn(): Boolean? = getSystemService<PowerManager>()?.isInteractive

    /** Whether this service is in the foreground, which Android 10 and later can say. */
    private fun inForeground(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved called")
        if (dataStore.get(StopMusicOnTaskClearKey, true) && !dataStore.get(KeepAliveKey, false)) {
            Log.i(TAG, "onTaskRemoved kill")
            pauseAllPlayersAndStopSelf()
        } else {
            Log.i(TAG, "onTaskRemoved def")
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        /** How long after the stall watch gave a song up the player's own error on it is taken for the same fault. */
        private const val STALL_ECHO_MS = 60_000L

        /**
         * Whether a service instance is alive, which decides whether the widget's buttons can
         * speak to it directly or have to go the long way round, through the media button
         * receiver that also starts it.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Normalisation reference, in dB relative to YouTube's -14 LKFS target.
         *
         * We can only attenuate: ExoPlayerImpl.setVolume hard-clamps to [0,1], so gain above unity
         * is impossible without an AudioProcessor. That makes output loudness min(track, reference),
         * so the reference alone decides how consistent playback is. Every track louder than it is
         * pulled down to it; every track quieter is left alone.
         *
         * Set for consistency rather than loudness, which is the tradeoff that was chosen
         * deliberately: it is easy to turn the device up, and impossible to un-hear one track
         * blasting after another. Measured across 3285 tracks from a real library, whose loudness
         * ran -16.5 to +13.3 relative to the target:
         *
         *     reference   tracks at identical loudness   sd    worst attenuation
         *        +6                 53%                  high      -7 dB
         *         0                 94%                  0.87     -13 dB
         *        -3                 98%                  0.49     -16 dB
         *        -4.3               99%                  0.39     -17.5 dB
         *        -6                 99.5%                0.29     -19 dB
         *
         * -3 sits at the knee. Below it each extra dB of attenuation buys almost no consistency,
         * and playback gets quiet enough that some devices cannot make it up on the hardware side.
         *
         * Attenuation costs no audio quality worth worrying about: it is a multiply, Android mixes
         * in float, and even treating it as 16-bit integer the worst case here leaves about 80 dB
         * SNR. What it does cost is headroom on the device volume slider.
         */
        const val NORMALIZATION_REFERENCE_DB = -3f

        /**
         * Loudness assumed for a track whose real value is not known, in dB above -14 LKFS.
         *
         * Only reachable when normalisation is on and the stored loudness is null or nonsense. The
         * previous behaviour was to apply no attenuation at all, which is the loudest possible
         * answer to "I do not know" and is why a handful of tracks blared.
         *
         * Chosen against the 3178 real values in the library, not picked by feel. The residual
         * error for an unknown track is simply trueLoudness minus this number, so a high guess
         * makes it slightly quiet and a low guess makes it loud:
         *
         *     assume median  6.25 -> 50% of tracks still too loud, worst +6.3 dB
         *     assume p90     9.63 -> 10% still too loud, worst +2.9 dB
         *     assume 10.0         ->  6% still too loud, worst +2.5 dB   <- here
         *     assume max    12.56 ->  0% too loud, but everything unknown is 6 dB quiet
         *
         * 10 sits just past p90. Roughly 6% of unknown tracks stay marginally loud, by an amount
         * under the ~3 dB most people notice, and the rest play about 3.8 dB quieter than they
         * strictly should. That asymmetry is deliberate and matches how this actually gets used:
         * a track that is a bit quiet is a shrug, a track that is 16 dB loud is a jump scare.
         *
         * This is a safety net, not the fix. The fix is repairing the stored value, after which
         * almost nothing reaches this branch. See the loudness repair scan in settings.
         */
        const val UNKNOWN_LOUDNESS_DB = 10f

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SEARCH = "search"

        /** How often the sleep timer countdown is redrawn while a timer is running. */
        private const val SLEEP_TIMER_NOTIF_TICK_MS = 30_000L

        /** How often to look for a newly armed timer. Cheap: it is one boolean read. */

        const val CHANNEL_ID = "music_channel_01"
        const val CHANNEL_NAME = "fgs_workaround"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L

        /** media3's own floor, kept for everyone who is not chasing their own head. */
        /** Nothing real is quicker than this over any link, so anything under it is a miscount. */
        const val MIN_PLAUSIBLE_LATENCY = 0.02f

        /** Nor slower than this, so a seek cannot drag the prediction somewhere absurd. */
        const val MAX_PLAUSIBLE_LATENCY = 0.60f

        /** Slow, because this only has to track a codec change, not a head. */
        const val LATENCY_SMOOTHING = 0.2f

        /** Roughly LDAC over A2DP, which is the codec worth using. Tuned from here by ear. */
        const val DEFAULT_LEAD_MS = 260

        const val DEFAULT_BUFFER_US = 250_000

        /**
         * Short rather than reckless: media3 still floors the real buffer at four times whatever
         * the system says its minimum is, so this only bites where that floor is lower. If it
         * ever crackles, this is the number that did it.
         */
        const val LOW_LATENCY_BUFFER_US = 90_000

        const val COMMAND_GET_BINDER = "GET_BINDER"
    }
}

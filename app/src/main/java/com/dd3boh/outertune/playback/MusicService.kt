/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.RelatedRefreshCountKey
import com.dd3boh.outertune.constants.RelatedRefreshDayKey
import com.dd3boh.outertune.engine.DatabaseBackfillIo
import com.dd3boh.outertune.engine.LegacyBackfill
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
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
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
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
import com.dd3boh.outertune.constants.AudioOffloadKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.MAX_PLAYER_CONSECUTIVE_ERR
import com.dd3boh.outertune.constants.RELATED_RETRY_COOLDOWN_MS
import com.dd3boh.outertune.constants.MaxQueuesKey
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleLike
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleShuffle
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleStartRadio
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.PauseRemoteListenHistoryKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.ResumePlaybackOnLaunchKey
import com.dd3boh.outertune.constants.PlayerVolumeKey
import com.dd3boh.outertune.constants.RepeatModeKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeDurationKey
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import com.dd3boh.outertune.constants.ShareAudioFocusKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
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
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.setOffloadEnabled
import com.dd3boh.outertune.lyrics.LyricsHelper
import com.dd3boh.outertune.models.HybridCacheDataSinkFactory
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.utils.CoilBitmapLoader
import com.dd3boh.outertune.utils.LoudnessRepair
import com.dd3boh.outertune.utils.NetworkConnectivityObserver
import com.dd3boh.outertune.utils.Scrobbler
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.FailureMemo
import com.dd3boh.outertune.utils.Throttle
import com.dd3boh.outertune.utils.SongVersions
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.playerCoroutine
import com.dd3boh.outertune.utils.reportException
import com.google.common.util.concurrent.MoreExecutors
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import dagger.hilt.android.AndroidEntryPoint
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
/** Silence longer than this starts a new listening session. */
private const val SESSION_GAP_MS = 30L * 60 * 1000
/** How often an open listen row records how far it got, so a death loses at most this much. */
private const val CHECKPOINT_MS = 60_000L
/** A resume within this of where a stop left off, inside this window, continues that listen. */
private const val RESUME_TOLERANCE_MS = 5_000L
private const val RESUME_WINDOW_MS = 24L * 60 * 60 * 1000
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
    private val scope = CoroutineScope(Dispatchers.Main)
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

    val qbInit = MutableStateFlow(false)
    var queueBoard = QueueBoard(this, maxQueues = 1)
    var queuePlaylistId: String? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    // Player components
    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var scrobbler: Scrobbler

    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(true)

    lateinit var sleepTimer: SleepTimer

    private val sleepTimerNotification by lazy { SleepTimerNotification(this) }

    /**
     * Applies gain on the PCM stream, which is the only way to exceed unity: player.volume is
     * clamped to [0,1]. Shared by both sink builders below so there is one instance whichever
     * decoder path is in use.
     */
    val gainProcessor = GainAudioProcessor()

    // Player vars
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)

    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(offloadScope, SharingStarted.Lazily, null)

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
    private val isGaplessOffloadAllowed = dataStore.get(AudioGaplessOffloadKey, false)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    private var isAudioEffectSessionOpened = false

    var consecutivePlaybackErr = 0

    override fun onCreate() {
        Log.i(TAG, "Starting MusicService")
        super.onCreate()

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

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // handleAudioFocus. On, InterTune claims audio focus, which is what silences
                // whatever else was playing, and it also pauses InterTune when something else
                // claims it. Off does both halves of what upstream #1255 asks for: it stops
                // interrupting others and stops being interrupted. Read once here because audio
                // attributes are fixed when the player is built, so a change applies at the next
                // start rather than mid-song.
                !dataStore.get(ShareAudioFocusKey, false)
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                // listeners
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

                // misc
                setOffloadEnabled(dataStore.get(AudioOffloadKey, false))
            }

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }

        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // TODO: do i even want to have smaller art for media notification
            .setBitmapLoader(CoilBitmapLoader(this))
            .build()

        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

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
            if (!qbInit.value) {
                initQueue()
                resumeOnLaunchIfAsked()
            }
            // The legacy play log becomes listens, once; nothing to do after the first run.
            runCatching { LegacyBackfill(DatabaseBackfillIo(database)).run() }
                .onFailure { Log.w(TAG, "Could not backfill the play log", it) }

            combine(
                playerVolume,
                normalizeFactor,
                sleepTimer.fadeFactor
            ) { playerVolume, normalizeFactor, fadeFactor ->
                playerVolume * normalizeFactor * fadeFactor
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
                    player.volume = min(total, 1f) * sleepTimer.fadeFactor.value
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
                        player.skipSilenceEnabled = it
                    }
                }

            // The sleep timer's own notification, and the only thing in this app that can be a
            // Live Update: the media notification draws a custom view, which the platform refuses
            // to promote. Polled rather than observed because triggerTime is Compose state and the
            // thing being shown is a countdown, which has to be redrawn as it counts anyway. A
            // minute is the resolution the text shows, so it is also the resolution it needs.
            scope.launch {
                var shown = false
                while (isActive) {
                    val armed = sleepTimer.isActive
                    if (armed) {
                        val trigger = sleepTimer.triggerTime
                        sleepTimerNotification.show(
                            if (trigger == -1L) null
                            else (trigger - System.currentTimeMillis()).coerceAtLeast(0L)
                        )
                        shown = true
                    } else if (shown) {
                        sleepTimerNotification.hide()
                        shown = false
                    }
                    delay(if (armed) SLEEP_TIMER_NOTIF_TICK_MS else SLEEP_TIMER_NOTIF_IDLE_MS)
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
            connectivityObserver = NetworkConnectivityObserver(this@MusicService)

            offloadScope.launch {
                connectivityObserver.networkStatus.collect { isConnected ->
                    isNetworkConnected.value = isConnected

                    if (isConnected && waitingForNetworkConnection.value) {
                        waitingForNetworkConnection.value = false
                        withContext(Dispatchers.Main) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
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
     * What each song knew about itself when it started, keyed by media id: where its queue came
     * from, how many songs had autoplayed before it, and the wall clock. Read back when its stats
     * arrive, because by then the queue may already be a different one.
     */
    private class StartInfo(
        val origin: Int,
        val originSlot: Int,
        val queueId: Long,
        val learn: Boolean,
        val autoplayDepth: Int,
        val runId: Long,
        val tappedAt: Long?,
    ) {
        /** When and where sound first came out, set when the row is opened, not when the item was loaded. */
        @Volatile var startedAt: Long = 0L
        @Volatile var startPositionMs: Long = 0L
        @Volatile var opened = false
        /** The open listen row for this play: 0 until its insert has run, and completed for whoever waits. */
        @Volatile var rowId: Long = 0L
        val rowReady = kotlinx.coroutines.CompletableDeferred<Long>()
    }
    /**
     * Per song id, oldest first. A song can be playing twice over as far as this bookkeeping is
     * concerned: on repeat-one the next play starts before the stats of the one that ended arrive,
     * so the stats take the oldest entry and everything about the current play uses the newest.
     */
    private val startInfo = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<StartInfo>>()
    private fun currentStart(mediaId: String): StartInfo? = startInfo[mediaId]?.peekLast()
    private fun takeOldestStart(mediaId: String): StartInfo? {
        val plays = startInfo[mediaId] ?: return null
        val oldest = plays.pollFirst()
        if (plays.isEmpty()) startInfo.remove(mediaId, plays)
        return oldest
    }

    /** Set by anything that is the listener choosing a song, read and cleared by the next transition. */
    @Volatile var userChoicePending = false
    /** The tap that started the queue being set up, handed to the listen it produces. */
    @Volatile var pendingTap: Long? = null
    /** An origin for the next play that no queue carries: a system resumption, a play from outside. */
    @Volatile var pendingOrigin: PlayOrigin? = null
    private val lastKnownPosition = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var checkpointJob: kotlinx.coroutines.Job? = null
    private var volumeReceiverRegistered = false
    private var lastRepeatMode = Player.REPEAT_MODE_OFF

    /** How the previous song ended, by media id, written at the transition that ended it. */
    private val pendingEndReasons = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var lastMediaId: String? = null
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

    private suspend fun recoverSong(mediaId: String, playbackData: YTPlayerUtils.PlaybackData? = null) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            // Looked up again here: the listen log inserts a song the moment it starts playing,
            // with whatever length it had, and an insert is ignored if the row already exists.
            val existing = songDurationSec(mediaId)
            if (existing == null) insert(mediaMetadata.copy(duration = duration))
            else if (existing == -1 && duration != -1) setSongDuration(mediaId, duration)
        }
        // A list is fetched once, and fetched again after 90 days for at most ten seeds a day,
        // never while YouTube is throttling us. A legacy list of unknown age (fetchedAt 0) stays.
        val fetchedAt = database.relatedFetchedAt(mediaId)
        val refresh = fetchedAt != null && fetchedAt > 0 &&
            System.currentTimeMillis() - fetchedAt > RELATED_STALE_MS && !Throttle.isBlocked && takeRelatedRefreshBudget()
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
        } finally {
            relatedInFlight.remove(mediaId)
        }
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                val song = it.song.toggleLike()
                update(song)
                downloadUtil.autoDownloadOnLike(song)

                if (!song.isLocal) {
                    syncUtils.likeSong(song)
                }
            }
        }
    }

    fun toggleStartRadio() {
        val mediaMetadata = player.currentMetadata ?: return
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
        if (!qbInit.value) {
            runBlocking(Dispatchers.IO) {
                initQueue()
            }
        }
        // Radio was already a flag; it is also an origin, and the more useful of the two.
        val playOrigin = if (origin == PlayOrigin.UNKNOWN && isRadio) PlayOrigin.RADIO else origin
        // A tap that starts a queue is the listener choosing, and it begins a run: one play of
        // one queue. Queue ids name a title and are reused, so the run is what learning keys on.
        userChoicePending = true
        pendingTap = tappedAt
        val runId = System.currentTimeMillis()

        var queueTitle = title
        queuePlaylistId = queue.playlistId
        var q: MultiQueueObject? = null
        val preloadItem = queue.preloadItem
        // do not use scope.launch ... it breaks randomly... why is this bug back???
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "playQueue: Resolving additional queue data...")
            try {
                if (preloadItem != null) {
                    q = queueBoard.addQueue(
                        queueTitle ?: "Radio\u2060temp",
                        listOf(preloadItem),
                        shuffled = queue.startShuffled,
                        replace = replace,
                        continuationEndpoint = null // fulfilled later on after initial status
                    )
                    q?.origin = playOrigin.code
                    q?.originSlot = originSlot
                    q?.runId = runId
                    queueBoard.setCurrQueue(q, true)
                }

                val initialStatus = withContext(Dispatchers.IO) { queue.getInitialStatus() }
                // do not find a title if an override is provided
                if ((title == null) && initialStatus.title != null) {
                    queueTitle = initialStatus.title

                    if (preloadItem != null && q != null) {
                        queueBoard.renameQueue(q!!, queueTitle)
                    }
                }

                val items = ArrayList<MediaMetadata>()
                Log.d(TAG, "playQueue: Queue initial status item count: ${initialStatus.items.size}")
                if (!initialStatus.items.isEmpty()) {
                    if (preloadItem != null) {
                        items.add(preloadItem)
                        items.addAll(initialStatus.items.subList(1, initialStatus.items.size))
                    } else {
                        items.addAll(initialStatus.items)
                    }
                    val q = queueBoard.addQueue(
                        queueTitle ?: getString(R.string.queue),
                        items,
                        shuffled = queue.startShuffled,
                        startIndex = if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0,
                        replace = replace || preloadItem != null,
                        continuationEndpoint = if (isRadio) items.takeLast(4).shuffled().first().id else null // yq?.getContinuationEndpoint()
                    )
                    q?.origin = playOrigin.code
                    q?.originSlot = originSlot
                    q?.runId = runId
                    queueBoard.setCurrQueue(q, shouldResume)
                }

                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (e: Exception) {
                reportException(e)
                Toast.makeText(this@MusicService, "plr: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }

            Log.d(TAG, "playQueue: Queue additional data resolution complete")
        }
    }

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
                        )
                    )
                }
            } else {
                // enqueue next
                queueBoard.getCurrentQueue()?.let {
                    queueBoard.addSongsToQueue(it, player.currentMediaItemIndex + 1, items.mapNotNull { it.metadata })
                }
            }
        }
    }

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        queueBoard.enqueueEnd(items.mapNotNull { it.metadata })
    }

    fun triggerShuffle() {
        val oldIndex = player.currentMediaItemIndex
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

    suspend fun initQueue() {
        closeOrphanedListens()
        Log.i(TAG, "+initQueue()")
        val persistQueue = dataStore.get(PersistentQueueKey, true)
        val maxQueues = dataStore.get(MaxQueuesKey, 19)
        if (persistQueue) {
            queueBoard = QueueBoard(this, queueBoard.masterQueues, database.readQueue().toMutableList(), maxQueues)
        } else {
            queueBoard = QueueBoard(this, queueBoard.masterQueues, maxQueues = maxQueues)
        }
        Log.d(TAG, "Queue with $maxQueues queue limit. Persist queue = $persistQueue. Queues loaded = ${queueBoard.masterQueues.size}")
        qbInit.value = true
        Log.i(TAG, "-initQueue()")
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
            if (player.mediaItemCount == 0) {
                val loaded = queueBoard.setCurrQueue(shouldResume = true)
                if (loaded == null || player.mediaItemCount == 0) {
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
            player.prepare()
            player.play()
        }
    }

    fun deInitQueue() {
        Log.i(TAG, "+deInitQueue()")
        val pos = player.currentPosition
        queueBoard.shutdown()
        if (dataStore.get(PersistentQueueKey, true)) {
            runBlocking(Dispatchers.IO) {
                saveQueueToDisk(pos)
            }
        }
        // do not replace the object. Can lead to entire queue being deleted even though it is supposed to be saved already
        qbInit.value = false
        Log.i(TAG, "-deInitQueue()")
    }

    suspend fun saveQueueToDisk(currentPosition: Long) {
        val data = queueBoard.getAllQueues()
        data.last().lastSongPos = currentPosition
        database.updateAllQueues(data)
    }


// Audio playback

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
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
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    private fun createCacheDataSource(): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    .build()
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

    private fun createDataSourceFactory(): DataSource.Factory {
        val songUrlCache = HashMap<String, Pair<String, Long>>()
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            Log.d(TAG, "PLAYING: song id = $mediaId")

            var song = queueBoard.getCurrentQueue()?.findSong(dataSpec.key ?: "")
            if (song == null) { // in the case of resumption, queueBoard may not be ready yet
                song = runBlocking { database.song(dataSpec.key).first()?.toMediaMetadata() }
            }
            // local song
            if (song?.localPath != null) {
                if (song.isLocal) {
                    Log.d(TAG, "PLAYING: local song")
                    val file = File(song.localPath)
                    if (!file.exists()) {
                        throw PlaybackException(
                            "File not found",
                            Throwable(),
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }

                    return@Factory dataSpec.withUri(file.toUri())
                } else {
                    val isDownloadNew = downloadUtil.localMgr.getFilePathIfExists(mediaId)
                    isDownloadNew?.let {
                        Log.d(TAG, "PLAYING: Custom downloaded song")
                        return@Factory dataSpec.withUri(it)
                    }
                }
            }

            val isDownload =
                downloadCache.isCached(mediaId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)
            val isCache = playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
            if (isDownload || isCache) {
                Log.d(TAG, "PLAYING: remote song (cache = ${isCache}, download = ${isDownload})")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                Log.d(TAG, "PLAYING: remote song (temp cache)")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            Log.d(TAG, "PLAYING: remote song (online fetch)")

            val playbackData = runBlocking(Dispatchers.IO) {
                val audioQuality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                )
            }.getOrElse { throwable ->
                when (throwable) {
                    is PlaybackException -> throw throwable

                    is ConnectException, is UnknownHostException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is SocketTimeoutException -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }
            val format = playbackData.format

            database.query {
                upsertFormatKeepingLoudness(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.effectiveLoudnessDb,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }
            offloadScope.launch { recoverSong(mediaId, playbackData) }

            val streamUrl = playbackData.streamUrl

            songUrlCache[mediaId] =
                streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
            dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
        }
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
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                arrayOf(gainProcessor),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                }
            }
                .setEnableDecoderFallback(true)
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
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                arrayOf(gainProcessor),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(if (!gaplessOffloadAllowed) OtOffloadSupportProvider(context) else DefaultAudioOffloadSupportProvider(context))
                        .build()
                }
            }
        }
    }


// Misc

    fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(getString(if (queueBoard.getCurrentQueue()?.shuffled == true) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setSessionCommand(CommandToggleShuffle)
                    .setCustomIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off)
                    .build(),
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setCustomIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat_off
                            REPEAT_MODE_ONE -> R.drawable.repeat_one
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder(if (currentSong.value?.song?.liked == true) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build()
            )
        )
    }

    fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
        Toast.makeText(this@MusicService, getString(R.string.wait_to_reconnect), Toast.LENGTH_LONG).show()
    }

    fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_PLAYER_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()

            Toast.makeText(this@MusicService, getString(R.string.err_play_next_on_error), Toast.LENGTH_SHORT).show()
            return
        }

        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_too_many_errors), Toast.LENGTH_LONG).show()
        consecutivePlaybackErr = 0
    }

    fun stopOnError() {
        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_error), Toast.LENGTH_LONG).show()
    }


// Player overrides

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        // wait for reconnection
        val isConnectionError = (error.cause?.cause is PlaybackException)
                && (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        if (dataStore.get(SkipOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }

        Toast.makeText(
            this@MusicService,
            "plr: ${error.message} (${error.errorCode}): ${error.cause?.message ?: ""} ",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            player.currentMediaItem?.mediaId?.let { id -> currentStart(id)?.takeIf { !it.opened }?.let { openListen(id, it) } }
        }
        if (!isPlaying) {
            val pos = player.currentPosition
            // A pause is where a stop will have happened if the app dies now, so the open row
            // learns the position at once rather than at the next minute mark.
            player.currentMediaItem?.mediaId?.let { id ->
                lastKnownPosition[id] = pos
                currentStart(id)?.let { checkpointListen(it, pos) }
            }
            val q = queueBoard.getCurrentQueue()
            q?.lastSongPos = pos
            // Written through at once, so a process the system kills while paused still comes back
            // where it was. Until now the position only reached the database in onDestroy.
            q?.let { mq -> database.query { runCatching { updateQueue(mq) }.onFailure { Log.w(TAG, "Could not save the paused position", it) } } }
        }
        super.onIsPlayingChanged(isPlaying)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        noteTransition(mediaItem, reason)
        // "Listening now" on Last.fm. Not a scrobble, not stored, and allowed to fail quietly.
        mediaItem?.metadata?.let { meta -> scope.launch { scrobbler.nowPlaying(meta) } }
        // +2 when and error happens, and -1 when transition. Thus when error, number increments by 1, else doesn't change
        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }

        if (player.isPlaying && reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            player.prepare()
            player.play()
        }

        // Auto load more songs
        val q = queueBoard.getCurrentQueue()
        val songCount = q?.getSize() ?: -1
        val playlistId = q?.playlistId
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            playlistId != null // aka "hasNext"
        ) {
            Log.d(TAG, "onMediaItemTransition: Triggering queue auto load more")
            scope.launch(SilentHandler) {
                val endpoint = playlistId // playlistId.substringBefore("\n")
                val continuation = null // playlistId.substringAfter("\n")
                val yq = YouTubeQueue(WatchEndpoint(endpoint, continuation))
                val mediaItems = yq.nextPage()
                q.playlistId = mediaItems.takeLast(4).shuffled().first().id // yq.getContinuationEndpoint()
                Log.d(TAG, "onMediaItemTransition: Got ${mediaItems.size} songs from radio")
                if (player.playbackState != STATE_IDLE && songCount > 1) { // initial radio loading is handled by playQueue()
                    queueBoard.enqueueEnd(mediaItems.drop(1))
                }
            }
        }

        queueBoard.setCurrQueuePosIndex(player.currentMediaItemIndex)

        // reshuffle queue when shuffle AND repeat all are enabled
        // no, when repeat mode is on, player does not "STATE_ENDED"
        if (player.currentMediaItemIndex == player.mediaItemCount - 1 &&
            (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            player.shuffleModeEnabled && player.repeatMode == REPEAT_MODE_ALL
        ) {
            scope.launch(SilentHandler) {
                // or else race condition: Assertions.checkArgument(eventTime.realtimeMs >= currentPlaybackStateStartTimeMs) fails in updatePlaybackState()
                delay(200)
                queueBoard.shuffleCurrent(player.mediaItemCount > 2)
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
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
                if (!player.playWhenReady) {
                    waitingForNetworkConnection.value = false
                }
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
    }

    /**
     * The transition tells us how the song before it ended, and what the new one is starting as.
     *
     * The player never says "the previous song was skipped"; it says "we moved to this one, by
     * seeking". So the reason is recorded against the song that just ended, to be picked up when its
     * playback stats arrive, and the run of consecutive autoplays is counted so a song that played
     * sixth in a radio queue is not weighed like one the listener chose.
     */
    private fun noteTransition(mediaItem: MediaItem?, reason: Int) {
        lastMediaId?.let { previous ->
            pendingEndReasons[previous] = when (reason) {
                MEDIA_ITEM_TRANSITION_REASON_AUTO, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> EndReason.ENDED
                MEDIA_ITEM_TRANSITION_REASON_SEEK -> EndReason.SKIPPED
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> EndReason.REPLACED
                else -> EndReason.UNKNOWN
            }
        }
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
        autoplayRun = when {
            chosen || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> 0
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> autoplayRun
            else -> autoplayRun + 1
        }
        checkpointJob?.cancel()
        val q = queueBoard.getCurrentQueue()
        val id = mediaItem?.mediaId ?: run { lastMediaId = null; return }
        val info = StartInfo(
            origin = origin?.code ?: q?.origin ?: PlayOrigin.UNKNOWN.code,
            originSlot = q?.originSlot ?: -1,
            queueId = q?.id ?: 0L,
            learn = q?.learn ?: true,
            autoplayDepth = autoplayRun,
            runId = q?.runId ?: 0L,
            tappedAt = tap,
        )
        startInfo.getOrPut(id) { java.util.concurrent.ConcurrentLinkedDeque() }.addLast(info)
        lastMediaId = id
        if (!volumeReceiverRegistered) registerVolumeReceiver()
        // A loaded item is not a listen: the queue restored at launch sits here unplayed. The row
        // opens when sound starts, which is now if playback carried straight over, and otherwise
        // in onIsPlayingChanged.
        if (player.isPlaying) openListen(id, info)
        checkpointJob = offloadScope.launch {
            while (true) {
                delay(CHECKPOINT_MS)
                val pos = withContext(Dispatchers.Main) { if (player.currentMediaItem?.mediaId == id) player.currentPosition else -1L }
                if (pos < 0) break
                checkpointListen(info, pos)
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
        info.startPositionMs = player.currentPosition.coerceAtLeast(0L)
        if (dataStore.get(PauseListenHistoryKey, false)) { info.rowReady.complete(0L); return }
        val metadata = player.currentMediaItem?.takeIf { it.mediaId == mediaId }?.metadata
        database.transaction {
            runCatching {
                // The row points at the song table, and recoverSong may still be fetching the
                // length before it writes the song; a song with no length yet is still a song.
                if (!songExists(mediaId)) metadata?.let { insert(it) }
                val previous = lastStoppedListen(mediaId)
                val continues = previous?.takeIf {
                    it.endReason == EndReason.STOPPED && it.endPositionMs >= 0 &&
                        kotlin.math.abs(info.startPositionMs - it.endPositionMs) <= RESUME_TOLERANCE_MS &&
                        info.startedAt - maxOf(it.endedAt, it.startedAt) <= RESUME_WINDOW_MS
                }?.id
                val last = lastListen()
                val sessionId = if (last == null || info.startedAt - last.endedAt > SESSION_GAP_MS) info.startedAt else last.sessionId
                val rowId = insert(
                    Listen(
                        songId = mediaId, startedAt = info.startedAt, endedAt = 0L,
                        tzOffsetMin = java.util.TimeZone.getDefault().getOffset(info.startedAt) / 60_000,
                        playedMs = 0L, durationMs = -1L, ratio = -1f, endReason = EndReason.OPEN,
                        origin = info.origin, originSlot = info.originSlot, queueId = info.queueId,
                        autoplayDepth = info.autoplayDepth, sessionId = sessionId, counted = false,
                        learn = info.learn, runId = info.runId, endPositionMs = -1L,
                        continuesListenId = continues,
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

    /** A play too short to be a listen: its open row goes, as if it had never been written. */
    private fun discardListen(info: StartInfo) {
        val rowId = info.rowId.takeIf { it > 0 } ?: return
        database.query {
            runCatching { discardOpenListen(rowId) }.onFailure { Log.w(TAG, "Could not discard listen", it) }
        }
    }

    /** How far this play got, written to its open row: a death then loses at most a minute. */
    private fun checkpointListen(info: StartInfo, positionMs: Long) {
        val rowId = info.rowId.takeIf { it > 0 } ?: return
        database.query {
            runCatching { checkpoint(rowId, (positionMs - info.startPositionMs).coerceAtLeast(0L), positionMs) }
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
            if (!player.isPlaying) return
            val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val before = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (now < 0 || before < 0 || now == before) return
            val id = player.currentMediaItem?.mediaId ?: return
            noteSignal(id, if (now > before) SignalKind.VOLUME_UP else SignalKind.VOLUME_DOWN, player.currentPosition, (now - before).toFloat())
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
        val itemChanged = oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
            oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId ||
            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        if (itemChanged) {
            oldPosition.mediaItem?.mediaId?.let { lastKnownPosition[it] = oldPosition.positionMs }
            return
        }
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        val id = newPosition.mediaItem?.mediaId ?: return
        val delta = newPosition.positionMs - oldPosition.positionMs
        if (kotlin.math.abs(delta) < 3_000) return
        noteSignal(id, if (delta < 0) SignalKind.SEEK_BACK else SignalKind.SEEK_FORWARD, newPosition.positionMs, delta / 1000f)
    }

    /**
     * Writes the complete record of a stop, whatever fraction was heard.
     *
     * Natural end is read off the stats themselves (endedCount), which is exact. Anything else was
     * cut short, and the transition that cut it says how; its callback and this one race, so a
     * missing reason is given a moment to arrive before being called a stop.
     */
    private suspend fun logListen(
        mediaId: String,
        playbackStats: PlaybackStats,
        durationSec: Int,
        ratio: Float,
        counted: Boolean,
    ): Long {
        val endedAt = System.currentTimeMillis()
        val info = takeOldestStart(mediaId)
        val endReason = when {
            playbackStats.endedCount > 0 -> EndReason.ENDED
            else -> pendingEndReasons.remove(mediaId) ?: run {
                delay(300)
                pendingEndReasons.remove(mediaId)
            } ?: EndReason.STOPPED
        }
        val startedAt = info?.startedAt
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
            // The complete record, under the same privacy switch as the counted play. Nothing that
            // lasted under two seconds: that is the player settling or a double tap, not a listen,
            // and it would otherwise be the most common row in the table.
            if (!historyPaused && playbackStats.totalPlayTimeMs >= 2_000) {
                listenId = runCatching { logListen(mediaItem.mediaId, playbackStats, durationSec, playRatio, counted) }
                    .onFailure { Log.w(TAG, "Could not log listen", it) }.getOrDefault(0L)
            } else {
                takeOldestStart(mediaItem.mediaId)?.let(::discardListen)
                pendingEndReasons.remove(mediaItem.mediaId); lastKnownPosition.remove(mediaItem.mediaId)
            }
            if (counted && !historyPaused) {
                database.query {
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
                mediaItem.metadata?.let { meta ->
                    scope.launch {
                        scrobbler.scrobble(
                            metadata = meta,
                            playedMs = playbackStats.totalPlayTimeMs,
                            // When it STARTED, which is what Last.fm orders history by. Using the
                            // finish time would shift every entry by the length of the song.
                            startedAtSeconds = (System.currentTimeMillis() -
                                    playbackStats.totalPlayTimeMs) / 1000,
                        )
                    }
                }

                // TODO: support playlist id
                // Throttle names history pings as work to drop while blocked, and this one costs a
                // whole extra /player per finished song. Nobody asked for it and nobody sees it fail.
                val ytHist = mediaItem.metadata?.isLocal != true &&
                        !dataStore.get(PauseRemoteListenHistoryKey, false) &&
                        !Throttle.isBlocked
                Log.d(TAG, "Trying to register remote history: $ytHist")
                if (ytHist) {
                    val playbackUrl = YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                        .getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    Log.d(TAG, "Got playback url: $playbackUrl")
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onFailure {
                                reportException(it)
                            }
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
        if (kind != null) player.currentMediaItem?.mediaId?.let { id -> noteSignal(id, kind, player.currentPosition) }
        updateNotification()
        offloadScope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val q = queueBoard.getCurrentQueue()
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(player.mediaItemCount))
        if (q == null || q.shuffled == shuffleModeEnabled) return
        triggerShuffle()
    }


    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // FG keep alive
        if (player.isPlaying || !dataStore.get(KeepAliveKey, false)) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onDestroy() {
        if (volumeReceiverRegistered) runCatching { unregisterReceiver(volumeReceiver) }
        checkpointJob?.cancel()
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
        private const val SLEEP_TIMER_NOTIF_IDLE_MS = 5_000L

        const val CHANNEL_ID = "music_channel_01"
        const val CHANNEL_NAME = "fgs_workaround"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L

        const val COMMAND_GET_BINDER = "GET_BINDER"
    }
}

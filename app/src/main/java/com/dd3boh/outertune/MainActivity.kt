/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune

import com.dd3boh.outertune.ui.navigation.appDestinations
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Intent
import android.os.Build
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import com.dd3boh.outertune.utils.dataStore
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import androidx.lifecycle.lifecycleScope
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.playback.queues.ListQueue
import kotlinx.coroutines.flow.filterNotNull
import com.dd3boh.outertune.widget.WidgetCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.constants.AppBarHeight
import com.dd3boh.outertune.constants.BackAnimationsKey
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.DefaultOpenTabKey
import com.dd3boh.outertune.constants.DynamicThemeKey
import com.dd3boh.outertune.constants.EnabledTabsKey
import com.dd3boh.outertune.constants.HighContrastKey
import com.dd3boh.outertune.constants.LibraryFilterKey
import com.dd3boh.outertune.constants.MinMiniPlayerHeight
import com.dd3boh.outertune.constants.MiniPlayerHeight
import com.dd3boh.outertune.constants.NavigationBarAnimationSpec
import com.dd3boh.outertune.constants.NavigationBarHeight
import com.dd3boh.outertune.constants.OOBE_VERSION
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.AnnouncementsEnabledKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.PureBlackKey
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.QuickPicksSourceKey
import com.dd3boh.outertune.constants.hasChips
import com.dd3boh.outertune.constants.orOffered
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.playback.MediaControllerViewModel
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.component.rememberBottomSheetState
import com.dd3boh.outertune.ui.component.shimmer.ShimmerTheme
import com.dd3boh.outertune.ui.menu.BottomSheetMenu
import com.dd3boh.outertune.ui.menu.MenuState
import com.dd3boh.outertune.ui.player.BottomSheetPlayer
import com.dd3boh.outertune.ui.screens.settings.SettingJumpHost
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget
import com.dd3boh.outertune.ui.screens.walkthrough.TourOverlay
import com.dd3boh.outertune.ui.screens.walkthrough.TourState
import com.dd3boh.outertune.ui.screens.walkthrough.tourFor
import kotlinx.coroutines.withTimeoutOrNull
import com.dd3boh.outertune.ui.screens.walkthrough.Install
import com.dd3boh.outertune.ui.screens.walkthrough.NewThingAction
import com.dd3boh.outertune.ui.screens.walkthrough.SETTINGS_TOUR
import com.dd3boh.outertune.ui.screens.walkthrough.SETTINGS_WALK
import com.dd3boh.outertune.ui.screens.walkthrough.TourStop
import com.dd3boh.outertune.ui.screens.walkthrough.TourTargets
import com.dd3boh.outertune.ui.screens.walkthrough.WelcomeBack
import com.dd3boh.outertune.ui.screens.walkthrough.WelcomeShow
import com.dd3boh.outertune.ui.screens.walkthrough.newThingsFor
import com.dd3boh.outertune.ui.screens.walkthrough.welcomeCardFor
import com.dd3boh.outertune.widget.MusicWidgetReceiver
import com.dd3boh.outertune.constants.WalkthroughSeenVersionKey
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.engine.SimilarSources
import com.dd3boh.outertune.utils.lastFmQuestionAskable
import com.dd3boh.outertune.ui.screens.OptInCatchUp
import com.dd3boh.outertune.ui.screens.catchUpOwed
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.screens.search.SearchBarContainer
import com.dd3boh.outertune.ui.theme.ColorSaver
import com.dd3boh.outertune.ui.theme.DefaultThemeColor
import com.dd3boh.outertune.ui.theme.OuterTuneTheme
import com.dd3boh.outertune.ui.theme.extractThemeColor
import com.dd3boh.outertune.ui.utils.appBarScrollBehavior
import com.dd3boh.outertune.ui.utils.blockFocusWhen
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.activity.compose.BackHandler
import com.dd3boh.outertune.ui.utils.dpadOverlay
import com.dd3boh.outertune.ui.utils.dpadOverlayEscape
import com.dd3boh.outertune.ui.utils.dpadTabBar
import com.dd3boh.outertune.ui.utils.dpadBringIntoView
import com.dd3boh.outertune.ui.utils.resetHeightOffset
import com.dd3boh.outertune.utils.ActivityLauncherHelper
import com.dd3boh.outertune.utils.InstallSource
import com.dd3boh.outertune.utils.installSource
import com.dd3boh.outertune.utils.NetworkConnectivityObserver
import com.dd3boh.outertune.utils.LoudnessRepair
import com.dd3boh.outertune.utils.Scrobbler
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.AutoBackup
import com.dd3boh.outertune.utils.BackgroundCheckWorker
import com.dd3boh.outertune.utils.ActiveCount
import com.dd3boh.outertune.utils.PollChecker
import com.dd3boh.outertune.utils.UpdateChecker
import com.dd3boh.outertune.utils.UpdateInstaller
import com.dd3boh.outertune.utils.coilCoroutine
import com.dd3boh.outertune.utils.lmScannerCoroutine
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.rememberNullablePreference
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBarDefaults
import com.dd3boh.outertune.constants.PlayerGlassIntensityKey
import com.dd3boh.outertune.constants.PlayerLiquidGlassKey
import com.dd3boh.outertune.ui.utils.LocalAppBackdrop
import com.dd3boh.outertune.ui.utils.LocalAppBackdropAvailable
import com.dd3boh.outertune.ui.utils.Landscape
import com.dd3boh.outertune.ui.utils.LocalLandscape
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dd3boh.outertune.ui.utils.rememberGlassSpec
import com.dd3boh.outertune.ui.utils.LocalGlassIntensity
import com.dd3boh.outertune.ui.component.LocalSearchBarGlass
import com.dd3boh.outertune.ui.utils.GlassSpec
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.dd3boh.outertune.ui.component.CrashReportDialog
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.dd3boh.outertune.constants.AutoInstallUpdatesKey
import com.dd3boh.outertune.constants.UpdateSnoozeUntilKey
import com.dd3boh.outertune.constants.UPDATE_SNOOZE_MS
import com.dd3boh.outertune.ui.component.UpdatePrompt
import kotlinx.coroutines.delay
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * How long a back takes when it is not being dragged.
 *
 * A predictive back gesture seeks the pop transition by hand and never consults this, but a back
 * from the button, from a keyboard, or a gesture released early still has to travel the rest of the
 * way on its own. The screen now covers a full width rather than an eighth, so the old 200 ms read
 * as a flick; this is slow enough to follow and short enough not to be in the way.
 */
private val NavPopSpec = tween<IntOffset>(300, easing = FastOutSlowInEasing)

/**
 * Whether this activity was started for the intent it holds.
 *
 * False when it was recreated with saved state (a rotation or other configuration change, or a
 * restore after process death), and when the task was relaunched from recents. Both of those hand
 * back the task's original intent with its extras.
 */
internal fun freshLaunch(hadSavedInstanceState: Boolean, flags: Int): Boolean =
    !hadSavedInstanceState && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

/**
 * Whether the launch intent's widget song is still owed: true only for a fresh launch whose
 * action is the widget's ACTION_PLAY_SONG intent. See [freshLaunch].
 */
internal fun widgetSongOwed(action: String?, hadSavedInstanceState: Boolean, flags: Int): Boolean =
    action == WidgetCommands.ACTION_PLAY_SONG && freshLaunch(hadSavedInstanceState, flags)

/**
 * A widget tap that reached the activity through onNewIntent and is waiting for the player.
 *
 * A ViewModel keeps it through a rotation or other recreation, and a new process starts without
 * it, so a tap cannot start by itself on a later reopen from recents.
 */
internal class PendingWidgetTap : ViewModel() {
    var intent by mutableStateOf<Intent?>(null)
}

/**
 * A link that reached the activity through onNewIntent and is waiting for the player and for
 * setup to be done. Kept the way [PendingWidgetTap] keeps a tap, and for the same reasons.
 */
internal class PendingLink : ViewModel() {
    var link by mutableStateOf<String?>(null)
}

/**
 * Whether a widget-tapped song plays on its own or keeps the radio.
 *
 * A local song's id is not a YouTube video id (SongEntity.generateSongId makes local ids "LS" plus
 * random characters), so asking YouTube to start a radio from one fails outright: offline always,
 * and online for want of anything meaningful to return. Home already makes this same choice for
 * its own local rows; the widget mirrors it here.
 */
internal enum class WidgetPlaybackKind { LOCAL, RADIO }

internal fun widgetPlaybackKind(metadata: MediaMetadata): WidgetPlaybackKind =
    if (metadata.isLocal) WidgetPlaybackKind.LOCAL else WidgetPlaybackKind.RADIO

/**
 * A song tapped on the home screen widget.
 *
 * The widget carries enough of the song in its intent to play it without a lookup, because the
 * song it is offering need not be in the library at all: Quick picks on the YouTube source is a
 * shelf from the feed. The library is still asked first, since a song that is there comes with
 * everything else the app knows about it.
 */
private fun playFromWidget(
    intent: Intent?,
    database: MusicDatabase,
    playerConnection: PlayerConnection?,
    scope: CoroutineScope,
) {
    if (intent?.action != WidgetCommands.ACTION_PLAY_SONG) return
    val id = intent.getStringExtra(WidgetCommands.EXTRA_SONG_ID) ?: return
    // Not until the player is connected. With the app closed, the tap creates the activity and the
    // connection arrives a moment later, so the first call has none: taking the id out before
    // this check threw it away, and the call that came with the connection found nothing to play.
    val connection = playerConnection ?: return
    // Taken out of the intent, or every recomposition and every rotation plays it again.
    intent.removeExtra(WidgetCommands.EXTRA_SONG_ID)
    val title = intent.getStringExtra(WidgetCommands.EXTRA_SONG_TITLE).orEmpty()
    val artist = intent.getStringExtra(WidgetCommands.EXTRA_SONG_ARTIST).orEmpty()
    val thumbnail = intent.getStringExtra(WidgetCommands.EXTRA_SONG_THUMBNAIL)
    val duration = intent.getIntExtra(WidgetCommands.EXTRA_SONG_DURATION, 0)
    scope.launch {
        val known = runCatching { withContext(Dispatchers.IO) { database.song(id).first() } }.getOrNull()
        val metadata = known?.toMediaMetadata() ?: MediaMetadata(
            id = id,
            title = title,
            artists = listOf(MediaMetadata.Artist(null, artist)),
            duration = duration,
            thumbnailUrl = thumbnail,
            genre = null,
        )
        when (widgetPlaybackKind(metadata)) {
            WidgetPlaybackKind.LOCAL -> connection.playQueue(
                ListQueue(title = metadata.title, items = listOf(metadata)),
                origin = PlayOrigin.WIDGET,
            )
            WidgetPlaybackKind.RADIO -> connection.playQueue(
                YouTubeQueue.radio(metadata),
                isRadio = true,
                origin = PlayOrigin.WIDGET,
            )
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    val MAIN_TAG = "MainOtActivity"

    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var scrobbler: Scrobbler

    @Inject
    lateinit var loudnessRepair: LoudnessRepair

    @Inject
    lateinit var updateChecker: UpdateChecker

    @Inject
    lateinit var updateInstaller: UpdateInstaller

    @Inject
    lateinit var pollChecker: PollChecker

    @Inject
    lateinit var activeCount: ActiveCount

    lateinit var activityLauncher: ActivityLauncherHelper
    lateinit var connectivityObserver: NetworkConnectivityObserver

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)

    private val pendingWidgetTap: PendingWidgetTap by viewModels()

    private val pendingLink: PendingLink by viewModels()

    val controllerViewModel: MediaControllerViewModel by viewModels()

    // storage permission helpers
    val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
//                Toast.makeText(this, "Granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.scanner_missing_storage_perm), Toast.LENGTH_SHORT).show()
            }
        }

    private fun handleOpenPoll(intent: Intent?) {
        if (intent?.getBooleanExtra(BackgroundCheckWorker.EXTRA_OPEN_POLL, false) != true) return
        Log.i(MAIN_TAG, "Opening the question from its notification")
        intent.removeExtra(BackgroundCheckWorker.EXTRA_OPEN_POLL)
        pollChecker.requestOpen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenPoll(intent)
        // Every later widget tap comes through here, sometimes before the first composition (a
        // tap on a task whose process died), so it is held until the player is connected.
        // onNewIntent never replays an old intent, so nothing is gated here.
        if (intent.action == WidgetCommands.ACTION_PLAY_SONG) pendingWidgetTap.intent = intent
        // A link tapped or shared later, held the same way and opened by the effect that opens
        // the launch intent's link. On a task whose process died it arrives here too, before the
        // first composition.
        newIntentLink(intent.action, intent.dataString, intent.getStringExtra(Intent.EXTRA_TEXT))
            ?.let { pendingLink.link = it }
        if (intent.action == ACTION_PLAY_LIKED) playLikedWhenReady()
        handlePlayFromSearch(intent)
    }

    private fun handlePlayFromSearch(intent: Intent) {
        searchToPlay(intent.action, intent.data != null, intent.getStringExtra(SearchManager.QUERY))
            ?.let(::playFromSearch)
    }

    /**
     * "Play X" from a voice assistant, a car or an automation app. The words go to the media
     * session the way a voice request in Android Auto reaches it, so the same search of the
     * library plays, and with no words the queue there is carries on, or the saved one starts.
     *
     * The session takes requests from a connected controller. On a cold start the activity's own
     * is still connecting, and an activity brought back from the background released its own
     * when it stopped, so without one this waits for the next. The session's search is asked
     * first: a request it cannot place fails and leaves the player alone, and the play() after it
     * would have started whatever was there before.
     */
    private fun playFromSearch(query: String) {
        controllerViewModel.addControllerCallback(lifecycle) { browser, _ ->
            if (!browser.isConnected) return@addControllerCallback
            dispose()
            lifecycleScope.launch {
                if (query.isNotEmpty()) {
                    val found = try {
                        browser.getSearchResult(query, 0, 1, null).await().value
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(MAIN_TAG, "Could not search for a play from search", e)
                        null
                    }
                    if (found.isNullOrEmpty()) {
                        Toast.makeText(this@MainActivity, R.string.no_results_found, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                browser.setMediaItems(listOf(searchPlayRequest(query)))
                browser.prepare()
                browser.play()
            }
        }
    }

    /**
     * The "Play liked songs" shortcut: the liked songs in the order the Liked songs screen shows
     * them, as its Play button plays them. It waits for the player, which on a cold start from
     * the shortcut is not connected yet.
     */
    private fun playLikedWhenReady() {
        lifecycleScope.launch {
            val connection = snapshotFlow { playerConnection }.filterNotNull().first()
            val prefs = dataStore.data.first()
            val songs = database.likedSongs(
                prefs[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE),
                prefs[SongSortDescendingKey] ?: true,
            ).first()
            if (songs.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.shortcut_no_liked_songs, Toast.LENGTH_SHORT).show()
                return@launch
            }
            connection.playQueue(
                ListQueue(title = getString(R.string.liked_songs), items = songs.map { it.toMediaMetadata() }),
                origin = PlayOrigin.PLAYLIST,
            )
        }
    }

    override fun onDestroy() {
        Log.i(MAIN_TAG, "onDestroy() called. isFinishing = $isFinishing")
        try {
            connectivityObserver.unregister()
        } catch (e: UninitializedPropertyAccessException) {
            // lol
        }
        // https://github.com/androidx/media/issues/805
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE && (playerConnection?.player?.playWhenReady != true || playerConnection?.player?.mediaItemCount == 0)) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(MusicService.NOTIFICATION_ID)
        }
        lifecycle.removeObserver(controllerViewModel)
        playerConnection = null

        super.onDestroy()
    }

    @SuppressLint("UnusedBoxWithConstraintsScope")
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not again after a rotation or a restored process: the shortcut was tapped once. Nor when
        // the task is reopened from recents, which starts it again with the shortcut's intent and
        // replayed the liked songs over whatever was playing (Android 11 and older, where Back
        // finishes the activity instead of keeping it).
        if (intent?.action == ACTION_PLAY_LIKED && freshLaunch(savedInstanceState != null, intent?.flags ?: 0)) {
            playLikedWhenReady()
        }
        lifecycle.addObserver(controllerViewModel)
        controllerViewModel.addControllerCallback(lifecycle) { controller, _ ->
            playerConnection = PlayerConnection(controllerViewModel, database)
        }
        // A play-from-search request, once: not after a rotation or a restored process, and not
        // when the task is reopened from recents, which starts it again with the intent it was
        // first started with and would play an old request over whatever is playing now.
        if (freshLaunch(savedInstanceState != null, intent?.flags ?: 0)) {
            intent?.let(::handlePlayFromSearch)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        activityLauncher = ActivityLauncherHelper(this)

        setContent {
            Log.v(MAIN_TAG, "RC-1")
            val coroutineScope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current
            val snackbarHostState = remember { SnackbarHostState() }

            val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
            val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
            val highContrastCompat by rememberPreference(HighContrastKey, defaultValue = false)
            val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            }

            val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
            // The window, measured once for everything that is laid out differently when a phone
            // is on its side (Landscape.kt).
            val windowPx = LocalWindowInfo.current.containerSize
            val windowDensity = LocalDensity.current
            // The width the root below is laid out at, which it reports itself: the system's
            // size can be the wider of the two (Landscape.laidOutWidth). Forgotten when the
            // system's size changes, so a width from before a turn is never the one used.
            var laidOutWidth by remember(windowPx) { mutableIntStateOf(0) }
            val landscape = remember(windowPx, laidOutWidth, windowDensity) {
                with(windowDensity) {
                    Landscape(Landscape.laidOutWidth(windowPx.width, laidOutWidth).toDp(), windowPx.height.toDp())
                }
            }
//            val tabMode = this@MainActivity.tabMode()
            // The rail for a wide window, and for a short one: a phone on its side that is not
            // 840dp wide used to keep the bar along the bottom, a sixth of the height it had.
            val useNavRail by remember(landscape) {
                derivedStateOf {
                    windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED || landscape.active
                }
            }


            val (oobeStatus) = rememberPreference(OobeStatusKey, defaultValue = 0)

            val (slimNavPreference) = rememberPreference(SlimNavBarKey, defaultValue = false)

            /**
             * The slim bar, forced when the window is too short to spare the room.
             *
             * In Samsung's pop up view the whole app is a window a few hundred dp tall, and the
             * navigation bar was taking a fifth of it: 68dp of chrome with labels under the icons,
             * above a mini player, inside something the size of a playing card. The same is true
             * of any short window, landscape included.
             *
             * Forced rather than offered, and the preference is still honoured in the other
             * direction: somebody who asked for the slim bar keeps it everywhere, somebody who did
             * not gets it back the moment the window is a normal height again.
             */
            val slimNav = slimNavPreference ||
                    windowSizeClass.windowHeightSizeClass == WindowHeightSizeClass.COMPACT
            val (enabledTabs) = rememberPreference(EnabledTabsKey, defaultValue = DEFAULT_ENABLED_TABS)
            val navigationItems = Screens.getScreens(enabledTabs)
            val (defaultOpenTab, onDefaultOpenTabChange) = rememberPreference(
                DefaultOpenTabKey,
                defaultValue = Screens.Home.route
            )




            LaunchedEffect(Unit) {
                // Look for a newer release. Does nothing unless the user opted in, and never asks
                // more than once in two minutes, so this is cheap to call on every open. Failure
                // is silent on purpose: nobody opened a music player to be told GitHub is down.
                coroutineScope.launch { updateChecker.check() }

                // Same contract as the update check: nothing happens unless the user opted in, it
                // rate limits itself, and failure is silent.
                coroutineScope.launch {
                    // Only when the activity starts afresh. A rotation or a restore recreates it
                    // while setup or the catch-up screen may be open, and somebody who has just
                    // said yes to questions there, with news not answered yet, would have news
                    // switched on before being asked. The switch-over only needs one fresh launch.
                    if (savedInstanceState == null) pollChecker.adoptNewsChoice()
                    pollChecker.check()
                }
                // Re-applied on every launch, once. This is also what puts the schedule back after
                // a reboot, since WorkManager needs the app to run once before it will restore its
                // own. A schedule that is there is kept, and replaced only when it was made for
                // another interval than the setting says, because replacing made every launch a
                // check (BackgroundCheckWorker.schedule says why).
                BackgroundCheckWorker.schedule(this@MainActivity)

                // And the same again for the once-a-day count: off unless asked for, silent when
                // it fails, and a no-op on every open after the first one each day.
                coroutineScope.launch { activeCount.ping() }
                // Scheduled backups: a no-op until the switch is on, and otherwise puts the schedule
                // back if it is missing. It replaces one that is there only when it was made for
                // another interval than the settings say, as after a Restore, because replacing
                // made every launch a backup (AutoBackup.schedule says why).
                AutoBackup.schedule(this@MainActivity)

                // A notification about a question opens the question. handleOpenPoll is called for
                // the intent that started this, and again from onNewIntent when the app was already
                // running, since Android delivers that to the existing instance instead. Not for a
                // task reopened from recents or an activity restored after process death: both hand
                // back the original intent with the extra still in it, because handleOpenPoll's
                // removeExtra only changed the old process's copy.
                if (freshLaunch(savedInstanceState != null, intent?.flags ?: 0)) {
                    handleOpenPoll(intent)
                }

                // Receives the outcome of an in-app install. Registered here rather than in the
                // manifest because it is only meaningful while the app is alive to show it.
                updateInstaller.registerReceiver()
                // Forget the update once it has actually installed, so a finished one cannot sit
                // there prompting forever if the process happened to survive.
                updateInstaller.onInstalled = { coroutineScope.launch { updateChecker.clearFound() } }

                // local media & download folders auto scan
                coroutineScope.launch(lmScannerCoroutine) {
                    scanInit(
                        this@MainActivity, database, downloadUtil, coroutineScope, playerConnection,
                        snackbarHostState
                    )
                }
            }


            LaunchedEffect(useDarkTheme) {
                setSystemBarAppearance(useDarkTheme)
            }
            var themeColor by rememberSaveable(stateSaver = ColorSaver) {
                mutableStateOf(DefaultThemeColor)
            }

            // One observer for the activity, not one per recomposition of this scope. This scope
            // recomposes on every song change (it reads themeColor, and dynamic theme is on by
            // default), and a new registration replays onAvailable for the network that is
            // already there, which calls Throttle.onNetworkChanged() and clears whatever back-off
            // is in effect. onDestroy unregisters it.
            connectivityObserver = remember { NetworkConnectivityObserver(this@MainActivity) }
            val isNetworkConnected by connectivityObserver.networkStatus.collectAsState(true)

            LaunchedEffect(playerConnection, enableDynamicTheme, isSystemInDarkTheme) {
                val playerConnection = playerConnection
                if (!enableDynamicTheme || playerConnection == null) {
                    themeColor = DefaultThemeColor
                    return@LaunchedEffect
                }
                playerConnection.service.currentMediaMetadata.collectLatest { song ->
                    // Done inside collectLatest rather than launched from it. Launching made the
                    // lambda return the moment the job was handed off, so collectLatest had
                    // nothing left to cancel when the next song arrived: every skip added another
                    // image load and palette extraction racing the others to write themeColor,
                    // and the winner was whichever finished last, not whichever song was playing.
                    // Skipping quickly could therefore settle the theme on a song two back.
                    themeColor = withContext(coilCoroutine) {
                        var ret = DefaultThemeColor
                        // A 100px thumbnail, the same one Player.kt asks for when it extracts the
                        // gradient. Local files were already capped at 100px here, but a remote
                        // song passed its bare thumbnailUrl with no size, so Coil fetched and
                        // software-decoded the cover at whatever size that url named, often 544px
                        // and over a megabyte of heap, on every track change, screen off included,
                        // only to boil it down to one colour. Palette scales its input down to
                        // roughly 112px square before it looks at it anyway, so the colour comes
                        // out near enough the same. It is also the url the player's gradient and
                        // blur backgrounds ask for, so they share one cached copy of it.
                        val model = song?.getThumbnailModel(100, 100)
                        if (model != null) {
                            val result = applicationContext.imageLoader.execute(
                                ImageRequest.Builder(applicationContext)
                                    .data(model)
                                    .allowHardware(false)
                                    .build()
                            )

                            ret = result.image?.toBitmap()?.extractThemeColor() ?: DefaultThemeColor
                        }
                        ret
                    }
                }
            }


            OuterTuneTheme(
                context = this@MainActivity,
                darkTheme = useDarkTheme,
                pureBlack = pureBlack,
                highContrastCompat = highContrastCompat,
                themeColor = themeColor
            ) {
                Log.v(MAIN_TAG, "RC-2.1")

                /**
                 * Offer the update, and let the answer stick.
                 *
                 * Shown regardless of the automatic setting, because Android never installs without
                 * a confirmation anyway, so there is no version of this that happens invisibly. The
                 * setting only decides whether the apk is already downloaded by the time this
                 * appears.
                 *
                 * Snoozed rather than dismissed by default: "later" writes a timestamp an hour out,
                 * and tapping outside counts as later. Only Cancel marks the version dismissed, and
                 * that is per version, so the next release still gets through.
                 */
                val pendingUpdate by updateChecker.available.collectAsState()

                // Re-read on resume. Granting "install unknown apps" happens in another activity,
                // and a plain canRequestInstall() call would still read false when the user comes
                // back, so the prompt would keep offering to send them there. The tap handler
                // re-checks live so it always behaves correctly; this is what makes it also say
                // the right thing.
                var canInstall by remember { mutableStateOf(updateInstaller.canRequestInstall()) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            canInstall = updateInstaller.canRequestInstall()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val autoInstall by rememberPreference(AutoInstallUpdatesKey, defaultValue = false)
                val snoozeUntil by rememberPreference(UpdateSnoozeUntilKey, defaultValue = 0L)
                val installState by updateInstaller.state.collectAsState()

                // Pre-fetch only when asked to, and only once per found update. Never on F-Droid:
                // the switch that turns autoInstall on is hidden there, but the preference can
                // still be true from before that install source was checked (an older build, a
                // restored backup), and a GitHub apk downloaded to an F-Droid install can never be
                // the one that gets installed.
                val fromFdroid = remember { installSource() == InstallSource.F_DROID }
                LaunchedEffect(pendingUpdate, autoInstall) {
                    val u = pendingUpdate
                    if (u != null && updatePrefetchOwed(
                            autoInstall = autoInstall,
                            fromFdroid = fromFdroid,
                            installerBusy = updateInstaller.isBusy,
                            installerIdle = installState is UpdateInstaller.State.Idle,
                        )
                    ) {
                        updateInstaller.download(u.downloadUrl, u.sizeBytes)
                    }
                }

                var snoozeTick by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(snoozeUntil) {
                    // Wake up when the snooze expires so the prompt returns without needing a
                    // relaunch. A plain timestamp comparison would not recompose on its own.
                    val wait = snoozeUntil - System.currentTimeMillis()
                    if (wait > 0) {
                        delay(wait)
                        snoozeTick = System.currentTimeMillis()
                    }
                }

                // Hoisted so the walkthrough can stand aside for it. Two full screen prompts on
                // one launch is one too many, and an update is the more urgent of the two.
                val updatePromptVisible = pendingUpdate != null &&
                        snoozeUntil <= snoozeTick.coerceAtLeast(System.currentTimeMillis()) &&
                        installState !is UpdateInstaller.State.AwaitingConfirmation

                pendingUpdate?.let { found ->
                    val snoozed = snoozeUntil > snoozeTick.coerceAtLeast(System.currentTimeMillis())
                    if (!snoozed && installState !is UpdateInstaller.State.AwaitingConfirmation) {
                        UpdatePrompt(
                            update = found,
                            ready = updateInstaller.isDownloaded(found.sizeBytes),
                            needsPermission = !canInstall,
                            onInstall = {
                                // Android refuses to show its install prompt until this app is
                                // allowed to install apps, and refuses silently, so send the user
                                // to the one screen that grants it rather than appearing to do
                                // nothing. The apk stays downloaded, so coming back and tapping
                                // install again goes straight through.
                                if (!updateInstaller.canRequestInstall()) {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            "package:$packageName".toUri()
                                        )
                                    )
                                } else {
                                    updateInstaller.installOrDownload(found.downloadUrl, found.sizeBytes)
                                }
                            },
                            onRemindLater = {
                                coroutineScope.launch {
                                    dataStore.edit {
                                        it[UpdateSnoozeUntilKey] =
                                            System.currentTimeMillis() + UPDATE_SNOOZE_MS
                                    }
                                }
                            },
                            onCancel = {
                                coroutineScope.launch { updateChecker.dismiss(found.versionCode) }
                            },
                        )
                    }
                }

                val density = LocalDensity.current
                // ignoringVisibility, NOT systemBars: the landscape player hides the bars, and
                // bottomInset feeds the player sheet's collapsedBound, which is a remember() key
                // for its BottomSheetState. Using the live inset rebuilds that state the instant
                // the bars toggle, cancelling any in-flight drag and springing the sheet back to
                // its previous anchor. The ignoringVisibility value is what the bars *would*
                // occupy, so it stays constant and the layout does not jump either.
                val windowsInsets = WindowInsets.systemBarsIgnoringVisibility
                val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }
                val cutoutInsets = WindowInsets.displayCutout

                val liquidGlass by rememberPreference(PlayerLiquidGlassKey, defaultValue = false)
                val glassIntensity by rememberPreference(PlayerGlassIntensityKey, defaultValue = 1f)
                // TIRAMISU because lens() needs a RuntimeShader and the settings toggle is only
                // offered at 33+. !useNavRail because playerAwareWindowInsets reserves left = 80dp
                // on tablets, so nothing ever scrolls behind the rail and a glass rail would be a
                // full offscreen pass refracting the flat surface fill. A phone on its side has
                // the rail too, and still has the page scrolling behind its search pill and its
                // mini player, so those two keep their glass there; the rail itself stays solid.
                val backdropAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        (!useNavRail || landscape.active)
                val navGlass = liquidGlass && backdropAvailable
                val appBackdrop = rememberLayerBackdrop()

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()

                /**
                 * Hoisted out of [SearchBarContainer] so the navigation bar can close the search
                 * overlay. The search bar is drawn on top of whatever destination you were on, not
                 * as a destination of its own, so while it is open navBackStackEntry still points
                 * at (say) library. Tapping Library therefore matched the "already on this tab"
                 * branch and only requested a scroll-to-top, leaving the search UI up and looking
                 * like the tap did nothing.
                 */
                var searchActive by rememberSaveable { mutableStateOf(false) }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { laidOutWidth = it.width }
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Log.v(MAIN_TAG, "RC-2.2")

                    fun getNavPadding(): Dp {
                        return if (!useNavRail) (if (slimNav) 52.dp else 68.dp)
                        // On its side the mini player is a panel floating over the page, and the
                        // gap under it is the gesture bar's, or a small one where there is none.
                        else if (landscape.active) (if (bottomInset >= 16.dp) 0.dp else 8.dp)
                        else MinMiniPlayerHeight
                    }

                    val playerBottomSheetState = rememberBottomSheetState(
                        dismissedBound = 0.dp,
                        collapsedBound = bottomInset + MiniPlayerHeight + getNavPadding(),
                        expandedBound = maxHeight,
                    )

                    val playerAwareWindowInsets =
                        remember(
                            bottomInset,
                            playerBottomSheetState.isDismissed,
                        ) {
                            // TODO: Navbar is shown in all screens except for oobe (which doesn't use these insets). Idk what do to tbh
                            var bottom = bottomInset + if (!useNavRail) NavigationBarHeight else 0.dp

                            if (!playerBottomSheetState.isDismissed) bottom += MiniPlayerHeight
                            windowsInsets
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                                .add(cutoutInsets.only(WindowInsetsSides.Horizontal))
                                .add(
                                    WindowInsets(
                                        left = if (!useNavRail) 0.dp else NavigationBarHeight,
                                        top = AppBarHeight,
                                        bottom = bottom
                                    )
                                )
                        }

                    // One tour for the activity. It outlives individual screens because it
                    // points at controls belonging to several of them, and a rotation.
                    val tourState = rememberSaveable(saver = TourState.Saver) { TourState() }

                    // Remembered: the default built a fresh state on every recomposition of this
                    // scope, which only went unnoticed because the scope rarely recomposed.
                    val appBarState = remember { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
                    val scrollBehavior = appBarScrollBehavior(
                        state = appBarState,
                        canScroll = {
                            navBackStackEntry?.destination?.route?.startsWith("search/") == false &&
                                    (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                        }
                    )
                    // Every screen feeds this one state, and nothing gave it back: scroll a
                    // settings page, go back, and Home's search bar was still slid away. A tab, or
                    // search, now arrives with it fully out. Only on arrival there: a tab being
                    // left keeps its bar where it was while it fades, instead of flashing back in.
                    // The route is read in the flow, not during composition, so a navigation does
                    // not recompose everything this scope provides.
                    val tabRoutes by rememberUpdatedState(navigationItems.map { it.route })
                    LaunchedEffect(Unit) {
                        snapshotFlow { navBackStackEntry?.destination?.route }
                            .distinctUntilChanged()
                            .collectLatest { route ->
                                if (route in tabRoutes || route?.startsWith("search/") == true) {
                                    appBarState.contentOffset = 0f
                                    appBarState.resetHeightOffset()
                                }
                            }
                    }

                    // The Search shortcut. Search is the overlay over the current destination, not
                    // a destination, so it is opened the way tapping the bar opens it, and not over
                    // setup, which comes first. It waits for the first destination and then a frame:
                    // the overlay shuts itself whenever the destination under it changes, and at
                    // launch that includes the first one arriving.
                    var searchFromShortcut by rememberSaveable {
                        mutableStateOf(intent?.action == ACTION_SEARCH && oobeStatus >= OOBE_VERSION)
                    }
                    LaunchedEffect(searchFromShortcut) {
                        if (!searchFromShortcut) return@LaunchedEffect
                        snapshotFlow { navBackStackEntry }.first { it != null }
                        withFrameNanos { }
                        searchActive = true
                        searchFromShortcut = false
                    }

                    // The Songs, Albums and Playlists shortcuts, handled the same way: their tab,
                    // or Library on their filter, once the first destination is there. Since
                    // upstream 25d504fe1 stopped making the tab the start destination they only
                    // set the filter and opened on the default tab, and the filter was written
                    // from composition, again on every recomposition of this scope, putting it
                    // back over whatever had been picked since. Now it is written once for the
                    // tap, and before Library opens, which reads it when it first draws.
                    var libraryShortcutAction by rememberSaveable {
                        mutableStateOf(
                            intent?.action?.takeIf {
                                libraryShortcut(it, navigationItems) != null && oobeStatus >= OOBE_VERSION
                            }
                        )
                    }
                    LaunchedEffect(libraryShortcutAction) {
                        val target = libraryShortcut(libraryShortcutAction, navigationItems) ?: return@LaunchedEffect
                        target.filter?.let { filter -> dataStore.edit { it[LibraryFilterKey] = filter.name } }
                        snapshotFlow { navBackStackEntry }.first { it != null }
                        navigateToNavTab(navController, target.screen.route, navigationItems, navController.currentBackStackEntry)
                        libraryShortcutAction = null
                    }


                    // A Quick pick tapped on the home screen widget. The song that started the app
                    // is decided once, on a fresh launch, and kept through a rotation. Any later
                    // tap comes from onNewIntent through pendingWidgetTap. Each plays once, when
                    // the player is connected.
                    var widgetSongPending by rememberSaveable {
                        mutableStateOf(
                            widgetSongOwed(intent?.action, savedInstanceState != null, intent?.flags ?: 0)
                        )
                    }
                    LaunchedEffect(playerConnection, widgetSongPending, pendingWidgetTap.intent) {
                        if (widgetSongPending) {
                            playFromWidget(intent, database, playerConnection, coroutineScope)
                            if (playerConnection != null) widgetSongPending = false
                        }
                        pendingWidgetTap.intent?.let { pending ->
                            playFromWidget(pending, database, playerConnection, coroutineScope)
                            if (playerConnection != null) pendingWidgetTap.intent = null
                        }
                    }

                    // A YouTube link, shared to the app or tapped. The one that started the app is
                    // decided once, on a fresh launch; the effect that handled it went with
                    // upstream's MainActivity refactor (25d504fe1), so a song link opened
                    // InterTune on Home and did nothing else. Any later link comes from
                    // onNewIntent through pendingLink, including one on a task whose process died,
                    // which arrives before this composition runs. Each is held until the player is
                    // connected, which on a cold start comes a moment later, and until setup is
                    // finished, then cleared, so a rotation does not open it again.
                    var pendingLaunchLink by rememberSaveable {
                        mutableStateOf(
                            launchLink(
                                action = intent?.action,
                                data = intent?.dataString,
                                text = intent?.getStringExtra(Intent.EXTRA_TEXT),
                                fromRecents = !freshLaunch(savedInstanceState != null, intent?.flags ?: 0),
                            )
                        )
                    }
                    LaunchedEffect(pendingLaunchLink, pendingLink.link, playerConnection, oobeStatus) {
                        val link = pendingLaunchLink ?: pendingLink.link ?: return@LaunchedEffect
                        val connection = playerConnection ?: return@LaunchedEffect
                        if (oobeStatus < OOBE_VERSION) return@LaunchedEffect
                        snapshotFlow { navBackStackEntry }.first { it != null }
                        if (pendingLaunchLink != null) pendingLaunchLink = null else pendingLink.link = null
                        youtubeNavigator(
                            this@MainActivity, navController, coroutineScope, connection, snackbarHostState, link.toUri()
                        )
                    }

                    CompositionLocalProvider(
                        LocalDatabase provides database,
                        LocalContentColor provides contentColorFor(MaterialTheme.colorScheme.surface),
                        LocalMenuState provides MenuState(rememberModalBottomSheetState()),
                        LocalPlayerConnection provides playerConnection,
                        LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                        LocalDownloadUtil provides downloadUtil,
                        LocalShimmerTheme provides ShimmerTheme,
                        LocalSyncUtils provides syncUtils,
                        LocalScrobbler provides scrobbler,
                        LocalLoudnessRepair provides loudnessRepair,
                        LocalUpdateChecker provides updateChecker,
                        LocalUpdateInstaller provides updateInstaller,
                        LocalPollChecker provides pollChecker,
                        LocalActiveCount provides activeCount,
                        LocalNetworkConnected provides isNetworkConnected,
                        LocalSnackbarHostState provides snackbarHostState,
                        LocalAppBackdrop provides (if (navGlass) appBackdrop else null),
                        LocalAppBackdropAvailable provides backdropAvailable,
                        LocalLandscape provides landscape,
                        LocalGlassIntensity provides glassIntensity,
                        dpadBringIntoView(),
                    ) {
                        /**
                         * Ask for the answers this install never gave.
                         *
                         * Derived from the preferences rather than snapshotted at launch, so it becomes true
                         * the moment onboarding completes with a question still open. That is what makes the
                         * ask unconditional: skipping on the welcome page, walking past the cards and
                         * tapping finish, updating from a build whose wizard never had the card, or
                         * restoring a backup that carried no answer all land here rather than leaving
                         * somebody never asked.
                         *
                         * The rememberSaveable this replaces could not do that. With no keys its initialiser
                         * ran once, at launch, when a fresh install still had OobeStatusKey at 0, so it
                         * latched false for the whole first session, and being saveable it survived process
                         * death too. Nothing re-evaluated it when the wizard wrote OOBE_VERSION seconds
                         * later.
                         *
                         * Unset genuinely means "never asked" rather than "said no", because every path that
                         * answers writes a value, so this cannot pester anyone who already declined.
                         *
                         * Latched open rather than driven straight off the gate: answering the last card
                         * makes the gate false, and a screen that vanishes under the finger that just
                         * answered it reads as a crash. [OptInCatchUp] carries its own finish button.
                         *
                         * Inside the theme on purpose: at the top of setContent it drew in baseline Material
                         * purple, because OuterTuneTheme had not opened yet. Inside the providers for a
                         * harder reason: [OptInCatchUp] shows the wizard's own cards, and those read
                         * LocalUpdateChecker and LocalPollChecker, so above this block they throw rather
                         * than render. The dialog it replaced never noticed, having reached the checker
                         * through the activity field instead.
                         */
                        val updateChoice by rememberNullablePreference(UpdateCheckEnabledKey)
                        val pollChoice by rememberNullablePreference(PollsEnabledKey)
                        val newsChoice by rememberNullablePreference(AnnouncementsEnabledKey)
                        val usageChoice by rememberNullablePreference(UsageCountEnabledKey)
                        // Last.fm is owed only where it can be asked for; see LastFmSimilarOptInCard.
                        val similarStored by rememberNullablePreference(SimilarSourceKey)
                        val similarOldSwitch by rememberNullablePreference(SimilarFromLastFmKey)
                        val lastFmOwed = lastFmQuestionAskable() && !SimilarSources.asked(similarStored, similarOldSwitch)

                        var catchUpOpen by rememberSaveable { mutableStateOf(false) }
                        var catchUpDone by rememberSaveable { mutableStateOf(false) }

                        LaunchedEffect(updateChoice, pollChoice, newsChoice, usageChoice, lastFmOwed, oobeStatus) {
                            if (!catchUpDone && oobeStatus >= OOBE_VERSION &&
                                catchUpOwed(updateChoice, pollChoice, newsChoice, usageChoice, lastFmOwed)
                            ) {
                                catchUpOpen = true
                            }
                        }

                        /*
                         * The walkthrough for whatever arrived in this build.
                         *
                         * Behind the catch-up on purpose: coming back to an update, being asked
                         * to make three privacy decisions and then walked through six features
                         * before ever reaching the app is not a welcome. Somebody who has just
                         * answered those gets the tour on their next launch instead.
                         *
                         * Not behind the setup wizard. A first install is shown round as soon as
                         * setup is done, in the same launch: held back the way the catch-up
                         * holds it, the first session had no tour at all and the second opened
                         * on one for no reason anybody could see.
                         *
                         * Latched the same way and for the same reason: finishing writes the
                         * preference, and a screen that vanished under the finger that dismissed
                         * it would read as a crash.
                         */
                        val (walkthroughSeen, setWalkthroughSeen) =
                            rememberPreference(WalkthroughSeenVersionKey, defaultValue = 0)
                        val pendingStops = remember(walkthroughSeen) { tourFor(walkthroughSeen) }
                        // Whether this launch opened on the wizard, read from the stored value at
                        // the first frame. catchUpDone holds the tour back to the next launch:
                        // keyed on the catch-up being open alone, the effect below ran again the
                        // moment it closed and started the tour straight after, which every
                        // 0.10.9 upgrader would have met, since none has answered the usage count
                        // yet. A launch that opened on the wizard is the one that is not held.
                        val wizardThisLaunch = rememberSaveable { oobeStatus < OOBE_VERSION }

                        /*
                         * The welcome back page (WelcomeBack.kt): for somebody who has been here
                         * before, what is new since, each thing with a way to it. It takes the
                         * tour's place for them and starts tours of its own, which come back to it.
                         *
                         * It does not open by itself. Whoever is owed it finds a card at the top
                         * of Home (welcomeCardFor): "See" opens the page, and the cross marks this
                         * build as seen with nothing opened, as closing the page does.
                         *
                         * welcomeEverything is the page asked for from Settings: it lists all
                         * there is, and closing it marks nothing as seen, unless a card was
                         * waiting, which a longer list than its own has then answered.
                         */
                        var welcomeOpen by rememberSaveable { mutableStateOf(false) }
                        var welcomeEverything by rememberSaveable { mutableStateOf(false) }
                        // What "Show me" has asked for and which cards were looked at. Saved, so
                        // that a rotation between the tap and the first bubble starts the tour
                        // again instead of leaving neither it nor the page: see WelcomeShow.
                        val welcomeShow = rememberSaveable(saver = WelcomeShow.Saver) { WelcomeShow() }
                        var tourFromWelcome by rememberSaveable { mutableStateOf(false) }
                        // Quick picks is only listed where it has chips to be shown: see Install.
                        val quickPicksSource by rememberEnumPreference(QuickPicksSourceKey, defaultValue = QuickPicksSource.YOUTUBE)
                        val install = Install(quickPicksChips = quickPicksSource.orOffered().hasChips)
                        val newThings = remember(walkthroughSeen, welcomeEverything, quickPicksSource) {
                            newThingsFor(walkthroughSeen, everything = welcomeEverything, install = install)
                        }
                        // Owed, whatever has the screen just now. Asked of what is new since
                        // somebody was last here and not of the page's list, which is all there
                        // is while the page asked for from Settings is open.
                        val welcomeOwed = welcomeCardFor(walkthroughSeen, install = install) != null
                        // The card as Home is to show it at this moment. A tour on its way from
                        // the page counts as one that is up: the card would otherwise show for
                        // the moment between the page closing and the first bubble.
                        val welcomeCard by rememberUpdatedState(
                            welcomeCardFor(
                                walkthroughSeen,
                                install = install,
                                setupDone = oobeStatus >= OOBE_VERSION,
                                questionsOpen = catchUpOpen,
                                tourUp = tourState.running || tourFromWelcome,
                                pageOpen = welcomeOpen,
                            )
                        )
                        // "See": the page as it used to open by itself. The cross: this build
                        // marked as seen, and nothing opened. Both remembered, because they are
                        // handed to the graph of destinations, which is built again whenever
                        // what it is given changes, and the setter of a preference is a new
                        // function at every recomposition.
                        val seeWelcome = remember {
                            {
                                welcomeEverything = false
                                welcomeShow.startOver()
                                welcomeOpen = true
                            }
                        }
                        val dismissWelcome = remember { { setWalkthroughSeen(BuildConfig.VERSION_CODE) } }

                        // Nothing starts by itself for somebody who is owed the card: not the
                        // page, and not the tour's handful of new stops, which the page lists.
                        LaunchedEffect(oobeStatus, catchUpOpen, pendingStops, welcomeOwed, updatePromptVisible) {
                            if (!catchUpOpen && (!catchUpDone || wizardThisLaunch) && !updatePromptVisible &&
                                oobeStatus >= OOBE_VERSION && pendingStops.isNotEmpty() && !welcomeOwed &&
                                !tourState.running && !welcomeOpen && !tourFromWelcome
                            ) {
                                // Straight from the wizard, its screen has to be gone and Home's
                                // controls back first: a stop on Home whose control has not
                                // reported when the tour starts is left out of it.
                                if (wizardThisLaunch) withTimeoutOrNull(3000) {
                                    while (navController.currentDestination?.route == "setup_wizard" || !TourTargets.known(Tour.SEARCH_BAR)) delay(50)
                                }
                                // A beat after the first frame, so the controls it points at have
                                // reported where they are. Pointing at a target that has not been
                                // measured yet puts the hole in the top left corner.
                                delay(600)
                                tourState.start(pendingStops)
                            }
                        }

                        // Asked for from Settings, under About.
                        LaunchedEffect(tourState.welcomeAsked) {
                            if (tourState.welcomeAsked) {
                                tourState.welcomeAsked = false
                                welcomeEverything = true
                                welcomeShow.startOver()
                                welcomeOpen = true
                            }
                        }

                        // Home's own screen, at its top. Not the start destination, which is the
                        // tab the app opens on and need not be Home. And the top, because what a
                        // stop on Home points at is an item of a lazy list, which is not there at
                        // all once Home has been scrolled away from it. Home listens for
                        // scrollToTop, as it does when its tab is tapped again.
                        val goHome = {
                            if (navController.currentDestination?.route != Tour.ROUTE_HOME &&
                                !navController.popBackStack(Tour.ROUTE_HOME, inclusive = false)
                            ) {
                                navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
                                if (navController.currentDestination?.route != Tour.ROUTE_HOME) {
                                    navigateToNavTab(navController, Tour.ROUTE_HOME, navigationItems, navController.currentBackStackEntry)
                                }
                            }
                            navController.currentBackStackEntry?.savedStateHandle?.set("scrollToTop", true)
                        }

                        // To the screen a stop is on. From one screen of Settings to another
                        // the tour leaves the first as it goes, the way somebody would by the
                        // arrow, so that there is one screen over the list at a time and not
                        // a dozen piled up under the last by the end of the closer look. Going
                        // from the list into a screen this pops nothing, and neither does going
                        // into Settings from Home, where the list is not underneath yet.
                        val tourGoTo: (String) -> Unit = { route ->
                            if (navController.currentDestination?.route != route) {
                                navController.navigate(route) { popUpTo(Tour.ROUTE_SETTINGS) }
                            }
                        }

                        // A tour from the page: to the screen its first stop is on, and started once
                        // what it points at is there. If nothing of it is on screen (Quick picks
                        // has no chips while the engine has fallen back to another row), the page
                        // comes back and says so, rather than a button that did nothing, and the
                        // card is not ticked.
                        val showFromWelcome: (String, List<TourStop>) -> Unit = { card, stops ->
                            if (stops.isEmpty()) {
                                Toast.makeText(this@MainActivity, R.string.welcome_back_not_here, Toast.LENGTH_LONG).show()
                            } else {
                                welcomeOpen = false
                                tourFromWelcome = true
                                welcomeShow.ask(card, stops)
                            }
                        }

                        // Started by an effect of what was asked, not by a coroutine of the tap.
                        // A rotation ends either, but what was asked is saved, so the effect runs
                        // again on the other side of it and the tour still starts.
                        LaunchedEffect(welcomeShow.waiting) {
                            if (!welcomeShow.waiting) return@LaunchedEffect
                            val stops = welcomeShow.stops
                            val first = stops.firstOrNull()
                            // After a rotation this can run before the screens are back.
                            val screensThere = withTimeoutOrNull(3000) {
                                while (navController.currentDestination == null) delay(50)
                                true
                            } == true
                            if (screensThere && first != null) {
                                val route = first.route
                                if (route == null) goHome()
                                else if (navController.currentDestination?.route != route) navController.navigate(route)
                                val target = first.targetId
                                if (target != null) withTimeoutOrNull(3000) { while (!TourTargets.known(target)) delay(50) }
                                delay(150)
                                tourState.start(stops)
                            }
                            if (tourState.running) {
                                welcomeShow.shown()
                            } else {
                                tourFromWelcome = false
                                if (screensThere) navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
                                welcomeOpen = true
                                Toast.makeText(this@MainActivity, R.string.welcome_back_not_here, Toast.LENGTH_LONG).show()
                                welcomeShow.notShown()
                            }
                        }

                        if (welcomeOpen && !tourState.running) {
                            WelcomeBack(
                                things = newThings,
                                seen = welcomeShow.lookedAt.toSet(),
                                returning = !welcomeEverything,
                                // A card is ticked once there has been something to see: the
                                // launcher's sheet for the widget, the tour's first bubble for
                                // the rest. Not at the tap, which ticked cards that led nowhere.
                                onShow = { thing ->
                                    if (thing.action == NewThingAction.ADD_WIDGET) {
                                        val widgets = AppWidgetManager.getInstance(this@MainActivity)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && widgets.isRequestPinAppWidgetSupported) {
                                            widgets.requestPinAppWidget(ComponentName(this@MainActivity, MusicWidgetReceiver::class.java), null, null)
                                            welcomeShow.looked(thing.id)
                                        } else {
                                            Toast.makeText(this@MainActivity, R.string.welcome_back_no_widget_host, Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        showFromWelcome(thing.id, thing.stops)
                                    }
                                },
                                // The four groups and back to the page. The question the
                                // tutorial's walk ends on is not asked here: whoever taps this
                                // has asked for the walk, and the tour of the settings is one
                                // row down under About.
                                onShowSettings = { showFromWelcome(SETTINGS_WALK, SETTINGS_TOUR) },
                                onDone = {
                                    welcomeOpen = false
                                    if (!welcomeEverything || welcomeOwed) setWalkthroughSeen(BuildConfig.VERSION_CODE)
                                    welcomeEverything = false
                                },
                            )
                        }

                        // The tutorial is over once its question is up, whatever the answer, so
                        // it is marked as seen there and not only when the tour ends: somebody
                        // who says yes and leaves the app half way round the settings is not
                        // given the whole tutorial again at the next launch.
                        LaunchedEffect(tourState.asking) {
                            if (tourState.asking && !tourFromWelcome) setWalkthroughSeen(BuildConfig.VERSION_CODE)
                        }

                        if (catchUpOpen) {
                            OptInCatchUp(onDone = {
                                catchUpOpen = false
                                catchUpDone = true
                                // The launch ping already ran, before there was an answer to read.
                                // Somebody who has just said yes should count today rather than
                                // tomorrow, so ask once more now that the preference is written.
                                coroutineScope.launch { activeCount.ping() }
                            })
                        }

                        // Behind the expanded player or the walkthrough, the screen is still composed, and
                        // the arrow keys would wander into controls nobody can see.
                        val screenCovered by remember(playerBottomSheetState, tourState) {
                            derivedStateOf { playerBottomSheetState.progress >= 0.5f || tourState.running }
                        }

                        // From the keys, Back on a tab's own screen first takes focus down to the
                        // tabs: other than scrolling to the very end of a long list, the only way
                        // there. The next Back does what it always did.
                        var contentHasFocus by remember { mutableStateOf(false) }
                        val selectedTabFocus = remember { FocusRequester() }
                        val onTabRoot by remember(navigationItems) {
                            derivedStateOf { navigationItems.any { it.route == navBackStackEntry?.destination?.route } }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .dpadOverlayEscape()
                        ) {
                            Log.v(MAIN_TAG, "RC-3")


                            val (backAnimations) = rememberPreference(BackAnimationsKey, defaultValue = true)
                            val navHost: @Composable() (() -> Unit) = @Composable {
                                NavHost(
                                    navController = navController,
                                    startDestination = (Screens.getAllScreens()
                                        .find { it.route == defaultOpenTab })?.route
                                        ?: Screens.Home.route,
                                    enterTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }
                                        val previousRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }

                                        if (currentRouteIndex == -1 || currentRouteIndex > previousRouteIndex)
                                            slideInHorizontally { it / 8 } + fadeIn(tween(200))
                                        else
                                            slideInHorizontally { -it / 8 } + fadeIn(tween(200))
                                    },
                                    exitTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }
                                        val targetRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }

                                        if (targetRouteIndex == -1 || targetRouteIndex > currentRouteIndex)
                                            slideOutHorizontally { -it / 8 } + fadeOut(tween(200))
                                        else
                                            slideOutHorizontally { it / 8 } + fadeOut(tween(200))
                                    },
                                    // Back reveals rather than cross fades. Both pop transitions used to
                                    // fade a whole screen in while fading another whole screen out, over the
                                    // same eighth-width slide, so halfway through a back you were looking at
                                    // two half transparent screens stacked on top of each other. Under a
                                    // predictive back gesture, which seeks these transitions frame by frame,
                                    // you sat in that ghosted middle for as long as your finger was down.
                                    //
                                    // Now the screen being left slides off in one piece and stays opaque,
                                    // and the one underneath comes back at full opacity from a small
                                    // parallax offset. Navigation gives the popping screen the higher z
                                    // index, so it passes over the top rather than under.
                                    popEnterTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }
                                        val previousRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }

                                        val forward = previousRouteIndex != -1 && previousRouteIndex < currentRouteIndex
                                        if (!backAnimations) {
                                            // The cross fade this replaced, kept for anyone who wants it back.
                                            if (forward) slideInHorizontally { it / 8 } + fadeIn(tween(200))
                                            else slideInHorizontally { -it / 8 } + fadeIn(tween(200))
                                        } else if (forward)
                                            slideInHorizontally(NavPopSpec) { it / 5 }
                                        else
                                            slideInHorizontally(NavPopSpec) { -it / 5 }
                                    },
                                    popExitTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }
                                        val targetRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }

                                        val leftwards = currentRouteIndex != -1 && currentRouteIndex < targetRouteIndex
                                        if (!backAnimations) {
                                            if (leftwards) slideOutHorizontally { -it / 8 } + fadeOut(tween(200))
                                            else slideOutHorizontally { it / 8 } + fadeOut(tween(200))
                                        } else if (leftwards)
                                            slideOutHorizontally(NavPopSpec) { -it }
                                        else
                                            slideOutHorizontally(NavPopSpec) { it }
                                    },
                                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                                )
                                {
                                    appDestinations(
                                        navController = navController,
                                        scrollBehavior = scrollBehavior,
                                        searchActive = { searchActive },
                                        onSearchActiveChange = { searchActive = it },
                                        tourState = tourState,
                                        welcomeCard = { welcomeCard },
                                        onWelcomeSee = seeWelcome,
                                        onWelcomeDismiss = dismissWelcome,
                                        appScope = coroutineScope,
                                    )
                                }
                            }

                            val navbar: @Composable() (() -> Unit) = @Composable {
                                val dockGlass = rememberGlassSpec()
                                val dockTint = dockGlass?.tint() ?: Color.Transparent
                                val dockShape = RoundedCornerShape(if (slimNav) 26.dp else 28.dp)

                                val navigationBarHeight by animateDpAsState(
                                    targetValue = NavigationBarHeight,
                                    animationSpec = NavigationBarAnimationSpec,
                                    label = ""
                                )

                                NavigationBar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .blockFocusWhen(screenCovered)
                                        .dpadOverlay()
                                        .dpadTabBar()
                                        .height(bottomInset + getNavPadding())
                                        .offset {
                                            if (navigationBarHeight == 0.dp) {
                                                IntOffset(
                                                    x = 0,
                                                    y = (bottomInset + NavigationBarHeight).roundToPx()
                                                )
                                            } else {
                                                val slideOffset =
                                                    (bottomInset + NavigationBarHeight) * playerBottomSheetState.progress.coerceIn(
                                                        0f,
                                                        1f
                                                    )
                                                val hideOffset =
                                                    (bottomInset + NavigationBarHeight) * (1 - navigationBarHeight / NavigationBarHeight)
                                                IntOffset(
                                                    x = 0,
                                                    y = (slideOffset + hideOffset).roundToPx()
                                                )
                                            }
                                        }
                                        .then(
                                            if (navGlass) {
                                                Modifier
                                                    // windowInsets is zeroed below for the glass
                                                    // path, which would otherwise throw away the
                                                    // horizontal component. useNavRail is EXPANDED
                                                    // (>=840dp) so a landscape phone still gets the
                                                    // bar and would slide tabs under a cutout.
                                                    .windowInsetsPadding(
                                                        windowsInsets.only(WindowInsetsSides.Horizontal)
                                                            .add(cutoutInsets.only(WindowInsetsSides.Horizontal))
                                                    )
                                                    // Shrinks the drawn panel into a floating dock
                                                    // without changing the measured outer height,
                                                    // so collapsedBound and playerAwareWindowInsets
                                                    // are untouched.
                                                    .padding(
                                                        start = 12.dp,
                                                        end = 12.dp,
                                                        bottom = (bottomInset - 4.dp).coerceAtLeast(6.dp)
                                                    )
                                                    .drawBackdrop(
                                                        backdrop = appBackdrop,
                                                        shape = { dockShape },
                                                        effects = {
                                                            vibrancy()
                                                            blur(dockGlass!!.blur.toPx())
                                                            lens(
                                                                refractionHeight = 20f.dp.toPx() * dockGlass.lensT,
                                                                refractionAmount = 28f.dp.toPx() * dockGlass.lensT,
                                                                depthEffect = true
                                                            )
                                                        }
                                                    )
                                                    // Tint goes here, not in onDrawSurface: that
                                                    // callback runs on the node's own unclipped
                                                    // canvas, so it would paint a square patch.
                                                    .background(dockTint, dockShape)
                                            } else Modifier
                                        ),
                                    containerColor = if (navGlass) Color.Transparent
                                    else MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    windowInsets = if (navGlass) WindowInsets(0, 0, 0, 0)
                                    else NavigationBarDefaults.windowInsets
                                ) {
                                    navigationItems.fastForEach { screen ->
                                        // TODO: display selection when based on root page user entered
//                                        val isSelected = navBackStackEntry?.destination?.hierarchy?.any {
//                                            it.route?.substringBefore("?")?.substringBefore("/") == screen.route
//                                        } == true
                                        val tabSelected = navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true
                                        // The slim bar has no text under its icons, and a tab
                                        // then had no name at all for a screen reader: Material
                                        // drops an icon's own description whenever a label is
                                        // handed over, shown or not. So the name is on the tab.
                                        val tabName = stringResource(screen.titleId)
                                        NavigationBarItem(
                                            // Only the library tab is pointed at, so only it is
                                            // reported. Tagging every tab would have four of them
                                            // writing bounds on every recomposition of the bar.
                                            modifier = (if (screen.route == Screens.Library.route) {
                                                Modifier.tourTarget(Tour.NAV_LIBRARY)
                                            } else Modifier).then(
                                                if (tabSelected) Modifier.focusRequester(selectedTabFocus) else Modifier
                                            ).then(
                                                if (slimNav) Modifier.semantics { contentDescription = tabName } else Modifier
                                            ),
                                            selected = tabSelected,
                                            icon = {
                                                Icon(
                                                    screen.icon,
                                                    contentDescription = null
                                                )
                                            },
                                            label = {
                                                if (!slimNav) {
                                                    Text(
                                                        text = stringResource(screen.titleId),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            },
                                            onClick = {
                                                if (playerBottomSheetState.isExpanded) {
                                                    playerBottomSheetState.collapseSoft()
                                                }

                                                // Close the search overlay first. It is drawn on
                                                // top of the current destination rather than being
                                                // one, so while it is open the checks below still
                                                // see the destination underneath it and would just
                                                // scroll that to top, leaving search on screen.
                                                if (searchActive) {
                                                    searchActive = false
                                                }

                                                if (navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true) {
                                                    if (!searchActive) {
                                                        navBackStackEntry?.savedStateHandle?.set(
                                                            "scrollToTop",
                                                            true
                                                        )
                                                    }
                                                } else {
                                                    navigateToNavTab(navController, screen.route, navigationItems, navBackStackEntry)
                                                }

                                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            }
                                        )
                                    }
                                }
                            }

                            @Composable
                            fun navRail(alignment: Alignment) {
                                val layoutDirection = LocalLayoutDirection.current
                                val navigationBarHeight by animateDpAsState(
                                    targetValue = NavigationBarHeight,
                                    animationSpec = NavigationBarAnimationSpec,
                                    label = ""
                                )
                                val leftInset = remember {
                                    derivedStateOf {
                                        playerAwareWindowInsets.getLeft(density, layoutDirection).dp
                                    }
                                }
                                NavigationRail(
                                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                                    header = {
                                        Spacer(Modifier.height(8.dp))
                                        Image(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .padding(start = 8.dp),
                                            painter = painterResource(R.drawable.small_icon),
                                            contentDescription = null
                                        )
                                    },
                                    // The offset goes before verticalScroll so the scroll container
                                    // slides away with the rail. After it, only the rail's content
                                    // moved: the scroll container stayed where the rail had been, on
                                    // top of the open player, and swallowed every tap on its left strip.
                                    modifier = Modifier
                                        .align(alignment)
                                        .blockFocusWhen(screenCovered)
                                        .fillMaxHeight()
                                        .offset {
                                            if (navigationBarHeight == 0.dp) {
                                                IntOffset(
                                                    x = 0,
                                                    y = (bottomInset + NavigationBarHeight).roundToPx()
                                                )
                                            } else {
                                                val slideOffset =
                                                    (bottomInset + NavigationBarHeight + leftInset.value) *
                                                            playerBottomSheetState.progress.coerceIn(0f, 1f)
                                                val hideOffset =
                                                    (bottomInset + NavigationBarHeight) * (1 - navigationBarHeight / NavigationBarHeight)
                                                IntOffset(
                                                    x = -(slideOffset + hideOffset).roundToPx(),
                                                    y = 0
                                                )
                                            }
                                        }
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    navigationItems.fastForEach { screen ->
                                        // TODO: display selection when based on root page user entered
//                                                val isSelected = navBackStackEntry?.destination?.hierarchy?.any {
//                                                    it.route?.substringBefore("?")?.substringBefore("/") == screen.route
//                                                } == true
                                        // Named for a screen reader when slim, as in the bar above.
                                        val tabName = stringResource(screen.titleId)
                                        NavigationRailItem(
                                            modifier = if (slimNav) Modifier.semantics { contentDescription = tabName } else Modifier,
                                            selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true,
                                            icon = {
                                                Icon(
                                                    screen.icon,
                                                    contentDescription = null
                                                )
                                            },
                                            label = {
                                                if (!slimNav) {
                                                    Text(
                                                        text = stringResource(screen.titleId),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            },
                                            onClick = {
                                                if (playerBottomSheetState.isExpanded) {
                                                    playerBottomSheetState.collapseSoft()
                                                }
                                                // Close the search overlay first. It is drawn on
                                                // top of the current destination rather than being
                                                // one, so while it is open the checks below still
                                                // see the destination underneath it and would just
                                                // scroll that to top, leaving search on screen.
                                                if (searchActive) {
                                                    searchActive = false
                                                }

                                                if (navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true) {
                                                    if (!searchActive) {
                                                        navBackStackEntry?.savedStateHandle?.set(
                                                            "scrollToTop",
                                                            true
                                                        )
                                                    }
                                                } else {
                                                    navigateToNavTab(navController, screen.route, navigationItems, navBackStackEntry)
                                                }

                                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            }
                                        )
                                    }
                                }
                            }

                            val bottomSheetMenu: @Composable() (() -> Unit) = @Composable {
                                BottomSheetMenu(
                                    state = LocalMenuState.current,
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            // Over the page colour, like the app backdrop, so the blur has something between rows.
                            val navHostSurface = rememberUpdatedState(MaterialTheme.colorScheme.surface)
                            val navHostBackdrop = rememberLayerBackdrop(
                                onDraw = remember {
                                    val draw: ContentDrawScope.() -> Unit = {
                                        drawRect(navHostSurface.value)
                                        drawContent()
                                    }
                                    draw
                                }
                            )

                            // phone
                            // Everything the glass panels refract goes in here. The .background()
                            // must come AFTER layerBackdrop: LayerBackdropNode records only what
                            // follows it in the chain, and the app's surface fill lives on the
                            // outer BoxWithConstraints, outside this layer. Without it the recorded
                            // layer is transparent between rows and blur() bleeds into nothing.
                            // It used to come before, which looks the same on screen and records no
                            // fill: the dock and the mini player then drew a blurred copy of the rows
                            // with nothing behind it, and the rows themselves showed through that
                            // copy, sharp. Only rows with a fill of their own were spared, such as
                            // the song rows, which sit on a surface for the swipe to queue.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (navGlass) {
                                            Modifier
                                                .layerBackdrop(appBackdrop)
                                                .background(MaterialTheme.colorScheme.surface)
                                        } else Modifier
                                    )
                                    .onFocusChanged { contentHasFocus = it.hasFocus }
                                    .blockFocusWhen(screenCovered)
                            ) {
                                // The nav host in a layer of its own, which the search pill beside it reads for
                                // its glass (LocalSearchBarGlass). Always wrapped so turning glass on or off does
                                // not move the nav host in the composition.
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .then(if (navGlass) Modifier.layerBackdrop(navHostBackdrop).graphicsLayer() else Modifier)
                                ) {
                                    navHost()
                                }

                                // Only this search bar gets the glass. The "search" destination composes another
                                // one inside the nav host, and that one would be reading a layer it is part of.
                                CompositionLocalProvider(
                                    LocalSearchBarGlass provides if (navGlass) GlassSpec(navHostBackdrop, glassIntensity.coerceIn(0f, 1f)) else null
                                ) {
                                    SearchBarContainer(navController, scrollBehavior, searchActive) { searchActive = it }
                                }
                            }

                            // BottomSheetPlayer and the dock BOTH stay outside the published layer.
                            // MiniPlayer consumes this backdrop, so putting the sheet inside would
                            // make the layer contain a reader of itself: RenderNode::prepareTreeImpl
                            // recurses until the native stack overflows and the process dies. The
                            // sheet's collapsed fill is transparent under glass anyway (Player.kt),
                            // so there is nothing of it worth capturing.
                            // Not over setup either, even once it is marked done: its exit page stays on
                            // screen for a moment after, and the configurator runs it again later.
                            // Derived: reading the route here recomposed this whole scope, nav host
                            // and player included, on every navigation. See the note above tabRoutes.
                            val onSetupWizard by remember {
                                derivedStateOf { navBackStackEntry?.destination?.route == "setup_wizard" }
                            }
                            if (oobeStatus >= OOBE_VERSION && !onSetupWizard) {
                                BottomSheetPlayer(
                                    state = playerBottomSheetState,
                                    navController = navController
                                )

                                if (!useNavRail) {
                                    navbar()
                                    BackHandler(
                                        enabled = contentHasFocus && onTabRoot && !searchActive &&
                                                LocalInputModeManager.current.inputMode == InputMode.Keyboard
                                    ) {
                                        runCatching { selectedTabFocus.requestFocus() }
                                    }
                                } else {
                                    navRail(if (LocalLayoutDirection.current == LayoutDirection.Rtl) Alignment.BottomEnd else Alignment.BottomStart)
                                }
                            }
                            bottomSheetMenu()

                            // After the navigation bar, not before it. The bar is a sibling of the
                            // content box and is drawn after it, so an overlay inside that box ends
                            // up underneath the bar: the tour would dim the screen right down to
                            // the bar's top edge and then point at a Library tab it had neither
                            // dimmed nor lit. Out here it is also outside the published glass
                            // layer, which it has no business being refracted by.
                            TourOverlay(
                                state = tourState,
                                // Two stops in a row on one screen are one screen, not two of it.
                                onNavigate = tourGoTo,
                                // Back out of a stop's screen to the one the stop before it is
                                // on. That screen is underneath, since the tour came through it.
                                // If it is not, it is opened the way a step forward opens it.
                                onNavigateBack = { route ->
                                    if (route == null) {
                                        goHome()
                                    } else if (navController.currentDestination?.route != route && !navController.popBackStack(route, inclusive = false)) {
                                        tourGoTo(route)
                                    }
                                },
                                onFinish = {
                                    if (tourFromWelcome) {
                                        // Back to the page it was started from, and to Home under
                                        // it, so that closing the page leaves somebody where they
                                        // were and not in whichever setting was shown last.
                                        tourFromWelcome = false
                                        navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
                                        welcomeOpen = true
                                    } else {
                                        // Not with a card waiting on Home: a tour asked for
                                        // under About has shown none of what the card is about.
                                        if (!welcomeOwed) setWalkthroughSeen(BuildConfig.VERSION_CODE)
                                        // A first install's tour ends on its walk round Settings,
                                        // and the closer look in whichever of their screens it
                                        // had got to. Back out of them, so that what the tour
                                        // leaves somebody in is the app and not its settings.
                                        val route = navController.currentDestination?.route.orEmpty()
                                        if (route == Tour.ROUTE_SETTINGS || route.startsWith(Tour.ROUTE_SETTINGS + "/")) {
                                            navController.popBackStack(Tour.ROUTE_SETTINGS, inclusive = true)
                                        }
                                    }
                                },
                            )

                            // A setting named in an explanation, jumped to and flashed.
                            SettingJumpHost(navController)

                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                                    .align(Alignment.BottomCenter)
                            )

                            // Only after setup, so a crash during the wizard is offered once it is done.
                            if (oobeStatus >= OOBE_VERSION) CrashReportDialog()

                            // Setup wizard. Not when it is already on the back stack: a rotation
                            // recreates the activity with the stack restored, wizard included, and
                            // this ran again on top of it, so there were two, and Done on the top
                            // one left the other showing with no way forward.
                            LaunchedEffect(Unit) {
                                val alreadyOpen = runCatching { navController.getBackStackEntry("setup_wizard") }.isSuccess
                                if (oobeStatus < OOBE_VERSION && !alreadyOpen) {
                                    navController.navigate("setup_wizard")
                                }
                            }

                        }
                    }
                }
            }
        }
    }

    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        // sdk24 support
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }

    companion object {
        const val ACTION_SEARCH = "dev.skye.intertune.action.SEARCH"
        const val ACTION_SONGS = "dev.skye.intertune.action.SONGS"
        const val ACTION_ALBUMS = "dev.skye.intertune.action.ALBUMS"
        const val ACTION_PLAYLISTS = "dev.skye.intertune.action.PLAYLISTS"
        const val ACTION_PLAY_LIKED = "dev.skye.intertune.action.PLAY_LIKED"
    }
}

/**
 * Navigate to a bottom-bar / rail tab that is not the one currently shown.
 *
 * The only subtlety is [restoreState]. The nav graph is flat, so every route is a sibling and
 * there are no per-tab back stacks; `popUpTo(start) { saveState = true }` saves whatever was
 * above the start destination, and `restoreState = true` puts it back.
 *
 * That is what upstream #959 was: from a playlist, tapping Library saved the stack and then
 * immediately restored it, landing you back in the playlist. "Nothing happens."
 *
 * The fix there was a branch that called [NavHostController.navigateUp] whenever the current
 * destination was not itself a tab. That escapes the sub-page, but it ignores *which* tab was
 * tapped, so from History tapping Songs walked back to Home instead — upstream #1168.
 *
 * Restoring is only ever wanted when hopping between tab roots, where it preserves each tab's
 * scroll position. Coming from a sub-page there is nothing worth restoring and restoring is
 * precisely what breaks it. So: restore when leaving a tab, jump cleanly when leaving anything
 * else. One rule, both issues.
 */
private fun navigateToNavTab(
    navController: NavHostController,
    route: String,
    navigationItems: List<Screens>,
    current: NavBackStackEntry?,
) {
    val leavingATab = navigationItems.any { scr ->
        current?.destination?.hierarchy?.any { it.route == scr.route } == true
    }
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = leavingATab
    }
}

/** Where a library launcher shortcut opens: [screen], and the Library filter to show there, if any. */
internal data class LibraryShortcut(val screen: Screens, val filter: Screens.LibraryFilter?)

/**
 * The Songs, Albums and Playlists shortcuts open their own tab when the navigation bar has it,
 * and otherwise Library showing that filter. Null for any other action.
 */
internal fun libraryShortcut(action: String?, navigationItems: List<Screens>): LibraryShortcut? {
    val (tab, filter) = when (action) {
        MainActivity.ACTION_SONGS -> Screens.Songs to Screens.LibraryFilter.SONGS
        MainActivity.ACTION_ALBUMS -> Screens.Albums to Screens.LibraryFilter.ALBUMS
        MainActivity.ACTION_PLAYLISTS -> Screens.Playlists to Screens.LibraryFilter.PLAYLISTS
        else -> return null
    }
    return if (tab in navigationItems) LibraryShortcut(tab, null) else LibraryShortcut(Screens.Library, filter)
}

/**
 * What to open for the intent that started the activity: its link, or the text it was shared
 * with, which youtubeNavigator reads as a link or ignores. Null for a widget tap, which plays its
 * own song, and for an intent started again from recents or restored with the activity, which
 * would open an old link over whatever is on screen now.
 */
internal fun launchLink(action: String?, data: String?, text: String?, fromRecents: Boolean): String? =
    if (fromRecents || action == WidgetCommands.ACTION_PLAY_SONG) null else data ?: text

/**
 * What to open for an intent handed to onNewIntent: taken the way [launchLink] takes it, with no
 * recents check, because onNewIntent is only ever handed an intent that is arriving now.
 */
internal fun newIntentLink(action: String?, data: String?, text: String?): String? =
    launchLink(action, data, text, fromRecents = false)

/**
 * The words of a play-from-search intent, empty when it names nothing ("play some music"), or
 * null when the intent is not one. One that carries data came in through a YouTube link filter,
 * which names the action as well, and is left to the link handling.
 */
internal fun searchToPlay(action: String?, hasData: Boolean, query: String?): String? =
    if (action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH && !hasData) query?.trim().orEmpty() else null

/**
 * A search as the media session takes one: no id, and the words in the request metadata. This is
 * what media3 makes of "Hey Google, play X" in Android Auto, so
 * MediaLibrarySessionCallback.onSetMediaItems plays it the same way, and empty words resume.
 */
internal fun searchPlayRequest(query: String): MediaItem = MediaItem.Builder()
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
    .build()

/**
 * Whether a found update should be pre-fetched: only with auto-install on, only when nothing else
 * is using the installer, and never on F-Droid. A GitHub apk downloaded there can never be the one
 * that installs, and the preference can still read true from an older build or a restored backup
 * even though its switch is hidden there.
 */
internal fun updatePrefetchOwed(
    autoInstall: Boolean,
    fromFdroid: Boolean,
    installerBusy: Boolean,
    installerIdle: Boolean,
): Boolean = autoInstall && !fromFdroid && !installerBusy && installerIdle


val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalMenuState = staticCompositionLocalOf<MenuState> { error("No menu state provided") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets = compositionLocalOf<WindowInsets> { error("No player WindowInsets provided") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
val LocalSyncUtils = staticCompositionLocalOf<SyncUtils> { error("No SyncUtils provided") }
val LocalScrobbler = staticCompositionLocalOf<Scrobbler> { error("No Scrobbler provided") }
val LocalLoudnessRepair = staticCompositionLocalOf<LoudnessRepair> { error("No LoudnessRepair provided") }
val LocalUpdateChecker = staticCompositionLocalOf<UpdateChecker> { error("No UpdateChecker provided") }
val LocalUpdateInstaller = staticCompositionLocalOf<UpdateInstaller> { error("No UpdateInstaller provided") }
val LocalPollChecker = staticCompositionLocalOf<PollChecker> { error("No PollChecker provided") }
val LocalActiveCount = staticCompositionLocalOf<ActiveCount> { error("No ActiveCount provided") }
val LocalNetworkConnected = staticCompositionLocalOf<Boolean> { error("No Network Status provided") }
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> { error("No SnackbarHostState provided") }

/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.utils.dataStore
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private const val TAG = "AutoCar"

/** The tab the car's screens add to the tree's own: the settings, there at a tap. */
private const val SETTINGS = "settings"

/** The most of one list that is asked for: more than that is not read at the wheel. */
private const val MOST_ASKED = 200

/** How long after the last letter a search is sent. */
private const val TYPED_MS = 450L

private const val TILES = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM

/**
 * Only a car's own software is let in, and with the rework held back, nobody. The list of cars
 * is the library's own, which its documentation says to use and its resources mark as private.
 */
@SuppressLint("PrivateResource")
internal fun carHosts(service: CarAppService): HostValidator {
    val known = HostValidator.Builder(service.applicationContext)
    if (Unreleased.AUTO_REWORK) known.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
    return known.build()
}

/**
 * InterTune's own screens in the car, for a car that draws a music app from descriptions of
 * screens and not only from a tree of lists (Android Auto since its eighth version of them).
 * What that buys a driver: tiles of the largest size instead of the size the car picks, the
 * way to what is playing in the bottom corner of every list, a search button above it, the
 * settings as a tab, and the queue behind the playing screen.
 *
 * That corner is a button in the eighth version. From the ninth the car draws a small player
 * of its own there, with the song and its controls, on every one of these screens without
 * being asked: which is why the build carries the library that speaks the ninth, though no
 * Android Auto released by October 2026 does (17.7 stops at the eighth).
 *
 * Nothing is kept twice. The screens read the same tree a car without them is handed
 * (MediaLibrarySessionCallback, AutoBrowse) through the app's own session, and draw each list
 * as the tree says: tiles where it asks for tiles, rows elsewhere, under its headings. A tap
 * plays by the same ids. An older car never asks for this service and gets the tree.
 */
class AutoCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = carHosts(this)

    override fun onCreateSession(sessionInfo: SessionInfo): Session = AutoCarSession()
}

internal class AutoCarSession : Session() {
    /** The app's own library and player, reached as any car reaches them. */
    lateinit var browser: ListenableFuture<MediaBrowser>
        private set

    private val screens get() = carContext.getCarService(ScreenManager::class.java)

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "the car speaks version ${carContext.carAppApiLevel} of the screens")
        connect()
        val home = AutoTabsScreen(carContext, this)
        if (intent.action != androidx.car.app.media.MediaConstants.ACTION_SHOW_MEDIA_PLAYBACK) return home
        screens.push(home)
        return AutoPlayerScreen(carContext, this)
    }

    /** The car's small player was tapped: it asks for the playing screen. */
    override fun onNewIntent(intent: Intent) {
        if (intent.action == androidx.car.app.media.MediaConstants.ACTION_SHOW_MEDIA_PLAYBACK) showPlayer()
    }

    fun showPlayer() {
        if (screens.top !is AutoPlayerScreen) screens.push(AutoPlayerScreen(carContext, this))
    }

    /** The session is handed to the car, which draws its small player and the playing screen from it. */
    private fun connect() {
        val main = ContextCompat.getMainExecutor(carContext)
        val token = SessionToken(carContext, ComponentName(carContext, MusicService::class.java))
        browser = MediaBrowser.Builder(carContext.applicationContext, token).buildAsync()
        browser.addListener({
            val connected = runCatching { browser.get() }.onFailure { Log.w(TAG, "no player for the car", it) }.getOrNull() ?: return@addListener
            // Another song: the row that is playing is marked, so the list in front is drawn again. It keeps its place.
            connected.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    (screens.top as? AutoListScreen)?.invalidate()
                }
            })
            val asked = connected.sendCustomCommand(SessionCommand(MusicService.COMMAND_GET_BINDER, Bundle.EMPTY), Bundle.EMPTY)
            asked.addListener({
                runCatching {
                    val service = (asked.get().extras.getBinder("music_binder") as MusicService.MusicBinder).service
                    carContext.getCarService(MediaPlaybackManager::class.java)
                        .registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(service.platformToken))
                }.onFailure { Log.w(TAG, "the car was not given the session", it) }
            }, main)
        }, main)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = MediaController.releaseFuture(browser)
        })
    }

    val player: MediaBrowser? get() = if (browser.isDone) runCatching { browser.get() }.getOrNull() else null

    /** What a list of the tree holds; nothing when it cannot be had. */
    suspend fun children(id: String): List<MediaItem> = try {
        browser.await().getChildren(id, 0, MOST_ASKED, null).await().value?.toList().orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "the list \"$id\" could not be had", e)
        emptyList()
    }

    /** What a search finds: the library's answer, then YouTube Music's. */
    suspend fun found(query: String): List<MediaItem> = try {
        val library = browser.await()
        library.search(query, null).await()
        library.getSearchResult(query, 0, 50, null).await().value?.toList().orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "the search could not be done", e)
        emptyList()
    }

    /** Plays what one of the tree's ids names, then shows it playing. */
    fun play(id: String) {
        val to = player ?: return
        to.setMediaItem(MediaItem.Builder().setMediaId(id).build())
        to.prepare()
        to.play()
        showPlayer()
    }
}

// What is drawn, from what the tree hands over.

private fun CarContext.icon(@DrawableRes drawable: Int) = CarIcon.Builder(IconCompat.createWithResource(this, drawable)).build()

/** The app's drawings the tree names in its addresses, by the names it gives them. */
internal val DRAWINGS: Map<String, Int> = mapOf(
    "album" to R.drawable.album,
    "artist" to R.drawable.artist,
    "auto_home" to R.drawable.auto_home,
    "auto_library" to R.drawable.auto_library,
    "auto_recent" to R.drawable.auto_recent,
    "download" to R.drawable.download,
    "favorite" to R.drawable.favorite,
    "music_note" to R.drawable.music_note,
    "queue_music" to R.drawable.queue_music,
    "shuffle_on" to R.drawable.shuffle_on,
)

/** A picture of the tree's: a cover it serves, or one of the app's drawings. True with it for a cover. */
private fun CarContext.picture(address: Uri?, @DrawableRes otherwise: Int): Pair<CarIcon, Boolean> = when (address?.scheme) {
    ContentResolver.SCHEME_CONTENT -> CarIcon.Builder(IconCompat.createWithContentUri(address)).build() to true
    ContentResolver.SCHEME_ANDROID_RESOURCE -> icon(DRAWINGS[address.lastPathSegment] ?: otherwise) to false
    else -> icon(otherwise) to false
}

private fun MediaItem.name(): String = mediaMetadata.title?.toString().orEmpty().ifEmpty { "?" }

private fun MediaItem.under(): String? = (mediaMetadata.subtitle ?: mediaMetadata.artist)?.toString()?.takeIf { it.isNotEmpty() }

/** How the tree wants this drawn: its heading, and tiles or rows (its own word first, then its list's). */
private fun MediaItem.laid(list: Bundle?): AutoBrowse.Laid {
    val own = mediaMetadata.extras
    val style = own?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, 0)?.takeIf { it != 0 }
        ?: list?.getInt(
            if (mediaMetadata.isBrowsable == true) MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE else MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, 0,
        ) ?: 0
    return AutoBrowse.Laid(own?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE), style == TILES)
}

/** A screen that could not be drawn says so. It never takes the app, and the music, down with it. */
private inline fun Screen.safely(draw: () -> Template): Template = try {
    draw()
} catch (e: Exception) {
    Log.e(TAG, "a screen could not be drawn", e)
    MessageTemplate.Builder(carContext.getString(R.string.error_unknown))
        .setHeader(Header.Builder().setStartHeaderAction(Action.APP_ICON).setTitle(carContext.getString(R.string.app_name)).build())
        .build()
}

/** Opens what was tapped: a list as a screen of its own, anything else by playing it. */
private fun Screen.open(session: AutoCarSession, item: MediaItem) {
    if (item.mediaMetadata.isBrowsable == true) screenManager.push(AutoListScreen(carContext, session, item)) else session.play(item.mediaId)
}

// The kinds of picture named below are the ones the eighth version of the car's screens reads; the ninth sizes by section.
@Suppress("DEPRECATION")
private fun Screen.tile(session: AutoCarSession, item: MediaItem): GridItem {
    val (picture, cover) = carContext.picture(item.mediaMetadata.artworkUri, R.drawable.music_note)
    val tile = GridItem.Builder().setTitle(item.name()).setImage(picture, if (cover) GridItem.IMAGE_TYPE_LARGE else GridItem.IMAGE_TYPE_ICON)
    item.under()?.let(tile::setText)
    return tile.setOnClickListener { open(session, item) }.build()
}

@Suppress("DEPRECATION")
private fun Screen.row(session: AutoCarSession, item: MediaItem, playing: String?): Row {
    val (picture, cover) = carContext.picture(item.mediaMetadata.artworkUri, R.drawable.music_note)
    val row = Row.Builder().setTitle(item.name()).setImage(picture, if (cover) Row.IMAGE_TYPE_LARGE else Row.IMAGE_TYPE_ICON)
    item.under()?.let(row::addText)
    if (item.mediaMetadata.isBrowsable == true) row.setBrowsable(true)
    // The song that is playing carries the car's own mark.
    else if (playing != null && item.mediaId.substringAfterLast('/') == playing) row.addAction(Action.MEDIA_PLAYBACK)
    return row.setOnClickListener { open(session, item) }.build()
}

/**
 * What sits in the bottom corner of every screen: the way to what is playing, and above it the
 * way into the search. In the eighth version of the car's screens the first is a button whose
 * tap is ours to answer; later ones draw a small player there themselves and are not asked.
 */
private fun Screen.corner(session: AutoCarSession): List<Action> = buildList {
    if (carContext.carAppApiLevel == CarAppApiLevels.LEVEL_8) {
        add(Action.Builder(Action.MEDIA_PLAYBACK).setOnClickListener { session.showPlayer() }.build())
    }
    add(
        Action.Builder()
            .setIcon(carContext.icon(R.drawable.auto_search))
            // A colour has to be named, and "the default" is not one; the car paints its own whatever it is.
            .setBackgroundColor(CarColor.PRIMARY)
            .setOnClickListener { screenManager.push(AutoSearchScreen(carContext, session)) }
            .build()
    )
}

/** A list of the tree as the sections of a screen, tiles where the tree asks for tiles and rows elsewhere. */
private fun Screen.sectioned(session: AutoCarSession, list: MediaItem?, items: List<MediaItem>?, header: Header?): Template {
    val out = SectionedItemTemplate.Builder().setScrollStatePersistenceStrategy(SectionedItemTemplate.SCROLL_STATE_PRESERVE_INDEX)
    header?.let(out::setHeader)
    corner(session).forEach(out::addAction)
    if (items == null) return out.setLoading(true).build()
    if (items.isEmpty()) return out.addSection(RowSection.Builder().setNoItemsMessage(carContext.getString(R.string.auto_nothing_here)).build()).build()
    val playing = session.player?.currentMediaItem?.mediaId
    for ((how, run) in AutoBrowse.sections(items) { it.laid(list?.mediaMetadata?.extras) }) {
        if (how.tile) {
            val tiles = GridSection.Builder().setItemSize(GridSection.ITEM_SIZE_EXTRA_LARGE)
            how.heading?.let(tiles::setTitle)
            run.forEach { tiles.addItem(tile(session, it)) }
            out.addSection(tiles.build())
        } else {
            val rows = RowSection.Builder()
            how.heading?.let(rows::setTitle)
            run.forEach { rows.addItem(row(session, it, playing)) }
            out.addSection(rows.build())
        }
    }
    return out.build()
}

/** Where the car's screens start: the tree's tabs, and the settings as one more. */
internal class AutoTabsScreen(carContext: CarContext, private val session: AutoCarSession) : Screen(carContext) {
    private var tabs: List<MediaItem>? = null
    private var tab: String? = null
    private val lists = HashMap<String, List<MediaItem>>()
    private var settings: AutoSettings? = null

    init {
        lifecycleScope.launch {
            tabs = session.children(MusicService.ROOT)
            if (tab == null) tab = tabs?.firstOrNull()?.mediaId
            invalidate()
            load(tab)
        }
        lifecycleScope.launch {
            carContext.dataStore.data.map { AutoSettings.of(it) }.distinctUntilChanged().collect {
                settings = it
                if (tab == SETTINGS) invalidate()
            }
        }
        // Back from the playing screen or from another app: what is to carry on with has changed.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = load(tab)
        })
    }

    private fun load(id: String?) {
        if (id == null || id == SETTINGS) return
        lifecycleScope.launch {
            lists[id] = session.children(id)
            if (tab == id) invalidate()
        }
    }

    override fun onGetTemplate(): Template = safely {
        val out = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                tab = tabContentId
                invalidate()
                load(tabContentId)
            }
        }).setHeaderAction(Action.APP_ICON)
        val known = tabs?.take(3)
        val now = tab
        if (known == null || now == null) return@safely out.setLoading(true).build()
        for (item in known) {
            out.addTab(Tab.Builder().setContentId(item.mediaId).setTitle(item.name()).setIcon(carContext.picture(item.mediaMetadata.artworkUri, R.drawable.music_note).first).build())
        }
        out.addTab(Tab.Builder().setContentId(SETTINGS).setTitle(carContext.getString(R.string.settings)).setIcon(carContext.icon(R.drawable.auto_settings)).build())
        val inside = if (now == SETTINGS) {
            val list = ListTemplate.Builder()
            corner(session).forEach(list::addAction)
            settings?.let { list.setSingleList(settingsLines(carContext, it, screenManager)) } ?: list.setLoading(true)
            list.build()
        } else {
            sectioned(session, known.firstOrNull { it.mediaId == now }, lists[now], header = null)
        }
        out.setTabContents(TabContents.Builder(inside).build()).setActiveTabContentId(now).build()
    }
}

/** One list of the tree: a playlist, an album, the albums, what was played lately. */
internal class AutoListScreen(carContext: CarContext, private val session: AutoCarSession, private val list: MediaItem) : Screen(carContext) {
    private var items: List<MediaItem>? = null

    init {
        lifecycleScope.launch {
            items = session.children(list.mediaId)
            invalidate()
        }
    }

    override fun onGetTemplate(): Template = safely {
        sectioned(session, list, items, Header.Builder().setStartHeaderAction(Action.BACK).setTitle(list.name()).build())
    }
}

/** The playing screen. The car draws it from the session; the bar across its top is ours, and leads to the queue. */
internal class AutoPlayerScreen(carContext: CarContext, private val session: AutoCarSession) : Screen(carContext) {
    override fun onGetTemplate(): Template = safely {
        MediaPlaybackTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(carContext.getString(R.string.auto_now_playing))
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.queue))
                            .setIcon(carContext.icon(R.drawable.queue_music))
                            .setOnClickListener { screenManager.push(AutoQueueScreen(carContext, session)) }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}

/** What plays next, from the song that is playing on: a tap goes to that song. */
internal class AutoQueueScreen(carContext: CarContext, private val session: AutoCarSession) : Screen(carContext) {
    override fun onGetTemplate(): Template = safely {
        val out = SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setStartHeaderAction(Action.BACK).setTitle(carContext.getString(R.string.queue)).build())
        val player = session.player
        val rows = RowSection.Builder()
        if (player == null || player.mediaItemCount == 0) {
            rows.setNoItemsMessage(carContext.getString(R.string.auto_nothing_here))
        } else {
            val from = player.currentMediaItemIndex.coerceAtLeast(0)
            for (at in from until minOf(player.mediaItemCount, from + AutoBrowse.MOST_RECENT)) {
                val song = player.getMediaItemAt(at)
                val row = Row.Builder()
                    .setTitle(song.name())
                    .setImage(carContext.picture(AutoArt.uri(carContext, AutoArt.SONG, song.mediaId), R.drawable.music_note).first, Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener {
                        player.seekToDefaultPosition(at)
                        player.play()
                        screenManager.pop()
                    }
                song.under()?.let(row::addText)
                if (at == from) row.addAction(Action.MEDIA_PLAYBACK)
                rows.addItem(row.build())
            }
        }
        out.addSection(rows.build()).build()
    }
}

/** The search: typed where the car lets it be typed, said to the car otherwise. A tap plays what was found. */
internal class AutoSearchScreen(carContext: CarContext, private val session: AutoCarSession) : Screen(carContext) {
    private var asked = ""
    private var found: List<MediaItem>? = emptyList()
    private var asking: Job? = null

    private fun ask(text: String) {
        val query = text.trim()
        if (query == asked) return
        asked = query
        asking?.cancel()
        if (query.length < 2) {
            found = emptyList()
            invalidate()
            return
        }
        asking = lifecycleScope.launch {
            delay(TYPED_MS)
            found = null
            invalidate()
            found = session.found(query)
            invalidate()
        }
    }

    override fun onGetTemplate(): Template = safely {
        val out = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = ask(searchText)
            override fun onSearchSubmitted(searchText: String) = ask(searchText)
        }).setHeaderAction(Action.BACK).setShowKeyboardByDefault(true).setSearchHint(carContext.getString(R.string.search)).setInitialSearchText(asked)
        val results = found ?: return@safely out.setLoading(true).build()
        val most = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val lines = ItemList.Builder()
        results.take(most).forEach { lines.addItem(row(session, it, playing = null)) }
        if (results.isEmpty()) lines.setNoItemsMessage(carContext.getString(if (asked.length >= 2) R.string.no_results_found else R.string.auto_search_hint))
        out.setItemList(lines.build()).build()
    }
}

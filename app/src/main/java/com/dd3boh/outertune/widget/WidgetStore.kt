/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.util.SizeF
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.scale
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.ui.theme.extractCoverColor
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.ui.utils.coverAddresses
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * The one file the widget reads and the app writes, and the artwork beside it.
 *
 * The words are written whether or not anybody has added a widget, so a widget dropped on a home
 * screen opens on the song that plays and the rows Home shows. Everything that costs something,
 * the artwork looked for and fetched and the widgets told to draw, is skipped while nobody has,
 * which is the common case: [hasWidgets] is one call into the launcher's own record. A music player
 * should not pay for a home screen it is not on. When a widget appears, [hydrate] draws it at once
 * from what is written and then fetches what the words were written without.
 */
object WidgetStore {
    private const val TAG = "WidgetStore"

    /** The lines that time a widget's first draw and its redraws, under one tag for logcat. */
    internal const val DRAW_TAG = "WidgetDraw"

    /** The now playing artwork beside a title. */
    private const val ART_NOW_PX = WidgetArt.NOW_PX

    /** The now playing artwork when it is drawn big: Cover's hero, Full cover's whole face. */
    private const val ART_BIG_PX = WidgetArt.BIG_PX

    /**
     * A list song's cover: a Shelf's covers, which are drawn up to about a hundred dp, and Up next's.
     * 96 px when a list was rows with a 40 dp thumbnail; a shelf's covers drawn from those were soft.
     */
    private const val ART_PICK_PX = WidgetArt.PICK_PX

    /**
     * Artwork is passed to the launcher as a bitmap inside a binder transaction with about a
     * megabyte to spend for everything on screen, so the files are small and there are few of
     * them. Anything older than the snapshot's own songs is deleted on each write.
     */
    private const val MAX_ART_FILES = 40

    private val mutex = Mutex()

    /** One download of now playing artwork at a time, and none of it under [mutex]. */
    private val artMutex = Mutex()

    /** The song that plays, as last written: the only one whose artwork is worth a download. */
    @Volatile
    private var wantedArt: String? = null

    /**
     * The song whose artwork was last tried in this process. Artwork that cannot be made (no
     * network, a local file with no embedded art, a thumbnail url that keeps failing) stays
     * missing, and trying it again on every play and pause would be a download that fails each
     * time. So missing artwork for the same song is tried once per process; a song change always
     * tries, and a new process tries again.
     */
    private var lastArtAttempt: String? = null

    /**
     * What the widget is drawing, held in memory as well as on disk.
     *
     * The file alone is not enough. A Glance widget's provideGlance runs once, when the launcher
     * opens a session, and everything after that is recomposition: a snapshot read before the
     * composition starts is the snapshot that widget keeps until its session ends, however many
     * times the app rewrites the file. So the composition follows this flow instead, and a write
     * that changes it redraws the widget at once.
     */
    private val _drawn = MutableStateFlow<Drawn?>(null)
    val drawn: StateFlow<Drawn?> = _drawn.asStateFlow()

    /**
     * A snapshot and the artwork it names, decoded once per change rather than once per draw.
     * [big] is the one large cover, for a widget whose whole face is the artwork; sending that one
     * everywhere would spend the launcher's whole megabyte on a picture nobody can see.
     * [nowCover] is the now playing song's own 192 px cover. It is kept apart from [art] because
     * [art] is keyed by id and the song usually also sits in Recently played (or another list),
     * whose 96 px copy is the one the map keeps. [firsts] are the big covers of the lists' first
     * songs that have one on the phone, by id: Big cover with a list alone draws that song big.
     */
    data class Drawn(
        val snapshot: WidgetSnapshot,
        val art: Map<String, Bitmap>,
        val big: Bitmap? = null,
        val nowCover: Bitmap? = null,
        val firsts: Map<String, Bitmap> = emptyMap(),
    ) {
        /**
         * Which bitmaps the widget's pictures are drawn from, by identity: the now playing covers, and
         * each list's covers in the order the widget shows them. Two of these with the same text draw
         * the same pictures, which is what [warm] asks before it draws them ahead (a picture is kept
         * by the very bitmaps it is drawn from). Up next's covers are shown as they are, not drawn into
         * anything, so they are left out.
         */
        val pictures: String by lazy {
            fun id(b: Bitmap?) = if (b == null) "-" else "${System.identityHashCode(b)}.${b.generationId}"
            buildString {
                append(id(nowCover)).append('/').append(id(big))
                for (list in listOf(snapshot.picks, snapshot.forgotten, snapshot.keepListening, snapshot.recent)) {
                    append('|')
                    for (song in list) append(song.id).append('=').append(id(art[song.id])).append(',')
                }
                for ((song, b) in firsts.toSortedMap()) append(';').append(song).append('*').append(id(b))
            }
        }
    }

    private fun dir(context: Context) = File(context.filesDir, "widget").apply { mkdirs() }
    private fun file(context: Context) = File(dir(context), "snapshot.tsv")
    private fun artDir(context: Context) = File(dir(context), "art").apply { mkdirs() }

    /** Whether anybody has this widget on a home screen. Nothing else here runs unless they have. */
    fun hasWidgets(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java))
            .isNotEmpty()
    }.getOrDefault(false)

    /**
     * Whether one of the widgets can be wide enough for Up next (a tablet's, a foldable's inside).
     * Up next's covers are only fetched for one: on a phone they are never drawn.
     */
    private fun wideWidgets(context: Context): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java)).any { id ->
            manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) >= WidgetLayout.PANES_WIDTH_DP
        }
    }.getOrDefault(false)

    /**
     * The player widgets on home screens now, by the launcher's own record, oldest first: Settings >
     * Widgets has a card for each. The recognition button has nothing to set, so it has none.
     */
    fun placed(context: Context): List<Int> = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java)).sorted()
    }.getOrDefault(emptyList())

    /**
     * One widget's settings read or written at a time. A read waits for a write asked for before it,
     * so a screen that reads a widget again never gets what it was before a change still landing.
     */
    private val settingsLock = Mutex()

    /** Where a change asked for by a screen is written, so it lands after the screen has gone. */
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * What widget [appWidgetId] is set to now, or the defaults when it has never been asked: its own
     * Glance state (WidgetKeys), which its setup sheet and Settings > Widgets both read.
     */
    suspend fun settingsOf(context: Context, appWidgetId: Int): WidgetSettings {
        return settingsLock.withLock {
            runCatching {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                WidgetKeys.read(getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId))
            }.getOrDefault(WidgetSettings())
        }
    }

    /**
     * [settings] for widget [appWidgetId], and that widget drawn again with them at once. How its
     * setup sheet saves and how Settings > Widgets does, so the two edit the same values.
     */
    suspend fun saveSettings(context: Context, appWidgetId: Int, settings: WidgetSettings) {
        settingsLock.withLock {
            runCatching {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                updateAppWidgetState(context, glanceId) { prefs -> WidgetKeys.write(prefs, settings) }
                // What the new settings draw (another look, another list) is drawn on workers first.
                warmFor(context, settings, AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId))
                MusicWidget().update(context, glanceId)
            }.onFailure { Log.w(TAG, "Could not save widget $appWidgetId's settings", it) }
        }
    }

    /**
     * [saveSettings] for a screen that can be left the moment a choice is made, as Settings >
     * Widgets can: in a scope of its own, one change after another in the order they were made
     * (each takes the lock before this returns, or queues for it), and then whatever the widget's
     * new list is missing fetched, as the setup sheet has it fetched after its Done.
     */
    fun changeSettings(context: Context, appWidgetId: Int, settings: WidgetSettings) {
        val app = context.applicationContext
        settingsScope.launch(start = CoroutineStart.UNDISPATCHED) {
            saveSettings(app, appWidgetId, settings)
            runCatching { hydrate(app) }.onFailure { Log.w(TAG, "Could not fill the widget after a change", it) }
        }
    }

    suspend fun read(context: Context): WidgetSnapshot = withContext(Dispatchers.IO) {
        runCatching { WidgetCodec.decode(file(context).takeIf { it.exists() }?.readText()) }
            .getOrDefault(WidgetSnapshot())
    }

    /** What to draw right now: the flow if this process has it, else the file. */
    suspend fun load(context: Context): Drawn = drawn.value ?: withContext(Dispatchers.IO) {
        decoded(context, read(context).withPlayer(MusicService.isRunning)).also { _drawn.value = it }
    }

    /** The artwork the snapshot names, as bitmaps. Small, few, and only re-read when they change. */
    private fun decoded(context: Context, snapshot: WidgetSnapshot): Drawn {
        // Every cover through the kept bitmaps: the pictures drawn ahead are kept by those very bitmaps,
        // so a cover decoded again for each write would never find them (see warm and prefetchNext).
        val (art, nowCover) = coversFor(snapshot, _drawn.value?.art.orEmpty(), ::decodeCover, ::decodeCover)
        val firsts = WidgetList.entries.mapNotNull { snapshot.list(it).firstOrNull() }.distinctBy { it.id }
            .mapNotNull { first -> decodeCover(bigArtPath(context, first.id))?.let { first.id to it } }.toMap()
        return Drawn(
            snapshot,
            art,
            snapshot.nowPlaying?.let { decodeCover(bigArtPath(context, it.id)) },
            nowCover,
            firsts,
        )
    }

    private fun decode(path: String?): Bitmap? = runCatching {
        path?.let { File(it) }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(path) }
    }.getOrNull()

    /**
     * The now playing covers as last decoded, by file. Every play and pause writes the snapshot, and
     * each write decoded the 320 px cover again: now it is decoded once per song (and again only if
     * its file is replaced), and Full cover's glass, which is drawn from that very bitmap, is drawn
     * once per song too (see WidgetGlass).
     */
    private val decodedCovers = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 32
    }

    private fun decodeCover(path: String?): Bitmap? {
        val file = path?.let { File(it) }?.takeIf { it.exists() } ?: return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        synchronized(decodedCovers) { decodedCovers[key]?.let { return it } }
        return decode(path)?.also { synchronized(decodedCovers) { decodedCovers[key] = it } }
    }

    /**
     * The file a song's artwork is kept in at [px]: the cover fetched at that size.
     *
     * The "c" is there because files without it exist on every phone that had a widget before: they
     * were made from the address the song is stored with, a 120 pixel thumbnail for most songs,
     * scaled up to the size in their name. Under the old name they would be taken for the real
     * thing and never replaced.
     */
    private fun artFile(context: Context, id: String, px: Int) = File(artDir(context), "${hash(id)}_${px}c.png")

    /**
     * Artwork made from the stored address because the cover at its size could not be had, which
     * means no connection. The name every artwork file had before, so the ones already on the phone
     * are found here and serve as exactly that.
     */
    private fun lesserArtFile(context: Context, id: String, px: Int) = File(artDir(context), "${hash(id)}_$px.png")

    private fun bigArtPath(context: Context, id: String) = artFile(context, id, ART_BIG_PX).absolutePath

    private suspend fun write(context: Context, snapshot: WidgetSnapshot) = withContext(Dispatchers.IO) {
        runCatching {
            // Written beside the real file and moved over it, because the launcher can read this
            // at any moment and half a snapshot is worse than a stale one.
            val tmp = File(dir(context), "snapshot.tmp")
            tmp.writeText(WidgetCodec.encode(snapshot))
            tmp.renameTo(file(context))
        }.onFailure { Log.w(TAG, "Could not write the widget snapshot", it) }
        // The flow is what a widget already on screen is watching; the file is for the next time
        // the process starts from nothing. The pictures of what is about to be drawn are drawn first,
        // on workers (see warm): Glance composes on the main thread, where drawing a crate or a row of
        // records for a new cover took the better part of a second.
        val drawn = decoded(context, snapshot)
        warm(context, drawn)
        _drawn.value = drawn
    }

    /** One widget's settings at one of the sizes the launcher gave it. */
    private data class Placed(val settings: WidgetSettings, val w: Int, val h: Int)

    /** What [warm] has drawn ahead already, so a write that changes nothing in the pictures draws nothing: newest last. */
    private val warmed: MutableSet<String> = Collections.synchronizedSet(Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 96
        },
    ))

    /**
     * How long the widget waits for its pictures to be drawn ahead. Past it the widget is drawn anyway,
     * and the composition takes a picture still being drawn from the worker drawing it (SingleFlight).
     */
    private const val WARM_LIMIT_MS = 1_500L

    /** Every size of every widget on a home screen, with that widget's settings. */
    private suspend fun placedSizes(context: Context): List<Placed> = runCatching {
        val manager = AppWidgetManager.getInstance(context)
        val glance = GlanceAppWidgetManager(context)
        glance.getGlanceIds(MusicWidget::class.java).flatMap { glanceId ->
            val settings = WidgetKeys.read(getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId))
            sizesOf(manager.getAppWidgetOptions(glance.getAppWidgetId(glanceId))).map { (w, h) -> Placed(settings, w, h) }
        }.distinct()
    }.onFailure { Log.w(TAG, "Could not read the sizes of the widgets", it) }.getOrDefault(emptyList())

    /**
     * The widget composed once, off the main thread, for each size of each widget on the home screen,
     * with [drawn] and the result thrown away. The point is what it leaves behind: every picture the
     * composition draws (a crate, a row of records, the blurred cover, the glass) is kept for these
     * very bitmaps, so the composition the launcher's session makes next, which Glance runs on the
     * main thread, finds them all done. The classes and code it runs are warm by then too.
     */
    private suspend fun warm(context: Context, drawn: Drawn, placed: List<Placed>? = null) {
        if (!hasWidgets(context)) return
        // Only a Shelf draws pictures worth drawing ahead (a crate, a row of records, the blurred cover,
        // up to 600 000 pixels a size). Cover is covers as they are; Full cover's glass is a few thousand
        // pixels, a few milliseconds: for those the wait would cost more than it saves.
        val wanted = (placed ?: placedSizes(context)).filter {
            it.settings.style == WidgetStyle.SHELF && warmed.add("${drawn.pictures}|${it.settings.hashCode()}|${it.w}x${it.h}")
        }
        if (wanted.isEmpty()) return
        val started = SystemClock.elapsedRealtime()
        val finished = withTimeoutOrNull(WARM_LIMIT_MS) {
            coroutineScope {
                wanted.map { w ->
                    async {
                        try {
                            composeAhead(context, w.settings, w.w, w.h, drawn)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.w(TAG, "Could not draw the widget ahead at ${w.w}x${w.h}", e)
                        }
                    }
                }.awaitAll()
            }
        }
        if (finished == null) wanted.forEach { warmed.remove("${drawn.pictures}|${it.settings.hashCode()}|${it.w}x${it.h}") }
        Log.d(DRAW_TAG, "Pictures drawn ahead for ${wanted.size} sizes in ${SystemClock.elapsedRealtime() - started} ms${if (finished == null) " (cut short)" else ""}")
    }

    /**
     * The sizes of widget [appWidgetId] as the launcher has just given them, drawn ahead for what is
     * on the widget now: asked for by the receiver the moment the launcher says a widget has a new
     * size, while Glance is still starting the session that will compose it, so the new layout's
     * pictures are done when the composition asks for them.
     */
    suspend fun warmResized(context: Context, appWidgetId: Int, options: Bundle) {
        if (!hasWidgets(context)) return
        warmFor(context, settingsOf(context, appWidgetId), options)
    }

    /** [warm] for a widget with [settings] at the sizes in [options], with what is drawn now. */
    private suspend fun warmFor(context: Context, settings: WidgetSettings, options: Bundle) {
        if (!hasWidgets(context)) return
        warm(context, load(context), sizesOf(options).map { (w, h) -> Placed(settings, w, h) })
    }

    /** The widgets being resized, by id: a resize that goes on only wants the size it has come to. */
    private val resizing = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    /** [warmResized] on a worker, in place of the one before it for the same widget that is still at it. */
    fun warmResizedSoon(context: Context, appWidgetId: Int, options: Bundle) {
        val app = context.applicationContext
        resizing.remove(appWidgetId)?.cancel()
        resizing[appWidgetId] = prepareScope.launch {
            try {
                warmResized(app, appWidgetId, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Could not draw the resized widget ahead", e)
            }
        }
    }

    /**
     * What is playing at the moment the snapshot is written. [liked] counts only for a song that was
     * not playing before (see likedFor): it is the first guess at its heart (see [likeGuess]), and
     * [followLikes] puts the stored like there once it is read. [shuffled] and [upNext] are the
     * queue's, or null when they cannot be read (the service going away): the last ones then stay.
     * [remainingMs] is what the song has left, for the wake (see [wakeBeforeChange]).
     */
    data class NowState(
        val song: MediaMetadata?,
        val isPlaying: Boolean,
        val liked: Boolean = false,
        val shuffled: Boolean? = null,
        val upNext: List<MediaMetadata>? = null,
        /** How long the song has left at the moment of the call, or null when the player cannot say: see [wakeDelayMs]. */
        val remainingMs: Long? = null,
    )

    /**
     * The song that is playing, and whether it is. Called on every song change and every play or
     * pause.
     *
     * Words first, pictures after. The title, the artist and the play button are written with
     * whatever artwork the phone already has and the widget is told to draw. Only then is the
     * artwork fetched, outside the lock (see [picturesFor]). It used to be fetched first, three
     * sizes one after the other, with the words waiting behind them and behind them the next
     * song's words: on a slow connection the widget showed the last song and the wrong button for
     * as long as that took.
     *
     * The state is read through [state] under the lock rather than passed in, because these calls
     * arrive from the player a few milliseconds apart and finish in whatever order the disk
     * allows. Asking at the moment of writing means the last write is the truth, instead of
     * whichever one happened to be slowest leaving a paused button over a playing song.
     */
    suspend fun setNowPlaying(context: Context, state: suspend () -> NowState) {
        val widgets = hasWidgets(context)
        var owed: Pair<MediaMetadata, List<Int>>? = null
        var owedNext = emptyList<MediaMetadata>()
        var coming: MediaMetadata? = null
        var remaining: Long? = null
        var playingNow = false
        var written = ""
        // What this write changes on the widget. A song change calls this 4 to 10 times within two
        // seconds (the player's timeline and position events, its play state), mostly with nothing
        // new, and every write redrew the widget and sent its pictures to the launcher again, about
        // 2.5 MB a size: the new title took 0.9 to 2 s to show (Pixel 5, 5 Nov 2026). A write that
        // changes nothing the widget shows is now skipped, and so is its redraw.
        var changed = emptyList<String>()
        mutex.withLock {
            val (song, isPlaying, liked, shuffled, queued, left) = state()
            remaining = left
            playingNow = isPlaying
            // The first call for a song is the moment the player changed: the time to the launcher starts here.
            if (widgets && song?.id != lastAsked) {
                lastAsked = song?.id
                Log.d(DRAW_TAG, "Song changed to ${song?.id}")
            }
            coming = queued?.firstOrNull()
            val old = read(context)
            wantedArt = song?.id
            // Up next is drawn only by a widget wide and tall enough for it (see drawsUpNext), at any of its
            // sizes: a change of the queue alone redraws nothing else. The sizes are read only when they
            // decide something, a change of Up next alone; read first, they held up every song change.
            val upNextShown: suspend () -> Boolean = { widgets && placedSizes(context).any { drawsUpNext(it.settings, it.w, it.h) } }
            written = "${song?.id}, ${if (isPlaying) "playing" else "paused"}"
            if (song == null) {
                val next = old.copy(
                    nowPlaying = null, isPlaying = false, isLiked = false, upNext = emptyList(),
                    isShuffled = shuffled ?: old.isShuffled,
                )
                changed = old.changesShownTo(next, upNextShown)
                if (changed.isNotEmpty()) write(context, next.copy(updatedAt = System.currentTimeMillis()))
            } else {
                val same = old.nowPlaying?.id == song.id
                // The picture is only for a widget. The words are written either way, so a widget
                // added mid-song opens on the right song.
                val art = (if (widgets) artOnPhone(context, song.id, ART_NOW_PX) else null)
                    ?: old.nowPlaying?.artPath.takeIf { same }
                val now = WidgetSong(
                    id = song.id,
                    title = song.title,
                    artist = song.artists.joinToString(", ") { it.name },
                    artPath = art,
                    thumbnailUrl = song.thumbnailUrl,
                    durationSec = song.duration,
                    isLocal = song.isLocal,
                    // Kept while the song is the same, and worked out if it never was: a song
                    // written before any widget existed has no picture and so no colour, and a
                    // widget told to take its colour from the cover then showed plain dark until
                    // the next song.
                    colour = (if (same) old.nowPlaying?.colour else null) ?: artColour(art),
                )
                // Recently played is kept here rather than queried: the song that just started is
                // the newest there is, and the widget should not have to ask the database to know it.
                // Pause listen history says the app keeps no record of what plays; the widget's own
                // list is a record too, so it stops as well while that is on, and so does making a
                // picture for a row nextRecent would only throw away unused.
                val paused = context.dataStore.get(PauseListenHistoryKey, false)
                val row = (if (widgets && !paused) artOnPhone(context, song.id, ART_PICK_PX) else null) ?: now.artPath
                val recent = nextRecent(old.recent, now.copy(artPath = row), paused = paused, maxPicks = WidgetLayout.MAX_PICKS)
                // Up next with the covers the phone has: a list song's, or one already fetched.
                val known = (old.picks + old.forgotten + old.keepListening + old.recent + old.upNext).associateBy { it.id }
                val upNext = queued?.take(WidgetLayout.MAX_UP_NEXT)?.map { next ->
                    WidgetSong(
                        id = next.id,
                        title = next.title,
                        artist = next.artists.joinToString(", ") { it.name },
                        artPath = known[next.id]?.artPath ?: if (widgets) artOnPhone(context, next.id, ART_PICK_PX) else null,
                        thumbnailUrl = next.thumbnailUrl,
                        durationSec = next.duration,
                        isLocal = next.isLocal,
                    )
                } ?: old.upNext
                val next = old.copy(
                    nowPlaying = now,
                    isPlaying = isPlaying,
                    isLiked = likedFor(old, song.id, liked),
                    recent = recent,
                    isShuffled = shuffled ?: old.isShuffled,
                    upNext = upNext,
                )
                changed = old.changesShownTo(next, upNextShown)
                if (changed.isNotEmpty()) write(context, next.copy(updatedAt = System.currentTimeMillis()))
                // A missing picture is still fetched when nothing else changed.
                if (widgets) {
                    // Three sizes: a widget given over to the artwork wants a real cover, one with
                    // a title beside it a smaller one, and a list row a thumbnail.
                    val sizes = listOfNotNull(ART_BIG_PX, ART_NOW_PX, ART_PICK_PX.takeIf { !paused })
                    val missing = sizes.any { !artFile(context, song.id, it).exists() }
                    if (shouldFetchArt(same, missing, song.id, lastArtAttempt)) {
                        lastArtAttempt = song.id
                        owed = song to sizes
                    }
                    // Up next's covers, on a widget wide enough to show them.
                    val bare = upNext.filter { it.artPath == null }.mapTo(HashSet()) { it.id }
                    if (bare.isNotEmpty() && wideWidgets(context)) owedNext = queued.orEmpty().filter { it.id in bare }
                }
            }
        }
        if (widgets) {
            if (changed.isEmpty()) {
                Log.d(DRAW_TAG, "Unchanged $written, not drawn again")
            } else {
                Log.d(DRAW_TAG, "Written $written (${changed.joinToString()})")
                MusicWidget().updateAll(context)
                // How long the launcher's session took to be asked for: the next line is its own, "Drawing".
                Log.d(DRAW_TAG, "Widgets told about $written")
            }
        }
        // The old covers are cleared once the widgets have been told, not before: it read every file in
        // the folder and every song of the snapshot while the new song waited to be drawn.
        if (changed.isNotEmpty()) mutex.withLock { prune(context, read(context)) }
        owed?.let { (song, sizes) ->
            if (picturesFor(context, song, sizes)) MusicWidget().updateAll(context)
        }
        if (owedNext.isNotEmpty() && nextPicturesFor(context, owedNext)) MusicWidget().updateAll(context)
        // And the next song's, while this one plays: its covers and its pictures for the widget.
        if (widgets) coming?.let { prepareNext(context, it) }
        if (widgets) wakeBeforeChange(context, remaining, playingNow)
    }

    /** The wake that is waiting for the end of the song, one at a time: the latest call to [setNowPlaying] has the time left. */
    private var waking: Job? = null

    /**
     * Glance ends a widget's session 45 seconds after it began, and the draw after that starts a new
     * one through WorkManager before anything is composed: on the Pixel 5 a new song's title was
     * asked for 0.33 s after it was written and first composed 0.2 s after that, a half second of
     * starting over at the very moment the song changes, which is nearly always after the last draw
     * by minutes. So the session is started a little before the song ends, by a draw that changes
     * nothing, and the change itself finds it running (see [wakeDelayMs]).
     */
    private fun wakeBeforeChange(context: Context, remainingMs: Long?, playing: Boolean) {
        val app = context.applicationContext
        val wait = wakeDelayMs(remainingMs, playing)
        synchronized(this) {
            waking?.cancel()
            waking = if (wait == null) null else prepareScope.launch {
                delay(wait)
                if (hasWidgets(app)) {
                    Log.d(DRAW_TAG, "Waking the widgets for the change of song")
                    MusicWidget().updateAll(app)
                }
            }
        }
    }

    /** The song the last call to [setNowPlaying] was about, for the one line a song change writes. */
    @Volatile
    private var lastAsked: String? = null

    private val prepareScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The song being got ready, and its job: one song at a time, the latest asked for. */
    private var preparing: String? = null
    private var preparingJob: Job? = null

    /**
     * The next song of the queue made ready while this one plays, in the background and behind
     * everything the song that plays is owed. When it starts the widget has to show its title, its
     * cover and everything drawn from its cover, and each of those used to be fetched or drawn after
     * the change: the title came first and the cover a moment later, in a second redraw, and the
     * drawing took the main thread's turn. Now its covers are on the phone before it starts, and the
     * widget has been composed once as it will be, so the pictures are kept (see [warm]).
     */
    private fun prepareNext(context: Context, next: MediaMetadata) {
        val app = context.applicationContext
        synchronized(this) {
            if (preparing == next.id) return
            preparing = next.id
            preparingJob?.cancel()
            preparingJob = prepareScope.launch {
                try {
                    // After this song's own draw, not beside it: started at the change, fetching and
                    // decoding the next song's covers held the phone while the new song was drawn, and a
                    // skip reached a Full cover widget later than before (Pixel 5). A song ending by itself
                    // was prepared for long before, so the wait costs it nothing.
                    delay(PREPARE_AFTER_MS)
                    getReady(app, next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Could not get the widget ready for the next song", e)
                }
            }
        }
    }

    /** How long after a song change the next song is got ready for the widget: after the change has been drawn. */
    internal const val PREPARE_AFTER_MS = 5_000L

    private suspend fun getReady(context: Context, next: MediaMetadata) {
        val started = SystemClock.elapsedRealtime()
        val paused = context.dataStore.get(PauseListenHistoryKey, false)
        val sizes = listOfNotNull(ART_BIG_PX, ART_NOW_PX, ART_PICK_PX.takeIf { !paused })
        artMutex.withLock { artInSizes(context, next.id, sizes) { px -> artModel(next, px) } }
        val held = drawn.value?.snapshot ?: return
        // It has started already: the write for it is the real one.
        if (held.nowPlaying?.id == next.id) return
        val art = artOnPhone(context, next.id, ART_NOW_PX) ?: return
        val now = WidgetSong(
            id = next.id,
            title = next.title,
            artist = next.artists.joinToString(", ") { it.name },
            artPath = art,
            thumbnailUrl = next.thumbnailUrl,
            durationSec = next.duration,
            isLocal = next.isLocal,
            colour = artColour(art),
        )
        val row = (if (paused) null else artOnPhone(context, next.id, ART_PICK_PX)) ?: art
        // As setNowPlaying will write it when the song starts, up to the queue after it.
        val ahead = held.copy(
            nowPlaying = now,
            isPlaying = true,
            recent = nextRecent(held.recent, now.copy(artPath = row), paused = paused, maxPicks = WidgetLayout.MAX_PICKS),
            upNext = held.upNext.filter { it.id != next.id },
        )
        warm(context, decoded(context, ahead))
        Log.d(DRAW_TAG, "Next song ${next.id} made ready in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /**
     * The artwork of the song that plays, once its words are on the widget: fetched outside the
     * lock, so neither a pause nor the next song's title waits behind a download, and then written
     * into the snapshot. True when the widget has something new to draw.
     */
    private suspend fun picturesFor(context: Context, song: MediaMetadata, sizes: List<Int>): Boolean {
        val made = artMutex.withLock {
            // Skipped past while another song's artwork was downloading: nobody waits for this one.
            if (wantedArt != song.id) return false
            artInSizes(context, song.id, sizes) { px -> artModel(song, px) }
        }
        // Made by this fetch, or by the next song's being got ready (see prepareNext) when this song was
        // that one, which a quick skip makes of it: either way the snapshot is told where they are. With
        // nothing new on the phone, or the snapshot pointing at it already, there is nothing to tell.
        if (!made) {
            val have = artOnPhone(context, song.id, ART_NOW_PX)
            if (have == null || have == read(context).nowPlaying?.takeIf { it.id == song.id }?.artPath) return false
        }
        mutex.withLock {
            val snapshot = read(context)
            // The song may have changed since. Its row in Recently played is still its own.
            val now = snapshot.nowPlaying?.takeIf { it.id == song.id }?.let {
                val art = artOnPhone(context, song.id, ART_NOW_PX) ?: it.artPath
                it.copy(artPath = art, colour = it.colour ?: artColour(art))
            }
            val row = artOnPhone(context, song.id, ART_PICK_PX)
            write(
                context,
                snapshot.copy(
                    nowPlaying = now ?: snapshot.nowPlaying,
                    recent = snapshot.recent.map { if (it.id == song.id && row != null) it.copy(artPath = row) else it },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return true
    }

    /**
     * The covers of Up next's songs written without one, fetched outside the lock like a row's, and
     * written into Up next for the songs still in it. True when the widget has something new to draw.
     */
    private suspend fun nextPicturesFor(context: Context, songs: List<MediaMetadata>): Boolean {
        val made = HashMap<String, String>()
        for (song in songs) {
            artMutex.withLock {
                artFor(context, song.id, artModel(song, ART_PICK_PX), ART_PICK_PX)?.let { made[song.id] = it }
            }
        }
        if (made.isEmpty()) return false
        mutex.withLock {
            val snapshot = read(context)
            val next = snapshot.upNext.map { if (it.artPath == null) it.copy(artPath = made[it.id]) else it }
            write(context, snapshot.copy(upNext = next, updatedAt = System.currentTimeMillis()))
        }
        return true
    }

    /**
     * The heart of the song the widget shows, as stored: [liked] for [songId], drawn at once. Nothing
     * is written when the widget shows another song by now, or already shows that like.
     */
    suspend fun setLiked(context: Context, songId: String, liked: Boolean) {
        val changed = mutex.withLock {
            val next = read(context).withLike(songId, liked) ?: return@withLock false
            write(context, next.copy(updatedAt = System.currentTimeMillis()))
            true
        }
        if (changed && hasWidgets(context)) MusicWidget().updateAll(context)
    }

    /**
     * Keeps the widget's heart on the like as stored, wherever it was changed: the player's heart,
     * the notification's, a menu, the widget's own, or YouTube's list of liked songs. For as long as
     * [scope] lives, which is the player's service, and for the song the widget shows rather than
     * the one the player holds: after a cold start the widget still shows the last song while the
     * player holds none, and a like given it from the player's screen has to fill this heart too.
     * [likedOf] is the stored like of a song, null when the song is not stored.
     */
    fun followLikes(context: Context, scope: CoroutineScope, likedOf: (String) -> Flow<Boolean?>) {
        scope.launch(Dispatchers.IO) {
            // What the widget shows, read from the file when this process has not written it yet.
            if (drawn.value == null && hasWidgets(context)) runCatching { load(context) }
            likesToDraw(drawn.map { it?.snapshot?.nowPlaying?.id }, likedOf).collect { (id, liked) ->
                runCatching { setLiked(context, id, liked) }.onFailure { Log.w(TAG, "Could not draw the widget's heart", it) }
            }
        }
    }

    /**
     * One of Home's rows as Home is showing it. The widget and the app then hold the same songs.
     *
     * Words first here too: a row is written with the artwork the phone already has, and what is
     * missing is fetched outside the lock (see [rowPicturesFor]). Fetched under it, a row of new
     * songs on a slow connection kept the next song's title and the play button waiting.
     */
    suspend fun setList(context: Context, which: WidgetList, songs: List<MediaMetadata>) {
        val widgets = hasWidgets(context)
        var changed = false
        var owed = emptyList<MediaMetadata>()
        mutex.withLock {
            val old = read(context)
            val wanted = songs.take(WidgetLayout.MAX_PICKS)
            if (wanted.map { it.id } == old.list(which).map { it.id }) return@withLock
            val byId = old.songs().associateBy { it.id }
            val list = wanted.map { song ->
                WidgetSong(
                    id = song.id,
                    title = song.title,
                    artist = song.artists.joinToString(", ") { it.name },
                    artPath = byId[song.id]?.artPath ?: if (widgets) artOnPhone(context, song.id, ART_PICK_PX) else null,
                    thumbnailUrl = song.thumbnailUrl,
                    durationSec = song.duration,
                    isLocal = song.isLocal,
                )
            }
            write(context, old.withList(which, list).copy(updatedAt = System.currentTimeMillis()))
            prune(context, read(context))
            changed = true
            if (widgets) {
                val bare = list.filter { it.artPath == null }.mapTo(HashSet()) { it.id }
                owed = wanted.filter { it.id in bare }
            }
        }
        if (widgets && changed) MusicWidget().updateAll(context)
        if (owed.isNotEmpty() && rowPicturesFor(context, which, owed)) MusicWidget().updateAll(context)
        if (widgets && changed && firstPictureFor(context, which)) MusicWidget().updateAll(context)
    }

    /**
     * The artwork of rows written without any, fetched outside the lock and then written into the
     * list, for the rows that are still in it. True when the widget has something new to draw.
     */
    private suspend fun rowPicturesFor(context: Context, which: WidgetList, songs: List<MediaMetadata>): Boolean {
        val made = HashMap<String, String>()
        for (song in songs) {
            // One at a time with the now playing artwork, which takes its turn between two rows.
            artMutex.withLock {
                artFor(context, song.id, artModel(song, ART_PICK_PX), ART_PICK_PX)?.let { made[song.id] = it }
            }
        }
        if (made.isEmpty()) return false
        mutex.withLock {
            val snapshot = read(context)
            val rows = snapshot.list(which).map { if (it.artPath == null) it.copy(artPath = made[it.id]) else it }
            write(context, snapshot.withList(which, rows).copy(updatedAt = System.currentTimeMillis()))
        }
        return true
    }

    /**
     * A widget has appeared, or its settings were saved: it is drawn at once from what is written,
     * and then given what the snapshot is missing because there was no widget to want it.
     *
     * First the words as they are, with the artwork the phone already has. The song that plays and
     * Home's rows are written whether or not a widget exists (see [setNowPlaying] and [setList]), so
     * a new widget is written and drawn the moment it appears, before anything is read from the
     * library or fetched. The library used to be read first, under the lock: History alone is every
     * play there has been, and a big library kept the new widget, and every play and pause meanwhile,
     * waiting for it.
     *
     * Then what the library adds: Quick picks and Home's other rows when Home never wrote them, and
     * the rest of a Recently played that is still short (see [libraryFills]). Read with no lock held
     * and merged into the snapshot as it is by then, so a row Home wrote meanwhile is kept.
     *
     * Then pictures in the order they are looked for, each drawn the moment it is in: the cover of
     * what is playing, then the rows of the lists the widgets show, then the other lists. A new
     * widget's cover used to wait for every cover of every list, one download after another, which
     * on a phone that had never had a widget was a few dozen of them.
     */
    suspend fun hydrate(context: Context) {
        if (!hasWidgets(context)) return
        val started = System.currentTimeMillis()
        mutex.withLock {
            var snapshot = read(context).withPlayer(MusicService.isRunning)
            snapshot.nowPlaying?.let { now ->
                if (now.artPath == null || now.colour == null) {
                    val art = now.artPath ?: artOnPhone(context, now.id, ART_NOW_PX)
                    snapshot = snapshot.copy(nowPlaying = now.copy(artPath = art, colour = now.colour ?: artColour(art)))
                }
                // The heart as stored: the file can be from before the heart existed, which is every
                // file after the update that brings it, and nothing follows the like while no service
                // is alive (see followLikes). A tap on a heart drawn empty over a liked song would
                // take the like away.
                val stored = runCatching {
                    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                        .database().readOrNull { songRow(now.id) }?.liked
                }.getOrNull()
                if (stored != null) snapshot = snapshot.copy(isLiked = stored)
            }
            // Every list, not only the one this widget shows: a second widget, or the same one
            // set to another list, then has something to draw the moment it is asked.
            for (which in WidgetList.entries) snapshot = snapshot.withList(which, withArtOnPhone(context, snapshot.list(which)))
            write(context, snapshot)
            prune(context, read(context))
        }
        MusicWidget().updateAll(context)
        Log.i(DRAW_TAG, "Widgets written and drawn in ${System.currentTimeMillis() - started} ms")

        // What the library adds, read with no lock held: a play or a pause never waits for it.
        val wanted = read(context).let { held -> WidgetList.entries.filter { libraryFills(it, held.list(it)) } }
        val fetched = wanted.associateWith { fromLibrary(context, it) }.filterValues { it.isNotEmpty() }
        if (fetched.isNotEmpty()) {
            val grew = mutex.withLock {
                var snapshot = read(context)
                var changed = false
                for ((which, more) in fetched) {
                    val held = snapshot.list(which)
                    if (!libraryFills(which, held)) continue
                    val filled = filledUp(held, more, WidgetLayout.MAX_PICKS)
                    if (filled.map { it.id } == held.map { it.id }) continue
                    snapshot = snapshot.withList(which, withArtOnPhone(context, filled))
                    changed = true
                }
                if (changed) {
                    write(context, snapshot)
                    prune(context, read(context))
                }
                changed
            }
            if (grew) MusicWidget().updateAll(context)
            Log.i(DRAW_TAG, "Widgets: ${fetched.keys} filled from the library by ${System.currentTimeMillis() - started} ms")
        }

        val written = read(context)
        val bare = WidgetList.entries.associateWith { which -> written.list(which).filter { it.artPath == null } }
        // Up next's too, on a widget wide enough to show them.
        val bareNext = (if (wideWidgets(context)) written.upNext else emptyList()).filter { it.artPath == null }

        val now = written.nowPlaying
        if (now != null && artMutex.withLock {
                artInSizes(context, now.id, listOf(ART_BIG_PX, ART_NOW_PX)) { px -> artModel(now, px) }
            }
        ) {
            mutex.withLock {
                val snapshot = read(context)
                // Only if it is still the song that plays.
                snapshot.nowPlaying?.takeIf { it.id == now.id }?.let { song ->
                    val art = artOnPhone(context, song.id, ART_NOW_PX) ?: song.artPath
                    write(context, snapshot.copy(nowPlaying = song.copy(artPath = art, colour = song.colour ?: artColour(art))))
                }
            }
            MusicWidget().updateAll(context)
        }

        val made = HashMap<String, String>()
        val batches = rowOrder(shownLists(context)).map { bare[it].orEmpty() } + listOf(bareNext)
        for (batch in batches) {
            var more = false
            for (song in batch.filter { it.id !in made }) {
                artMutex.withLock {
                    artFor(context, song.id, artModel(song, ART_PICK_PX), ART_PICK_PX)?.let {
                        made[song.id] = it
                        more = true
                    }
                }
            }
            if (!more) continue
            mutex.withLock {
                // A row is its own wherever it still is, in this list or another.
                var snapshot = read(context)
                for (which in WidgetList.entries) {
                    snapshot = snapshot.withList(which, snapshot.list(which).map { if (it.artPath == null) it.copy(artPath = made[it.id]) else it })
                }
                snapshot = snapshot.copy(upNext = snapshot.upNext.map { if (it.artPath == null) it.copy(artPath = made[it.id]) else it })
                write(context, snapshot)
            }
            MusicWidget().updateAll(context)
        }
        // Last, the big cover of a list's first song, for a widget that draws it big.
        for (which in WidgetList.entries) if (firstPictureFor(context, which)) MusicWidget().updateAll(context)
    }

    /** The list each widget on the home screen is set to, in no particular order, without repeats. */
    private suspend fun shownLists(context: Context): List<WidgetList> = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(MusicWidget::class.java).map { id ->
            WidgetKeys.read(getAppWidgetState(context, PreferencesGlanceStateDefinition, id)).list
        }.distinct()
    }.onFailure { Log.w(TAG, "Could not read which lists the widgets show", it) }.getOrDefault(emptyList())

    /** The lists a widget draws as a Big cover of the list alone, its first song big. */
    private suspend fun bigFirstLists(context: Context): Set<WidgetList> = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(MusicWidget::class.java).mapNotNull { id ->
            WidgetKeys.read(getAppWidgetState(context, PreferencesGlanceStateDefinition, id))
                .takeIf { it.style == WidgetStyle.SHELF && it.look == ShelfLook.BIG_COVER && it.contentShown == WidgetContent.LIST }?.list
        }.toSet()
    }.onFailure { Log.w(TAG, "Could not read which widgets draw a first song big", it) }.getOrDefault(emptySet())

    /**
     * The big cover of [which]'s first song, where a widget draws it big (Big cover with a list
     * alone), as the song that plays has one: its row's cover is a 128 pixel thumbnail, a blur at
     * that size. Fetched outside the lock like a row's, once (a file there already is kept). True
     * when the widget has something new to draw.
     */
    private suspend fun firstPictureFor(context: Context, which: WidgetList): Boolean {
        if (which !in bigFirstLists(context)) return false
        val first = read(context).list(which).firstOrNull() ?: return false
        val made = artMutex.withLock { artInSizes(context, first.id, listOf(ART_BIG_PX)) { px -> artModel(first, px) } }
        if (!made) return false
        mutex.withLock { _drawn.value = decoded(context, read(context)) }
        return true
    }

    /**
     * A row straight from the library, for a widget added before Home has ever filled that row.
     * The same queries Home uses, so the widget is never emptier than the app. With the artwork
     * the phone has and no more: [hydrate] fetches the rest once the rows are drawn.
     */
    private suspend fun fromLibrary(context: Context, which: WidgetList): List<WidgetSong> = runCatching {
        val database = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).database()
        val rows = when (which) {
            WidgetList.QUICK_PICKS -> database.quickPicks().first()
            WidgetList.FORGOTTEN_FAVOURITES -> database.forgottenFavorites().first()
            WidgetList.KEEP_LISTENING -> database.mostPlayedSongs(
                System.currentTimeMillis() - 14L * 86_400_000L, limit = WidgetLayout.MAX_PICKS,
            ).first()
            // What History shows, newest first, one row per song however often it has been played.
            WidgetList.RECENT -> database.historyPlays().first()
                .map { it.song }.distinctBy { it.id }.take(WidgetLayout.MAX_PICKS)
        }
        rows.take(WidgetLayout.MAX_PICKS).map { song ->
            val meta = song.toMediaMetadata()
            WidgetSong(
                id = meta.id,
                title = meta.title,
                artist = meta.artists.joinToString(", ") { it.name },
                artPath = artOnPhone(context, meta.id, ART_PICK_PX),
                thumbnailUrl = meta.thumbnailUrl,
                durationSec = meta.duration,
                isLocal = meta.isLocal,
            )
        }
    }.onFailure { Log.w(TAG, "Could not read $which for the widget", it) }.getOrDefault(emptyList())

    /**
     * Colours worked out, by cover file as it is on the phone. The next song's is worked out while this
     * one plays (getReady), and its change finds it here: reading the cover's pixels again was the
     * biggest part of the time between a song change and the widget hearing of it (Pixel 5 trace).
     */
    private val colours = LruCache<String, Int>(24)

    /** The colour of a cover: the one the player would take, or what a cover of greys has most of. */
    private fun artColour(path: String?): Int? = runCatching {
        if (path == null) return@runCatching null
        val file = File(path)
        val key = "$path:${file.lastModified()}:${file.length()}"
        colours.get(key) ?: decode(path)?.extractCoverColor()?.toArgb()?.also { colours.put(key, it) }
    }.getOrNull()

    /**
     * The artwork file a song already has at [px], if any: the cover at that size before one made
     * from the stored address (see lesserArtFile).
     */
    private fun artOnPhone(context: Context, id: String, px: Int): String? =
        listOf(artFile(context, id, px), lesserArtFile(context, id, px)).firstOrNull { it.exists() }?.absolutePath

    /** A list's rows with the artwork the phone already has for those written without any. */
    private fun withArtOnPhone(context: Context, songs: List<WidgetSong>): List<WidgetSong> =
        songs.map { song -> if (song.artPath != null) song else song.copy(artPath = artOnPhone(context, song.id, ART_PICK_PX)) }

    /** What to ask Coil for a song's artwork at [px], best first (see coverAddresses). */
    private fun artModel(song: MediaMetadata, px: Int = ART_NOW_PX): List<Any> = when {
        song.isLocal -> listOfNotNull(song.localPath?.let { LocalArtworkPath(it, px, px) })
        else -> song.thumbnailUrl?.let { coverAddresses(it, px) }.orEmpty()
    }

    /** The same, for a song read back from the snapshot, where a local file is all that was kept. */
    private fun artModel(song: WidgetSong, px: Int = ART_NOW_PX): List<Any> = when {
        song.isLocal -> listOfNotNull(song.thumbnailUrl?.let { LocalArtworkPath(it, px, px) })
        else -> song.thumbnailUrl?.let { coverAddresses(it, px) }.orEmpty()
    }

    /**
     * The song's artwork as a small square PNG on disk, or null if it could not be had. Cached by
     * song and size, so a song that comes round again costs nothing.
     *
     * [models] are tried in order. Only the first gives the cover at its size; what a later one
     * gives is kept under another name (see lesserArtFile) and handed out for now, so the real
     * cover is still fetched the next time the song comes round with a connection.
     */
    private suspend fun artFor(context: Context, id: String, models: List<Any>, px: Int): String? {
        val sharp = artFile(context, id, px)
        if (sharp.exists()) return sharp.absolutePath
        return runCatching {
            withContext(Dispatchers.IO) {
                for ((nth, model) in models.withIndex()) {
                    val out = if (nth == 0) sharp else lesserArtFile(context, id, px)
                    if (out.exists()) return@withContext out.absolutePath
                    val result = context.imageLoader.execute(
                        ImageRequest.Builder(context).data(model).allowHardware(false).size(px, px).build()
                    )
                    val bitmap = result.image?.toBitmap() ?: continue
                    // Cropped to the middle before it is scaled: a lot of YouTube's artwork is wide,
                    // and squashing a wide picture into a square is the one thing a cover must not do.
                    val side = minOf(bitmap.width, bitmap.height)
                    val cropped = if (bitmap.width == bitmap.height) bitmap
                    else Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                    val square = if (cropped.width > px) cropped.scale(px, px) else cropped
                    return@withContext if (save(square, out)) out.absolutePath else null
                }
                null
            }
        }.onFailure { Log.w(TAG, "Could not cache the widget artwork", it) }.getOrNull()
    }

    /**
     * A song's artwork in each of [sizes] from one download, where each size used to be a download
     * of its own: the cover is asked for at the largest size that is missing and scaled down for
     * the others. Files that are there are left alone. True when one was written.
     *
     * As in [artFor], only the first model gives the cover at its size, and what a later one gives
     * is kept under the other name.
     */
    private suspend fun artInSizes(context: Context, id: String, sizes: List<Int>, models: (Int) -> List<Any>): Boolean {
        val wanted = sizes.filter { !artFile(context, id, it).exists() }.sortedDescending()
        val largest = wanted.firstOrNull() ?: return false
        return runCatching {
            withContext(Dispatchers.IO) {
                for ((nth, model) in models(largest).withIndex()) {
                    val file = { px: Int -> if (nth == 0) artFile(context, id, px) else lesserArtFile(context, id, px) }
                    val missing = wanted.filter { !file(it).exists() }
                    if (missing.isEmpty()) return@withContext false
                    val result = context.imageLoader.execute(
                        ImageRequest.Builder(context).data(model).allowHardware(false).size(largest, largest).build()
                    )
                    val bitmap = result.image?.toBitmap() ?: continue
                    // Cropped to the middle before it is scaled, for the reason given in artFor.
                    val side = minOf(bitmap.width, bitmap.height)
                    var square = if (bitmap.width == bitmap.height) bitmap
                    else Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                    // Largest first, each made from the one before: no step is more than a halving.
                    var saved = false
                    for (px in missing) {
                        if (square.width > px) square = square.scale(px, px)
                        saved = save(square, file(px)) || saved
                    }
                    return@withContext saved
                }
                false
            }
        }.onFailure { Log.w(TAG, "Could not cache the widget artwork", it) }.getOrDefault(false)
    }

    /**
     * Written beside the file and moved over it. Artwork is fetched outside the lock now, so a
     * snapshot may be written, and this file looked for and read, while it is being made: it has to
     * be there whole or not at all, and two fetches of one cover must not end up as a mix of both.
     */
    private fun save(artwork: Bitmap, out: File): Boolean {
        val tmp = File.createTempFile("art", ".tmp", out.parentFile)
        tmp.outputStream().use { artwork.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return tmp.renameTo(out).also { moved -> if (!moved) tmp.delete() }
    }

    /**
     * Artwork for songs the widget is no longer showing, oldest first.
     *
     * Never a file the snapshot still points at. Age alone threw away the art of list rows that had
     * not changed in a while, since a cached file is not touched when it is reused: it went on
     * showing from memory, and after the process died those rows had only the placeholder, with
     * nothing to fetch it again because the path was still recorded.
     */
    private fun prune(context: Context, snapshot: WidgetSnapshot) = runCatching {
        val files = artDir(context).listFiles().orEmpty()
        if (files.size <= MAX_ART_FILES) return@runCatching
        val inUse = snapshot.artPathsInUse { id -> bigArtPath(context, id) }
        files.filter { it.absolutePath !in inUse }
            .sortedBy { it.lastModified() }
            .dropLast(MAX_ART_FILES)
            .forEach { it.delete() }
    }.getOrDefault(Unit)

    /** Songs' file names, kept: every cover looked up works one out, several times a song change. */
    private val hashes = LruCache<String, String>(128)

    private fun hash(id: String): String = hashes.get(id)
        ?: MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            .also { hashes.put(id, it) }
}

/**
 * Whether a widget set as [settings] draws Up next at [w] by [h] dp: Cover and Full cover do, from a
 * tablet's width and height up and when the queue has a song to show (WidgetLayout.plan's panes). A
 * Shelf never does. This may say yes where the plan then draws something else, never no where it
 * draws Up next: WidgetUpNextTest walks the sizes to prove it.
 */
internal fun drawsUpNext(settings: WidgetSettings, w: Int, h: Int): Boolean =
    settings.style != WidgetStyle.SHELF && w >= WidgetLayout.PANES_WIDTH_DP && h >= WidgetLayout.PANES_HEIGHT_DP

/** How long before the end of a song the widget's session is started, so it is running when the song changes. */
internal const val WAKE_BEFORE_END_MS = 12_000L

/** A song with less left than this changes before a wake could be of use: the change starts its own session. */
internal const val WAKE_TOO_LATE_MS = 2_000L

/**
 * How long to wait before waking the widget's session for the song's change, or null for no wake: a
 * song that is not playing does not end, one that cannot say how long it has left has no time to wake
 * for, and one that ends within [WAKE_TOO_LATE_MS] ends before a wake could do anything. With less left
 * than [WAKE_BEFORE_END_MS] the wake is at once.
 */
internal fun wakeDelayMs(remainingMs: Long?, playing: Boolean): Long? =
    if (!playing || remainingMs == null || remainingMs < WAKE_TOO_LATE_MS) null
    else (remainingMs - WAKE_BEFORE_END_MS).coerceAtLeast(0L)

/**
 * The sizes in dp a launcher gave a widget, as Glance reads them for a widget of exact sizes: the
 * list of sizes on Android 12 and up, and otherwise the two it estimates, the narrowest and tallest
 * (upright) and the widest and shortest (on its side). Whole dp, as the composition reads them.
 */
internal fun sizesFrom(listed: List<Pair<Float, Float>>?, minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int): List<Pair<Int, Int>> {
    if (!listed.isNullOrEmpty()) return listed.map { (w, h) -> w.toInt() to h.toInt() }.distinct()
    return listOf(minWidth to maxHeight, maxWidth to minHeight).filter { (w, h) -> w > 0 && h > 0 }.distinct()
}

@Suppress("DEPRECATION")
internal fun sizesOf(options: Bundle): List<Pair<Int, Int>> = sizesFrom(
    listed = if (Build.VERSION.SDK_INT >= 31) {
        options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.map { it.width to it.height }
    } else null,
    minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
    minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
    maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
    maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
)

/** How a broadcast receiver, which Hilt does not inject into here, reaches the one database. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun database(): MusicDatabase

    /** For the heart pressed with no service alive, which downloads a liked song as the service would. */
    fun downloadUtil(): DownloadUtil
}

/** Every list once, the ones the widgets show first: the order a new widget's rows get their covers in. */
internal fun rowOrder(shown: List<WidgetList>): List<WidgetList> = (shown + WidgetList.entries).distinct()

/**
 * Whether setNowPlaying should fetch the now playing artwork: when some of it is [missing], always
 * for a song that was not already playing, and otherwise only when this is not the song already
 * tried in this process. Kept apart from [WidgetStore.setNowPlaying] so the once per song limit is
 * tested without a real file, a fetch, or the lock.
 */
internal fun shouldFetchArt(same: Boolean, missing: Boolean, songId: String, lastAttempt: String?): Boolean =
    missing && (!same || lastAttempt != songId)

/**
 * The id-keyed art map WidgetStore.decoded() builds, paired with the now playing cover read from
 * its own path rather than from that map.
 *
 * The now playing song usually shares its id with its own entry in Recently played, and a plain
 * `associate`/`toMap` over [WidgetSnapshot.songs] keeps the last value for a repeated key, which is
 * recent's smaller copy whenever a widget is on screen since recent comes after now playing there.
 * The paired cover is read straight from [WidgetSnapshot.nowPlaying] instead, so that collision
 * never reaches it.
 */
internal fun <B> coversFor(
    snapshot: WidgetSnapshot,
    old: Map<String, B>,
    decode: (String?) -> B?,
    decodeNow: (String?) -> B? = decode,
): Pair<Map<String, B>, B?> {
    val art = snapshot.songs().mapNotNull { song ->
        val value = old[song.id] ?: decode(song.artPath)
        value?.let { song.id to it }
    }.toMap()
    val nowCover = snapshot.nowPlaying?.let { decodeNow(it.artPath) }
    return art to nowCover
}

/**
 * The hearts to draw while the widget shows the songs [shown] gives: the stored like of each, from
 * [likedOf], as it changes, and nothing of a song the widget no longer shows. A song that is not
 * stored, or nothing at all on the widget, gives nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun likesToDraw(shown: Flow<String?>, likedOf: (String) -> Flow<Boolean?>): Flow<Pair<String, Boolean>> =
    shown.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) emptyFlow() else likedOf(id).filterNotNull().map { id to it } }
        .distinctUntilChanged()

/**
 * The first guess at a new song's heart: the player's row of it, once the player has the row of
 * this song and not still the last one's, otherwise what the song itself says, which is the like as
 * it was when the queue was made. [WidgetStore.followLikes] corrects it within moments if it is wrong.
 */
internal fun likeGuess(song: MediaMetadata?, row: SongEntity?): Boolean =
    if (song == null) false else row?.takeIf { it.id == song.id }?.liked ?: song.liked

/**
 * The Recently played list after a song starts, honouring Pause listen history: unchanged while
 * paused, so the widget keeps no record either, otherwise the song prepended, deduplicated by id
 * and capped the way it always was.
 */
internal fun nextRecent(old: List<WidgetSong>, played: WidgetSong, paused: Boolean, maxPicks: Int): List<WidgetSong> =
    if (paused) old
    else (listOf(played) + old).distinctBy { it.id }.take(maxPicks)

/**
 * The snapshot as it can be drawn with the player's service [running] or not. Nothing plays without
 * that service, but the file keeps the play state it was last written with: the service writes
 * paused as it goes away, and an update of the app, or Android stopping it, never lets it. A widget
 * drawn again after an update offered pause over silence (seen on the Pixel 5, 5 Nov 2026).
 */
internal fun WidgetSnapshot.withPlayer(running: Boolean): WidgetSnapshot =
    if (isPlaying && !running) copy(isPlaying = false) else this

/**
 * Whether the library fills [which] when a widget appears, holding [held]. Recently played is the
 * snapshot's own list, a song for each one played since the snapshot began: one or two on a new
 * install, where the widget drew them and left the rest of its frame bare, so a short one is filled
 * up with what History holds from before. A row of Home's is what Home showed, short or not, and
 * Home writes it again at its next build: filled up here, it would shrink back then, so it is filled
 * only when Home never wrote it.
 */
internal fun libraryFills(which: WidgetList, held: List<WidgetSong>): Boolean =
    if (which == WidgetList.RECENT) held.size < WidgetLayout.MAX_PICKS else held.isEmpty()

/**
 * [held] with the songs of [more] it does not hold yet after it, up to [maxPicks]: a list shorter
 * than the widget has rows for, filled up from the library. What it held keeps its place and its
 * own row, artwork and all.
 */
internal fun filledUp(held: List<WidgetSong>, more: List<WidgetSong>, maxPicks: Int): List<WidgetSong> =
    (held + more).distinctBy { it.id }.take(maxPicks)

/**
 * Every artwork file this snapshot is still using, so [WidgetStore.prune] never deletes one still
 * on screen. The now playing song's big cover has no [WidgetSong.artPath] field of its own, since
 * [bigPath] computes its file from the id instead, so without this it would be eligible for
 * deletion even while it is on screen; nor has a list's first song's, which Big cover with a list
 * alone draws big.
 */
internal fun WidgetSnapshot.artPathsInUse(bigPath: (String) -> String): Set<String> =
    (listOfNotNull(nowPlaying) + picks + forgotten + keepListening + recent + upNext)
        .mapNotNullTo(HashSet()) { it.artPath }
        .apply {
            nowPlaying?.let { add(bigPath(it.id)) }
            for (first in listOfNotNull(picks.firstOrNull(), forgotten.firstOrNull(), keepListening.firstOrNull(), recent.firstOrNull())) add(bigPath(first.id))
        }

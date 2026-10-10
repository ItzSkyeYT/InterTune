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
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.scale
import androidx.glance.appwidget.updateAll
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The one file the widget reads and the app writes, and the artwork beside it.
 *
 * Every write is cheap and every write is skipped entirely when nobody has added a widget, which
 * is the common case: [hasWidgets] is one call into the launcher's own record, and everything here
 * is behind it. A music player should not pay for a home screen it is not on.
 */
object WidgetStore {
    private const val TAG = "WidgetStore"

    /** The now playing artwork beside a title. */
    private const val ART_NOW_PX = 192

    /** The now playing artwork when the whole widget is given over to it. */
    private const val ART_BIG_PX = 320

    /** A thumbnail at the head of a list row. */
    private const val ART_PICK_PX = 96

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
     * whose 96 px copy is the one the map keeps.
     */
    data class Drawn(
        val snapshot: WidgetSnapshot,
        val art: Map<String, Bitmap>,
        val big: Bitmap? = null,
        val nowCover: Bitmap? = null,
    )

    private fun dir(context: Context) = File(context.filesDir, "widget").apply { mkdirs() }
    private fun file(context: Context) = File(dir(context), "snapshot.tsv")
    private fun artDir(context: Context) = File(dir(context), "art").apply { mkdirs() }

    /** Whether anybody has this widget on a home screen. Nothing else here runs unless they have. */
    fun hasWidgets(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java))
            .isNotEmpty()
    }.getOrDefault(false)

    suspend fun read(context: Context): WidgetSnapshot = withContext(Dispatchers.IO) {
        runCatching { WidgetCodec.decode(file(context).takeIf { it.exists() }?.readText()) }
            .getOrDefault(WidgetSnapshot())
    }

    /** What to draw right now: the flow if this process has it, else the file. */
    suspend fun load(context: Context): Drawn = drawn.value ?: withContext(Dispatchers.IO) {
        decoded(context, read(context)).also { _drawn.value = it }
    }

    /** The artwork the snapshot names, as bitmaps. Small, few, and only re-read when they change. */
    private fun decoded(context: Context, snapshot: WidgetSnapshot): Drawn {
        val (art, nowCover) = coversFor(snapshot, _drawn.value?.art.orEmpty(), ::decode)
        return Drawn(
            snapshot,
            art,
            snapshot.nowPlaying?.let { decode(bigArtPath(context, it.id)) },
            nowCover,
        )
    }

    private fun decode(path: String?): Bitmap? = runCatching {
        path?.let { File(it) }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(path) }
    }.getOrNull()

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
        // the process starts from nothing.
        _drawn.value = decoded(context, snapshot)
    }

    /** What is playing at the moment the snapshot is written. */
    data class NowState(val song: MediaMetadata?, val isPlaying: Boolean)

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
        mutex.withLock {
            val (song, isPlaying) = state()
            val old = read(context)
            wantedArt = song?.id
            if (song == null) {
                write(context, old.copy(nowPlaying = null, isPlaying = false, updatedAt = System.currentTimeMillis()))
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
                write(context, old.copy(nowPlaying = now, isPlaying = isPlaying, recent = recent, updatedAt = System.currentTimeMillis()))
                if (widgets) {
                    // Three sizes: a widget given over to the artwork wants a real cover, one with
                    // a title beside it a smaller one, and a list row a thumbnail.
                    val sizes = listOfNotNull(ART_BIG_PX, ART_NOW_PX, ART_PICK_PX.takeIf { !paused })
                    val missing = sizes.any { !artFile(context, song.id, it).exists() }
                    if (shouldFetchArt(same, missing, song.id, lastArtAttempt)) {
                        lastArtAttempt = song.id
                        owed = song to sizes
                    }
                }
            }
            prune(context, read(context))
        }
        if (widgets) MusicWidget().updateAll(context)
        owed?.let { (song, sizes) ->
            if (picturesFor(context, song, sizes)) MusicWidget().updateAll(context)
        }
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
        if (!made) return false
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
     * A widget has appeared. Whatever the snapshot is missing because there was no widget to want
     * it, get now: Quick picks from the library when Home has not filled them in, the rest of a
     * Recently played that is still short, and the artwork of what it already knows.
     *
     * Words first, as everywhere else here. What the snapshot and the library know is written with
     * the artwork the phone has and the widget is drawn; the rest of the artwork is fetched after
     * that, outside the lock, and written when it is all there. It used to be fetched first, under
     * the lock, so a new widget on a slow connection stayed empty until the last cover of every
     * list was in, and a song change in that stretch waited as well.
     */
    suspend fun hydrate(context: Context) {
        if (!hasWidgets(context)) return
        var playing: WidgetSong? = null
        var bare = emptyList<WidgetSong>()
        mutex.withLock {
            var snapshot = read(context)
            snapshot.nowPlaying?.let { now ->
                if (now.artPath == null || now.colour == null) {
                    val art = now.artPath ?: artOnPhone(context, now.id, ART_NOW_PX)
                    snapshot = snapshot.copy(nowPlaying = now.copy(artPath = art, colour = now.colour ?: artColour(art)))
                }
            }
            // Every list, not only the one this widget shows: a second widget, or the same one
            // set to another list, then has something to draw the moment it is asked.
            for (which in WidgetList.entries) {
                val held = snapshot.list(which)
                // Recently played is this file's own list, a song for each one played since the
                // snapshot began: one or two on a new install, where the widget drew them and left
                // the rest of its frame bare, so a short one is filled up with what History holds
                // from before. A row of Home's is what Home showed, short or not, and Home writes
                // it again at its next build: filled up here, it would shrink back then.
                val wanting = if (which == WidgetList.RECENT) held.size < WidgetLayout.MAX_PICKS else held.isEmpty()
                val filled = if (wanting) filledUp(held, fromLibrary(context, which), WidgetLayout.MAX_PICKS) else held
                snapshot = snapshot.withList(which, filled.map { song ->
                    if (song.artPath != null) song
                    else song.copy(artPath = artOnPhone(context, song.id, ART_PICK_PX))
                })
            }
            write(context, snapshot)
            prune(context, read(context))
            playing = snapshot.nowPlaying
            bare = WidgetList.entries.flatMap { snapshot.list(it) }.filter { it.artPath == null }.distinctBy { it.id }
        }
        MusicWidget().updateAll(context)

        val now = playing
        val made = now != null && artMutex.withLock {
            artInSizes(context, now.id, listOf(ART_BIG_PX, ART_NOW_PX)) { px -> artModel(now, px) }
        }
        val rows = HashMap<String, String>()
        for (song in bare) {
            artMutex.withLock {
                artFor(context, song.id, artModel(song, ART_PICK_PX), ART_PICK_PX)?.let { rows[song.id] = it }
            }
        }
        if (!made && rows.isEmpty()) return
        mutex.withLock {
            var snapshot = read(context)
            // Only if it is still the song that plays; a row is its own wherever it still is.
            snapshot.nowPlaying?.takeIf { it.id == now?.id }?.let { song ->
                val art = artOnPhone(context, song.id, ART_NOW_PX) ?: song.artPath
                snapshot = snapshot.copy(nowPlaying = song.copy(artPath = art, colour = song.colour ?: artColour(art)))
            }
            for (which in WidgetList.entries) {
                snapshot = snapshot.withList(which, snapshot.list(which).map { if (it.artPath == null) it.copy(artPath = rows[it.id]) else it })
            }
            write(context, snapshot)
        }
        MusicWidget().updateAll(context)
    }

    /**
     * A row straight from the library, for a widget added before Home has ever filled that row.
     * The same queries Home uses, so the widget is never emptier than the app. With the artwork
     * the phone has and no more: this runs under the lock (see [hydrate]).
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

    /** The colour of a cover: the one the player would take, or what a cover of greys has most of. */
    private fun artColour(path: String?): Int? = runCatching {
        decode(path)?.extractCoverColor()?.toArgb()
    }.getOrNull()

    /**
     * The artwork file a song already has at [px], if any: the cover at that size before one made
     * from the stored address (see lesserArtFile).
     */
    private fun artOnPhone(context: Context, id: String, px: Int): String? =
        listOf(artFile(context, id, px), lesserArtFile(context, id, px)).firstOrNull { it.exists() }?.absolutePath

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

    private fun hash(id: String): String =
        MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}

/** How a broadcast receiver, which Hilt does not inject into here, reaches the one database. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun database(): MusicDatabase
}

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
internal fun <B> coversFor(snapshot: WidgetSnapshot, old: Map<String, B>, decode: (String?) -> B?): Pair<Map<String, B>, B?> {
    val art = snapshot.songs().mapNotNull { song ->
        val value = old[song.id] ?: decode(song.artPath)
        value?.let { song.id to it }
    }.toMap()
    val nowCover = snapshot.nowPlaying?.let { decode(it.artPath) }
    return art to nowCover
}

/**
 * The Recently played list after a song starts, honouring Pause listen history: unchanged while
 * paused, so the widget keeps no record either, otherwise the song prepended, deduplicated by id
 * and capped the way it always was.
 */
internal fun nextRecent(old: List<WidgetSong>, played: WidgetSong, paused: Boolean, maxPicks: Int): List<WidgetSong> =
    if (paused) old
    else (listOf(played) + old).distinctBy { it.id }.take(maxPicks)

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
 * deletion even while it is on screen.
 */
internal fun WidgetSnapshot.artPathsInUse(bigPath: (String) -> String): Set<String> =
    (listOfNotNull(nowPlaying) + picks + forgotten + keepListening + recent)
        .mapNotNullTo(HashSet()) { it.artPath }
        .apply { nowPlaying?.let { add(bigPath(it.id)) } }

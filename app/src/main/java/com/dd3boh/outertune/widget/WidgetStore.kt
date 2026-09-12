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
import androidx.core.net.toUri
import androidx.glance.appwidget.updateAll
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.ui.theme.extractThemeColor
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.LocalArtworkPath
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
     */
    data class Drawn(val snapshot: WidgetSnapshot, val art: Map<String, Bitmap>, val big: Bitmap? = null)

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
        val old = _drawn.value?.art.orEmpty()
        val art = snapshot.songs().mapNotNull { song ->
            val bitmap = old[song.id] ?: decode(song.artPath)
            bitmap?.let { song.id to it }
        }.toMap()
        return Drawn(snapshot, art, snapshot.nowPlaying?.let { decode(bigArtPath(context, it.id)) })
    }

    private fun decode(path: String?): Bitmap? = runCatching {
        path?.let { File(it) }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(path) }
    }.getOrNull()

    private fun bigArtPath(context: Context, id: String) = File(artDir(context), hash(id) + "_" + ART_BIG_PX + ".png").absolutePath

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
     * pause, so it does as little as it can: the artwork is fetched only when the song changed.
     *
     * The state is read through [state] under the lock rather than passed in, because these calls
     * arrive from the player a few milliseconds apart and finish in whatever order the disk
     * allows. Asking at the moment of writing means the last write is the truth, instead of
     * whichever one happened to be slowest leaving a paused button over a playing song.
     */
    suspend fun setNowPlaying(context: Context, state: suspend () -> NowState) {
        val widgets = hasWidgets(context)
        mutex.withLock {
            val (song, isPlaying) = state()
            val old = read(context)
            if (song == null) {
                write(context, old.copy(nowPlaying = null, isPlaying = false, updatedAt = System.currentTimeMillis()))
            } else {
                val same = old.nowPlaying?.id == song.id
                // The picture is the expensive half and only a widget needs it. The words are
                // written either way, so a widget added mid-song opens on the right song. Two
                // sizes, because a widget given over to the artwork wants a real cover while one
                // with a list beside it wants a thumbnail.
                if (!same && widgets) artFor(context, song.id, artModel(song, ART_BIG_PX), ART_BIG_PX)
                val art = if (same) old.nowPlaying?.artPath
                else if (widgets) artFor(context, song.id, artModel(song, ART_NOW_PX), ART_NOW_PX) else null
                val now = WidgetSong(
                    id = song.id,
                    title = song.title,
                    artist = song.artists.joinToString(", ") { it.name },
                    artPath = art,
                    thumbnailUrl = song.thumbnailUrl,
                    durationSec = song.duration,
                    isLocal = song.isLocal,
                    colour = if (same) old.nowPlaying?.colour else artColour(art),
                )
                // Recently played is kept here rather than queried: the song that just started is
                // the newest there is, and the widget should not have to ask the database to know it.
                val recent = (listOf(now.copy(artPath = pickArt(context, now))) + old.recent)
                    .distinctBy { it.id }
                    .take(WidgetLayout.MAX_PICKS)
                write(context, old.copy(nowPlaying = now, isPlaying = isPlaying, recent = recent, updatedAt = System.currentTimeMillis()))
            }
            prune(context)
        }
        if (widgets) MusicWidget().updateAll(context)
    }

    /** One of Home's rows as Home is showing it. The widget and the app then hold the same songs. */
    suspend fun setList(context: Context, which: WidgetList, songs: List<MediaMetadata>) {
        val widgets = hasWidgets(context)
        var changed = false
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
                    artPath = byId[song.id]?.artPath ?: if (widgets) artFor(context, song.id, artModel(song, ART_PICK_PX), ART_PICK_PX) else null,
                    thumbnailUrl = song.thumbnailUrl,
                    durationSec = song.duration,
                    isLocal = song.isLocal,
                )
            }
            write(context, old.withList(which, list).copy(updatedAt = System.currentTimeMillis()))
            prune(context)
            changed = true
        }
        if (widgets && changed) MusicWidget().updateAll(context)
    }

    /**
     * A widget has appeared. Whatever the snapshot is missing because there was no widget to want
     * it, fetch now: the artwork of what it already knows, and Quick picks from the library when
     * Home has not filled them in. Then draw.
     */
    suspend fun hydrate(context: Context) {
        if (!hasWidgets(context)) return
        mutex.withLock {
            var snapshot = read(context)
            val now = snapshot.nowPlaying
            if (now != null && now.artPath == null) {
                snapshot = snapshot.copy(nowPlaying = now.copy(artPath = artFor(context, now.id, artModel(now), ART_NOW_PX)))
            }
            if (now != null) artFor(context, now.id, artModel(now, ART_BIG_PX), ART_BIG_PX)
            // Every list, not only the one this widget shows: a second widget, or the same one
            // set to another list, then has something to draw the moment it is asked.
            for (which in WidgetList.entries) {
                val filled = snapshot.list(which).ifEmpty { fromLibrary(context, which) }
                snapshot = snapshot.withList(which, filled.map { song ->
                    if (song.artPath != null) song
                    else song.copy(artPath = artFor(context, song.id, artModel(song, ART_PICK_PX), ART_PICK_PX))
                })
            }
            write(context, snapshot)
            prune(context)
        }
        MusicWidget().updateAll(context)
    }

    /**
     * A row straight from the library, for a widget added before Home has ever filled that row.
     * The same queries Home uses, so the widget is never emptier than the app.
     */
    private suspend fun fromLibrary(context: Context, which: WidgetList): List<WidgetSong> = runCatching {
        val database = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).database()
        val rows = when (which) {
            WidgetList.QUICK_PICKS -> database.quickPicks().first()
            WidgetList.FORGOTTEN_FAVOURITES -> database.forgottenFavorites().first()
            WidgetList.KEEP_LISTENING -> database.mostPlayedSongs(
                System.currentTimeMillis() - 14L * 86_400_000L, limit = WidgetLayout.MAX_PICKS,
            ).first()
            // Newest first, one row per song however often it has been played.
            WidgetList.RECENT -> database.events().first()
                .map { it.song }.distinctBy { it.id }.take(WidgetLayout.MAX_PICKS)
        }
        rows.take(WidgetLayout.MAX_PICKS).map { song ->
            val meta = song.toMediaMetadata()
            WidgetSong(
                id = meta.id,
                title = meta.title,
                artist = meta.artists.joinToString(", ") { it.name },
                artPath = artFor(context, meta.id, artModel(meta, ART_PICK_PX), ART_PICK_PX),
                thumbnailUrl = meta.thumbnailUrl,
                durationSec = meta.duration,
                isLocal = meta.isLocal,
            )
        }
    }.onFailure { Log.w(TAG, "Could not read $which for the widget", it) }.getOrDefault(emptyList())

    /** The colour of a cover, the way the player takes its own. */
    private fun artColour(path: String?): Int? = runCatching {
        decode(path)?.extractThemeColor()?.toArgb()
    }.getOrNull()

    /** A row-sized copy of the now playing artwork, for the Recently played list. */
    private suspend fun pickArt(context: Context, song: WidgetSong): String? =
        artFor(context, song.id, artModel(song, ART_PICK_PX), ART_PICK_PX) ?: song.artPath

    private fun artModel(song: MediaMetadata, px: Int = ART_NOW_PX): Any? = when {
        song.isLocal -> song.localPath?.let { LocalArtworkPath(it, px, px) }
        else -> song.thumbnailUrl?.toUri()
    }

    /** The same, for a song read back from the snapshot, where a local file is all that was kept. */
    private fun artModel(song: WidgetSong, px: Int = ART_NOW_PX): Any? = when {
        song.isLocal -> song.thumbnailUrl?.let { LocalArtworkPath(it, px, px) }
        else -> song.thumbnailUrl?.toUri()
    }

    /**
     * The song's artwork as a small square PNG on disk, or null if it could not be had. Cached by
     * song and size, so a song that comes round again costs nothing.
     */
    private suspend fun artFor(context: Context, id: String, model: Any?, px: Int): String? {
        model ?: return null
        val out = File(artDir(context), "${hash(id)}_$px.png")
        if (out.exists()) return out.absolutePath
        return runCatching {
            withContext(Dispatchers.IO) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(model).allowHardware(false).size(px, px).build()
                )
                val bitmap = result.image?.toBitmap() ?: return@withContext null
                // Cropped to the middle before it is scaled: a lot of YouTube's artwork is wide,
                // and squashing a wide picture into a square is the one thing a cover must not do.
                val side = minOf(bitmap.width, bitmap.height)
                val cropped = if (bitmap.width == bitmap.height) bitmap
                else Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                val square = if (cropped.width > px) cropped.scale(px, px) else cropped
                out.outputStream().use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out.absolutePath
            }
        }.onFailure { Log.w(TAG, "Could not cache the widget artwork", it) }.getOrNull()
    }

    /** Artwork for songs the widget is no longer showing, oldest first. */
    private fun prune(context: Context) = runCatching {
        val files = artDir(context).listFiles().orEmpty()
        if (files.size <= MAX_ART_FILES) return@runCatching
        files.sortedBy { it.lastModified() }.dropLast(MAX_ART_FILES).forEach { it.delete() }
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

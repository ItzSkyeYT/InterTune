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
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.glance.appwidget.updateAll
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
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

    /** The now playing artwork, which has the whole width of the widget to fill. */
    private const val ART_NOW_PX = 256

    /** A Quick picks thumbnail, which is a small square at the head of a row. */
    private const val ART_PICK_PX = 96

    /**
     * Artwork is passed to the launcher as a bitmap inside a binder transaction with about a
     * megabyte to spend for everything on screen, so the files are small and there are few of
     * them. Anything older than the snapshot's own songs is deleted on each write.
     */
    private const val MAX_ART_FILES = 12

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

    /** A snapshot and the artwork it names, decoded once per change rather than once per draw. */
    data class Drawn(val snapshot: WidgetSnapshot, val art: Map<String, Bitmap>)

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
        val snapshot = read(context)
        Drawn(snapshot, decodeArt(snapshot)).also { _drawn.value = it }
    }

    /** The artwork the snapshot names, as bitmaps. Small, few, and only re-read when they change. */
    private fun decodeArt(snapshot: WidgetSnapshot): Map<String, Bitmap> {
        val old = _drawn.value?.art.orEmpty()
        return (listOfNotNull(snapshot.nowPlaying) + snapshot.picks).mapNotNull { song ->
            val path = song.artPath ?: return@mapNotNull null
            val bitmap = old[song.id] ?: runCatching {
                File(path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(path) }
            }.getOrNull()
            bitmap?.let { song.id to it }
        }.toMap()
    }

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
        _drawn.value = Drawn(snapshot, decodeArt(snapshot))
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
                // written either way, so a widget added mid-song opens on the right song.
                val art = if (same) old.nowPlaying?.artPath
                else if (widgets) artFor(context, song.id, artModel(song), ART_NOW_PX) else null
                val now = WidgetSong(
                    id = song.id,
                    title = song.title,
                    artist = song.artists.joinToString(", ") { it.name },
                    artPath = art,
                    thumbnailUrl = song.thumbnailUrl,
                    durationSec = song.duration,
                    isLocal = song.isLocal,
                )
                write(context, old.copy(nowPlaying = now, isPlaying = isPlaying, updatedAt = System.currentTimeMillis()))
            }
            prune(context)
        }
        if (widgets) MusicWidget().updateAll(context)
    }

    /** Quick picks as Home is showing them. The widget and the app then hold the same row. */
    suspend fun setPicks(context: Context, songs: List<MediaMetadata>) {
        val widgets = hasWidgets(context)
        mutex.withLock {
            val old = read(context)
            val wanted = songs.take(WidgetLayout.MAX_PICKS)
            if (wanted.map { it.id } == old.picks.map { it.id }) return@withLock
            val byId = old.picks.associateBy { it.id }
            val picks = wanted.map { song ->
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
            write(context, old.copy(picks = picks, updatedAt = System.currentTimeMillis()))
            prune(context)
        }
        if (widgets) MusicWidget().updateAll(context)
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
            if (snapshot.picks.isEmpty()) {
                snapshot = snapshot.copy(picks = libraryPicks(context))
            }
            snapshot = snapshot.copy(
                picks = snapshot.picks.map { pick ->
                    if (pick.artPath != null) pick
                    else pick.copy(artPath = artFor(context, pick.id, artModel(pick, ART_PICK_PX), ART_PICK_PX))
                }
            )
            write(context, snapshot)
            prune(context)
        }
        MusicWidget().updateAll(context)
    }

    /**
     * Quick picks straight from the library, for a widget added before Home has ever filled its
     * row. The same query the Your library source uses, so the widget is never emptier than the app.
     */
    private suspend fun libraryPicks(context: Context): List<WidgetSong> = runCatching {
        val database = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).database()
        database.quickPicks().first().take(WidgetLayout.MAX_PICKS).map { song ->
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
    }.onFailure { Log.w(TAG, "Could not read Quick picks for the widget", it) }.getOrDefault(emptyList())

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

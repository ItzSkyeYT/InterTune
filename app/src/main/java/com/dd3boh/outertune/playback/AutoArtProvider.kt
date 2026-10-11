/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.core.graphics.scale
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.ui.utils.coverAddresses
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.dd3boh.outertune.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException

/**
 * The covers Android Auto asks for (see [AutoArt]). Read only, and only pictures: an address
 * names a song, an album, an artist or a playlist of the library by its id, the cover is looked
 * up from that, fetched once at the size the car draws and kept in the cache, and the file is
 * handed over. Nothing else can be asked of it, and what it is asked for never leaves the phone.
 */
class AutoArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("no context")
        val (kind, id) = AutoArt.parse(uri.pathSegments) ?: throw FileNotFoundException(uri.toString())
        // A binder thread of this app, never the main one: the car waits for the answer, so does this.
        val file = runBlocking { withTimeoutOrNull(FETCH_MS) { cover(context.applicationContext, kind, id) } }
            ?: throw FileNotFoundException("no cover for $kind")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private suspend fun cover(context: Context, kind: String, id: String): File? = withContext(Dispatchers.IO) {
        val out = File(File(context.cacheDir, "auto-art").apply { mkdirs() }, "${kind}_${name(id)}.png")
        if (out.length() > 0) return@withContext out
        runCatching {
            for (model in models(context, kind, id)) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(model).allowHardware(false).size(PX, PX).build()
                )
                val bitmap = result.image?.toBitmap() ?: continue
                // The middle of it, square: a lot of YouTube's artwork is wide, and a cover is not.
                val side = minOf(bitmap.width, bitmap.height)
                val cropped = if (bitmap.width == bitmap.height) bitmap
                else Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                val square = if (cropped.width > PX) cropped.scale(PX, PX) else cropped
                // Written beside it and moved into place, so that a second request for the same
                // cover never reads half a file.
                val part = File(out.parentFile, "${out.name}.${Thread.currentThread().id}.part")
                part.outputStream().use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (part.renameTo(out)) return@withContext out
                part.delete()
            }
            null
        }.onFailure { Log.w(TAG, "Could not make a cover for the car", it) }.getOrNull()
    }

    /** What to ask Coil for, best first: the picture the library has for this, or for a playlist its first song's. */
    private suspend fun models(context: Context, kind: String, id: String): List<Any> {
        val database = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).database()
        fun web(address: String?) = address?.let { coverAddresses(it, PX) }.orEmpty()
        return when (kind) {
            AutoArt.SONG -> database.song(id).first()?.song?.let { song ->
                if (song.isLocal) listOfNotNull(song.localPath?.let { LocalArtworkPath(it, PX, PX) }) else web(song.thumbnailUrl)
            }.orEmpty()

            AutoArt.ALBUM -> web(database.album(id).first()?.album?.thumbnailUrl)
            AutoArt.ARTIST -> web(database.artist(id).first()?.artist?.thumbnailUrl)
            AutoArt.PLAYLIST -> when (id) {
                PlaylistEntity.LIKED_PLAYLIST_ID -> web(database.likedSongs(SongSortType.CREATE_DATE, true).first().firstOrNull()?.song?.thumbnailUrl)
                PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> web(database.downloadNoLocalSongs().first().firstOrNull()?.song?.thumbnailUrl)
                else -> database.playlist(id).first()?.let { web(it.playlist.thumbnailUrl) + web(it.songThumbnails.firstOrNull { address -> address != null }) }.orEmpty()
            }

            else -> emptyList()
        }
    }

    /** A file name for an id: every id has its own, and none can step out of the folder. */
    private fun name(id: String): String =
        Base64.encodeToString(id.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).take(120)

    companion object {
        private const val TAG = "AutoArt"

        /** The size a cover is kept at: the car's grid draws them at about 256, and no car at more than this. */
        private const val PX = 320

        /** How long the car is kept waiting for one cover before it gets none. */
        private const val FETCH_MS = 8_000L
    }
}

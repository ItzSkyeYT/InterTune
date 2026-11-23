/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.dd3boh.outertune.R
import javax.inject.Inject

/**
 * Coil's reader for the picture inside a music file on the phone, for the app's own screens.
 *
 * Not for the system's media player: a file with no picture is answered here with the app's logo,
 * which the app's lists draw for such a song, and the system took it for the song's cover. The
 * session has its own loader, SessionBitmapLoader.
 */
class CoilBitmapLoader @Inject constructor(
    private val context: Context,
    private val data: LocalArtworkPath = LocalArtworkPath(null),
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return try {
            if (data.path?.startsWith("/storage/") == true) {
                val mData = MediaMetadataRetriever()
                val own: Bitmap? = try {
                    mData.setDataSource(data.path)
                    mData.embeddedPicture?.let { art -> BitmapFactory.decodeByteArray(art, 0, art.size) }
                } catch (e: Exception) {
                    null
                } finally {
                    runCatching { mData.release() }
                }
                // A file with no picture of its own takes its folder's cover, as music players do (LocalCovers).
                var image: Bitmap = own
                    ?: LocalCovers.of(context, data.path, if (data.x + data.y > 0) maxOf(data.x, data.y) else FOLDER_COVER_PX)
                    ?: drawPlaceholder(context)

                if (data.x + data.y > 0) {
                    var realX = data.x
                    var realY = data.y

                    // scale maintaining aspect ratio
                    if (image.width != image.height) {
                        val frameW = data.x
                        val frameH = data.y
                        val imgW = image.width
                        val imgH = image.height

                        val scaleX = frameW.toFloat() / imgW
                        val scaleY = frameH.toFloat() / imgH
                        val scale = minOf(scaleX, scaleY)

                        realX = (imgW * scale).toInt()
                        realY = (imgH * scale).toInt()
                    }

                    image = image.scale(realX, realY)
                }

                ImageFetchResult(
                    image = image.asImage(),
                    isSampled = false,
                    dataSource = DataSource.DISK
                )
            } else {
                null
            }
        } catch (e: Exception) {
            reportException(e)
            ImageFetchResult(
                image = drawPlaceholder(context).asImage(),
                isSampled = false,
                dataSource = DataSource.MEMORY
            )
        }
    }

    companion object {
        /** The size a folder's cover is read at when no size is asked for. */
        const val FOLDER_COVER_PX = 1024

        /**
         * The cover of a local song or album that has none: a slate square with a white note, opaque
         * like a real cover, so a list, the player or a widget never shows what is behind it (#150,
         * drawn after Ti-Die's design of 14 Nov 2026). Coil hands it out as the file's picture.
         *
         * @param context
         * @param x Desired final x dimension
         * @param y Desired final y dimension
         */
        fun drawPlaceholder(context: Context, x: Int = 2000, y: Int = 2000): Bitmap {
            val bitmap = Bitmap.createBitmap(x, y, Bitmap.Config.ARGB_8888)
            ContextCompat.getDrawable(context, R.drawable.local_song_cover)?.let { cover ->
                cover.setBounds(0, 0, x, y)
                cover.draw(Canvas(bitmap))
            }
            return bitmap
        }
    }

    class Factory(
        private val context: Context,
    ) : Fetcher.Factory<LocalArtworkPath> {
        override fun create(data: LocalArtworkPath, options: Options, imageLoader: ImageLoader): Fetcher? {
            return CoilBitmapLoader(context, data = data)
        }
    }
}

class LocalArtworkPathKeyer : Keyer<LocalArtworkPath> {
    override fun key(
        data: LocalArtworkPath,
        options: Options
    ): String? {
        return data.path + ";" + data.x + ";" + data.y
    }

}

data class LocalArtworkPath(val path: String?, val x: Int = -1, val y: Int = -1)

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
import android.graphics.drawable.Drawable
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
import kotlin.math.min

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
                var image: Bitmap = try {
                    mData.setDataSource(data.path)
                    val art = mData.embeddedPicture
                    BitmapFactory.decodeByteArray(art, 0, art!!.size)
                } catch (e: Exception) {
                    drawPlaceholder(context)
                } ?: drawPlaceholder(context)

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
        // TODO: re eval dimens after a few months
        /**
         * Draw a centered square app icon with the maximum possible size while maintaining aspect ratio.
         *
         * @param context
         * @param x Desired final x dimension
         * @param y Desired final y dimension
         * @param size Percentage size of valid draw frame. Must be a value between 0.0 and 1.0. For example, 0.8
         *      means that inner frame should be 80% of the size of the final frame, and centered within that frame.
         */
        fun drawPlaceholder(context: Context, x: Int = 2000, y: Int = 2000, size: Float = 0.8f): Bitmap {
            val padding = size.coerceIn(0f, 1f)
            val innerRecWidth = x * padding
            val innerRecHeight = y * padding

            val squareLength = min(innerRecWidth, innerRecHeight).toInt()
            val squareLeft = ((x - squareLength) / 2)
            val squareTop = ((y - squareLength) / 2)

            val drawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.placeholder_icon)
            val bitmap = Bitmap.createBitmap(x, y, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawable?.setBounds(squareLeft, squareTop, squareLeft + squareLength, squareTop + squareLength)
            drawable?.draw(canvas)
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

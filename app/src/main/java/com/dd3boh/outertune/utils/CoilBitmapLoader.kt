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
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.media3.common.util.BitmapLoader
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.imageLoader
import coil3.key.Keyer
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.utils.artSizeBucket
import com.dd3boh.outertune.ui.utils.coverAddresses
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import kotlin.math.min

class CoilBitmapLoader @Inject constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(coilCoroutine),
    private val data: LocalArtworkPath = LocalArtworkPath(null),
) : Fetcher, BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future {
            BitmapFactory.decodeByteArray(data, 0, data.size) ?: drawPlaceholder(context)
        }

    /** The longest side the system keeps of the cover it is handed, in pixels on this screen. */
    private val artPx get() = sessionArtPx(context.resources.displayMetrics.density)

    private val covers by lazy {
        LastAsked<Uri, Bitmap>(scope, ::cover) { drawPlaceholder(context, artPx, artPx) }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = covers.ask(uri)

    /** The cover at [uri] for the system, or null when it cannot be had right now. */
    private suspend fun cover(uri: Uri): Bitmap? {
        val stored = uri.toString()
        try {
            // local images
            if (stored.startsWith("/storage/")) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(LocalArtworkPath(stored))
                        .allowHardware(false)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build()
                )
                if (result is ErrorResult) {
                    reportException(ExecutionException(result.throwable))
                    return null
                }
                return result.image!!.toBitmap()
            }

            var failure: Throwable? = null
            for (address in sessionArtwork(stored, artPx)) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(address)
                        // decoded straight to what the system keeps, and never blown up to it
                        .size(artPx, artPx)
                        .precision(Precision.INEXACT)
                        .allowHardware(false)
                        // LastAsked keeps the one that matters. In Coil's cache a cover this size
                        // is megabytes that nothing else can use, and a few songs of them push
                        // every list thumbnail out (see the note on hardware bitmaps in App.kt).
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .build()
                )
                if (result is SuccessResult) return result.image.toBitmap()
                failure = (result as ErrorResult).throwable
            }
            reportException(ExecutionException(failure))
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(ExecutionException(e))
            return null
        }
    }

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

/**
 * The longest side the platform keeps of a bitmap in a media session's metadata, in dp
 * (config_mediaMetadataBitmapMaxSize). MediaSession.setMetadata scales anything larger down to it
 * before the bitmap leaves the app, so more than this is never seen by anyone.
 */
private const val SESSION_ART_DP = 320

/** That limit in pixels on a screen of this [density]. */
fun sessionArtPx(density: Float): Int = (SESSION_ART_DP * density + 0.5f).toInt()

/**
 * The addresses to try for the cover handed to the system, best first: its media player in the
 * shade and on the lock screen, the notification, a watch, a car. They all get the one bitmap the
 * session holds, and the system player stretches it across its whole width.
 *
 * It used to be loaded from the address the song is [stored] with, as it is. Most songs are stored
 * with the thumbnail of the list they were first seen in, 120 pixels for 334 of the 402 songs in
 * one real library, so that is what the system drew over a thousand pixels. Now the image host is
 * asked for [px], rounded up to the size the player asks for on the same screen (artSizeBucket), so
 * whichever of the two comes second finds the file on disk.
 *
 * The stored address stays as a second try. With no connection the large cover cannot be fetched,
 * while the small one is on disk for every song played before this change.
 */
fun sessionArtwork(stored: String, px: Int): List<String> = coverAddresses(stored, artSizeBucket(px))

class LocalArtworkPathKeyer : Keyer<LocalArtworkPath> {
    override fun key(
        data: LocalArtworkPath,
        options: Options
    ): String? {
        return data.path + ";" + data.x + ";" + data.y
    }

}

data class LocalArtworkPath(val path: String?, val x: Int = -1, val y: Int = -1)

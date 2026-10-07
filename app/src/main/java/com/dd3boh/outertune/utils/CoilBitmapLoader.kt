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
import coil3.annotation.ExperimentalCoilApi
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
import coil3.request.ImageResult
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

    /**
     * Told when the large cover has arrived for a song that was first answered with its small one:
     * the address the song is stored with, and the address of the cover now in hand. media3 does
     * not ask again by itself (see SessionPlayer), so whoever holds the session has to make it.
     * Called on the thread the cover was loaded on.
     */
    var sharper: ((stored: String, sharp: String) -> Unit)? = null

    private val covers by lazy {
        LastAsked<SessionCover, Bitmap>(
            scope,
            find = ::cover,
            instead = { drawPlaceholder(context, artPx, artPx) },
            better = { cover -> sharper?.invoke(cover.stored, cover.sharp) },
            retryAfterMs = LARGE_COVER_RETRY_MS,
        )
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        covers.ask(SessionCover(sessionArtwork(uri.toString(), artPx)))

    /**
     * The cover for the system, or null when it cannot be had right now.
     *
     * The large cover can be seconds away on a slow connection and out of reach without one, and
     * until this answers the lock screen and the notification show no picture at all. So unless
     * the large one is on the phone already, the cover the song is stored with goes out first,
     * through [meanwhile]: it is on the phone for most songs and a few kilobytes for the rest. The
     * large one is fetched behind it, and LastAsked hands it to whoever asks from then on.
     */
    private suspend fun cover(asked: SessionCover, meanwhile: (Bitmap) -> Unit): Bitmap? {
        try {
            // local images
            if (asked.sharp.startsWith("/storage/")) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(LocalArtworkPath(asked.sharp))
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

            var handedOut = false
            if (asked.stored != asked.sharp && !onPhone(asked.sharp)) {
                (load(asked.stored) as? SuccessResult)?.let {
                    meanwhile(it.image.toBitmap())
                    handedOut = true
                }
            }
            val result = load(asked.sharp)
            if (result is SuccessResult) return result.image.toBitmap()
            // With the stored cover handed out, a large one that cannot be had means no connection,
            // which is nothing to report at every play and pause.
            if (!handedOut) reportException(ExecutionException((result as ErrorResult).throwable))
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(ExecutionException(e))
            return null
        }
    }

    private suspend fun load(address: String): ImageResult = context.imageLoader.execute(
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

    /**
     * Whether the image at [address] is in Coil's files, so that asking for it costs no download.
     * An address is its own key there. With the image cache switched off in settings nothing is,
     * and every cover is one of the two downloads.
     */
    @OptIn(ExperimentalCoilApi::class)
    private fun onPhone(address: String): Boolean = runCatching {
        context.imageLoader.diskCache?.openSnapshot(address)?.use { true } ?: false
    }.getOrDefault(false)

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

/**
 * How long a large cover that could not be fetched is left alone, while the small one stands in
 * for it, before the system asking for the cover starts another download.
 */
private const val LARGE_COVER_RETRY_MS = 15_000L

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
 * The stored address stays as the other one. With no connection the large cover cannot be fetched,
 * and on a slow one it is seconds away, while the small one is on the phone for most songs: it is
 * what the system is given until the large one is there (see CoilBitmapLoader.cover).
 */
fun sessionArtwork(stored: String, px: Int): List<String> = coverAddresses(stored, artSizeBucket(px))

/**
 * A cover as the system asks for it: its [addresses], best first (see [sessionArtwork]).
 *
 * Two of these are the same cover when their best address is the same. Once the large cover has
 * taken the small one's place the session names the cover by the large address (see SessionPlayer),
 * and asking by that name has to find the cover that is already in hand, not start another.
 */
internal class SessionCover(val addresses: List<String>) {
    /** The cover at the size the system keeps. */
    val sharp: String get() = addresses.first()

    /** The cover as the song is stored with it: the same address when there is only the one. */
    val stored: String get() = addresses.last()

    override fun equals(other: Any?): Boolean = other is SessionCover && other.sharp == sharp
    override fun hashCode(): Int = sharp.hashCode()
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

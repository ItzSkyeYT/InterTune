/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 * Copyright (C) 2026 InterTune
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
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.BitmapLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.utils.artSizeBucket
import com.dd3boh.outertune.ui.utils.coverAddresses
import com.dd3boh.outertune.ui.utils.coverSide
import com.dd3boh.outertune.ui.utils.resize
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The covers handed to the system for the playing song, as the media session's bitmap loader: its
 * media player in the shade and on the lock screen, the notification, a watch, a car.
 *
 * Each answer is the song's own cover, or a plain square with a music note when there is none to be
 * had ([noCover]). It used to be the app's logo, drawn by the code that draws it in the app's lists:
 * offline, or on a connection too slow for the cover, the lock screen showed the logo as the song's
 * cover, and kept it to the end of the song even once the cover could be had.
 */
class SessionBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(coilCoroutine),
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future { decodeSampled(data, artPx) ?: noCover(context, artPx) }

    private val density get() = context.resources.displayMetrics.density

    /** The longest side the system keeps of the cover it is handed, in pixels on this screen. */
    private val artPx get() = sessionArtPx(density)

    /**
     * Told when a better cover is in hand for the playing song than the one the system was given:
     * the large one in place of a small one, or the cover in place of the stand-in. Given the
     * address the song is stored with and the cover's large address. media3 does not ask again by
     * itself (see SessionPlayer), so whoever holds the session has to make it. Called on the thread
     * the cover was loaded on.
     */
    var sharper: ((stored: String, sharp: String) -> Unit)? = null

    private val covers by lazy {
        LastAsked<SessionCover, Bitmap>(
            scope,
            find = ::cover,
            instead = { noCover(context, artPx) },
            better = { cover -> sharper?.invoke(cover.stored, cover.sharp) },
            retryAfterMs = LARGE_COVER_RETRY_MS,
            retryDelaysMs = COVER_RETRY_DELAYS_MS,
        )
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        covers.ask(SessionCover(sessionArtwork(unmarkedCover(uri.toString()), artPx)))

    /** The connection is back: a cover that could not be had for want of it is tried for at once. */
    fun tryAgain() = covers.tryAgain()

    /**
     * The cover for the system, or null when it cannot be had right now.
     *
     * The large cover can be seconds away on a slow connection and out of reach without one, and
     * until this answers the lock screen and the notification show no picture at all. So unless the
     * large one is on the phone already, a smaller one goes out first, through [meanwhile]: the
     * largest the phone has of the sizes the app's own screens fetch (see [coverOnPhone]), or else
     * the one the song is stored with, a few kilobytes. The large one is fetched behind it, and
     * LastAsked hands it to whoever asks from then on. When neither comes, LastAsked hands out the
     * stand-in and tries again, by itself and when the connection is back.
     */
    private suspend fun cover(asked: SessionCover, meanwhile: (Bitmap) -> Unit): Bitmap? {
        try {
            // A file's own picture, or none: it will not come later, so there is nothing to try again.
            if (asked.sharp.startsWith("/")) {
                return embedded(asked.sharp) ?: noCover(context, artPx).also { Log.d(TAG, "A file with no picture: the stand-in") }
            }

            if (onPhone(asked.sharp)) {
                fromPhone(asked.sharp)?.let {
                    Log.d(TAG, "The large cover from the phone")
                    return it
                }
            }
            var handedOut = false
            for (address in coverOnPhone(asked.stored, asked.sharp, density)) {
                if (!onPhone(address)) continue
                val smaller = fromPhone(address) ?: continue
                Log.d(TAG, "A smaller cover from the phone, ${coverSide(address).takeIf { it > 0 } ?: "?"} pixels, while the large one is fetched")
                meanwhile(smaller)
                handedOut = true
                break
            }
            if (!handedOut && asked.stored != asked.sharp) {
                fetch(asked.stored, SMALL_COVER_TIMEOUT_MS)?.let {
                    Log.d(TAG, "The small cover from the network, while the large one is fetched")
                    meanwhile(it)
                }
            }
            return fetch(asked.sharp, LARGE_COVER_TIMEOUT_MS)?.also { Log.d(TAG, "The large cover from the network") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
            return null
        }
    }

    /**
     * The cover at [address] from the image host, or null if it did not come within [timeoutMs].
     * Not reported: offline it fails at every try, and the stand-in or a smaller cover is out.
     */
    private suspend fun fetch(address: String, timeoutMs: Long): Bitmap? {
        val result = withTimeoutOrNull(timeoutMs) { load(address, network = true) }
        (result as? SuccessResult)?.let { return it.image.toBitmap() }
        val why = (result as? ErrorResult)?.throwable?.javaClass?.simpleName ?: "no answer in ${timeoutMs / 1000} s"
        Log.d(TAG, "A cover not had from the network: $why")
        return null
    }

    /** The cover at [address] if the phone has it, read from there and never fetched. */
    private suspend fun fromPhone(address: String): Bitmap? =
        (load(address, network = false) as? SuccessResult)?.let { it.image.toBitmap() }

    private suspend fun load(address: String, network: Boolean): ImageResult = context.imageLoader.execute(
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
            .apply { if (!network) networkCachePolicy(CachePolicy.DISABLED) }
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

    /**
     * The picture inside the file at [path], decoded no larger than the system keeps, or null if
     * the file has none. Read here rather than through Coil, whose reader for files hands back the
     * app's logo for a file with no picture, as if it were the cover.
     */
    private fun embedded(path: String): Bitmap? {
        val picture = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.embeddedPicture
            } finally {
                retriever.release()
            }
        }.getOrNull() ?: return null
        return decodeSampled(picture, artPx)
    }
}

private const val TAG = "SessionCover"

/**
 * The longest side the platform keeps of a bitmap in a media session's metadata, in dp
 * (config_mediaMetadataBitmapMaxSize). MediaSession.setMetadata scales anything larger down to it
 * before the bitmap leaves the app, so more than this is never seen by anyone.
 */
private const val SESSION_ART_DP = 320

/**
 * How long a large cover that could not be fetched is left alone, while something lesser stands in
 * for it, before the system asking for the cover starts another download.
 */
private const val LARGE_COVER_RETRY_MS = 15_000L

/**
 * The waits before each try for a cover that could not be had, made with nobody asking: the lock
 * screen asks once a song. Soon at first, for a connection that was only slow, then less often, a
 * few times a song at most. The connection coming back is a try of its own (tryAgain).
 */
internal val COVER_RETRY_DELAYS_MS = listOf(3_000L, 10_000L, 30_000L, 60_000L, 120_000L)

/** How long the small cover and the large one are waited for over the network, each time. */
private const val SMALL_COVER_TIMEOUT_MS = 10_000L
private const val LARGE_COVER_TIMEOUT_MS = 30_000L

/**
 * The sizes the app's own screens ask for a cover at: in dp the mini player and the lists (48) and
 * the grids (96), in pixels the player's underlay (128, Thumbnail.kt) and the colours of the player
 * and the theme (100). Whichever of them was on screen is on the phone.
 */
private val SCREEN_COVER_DP = listOf(48, 96)
private val SCREEN_COVER_PX = listOf(128, 100)

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
 * what the system is given until the large one is there (see SessionBitmapLoader.cover).
 */
fun sessionArtwork(stored: String, px: Int): List<String> = coverAddresses(stored, artSizeBucket(px))

/**
 * Where else the cover may be on the phone when the large one is not, largest first: the address
 * the song is [stored] with, and the sizes the app's own screens ask for it at on a screen of this
 * [density]. Offline these are all there is, and a song played from a list or seen in the player
 * has its cover there at one size or another. The [sharp] one is never among them: it is asked for
 * first, on its own. An address the image host cannot be asked a size of (a video still, a file)
 * has only itself.
 */
internal fun coverOnPhone(stored: String, sharp: String, density: Float): List<String> {
    val sizes = SCREEN_COVER_DP.map { (it * density).roundToInt() } + SCREEN_COVER_PX
    return (listOf(stored) + sizes.map { stored.resize(it, it) })
        .distinct()
        .filter { it != sharp }
        .sortedByDescending(::coverSide)
}

/**
 * The mark that makes a cover's name a new one (see [nextCoverName]). A fragment: nothing that
 * fetches the address sends it, and [unmarkedCover] reads past it.
 */
private val COVER_MARK = Regex("#cover-\\d+$")

internal fun markedCover(address: String, n: Int): String = "$address#cover-$n"

/** [address] without the mark [markedCover] put on it, and otherwise as it is. */
internal fun unmarkedCover(address: String): String = address.replace(COVER_MARK, "")

/**
 * The name the session gives the playing song's cover when a better one is in hand: its large
 * address, or that address with a mark when it is [current], the name it goes by already. media3
 * hands the system a new picture only when the name changes (see SessionPlayer), and a cover with
 * one address (a video still, a file) or a second better one in the same song had nothing to
 * change to.
 */
internal fun nextCoverName(current: String, sharp: String, n: Int): String =
    if (sharp != current) sharp else markedCover(sharp, n)

/**
 * The largest power of two to divide a picture of [width] by [height] by, as BitmapFactory's
 * inSampleSize, that leaves its shorter side at least [px]. A picture inside a music file can be
 * thousands of pixels across, and the system keeps [px].
 */
internal fun sampleSize(width: Int, height: Int, px: Int): Int {
    var n = 1
    while (px > 0 && min(width, height) / (n * 2) >= px) n *= 2
    return n
}

/** [data] decoded no larger than [px] needs, or null if it is no picture. */
private fun decodeSampled(data: ByteArray, px: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, px) })
}.getOrNull()

/**
 * The share of the stand-in's side the music note takes. Small, because the phone's own media
 * player shows a wide band across the middle of a square cover, and the note must stay clear of
 * the title and the buttons drawn over it.
 */
private const val NO_COVER_NOTE = 0.26f

/**
 * What the system is shown for a song with no cover to be had: a plain square in a dark, quiet tone
 * of the phone's own colours with a music note in it, as the widget draws a song with no cover. It
 * stands in until the cover comes, and for good for a file with no picture.
 *
 * Never the app's logo. That was drawn here before, and on the lock screen it read as the song's
 * cover: a large dark red logo where the cover should have been (23 Oct 2026).
 */
internal fun noCover(context: Context, px: Int): Bitmap {
    val side = px.coerceIn(96, 1024)
    val (ground, ink) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getColor(android.R.color.system_neutral2_800) to context.getColor(android.R.color.system_neutral2_400)
    } else {
        0xFF2B2B30.toInt() to 0xFF77777E.toInt()
    }
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(ground)
    ContextCompat.getDrawable(context, R.drawable.music_note)?.mutate()?.let { note ->
        val n = (side * NO_COVER_NOTE).roundToInt()
        val at = (side - n) / 2
        note.setTint(ink)
        note.setBounds(at, at, at + n, at + n)
        note.draw(canvas)
    }
    return bitmap
}

/**
 * A cover as the system asks for it: its [addresses], best first (see [sessionArtwork]).
 *
 * Two of these are the same cover when their best address is the same. Once a better cover has
 * taken the place of what the system was given, the session names the cover by the large address,
 * or by that address with a mark (see SessionPlayer), and asking by that name has to find the cover
 * that is already in hand, not start another.
 */
internal class SessionCover(val addresses: List<String>) {
    /** The cover at the size the system keeps. */
    val sharp: String get() = addresses.first()

    /** The cover as the song is stored with it: the same address when there is only the one. */
    val stored: String get() = addresses.last()

    override fun equals(other: Any?): Boolean = other is SessionCover && other.sharp == sharp
    override fun hashCode(): Int = sharp.hashCode()
}

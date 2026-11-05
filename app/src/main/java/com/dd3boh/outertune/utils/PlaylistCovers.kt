/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.dd3boh.outertune.constants.Unreleased
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * The cover of a playlist of one's own (Unreleased.PLAYLIST_COVER): the first song's cover as it
 * always was, the cover of another song of the playlist, or a photo from the phone.
 *
 * What goes in PlaylistEntity.thumbnailUrl, the field a playlist's picture is drawn from wherever
 * it is drawn: nothing for the first song's cover, a song's cover as the song has it, or
 * "playlist-covers/<name>" for a photo. A photo is a file in the app's own storage, copied there
 * at [MAX_SIDE] px at most, so it stays when the photo is deleted from the phone. It is named
 * from the playlist and the time, a new name for each, so that the image cache never shows the
 * photo it replaced. The value holds the folder and the name and not the whole path, so that it
 * means the same after a backup is restored to another phone, or into another build of the app.
 */
object PlaylistCovers {
    private const val TAG = "PlaylistCovers"

    /** The folder in the app's files, and the start of what a photo is stored as. */
    const val DIR = "playlist-covers"

    /** The longer side of a kept photo, in pixels. */
    const val MAX_SIDE = 1024

    /** JPEG quality of a kept photo. */
    const val QUALITY = 90

    /**
     * Whether the choice is offered for a playlist: only a playlist of one's own, with no YouTube
     * playlist behind it, since a synced one has YouTube's picture written into the same field,
     * and only while [enabled] says so.
     */
    fun canChoose(browseId: String?, enabled: Boolean = Unreleased.PLAYLIST_COVER): Boolean = enabled && browseId == null

    /** Whether this build lets anyone pick a cover at all: the backup sheet offers their photos only then. */
    val offered: Boolean get() = canChoose(browseId = null)

    // ---- the size of a kept photo ----

    /**
     * The size a [width] by [height] picture is kept at: its longer side at most [maxSide], its
     * shape the same, and never larger than it came. Null for a picture that has no size.
     */
    fun fit(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || maxSide <= 0) return null
        if (max(width, height) <= maxSide) return width to height
        return if (width >= height) maxSide to shorter(height, width, maxSide) else shorter(width, height, maxSide) to maxSide
    }

    /** [side] of a picture whose longer side [longer] becomes [to], rounded, and at least a pixel. */
    private fun shorter(side: Int, longer: Int, to: Int): Int = max(1, ((side.toLong() * to + longer / 2) / longer).toInt())

    /**
     * The largest power of two to divide a [width] by [height] picture by, as BitmapFactory's
     * inSampleSize, that leaves its longer side at least [maxSide]. A photo is read at that size
     * and then brought down to [fit], so a 50 megapixel one never sits in memory whole.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var n = 1
        while (maxSide > 0 && max(width, height) / (n * 2) >= maxSide) n *= 2
        return n
    }

    /** How a photo is turned to stand as it was taken: a turn clockwise, then a mirror left to right if [mirror]. */
    data class Turn(val degrees: Int, val mirror: Boolean)

    /** The turn for an EXIF orientation tag (1 to 8). Anything else, and a photo with none, stands as it is. */
    fun turnFor(orientation: Int): Turn = when (orientation) {
        2 -> Turn(0, true)
        3 -> Turn(180, false)
        4 -> Turn(180, true)
        5 -> Turn(90, true)
        6 -> Turn(90, false)
        7 -> Turn(270, true)
        8 -> Turn(270, false)
        else -> Turn(0, false)
    }

    // ---- the files ----

    fun dir(base: File) = File(base, DIR)

    /** The name of a playlist's photo made at [millis]. */
    fun fileName(playlistId: String, millis: Long) = "$playlistId-$millis.jpg"

    /** What goes in the playlist's picture field for the photo [fileName]. */
    fun stored(fileName: String) = "$DIR/$fileName"

    /** Whether a picture field holds a kept photo, as opposed to nothing or a song's cover. */
    fun isPhoto(stored: String?): Boolean = stored != null && stored.startsWith("$DIR/")

    /**
     * The file a kept photo is in, found by its name alone: whatever came before the name is
     * dropped, so a value can never point outside the folder. Null when [stored] is not a kept photo.
     */
    fun fileOf(base: File, stored: String?): File? {
        if (!isPhoto(stored)) return null
        val name = stored!!.substringAfterLast('/')
        return if (name.isEmpty()) null else File(dir(base), name)
    }

    /** Every file the folder holds, for a backup. */
    fun files(base: File): List<File> = dir(base).listFiles { file -> file.isFile && !file.name.endsWith(".part") }?.sortedBy { it.name }.orEmpty()

    /**
     * Writes a photo for [playlistId] with [write] and returns its name, or null when it could not
     * be kept. Written beside its place and then renamed, so a cut leaves nothing half made. Blocking:
     * call it off the main thread.
     */
    fun keep(base: File, playlistId: String, millis: Long = System.currentTimeMillis(), write: (OutputStream) -> Boolean): String? {
        val name = fileName(playlistId, millis)
        val target = File(dir(base), name)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "$name.part")
        val done = runCatching { part.outputStream().use { write(it) } && part.renameTo(target) }.getOrDefault(false)
        if (!done) part.delete()
        return if (done) name else null
    }

    /**
     * Deletes the files kept for [playlistId], except the one named [except]: the photo it just
     * got, when this follows the write of the new one. Another playlist's are never touched, and
     * what a cut write left behind (.part) goes with them.
     */
    fun forget(base: File, playlistId: String, except: String? = null) {
        dir(base).listFiles { file -> file.name.startsWith("$playlistId-") && file.name != except }?.forEach { it.delete() }
    }

    // ---- in a backup ----

    /** The name a kept photo has in a backup's zip: its folder and its name, as it is stored. */
    fun entryName(fileName: String) = stored(fileName)

    /** The name of the photo a zip [entry] holds, or null when it is not one: a plain name in the folder, ending in .jpg, and nothing else. */
    fun backedUpName(entry: String): String? {
        val name = entry.removePrefix("$DIR/")
        return if (name != entry && Regex("""[A-Za-z0-9_.-]+\.jpg""").matches(name)) name else null
    }

    /** Puts every photo in [zip], each as an entry of its own. One that cannot be read is left out of the backup, and the backup is not left out. */
    fun addTo(zip: ZipOutputStream, base: File) {
        for (file in files(base)) {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            zip.putNextEntry(ZipEntry(entryName(file.name)))
            zip.write(bytes)
            zip.closeEntry()
        }
    }

    /**
     * Reads the photo that the zip [entry] holds into the folder [staging], where it waits until the
     * database it goes with is known good. False for an entry that is not a photo or could not be read.
     */
    fun stage(staging: File, entry: String, input: InputStream): Boolean {
        val name = backedUpName(entry) ?: return false
        staging.mkdirs()
        return runCatching { File(staging, name).outputStream().use { input.copyTo(it) }; true }.getOrDefault(false)
    }

    /**
     * Puts the photos waiting in [staging] in the folder of [base], in place of the ones there: the
     * database that was restored names these and none other. Nothing staged, nothing changed, so a
     * backup made before covers, or without any, leaves the folder as it is. A photo that cannot
     * be copied is left out, and the playlist that names it is drawn with the first song's cover.
     */
    fun place(staging: File, base: File) {
        val staged = staging.listFiles { file -> file.isFile }.orEmpty()
        if (staged.isNotEmpty()) {
            val dir = dir(base)
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            for (file in staged) runCatching { file.copyTo(File(dir, file.name), overwrite = true) }
        }
        staging.deleteRecursively()
    }

    // ---- what is drawn ----

    /**
     * What a playlist's picture is drawn from: a kept photo's file, or when that is gone, as if no
     * photo was chosen; otherwise the cover as stored, or when none is, the [automatic] one (the
     * first song's). Null when there is nothing to draw. [px] is the size it is drawn at.
     */
    fun model(base: File, stored: String?, automatic: String?, px: Int): Any? {
        if (isPhoto(stored)) {
            val file = fileOf(base, stored)
            return if (file != null && file.isFile) file else automatic?.let { getThumbnailModel(it, px, px) }
        }
        return (stored ?: automatic)?.let { getThumbnailModel(it, px, px) }
    }

    /** A song of a playlist as the sheet reads it: the cover its row shows, and its title. */
    data class CoverSource(val cover: String?, val title: String)

    /** A cover the sheet offers, under the title of the first song that has it. */
    data class Option(val cover: String, val title: String)

    /** The cover a song's own row shows: a file's own picture for a song on the phone, else the song's thumbnail. Null when it has none. */
    fun coverOf(thumbnailUrl: String?, isLocal: Boolean, localPath: String?): String? =
        (if (isLocal) thumbnailUrl ?: localPath else thumbnailUrl)?.takeIf { it.isNotBlank() }

    /** The sheet's strip: [songs] in the playlist's order, each picture once, under the first song that has it. */
    fun options(songs: List<CoverSource>): List<Option> =
        songs.mapNotNull { song -> song.cover?.let { Option(it, song.title) } }.distinctBy { it.cover }

    // ---- a photo from the phone ----

    /**
     * Reads the picture at [uri], stands it as it was taken, brings it down to [fit] and keeps it
     * as a JPEG for [playlistId]. Returns the name it is kept under, or null when it could not be
     * read (not a picture, or one the phone cannot decode). Blocking: call it off the main thread.
     */
    fun importPhoto(context: Context, playlistId: String, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (fit(bounds.outWidth, bounds.outHeight) == null) return@runCatching null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrNull() ?: 1
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
        } ?: return@runCatching null
        val (width, height) = fit(decoded.width, decoded.height) ?: return@runCatching null
        val scaled = if (width == decoded.width && height == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
        val turn = turnFor(orientation)
        val standing = if (turn == Turn(0, false)) scaled else {
            val matrix = Matrix().apply {
                postRotate(turn.degrees.toFloat())
                if (turn.mirror) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        }
        keep(context.filesDir, playlistId) { out -> standing.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
    }.onFailure { Log.w(TAG, "Could not use the photo: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
}

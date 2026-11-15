/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException

/**
 * A picture of an announcement or a question, named in the signed document by its SHA-256: taken from [url]
 * first, then from each of [mirrors], and shown only when its bytes match. So a picture can come from any host
 * that has it, and none of them can swap it.
 */
data class CheckedPicture(val url: String, val sha256: String, val mirrors: List<String>) {
    val sources: List<String> get() = (listOf(url) + mirrors).distinct()
}

/**
 * The pictures of the document in use, from its "pictures": the address as the entry gives it, its hash, and
 * the other hosts that have it.
 *
 * ```
 * "pictures": { "https://raw.githubusercontent.com/.../release-0.13.webp":
 *     { "sha256": "9f2c...", "mirrors": ["https://intertune.app/news/p/9f2c....webp", ...] } }
 * ```
 *
 * The screens ask [model] for what to load. A picture the document names by its hash is loaded by
 * [CheckedPictureFetcher]; any other is loaded from its address as before, which only a document on the phone
 * itself (the stand-in of a debug build) has: tools/announce/announce.py names every picture of a signed one.
 */
object CheckedPictures {
    const val TAG = "CheckedPictures"

    /** Under the app's cache folder: a picture checked once is not downloaded again. */
    const val FOLDER = "news-pictures"

    /** Larger than this, a picture is not taken: the notes ask for well under 1 MB. */
    const val MOST_BYTES = 8 * 1024 * 1024

    private const val MOST_MIRRORS = 8
    private val HEX = Regex("[0-9a-f]{64}")

    @Volatile
    var known: Map<String, CheckedPicture> = emptyMap()
        private set

    /** The pictures [document] names by their hash, keyed by the address as written and as the app reads it. */
    fun read(document: String?): Map<String, CheckedPicture> {
        val root = document?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return emptyMap()
        val pictures = root["pictures"] as? JsonObject ?: return emptyMap()
        val out = HashMap<String, CheckedPicture>()
        for ((written, value) in pictures) {
            val o = value as? JsonObject ?: continue
            val sha = (o["sha256"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { HEX.matches(it) } ?: continue
            val url = AnnouncementParser.webUrl(written) ?: continue
            val mirrors = (o["mirrors"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(AnnouncementParser::webUrl) }
                .distinct()
                .take(MOST_MIRRORS)
            val picture = CheckedPicture(url, sha, mirrors)
            out[written.trim()] = picture
            out[url] = picture
        }
        return out
    }

    /** Makes [document]'s pictures the ones known, as the document in use changes. */
    fun use(document: String?) {
        known = read(document)
    }

    /** What to give the picture loader for [url]: the picture checked by its hash when the document names it so. */
    fun model(url: String): Any = known[url.trim()] ?: url

    /**
     * The bytes of the first of [sources] that [download] gets and that have the hash [wanted]. A host that
     * fails or gives other bytes is passed over; each host asked gets a line in [notes].
     */
    fun firstMatching(sources: List<String>, wanted: String, download: (String) -> ByteArray?, notes: MutableList<String>): ByteArray? {
        for ((i, url) in sources.withIndex()) {
            val bytes = runCatching { download(url) }.getOrNull()
            val where = "picture ${wanted.take(12)} from host ${i + 1} (${SignedSources.host(url)})"
            when {
                bytes == null -> notes += "$where: not there"
                sha256(bytes) != wanted -> notes += "$where: not the picture the document names, passed over"
                else -> {
                    notes += "$where: matches its hash"
                    return bytes
                }
            }
        }
        return null
    }

    fun sha256(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    /** One picture's bytes from [url], at most [MOST_BYTES], or null. */
    internal fun download(url: String): ByteArray? {
        SignedSources.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MOST_BYTES) return null
            val source = body.source()
            if (source.request(MOST_BYTES + 1L)) return null
            return source.buffer.readByteArray()
        }
    }
}

/**
 * Coil's loader for a [CheckedPicture]: the copy already checked in [folder] when there is one, else the
 * first host whose bytes match, kept there. None matching is an error, and the screen leaves the picture out.
 */
class CheckedPictureFetcher(private val data: CheckedPicture, private val folder: File) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = File(folder, data.sha256)
        var where = DataSource.DISK
        if (!file.isFile) {
            val notes = mutableListOf<String>()
            val found = CheckedPictures.firstMatching(data.sources, data.sha256, CheckedPictures::download, notes)
            notes.forEach { Log.i(CheckedPictures.TAG, it) }
            val bytes = found ?: throw IOException("No host had the picture the document names")
            folder.mkdirs()
            val part = File(folder, "${data.sha256}.part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) file.writeBytes(bytes)
            where = DataSource.NETWORK
        }
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), null, where)
    }

    class Factory(private val folder: File) : Fetcher.Factory<CheckedPicture> {
        override fun create(data: CheckedPicture, options: Options, imageLoader: ImageLoader): Fetcher =
            CheckedPictureFetcher(data, folder)
    }
}

/** The memory cache knows a checked picture by its hash: the same picture from any host is one picture. */
class CheckedPictureKeyer : Keyer<CheckedPicture> {
    override fun key(data: CheckedPicture, options: Options): String = "checked:${data.sha256}"
}

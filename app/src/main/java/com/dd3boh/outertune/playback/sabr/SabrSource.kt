/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.sabr

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.dd3boh.outertune.sabr.SabrFile
import com.dd3boh.outertune.utils.StreamFamily
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The SABR starts the resolver has handed out, by the address it handed out for them:
 * `sabr://<videoId>?itag=<itag>&lmt=<lastModified>`, and `&for=download` after it for a
 * download's. The address carries nothing secret; what it stands for stays here. One that is no
 * longer here was forgotten (expired or refused), and the player then asks the resolver again, as
 * it does for an expired direct address.
 */
object SabrStarts {
    /**
     * [pacedByPlayer]: read at the pace of where the player is (SabrClock), and false for a
     * download's, which runs at its own clock (SabrOver.downloadClock).
     */
    class Start(
        val videoId: String,
        val itag: Int,
        val lastModified: Long,
        val file: SabrFile,
        val expiresAtMs: Long,
        val pacedByPlayer: Boolean = true,
    )

    private val starts = ConcurrentHashMap<String, Start>()

    /**
     * A download's start has an address of its own. Under one address, the second start of a
     * song replaced the first: the player's next open read a download's start, at four times its
     * own pace and with its clock asleep.
     */
    fun addressOf(videoId: String, itag: Int, lastModified: Long, forDownload: Boolean = false): String =
        "$SCHEME://$videoId?itag=$itag&lmt=$lastModified" + if (forDownload) "&for=download" else ""

    /** Keeps [start] and gives the address that stands for it. */
    fun put(start: Start): String =
        addressOf(start.videoId, start.itag, start.lastModified, forDownload = !start.pacedByPlayer).also { starts[it] = start }

    /** The start an address stands for, or null when it was forgotten or has run out. */
    fun forAddress(address: String, nowMs: Long = System.currentTimeMillis()): Start? {
        val start = starts[address] ?: return null
        if (nowMs >= start.expiresAtMs) {
            starts.remove(address, start)
            return null
        }
        return start
    }

    /** Every start of [videoId] goes, as the direct address does when the player fails on it. */
    fun forget(videoId: String) {
        starts.entries.removeIf { it.value.videoId == videoId }
    }

    const val SCHEME = "sabr"
}

/**
 * One read of a song over SABR, what [SabrDataSource] does without Media3's Uri, so that the JVM
 * tests reach it: the start an address stands for, and a reader of its file from the byte asked
 * for. While it is open, a read paced by the player keeps the player's clock running (SabrClock),
 * and a cut of [cutWith], the player's calls for the player's reads, ends it as it ends a direct
 * read: the read fails at once, and media3 opens it again at the same byte, on a new SABR stream.
 * Its failures are put the way the recovery reads a direct address's: see [SabrFailures].
 *
 * The DataSpec given to [open] is only carried by the exceptions it throws.
 */
@OptIn(UnstableApi::class)
class SabrRead(private val starts: SabrStarts = SabrStarts, private val cutWith: StreamFamily.Calls? = null) {
    private var start: SabrStarts.Start? = null
    private var reader: SabrFile.Reader? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var spec: DataSpec? = null

    /** Whether this read counts among the clock's open ones, until its close. */
    private var clocked = false

    /** Its place among the reads [cutWith] cuts, while it is open. */
    private var cuttable: java.io.Closeable? = null

    /** Whether it was cut, from the main thread: the read that fails then says so. */
    @Volatile
    private var cut = false

    /** The song read, while the read is open. */
    val videoId: String? get() = start?.videoId

    /** Opens [address] at [position] for [length] bytes, C.LENGTH_UNSET for the rest; returns how many will come. */
    fun open(address: String, position: Long, length: Long, spec: DataSpec): Long {
        this.spec = spec
        // Run out or forgotten: as an address that has run out, so the route changes at once.
        val found = starts.forAddress(address) ?: throw SabrFailures.startGone(spec)
        start = found
        val size = found.file.contentLength
        if (position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        // Before the stream starts, which reads the clock at once.
        if (found.pacedByPlayer && !clocked) {
            clocked = true
            SabrClock.opened()
        }
        val opened = found.file.open(position)
        reader = opened
        cut = false
        cuttable = cutWith?.cutAlongside(
            java.io.Closeable {
                cut = true
                opened.close()
            }
        )
        android.util.Log.i("SabrDataSource", "[${found.videoId}] SABR read from byte $position of $size")
        val left = size - position
        bytesRemaining = if (length != C.LENGTH_UNSET.toLong()) minOf(length, left) else left
        return bytesRemaining
    }

    /** Up to [length] bytes into [buffer], or C.RESULT_END_OF_INPUT at the end of what was asked for. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        // As they are when the read begins: a close from another thread cannot take them from under it.
        val reading = checkNotNull(reader) { "read before open" }
        val spec = checkNotNull(this.spec) { "read before open" }
        val song = start
        val asked = if (bytesRemaining > 0) minOf(length.toLong(), bytesRemaining).toInt() else length
        val n = try {
            reading.read(buffer, offset, asked)
        } catch (e: IOException) {
            // Cut by the stall watch: a failed read, as a cancelled call's is.
            val failure = if (cut) IOException("SABR read cut: no byte came", e) else e
            // A seek or a stop interrupts a read on purpose: not a failure.
            if (failure !is java.io.InterruptedIOException || failure is java.net.SocketTimeoutException) {
                android.util.Log.w("SabrDataSource", "[${song?.videoId}] SABR read failed", failure)
            }
            throw SabrFailures.asPlayerSees(failure, spec, HttpDataSource.HttpDataSourceException.TYPE_READ) { song?.let { starts.forget(it.videoId) } }
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining > 0) bytesRemaining -= n
        return n
    }

    fun close() {
        try {
            cuttable?.close()
            cuttable = null
            reader?.close()
        } finally {
            reader = null
            start = null
            spec = null
            if (clocked) {
                clocked = false
                SabrClock.closed()
            }
        }
    }
}

/**
 * A song's bytes over SABR, for the player, its cache, downloads and the cast server alike: the
 * file from the byte asked for, read by [SabrRead], and cut with [cutWith]. Media3's side of it only.
 */
@OptIn(UnstableApi::class)
class SabrDataSource(starts: SabrStarts = SabrStarts, cutWith: StreamFamily.Calls? = null) : BaseDataSource(/* isNetwork = */ true) {
    private val read = SabrRead(starts, cutWith)
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val left = read.open(dataSpec.uri.toString(), dataSpec.position, dataSpec.length, dataSpec)
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = read.read(buffer, offset, length)
        if (n > 0) bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            read.close()
        } finally {
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/**
 * The upstream every reader of a song goes through: `sabr:` addresses to [SabrDataSource], every
 * other address to [http] as before. The player's cache chain, downloads and the cast server all
 * build on it, so a song started over SABR is cached, downloaded and cast like any other.
 */
@OptIn(UnstableApi::class)
class SabrOrHttp private constructor(private val http: DataSource, private val sabr: DataSource) : DataSource {
    private var current: DataSource? = null

    /**
     * [cutWith]: the calls [http] fetches with. The stall watch's cut of the player's calls then
     * ends the player's SABR reads too, and only theirs: a download's, a lyrics clip's and the
     * cast server's are on calls nothing cuts.
     */
    class Factory(private val http: DataSource.Factory, private val cutWith: StreamFamily.Calls? = null) : DataSource.Factory {
        override fun createDataSource(): DataSource = SabrOrHttp(http.createDataSource(), SabrDataSource(cutWith = cutWith))
    }

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        sabr.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == SabrStarts.SCHEME) sabr else http
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(current) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}

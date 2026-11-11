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
import com.dd3boh.outertune.utils.RouteTimes
import com.dd3boh.outertune.utils.StreamFamily
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
     * download's, which runs at its own clock (SabrOver.downloadClock). [songId]: the song the
     * player asked for, which a stand-in's start is not: the queue knows songs by that id
     * (SabrAhead). [networkChanges]: how many changes of network SabrOver had seen when it was
     * made, so that a walk after another one makes a start of its own.
     */
    class Start(
        val videoId: String,
        val itag: Int,
        val lastModified: Long,
        val file: SabrFile,
        val expiresAtMs: Long,
        val pacedByPlayer: Boolean = true,
        val songId: String = videoId,
        val networkChanges: Int = 0,
    ) {
        /** Whether its refusal was counted: two reads of one start can both meet it. See [refused]. */
        internal val refusalCounted = AtomicBoolean(false)
    }

    private val starts = ConcurrentHashMap<String, Start>()

    /** How many starts of a song in a row YouTube has turned down, and when the last one was. */
    private class Refused(val inARow: Int, val lastAt: Long)

    /** By the song the player asked for. Guarded by itself. */
    private val refusals = HashMap<String, Refused>()

    /**
     * YouTube turned [start] down, at [nowMs]: it goes with every other start of its video, as
     * [forget] has it, and the refusal is counted for the song the player asked for.
     *
     * One refusal is answered with a new start, made from a new answer by the walk that follows:
     * a start can age, and YouTube asks for a fresh player response of one that has. When the
     * start made after it is turned down as well, before it brought the player any music and
     * within [RouteTimes.SABR_REFUSED_REST_MS], that is YouTube's word on the session and not on
     * the start, and a third asked the same way gets it again. Nothing told those walks apart, a
     * start over SABR having no route to change: the player's four loads and its three quiet
     * retries each walked the whole chain to this rung, sixteen walks for one song, which was
     * then given up as "no answer from YouTube's servers" (issue #138, signed in behind a VPN).
     * So the song rests: see [restsUntil].
     */
    fun refused(start: Start, nowMs: Long = System.currentTimeMillis()) {
        forget(start.videoId)
        if (!start.refusalCounted.compareAndSet(false, true)) return
        synchronized(refusals) {
            // A refusal as old as the rest is no longer part of a row, nor is one a clock set back left ahead.
            refusals.values.removeIf { nowMs < it.lastAt || nowMs - it.lastAt >= RouteTimes.SABR_REFUSED_REST_MS }
            refusals[start.songId] = Refused((refusals[start.songId]?.inARow ?: 0) + 1, nowMs)
        }
    }

    /** A start of [songId] brought the player music: what was refused before it no longer counts. */
    fun served(songId: String) {
        synchronized(refusals) { refusals.remove(songId) }
    }

    /**
     * Until when (wall clock) no new start of [songId] is made, or null when one may be: YouTube
     * turned down its last [REFUSALS_IN_A_ROW] starts in a row, the last of them less than
     * [RouteTimes.SABR_REFUSED_REST_MS] ago. The walk then ends as it does without SABR, in
     * YouTube's own words, which the player does not try again (SabrOver.over).
     */
    fun restsUntil(songId: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val seen = synchronized(refusals) { refusals[songId] } ?: return null
        val end = seen.lastAt + RouteTimes.SABR_REFUSED_REST_MS
        return end.takeIf { seen.inARow >= REFUSALS_IN_A_ROW && nowMs >= seen.lastAt && nowMs < end }
    }

    /** For the tests, each of which starts as a new process does. */
    internal fun forgetRefusals() {
        synchronized(refusals) { refusals.clear() }
    }

    /** How many starts of a song in a row YouTube turns down before the song rests. */
    const val REFUSALS_IN_A_ROW = 2

    /**
     * A download's start has an address of its own. Under one address, the second start of a
     * song replaced the first: the player's next open read a download's start, at four times its
     * own pace and with its clock asleep.
     */
    fun addressOf(videoId: String, itag: Int, lastModified: Long, forDownload: Boolean = false): String =
        "$SCHEME://$videoId?itag=$itag&lmt=$lastModified" + if (forDownload) "&for=download" else ""

    /**
     * Keeps [start] and gives the address that stands for it. A start it replaces lets go of what
     * it read ahead: nothing finds it by its address any more.
     */
    fun put(start: Start): String =
        addressOf(start.videoId, start.itag, start.lastModified, forDownload = !start.pacedByPlayer).also { address ->
            starts.put(address, start)?.takeIf { it !== start }?.file?.dropAhead(", replaced by a new start")
        }

    /** The start an address stands for, or null when it was forgotten or has run out. */
    fun forAddress(address: String, nowMs: Long = System.currentTimeMillis()): Start? {
        val start = starts[address] ?: return null
        if (nowMs >= start.expiresAtMs) {
            if (starts.remove(address, start)) start.file.dropAhead(", its start ran out")
            return null
        }
        return start
    }

    /** Every start of [videoId] goes, as the direct address does when the player fails on it. */
    fun forget(videoId: String) {
        val gone = starts.values.filter { it.videoId == videoId }
        starts.entries.removeIf { it.value.videoId == videoId }
        gone.forEach { it.file.dropAhead(", its start was forgotten") }
    }

    /**
     * Every song but [songs] lets go of what was read ahead of it: the queue has moved on from
     * them (SabrAhead). Their starts stay, and a read of one opened later reads ahead again.
     */
    fun dropAheadExcept(songs: Set<String>) {
        starts.values.filter { it.songId !in songs }.forEach { it.file.dropAhead(", the queue moved on") }
    }

    /** Whether a start of [songId] has the rest of the song held, read ahead to its end. */
    fun restHeld(songId: String): Boolean = starts.values.any { it.songId == songId && it.file.restHeld }

    /**
     * The address of a live start of [songId] that holds the byte at [position], read ahead: the
     * song plays from what is held with no network, even once its address was forgotten (a change
     * of network forgets every one). Only a start of the format [itag] and [lastModified], the one
     * the player was given for the song: bytes at a position are that file's only. Null when none.
     */
    fun heldAt(songId: String, position: Long, itag: Int, lastModified: Long?, nowMs: Long = System.currentTimeMillis()): String? =
        starts.entries.firstOrNull { (_, start) ->
            start.songId == songId && start.pacedByPlayer && start.itag == itag &&
                (lastModified == null || lastModified == 0L || start.lastModified == lastModified) &&
                nowMs < start.expiresAtMs && start.file.holds(position)
        }?.key

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

    /** How many bytes this read has handed over since it was opened, counted up to [SERVED_BYTES]. */
    private var handedOver = 0L

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
        // With its length: a part between two cached ones is read ahead only as far as it goes.
        val opened = found.file.open(position, length)
        reader = opened
        cut = false
        handedOver = 0
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
            // A refusal forgets the start and is counted for the song: see SabrStarts.refused.
            throw SabrFailures.asPlayerSees(failure, spec, HttpDataSource.HttpDataSourceException.TYPE_READ) { song?.let { starts.refused(it) } }
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining > 0) bytesRemaining -= n
        // Music has come from this start, more than a file's first piece: the refusals before it
        // were not YouTube's word on the session, and no longer count (SabrStarts.served).
        if (handedOver < SERVED_BYTES) {
            handedOver += n
            if (handedOver >= SERVED_BYTES) song?.let { starts.served(it.songId) }
        }
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

    companion object {
        /**
         * How much of a song one read has to hand over before its start counts as having brought
         * music: a few seconds of it, and well past the first piece of a file, which holds none.
         */
        const val SERVED_BYTES = 64 * 1024L
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

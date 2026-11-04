/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dd3boh.outertune.App
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.utils.walkSongs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a failed send comes to (see [Sent]), from what YouTube answered or did not.
 *
 * Not now, kept and nothing after it sent: no answer at all (offline, a network that does not reach
 * YouTube), 401 (a sign-in that lapsed, WhyStopped), 408, 429 (throttled), YouTube's own errors
 * (5xx), and the app's gate (Read only or signed out). Refused, forgotten: YouTube's answer that the
 * request itself is wrong and will stay so (400, 404, 409, 410, a STATUS_FAILED edit), as a playlist
 * deleted elsewhere or a song it holds already. Anything else, a 403 among them, is counted and
 * given up after a few tries, so one change YouTube will never take cannot hold up the rest for good.
 */
object PendingOutcome {
    fun of(failure: Throwable): Sent {
        if (failure is CancellationException) throw failure
        if (failure is YouTube.AccountWriteRefused) return Sent.NotNow
        if (failure is YouTube.PlaylistEditRefused) return Sent.Refused
        val status = statusOf(failure)
        return when {
            status == null -> if (noAnswer(failure)) Sent.NotNow else Sent.Failed
            status == 401 || status == 408 || status == 429 || status >= 500 -> Sent.NotNow
            status == 400 || status == 404 || status == 409 || status == 410 -> Sent.Refused
            else -> Sent.Failed
        }
    }

    private fun statusOf(failure: Throwable): Int? {
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is ResponseException) return t.response.status.value
            t = t.cause
        }
        return null
    }

    private fun noAnswer(failure: Throwable): Boolean {
        if (WhyStopped.kindOf(failure) == WhyStopped.Kind.NO_ANSWER) return true
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is IOException) return true
            t = t.cause
        }
        return false
    }
}

/**
 * Sends one change to the account and says what became of it. An add's answer names the new
 * entry, which is written onto the row it made here, so that a removal or a move of it later can
 * be sent. A removal or a move of a row whose entry is not known (added by a version that threw the
 * answer away, or saved from a playlist's page) finds it in the playlist as YouTube has it: an
 * entry of the same song that no other row here holds.
 */
class YouTubeSender(private val database: MusicDatabase) {

    suspend fun send(pending: Pending): Sent = when (val c = pending.change) {
        is Change.Like -> done(pending, YouTube.likeVideo(c.videoId, c.liked))
        is Change.SavePlaylist -> done(pending, YouTube.likePlaylist(c.browseId, c.saved))
        is Change.SaveAlbum -> done(pending, YouTube.likePlaylist(c.playlistId, c.saved))
        is Change.Follow -> follow(pending, c)
        is Change.Rename -> done(pending, YouTube.renamePlaylist(c.browseId, c.name))
        is Change.Delete -> done(pending, YouTube.deletePlaylist(c.browseId))
        is Change.Add -> YouTube.addToPlaylist(c.browseId, c.videoId, c.duplicate).fold(
            onSuccess = { setVideoId ->
                if (setVideoId != null) {
                    runCatching { database.learnSetVideoId(c.mapId, c.videoId, setVideoId) }
                        .onFailure { Log.w(TAG, "Could not note the id YouTube gave an added song", it) }
                }
                Sent.Done(setVideoId)
            },
            onFailure = { failed(pending, it) },
        )
        is Change.Remove -> when (val entry = entryOf(pending, c.browseId, c.mapId, c.videoId, c.setVideoId)) {
            is Entry.Known -> done(pending, YouTube.removeFromPlaylist(c.browseId, c.videoId, entry.setVideoId))
            // YouTube does not hold the song there: nothing to take out.
            Entry.Absent -> Sent.Done()
            is Entry.Unread -> entry.outcome
        }
        is Change.Move -> {
            val moved = entryOf(pending, c.browseId, c.mapId, c.videoId, c.setVideoId)
            val before = if (moved is Entry.Unread) moved else entryOf(pending, c.browseId, c.beforeMapId, c.beforeVideoId, c.beforeSetVideoId)
            when {
                moved is Entry.Unread -> moved.outcome
                before is Entry.Unread -> before.outcome
                moved is Entry.Known && before is Entry.Known -> done(pending, YouTube.moveSongPlaylist(c.browseId, moved.setVideoId, before.setVideoId))
                // One of the two is not on YouTube: the order cannot be said there.
                else -> Sent.Refused
            }
        }
    }

    private suspend fun follow(pending: Pending, c: Change.Follow): Sent {
        val channelId = c.channelId ?: YouTube.artist(c.artistId).fold(
            onSuccess = { it.artist.channelId ?: return Sent.Refused },
            onFailure = { return failed(pending, it) },
        )
        return done(pending, YouTube.subscribeChannel(channelId, c.followed))
    }

    private fun done(pending: Pending, result: Result<*>): Sent =
        result.fold(onSuccess = { Sent.Done() }, onFailure = { failed(pending, it) })

    /** What [failure] comes to, said in the log without the addresses a failed request can name. */
    private fun failed(pending: Pending, failure: Throwable): Sent = PendingOutcome.of(failure).also { outcome ->
        Log.w(TAG, "Could not send a ${PendingStore.kindOf(pending.change)} to YouTube Music ($outcome)", ErrorText.forLog(failure))
    }

    private sealed interface Entry {
        data class Known(val setVideoId: String) : Entry
        data object Absent : Entry
        data class Unread(val outcome: Sent) : Entry
    }

    /** The entry on YouTube that the row [mapId] of [videoId] is. */
    private suspend fun entryOf(pending: Pending, browseId: String, mapId: Int, videoId: String, setVideoId: String?): Entry {
        setVideoId?.let { return Entry.Known(it) }
        runCatching { database.playlistSongMapById(mapId) }.getOrNull()
            ?.takeIf { it.songId == videoId }?.setVideoId?.let { return Entry.Known(it) }
        val page = YouTube.playlist(browseId).getOrElse { return Entry.Unread(failed(pending, it)) }
        val walked = page.walkSongs()
        if (!walked.complete) return Entry.Unread(Sent.NotNow)
        val held = runCatching { database.setVideoIdsHeld(browseId).toHashSet() }.getOrElse { return Entry.Unread(Sent.Failed) }
        return PendingRules.entryFor(videoId, walked.items.map { it.id to it.setVideoId }, held)
            ?.let { Entry.Known(it) } ?: Entry.Absent
    }

    companion object {
        private const val TAG = "PendingChanges"
    }
}

/**
 * The changes made in the app for the YouTube Music account, kept until the account has them
 * ([PendingStore]) and sent at once, at every sync before the account is read, and by
 * [PendingChangesWorker] once the phone has a connection again.
 *
 * Only with sync in Read and write and someone signed in: "Read only" never writes to the account,
 * and with nobody signed in there is no account to write to, so nothing is kept for either and the
 * sync goes on as it always has. What was kept in Read and write and is still waiting after a switch
 * to Read only stays kept and is not sent until Read and write is back.
 */
object PendingChanges {
    private const val TAG = "PendingChanges"
    private const val FILE = "account_changes.json"

    /** Between two tries that met no connection or a refusal of the moment, for anything but the job. */
    private const val PAUSE_AFTER_NOT_NOW_MS = 30_000L

    /** In the order they were made: one thread, one change after another. */
    private val keeping = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val sending = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastNotNowAt = 0L

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun database(): MusicDatabase
    }

    private val app: App? get() = runCatching { App.instance }.getOrNull()

    private val store: PendingStore? by lazy {
        app?.let { PendingStore(File(it.noBackupFilesDir, FILE)) }
    }

    /** Whose account a change made now is for: the one signed in. See [LikedSync.accountKey]. */
    fun accountOf(context: Context): String? =
        LikedSync.accountKey(context.dataStore[DataSyncIdKey], context.dataStore[AccountEmailKey])

    /** Keeps [change] for the account signed in and sends it, when the account may be written to. */
    fun record(change: Change) {
        val app = app ?: return
        if (!app.mayPushToYouTube()) return
        val store = store ?: return
        keeping.launch {
            runCatching { store.put(accountOf(app), change) }
                .onFailure { Log.w(TAG, "Could not keep a ${PendingStore.kindOf(change)}", it) }
            kick(app)
        }
    }

    /** A send waiting to start, which will see every change kept before it starts. */
    private val kickWaiting = AtomicBoolean(false)

    /**
     * Sends soon what was just kept. One send waits at most, behind the one running: a hundred songs
     * added in a row are one send, not a hundred, and after a try that met no connection the job,
     * which waits for one, takes over (see [send]'s patience).
     */
    private fun kick(context: Context) {
        if (!kickWaiting.compareAndSet(false, true)) return
        sending.launch {
            kickWaiting.set(false)
            runCatching { send(context) }.onFailure { Log.w(TAG, "Could not send the changes waiting", it) }
        }
    }

    /** The song's like, as the heart leaves it: [liked] the state it is in now. */
    fun liked(song: SongEntity, liked: Boolean) {
        if (song.isLocal) return
        record(Change.Like(song.id, liked))
    }

    /** A song taken out of the library here, as it was before: a like it had goes with it, on the account too. */
    fun tookOutOfLibrary(song: SongEntity) {
        PendingRules.outOfLibrary(song.id, song.isLocal, song.liked)?.let(::record)
    }

    /** The playlist's heart: [saved] as it is now. Not a playlist of the phone's own. */
    fun savedPlaylist(playlist: PlaylistEntity, saved: Boolean) {
        val browseId = playlist.browseId?.removePrefix("VL") ?: return
        if (playlist.isLocal) return
        record(Change.SavePlaylist(browseId, saved))
    }

    fun savedAlbum(album: AlbumEntity, saved: Boolean) {
        val playlistId = album.playlistId ?: return
        if (album.isLocal) return
        record(Change.SaveAlbum(album.id, playlistId, saved))
    }

    fun followed(artist: ArtistEntity, followed: Boolean) {
        if (artist.isLocal) return
        record(Change.Follow(artist.id, artist.channelId, followed))
    }

    fun renamed(playlist: PlaylistEntity, name: String) {
        val browseId = playlist.browseId?.removePrefix("VL") ?: return
        if (playlist.isLocal) return
        record(Change.Rename(browseId, name))
    }

    /** Deleted here: one of the account's own playlists is deleted there too, anyone else's taken out of its library. */
    fun deleted(playlist: PlaylistEntity) {
        val browseId = playlist.browseId?.removePrefix("VL") ?: return
        if (playlist.isLocal) return
        record(if (playlist.isEditable) Change.Delete(browseId) else Change.SavePlaylist(browseId, false))
    }

    /** Rows written into [playlist]: each a song from YouTube (not [com.dd3boh.outertune.db.daos.AddedRow.local]) goes to its playlist there. */
    fun added(playlist: PlaylistEntity, rows: List<com.dd3boh.outertune.db.daos.AddedRow>) {
        val browseId = playlist.browseId?.removePrefix("VL") ?: return
        if (playlist.isLocal) return
        rows.filterNot { it.local }.forEach { record(Change.Add(browseId, it.map.id, it.map.songId, it.duplicate)) }
    }

    /** Rows taken out of [playlist], as they were. */
    fun removed(playlist: PlaylistEntity, maps: List<PlaylistSongMap>) {
        val browseId = playlist.browseId?.removePrefix("VL") ?: return
        if (playlist.isLocal) return
        maps.forEach { record(Change.Remove(browseId, it.id, it.songId, it.setVideoId)) }
    }

    /** Rows put in front of others in [playlist], as [youTubeMovesAfterDrag] gives them. */
    fun moved(playlist: PlaylistEntity, moves: List<Pair<PlaylistSongMap, PlaylistSongMap>>) {
        val browseId = playlist.browseId?.removePrefix("VL") ?: return
        if (playlist.isLocal) return
        moves.forEach { (row, before) ->
            record(Change.Move(browseId, row.id, row.songId, row.setVideoId, before.id, before.songId, before.setVideoId))
        }
    }

    /**
     * Sends what waits for the account signed in. [Flushed.DONE] when nothing of its is left,
     * [Flushed.LEFT] when something waits for a better moment (and the job will try again), and
     * [Flushed.BLOCKED] when the account may not be written to now. [patient]: the job, which tries
     * whatever happened a moment ago. A change just made and a sync do not try again within half a
     * minute of a try that met no connection or failed, so that six kinds of sync, or a hundred
     * changes made offline, do not each wait out a timeout: the job is waiting for a connection.
     */
    suspend fun send(context: Context, patient: Boolean = false): Flushed {
        val store = store ?: return Flushed.DONE
        val account = accountOf(context)
        if (!context.mayPushToYouTube()) return if (store.of(account).isEmpty()) Flushed.DONE else Flushed.BLOCKED
        if (store.of(account).isEmpty()) return Flushed.DONE
        if (!patient && System.currentTimeMillis() - lastNotNowAt < PAUSE_AFTER_NOT_NOW_MS) return Flushed.LEFT
        val sender = YouTubeSender(EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).database())
        val result = store.send(account) { sender.send(it) }
        if (result == PendingStore.Flush.LEFT) {
            lastNotNowAt = System.currentTimeMillis()
            PendingChangesWorker.schedule(context)
            return Flushed.LEFT
        }
        return Flushed.DONE
    }

    enum class Flushed { DONE, LEFT, BLOCKED }

    /**
     * For a sync about to read the account: what waits is sent first, as sync engines push before
     * they pull, and what is still waiting after that, with what was sent a moment ago and may not
     * show in the read yet (PendingStore.withRecent), is what the sync leaves alone.
     */
    suspend fun sendThenWaiting(context: Context): Waiting {
        val store = store ?: return Waiting.NONE
        runCatching { send(context) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Could not send the changes waiting", it) }
        return Waiting(store.withRecent(accountOf(context)))
    }

    /** What waits for the account signed in, without sending: for a sync already in a database transaction. */
    fun waitingNow(context: Context): Waiting = store?.let { Waiting(it.now(accountOf(context))) } ?: Waiting.NONE

    /** At the app's start: what is still waiting is sent once there is a connection. */
    fun atStart(context: Context) {
        val store = store ?: return
        sending.launch {
            if (store.of(accountOf(context)).isNotEmpty() && context.mayPushToYouTube()) PendingChangesWorker.schedule(context, soon = true)
        }
    }

    /** After a restore: the library in place is not the one these changes were made to. */
    suspend fun forgetAll() {
        store?.forgetAll()
    }
}

/**
 * Sends the changes waiting for the account once the phone has a connection, and tries again later
 * when YouTube says not now. Same shape as RecogniseLaterWorker: no @HiltWorker, so WorkManager's
 * default factory builds it, and WorkManager keeps it through a restart of the phone.
 */
class PendingChangesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (PendingChanges.send(applicationContext, patient = true)) {
        PendingChanges.Flushed.LEFT -> Result.retry()
        // Read only, or signed out: they wait, and the next change, start or sync looks again.
        PendingChanges.Flushed.BLOCKED, PendingChanges.Flushed.DONE -> Result.success()
    }

    companion object {
        private const val WORK_NAME = "account_changes"

        /** Once the phone has a connection; [soon] at the app's start, else after half a minute. */
        fun schedule(context: Context, soon: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<PendingChangesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(if (soon) 5L else 30L, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Log.w("PendingChanges", "Could not schedule the changes waiting for the account", it) }
        }
    }
}

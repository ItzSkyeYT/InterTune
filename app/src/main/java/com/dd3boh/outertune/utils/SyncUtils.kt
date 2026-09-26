/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.LastAlbumSyncKey
import com.dd3boh.outertune.constants.LastArtistSyncKey
import com.dd3boh.outertune.constants.LastFullSyncKey
import com.dd3boh.outertune.constants.LastLibSongSyncKey
import com.dd3boh.outertune.constants.LastLikeSongSyncKey
import com.dd3boh.outertune.constants.LastPlaylistSyncKey
import com.dd3boh.outertune.constants.LastRecentActivitySyncKey
import com.dd3boh.outertune.constants.SYNC_CD
import com.dd3boh.outertune.constants.SyncConflictResolution
import com.dd3boh.outertune.constants.SyncContent
import com.dd3boh.outertune.constants.YtmSyncConflictKey
import com.dd3boh.outertune.constants.YtmSyncContentKey
import com.dd3boh.outertune.constants.decodeSyncString
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.isAutoSyncEnabled
import com.dd3boh.outertune.extensions.isInternetConnected
import com.dd3boh.outertune.extensions.isUserLoggedIn
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.utils.Walked
import com.zionhuang.innertube.utils.walkItems
import com.zionhuang.innertube.utils.walkSongs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton class for syncing local data from remote YouTube Music
 */
@Singleton
class SyncUtils @Inject constructor(
    val database: MusicDatabase,
    private val downloadUtil: DownloadUtil,
    @ApplicationContext private val context: Context
) {
    private val TAG = "SyncUtils"

    private val scope =  CoroutineScope(syncCoroutine)

    private val _isSyncingRemoteLikedSongs = MutableStateFlow(false)
    private val _isSyncingRemoteSongs = MutableStateFlow(false)
    private val _isSyncingRemoteAlbums = MutableStateFlow(false)
    private val _isSyncingRemoteArtists = MutableStateFlow(false)
    private val _isSyncingRemotePlaylists = MutableStateFlow(false)
    private val _isSyncingRecentActivity = MutableStateFlow(false)

    val isSyncingRemoteLikedSongs: StateFlow<Boolean> = _isSyncingRemoteLikedSongs.asStateFlow()
    val isSyncingRemoteSongs: StateFlow<Boolean> = _isSyncingRemoteSongs.asStateFlow()
    val isSyncingRemoteAlbums: StateFlow<Boolean> = _isSyncingRemoteAlbums.asStateFlow()
    val isSyncingRemoteArtists: StateFlow<Boolean> = _isSyncingRemoteArtists.asStateFlow()
    val isSyncingRemotePlaylists: StateFlow<Boolean> = _isSyncingRemotePlaylists.asStateFlow()
    val isSyncingRecentActivity: StateFlow<Boolean> = _isSyncingRecentActivity.asStateFlow()

    companion object {
        const val DEFAULT_SYNC_CONTENT = "ARPLSC"

        /**
         * How recently a song can have been liked before sync refuses to unlike it.
         *
         * Long enough to cover a weekend where the push to YouTube never went out, short enough
         * that a genuine unlike made on another device still lands within a couple of days.
         */
        const val UNLIKE_GRACE_DAYS = 3L

        /** Under filesDir: the LM ids seen at each account's last complete liked sync. */
        const val LIKED_SNAPSHOT_DIR = "liked_sync"
    }

    /**
     * Every enabled kind of content. [bypassCd] is someone asking for it (the Sync now button, a
     * pull on the Library's All tab): that needs only a signed-in account, and runs whatever the
     * cooldowns say. It used to return at once when "Automatically sync" was off, and to call each
     * kind without the bypass, so the button mostly did nothing and then said "Sync complete".
     *
     * On IO whoever calls it: the kinds below still block their thread on parts of their work,
     * and the button ran all of it on the main thread.
     */
    suspend fun tryAutoSync(bypassCd: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        autoSync(bypassCd)
    }

    private suspend fun autoSync(bypassCd: Boolean): SyncResult {
        if (bypassCd) {
            if (!context.isUserLoggedIn()) return SyncResult.NOTHING
        } else if (!context.isAutoSyncEnabled()) {
            return SyncResult.NOTHING
        }
        // bypassCd is the user pressing Sync now, which should always try. An automatic sync is
        // the "work nobody asked for" the throttle exists to drop.
        if (!bypassCd && Throttle.isBlocked) {
            Log.d(TAG, "Skipping auto sync, backing off")
            return SyncResult.NOTHING
        }
        Log.d(TAG, "Starting auto sync job")
        if (!bypassCd) {
            // Default 0, not now: an account that has never synced should sync straight away
            // rather than sit out a cooldown it never earned.
            val lastSync = context.dataStore.get(LastFullSyncKey, 0L)
            val currentTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            val elapsed = currentTime - lastSync
            if (elapsed < SYNC_CD) {
                Log.d(TAG, "Aborting auto sync. ${(SYNC_CD - elapsed) / 60} minutes until eligible")
                return SyncResult.NOTHING
            }
        }

        val results = listOf(
            syncRemoteLikedSongs(bypassCd),
            syncRemoteSongs(bypassCd),
            syncRemoteAlbums(bypassCd),
            syncRemoteArtists(bypassCd),
            syncRemotePlaylists(bypassCd),
        )
        context.dataStore.edit { settings ->
            settings[LastFullSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        }
        return SyncResult.combine(results)
    }

    private fun checkEnabled(item: SyncContent): Boolean {
        return decodeSyncString(context.dataStore.get(YtmSyncContentKey, DEFAULT_SYNC_CONTENT)).contains(item)
    }

    private fun checkPartialSyncEligibility(key: Preferences.Key<Long>): Boolean {
        val lastSync = context.dataStore.get(key, 0L)
        val currentTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        val elapsed = currentTime - lastSync
        if (elapsed < SYNC_CD) {
            Log.d(TAG, "Aborting auto sync. ${(SYNC_CD - elapsed) / 60} minutes until eligible")
            return false
        }
        return true
    }

    private fun checkOverwrite(item: SyncConflictResolution): Boolean {
        return context.dataStore.get(YtmSyncConflictKey, SyncConflictResolution.ADD_ONLY.name)
            .toEnum(defaultValue = SyncConflictResolution.ADD_ONLY) == item
    }

    /**
     * Like single song
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun likeSong(s: SongEntity) {
        scope.launch {
            if (!s.isLocal && context.mayPushToYouTube()) YouTube.likeVideo(s.id, s.liked)
        }
    }

    /**
     * Add/remove to library single song
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changeInLibrary(s: SongEntity) {
        scope.launch {
            // we don't have an api call yet
        }
    }

    /**
     * Singleton syncRemoteLikedSongs
     */
    suspend fun syncRemoteLikedSongs(bypass: Boolean = false): SyncResult = withContext(Dispatchers.IO) { likedSongs(bypass) }

    private suspend fun likedSongs(bypass: Boolean): SyncResult {
        // REQUIRED: internet, no ongoing sync, category enabled, and someone signed in. The sign-in
        // check used to live only in the optional block below, so a pull-to-refresh (bypass)
        // synced a signed-out session, whose empty answers read as "everything was removed".
        if (_isSyncingRemoteLikedSongs.value || !checkEnabled(SyncContent.LIKED_SONGS) || !context.isInternetConnected() || !context.isUserLoggedIn()) {
            if (_isSyncingRemoteLikedSongs.value)
                Log.i(TAG, "Library songs synchronization already in progress")
            return SyncResult.NOTHING
        }
        // OPTIONAL: auto sync and cooldown
        if (!bypass) {
            if (!context.isAutoSyncEnabled() || !checkPartialSyncEligibility(LastLikeSongSyncKey)) {
                return SyncResult.NOTHING
            }
        }
        _isSyncingRemoteLikedSongs.value = true
        // Failed until shown otherwise: a fetch that throws or comes back short says so.
        var result = SyncResult.FAILED

        try {
            Log.d(TAG, "Liked songs synchronization started")

            // Get remote and local liked songs
            YouTube.playlist("LM").onSuccess { page ->
                val walked = page.walkSongs()
                if (!context.isInternetConnected()) {
                    return SyncResult.FAILED
                }

                val remoteSongs = walked.items.reversed()

                // An empty answer is not "you have unliked everything", it is a fetch that did
                // not work: a throttled request, a session that came back signed out, a bad page.
                // Acting on it wipes the entire liked library, and the listener has no way to get
                // it back. If LM says nothing, believe nothing.
                if (remoteSongs.isEmpty()) {
                    Log.w(TAG, "LM came back empty, refusing to unlike anything")
                    downloadUtil.downloadLikedSongs()
                    return@onSuccess
                }

                // Complete means every continuation answered, and the count in LM's header agrees
                // with what came back. A page that went missing makes every older like look
                // unliked, and a 1,500-song LM would lose everything after it.
                val headerCount = LikedSync.parseSongCount(page.playlist.songCountText)
                val complete = walked.complete && LikedSync.readLooksComplete(remoteSongs.size, headerCount)
                if (!complete) {
                    Log.w(TAG, "LM read looks incomplete (${remoteSongs.size} of ${headerCount ?: "?"}, " +
                            "walk complete: ${walked.complete}), refusing to unlike anything")
                }
                val remoteIds = remoteSongs.mapTo(HashSet()) { it.id }

                // Only a like YouTube is known to have had can be taken back: one that was in LM
                // at this account's last complete sync and is gone now. Everything else missing
                // from LM (liked signed out, offline, under another account) was never there to be
                // unliked, and used to be unliked anyway, all of it on a first sign-in.
                val accountKey = LikedSync.accountKey(
                    context.dataStore[DataSyncIdKey], context.dataStore[AccountEmailKey]
                )
                val snapshots = LikedSnapshotStore(File(context.filesDir, LIKED_SNAPSHOT_DIR))
                val snapshot = accountKey?.let { snapshots.read(it) }
                val localLikes = database.likedSongsByNameAsc().first().filterNot { it.song.isLocal }
                val idsToUnlike = LikedSync.idsToUnlike(
                    localLikes = localLikes.map { LikedSync.LocalLike(it.id, it.song.likedDate) },
                    remoteIds = remoteIds,
                    complete = complete,
                    snapshot = snapshot,
                    // "Keep all local content": sync never takes anything away, likes included.
                    addOnly = !checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE),
                    now = LocalDateTime.now(),
                    // A like pushed by toggleLike may simply not have arrived yet.
                    graceDays = UNLIKE_GRACE_DAYS,
                )
                val songsToUnlike = localLikes.filter { it.id in idsToUnlike }

                if (songsToUnlike.isNotEmpty()) {
                    Log.i(TAG, "Unliking ${songsToUnlike.size} songs YouTube had and has since unliked")
                } else if (snapshot == null) {
                    Log.i(TAG, "No liked snapshot for this account yet, only adding")
                }

                // Unlike local songs in the database
                runBlocking {
                    songsToUnlike.forEach { song ->
                        launch(Dispatchers.IO) {
                            database.update(song.song.localToggleLike())
                        }
                    }
                }

                // Insert or like songs in the database
                for (remoteSong in remoteSongs) {
                    val localSong = database.song(remoteSong.id).firstOrNull()
                    database.transaction {
                        if (localSong == null) {
                            insert(remoteSong.toMediaMetadata(), SongEntity::localToggleLike)
                        } else if (!localSong.song.liked) {
                            update(localSong.song.localToggleLike())
                        }
                    }
                }

                // What LM held, for the next sync to compare against. Only a complete read is
                // worth remembering: a song missing from a short one would drop out of the
                // snapshot, and its later unlike on YouTube would never reach the app.
                if (complete && accountKey != null) {
                    runCatching { snapshots.write(accountKey, remoteIds) }
                        .onFailure { Log.w(TAG, "Could not save the liked snapshot", it) }
                }

                // Songs liked on YouTube never pass through a like button, so without this a fresh
                // login syncs hundreds of liked songs and downloads none of them, under a setting
                // that says it downloads your liked songs. One bounded snapshot, no collector.
                downloadUtil.downloadLikedSongs()
                result = if (walked.complete) SyncResult.SYNCED else SyncResult.FAILED
            }

        } finally {
            // First, and the stamp after it cannot be cancelled: the flag was reset last, after a
            // suspending write, so a sync cancelled by leaving its screen left the flag set,
            // and since the guard keeps a second sync out, that kind of sync never ran again.
            _isSyncingRemoteLikedSongs.value = false
            withContext(NonCancellable) {
                context.dataStore.edit { settings ->
                    settings[LastLikeSongSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                }
            }
            Log.i(TAG, "Liked songs synchronization ended")
        }
        return result
    }

    /**
     * Singleton syncRemoteSongs
     */
    suspend fun syncRemoteSongs(bypass: Boolean = false): SyncResult = withContext(Dispatchers.IO) { librarySongs(bypass) }

    private suspend fun librarySongs(bypass: Boolean): SyncResult {
        // REQUIRED: internet, no ongoing sync, category enabled, and someone signed in
        if (_isSyncingRemoteSongs.value || !checkEnabled(SyncContent.PRIVATE_SONGS) || !context.isInternetConnected() || !context.isUserLoggedIn()) {
            if (_isSyncingRemoteSongs.value)
                Log.i(TAG, "Library songs synchronization already in progress")
            return SyncResult.NOTHING
        }
        // OPTIONAL: auto sync and cooldown
        if (!bypass) {
            if (!context.isAutoSyncEnabled() || !checkPartialSyncEligibility(LastLibSongSyncKey)) {
                return SyncResult.NOTHING
            }
        }
        _isSyncingRemoteSongs.value = true
        // Failed until shown otherwise: a fetch that throws or comes back short says so.
        var result = SyncResult.FAILED

        try {
            Log.i(TAG, "Library songs synchronization started")

            // Get remote songs (from library and uploads)
            val remote = getRemoteData<SongItem>("FEmusic_liked_videos", "FEmusic_library_privately_owned_tracks")
            val remoteSongs = remote.items
            if (!context.isInternetConnected()) {
                return SyncResult.FAILED
            }

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE) && removalAllowed(remote, "songs")) {
                // Identify local songs to remove
                val remoteIds = remoteSongs.mapTo(HashSet()) { it.id }
                val songsToRemoveFromLibrary = database.songsByNameAsc().first()
                    .filterNot { it.song.isLocal }
                    .filterNot { localSong -> localSong.id in remoteIds }

                // Remove local songs from the library, and only from the library: toggleLibrary
                // also unliked them, and whether a song is liked is the liked sync's business.
                runBlocking {
                    songsToRemoveFromLibrary.forEach { song ->
                        launch(Dispatchers.IO) {
                            database.update(song.song.copy(inLibrary = null))
                        }
                    }
                }
            }

            // Inset or mark songs to library
            runBlocking {
                val jobs = remoteSongs.map { song ->
                    launch(Dispatchers.IO) {
                        val dbSong = database.song(song.id).firstOrNull()
                        database.transaction {
                            if (dbSong == null) {
                                insert(song.toMediaMetadata(), SongEntity::toggleLibrary)
                            } else if (dbSong.song.inLibrary == null) {
                                update(dbSong.song.toggleLibrary())
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
            result = if (remote.complete) SyncResult.SYNCED else SyncResult.FAILED
        } finally {
            // First, and the stamp after it cannot be cancelled: the flag was reset last, after a
            // suspending write, so a sync cancelled by leaving its screen left the flag set,
            // and since the guard keeps a second sync out, that kind of sync never ran again.
            _isSyncingRemoteSongs.value = false
            withContext(NonCancellable) {
                context.dataStore.edit { settings ->
                    settings[LastLibSongSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                }
            }
            Log.i(TAG, "Library songs synchronization ended")
        }
        return result
    }

    /**
     * Singleton syncRemoteAlbums
     */
    suspend fun syncRemoteAlbums(bypass: Boolean = false): SyncResult = withContext(Dispatchers.IO) { albums(bypass) }

    private suspend fun albums(bypass: Boolean): SyncResult {
        // REQUIRED: internet, no ongoing sync, category enabled, and someone signed in
        if (_isSyncingRemoteAlbums.value || !checkEnabled(SyncContent.ALBUMS) || !context.isInternetConnected() || !context.isUserLoggedIn()) {
            if (_isSyncingRemoteAlbums.value)
                Log.i(TAG, "Library songs synchronization already in progress")
            return SyncResult.NOTHING
        }
        // OPTIONAL: auto sync and cooldown
        if (!bypass) {
            if (!context.isAutoSyncEnabled() || !checkPartialSyncEligibility(LastAlbumSyncKey)) {
                return SyncResult.NOTHING
            }
        }
        _isSyncingRemoteAlbums.value = true
        // Failed until shown otherwise: a fetch that throws or comes back short says so.
        var result = SyncResult.FAILED

        try {
            Log.i(TAG, "Library albums synchronization started")

            // Get remote albums (from library and uploads)
            val remote =
                getRemoteData<AlbumItem>("FEmusic_liked_albums", "FEmusic_library_privately_owned_releases")
            val remoteAlbums = remote.items
            if (!context.isInternetConnected()) {
                return SyncResult.FAILED
            }

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE) && removalAllowed(remote, "albums")) {
                // Identify local albums to remove
                val albumsToRemoveFromLibrary = database.albumsLikedAsc().first()
                    .filterNot { it.album.isLocal }
                    .filterNot { localAlbum -> remoteAlbums.any { it.id == localAlbum.id } }

                // Remove albums from local database
                runBlocking {
                    albumsToRemoveFromLibrary.forEach { album ->
                        launch(Dispatchers.IO) {
                            database.update(album.album.localToggleLike())
                        }
                    }
                }
            }

            // Add or mark albums in local database
            runBlocking {
                remoteAlbums.forEach { remoteAlbum ->
                    launch(Dispatchers.IO) {
                        val localAlbum = database.album(remoteAlbum.id).firstOrNull()
                        if (localAlbum == null) {
                            database.insert(remoteAlbum)
                            database.album(remoteAlbum.id).firstOrNull()?.let {
                                database.update(it.album.localToggleLike())
                            }
                        } else if (localAlbum.album.bookmarkedAt == null) {
                            database.update(localAlbum.album.localToggleLike())
                        }
                    }
                }
            }
            result = if (remote.complete) SyncResult.SYNCED else SyncResult.FAILED
        } finally {
            // First, and the stamp after it cannot be cancelled: the flag was reset last, after a
            // suspending write, so a sync cancelled by leaving its screen left the flag set,
            // and since the guard keeps a second sync out, that kind of sync never ran again.
            _isSyncingRemoteAlbums.value = false
            withContext(NonCancellable) {
                context.dataStore.edit { settings ->
                    settings[LastAlbumSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                }
            }
            Log.i(TAG, "Library albums synchronization ended")
        }
        return result
    }

    /**
     * Singleton syncRemoteArtists
     */
    suspend fun syncRemoteArtists(bypass: Boolean = false): SyncResult = withContext(Dispatchers.IO) { artists(bypass) }

    private suspend fun artists(bypass: Boolean): SyncResult {
        // REQUIRED: internet, no ongoing sync, category enabled, and someone signed in
        if (_isSyncingRemoteArtists.value || !checkEnabled(SyncContent.ARTISTS) || !context.isInternetConnected() || !context.isUserLoggedIn()) {
            if (_isSyncingRemoteArtists.value)
                Log.i(TAG, "Library songs synchronization already in progress")
            return SyncResult.NOTHING
        }
        // OPTIONAL: auto sync and cooldown
        if (!bypass) {
            if (!context.isAutoSyncEnabled() || !checkPartialSyncEligibility(LastArtistSyncKey)) {
                return SyncResult.NOTHING
            }
        }
        _isSyncingRemoteArtists.value = true
        // Failed until shown otherwise: a fetch that throws or comes back short says so.
        var result = SyncResult.FAILED

        try {
            Log.i(TAG, "Artist subscriptions synchronization started")

            // Get remote artists (from library and uploads)
            val likedRemote = getRemoteData<ArtistItem>(
                "FEmusic_library_corpus_artists",
                "FEmusic_library_privately_owned_artists"
            )
            val likedArtists = likedRemote.items
            val trackArtists = getRemoteData<ArtistItem>(
                "FEmusic_library_corpus_track_artists",
                "FEmusic_library_privately_owned_artists"
            ).items
            val remoteArtists = mutableListOf<ArtistItem>().apply {
                addAll(likedArtists)
                addAll(trackArtists.filterNot { trackArtist ->
                    likedArtists.any { it.id == trackArtist.id }
                })
            }

            if (!context.isInternetConnected()) {
                return SyncResult.FAILED
            }

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE) && removalAllowed(likedRemote, "artists")) {
                // Get local artists
                val artistsToRemoveFromSubscriptions = database.artistsBookmarkedAsc().first()
                    .filterNot { it.artist.isLocal }
                    .filterNot { localArtist -> likedArtists.any { it.id == localArtist.id } }

                // Remove local artists from the database
                runBlocking {
                    artistsToRemoveFromSubscriptions.forEach { artist ->
                        launch(Dispatchers.IO) {
                            database.update(artist.artist.localToggleLike())
                        }
                    }
                }
            }

            // Add or mark artists in the database
            runBlocking {
                remoteArtists.forEach { remoteArtist ->
                    launch(Dispatchers.IO) {
                        val localArtist = database.artist(remoteArtist.id).firstOrNull()
                        val isLikedArtist = likedArtists.contains(remoteArtist)

                        database.transaction {
                            if (localArtist == null) {
                                insert(
                                    ArtistEntity(
                                        id = remoteArtist.id,
                                        name = remoteArtist.title,
                                        thumbnailUrl = remoteArtist.thumbnail,
                                        channelId = remoteArtist.channelId,
                                        bookmarkedAt = if (isLikedArtist) LocalDateTime.now() else null
                                    )
                                )
                            } else if (localArtist.artist.bookmarkedAt == null && isLikedArtist) {
                                update(localArtist.artist.localToggleLike())
                            }
                        }
                    }
                }
            }
            result = if (likedRemote.complete) SyncResult.SYNCED else SyncResult.FAILED
        } finally {
            // First, and the stamp after it cannot be cancelled: the flag was reset last, after a
            // suspending write, so a sync cancelled by leaving its screen left the flag set,
            // and since the guard keeps a second sync out, that kind of sync never ran again.
            _isSyncingRemoteArtists.value = false
            withContext(NonCancellable) {
                context.dataStore.edit { settings ->
                    settings[LastArtistSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                }
            }
            Log.i(TAG, "Artist subscriptions synchronization ended")
        }
        return result
    }

    /**
     * Singleton syncRemotePlaylists
     */
    suspend fun syncRemotePlaylists(bypass: Boolean = false): SyncResult = withContext(Dispatchers.IO) { playlists(bypass) }

    private suspend fun playlists(bypass: Boolean): SyncResult {
        // REQUIRED: internet, no ongoing sync, category enabled, and someone signed in
        if (_isSyncingRemotePlaylists.value || !checkEnabled(SyncContent.PLAYLISTS) || !context.isInternetConnected() || !context.isUserLoggedIn()) {
            if (_isSyncingRemotePlaylists.value)
                Log.i(TAG, "Library songs synchronization already in progress")
            return SyncResult.NOTHING
        }
        // OPTIONAL: auto sync and cooldown
        if (!bypass) {
            if (!context.isAutoSyncEnabled() || !checkPartialSyncEligibility(LastPlaylistSyncKey)) {
                return SyncResult.NOTHING
            }
        }
        _isSyncingRemotePlaylists.value = true
        // Failed until shown otherwise: a fetch that throws or comes back short says so.
        var result = SyncResult.FAILED

        try {
            Log.i(TAG, "Library playlist synchronization started")

            // Get remote and local playlists
            YouTube.library("FEmusic_liked_playlists").onSuccess { firstPage ->
                val walked = firstPage.walkItems()
                if (!context.isInternetConnected()) {
                    return SyncResult.FAILED
                }

                val remotePlaylists = walked.items.filterIsInstance<PlaylistItem>()
                    .filterNot { it.id == "LM" || it.id == "SE" }
                    .reversed()

                val localPlaylists = database.playlistInLibraryAsc().first()

                if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE) &&
                    removalAllowed(Walked(remotePlaylists, walked.complete), "playlists")
                ) {
                    // Identify playlists to remove
                    val playlistsToRemove = localPlaylists
                        .filterNot { it.playlist.isLocal }
                        .filterNot { it.playlist.browseId == null }
                        .filterNot { localPlaylist -> remotePlaylists.any { it.id == localPlaylist.playlist.browseId } }

                    // Remove playlists from the database
                    runBlocking {
                        playlistsToRemove.forEach { playlist ->
                            launch(Dispatchers.IO) {
                                database.update(playlist.playlist.localToggleLike())
                            }
                        }
                    }
                }

                // Add or update playlists in the database
                runBlocking {
                    remotePlaylists.forEach { remotePlaylist ->
                        launch(Dispatchers.IO) {
                            // forcefully assign isEditable. These playlists are at mercy of YouTube
                            var localPlaylist =
                                localPlaylists.find { remotePlaylist.id == it.playlist.browseId }?.playlist
                                    ?.copy(isEditable = remotePlaylist.isEditable)
                            if (localPlaylist == null) {
                                localPlaylist = PlaylistEntity(
                                    name = remotePlaylist.title,
                                    browseId = remotePlaylist.id,
                                    isEditable = remotePlaylist.isEditable,
                                    bookmarkedAt = LocalDateTime.now(),
                                    thumbnailUrl = remotePlaylist.thumbnail,
                                    remoteSongCount = remotePlaylist.songCountText?.let {
                                        Regex("""\d+""").find(it)?.value?.toIntOrNull()
                                    },
                                    playEndpointParams = remotePlaylist.playEndpoint?.params,
                                    shuffleEndpointParams = remotePlaylist.shuffleEndpoint?.params,
                                    radioEndpointParams = remotePlaylist.radioEndpoint?.params
                                )
                                database.insert(localPlaylist)
                            } else {
                                database.update(localPlaylist, remotePlaylist)
                            }

                            // Fetch the playlist again after potential insertion/update
                            val updatedPlaylist =
                                database.playlistByBrowseId(remotePlaylist.id).firstOrNull()
                            updatedPlaylist?.let {
                                // The (playlistId, from) query. The one-argument overload takes a
                                // song id, so this was always empty and a saved playlist that is
                                // not ours was never refreshed.
                                val playlistSongMaps = database.songMapsToPlaylist(updatedPlaylist.id, 0)
                                if (updatedPlaylist.playlist.isEditable || playlistSongMaps.isNotEmpty()) {
                                    syncPlaylist(remotePlaylist.id, updatedPlaylist.id)
                                }
                            }
                        }
                    }
                }
                result = if (walked.complete) SyncResult.SYNCED else SyncResult.FAILED
            }
        } finally {
            // First, and the stamp after it cannot be cancelled: the flag was reset last, after a
            // suspending write, so a sync cancelled by leaving its screen left the flag set,
            // and since the guard keeps a second sync out, that kind of sync never ran again.
            _isSyncingRemotePlaylists.value = false
            withContext(NonCancellable) {
                context.dataStore.edit { settings ->
                    settings[LastPlaylistSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                }
            }
            Log.i(TAG, "Library playlist synchronization ended")
        }
        return result
    }

    /**
     * Replaces a playlist's songs with YouTube's copy of it. True when that happened.
     *
     * The local songs are cleared before the remote ones go in, so a read that stopped early used
     * to cut the playlist short, and one that came back empty emptied it. Only a whole read is
     * copied now; anything less leaves the playlist as it was.
     */
    suspend fun syncPlaylist(browseId: String, playlistId: String): Boolean = withContext(Dispatchers.IO) {
        // this is also used for individual playlist sync
        if (!context.isInternetConnected()) {
            return@withContext false
        }
        val playlistPage = YouTube.playlist(browseId).getOrElse {
            Log.w(TAG, "Could not read playlist $browseId: ${it.message}")
            return@withContext false
        }
        val walked = playlistPage.walkSongs()
        if (!context.isInternetConnected()) {
            return@withContext false
        }
        val hasLocalSongs = database.songMapsToPlaylist(playlistId, 0).isNotEmpty()
        if (!mayReplacePlaylist(walked.complete, walked.items.size, hasLocalSongs)) {
            Log.w(TAG, "Playlist $browseId was not read in full (${walked.items.size} songs), keeping the local copy")
            return@withContext false
        }

        database.transaction {
            clearPlaylist(playlistId)
            val songEntities = walked.items
                .map(SongItem::toMediaMetadata)
                .onEach { insert(it) }

            val playlistSongMaps = songEntities.mapIndexed { position, song ->
                PlaylistSongMap(
                    songId = song.id,
                    playlistId = playlistId,
                    position = position,
                    setVideoId = song.setVideoId
                )
            }
            playlistSongMaps.forEach { insert(it) }
        }
        true
    }

    suspend fun syncRecentActivity(bypass: Boolean = false): SyncResult = withContext(Dispatchers.IO) { recentActivity(bypass) }

    private suspend fun recentActivity(bypass: Boolean): SyncResult {
        // REQUIRED: internet, no ongoing sync, category enabled, and someone signed in
        if (_isSyncingRecentActivity.value || !checkEnabled(SyncContent.RECENT_ACTIVITY) || !context.isInternetConnected() || !context.isUserLoggedIn()) {
            if (_isSyncingRecentActivity.value)
                Log.i(TAG, "Recent activity synchronization already in progress")
            return SyncResult.NOTHING
        }
        // OPTIONAL: auto sync and cooldown
        if (!bypass) {
            if (!context.isAutoSyncEnabled() || !checkPartialSyncEligibility(LastRecentActivitySyncKey)) {
                return SyncResult.NOTHING
            }
        }
        _isSyncingRecentActivity.value = true
        // Failed until shown otherwise: a fetch that throws or comes back short says so.
        var result = SyncResult.FAILED

        try {
            Log.i(TAG, "Recent activity synchronization started")
            YouTube.libraryRecentActivity().onSuccess { page ->
                val recentActivity = page.items.take(9).drop(1)

                runBlocking {
                    launch(Dispatchers.IO) {
                        database.clearRecentActivity()

                        recentActivity.reversed().forEach { database.insertRecentActivityItem(it) }
                    }
                }
                result = SyncResult.SYNCED
            }
        } finally {
            // First, and the stamp after it cannot be cancelled: the flag was reset last, after a
            // suspending write, so a sync cancelled by leaving its screen left the flag set,
            // and since the guard keeps a second sync out, that kind of sync never ran again.
            _isSyncingRecentActivity.value = false
            withContext(NonCancellable) {
                context.dataStore.edit { settings ->
                    settings[LastRecentActivitySyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
                }
            }
            Log.i(TAG, "Recent activity synchronization ended")
        }
        return result
    }

    /**
     * The library and uploads tabs together, and whether both were read in full.
     *
     * This used to hand back whatever arrived: a tab that failed added nothing, and a walk that
     * stopped half-way counted as finished, so a 429 or a timeout looked exactly like a library
     * that had been emptied. What was read is still fine to add; only a complete read may remove.
     */
    private suspend inline fun <reified T> getRemoteData(libraryId: String, uploadsId: String): Walked<T> {
        val tabs = coroutineScope {
            listOf(libraryId to 0, uploadsId to 1).map { (browseId, tab) ->
                async {
                    YouTube.library(browseId, tab).fold(
                        onSuccess = { it.walkItems() },
                        onFailure = {
                            Log.w(TAG, "Could not read $browseId: ${it.message}")
                            Walked(emptyList<YTItem>(), complete = false)
                        }
                    )
                }
            }.awaitAll()
        }
        return Walked(
            items = tabs.flatMap { it.items.filterIsInstance<T>().reversed() },
            complete = tabs.all { it.complete }
        )
    }

    private fun removalAllowed(remote: Walked<*>, what: String): Boolean =
        mayRemoveMissing(remote.complete, remote.items.size).also {
            if (!it) Log.w(TAG, "Not removing any $what: the remote list was not read in full (${remote.items.size} read)")
        }
}

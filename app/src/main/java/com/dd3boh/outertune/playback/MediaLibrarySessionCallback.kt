package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.PlayOrigin
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.MediaSessionConstants
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.toggleRepeatMode
import com.dd3boh.outertune.extensions.toggleShuffleMode
import com.dd3boh.outertune.utils.albumWithOrderedSongs
import com.dd3boh.outertune.utils.reportException
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.AsyncFunction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** The longest a request to play waits for the saved queues to load at the service's start. */
private const val QUEUES_LOADED_TIMEOUT_MS = 5_000L

class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val TAG = MediaLibrarySessionCallback::class.simpleName.toString()
    // A supervisor, so one request that throws fails on its own. Under a plain Job the first
    // failure cancelled the scope, and every later browse, search and play from the car failed
    // with it until the service was restarted.
    private val scope = CoroutineScope(Dispatchers.Main) + SupervisorJob()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands.buildUpon()
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(SessionCommand(MusicService.COMMAND_GET_BINDER, Bundle.EMPTY))
                .build(),
            connectionResult.availablePlayerCommands
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.toggleShuffleMode()
            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MusicService.COMMAND_GET_BINDER -> return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_SUCCESS).apply {
                    extras.putBinder("music_binder", service.MusicBinder())
                }
            )
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        // TODO: when this is stable, change to debug
        Log.i(TAG, "onPlaybackResumption() called")
        resumptionItems() ?: run {
            Log.w(TAG, "No resumption queue data. Loading empty list")
            MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        }
    }

    /**
     * The saved queue to resume, for a media button, the system's resumption controls, or a request
     * to play that names nothing. Null when there is no saved queue.
     */
    private suspend fun resumptionItems(): MediaItemsWithStartPosition? {
        val (q, items) = withContext(Dispatchers.IO) {
            val q = database.getResumptionQueue() ?: return@withContext null
            Log.i(TAG, "Resumption queue found. Loading queue: size = ${q.queue.size}, queue name = ${q.title}, " +
                    "queuePosShuffled = ${q.getQueuePosShuffled()}, lastSongPos = ${q.lastSongPos},")
            q to MediaItemsWithStartPosition(
                q.getCurrentQueueShuffled().map { it.toMediaItem() },
                q.getQueuePosShuffled(),
                q.lastSongPos
            )
        } ?: return null
        // The system asked for this, from a media button or its own resumption notification, not
        // the listener choosing a song. The fragment it continues carries the real origin.
        service.pendingOrigin = PlayOrigin.RESUMED
        // The player's shuffle flag is whatever the last run left, off on a cold start, so a
        // shuffled queue came back in its shuffled order with the flag off, and the first press of
        // shuffle in the notification or the car changed nothing. Only when no other queue is
        // current, or the same one is, flag and all: against another queue the change would read
        // as a shuffle press on that one and load it into the player.
        val current = service.queueBoard.getCurrentQueue()
        if ((current == null || (current.id == q.id && current.shuffled == q.shuffled)) &&
            service.player.shuffleModeEnabled != q.shuffled
        ) {
            service.player.shuffleModeEnabled = q.shuffled
        }
        return items
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(
            MediaItem.Builder()
                .setMediaId(MusicService.ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsPlayable(false)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build(),
            params
        )
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        LibraryResult.ofItemList(
            when (parentId) {
                MusicService.ROOT -> listOf(
                    browsableMediaItem(
                        MusicService.SONG,
                        context.getString(R.string.songs),
                        null,
                        drawableUri(R.drawable.music_note),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST
                    ),
                    browsableMediaItem(
                        MusicService.ARTIST,
                        context.getString(R.string.artists),
                        null,
                        drawableUri(R.drawable.artist),
                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                    ),
                    browsableMediaItem(
                        MusicService.ALBUM,
                        context.getString(R.string.albums),
                        null,
                        drawableUri(R.drawable.album),
                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                    ),
                    browsableMediaItem(
                        MusicService.PLAYLIST,
                        context.getString(R.string.playlists),
                        null,
                        drawableUri(R.drawable.queue_music),
                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                    )
                )

                MusicService.SONG -> database.songsByCreateDateAsc().first().map { it.toMediaItem(parentId) }
                MusicService.ARTIST -> database.artistsInLibraryAsc().first().map { artist ->
                    browsableMediaItem(
                        "${MusicService.ARTIST}/${artist.id}",
                        artist.artist.name,
                        context.resources.getQuantityString(R.plurals.n_song, artist.songCount, artist.songCount),
                        artist.artist.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ARTIST
                    )
                }

                MusicService.ALBUM -> database.albumsInLibraryAsc().first().map { album ->
                    browsableMediaItem(
                        "${MusicService.ALBUM}/${album.id}",
                        album.album.title,
                        album.artists.joinToString { it.name },
                        album.album.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ALBUM
                    )
                }

                MusicService.PLAYLIST -> {
                    val likedSongCount = database.likedSongsCount().first()
                    val downloadedSongCount = downloadUtil.downloads.value.size
                    listOf(
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                            context.getString(R.string.liked_songs),
                            context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                            drawableUri(R.drawable.favorite),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                            context.getString(R.string.downloaded_songs),
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                downloadedSongCount,
                                downloadedSongCount
                            ),
                            drawableUri(R.drawable.download),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    ) + database.playlistInLibraryAsc().first().map { playlist ->
                        browsableMediaItem(
                            "${MusicService.PLAYLIST}/${playlist.id}",
                            playlist.playlist.name,
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                playlist.songCount,
                                playlist.songCount
                            ),
                            drawableUri(R.drawable.queue_music),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                }

                else -> when {
                    parentId.startsWith("${MusicService.ARTIST}/") ->
                        database.artistSongsByCreateDateAsc(parentId.removePrefix("${MusicService.ARTIST}/")).first()
                            .map {
                                it.toMediaItem(parentId)
                            }

                    parentId.startsWith("${MusicService.ALBUM}/") ->
                        database.albumSongs(parentId.removePrefix("${MusicService.ALBUM}/")).first().map {
                            it.toMediaItem(parentId)
                        }

                    parentId.startsWith("${MusicService.PLAYLIST}/") ->
                        when (val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")) {
                            PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, true)
                            PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs()
                            else -> database.playlistSongs(playlistId).map { list ->
                                list.map { it.song }
                            }
                        }.first().map {
                            it.toMediaItem(parentId)
                        }

                    else -> emptyList()
                }
            },
            params
        )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
        database.song(mediaId).first()?.toMediaItem()?.let {
            LibraryResult.ofItem(it, null)
        } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        // Play from Android Auto, the assistant, or any controller that plays by id or by search
        Log.d(TAG, "MediaLibrarySessionCallback.onSetMediaItems")
        val request = mediaItems.firstOrNull()
        val resolved = scope.future { request?.let { resolvePlayRequest(it, startPositionMs) } }
        // A request this cannot place fails rather than answering with an empty list. media3 puts
        // whatever it is given into the player, so an empty answer emptied it, which is what a
        // voice request did. A failed one it leaves alone, in 1.8.0 on both paths: the legacy
        // stub's onFailure does nothing, and a media3 controller gets an error with the player
        // untouched.
        return Futures.transformAsync(
            resolved,
            AsyncFunction<MediaItemsWithStartPosition?, MediaItemsWithStartPosition> { result ->
                if (result != null) Futures.immediateFuture(result)
                else Futures.immediateFailedFuture<MediaItemsWithStartPosition>(UnsupportedOperationException("Nothing to play for \"${request?.mediaId}\""))
            },
            MoreExecutors.directExecutor()
        )
    }

    /** What a request to play should put in the player, or null when this cannot place it. */
    private suspend fun resolvePlayRequest(request: MediaItem, startPositionMs: Long): MediaItemsWithStartPosition? {
        // Not before the service has read back its saved queues. A request that brings the
        // service up, from the car or from an assistant's play-from-search, can arrive first, and
        // loading them replaces the queue board: the queue added here was dropped, and the board
        // was left on a saved queue while the player played the request. Bounded, so a load that
        // failed does not hold the request for ever.
        withTimeoutOrNull(QUEUES_LOADED_TIMEOUT_MS) { service.qbInit.first { it } }
        val mediaId = request.mediaId
        if (mediaId.isEmpty()) {
            // No id: media3 builds these from a search, "Hey Google, play X" in the car, and from a
            // request that names nothing at all. A uri is not something this app plays by.
            if (request.requestMetadata.mediaUri != null) return null
            val query = request.requestMetadata.searchQuery?.trim().orEmpty()
            if (query.isEmpty()) return continueCurrentQueue()
            return startExternalQueue(searchLibrary(query).map { it.toMediaItem() }, 0, C.TIME_UNSET)
        }

        val target = PlayRequest.parse(mediaId) ?: return null
        Log.d(TAG, "Play request: $target")
        val songs: List<Song> = when (target) {
            is PlayRequest.Song -> database.songsByCreateDateAsc().first()
            is PlayRequest.Artist -> database.artistSongsByCreateDateAsc(target.artistId).first()
            is PlayRequest.Album -> database.albumWithOrderedSongs(target.albumId).first()?.songs ?: return null
            is PlayRequest.Playlist -> when (target.playlistId) {
                PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
                PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs()
                else -> database.playlistSongs(target.playlistId).map { list ->
                    list.map { it.song }
                }
            }.first()

            is PlayRequest.Search -> searchLibrary(target.query)
        }
        val index = songs.indexOfFirst { it.id == target.songId }.coerceAtLeast(0)
        val position = if (target is PlayRequest.Search) C.TIME_UNSET else startPositionMs
        return startExternalQueue(songs.map { it.toMediaItem() }, index, position)
    }

    /**
     * "Play" with nothing named, said to the assistant or the car: carry on with the queue there is.
     *
     * With songs in the player it plays them and returns null, so the request fails and media3
     * leaves the player as it is rather than loading it again. With none it returns the saved
     * queue, which is what a headset press would resume.
     */
    private suspend fun continueCurrentQueue(): MediaItemsWithStartPosition? {
        val player = service.player
        if (player.mediaItemCount == 0) return resumptionItems()
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
        }
        player.play()
        return null
    }

    /**
     * Hand the player a list somebody else chose, and make it the board's current queue.
     *
     * The board has to be told. addQueue never makes a queue current and the player is loaded by
     * media3 here, not by the board, so the queue playing before stayed current: every song change
     * wrote the car's position into it, radio topped it up and reloaded it over the car's music,
     * and shuffle swapped the car's music for it.
     */
    private fun startExternalQueue(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaItemsWithStartPosition? {
        // Nothing found is a request that fails, not an empty player.
        if (items.isEmpty()) return null
        val board = service.queueBoard
        val q = board.addQueue(
            context.getString(R.string.android_auto),
            items.map { it.metadata },
            shuffled = false,
            replace = true,
            delta = false,
            startIndex = startIndex
        ) ?: return null
        q.origin = PlayOrigin.EXTERNAL.code
        q.runId = System.currentTimeMillis()
        // The list plays in the order it came in, whatever this queue was the last time the car
        // started one; replacing its songs kept the old shuffled flag.
        q.shuffled = false
        board.setCurrQueueWithoutLoading(q)
        // The player keeps its shuffle flag across a new list. Changed only once this queue is
        // current, or the change reads as a shuffle press on the old queue and loads it back in.
        if (service.player.shuffleModeEnabled) service.player.shuffleModeEnabled = false
        service.userChoicePending = true
        return MediaItemsWithStartPosition(items, startIndex, startPositionMs)
    }

    /** The library search the search results list, so a spoken query plays what a typed one shows. */
    private suspend fun searchLibrary(query: String): List<Song> = combine(
        database.searchSongs(query),
        database.searchArtistSongs(query),
    ) { songs, artistSongs ->
        (songs + artistSongs).distinctBy { it.id }
    }.first()

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        Log.d(TAG, "MediaLibrarySessionCallback.onSearch: $query")
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Log.d(TAG, "MediaLibrarySessionCallback.onGetSearchResult: $query")
        return scope.future {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                // Playable and not browsable. Marked both, a result could open as a folder in the
                // car, and a search result has no children, so it opened empty.
                val items = searchLibrary(query)
                    .map {
                        it.toMediaItem(
                            path = "${MusicService.SEARCH}/$query",
                            isPlayable = true,
                            isBrowsable = false
                        )
                    }
                LibraryResult.ofItemList(items, params)
            } catch (e: Exception) {
                Log.d(TAG, "Could not get search results")
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    private fun drawableUri(@DrawableRes id: Int) = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC
    ) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(iconUri)
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false) =
        MediaItem.Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setSubtitle(artists.joinToString { it.name })
                    .setArtist(artists.joinToString { it.name })
                    .setArtworkUri(song.thumbnailUrl?.toUri())
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun com.dd3boh.outertune.models.MediaMetadata.toMediaItem(isPlayable: Boolean = true, isBrowsable: Boolean = false) = MediaItem.Builder()
        .setMediaId(id)
        .setUri(id)
        .setCustomCacheKey(id)
        .setTag(this)
        .setMediaMetadata(
           MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(artists.joinToString { it.name })
                .setArtist(artists.joinToString { it.name })
                .setArtworkUri(thumbnailUrl?.toUri())
                .setAlbumTitle(album?.title)
                .setIsPlayable(isPlayable)
                .setIsBrowsable(isBrowsable)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()
}
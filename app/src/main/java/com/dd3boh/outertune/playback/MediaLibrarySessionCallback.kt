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
import androidx.media3.session.MediaConstants
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
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.toggleRepeatMode
import com.dd3boh.outertune.extensions.toggleShuffleMode
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.utils.albumWithOrderedSongs
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.widget.WidgetList
import com.dd3boh.outertune.widget.WidgetStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.dd3boh.outertune.models.toMediaMetadata
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
private const val SEARCH_ONLINE_MS = 6_000L
/** The longest the answer to a search's request waits for its song to be in the player. See [MediaLibrarySessionCallback.startSearchRadio]. */
private const val SONG_IN_PLAYER_MS = 2_000L
private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
private const val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM

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

    /** The last search of the car that YouTube Music answered, and its answer. See [searchCar]. */
    @Volatile
    private var searchedOnline: Pair<String, List<Song>>? = null
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
            if (Unreleased.AUTO_REWORK) withSettings(params) else params
        )
    )

    /**
     * The root's answer with the car's way into the settings added to it: Android Auto puts a cog
     * over the tabs of an app whose root names a screen of its own for it. That screen is
     * AutoSettingsService. Anything that goes wrong making it leaves the answer as it was.
     */
    private fun withSettings(params: MediaLibraryService.LibraryParams?): MediaLibraryService.LibraryParams {
        val extras = Bundle(params?.extras ?: Bundle.EMPTY)
        runCatching {
            // Through AutoSettingsLink, not CarPendingIntent.getCarApp: a car that refuses the
            // screen must not take the app down with it.
            extras.putParcelable(
                MediaConstants.EXTRAS_KEY_APPLICATION_PREFERENCES_USING_CAR_APP_LIBRARY_INTENT,
                AutoSettingsLink.forCar(context),
            )
        }.onFailure { Log.w(TAG, "No settings for the car", it) }
        return MediaLibraryService.LibraryParams.Builder()
            .setExtras(extras)
            .setRecent(params?.isRecent ?: false)
            .setOffline(params?.isOffline ?: false)
            .setSuggested(params?.isSuggested ?: false)
            .build()
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        // The app's own screens in the car (AutoCar) read this tree too. They have the car's
        // small player in a corner, so no row of what is playing across the top of a tab.
        val own = browser.packageName == context.packageName
        val bar = if (Unreleased.AUTO_REWORK && !own && parentId in AutoBrowse.TABS) listOfNotNull(nowPlaying()) else emptyList()
        LibraryResult.ofItemList(
            bar + when (parentId) {
                MusicService.ROOT -> if (Unreleased.AUTO_REWORK) carTabs() + carMore() else listOf(
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

                AutoBrowse.HOME -> forYou(carryOn = own)
                AutoBrowse.LIBRARY -> carLibrary(heading = !own)
                AutoBrowse.RECENT -> recentSongs().map { it.toMediaItem(parentId) }
                MusicService.SONG -> librarySongs().map { it.toMediaItem(parentId) }
                MusicService.ARTIST -> database.artistsInLibraryAsc().first().map { artist ->
                    browsableMediaItem(
                        "${MusicService.ARTIST}/${artist.id}",
                        artist.artist.name,
                        context.resources.getQuantityString(R.plurals.n_song, artist.songCount, artist.songCount),
                        cover(AutoArt.ARTIST, artist.id, artist.artist.thumbnailUrl),
                        MediaMetadata.MEDIA_TYPE_ARTIST
                    )
                }

                MusicService.ALBUM -> database.albumsInLibraryAsc().first().map { album ->
                    browsableMediaItem(
                        "${MusicService.ALBUM}/${album.id}",
                        album.album.title,
                        album.artists.joinToString { it.name },
                        cover(AutoArt.ALBUM, album.id, album.album.thumbnailUrl),
                        MediaMetadata.MEDIA_TYPE_ALBUM
                    )
                }

                MusicService.PLAYLIST -> {
                    val likedSongCount = database.likedSongsCount().first()
                    val downloadedSongCount = downloadUtil.downloads.value.size
                    val lists = listOf(
                        likedSongCount to browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                            context.getString(R.string.liked_songs),
                            context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                            if (Unreleased.AUTO_REWORK && likedSongCount > 0) AutoArt.uri(context, AutoArt.PLAYLIST, PlaylistEntity.LIKED_PLAYLIST_ID) else drawableUri(R.drawable.favorite),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        ),
                        downloadedSongCount to browsableMediaItem(
                            "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                            context.getString(R.string.downloaded_songs),
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                downloadedSongCount,
                                downloadedSongCount
                            ),
                            if (Unreleased.AUTO_REWORK && downloadedSongCount > 0) AutoArt.uri(context, AutoArt.PLAYLIST, PlaylistEntity.DOWNLOADED_PLAYLIST_ID) else drawableUri(R.drawable.download),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    ) + database.playlistInLibraryAsc().first().map { playlist ->
                        playlist.songCount to browsableMediaItem(
                            "${MusicService.PLAYLIST}/${playlist.id}",
                            playlist.playlist.name,
                            context.resources.getQuantityString(
                                R.plurals.n_song,
                                playlist.songCount,
                                playlist.songCount
                            ),
                            if (Unreleased.AUTO_REWORK && playlist.songCount > 0) AutoArt.uri(context, AutoArt.PLAYLIST, playlist.id) else drawableUri(R.drawable.queue_music),
                            MediaMetadata.MEDIA_TYPE_PLAYLIST
                        )
                    }
                    // Since the rework a list with nothing in it is left out: in the car it is a
                    // tile that opens on nothing.
                    lists.filter { (songs, _) -> !Unreleased.AUTO_REWORK || songs > 0 }.map { it.second }
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

                    parentId.startsWith("${MusicService.PLAYLIST}/") -> {
                        val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")
                        val songs = playlistSongs(playlistId).map { it.toMediaItem(parentId) }
                        // In the car a playlist opens on the one thing most often wanted of it.
                        if (Unreleased.AUTO_REWORK && songs.size > 1) listOf(shuffleRow(playlistId, songs.size)) + songs else songs
                    }

                    else -> emptyList()
                }
            },
            params
        )
    }

    /**
     * A browser asking to be told when a list changes. Media3's own answer looks the list up as
     * an item first (onGetItem), and that knows songs only here: every list was refused, so
     * nothing said about a list afterwards ever reached the car. Since the rework a list is
     * taken as it comes, because the car's tabs now change with the song and the car, which
     * keeps what it was handed, has to be told.
     */
    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (!Unreleased.AUTO_REWORK) return super.onSubscribe(session, browser, parentId, params)
        // As Media3 does once it has accepted: a browser of its own kind is sent the list at once,
        // an older one (the car) has already asked for it.
        if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
            session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
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
        // untouched. So does a request this has played itself (a search's radio, the queue carried
        // on with), which media3 must not load anything over.
        return Futures.transformAsync(
            resolved,
            AsyncFunction<MediaItemsWithStartPosition?, MediaItemsWithStartPosition> { result ->
                if (result != null) Futures.immediateFuture(result)
                else Futures.immediateFailedFuture<MediaItemsWithStartPosition>(UnsupportedOperationException("Nothing for media3 to load for \"${request?.mediaId}\""))
            },
            MoreExecutors.directExecutor()
        )
    }

    /** What a request to play should put in the player, or null when this cannot place it. */
    private suspend fun resolvePlayRequest(request: MediaItem, startPositionMs: Long): MediaItemsWithStartPosition? {
        // Not before the service has read back its saved queues at least once. A request that
        // brings the service up, from the car or from an assistant's play-from-search, can arrive
        // first, and loading them replaces the queue board: the queue added here was dropped, and
        // the board was left on a saved queue while the player played the request. Bounded, so a
        // load that failed does not hold the request for ever.
        //
        // queuesLoadedOnce, not qbInit itself: qbInit goes back to false every time the mini
        // player is swiped away (deInitQueue), while queuesLoadedOnce only ever completes once. A
        // second request after a swipe would otherwise wait out the same five seconds every time,
        // for a load that already happened.
        withTimeoutOrNull(QUEUES_LOADED_TIMEOUT_MS) { service.queuesLoadedOnce.await() }
        if (!service.qbInit.value) {
            // Swiped away since that first load: load the board the way playQueue does when it
            // finds qbInit false, instead of waiting out a timeout for a signal that will not come
            // again.
            service.initQueue(onlyIfNeeded = true)
        }
        val mediaId = request.mediaId
        if (mediaId.isEmpty()) {
            // No id: media3 builds these from a search, "Hey Google, play X" in the car, and from a
            // request that names nothing at all. A uri is not something this app plays by.
            if (request.requestMetadata.mediaUri != null) return null
            val query = request.requestMetadata.searchQuery?.trim().orEmpty()
            if (query.isEmpty()) return continueCurrentQueue()
            // The best match, the first found, and its radio (playFound). Where it plays the
            // songs found instead, the queue is named after what was asked: an assistant's "play
            // X" and the phone's search come this way as much as the car does, and a queue of
            // theirs called "Android Auto" in the list of queues said where it came from wrongly.
            // In quotes, since a queue's name is what tells it from the others: a search for an
            // album's title would otherwise have replaced the songs of that album's own queue.
            return playFound(searchCar(query), picked = null, title = context.getString(R.string.queue_from_search, query))
        }

        val target = PlayRequest.parse(mediaId) ?: return null
        Log.d(TAG, "Play request: $target")
        if (target is PlayRequest.Resume) return continueCurrentQueue()
        // A song tapped in the results of a search: the song and its radio (playFound), never the
        // results after it.
        if (target is PlayRequest.Search) return playFound(searchCar(target.query), picked = target.songId, title = context.getString(R.string.android_auto))
        if (target is PlayRequest.Shuffle) {
            val list = when {
                target.list == AutoBrowse.LIKED -> playlistSongs(PlaylistEntity.LIKED_PLAYLIST_ID)
                target.list == AutoBrowse.DOWNLOADED -> playlistSongs(PlaylistEntity.DOWNLOADED_PLAYLIST_ID)
                target.list == AutoBrowse.LIBRARY -> librarySongs()
                else -> playlistSongs(target.list.removePrefix(AutoBrowse.PLAYLIST_PREFIX))
            }
            return startExternalQueue(list.shuffled().map { it.toMediaItem() }, 0, C.TIME_UNSET)
        }
        val songs: List<Song> = when (target) {
            is PlayRequest.Song -> librarySongs()
            is PlayRequest.Home -> homeSongs(target.section)
            is PlayRequest.Recent -> recentSongs()
            is PlayRequest.Resume, is PlayRequest.Shuffle, is PlayRequest.Search -> return null
            is PlayRequest.Artist -> database.artistSongsByCreateDateAsc(target.artistId).first()
            is PlayRequest.Album -> database.albumWithOrderedSongs(target.albumId).first()?.songs ?: return null
            is PlayRequest.Playlist -> playlistSongs(target.playlistId)
        }
        // A stale or removed id (the car's own cache, or a replayed history entry) is a request
        // this cannot place, the same as the Album-not-found case above: fail it rather than
        // coercing -1 to 0 and silently starting whatever sorts first.
        val index = PlayRequestIndex.indexOf(songs.map { it.id }, target.songId) ?: return null
        return startExternalQueue(songs.map { it.toMediaItem() }, index, startPositionMs)
    }

    /**
     * What a search plays, by [SearchPlay]: the song tapped in the results ([picked]), or with
     * none named the best match, and its radio, as a song picked in the phone's search plays. A
     * song that is only a file on the device has no radio, and plays on into the songs found with
     * it in a queue called [title], as every search played before. Null when there is no such
     * song.
     */
    private suspend fun playFound(found: List<Song>, picked: String?, title: String): MediaItemsWithStartPosition? {
        val plan = SearchPlay.plan(found, picked, { it.id }, { it.song.isLocal })
        Log.d(TAG, "Search plays: ${plan?.javaClass?.simpleName ?: "nothing"} from \"${picked ?: found.firstOrNull()?.id}\" of ${found.size} found")
        return when (plan) {
            null -> null
            is SearchPlay.Radio -> startSearchRadio(plan.seed)
            is SearchPlay.Found -> startExternalQueue(plan.songs.map { it.toMediaItem() }, plan.index, C.TIME_UNSET, title)
        }
    }

    /**
     * [seed], then its radio, started with the call a song picked in the phone's search makes
     * (OnlineSearchScreen), so that it is the phone's own queue: named by YouTube's answer, topped
     * up as it plays, and kept as a search for what the app learns from what is played.
     *
     * It reaches the player through playQueue, which tells the board itself, so the answer is null
     * and media3 loads nothing (onSetMediaItems): a list handed back would have been loaded over
     * the radio. playQueue puts the song in the player on a later turn of the main loop, and the
     * answer waits for that: a controller sends its prepare and play after its request (the app's
     * own screens in the car, the phone's play from search), and they would otherwise have found
     * the queue before in the player and started its song for a moment, or in an empty player had
     * media3 load the saved queue over the radio (onPlaybackResumption). Bounded, for a start that
     * never comes (another song tapped meanwhile).
     *
     * It plays at once, also for a request only to prepare (onPrepareFromSearch): media3 hands that
     * over the same way as a request to play.
     */
    private suspend fun startSearchRadio(seed: Song): MediaItemsWithStartPosition? {
        val song = seed.toMediaMetadata()
        service.playQueue(YouTubeQueue.radio(song), replace = true, isRadio = true, origin = PlayOrigin.SEARCH)
        withTimeoutOrNull(SONG_IN_PLAYER_MS) { service.currentMediaMetadata.first { it?.id == song.id } }
        return null
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
    private fun startExternalQueue(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        title: String = context.getString(R.string.android_auto),
    ): MediaItemsWithStartPosition? {
        // Nothing found is a request that fails, not an empty player.
        if (items.isEmpty()) return null
        val board = service.queueBoard
        val q = board.addQueue(
            title,
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

    /**
     * What a search from the car finds: the library's answer, and since the rework YouTube
     * Music's own after it. A driver says the name of a song and expects it to play; from the
     * library alone, a song never played before was "nothing found". What YouTube Music answers
     * is written into the library's table of songs seen, as anything shown anywhere in the app
     * is, which is what lets it be played and its cover be served. Its failing, or its taking
     * too long, leaves the library's answer as it was.
     */
    private suspend fun searchCar(query: String): List<Song> {
        val library = searchLibrary(query)
        if (!Unreleased.AUTO_REWORK || library.size >= AutoBrowse.MOST || query.trim().length < 3) return library
        // The car asks again at every letter typed and once more for the list it then shows, and
        // the tap that plays a result asks a third time: the same question is asked of YouTube
        // Music once.
        searchedOnline?.takeIf { it.first == query }?.let { return (library + it.second).distinctBy { song -> song.id } }
        val found = withTimeoutOrNull(SEARCH_ONLINE_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items
                        .filterIsInstance<SongItem>()
                        .take(AutoBrowse.MOST)
                        .mapNotNull { item ->
                            val seen = item.toMediaMetadata()
                            runCatching { database.insert(seen) }
                            database.song(seen.id).first()
                        }
                }.onFailure { Log.w(TAG, "YouTube Music gave the car's search nothing", it) }.getOrDefault(emptyList())
            }
        }.orEmpty()
        if (found.isNotEmpty()) searchedOnline = query to found
        return (library + found).distinctBy { it.id }
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
                val items = searchCar(query)
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
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
        extras: Bundle? = null,
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
                    .setExtras(extras)
                    .build()
            )
            .build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false, group: String? = null) =
        MediaItem.Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setSubtitle(artists.joinToString { it.name })
                    .setArtist(artists.joinToString { it.name })
                    .setArtworkUri(cover(AutoArt.SONG, id, song.thumbnailUrl))
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MEDIA_TYPE_MUSIC)
                    .setExtras(group?.let { style(group = it) })
                    .build()
            )
            .build()

    // What the car is shown since the rework: see AutoBrowse.

    /**
     * A cover's address for the car: one of the app's own, which the car can load, where the web
     * address the library holds is one it cannot (every cover was a grey square for that).
     */
    private fun cover(kind: String, id: String, stored: String?): Uri? =
        if (Unreleased.AUTO_REWORK) AutoArt.uri(context, kind, id) else stored?.toUri()

    /**
     * How the car lays out what a node holds, the heading an item stands under, and for an item
     * that is to look unlike its neighbours, how that one is laid out.
     */
    private fun style(playable: Int? = null, browsable: Int? = null, group: String? = null, single: Int? = null) = Bundle().apply {
        playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
        browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        single?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, it) }
    }

    /** The three tabs: something to play at once, the playlists, the library. */
    private fun carTabs(): List<MediaItem> = AutoBrowse.TABS.map { tab ->
        when (tab) {
            AutoBrowse.HOME -> browsableMediaItem(
                tab, context.getString(R.string.auto_for_you), null, drawableUri(R.drawable.auto_home),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, style(playable = GRID, browsable = GRID),
            )

            MusicService.PLAYLIST -> browsableMediaItem(
                tab, context.getString(R.string.playlists), null, drawableUri(R.drawable.queue_music),
                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, style(playable = LIST, browsable = GRID),
            )

            else -> browsableMediaItem(
                tab, context.getString(R.string.library), null, drawableUri(R.drawable.auto_library),
                MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, style(playable = LIST, browsable = LIST),
            )
        }
    }

    /**
     * What Android Auto lists under its own More tab, under the settings it puts first: every item
     * of the root past the three tabs goes there (AutoBrowse.TABS). Ways back to what is kept, so
     * More holds more than the settings alone (his ask, 26 Oct 2026). The car's own screens take
     * the first three only, so these never become tabs there.
     */
    private suspend fun carMore(): List<MediaItem> {
        val liked = database.likedSongsCount().first()
        return listOf(
            browsableMediaItem(
                "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}", context.getString(R.string.liked_songs),
                context.resources.getQuantityString(R.plurals.n_song, liked, liked), drawableUri(R.drawable.favorite),
                MediaMetadata.MEDIA_TYPE_PLAYLIST, style(playable = LIST),
            ),
            browsableMediaItem(
                "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}", context.getString(R.string.auto_more_downloaded),
                context.getString(R.string.auto_more_downloaded_line), drawableUri(R.drawable.download),
                MediaMetadata.MEDIA_TYPE_PLAYLIST, style(playable = LIST),
            ),
            browsableMediaItem(
                AutoBrowse.RECENT, context.getString(R.string.auto_more_recent), context.getString(R.string.auto_more_recent_line),
                drawableUri(R.drawable.auto_recent), MediaMetadata.MEDIA_TYPE_PLAYLIST, style(playable = LIST),
            ),
            action(
                "${AutoBrowse.SHUFFLE}/${AutoBrowse.LIBRARY}", context.getString(R.string.auto_more_shuffle_library),
                context.getString(R.string.auto_more_shuffle_line), drawableUri(R.drawable.shuffle_on),
            ),
        )
    }

    /**
     * What is playing, as a row right across the top of a tab: its cover, its name, who it is by.
     * The car's own way back to the player is a small square in a corner of the screen, and a row
     * is the widest thing a car lets an app put there. A tap carries on with the queue, which
     * leaves the song where it is and brings the player up. With nothing in the player it is the
     * song that was left off, and the tap loads that queue.
     */
    private suspend fun nowPlaying(): MediaItem? {
        val playing = withContext(Dispatchers.Main) { service.player.currentMediaItem?.metadata }
        val song = playing ?: runCatching { database.getResumptionQueue()?.getCurrentSong() }.getOrNull() ?: return null
        return action(
            "${AutoBrowse.RESUME}/queue", song.title, song.artists.joinToString { it.name }, AutoArt.uri(context, AutoArt.SONG, song.id),
            group = context.getString(if (playing != null) R.string.auto_now_playing else R.string.auto_carry_on), single = LIST,
        )
    }

    /**
     * For you: what starts music at one tap (the queue there is, lists to shuffle), then Home's
     * own rows, each under its name. [carryOn] puts the queue there is among the tiles, for the
     * screens that have no row of what is playing above them.
     */
    private suspend fun forYou(carryOn: Boolean): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        val jump = context.getString(R.string.auto_jump_back_in)
        if (carryOn) {
            val playing = withContext(Dispatchers.Main) { service.player.currentMediaItem?.metadata }
                ?: runCatching { database.getResumptionQueue()?.getCurrentSong() }.getOrNull()
            if (playing != null) {
                items += action("${AutoBrowse.RESUME}/queue", context.getString(R.string.auto_carry_on), playing.title, AutoArt.uri(context, AutoArt.SONG, playing.id), jump)
            }
        }
        val liked = database.likedSongsCount().first()
        if (liked > 1) {
            // A tile's name is cut after a dozen letters or so: the name says which songs, and
            // the line under it that they come shuffled.
            items += action(
                "${AutoBrowse.SHUFFLE}/${AutoBrowse.LIKED}", context.getString(R.string.auto_shuffle_liked),
                context.getString(R.string.auto_shuffled, context.resources.getQuantityString(R.plurals.n_song, liked, liked)),
                AutoArt.uri(context, AutoArt.PLAYLIST, PlaylistEntity.LIKED_PLAYLIST_ID), jump,
            )
        }
        val downloaded = downloadUtil.downloads.value.size
        if (downloaded > 1) {
            items += action(
                "${AutoBrowse.SHUFFLE}/${AutoBrowse.DOWNLOADED}", context.getString(R.string.auto_shuffle_downloads),
                context.getString(R.string.auto_shuffled, context.resources.getQuantityString(R.plurals.n_song, downloaded, downloaded)),
                AutoArt.uri(context, AutoArt.PLAYLIST, PlaylistEntity.DOWNLOADED_PLAYLIST_ID), jump,
            )
        }
        val names = mapOf(
            AutoBrowse.QUICK to R.string.quick_picks, AutoBrowse.KEEP to R.string.keep_listening, AutoBrowse.FORGOTTEN to R.string.forgotten_favorites,
        )
        for (section in AutoBrowse.SECTIONS) {
            val heading = context.getString(names.getValue(section))
            // The whole row plays on from the song tapped; only the first of it is shown.
            items += homeSongs(section).take(AutoBrowse.SHOWN).map { it.toMediaItem("${AutoBrowse.HOME}/$section", group = heading) }
        }
        return items
    }

    /**
     * The library's ways in: what was played lately, covers for albums and artists, a list for
     * songs. Under a [heading] of their own where the row of what is playing is above them, or
     * they read as more of it.
     */
    private fun carLibrary(heading: Boolean): List<MediaItem> {
        val browse = if (heading) context.getString(R.string.auto_browse) else null
        return listOf(
            browsableMediaItem(
                AutoBrowse.RECENT, context.getString(R.string.auto_recent), null, drawableUri(R.drawable.auto_recent),
                MediaMetadata.MEDIA_TYPE_PLAYLIST, style(playable = LIST, group = browse),
            ),
            browsableMediaItem(
                MusicService.ALBUM, context.getString(R.string.albums), null, drawableUri(R.drawable.album),
                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, style(playable = LIST, browsable = GRID, group = browse),
            ),
            browsableMediaItem(
                MusicService.ARTIST, context.getString(R.string.artists), null, drawableUri(R.drawable.artist),
                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, style(playable = LIST, browsable = GRID, group = browse),
            ),
            browsableMediaItem(
                MusicService.SONG, context.getString(R.string.songs), null, drawableUri(R.drawable.music_note),
                MediaMetadata.MEDIA_TYPE_PLAYLIST, style(playable = LIST, group = browse),
            ),
        )
    }

    /** Something that plays at a tap and is not a song: the queue carried on with, a list shuffled. */
    private fun action(id: String, title: String, subtitle: String?, art: Uri?, group: String? = null, single: Int? = null) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(subtitle)
                    .setArtworkUri(art)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setExtras(if (group != null || single != null) style(group = group, single = single) else null)
                    .build()
            )
            .build()

    private fun shuffleRow(playlistId: String, count: Int): MediaItem {
        val list = when (playlistId) {
            PlaylistEntity.LIKED_PLAYLIST_ID -> AutoBrowse.LIKED
            PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> AutoBrowse.DOWNLOADED
            else -> AutoBrowse.PLAYLIST_PREFIX + playlistId
        }
        return action(
            "${AutoBrowse.SHUFFLE}/$list", context.getString(R.string.shuffle),
            context.resources.getQuantityString(R.plurals.n_song, count, count), drawableUri(R.drawable.shuffle_on),
        )
    }

    /** The songs of a playlist, the liked and the downloaded ones being playlists for this. */
    private suspend fun playlistSongs(playlistId: String): List<Song> = when (playlistId) {
        PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> database.downloadNoLocalSongs()
        else -> database.playlistSongs(playlistId).map { list -> list.map { it.song } }
    }.first()

    /**
     * The library's songs as the car lists them and plays on through them: all of them, oldest
     * first, as it always was; since the rework the newest first, and no more than a driver
     * would ever scroll through.
     */
    private suspend fun librarySongs(): List<Song> {
        val all = database.songsByCreateDateAsc().first()
        return if (Unreleased.AUTO_REWORK) all.asReversed().take(AutoBrowse.MOST_SONGS) else all
    }

    /** One of Home's rows, the same queries Home and the widget use: what is shown is what plays. */
    private suspend fun homeSongs(section: String): List<Song> = when (section) {
        AutoBrowse.QUICK -> {
            val library = database.quickPicks().first()
            val shown = runCatching { WidgetStore.read(context).list(WidgetList.QUICK_PICKS).map { it.id } }.getOrDefault(emptyList())
            val byId = library.associateBy { it.id }
            AutoBrowse.picks(shown, library.map { it.id }).mapNotNull { byId[it] ?: database.song(it).first() }
        }

        AutoBrowse.KEEP -> database.mostPlayedSongs(System.currentTimeMillis() - 14L * 86_400_000L, limit = AutoBrowse.MOST).first()
        AutoBrowse.FORGOTTEN -> database.forgottenFavorites().first().take(AutoBrowse.MOST)
        else -> emptyList()
    }

    /** What was played lately, newest first, a song once however often it was played. */
    private suspend fun recentSongs(): List<Song> =
        database.historyPlays().first().mapNotNull { it.song }.distinctBy { it.id }.take(AutoBrowse.MOST_RECENT)

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
/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock
import android.provider.BaseColumns
import android.util.Log
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.AutoArt
import com.dd3boh.outertune.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The suggestions the phone's own search shows for InterTune (see [SystemSearch]): read only, the
 * library's first, then what YouTube Music adds once the words have three letters. The manifest
 * lets only a search holding GLOBAL_SEARCH read it, which One UI's home screen does and no
 * ordinary app can.
 */
class SystemSearchProvider : ContentProvider() {

    private val pacing = SystemSearch.Pacing(ONLINE_GAP_MS) { SystemClock.elapsedRealtime() }
    private val recent = SystemSearch.Recent<List<SystemSearch.Row>>(24)

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context?.applicationContext ?: return cursor
        val words = SystemSearch.words(uri.pathSegments, selectionArgs)
        val started = SystemClock.elapsedRealtime()
        if (words.isEmpty()) {
            // The shape of the request, never its content: a format this does not read may still
            // carry what somebody typed.
            Log.i(TAG, "Asked with no words: ${uri.pathSegments.size} path parts, " +
                "selection ${if (selection == null) "none" else "given"}, ${selectionArgs?.size ?: 0} arguments")
            return cursor
        }
        val ticket = pacing.ask()
        // A binder thread: the search waits for this, and gets nothing rather than a stall.
        val found = runCatching { runBlocking { withTimeoutOrNull(QUERY_MS) { rows(context, words) } } }
            .onFailure { Log.w(TAG, "Could not search the library for the phone's search", it) }
            .getOrNull()
        val online = if (!SystemSearch.goesOnline(words)) null else runCatching {
            runBlocking { withTimeoutOrNull(ONLINE_MS) { onlineRows(context, words, ticket) } }
        }.onFailure { Log.w(TAG, "Could not ask YouTube Music for the phone's search", it) }.getOrNull()
        // Which way the phone asked, what came back and how long it took: a search that shows
        // nothing for the app is otherwise silent.
        Log.i(TAG, "Asked by ${callingPackage ?: "?"} for ${words.length} letters: " +
            (found?.let { "${it.size} from the library" } ?: "the library not in time") + ", " +
            (online?.let { "${it.size} from YouTube Music" } ?: "YouTube Music not asked or not in time") +
            " in ${SystemClock.elapsedRealtime() - started} ms")
        val rows = SystemSearch.merge(found.orEmpty(), online.orEmpty()).take(SystemSearch.MOST)
        // Under one heading per kind, as Samsung Music's Tracks: the library's songs first, then
        // YouTube Music's, since a heading takes its rows in the order they come.
        val headings = mapOf(
            AutoArt.SONG to context.getString(R.string.songs), AutoArt.ARTIST to context.getString(R.string.artists),
            AutoArt.ALBUM to context.getString(R.string.albums), AutoArt.PLAYLIST to context.getString(R.string.playlists),
        )
        rows.forEachIndexed { i, row ->
            cursor.addRow(arrayOf<Any>(
                i, row.title, row.subtitle, AutoArt.uri(context, row.kind, row.id).toString(),
                Intent.ACTION_VIEW, row.link, SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT, headings[row.kind].orEmpty(),
            ))
        }
        return cursor
    }

    private suspend fun rows(context: Context, words: String): List<SystemSearch.Row> {
        val database = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).database()
        val songs = database.searchSongs(words, SystemSearch.SONGS * 2).first()
            .filter { SystemSearch.onYouTube(it.song.isLocal, it.song.id) }.take(SystemSearch.SONGS)
            .map { SystemSearch.Row(AutoArt.SONG, it.song.id, it.song.title, it.artists.joinToString { a -> a.name }, SystemSearch.songLink(it.song.id)) }
        val artists = database.searchArtists(words, SystemSearch.ARTISTS * 2).first()
            .filter { SystemSearch.onYouTube(it.artist.isLocal, it.artist.id) }.take(SystemSearch.ARTISTS)
            .map { SystemSearch.Row(AutoArt.ARTIST, it.artist.id, it.artist.name, context.getString(R.string.system_search_artist), SystemSearch.artistLink(it.artist.id)) }
        val albums = database.searchAlbums(words, SystemSearch.ALBUMS * 2).first()
            .filter { SystemSearch.onYouTube(it.album.isLocal, it.album.id) }.take(SystemSearch.ALBUMS)
            .map { SystemSearch.Row(AutoArt.ALBUM, it.album.id, it.album.title, context.getString(R.string.system_search_album, it.artists.joinToString { a -> a.name }), SystemSearch.albumLink(it.album.id)) }
        val playlists = database.searchPlaylists(words, SystemSearch.PLAYLISTS).first()
            .map { SystemSearch.Row(AutoArt.PLAYLIST, it.playlist.id, it.playlist.name, context.getString(R.string.system_search_playlist), SystemSearch.playlistLink(it.playlist.id)) }
        return songs + artists + albums + playlists
    }

    /**
     * What YouTube Music has for the words: a few songs, the top artist and the top album, from one
     * search. Only for the newest question once the typing has paused (see [SystemSearch.Pacing]),
     * and an answer is kept for the same words. What it finds is written into the table of songs,
     * artists and albums seen, as anything shown in the app is, so a tap can play it and AutoArt
     * can draw its cover; a row already there is left as it is.
     */
    private suspend fun onlineRows(context: Context, words: String, ticket: Long): List<SystemSearch.Row> {
        recent[words]?.let { return it }
        delay(SETTLE_MS)
        if (!pacing.isNewest(ticket)) return emptyList()
        val wait = pacing.wait()
        if (wait > 0) {
            delay(wait)
            if (!pacing.isNewest(ticket)) return emptyList()
        }
        val items = withContext(Dispatchers.IO) { YouTube.searchSummary(words) }
            .onFailure { Log.w(TAG, "YouTube Music gave the phone's search nothing", it) }
            .getOrNull()?.summaries.orEmpty().flatMap { it.items }
        if (items.isEmpty()) return emptyList()
        val database = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).database()
        val rows = withContext(Dispatchers.IO) {
            val songs = items.filterIsInstance<SongItem>().distinctBy { it.id }.take(SystemSearch.ONLINE_SONGS).map { song ->
                runCatching { database.insert(song.toMediaMetadata()) }
                SystemSearch.Row(AutoArt.SONG, song.id, song.title, song.artists.joinToString { it.name }, SystemSearch.songLink(song.id))
            }
            val artists = items.filterIsInstance<ArtistItem>().take(SystemSearch.ONLINE_ARTISTS).map { artist ->
                runCatching { database.insert(ArtistEntity(id = artist.id, name = artist.title, thumbnailUrl = artist.thumbnail)) }
                SystemSearch.Row(AutoArt.ARTIST, artist.id, artist.title, context.getString(R.string.system_search_artist), SystemSearch.artistLink(artist.id))
            }
            val albums = items.filterIsInstance<AlbumItem>().take(SystemSearch.ONLINE_ALBUMS).map { album ->
                runCatching {
                    database.insert(AlbumEntity(id = album.browseId, playlistId = album.playlistId, title = album.title, year = album.year,
                        thumbnailUrl = album.thumbnail, songCount = 0, duration = 0))
                }
                SystemSearch.Row(AutoArt.ALBUM, album.browseId, album.title,
                    context.getString(R.string.system_search_album, album.artists.orEmpty().joinToString { it.name }), SystemSearch.albumLink(album.browseId))
            }
            songs + artists + albums
        }
        recent[words] = rows
        return rows
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "SystemSearch"

        /** How long the phone's search is kept waiting for the library, at most. */
        private const val QUERY_MS = 1_500L

        /** How long it waits for YouTube Music on top, at most, the pause in the typing included. */
        private const val ONLINE_MS = 3_000L

        /** The pause in the typing before YouTube Music is asked. */
        private const val SETTLE_MS = 400L

        /** Never two searches of YouTube Music closer than this. */
        private const val ONLINE_GAP_MS = 1_000L

        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
            SystemSearch.GROUP_COLUMN,
        )
    }
}

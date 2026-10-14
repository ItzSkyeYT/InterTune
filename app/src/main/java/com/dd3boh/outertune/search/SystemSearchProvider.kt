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
import android.provider.BaseColumns
import android.util.Log
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.AutoArt
import com.dd3boh.outertune.widget.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The suggestions the phone's own search shows for InterTune (see [SystemSearch]): read only,
 * from the library on the phone, nothing asked of the network. The manifest lets only a search
 * holding GLOBAL_SEARCH read it, which One UI's home screen does and no ordinary app can.
 */
class SystemSearchProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context?.applicationContext ?: return cursor
        val words = SystemSearch.words(uri.pathSegments, selectionArgs)
        if (words.isEmpty()) return cursor
        // A binder thread: the search waits for this, and gets nothing rather than a stall.
        val rows = runCatching { runBlocking { withTimeoutOrNull(QUERY_MS) { rows(context, words) } } }
            .onFailure { Log.w(TAG, "Could not search the library for the phone's search", it) }
            .getOrNull().orEmpty()
        rows.forEachIndexed { i, row ->
            cursor.addRow(arrayOf<Any>(
                i, row.title, row.subtitle, AutoArt.uri(context, row.kind, row.id).toString(),
                Intent.ACTION_VIEW, row.link, SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT,
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

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "SystemSearch"

        /** How long the phone's search is kept waiting for the library, at most. */
        private const val QUERY_MS = 1_500L

        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
        )
    }
}

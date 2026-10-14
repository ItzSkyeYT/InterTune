/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.search

import java.net.URI

/**
 * What the phone's own search is given when it asks InterTune: songs, artists, albums and
 * playlists of the library whose names hold the words, each with the link that opens it here.
 *
 * One UI's search on the home screen ("Apps to search in") lists an app whose searchable says
 * includeInGlobalSearch, and reads the suggestions it serves; VLC is listed that way and YouTube,
 * whose searchable does not say it, is not. The searchable is res/xml/system_searchable.xml on
 * MainActivity, the suggestions come from SystemSearchProvider, which only a search holding
 * GLOBAL_SEARCH can read. Debug and preview builds only for now: the two manifests that declare
 * them are in src/debug and src/preview.
 */
object SystemSearch {

    /** How many of each the phone is given, at most: songs first, as most searches are for one. */
    const val SONGS = 5
    const val ARTISTS = 2
    const val ALBUMS = 2
    const val PLAYLISTS = 2

    /** The scheme of the one link of the app's own: a playlist of the library, which has no YouTube page. */
    const val APP_SCHEME = "intertune"

    /** One result as the phone shows it, under [kind] and [id] as AutoArt draws its cover. */
    data class Row(val kind: String, val id: String, val title: String, val subtitle: String, val link: String)

    /**
     * The words asked for: the path segment after search_suggest_query, where a searchable with
     * no selection puts them, or else the selection's argument.
     */
    fun words(pathSegments: List<String>, selectionArgs: Array<out String>?): String {
        val fromPath = pathSegments.dropWhile { it != SUGGEST_PATH }.drop(1).firstOrNull()
        return (fromPath ?: selectionArgs?.firstOrNull()).orEmpty().trim()
    }

    fun songLink(id: String) = "https://music.youtube.com/watch?v=$id"
    fun artistLink(id: String) = "https://music.youtube.com/channel/$id"
    fun albumLink(id: String) = "https://music.youtube.com/browse/$id"
    fun playlistLink(id: String) = "$APP_SCHEME://playlist/$id"

    /** The library playlist an "intertune://playlist/<id>" link names, or null for any other link. */
    fun playlistOf(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme != APP_SCHEME || uri.host != "playlist") return null
        return uri.path.orEmpty().trim('/').takeIf { it.matches(ID) }
    }

    /** Whether a library id is YouTube's, so its link opens it: a local file's is not. */
    fun onYouTube(isLocal: Boolean, id: String): Boolean = !isLocal && id.matches(ID)

    private const val SUGGEST_PATH = "search_suggest_query"
    private val ID = Regex("[A-Za-z0-9_-]+")
}

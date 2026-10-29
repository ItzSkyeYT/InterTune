/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.search

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the phone's own search is given when it asks InterTune: songs, artists, albums and
 * playlists of the library whose names hold the words, then what YouTube Music adds for words of
 * three letters or more, each with the link that opens it here. Whatever is typed in the phone's
 * search goes to YouTube Music while InterTune is switched on there, as it does to Spotify.
 *
 * One UI's search on the home screen ("Apps to search in") lists an app whose searchable says
 * includeInGlobalSearch, and reads the suggestions it serves; VLC is listed that way and YouTube,
 * whose searchable does not say it, is not. The searchable is res/xml/system_searchable.xml on
 * MainActivity, the suggestions come from SystemSearchProvider, which only a search holding
 * GLOBAL_SEARCH can read. Debug and preview builds only for now: the two manifests that declare
 * them are in src/debug and src/preview.
 */
object SystemSearch {

    /**
     * How many of each the phone is given, at most: songs first, as most searches are for one. With
     * what YouTube Music adds, twenty in all, which is as many as One UI shows of one app.
     */
    const val SONGS = 6
    const val ARTISTS = 2
    const val ALBUMS = 2
    const val PLAYLISTS = 2

    /** The app's own scheme: a playlist of the library, which has no YouTube page, and a song to play now (songLink). */
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

    /** A tapped song plays, where a song link from elsewhere only opens it (YouTubeLink.Video.playNow). */
    fun songLink(id: String) = "$APP_SCHEME://play?v=$id"
    fun artistLink(id: String) = "https://music.youtube.com/channel/$id"
    fun albumLink(id: String) = "https://music.youtube.com/browse/$id"
    fun playlistLink(id: String) = "$APP_SCHEME://playlist/$id"

    /** The library playlist an "intertune://playlist/<id>" link names, or null for any other link. */
    fun playlistOf(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme != APP_SCHEME || uri.host != "playlist") return null
        return uri.path.orEmpty().trim('/').takeIf { it.matches(ID) }
    }

    /**
     * The link that opens the app's own search for [words]: the phone's search hands them over when
     * InterTune is chosen to search in (ACTION_SEARCH, the words in SearchManager.QUERY), and
     * MainActivity opens links, not words.
     */
    fun searchLink(words: String): String = "$APP_SCHEME://search/" + URLEncoder.encode(words.trim(), "UTF-8")

    /** The link for an intent, when it is the phone's search handing words over, or null. */
    fun searchLinkFor(action: String?, words: String?): String? =
        if (action == ACTION_SEARCH && !words.isNullOrBlank()) searchLink(words) else null

    /** The words a [searchLink] carries, or null for any other link. */
    fun searchOf(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme != APP_SCHEME || uri.host != "search") return null
        val raw = uri.rawPath.orEmpty().removePrefix("/")
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Intent.ACTION_SEARCH, written out so that this stays free of Android and can be tested. */
    private const val ACTION_SEARCH = "android.intent.action.SEARCH"

    /** Whether a library id is YouTube's, so its link opens it: a local file's is not. */
    fun onYouTube(isLocal: Boolean, id: String): Boolean = !isLocal && id.matches(ID)

    /** Words from this many letters go to YouTube Music too, as the car's search does: fewer find nothing worth it. */
    const val ONLINE_MIN_LETTERS = 3

    /** What YouTube Music adds at most, after the library's. */
    const val ONLINE_SONGS = 6
    const val ONLINE_ARTISTS = 1
    const val ONLINE_ALBUMS = 1

    fun goesOnline(words: String): Boolean = words.trim().length >= ONLINE_MIN_LETTERS

    /** The library's rows first, then what YouTube Music found that the library did not already give. */
    fun merge(library: List<Row>, online: List<Row>): List<Row> {
        val given = library.mapTo(HashSet()) { it.link }
        return library + online.filter { given.add(it.link) }
    }

    /**
     * When YouTube Music is asked. The phone asks again at every letter typed, and every question
     * sent on would be a search from this address: a burst of them was followed by forty minutes
     * of bot checks on 4 Oct 2026. So only the newest question goes ([isNewest], after the typing
     * has paused), and never sooner than [gapMs] after the one before ([wait]).
     */
    class Pacing(private val gapMs: Long, private val now: () -> Long) {
        private var latest = 0L
        private var lastAt = Long.MIN_VALUE / 2

        /** A question has come in: its number, newer than every one before it. */
        @Synchronized fun ask(): Long = ++latest

        @Synchronized fun isNewest(ticket: Long): Boolean = ticket == latest

        /** How long to wait before asking YouTube Music now; the time it will be asked is taken. */
        @Synchronized fun wait(): Long {
            val time = now()
            val at = maxOf(time, lastAt + gapMs)
            lastAt = at
            return at - time
        }
    }

    /** The answers to the last few words asked, so typing back and forth asks nothing again. */
    class Recent<V>(private val size: Int) {
        private val kept = object : LinkedHashMap<String, V>(size, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = this.size > this@Recent.size
        }

        @Synchronized operator fun get(words: String): V? = kept[key(words)]
        @Synchronized operator fun set(words: String, value: V) { kept[key(words)] = value }
        private fun key(words: String) = words.trim().lowercase()
    }

    /** As many rows as One UI shows of one app: its home screen stops reading at twenty. */
    const val MOST = 20

    /**
     * The column One UI reads to put an app's results under headings, as Samsung Music's "Tracks":
     * rows with the same value go under one heading, in the order they first come.
     *
     * The big cards with large covers that Samsung Music and Spotify get are not on offer: One UI's
     * home screen asks a list of its own apps and Spotify for them by name, and every other app
     * gets plain rows (read from the home screen's code on One UI 8, 14 Oct 2026).
     */
    const val GROUP_COLUMN = "suggest_group"

    private const val SUGGEST_PATH = "search_suggest_query"
    private val ID = Regex("[A-Za-z0-9_-]+")
}

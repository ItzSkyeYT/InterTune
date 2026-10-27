/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.net.URI
import java.net.URLDecoder

/** A YouTube or YouTube Music link the app can open itself, or the share page's, which carries one. */
sealed interface YouTubeLink {
    data class Playlist(val id: String) : YouTubeLink
    data class Channel(val id: String) : YouTubeLink
    /**
     * A song. A link from outside (the share page, a friend's message, the search box) opens it for
     * the listener to choose what to do with it ([playNow] false); only the app's own play link,
     * which the phone's search hands over for a tapped result, plays it at once.
     */
    data class Video(val id: String, val playlistId: String?, val playNow: Boolean = false) : YouTubeLink
    /** An album's page, by the id YouTube Music browses it under ("MPREb_..."). */
    data class Album(val id: String) : YouTubeLink

    companion object {
        private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
        private const val SHORT_HOST = "youtu.be"

        /**
         * The app's own links, which the share page (intertune.app/s/) opens it with: the same pages
         * as YouTube's, the page's name where the host would be (intertune://watch?v=...,
         * intertune://playlist?list=...). Brave refuses a page's link to a YouTube address while it
         * keeps YouTube's links for itself ("Open YouTube links in Brave", brave-browser#46614), and
         * the share page fell on an error there.
         */
        const val OWN_SCHEME = "intertune"

        /**
         * The share page's site. A link to the page tapped in a chat opens the app (the manifest's
         * verified filter for intertune.app/s), and only the page is a link to open: the rest of
         * the site is not.
         */
        private val SITE_HOSTS = setOf("intertune.app", "www.intertune.app")

        /** The characters of YouTube's ids. Anything else is not one, and would not survive a route. */
        private val ID = Regex("[A-Za-z0-9_-]+")

        /** The share page's own rules for its ids (site/s/index.html): a song's has 11 characters, a list's 10 to 80. */
        private val PAGE_SONG = Regex("[A-Za-z0-9_-]{11}")
        private val PAGE_LIST = Regex("[A-Za-z0-9_-]{10,80}")

        /**
         * The link in [text], or null when it is not one.
         *
         * Everything typed into the search went through here, and plain words were taken for
         * links: "watch" parsed as a path with no video id, "c" and "channel" as a channel page,
         * "playlist" as a playlist with no id, and the search did nothing or opened a blank page.
         * Only an http or https address on a YouTube host, or the share page's, counts now.
         */
        fun parse(text: String): YouTubeLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != OWN_SCHEME && scheme !in setOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val path = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
            val query = pairs(uri.rawQuery).toMap()
            val list = query["list"]?.takeIf { it.matches(ID) }

            if (scheme != OWN_SCHEME && host == SHORT_HOST) {
                return path.singleOrNull()?.takeIf { it.matches(ID) }?.let { Video(it, list) }
            }
            if (scheme != OWN_SCHEME && host in SITE_HOSTS) return sharePage(path, uri.rawFragment)
            if (scheme != OWN_SCHEME && host !in HOSTS) return null
            // On the app's own links the page's name stands where YouTube's host would be.
            val segments = if (scheme == OWN_SCHEME) listOf(host) + path else path
            return when (segments.firstOrNull()) {
                "playlist" -> list?.let(::Playlist)
                "channel", "c" -> segments.getOrNull(1)?.takeIf { it.matches(ID) }?.let(::Channel)
                "watch" -> query["v"]?.takeIf { it.matches(ID) }?.let { Video(it, list) }
                "play" -> if (scheme != OWN_SCHEME) null else query["v"]?.takeIf { it.matches(ID) }?.let { Video(it, list, playNow = true) }
                // Only an album's: the other pages YouTube Music browses have links of their own above.
                "browse" -> segments.getOrNull(1)?.takeIf { it.matches(ID) && it.startsWith("MPRE") }?.let(::Album)
                else -> null
            }
        }

        /**
         * The share page's link (ShareLinks.song and .album make them, intertune.app/s/#v=...): what
         * it opens is after the #, in the page's own keys, v= a song and l= a list, an album's for
         * one. The name, the artists, the length and the kind beside them (t=, a=, d=, k=) are for
         * the page to show. With both, it is the song, as on the page.
         */
        private fun sharePage(path: List<String>, fragment: String?): YouTubeLink? {
            if (path != listOf("s")) return null
            // Split before anything is decoded: a name can hold an & or an = of its own.
            val keys = pairs(fragment)
            // The first of a key, as the page's URLSearchParams.get takes it.
            fun key(name: String) = keys.firstOrNull { it.first == name }?.second
            val song = key("v")?.takeIf { it.matches(PAGE_SONG) }
            val list = key("l")?.takeIf { it.matches(PAGE_LIST) }
            return when {
                song != null -> Video(song, null)
                list != null -> Playlist(list)
                else -> null
            }
        }

        /** The names and values of "a=1&b=2", each decoded. A part with no = in it is left out. */
        private fun pairs(raw: String?): List<Pair<String, String>> = raw.orEmpty().split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            if (name.isEmpty() || '=' !in pair) null else decode(name) to decode(pair.substringAfter('='))
        }

        private fun decode(part: String): String = runCatching { URLDecoder.decode(part, "UTF-8") }.getOrDefault(part)
    }
}

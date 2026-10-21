/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.net.URI
import java.net.URLDecoder

/** A YouTube or YouTube Music link the app can open itself. */
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

        /** The characters of YouTube's ids. Anything else is not one, and would not survive a route. */
        private val ID = Regex("[A-Za-z0-9_-]+")

        /**
         * The link in [text], or null when it is not one.
         *
         * Everything typed into the search went through here, and plain words were taken for
         * links: "watch" parsed as a path with no video id, "c" and "channel" as a channel page,
         * "playlist" as a playlist with no id, and the search did nothing or opened a blank page.
         * Only an http or https address on a YouTube host counts now.
         */
        fun parse(text: String): YouTubeLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != OWN_SCHEME && scheme !in setOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val path = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name.isEmpty() || '=' !in pair) null else decode(name) to decode(pair.substringAfter('='))
            }.toMap()
            val list = query["list"]?.takeIf { it.matches(ID) }

            if (scheme != OWN_SCHEME && host == SHORT_HOST) {
                return path.singleOrNull()?.takeIf { it.matches(ID) }?.let { Video(it, list) }
            }
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

        private fun decode(part: String): String = runCatching { URLDecoder.decode(part, "UTF-8") }.getOrDefault(part)
    }
}

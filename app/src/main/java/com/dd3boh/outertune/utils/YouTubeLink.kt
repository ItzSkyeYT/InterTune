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

        /**
         * The characters of YouTube's ids. Anything else is not one, and would not survive a route. Nor would the
         * word null, which Navigation reads in a route as no value at all: a link with list=null in it opened
         * online_playlist/null, and the app closed (#244).
         */
        private val ID = Regex("(?!null$)[A-Za-z0-9_-]+")

        /** The share page's own rules for its ids (site/s/index.html): a song's has 11 characters, a list's 10 to 80. */
        private val PAGE_SONG = Regex("[A-Za-z0-9_-]{11}")
        private val PAGE_LIST = Regex("[A-Za-z0-9_-]{10,80}")

        /** A video's id, as YouTube makes them: 11 characters. The addresses with the id in the path take only that. */
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** The YouTube app's own scheme, which the manifest takes as well: vnd.youtube:<id>, or a YouTube address after it. */
        private val APP_SCHEMES = setOf("vnd.youtube", "vnd.youtube.launch")

        /** A web address inside other words. What closes a bracket or a quote around it is not part of it. */
        private val LINK_IN_TEXT = Regex("""https?://[^\s<>"'()\[\]{}]+""", RegexOption.IGNORE_CASE)

        /** The end of a sentence after an address, which no YouTube address ends with. */
        private val SENTENCE_END = charArrayOf('.', ',', ';', ':', '!', '?')

        /**
         * The link in [text], or null when it is not one.
         *
         * Everything typed into the search went through here, and plain words were taken for
         * links: "watch" parsed as a path with no video id, "c" and "channel" as a channel page,
         * "playlist" as a playlist with no id, and the search did nothing or opened a blank page.
         * Only an http or https address on a YouTube host, or the share page's, counts now.
         *
         * With words around it (the title another app's Share puts before its link, "look at this",
         * brackets, a full stop), the first address in them that opens something. The whole text was
         * taken for the address, which it is not, and nothing opened and nothing was said.
         */
        fun parse(text: String): YouTubeLink? {
            val whole = text.trim()
            return address(whole) ?: LINK_IN_TEXT.findAll(whole).firstNotNullOfOrNull { address(it.value.trimEnd(*SENTENCE_END)) }
        }

        /** What one address opens, or null. */
        private fun address(text: String): YouTubeLink? {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme in APP_SCHEMES) return youtubeApp(uri.rawSchemeSpecificPart)
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
                // A Short, a live's own page, and the old player's address: the video's id comes next. Only YouTube's,
                // and the manifest hands the app /v/ and /embed/ links: they opened Home and nothing else.
                "shorts", "live", "v" -> if (scheme == OWN_SCHEME) null else idAfter(segments)?.let { Video(it, list) }
                // A video in another site's page; the whole of a list in one is the list.
                "embed" -> when {
                    scheme == OWN_SCHEME -> null
                    segments.getOrNull(1) == "videoseries" -> list?.let(::Playlist)
                    else -> idAfter(segments)?.let { Video(it, list) }
                }
                // Only an album's: the other pages YouTube Music browses have links of their own above.
                "browse" -> segments.getOrNull(1)?.takeIf { it.matches(ID) && it.startsWith("MPRE") }?.let(::Album)
                else -> null
            }
        }

        /** The video's id in the part after the page's name. The old player's addresses put their options after an &. */
        private fun idAfter(segments: List<String>): String? =
            segments.getOrNull(1)?.substringBefore('&')?.takeIf { it.matches(VIDEO_ID) }

        /**
         * The YouTube app's links (vnd.youtube:<id>, vnd.youtube://<id>), which the manifest takes and which opened
         * nothing: the video, or the YouTube address one carries after the scheme.
         */
        private fun youtubeApp(rest: String?): YouTubeLink? {
            val part = rest.orEmpty().removePrefix("//")
            val id = part.substringBefore('?').substringBefore('&').substringBefore('#').substringBefore('/')
            if (id.matches(VIDEO_ID)) return Video(id, null)
            return if ('/' in part) address("https://$part") else null
        }

        /**
         * The share page's link (ShareLinks.song and .album make them): a song's id in the path since
         * the version after 0.12.6 (intertune.app/s/v/<id>#t=...), before that after the #
         * (intertune.app/s/#v=...), in the page's own keys, v= a song and l= a list, an album's for
         * one. The name, the artists, the length and the kind beside them (t=, a=, d=, k=) are for
         * the page to show. With both, it is the song, as on the page.
         */
        private fun sharePage(path: List<String>, fragment: String?): YouTubeLink? {
            if (path.size == 3 && path[0] == "s" && path[1] == "v") return path[2].takeIf { it.matches(PAGE_SONG) }?.let { Video(it, null) }
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

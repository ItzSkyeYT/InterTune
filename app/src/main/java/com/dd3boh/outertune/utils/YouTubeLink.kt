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
    data class Video(val id: String, val playlistId: String?) : YouTubeLink

    companion object {
        private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
        private const val SHORT_HOST = "youtu.be"

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
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=')
                if (name.isEmpty() || '=' !in pair) null else decode(name) to decode(pair.substringAfter('='))
            }.toMap()
            val list = query["list"]?.takeIf { it.matches(ID) }

            if (host == SHORT_HOST) {
                return segments.singleOrNull()?.takeIf { it.matches(ID) }?.let { Video(it, list) }
            }
            if (host !in HOSTS) return null
            return when (segments.firstOrNull()) {
                "playlist" -> list?.let(::Playlist)
                "channel", "c" -> segments.getOrNull(1)?.takeIf { it.matches(ID) }?.let(::Channel)
                "watch" -> query["v"]?.takeIf { it.matches(ID) }?.let { Video(it, list) }
                else -> null
            }
        }

        private fun decode(part: String): String = runCatching { URLDecoder.decode(part, "UTF-8") }.getOrDefault(part)
    }
}

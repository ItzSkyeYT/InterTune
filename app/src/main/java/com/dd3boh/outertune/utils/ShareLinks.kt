/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import java.net.URLEncoder

/** What Share sends for a song or an album. */
enum class ShareLinkKind {
    /** The YouTube Music link, as always. */
    YOUTUBE_MUSIC,

    /** The share page, where whoever opens it picks their own music app. */
    PAGE,

    /**
     * Not chosen yet, where everybody starts ([ShareLinks.START]): the next share that has a page
     * asks, and the pick is kept as the setting, so the shares after it go straight through
     * (rememberShareLink). Settings offers the two above and shows this one as not chosen.
     *
     * Before 0.12.5 a debug or preview build offered it as "Ask each time" and asked at every
     * share. Whoever picked it there is asked once more, and that answer is kept like anyone's.
     */
    ASK,
}

/**
 * The two ways one thing can be shared. [page] is null where the share page has nothing to offer
 * (a local file, an artist, a playlist); [caption] is the name that goes with the page's link,
 * because a chat cannot show the song from the link alone.
 */
data class ShareLink(val youTubeMusic: String, val page: String?, val caption: String)

sealed interface ShareAction {
    data class Send(val text: String) : ShareAction
    data object Ask : ShareAction
}

/**
 * Builds the links and nothing more: InterTune contacts nobody to share. The page finds the song
 * on the other services itself, in the browser of whoever opens the link, from what the link
 * carries after the #: the id, the name, the artists and the length. Browsers do not send that
 * part to the page's host.
 */
object ShareLinks {
    /** Where the share page lives: on the site since 20 Oct 2026, offered in every build from 0.12.5 (Unreleased.SHARE_PAGE). */
    const val PAGE = "https://intertune.app/s/"

    /**
     * What "Links you share" says until somebody picks: nothing yet, so the next share asks
     * (decided on 23 Oct 2026). Share and the row in Settings both read the setting with this as
     * its default, so that the two never tell different stories.
     */
    val START = ShareLinkKind.ASK

    /**
     * What Settings offers. Not [START]: a pick in Settings is as lasting as one made in the
     * question, and a row offering "ask" that then asked only once would say one thing and do another.
     */
    val CHOICES = listOf(ShareLinkKind.YOUTUBE_MUSIC, ShareLinkKind.PAGE)

    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    /** An album's own playlist. The page cannot open an album by its browse id (MPREb_). */
    private val albumPlaylistId = Regex("OLAK5uy_[A-Za-z0-9_-]{20,}")

    // The page keeps 200 characters of a name and eight artists, so no more is sent.
    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    private fun named(title: String, artists: List<String>): Pair<String, String> =
        title.trim().take(200) to artists.map { it.trim() }.filter { it.isNotEmpty() }.take(8).joinToString(", ")

    /** [albumTrack]: the song is a track of an album, not a video. The page is stricter about the length of those. */
    fun song(id: String, title: String, artists: List<String>, seconds: Int, albumTrack: Boolean): ShareLink {
        val (name, by) = named(title, artists)
        val page = if (!videoId.matches(id) || name.isEmpty()) null else buildString {
            append(PAGE).append("#v=").append(id).append("&t=").append(enc(name))
            if (by.isNotEmpty()) append("&a=").append(enc(by))
            if (seconds > 0) append("&d=").append(seconds)
            if (albumTrack) append("&k=t")
        }
        return ShareLink("https://music.youtube.com/watch?v=$id", page, caption(name, by))
    }

    /** [link] is what the album is shared as today, kept as it is. */
    fun album(link: String, playlistId: String?, title: String, artists: List<String>): ShareLink {
        val (name, by) = named(title, artists)
        val page = if (playlistId == null || !albumPlaylistId.matches(playlistId) || name.isEmpty()) null else buildString {
            append(PAGE).append("#l=").append(playlistId).append("&t=").append(enc(name))
            if (by.isNotEmpty()) append("&a=").append(enc(by))
        }
        return ShareLink(link, page, caption(name, by))
    }

    private fun caption(name: String, by: String) = if (by.isEmpty()) name else "$name · $by"

    /** What goes out for [kind]. Where there is no page, the YouTube Music link whatever was chosen. */
    fun text(link: ShareLink, kind: ShareLinkKind): String =
        if (kind == ShareLinkKind.PAGE && link.page != null) "${link.caption}\n${link.page}" else link.youTubeMusic

    /** Asked only where there is a choice to make: nothing is chosen yet and there is a page. */
    fun decide(link: ShareLink, kind: ShareLinkKind): ShareAction =
        if (kind == ShareLinkKind.ASK && link.page != null) ShareAction.Ask else ShareAction.Send(text(link, kind))
}

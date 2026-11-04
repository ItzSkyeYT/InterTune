/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.Song
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * An artist the listener blocked, under Settings > Library and content > Blocked artists.
 *
 * @param id YouTube's id for the artist (UC...), a local file artist's own (LA...), or null when
 *   only the name is known, as for a name in a song's credits that links nowhere.
 * @param at when it was blocked, in milliseconds since 1970.
 */
data class BlockedArtist(
    val id: String?,
    val name: String,
    val thumbnail: String? = null,
    val at: Long = 0L,
)

/**
 * The blocked artists, as everything that hides them asks about them (Unreleased.ARTIST_BLOCK).
 *
 * An artist is blocked by YouTube's id. One with no id of YouTube's, a name in the credits that
 * links nowhere or a local file's artist, is blocked by its name in lower case. An artist blocked
 * with no id of YouTube's, from a local file, is known by its name alone, so its name blocks it
 * everywhere. A song, an album or a video is blocked when any of its artists is, a feature
 * included, and so is the artist's own item.
 *
 * Unlike the explicit and video switches, this reaches what the listener has as well: stored
 * songs keep their artists (SongArtistMap). Kept as one setting ([encode]), so a backup has it.
 */
data class ArtistBlock(val artists: List<BlockedArtist> = emptyList()) {
    /** YouTube's ids, and the local ids, of the artists blocked. */
    private val ids: Set<String> = artists.mapNotNullTo(HashSet()) { it.id }

    /** The names of every artist blocked, for an artist with no id of YouTube's. */
    private val names: Set<String> = artists.mapTo(HashSet()) { key(it.name) }

    /** The names of the artists blocked with no id of YouTube's, which only a name can match. */
    private val namesOnly: Set<String> = artists.filter { !isYouTubeId(it.id) }.mapTo(HashSet()) { key(it.name) }

    val isEmpty: Boolean get() = artists.isEmpty()

    /** Whether the artist with [id] and [name] is blocked. */
    fun blocks(id: String?, name: String?): Boolean {
        if (isEmpty) return false
        val named = name?.let(::key)?.takeIf { it.isNotEmpty() }
        return if (id != null && isYouTubeId(id)) {
            id in ids || (named != null && named in namesOnly)
        } else {
            (id != null && id in ids) || (named != null && named in names)
        }
    }

    /** The blocked artist [query] names, written the way the list has it give or take case and spaces, or null. */
    fun named(query: String): BlockedArtist? {
        val asked = key(query)
        if (asked.isEmpty()) return null
        return artists.firstOrNull { key(it.name) == asked }
    }

    /** The entry that blocks the artist with [id] and [name], as [blocks] finds it, or null. */
    fun entry(id: String?, name: String?): BlockedArtist? {
        if (isEmpty) return null
        val named = name?.let(::key)?.takeIf { it.isNotEmpty() }
        val byName = named?.let { n -> { a: BlockedArtist -> key(a.name) == n } }
        return if (id != null && isYouTubeId(id)) {
            artists.firstOrNull { it.id == id } ?: byName?.let { matches -> artists.firstOrNull { !isYouTubeId(it.id) && matches(it) } }
        } else {
            artists.firstOrNull { id != null && it.id == id } ?: byName?.let { matches -> artists.firstOrNull(matches) }
        }
    }

    fun hides(item: YTItem): Boolean = !isEmpty && when (item) {
        is SongItem -> item.artists.any { blocks(it.id, it.name) }
        is AlbumItem -> item.artists.orEmpty().any { blocks(it.id, it.name) }
        is ArtistItem -> blocks(item.id, item.title)
        is PlaylistItem -> item.author?.let { blocks(it.id, it.name) } == true
    }

    fun hides(song: Song): Boolean = !isEmpty && song.artists.any(::blocks)

    fun hides(song: MediaMetadata): Boolean = !isEmpty && song.artists.any { blocks(it.id, it.name) }

    /** A song, an album or an artist of the library; a playlist is the listener's own and stays. */
    fun hides(item: LocalItem): Boolean = !isEmpty && when (item) {
        is Song -> hides(item)
        is Album -> item.artists.any(::blocks)
        is Artist -> blocks(item.artist)
        is Playlist -> false
    }

    private fun blocks(artist: ArtistEntity) = blocks(artist.id, artist.name)

    /** [items] without what is blocked, the same list when nothing is. */
    fun <T : YTItem> apply(items: List<T>): List<T> = if (isEmpty) items else items.filterNot(::hides)

    /** The library's [items] without what is blocked, the same list when nothing is. */
    fun <T : LocalItem> library(items: List<T>): List<T> = if (isEmpty) items else items.filterNot { hides(it) }

    /** [songs] without what is blocked, the same list when nothing is. */
    fun songs(songs: List<MediaMetadata>): List<MediaMetadata> = if (isEmpty) songs else songs.filterNot { hides(it) }

    companion object {
        val NONE = ArtistBlock()

        /** A name as it is compared: in lower case, with its spaces trimmed and run together. */
        fun key(name: String): String = name.trim().replace(SPACES, " ").lowercase(Locale.ROOT)

        private val SPACES = Regex("""\s+""")

        /** An id of YouTube's for an artist: a channel, or the artist of the listener's own uploads. */
        fun isYouTubeId(id: String?): Boolean =
            id != null && (id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist"))

        /** [list] with [artist] first, and no other entry for the same artist. */
        fun add(list: List<BlockedArtist>, artist: BlockedArtist): List<BlockedArtist> =
            listOf(artist) + list.filterNot { it.sameAs(artist) }

        /** [list] without [artist]. */
        fun remove(list: List<BlockedArtist>, artist: BlockedArtist): List<BlockedArtist> =
            list.filterNot { it.sameAs(artist) }

        /** The same artist: the same id, or with no id of YouTube's on either, the same name. */
        private fun BlockedArtist.sameAs(other: BlockedArtist): Boolean =
            (id != null && id == other.id) ||
                (!isYouTubeId(id) && !isYouTubeId(other.id) && key(name) == key(other.name))

        fun encode(list: List<BlockedArtist>): String = buildJsonArray {
            for (a in list) add(buildJsonObject {
                a.id?.let { put("id", it) }
                put("name", a.name)
                a.thumbnail?.let { put("thumbnail", it) }
                put("at", a.at)
            })
        }.toString()

        /** What [encode] wrote. An entry it cannot read is left out rather than failing every list. */
        fun decode(text: String?): List<BlockedArtist> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element ->
                runCatching {
                    val o = element.jsonObject
                    fun text(key: String) = o[key]?.jsonPrimitive?.contentOrNull
                    val name = text("name")?.takeIf { it.isNotBlank() } ?: return@runCatching null
                    BlockedArtist(id = text("id"), name = name, thumbnail = text("thumbnail"), at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L)
                }.getOrNull()
            }
        }

        @Volatile
        private var lastRead: Pair<String?, ArtistBlock> = null to NONE

        /** [decode] as an ArtistBlock, read again only when the text changes: settings are read at every change of any of them. */
        fun read(text: String?): ArtistBlock {
            val last = lastRead
            if (last.first == text) return last.second
            return ArtistBlock(decode(text)).also { lastRead = text to it }
        }
    }
}

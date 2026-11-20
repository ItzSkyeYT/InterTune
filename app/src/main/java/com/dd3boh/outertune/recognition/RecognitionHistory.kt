/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.RecognitionHistoryKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One song this phone has heard in a room, and when.
 *
 * [videoId] is null whenever Shazam named the track but the YouTube search could not place it,
 * which is a real and fairly common outcome: nothing came back, or what came first was not plainly
 * the song ([idFor]). The entry is still worth keeping: knowing the app heard something at a
 * particular moment is most of the value, and the song can be searched for by name later even
 * when nothing was playable at the time.
 */
data class Heard(
    val title: String,
    val artist: String,
    val videoId: String?,
    val at: Long,
    /**
     * Whether [videoId] went through [idFor], as everything saved from 0.12.5 on has. Before, the
     * first song the search returned was kept whatever it was: "I Think Of You (Unreleased)" by
     * Sarsh was saved with "Sarah" by Jasiah, and a tap played that. Those entries say false.
     */
    val checked: Boolean,
    /**
     * The cover Shazam gave with the song, kept from 0.13 on: the row's picture when the song has
     * no video to show one of. Null in every entry saved before, and when Shazam gave none.
     */
    val cover: String? = null,
) {
    /**
     * The video a tap on this entry plays, or null to search for it by name instead.
     *
     * An entry saved before ids were checked plays its video only when the library's row of that
     * video is the song, by the same rule. The row is there once the video has played, under its
     * own name, which is how a wrong one shows. With no row, nothing on the phone says what the
     * video is, so it searches: one tap more, and never the wrong song.
     *
     * @param known the library's song under [videoId], when it holds one.
     */
    internal fun idToPlay(known: MediaMetadata?): String? {
        val id = videoId ?: return null
        if (checked) return id
        val song = known?.takeIf { it.id == id } ?: return null
        return if (corresponds(title, artist, song.title, song.artists.map { it.name })) id else null
    }

    /**
     * The picture of this entry's row when the library does not hold its song, given [videoId], the
     * video a tap plays ([idToPlay]): YouTube's frame of that video, or else the [cover] Shazam gave,
     * or none. Never the frame of a video the entry is not trusted with: before 0.12.5 that could be
     * another song's.
     */
    internal fun picture(videoId: String?): String? = videoId?.let { frameOf(it) } ?: cover

    companion object {
        /**
         * YouTube's still of [videoId] at 16:9 with no black bands: an album's track is its cover in
         * the middle, a music video a frame. Drawn cut to the square of a song's cover.
         */
        internal fun frameOf(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

        /**
         * The video kept for [track], given [first], the first song YouTube's search for it
         * returned: that one's only when it [corresponds], the check the engine makes before
         * adding it, so a tap on the entry never plays another song. Not the rest of that check:
         * a DJ mix is never added, but one that corresponds is the recording that was playing.
         */
        internal fun idFor(track: Recognised, first: SongItem?): String? =
            first?.takeIf { corresponds(track, it) }?.id
    }
}

/**
 * Everything recognition has ever heard, kept across restarts.
 *
 * In DataStore as a JSON array rather than in Room. The whole record is a few hundred short rows
 * that are only ever appended to, read back whole and occasionally cleared: none of that needs a
 * table, an index or a schema version, and a Room table would have meant a migration on a database
 * that carries the person's entire library. Written by hand with kotlinx.serialization's tree, as
 * RecogniseLater is: the app module does not apply the serialization plugin, and its unit tests
 * stub org.json, which this used until 0.13. The text is the same either way.
 */
@Singleton
class RecognitionHistory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Oldest entries fall off the end.
     *
     * A cap exists because this is one preference value, read and rewritten whole on every append.
     * Two hundred is far more than anybody will scroll and still trivial to parse.
     */
    private val limit = 200

    val entries: Flow<List<Heard>> = context.dataStore.data.map { prefs ->
        decode(prefs[RecognitionHistoryKey])
    }

    suspend fun add(entry: Heard) {
        context.dataStore.edit { prefs ->
            prefs[RecognitionHistoryKey] = encode(merged(decode(prefs[RecognitionHistoryKey]), entry, limit))
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.remove(RecognitionHistoryKey) }
    }

    companion object {
        /**
         * The entries in [raw], as kept, or none when it is not a list this wrote. Every field
         * has the fallback it always had, so an entry from any earlier version reads as it did:
         * no "checked" (before 0.12.5) is false, no "cover" (before 0.13) is null.
         */
        internal fun decode(raw: String?): List<Heard> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                Json.parseToJsonElement(raw).jsonArray.map { element ->
                    val o = element.jsonObject
                    Heard(
                        title = o.text("title").orEmpty(),
                        artist = o.text("artist").orEmpty(),
                        videoId = o.text("videoId")?.takeIf { it.isNotEmpty() },
                        at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L,
                        // Missing from every entry saved before 0.12.5, which is what tells them apart.
                        checked = o["checked"]?.jsonPrimitive?.booleanOrNull ?: false,
                        cover = o.text("cover")?.takeIf { it.isNotEmpty() },
                    )
                }
            }.getOrElse {
                // A value that will not parse is a value written by a build that is not this one. It
                // is dropped rather than crashing the read, because losing a list of song titles is
                // not worth taking the screen down for.
                Log.w("RecognitionHistory", "Could not read the stored history, starting over", it)
                emptyList()
            }
        }

        /** [entries] as the text [decode] reads. A cover is written only when there is one. */
        internal fun encode(entries: List<Heard>): String = buildJsonArray {
            entries.forEach {
                add(buildJsonObject {
                    put("title", it.title)
                    put("artist", it.artist)
                    put("videoId", it.videoId ?: "")
                    put("at", it.at)
                    put("checked", it.checked)
                    it.cover?.let { cover -> put("cover", cover) }
                })
            }
        }.toString()

        private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        /**
         * [entries], newest first, with [entry] in its place by when it was heard, and no more than
         * [limit] of them.
         *
         * Re-hearing the same song as it keeps playing should not fill the list with it, so the
         * most recent sighting wins and the time stays useful. Most recent by when it was heard,
         * not by when it came in: a song named later, from a listen kept with no connection
         * (RecogniseLater), goes where it was heard, and one heard again since then keeps that
         * newer sighting. The same moment saved twice takes the second: the engine saves a song as
         * Shazam names it, and again with the recording it chose once it is confirmed.
         */
        internal fun merged(entries: List<Heard>, entry: Heard, limit: Int): List<Heard> {
            fun isSame(it: Heard) = it.title == entry.title && it.artist == entry.artist
            if (entries.any { isSame(it) && it.at > entry.at }) return entries.take(limit)
            return (entries.filterNot(::isSame) + entry).sortedByDescending { it.at }.take(limit)
        }
    }
}

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
import org.json.JSONArray
import org.json.JSONObject
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

    companion object {
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
 * that carries the person's entire library. org.json rather than kotlinx.serialization because the
 * app module does not apply the serialization plugin, which is the same reason PollChecker parses
 * by hand.
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
        parse(prefs[RecognitionHistoryKey])
    }

    suspend fun add(entry: Heard) {
        context.dataStore.edit { prefs ->
            val current = parse(prefs[RecognitionHistoryKey])
            // Re-hearing the same song as it keeps playing should not fill the list with it. The
            // most recent sighting wins, so the timestamp stays useful.
            val deduped = current.filterNot { it.title == entry.title && it.artist == entry.artist }
            prefs[RecognitionHistoryKey] = write((listOf(entry) + deduped).take(limit))
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.remove(RecognitionHistoryKey) }
    }

    private fun parse(raw: String?): List<Heard> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Heard(
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    videoId = o.optString("videoId").takeIf { it.isNotEmpty() },
                    at = o.optLong("at"),
                    // Missing from every entry saved before 0.12.5, which is what tells them apart.
                    checked = o.optBoolean("checked", false),
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

    private fun write(entries: List<Heard>): String {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("title", it.title)
                    .put("artist", it.artist)
                    .put("videoId", it.videoId ?: "")
                    .put("at", it.at)
                    .put("checked", it.checked)
            )
        }
        return array.toString()
    }
}

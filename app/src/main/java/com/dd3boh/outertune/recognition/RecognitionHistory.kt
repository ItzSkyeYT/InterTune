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
import com.dd3boh.outertune.utils.dataStore
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
 * which is a real and fairly common outcome. The entry is still worth keeping: knowing the app
 * heard something at a particular moment is most of the value, and the song can be searched for
 * by name later even when nothing was playable at the time.
 */
data class Heard(
    val title: String,
    val artist: String,
    val videoId: String?,
    val at: Long,
)

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
            )
        }
        return array.toString()
    }
}

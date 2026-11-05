/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import android.content.Context
import android.util.Log

/**
 * The artists on the hand-made lists of AI music that the AI filter goes by (AiFilter), read from
 * the app's asset [ASSET]. No AI label can be read from YouTube's answers yet (TODO section 17), so
 * this is a list, not detection.
 *
 * The asset is written by tools/ai-filter/build_list.py, outside the app: YouTube channel ids, one
 * a line, each under the `[name]` of its source, and comment lines starting with `#` that name the
 * sources and the day the list was built. Whatever follows an id on its line is a note (an act's
 * name) and is not read. A line that is not a channel id is left out and counted, so a damaged
 * line costs that line and nothing more.
 *
 * Bundled for now; to be fetched from intertune.app later, which is why it is a file of its own
 * and not a table in the code.
 */
data class AiArtistList(
    /** Every channel id on the list. */
    val ids: Set<String>,
    /** How many ids each source gave, in the file's order. */
    val sources: Map<String, Int>,
    /** The day the list was built, as the file says it (YYYY-MM-DD), or null. */
    val built: String?,
    /** Lines that should have been a channel id and were not. */
    val unreadable: Int,
) {
    companion object {
        const val ASSET = "ai_artists.txt"

        val EMPTY = AiArtistList(emptySet(), emptyMap(), null, 0)

        /** A YouTube channel id: "UC" and 22 more characters of the url-safe base 64 alphabet. */
        private val CHANNEL_ID = Regex("^UC[0-9A-Za-z_-]{22}$")

        fun isChannelId(id: String) = CHANNEL_ID.matches(id)

        fun parse(text: String): AiArtistList {
            val ids = HashSet<String>()
            val sources = LinkedHashMap<String, Int>()
            var source = ""
            var built: String? = null
            var unreadable = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#") -> {
                        if (built == null) built = Regex("""^#\s*built\s+(\d{4}-\d{2}-\d{2})""").find(line)?.groupValues?.get(1)
                    }
                    line.startsWith("[") && line.endsWith("]") -> {
                        source = line.substring(1, line.length - 1).trim()
                        sources.getOrPut(source) { 0 }
                    }
                    else -> {
                        val id = line.substringBefore(' ').substringBefore('\t')
                        if (isChannelId(id)) {
                            if (ids.add(id)) sources[source] = (sources[source] ?: 0) + 1
                        } else {
                            unreadable++
                        }
                    }
                }
            }
            return AiArtistList(ids, sources, built, unreadable)
        }

        /** The bundled list, or [EMPTY] when it cannot be read. Reads a file: not on the main thread. */
        fun load(context: Context): AiArtistList = runCatching {
            context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
        }.onFailure { Log.w("AiArtistList", "Could not read the list of AI artists", it) }.getOrDefault(EMPTY)
    }
}

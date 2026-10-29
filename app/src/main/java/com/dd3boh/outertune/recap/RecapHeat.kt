/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One stretch of a song in YouTube's Most replayed heat map: where it starts, how long it is, how replayed (0 to 1). */
data class HeatMarker(val startMs: Long, val lengthMs: Long, val score: Double)

/**
 * Where a song starts under a slide of the Recap: at the highest point of YouTube's Most replayed
 * heat map after its first 5% (everyone replays the start), and where the piece of it would have
 * started ([RecapPiece.fromMs], a third of the way in) when there is no heat map to go by: a song
 * under about 50,000 views, a local file, an answer that did not come. Measured on 31 songs on 25
 * Oct 2026, that peak sits at a chorus start in about half of them, a second or so before it more
 * often than after (experiments/research/ai-label-and-best-parts-1025/FINDINGS.md, part 2).
 *
 * The heat map comes from one masked WEB `next` per song (InnerTube.heatMap): 100 markers of
 * startMillis, durationMillis and intensityScoreNormalized, each song scored against its own
 * highest. The markers, never the "Most replayed" label beside them, which is not the peak.
 */
object RecapHeat {
    /** The first part of a song not looked at for its peak. */
    const val SKIPPED_SHARE = 0.05

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The heat map in YouTube's answer [text], in time order; null when it has none (the masked
     * path comes back empty for a video under about 50,000 views) or the text is not an answer.
     */
    fun markers(text: String): List<HeatMarker>? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val mutations = root.at("frameworkUpdates")?.at("entityBatchUpdate")?.get("mutations") as? JsonArray ?: return null
        for (mutation in mutations) {
            val list = (mutation as? JsonObject)?.at("payload")?.at("macroMarkersListEntity")?.at("markersList") ?: continue
            if (list.string("markerType") != "MARKER_TYPE_HEATMAP") continue
            val markers = (list["markers"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.marker() }.orEmpty()
            if (markers.isNotEmpty()) return markers.sortedBy { it.startMs }
        }
        return null
    }

    /**
     * Where the highest point of [markers] starts, after the first [SKIPPED_SHARE] of the song,
     * and leaving at least [keepMs] of it after that point, so that a slide is never left without
     * its song for a peak in the last seconds. Of equal points, the first. Null when no marker
     * qualifies.
     */
    fun peakMs(markers: List<HeatMarker>, keepMs: Long = 0L): Long? {
        if (markers.isEmpty()) return null
        val end = markers.maxOf { it.startMs + it.lengthMs }
        val from = (end * SKIPPED_SHARE).toLong()
        val best = markers
            .filter { it.startMs >= from && it.startMs <= end - keepMs }
            .maxWithOrNull(compareBy<HeatMarker> { it.score }.thenByDescending { it.startMs })
        return best?.startMs
    }

    /**
     * Where a song plays from when its slide comes up: the heat map's peak when [markers] has one
     * that qualifies, [piece]'s start otherwise. A local file has no heat map and is never asked
     * for one ([local]).
     */
    fun startMs(piece: RecapPiece, local: Boolean, markers: List<HeatMarker>?): Long {
        if (local || markers == null) return piece.fromMs
        return peakMs(markers, keepMs = piece.lengthMs) ?: piece.fromMs
    }

    private fun JsonObject.at(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    /** One marker; the times are strings in the answer, the score a number. */
    private fun JsonObject.marker(): HeatMarker? {
        fun long(key: String) = this[key]?.let { runCatching { it.jsonPrimitive.longOrNull ?: it.jsonPrimitive.contentOrNull?.toLongOrNull() }.getOrNull() }
        val start = long("startMillis") ?: return null
        val length = long("durationMillis") ?: return null
        val score = this["intensityScoreNormalized"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: return null
        if (start < 0 || length <= 0) return null
        return HeatMarker(start, length, score)
    }
}

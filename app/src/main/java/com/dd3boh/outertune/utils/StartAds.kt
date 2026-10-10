/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How long a web client's stream address is held back by the ad its answer puts before the song.
 *
 * YouTube's own page plays that ad first and only then fetches the song, and the stream host goes
 * by it: an address fetched sooner than the ad could have ended is refused with 403, with a good
 * po token or without one. The experiment's first plays checked the address one to five seconds
 * after the answer (9 Oct 2026) and were refused every time, which this may be all there was to.
 *
 * The answer is read as yt-dlp reads it (_get_available_at_timestamp, 51bab8a011): every ad placed
 * at the start, or in a slot before the content, counts for its length, or for the time after
 * which it can be skipped when it says so, and the times add up. The app plays no ad and shows
 * none. It only waits as long as a page that did would have been busy.
 *
 * An app client's address (VISIONOS) is not held back, and nothing here is asked about one.
 */
object StartAds {
    /** How long the first try is always followed by a second, whatever the answer says: yt-dlp's default wait. */
    const val SETTLE_MS = 6_000L

    /** A second past the ad, since the host's clock and the phone's are two clocks. */
    private const val MARGIN_MS = 1_000L

    /** No song is held longer than this for an ad it is not going to show. */
    const val LONGEST_MS = 30_000L

    /** The time the ads before the song take, in milliseconds. Zero when the answer names none. */
    fun waitMs(adPlacements: List<JsonObject>?, adSlots: List<JsonObject>?): Long =
        (renderers(adPlacements, adSlots).sumOf { seconds(it) ?: 0.0 } * 1000).toLong()

    /**
     * The pauses before each try of an address whose answer holds it back for [waitMs]: at once,
     * [SETTLE_MS] after that, and once more when the ad would run longer than that, a second past
     * its end and never beyond [LONGEST_MS] in all.
     */
    fun pausesMs(waitMs: Long): List<Long> {
        val untilAdIsOver = minOf(waitMs + MARGIN_MS, LONGEST_MS) - SETTLE_MS
        return listOfNotNull(0L, SETTLE_MS, untilAdIsOver.takeIf { waitMs > 0 && it > 0 })
    }

    private fun renderers(adPlacements: List<JsonObject>?, adSlots: List<JsonObject>?): List<JsonObject> {
        val placed = adPlacements.orEmpty()
            .filter { it.at("adPlacementRenderer", "config", "adPlacementConfig", "kind").text() == "AD_PLACEMENT_KIND_START" }
            .map { it.at("adPlacementRenderer", "renderer", "instreamVideoAdRenderer") }
        val slotted = adSlots.orEmpty()
            .filter { it.at("adSlotRenderer", "adSlotMetadata", "triggerEvent").text() == "SLOT_TRIGGER_EVENT_BEFORE_CONTENT" }
            .flatMap { slot ->
                val content = slot.at("adSlotRenderer", "fulfillmentContent", "fulfilledLayout", "playerBytesAdLayoutRenderer", "renderingContent")
                val inARow = (content.at("playerBytesSequentialLayoutRenderer", "sequentialLayouts") as? JsonArray).orEmpty()
                    .map { it.at("playerBytesAdLayoutRenderer", "renderingContent", "instreamVideoAdRenderer") }
                listOf(content.at("instreamVideoAdRenderer")) + inARow
            }
        return (placed + slotted).filterIsInstance<JsonObject>()
    }

    /** The time one ad holds the song back: until it can be skipped, or else its whole length. Null when it says neither. */
    private fun seconds(ad: JsonObject): Double? {
        ad["skipOffsetMilliseconds"].text()?.toDoubleOrNull()?.let { return it / 1000 }
        // playerVars is a query string, and its last length_seconds is the one yt-dlp takes.
        return ad["playerVars"].text()?.split('&')
            ?.lastOrNull { it.substringBefore('=') == "length_seconds" }
            ?.substringAfter('=', "")?.toIntOrNull()?.toDouble()
    }

    private fun JsonElement?.at(vararg path: String): JsonElement? = path.fold(this) { here, name -> (here as? JsonObject)?.get(name) }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
}

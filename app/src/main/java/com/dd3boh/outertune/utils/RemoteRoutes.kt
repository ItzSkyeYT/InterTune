/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The order of the chain's clients and a kill switch for the new recovery, set from afar: the
 * "playback" block of the document the questions and the news come in (see PollChecker).
 *
 * The day YouTube closes a client, every phone used to find it out for itself, song after song,
 * until a release came. With this the maintainer can put another client first or take one out of
 * the chain for a few weeks, and turn the new recovery off if it misbehaves in the field. It can
 * never take a safeguard away: a chain left without a client that serves whole songs is refused,
 * the three switches only turn new code off, and the block expires by itself.
 *
 * ```
 * "playback": {
 *   "v": 1, "id": "2026-10-21-visionos-closed",
 *   "minVersionCode": 96, "maxVersionCode": 120, "until": "2026-11-15",
 *   "order": ["VISIONOS_104", "VISIONOS_SAFARI", "WEB_REMIX", "VISIONOS", "IOS"],
 *   "disabled": ["VISIONOS"],
 *   "hedge": true, "uncheckedPlay": true, "watchdog": true
 * }
 * ```
 *
 * Older versions read only "polls" and "announcements", and pass over this block.
 */
object RemoteRoutes {
    private const val TAG = "YTPlayerUtils"

    /** Larger than this, the document is not read for it at all. */
    const val MOST_BYTES = 256 * 1024

    /** Without an "until", a block lasts this long from the fetch. */
    const val DEFAULT_DAYS = 14L

    /** However far its "until" is, a block lasts no longer than this from the fetch. */
    const val MOST_DAYS = 45L

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MOST_NAMES = 16
    private val ID = Regex("""[A-Za-z0-9._-]{1,64}""")

    /** The clients this build knows by name, as the walk names them. */
    val KNOWN = setOf("ANDROID_VR", "VISIONOS", "VISIONOS_104", "VISIONOS_SAFARI", "WEB_REMIX", "IOS", "ANDROID")

    class Block(
        val id: String,
        val order: List<String>,
        val disabled: List<String>,
        val until: Long,
        val hedge: Boolean,
        val uncheckedPlay: Boolean,
        val watchdog: Boolean,
    )

    @Volatile
    var current: Block? = null

    private fun active(now: Long = System.currentTimeMillis()): Block? = current?.takeIf { now < it.until }

    /** The remote switches, each on unless a block in force says off. */
    val hedge: Boolean get() = active()?.hedge ?: true
    val uncheckedPlay: Boolean get() = active()?.uncheckedPlay ?: true
    val watchdog: Boolean get() = active()?.watchdog ?: true

    /**
     * The block in [json], or null for none worth taking: no block, a document that is not JSON
     * or too large, another version of the block, a build outside its bounds, one expired. A
     * field of the wrong type is dropped, not the block. [notes] gets a line for each thing left
     * out, for the log.
     */
    fun parse(json: String, versionCode: Int, fetchedAt: Long, now: Long, notes: MutableList<String> = mutableListOf()): Block? {
        if (json.length > MOST_BYTES) return null
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val block = root["playback"] as? JsonObject ?: return null
        if ((block["v"] as? JsonPrimitive)?.intOrNull != 1) return null.also { notes += "not version 1" }
        val id = (block["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { ID.matches(it) }
            ?: return null.also { notes += "no id" }
        val min = (block["minVersionCode"] as? JsonPrimitive)?.intOrNull ?: 0
        val max = (block["maxVersionCode"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE
        if (versionCode !in min..max) return null
        val latest = fetchedAt + MOST_DAYS * DAY_MS
        val until = (block["until"] as? JsonPrimitive)?.let { AnnouncementParser.parseTime(it, endOfDay = true) }
            ?.coerceAtMost(latest)
            ?: (fetchedAt + DEFAULT_DAYS * DAY_MS)
        if (until <= now) return null

        fun names(field: String): List<String> {
            val array = block[field] as? JsonArray ?: return emptyList<String>().also { if (field in block) notes += "$field is not a list" }
            val all = array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val unknown = all.filterNot { it in KNOWN }
            if (unknown.isNotEmpty()) notes += "$field: not known here: ${unknown.take(MOST_NAMES).joinToString(" ")}"
            return all.filter { it in KNOWN }.distinct().take(MOST_NAMES)
        }
        fun switch(field: String): Boolean = (block[field] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: true

        var disabled = names("disabled")
        if (StreamCheck.FULL_STREAM.all { it in disabled }) {
            notes += "it disabled every full-stream client"
            disabled = emptyList()
        }
        return Block(id, names("order"), disabled, until, switch("hedge"), switch("uncheckedPlay"), switch("watchdog"))
    }

    /** Reads [json] and puts its block in force, or none; says so in the log. */
    fun read(json: String?, versionCode: Int, fetchedAt: Long, now: Long = System.currentTimeMillis()) {
        val notes = mutableListOf<String>()
        val block = json?.let { parse(it, versionCode, fetchedAt, now, notes) }
        val was = current
        current = block
        for (note in notes) Log.w(TAG, StepLog.remoteIgnored(note))
        if (block != null && block.id != was?.id) Log.i(TAG, StepLog.remoteApplied(block.id, block.order, block.disabled))
    }

    /**
     * The chain [written], as the walk names its clients, in the order [block] gives and without
     * the clients it disables. The clients it names go first in its order, the rest after them as
     * written, so a client new in a later build is never lost. A disabled list that would leave
     * no client that serves whole songs is not applied.
     */
    fun applyTo(written: List<String>, block: Block?): List<String> {
        if (block == null) return written
        val named = block.order.filter { it in written }
        val ordered = named + written.filterNot { it in named }
        val kept = ordered.filterNot { it in block.disabled }
        if (kept.none { it in StreamCheck.FULL_STREAM }) {
            if (block.disabled.isNotEmpty()) Log.w(TAG, StepLog.remoteIgnored("its disabled list would leave this chain without a full-stream client"))
            return ordered
        }
        return kept
    }

    /** [applyTo] for the block in force now. */
    fun applyNow(written: List<String>): List<String> = applyTo(written, active())
}

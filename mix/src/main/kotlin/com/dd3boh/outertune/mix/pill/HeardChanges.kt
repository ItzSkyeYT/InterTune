/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.pill

import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.SongPair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What Auto did between two songs the last time the change was heard, so the pill names it from
 * then on (DESIGN.md 2.4: a pair says Auto until it has been heard or prepared). Nothing is read
 * ahead to fill it. Auto may choose otherwise another time (it keeps Smooth to one change in
 * three), so this is what was heard, until the analysis cache can say what will be.
 *
 * Only what belongs to the pair is kept: a mix, or a plain change for the pair's own reason. A
 * reason of the moment (casting, a sleep timer, a read that came late) is not. The oldest pairs
 * go first past [max]. A cache: the app keeps it under its cache folder, and losing it costs
 * nothing but the names until the songs are heard again.
 */
class HeardChanges(private val max: Int = MAX) : PairPlanner {

    private val pairs = LinkedHashMap<SongPair, AutoChoice>()

    val size: Int @Synchronized get() = pairs.size

    @Synchronized
    override fun auto(pair: SongPair): AutoChoice? = pairs[pair]

    /** Keeps [choice] for [pair] when it belongs to the pair; true when it was kept. */
    @Synchronized
    fun put(pair: SongPair, choice: AutoChoice): Boolean {
        if (!keeps(choice)) return false
        pairs.remove(pair)
        pairs[pair] = choice
        while (pairs.size > max) pairs.remove(pairs.keys.first())
        return true
    }

    @Synchronized
    fun encode(): String = JSON.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("v", VERSION)
        put("pairs", buildJsonArray {
            for ((pair, choice) in pairs) {
                add(buildJsonObject {
                    put("from", pair.from)
                    put("to", pair.to)
                    when (choice) {
                        is AutoChoice.Mixed -> {
                            put("preset", choice.preset.key)
                            put("bars", choice.length.bars)
                            put("seconds", choice.length.seconds)
                        }
                        is AutoChoice.Plain -> put("plain", choice.reason.name.lowercase())
                    }
                })
            }
        })
    })

    companion object {
        const val VERSION = 1
        const val MAX = 500

        private val JSON = Json { prettyPrint = false }

        /** The reasons that belong to a pair rather than to the moment it was heard. */
        private val PAIR_REASONS = setOf(PlainReason.NO_FIT, PlainReason.FORMATS, PlainReason.RUN_TOGETHER, PlainReason.SHORT_SONG, PlainReason.SAME_SONG)

        fun keeps(choice: AutoChoice): Boolean = choice is AutoChoice.Mixed || (choice is AutoChoice.Plain && choice.reason in PAIR_REASONS)

        /** The memory in [text]; empty when it cannot be read or is of another version, and without any entry it cannot read. */
        fun decode(text: String, max: Int = MAX): HeardChanges {
            val heard = HeardChanges(max)
            val root = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return heard
            if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) return heard
            val list = runCatching { root["pairs"]?.jsonArray }.getOrNull() ?: return heard
            for (entry in list) {
                val o = entry as? JsonObject ?: continue
                runCatching {
                    val pair = SongPair(o["from"]!!.jsonPrimitive.content, o["to"]!!.jsonPrimitive.content)
                    val plain = o["plain"]?.jsonPrimitive?.content
                    val choice = if (plain != null) {
                        AutoChoice.Plain(PlainReason.valueOf(plain.uppercase()))
                    } else {
                        val preset = Preset.of(o["preset"]!!.jsonPrimitive.content) ?: return@runCatching
                        AutoChoice.Mixed(preset, PillLength(o["bars"]?.jsonPrimitive?.intOrNull ?: 0, o["seconds"]?.jsonPrimitive?.intOrNull ?: 0))
                    }
                    heard.put(pair, choice)
                }
            }
            return heard
        }
    }
}
